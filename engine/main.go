// LegacyWA engine: runs on the phone, logs in as a WhatsApp linked device
// (via whatsmeow) and exposes a tiny HTTP API on 127.0.0.1 for the Android UI.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	qrcode "github.com/skip2/go-qrcode"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
	_ "modernc.org/sqlite"
)

const exitRestart = 3

// ---------------------------------------------------------------- events hub

type Msg struct {
	ID         string `json:"id"`
	Chat       string `json:"chat"`
	ChatName   string `json:"chatName,omitempty"`
	Sender     string `json:"sender"`
	SenderName string `json:"senderName"`
	Mine       bool   `json:"mine"`
	TS         int64  `json:"ts"`
	Text       string `json:"text"`
	State      int    `json:"state"` // mine: 1 sent, 2 delivered, 3 read; incoming: 0 unread, 3 read
}

type Event struct {
	Seq   int64    `json:"seq"`
	Type  string   `json:"type"` // message | receipt | chats | status
	Chat  string   `json:"chat,omitempty"`
	Msg   *Msg     `json:"msg,omitempty"`
	IDs   []string `json:"ids,omitempty"`
	State int      `json:"state,omitempty"`
}

type Hub struct {
	mu   sync.Mutex
	seq  int64
	buf  []Event
	wake chan struct{}
}

func newHub() *Hub { return &Hub{wake: make(chan struct{})} }

func (h *Hub) Publish(e Event) {
	h.mu.Lock()
	h.seq++
	e.Seq = h.seq
	h.buf = append(h.buf, e)
	if len(h.buf) > 1000 {
		h.buf = append([]Event(nil), h.buf[len(h.buf)-500:]...)
	}
	close(h.wake)
	h.wake = make(chan struct{})
	h.mu.Unlock()
}

func (h *Hub) Since(since int64) ([]Event, int64, chan struct{}) {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := []Event{}
	for _, e := range h.buf {
		if e.Seq > since {
			out = append(out, e)
		}
	}
	return out, h.seq, h.wake
}

// ---------------------------------------------------------------- app

type App struct {
	token string
	data  string
	hub   *Hub
	db    *sql.DB

	cliMu sync.RWMutex
	cli   *whatsmeow.Client

	mu      sync.Mutex
	state   string
	detail  string
	qrCode  string
	version string

	names  chan string
	queued sync.Map
}

func (a *App) client() *whatsmeow.Client {
	a.cliMu.RLock()
	defer a.cliMu.RUnlock()
	return a.cli
}

func (a *App) setState(state, detail string) {
	a.mu.Lock()
	changed := a.state != state || a.detail != detail
	a.state, a.detail = state, detail
	if state != "qr" {
		a.qrCode = ""
	}
	a.mu.Unlock()
	if changed {
		fmt.Printf("STATE %s %s\n", state, detail)
		a.hub.Publish(Event{Type: "status"})
	}
}

func (a *App) setQR(code string) {
	a.mu.Lock()
	a.state, a.detail, a.qrCode = "qr", "", code
	a.mu.Unlock()
	a.hub.Publish(Event{Type: "status"})
}

func main() {
	listen := flag.String("listen", "127.0.0.1:0", "listen address")
	data := flag.String("data", ".", "data directory")
	dns := flag.String("dns", "", "comma separated DNS servers")
	flag.Parse()

	setupDNS(*dns)
	if err := os.MkdirAll(*data, 0700); err != nil {
		fmt.Println("FATAL data dir:", err)
		os.Exit(1)
	}

	a := &App{
		token: os.Getenv("LEGACY_TOKEN"),
		data:  *data,
		hub:   newHub(),
		state: "starting",
		names: make(chan string, 4096),
	}

	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		fmt.Println("FATAL listen:", err)
		os.Exit(1)
	}
	go func() {
		if err := http.Serve(ln, a.routes()); err != nil {
			fmt.Println("FATAL http:", err)
			os.Exit(1)
		}
	}()
	fmt.Printf("ENGINE_READY %d\n", ln.Addr().(*net.TCPAddr).Port)

	go a.start()
	select {}
}

// Android has no /etc/resolv.conf, so Go's resolver would ask 127.0.0.1:53.
// Route all DNS queries to the servers the app passes in instead.
func setupDNS(list string) {
	var servers []string
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if s != "" && net.ParseIP(s) != nil {
			servers = append(servers, s)
		}
	}
	servers = append(servers, "8.8.8.8", "1.1.1.1", "9.9.9.9")
	var next uint32
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 4 * time.Second}
			start := int(atomic.AddUint32(&next, 1))
			var lastErr error
			for i := range servers {
				s := servers[(start+i)%len(servers)]
				c, err := d.DialContext(ctx, network, net.JoinHostPort(s, "53"))
				if err == nil {
					return c, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

func (a *App) start() {
	ctx := context.Background()
	var err error

	a.db, err = sql.Open("sqlite", "file:"+filepath.Join(a.data, "app.db")+"?_pragma=busy_timeout(5000)")
	if err == nil {
		a.db.SetMaxOpenConns(1)
		_, err = a.db.Exec(`
CREATE TABLE IF NOT EXISTS chats(jid TEXT PRIMARY KEY, name TEXT NOT NULL DEFAULT '', hint TEXT NOT NULL DEFAULT '',
  last TEXT NOT NULL DEFAULT '', ts INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS messages(chat TEXT NOT NULL, id TEXT NOT NULL, sender TEXT NOT NULL DEFAULT '',
  sender_name TEXT NOT NULL DEFAULT '', mine INTEGER NOT NULL DEFAULT 0, ts INTEGER NOT NULL DEFAULT 0,
  text TEXT NOT NULL DEFAULT '', state INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat, id));
CREATE INDEX IF NOT EXISTS messages_chat_ts ON messages(chat, ts);`)
	}
	if err != nil {
		a.setState("error", "Datenbank: "+err.Error())
		return
	}

	waDB, err := sql.Open("sqlite", "file:"+filepath.Join(a.data, "wa.db")+"?_pragma=foreign_keys(1)&_pragma=busy_timeout(5000)")
	if err != nil {
		a.setState("error", "Datenbank: "+err.Error())
		return
	}
	waDB.SetMaxOpenConns(1)
	container := sqlstore.NewWithDB(waDB, "sqlite3", waLog.Stdout("DB", "WARN", false))
	if err := container.Upgrade(ctx); err != nil {
		a.setState("error", "Datenbank-Upgrade: "+err.Error())
		return
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		a.setState("error", "Gerätespeicher: "+err.Error())
		return
	}

	store.SetOSInfo("LegacyWA", [3]uint32{1, 0, 0})
	a.updateVersion(ctx)

	cli := whatsmeow.NewClient(device, waLog.Stdout("WA", "INFO", false))
	cli.AddEventHandler(a.onEvent)
	a.cliMu.Lock()
	a.cli = cli
	a.cliMu.Unlock()

	go a.nameWorker()
	a.connect()
}

// WhatsApp rejects clients that report an old web version; fetch the current one.
func (a *App) updateVersion(ctx context.Context) {
	for i := 0; i < 3; i++ {
		c, cancel := context.WithTimeout(ctx, 20*time.Second)
		v, err := whatsmeow.GetLatestVersion(c, http.DefaultClient)
		cancel()
		if err == nil && v != nil {
			store.SetWAVersion(*v)
			a.mu.Lock()
			a.version = v.String()
			a.mu.Unlock()
			fmt.Println("WA version", v.String())
			return
		}
		fmt.Println("could not fetch WA version:", err)
		time.Sleep(3 * time.Second)
	}
}

func (a *App) connect() {
	cli := a.client()
	if cli.Store.ID == nil {
		a.setState("connecting", "Verbinde für Kopplung…")
		qrChan, err := cli.GetQRChannel(context.Background())
		if err != nil {
			a.setState("error", "QR: "+err.Error())
			return
		}
		if err := cli.Connect(); err != nil {
			a.setState("error", "Verbindung fehlgeschlagen: "+err.Error())
			go func() { time.Sleep(10 * time.Second); a.connect() }()
			return
		}
		go func() {
			for item := range qrChan {
				switch item.Event {
				case "code":
					a.setQR(item.Code)
				case "success":
					a.setState("connecting", "Gekoppelt, synchronisiere…")
				case "timeout":
					a.setState("qr_timeout", "QR-Code abgelaufen")
				default:
					msg := item.Event
					if item.Error != nil {
						msg += ": " + item.Error.Error()
					}
					a.setState("error", "Kopplung: "+msg)
				}
			}
		}()
		return
	}
	for {
		a.setState("connecting", "Verbinde…")
		err := cli.Connect()
		if err == nil {
			return
		}
		a.setState("reconnecting", "Keine Verbindung: "+err.Error())
		time.Sleep(15 * time.Second)
	}
}

func (a *App) onEvent(evt interface{}) {
	switch v := evt.(type) {
	case *events.Connected:
		a.setState("connected", "")
	case *events.PairSuccess:
		a.setState("connecting", "Gekoppelt, synchronisiere…")
	case *events.Disconnected:
		a.setState("reconnecting", "Verbindung verloren, verbinde neu…")
	case *events.LoggedOut:
		a.setState("logged_out", "Vom Haupt-Handy abgemeldet")
		go a.wipeAndRestart()
	case *events.ClientOutdated:
		a.setState("outdated", "WhatsApp verlangt eine neuere Version")
	case *events.StreamReplaced:
		a.setState("error", "Sitzung wurde an anderer Stelle geöffnet")
	case *events.TemporaryBan:
		a.setState("error", "Vorübergehend gesperrt: "+v.String())
	case *events.ConnectFailure:
		a.setState("error", fmt.Sprintf("Verbindung abgelehnt: %v %s", v.Reason, v.Message))
	case *events.Message:
		a.storeMessage(v, true)
	case *events.HistorySync:
		a.storeHistory(v)
	case *events.Receipt:
		state := 0
		switch v.Type {
		case types.ReceiptTypeDelivered:
			state = 2
		case types.ReceiptTypeRead, types.ReceiptTypeReadSelf:
			state = 3
		}
		if state == 0 || len(v.MessageIDs) == 0 {
			return
		}
		chat := v.Chat.String()
		ids := make([]string, 0, len(v.MessageIDs))
		for _, id := range v.MessageIDs {
			ids = append(ids, string(id))
			a.db.Exec(`UPDATE messages SET state=MAX(state,?) WHERE chat=? AND id=? AND mine=1`, state, chat, string(id))
		}
		a.hub.Publish(Event{Type: "receipt", Chat: chat, IDs: ids, State: state})
	}
}

func (a *App) wipeAndRestart() {
	time.Sleep(1500 * time.Millisecond)
	if a.db != nil {
		a.db.Close()
	}
	os.Remove(filepath.Join(a.data, "app.db"))
	os.Exit(exitRestart)
}

// ---------------------------------------------------------------- messages

func withCaption(label, caption string) string {
	if caption != "" {
		return label + " " + caption
	}
	return label
}

func textOf(m *waE2E.Message) string {
	if m == nil {
		return ""
	}
	switch {
	case m.GetConversation() != "":
		return m.GetConversation()
	case m.GetExtendedTextMessage() != nil:
		return m.GetExtendedTextMessage().GetText()
	case m.GetEphemeralMessage() != nil:
		return textOf(m.GetEphemeralMessage().GetMessage())
	case m.GetImageMessage() != nil:
		return withCaption("[Bild]", m.GetImageMessage().GetCaption())
	case m.GetVideoMessage() != nil:
		return withCaption("[Video]", m.GetVideoMessage().GetCaption())
	case m.GetAudioMessage() != nil:
		return "[Sprachnachricht]"
	case m.GetDocumentMessage() != nil:
		return withCaption("[Dokument]", m.GetDocumentMessage().GetFileName())
	case m.GetStickerMessage() != nil:
		return "[Sticker]"
	case m.GetLocationMessage() != nil:
		return "[Standort]"
	case m.GetLiveLocationMessage() != nil:
		return "[Live-Standort]"
	case m.GetContactMessage() != nil:
		return withCaption("[Kontakt]", m.GetContactMessage().GetDisplayName())
	case m.GetPollCreationMessage() != nil:
		return withCaption("[Umfrage]", m.GetPollCreationMessage().GetName())
	case m.GetViewOnceMessage() != nil, m.GetViewOnceMessageV2() != nil:
		return "[Einmal ansehen – bitte am Haupt-Handy öffnen]"
	case m.GetReactionMessage() != nil, m.GetProtocolMessage() != nil,
		m.GetSenderKeyDistributionMessage() != nil, m.GetPollUpdateMessage() != nil:
		return ""
	}
	return "[Nicht unterstützte Nachricht]"
}

func (a *App) storeMessage(evt *events.Message, live bool) {
	if a.db == nil || evt == nil {
		return
	}
	chat := evt.Info.Chat
	if chat.Server == types.BroadcastServer || chat.User == "status" {
		return
	}
	text := textOf(evt.Message)
	if text == "" {
		return
	}
	m := Msg{
		ID:         string(evt.Info.ID),
		Chat:       chat.String(),
		Sender:     evt.Info.Sender.ToNonAD().String(),
		SenderName: evt.Info.PushName,
		Mine:       evt.Info.IsFromMe,
		TS:         evt.Info.Timestamp.Unix(),
		Text:       text,
	}
	if m.Mine {
		m.State = 1
		m.SenderName = ""
	} else if !live {
		m.State = 3
	}
	if !a.insertMessage(&m, live && !m.Mine) {
		return
	}
	if !m.Mine && evt.Info.PushName != "" && chat.Server != types.GroupServer {
		a.db.Exec(`UPDATE chats SET hint=? WHERE jid=?`, evt.Info.PushName, m.Chat)
	}
	if live {
		m.ChatName = a.displayName(m.Chat)
		a.hub.Publish(Event{Type: "message", Chat: m.Chat, Msg: &m})
	}
}

func (a *App) insertMessage(m *Msg, countUnread bool) bool {
	res, err := a.db.Exec(`INSERT OR IGNORE INTO messages(chat,id,sender,sender_name,mine,ts,text,state) VALUES(?,?,?,?,?,?,?,?)`,
		m.Chat, m.ID, m.Sender, m.SenderName, boolInt(m.Mine), m.TS, m.Text, m.State)
	if err != nil {
		fmt.Println("insert message:", err)
		return false
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return false
	}
	preview := m.Text
	if m.Mine {
		preview = "Du: " + preview
	} else if strings.HasSuffix(m.Chat, "@"+types.GroupServer) && m.SenderName != "" {
		preview = m.SenderName + ": " + preview
	}
	unread := 0
	if countUnread {
		unread = 1
	}
	_, err = a.db.Exec(`INSERT INTO chats(jid,last,ts,unread) VALUES(?,?,?,?)
ON CONFLICT(jid) DO UPDATE SET
  last=CASE WHEN excluded.ts>=chats.ts THEN excluded.last ELSE chats.last END,
  ts=MAX(chats.ts, excluded.ts),
  unread=chats.unread+excluded.unread`, m.Chat, preview, m.TS, unread)
	if err != nil {
		fmt.Println("upsert chat:", err)
	}
	a.queueName(m.Chat)
	return true
}

func (a *App) storeHistory(v *events.HistorySync) {
	cli := a.client()
	if cli == nil || v.Data == nil {
		return
	}
	for _, conv := range v.Data.GetConversations() {
		chat, err := types.ParseJID(conv.GetID())
		if err != nil || chat.Server == types.BroadcastServer {
			continue
		}
		for _, hm := range conv.GetMessages() {
			evt, err := cli.ParseWebMessage(chat, hm.GetMessage())
			if err != nil {
				continue
			}
			a.storeMessage(evt, false)
		}
		if name := conv.GetName(); name != "" {
			a.db.Exec(`UPDATE chats SET name=? WHERE jid=?`, name, chat.String())
		}
		if n := conv.GetUnreadCount(); n > 0 {
			a.db.Exec(`UPDATE chats SET unread=? WHERE jid=?`, n, chat.String())
		}
	}
	a.hub.Publish(Event{Type: "chats"})
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// ---------------------------------------------------------------- names

func (a *App) queueName(jid string) {
	if _, loaded := a.queued.LoadOrStore(jid, true); loaded {
		return
	}
	select {
	case a.names <- jid:
	default:
		a.queued.Delete(jid)
	}
}

func (a *App) nameWorker() {
	ctx := context.Background()
	for jidStr := range a.names {
		var name string
		a.db.QueryRow(`SELECT name FROM chats WHERE jid=?`, jidStr).Scan(&name)
		if name != "" {
			continue
		}
		jid, err := types.ParseJID(jidStr)
		if err != nil {
			continue
		}
		cli := a.client()
		if jid.Server == types.GroupServer {
			for cli != nil && !cli.IsLoggedIn() {
				time.Sleep(5 * time.Second)
			}
			c, cancel := context.WithTimeout(ctx, 20*time.Second)
			info, err := cli.GetGroupInfo(c, jid)
			cancel()
			if err == nil && info != nil {
				name = info.Name
			} else {
				a.queued.Delete(jidStr) // retry later
			}
			time.Sleep(300 * time.Millisecond)
		} else {
			c, err := cli.Store.Contacts.GetContact(ctx, jid)
			if err == nil && c.Found {
				for _, n := range []string{c.FullName, c.FirstName, c.BusinessName, c.PushName} {
					if n != "" {
						name = n
						break
					}
				}
			}
		}
		if name != "" {
			a.db.Exec(`UPDATE chats SET name=? WHERE jid=?`, name, jidStr)
			a.hub.Publish(Event{Type: "chats"})
		}
	}
}

func (a *App) displayName(jid string) string {
	var name, hint string
	a.db.QueryRow(`SELECT name, hint FROM chats WHERE jid=?`, jid).Scan(&name, &hint)
	return pickName(jid, name, hint)
}

func pickName(jid, name, hint string) string {
	if name != "" {
		return name
	}
	if hint != "" {
		return hint
	}
	user := strings.SplitN(jid, "@", 2)[0]
	if strings.HasSuffix(jid, "@"+types.DefaultUserServer) {
		return "+" + user
	}
	if strings.HasSuffix(jid, "@"+types.GroupServer) {
		return "Gruppe"
	}
	return user
}

// ---------------------------------------------------------------- HTTP

func (a *App) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/status", a.hStatus)
	mux.HandleFunc("/qr.png", a.hQR)
	mux.HandleFunc("/pair/phone", a.hPairPhone)
	mux.HandleFunc("/pair/restart", a.hPairRestart)
	mux.HandleFunc("/chats", a.hChats)
	mux.HandleFunc("/messages", a.hMessages)
	mux.HandleFunc("/send", a.hSend)
	mux.HandleFunc("/read", a.hRead)
	mux.HandleFunc("/events", a.hEvents)
	mux.HandleFunc("/logout", a.hLogout)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if a.token != "" && r.Header.Get("X-Token") != a.token {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		mux.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, v interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, code int, err error) {
	http.Error(w, err.Error(), code)
}

func readBody(r *http.Request, v interface{}) error {
	return json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<20)).Decode(v)
}

func (a *App) needClient(w http.ResponseWriter) *whatsmeow.Client {
	cli := a.client()
	if cli == nil {
		fail(w, http.StatusServiceUnavailable, errors.New("Engine startet noch"))
	}
	return cli
}

func (a *App) hStatus(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	out := map[string]interface{}{
		"state":   a.state,
		"detail":  a.detail,
		"hasQR":   a.qrCode != "",
		"qrId":    strconv.Itoa(len(a.qrCode)) + ":" + tail(a.qrCode, 12),
		"version": a.version,
	}
	a.mu.Unlock()
	if cli := a.client(); cli != nil && cli.Store.ID != nil {
		out["loggedIn"] = true
		out["jid"] = cli.Store.ID.ToNonAD().String()
		out["name"] = cli.Store.PushName
	} else {
		out["loggedIn"] = false
	}
	_, seq, _ := a.hub.Since(1 << 62)
	out["seq"] = seq
	writeJSON(w, out)
}

func tail(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[len(s)-n:]
}

func (a *App) hQR(w http.ResponseWriter, r *http.Request) {
	a.mu.Lock()
	code := a.qrCode
	a.mu.Unlock()
	if code == "" {
		http.Error(w, "kein QR-Code", http.StatusNotFound)
		return
	}
	size, _ := strconv.Atoi(r.URL.Query().Get("size"))
	if size < 128 || size > 1024 {
		size = 400
	}
	png, err := qrcode.Encode(code, qrcode.Low, size)
	if err != nil {
		fail(w, 500, err)
		return
	}
	w.Header().Set("Content-Type", "image/png")
	w.Write(png)
}

func (a *App) hPairPhone(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	var req struct {
		Phone string `json:"phone"`
	}
	if err := readBody(r, &req); err != nil {
		fail(w, 400, err)
		return
	}
	phone := strings.Map(func(c rune) rune {
		if c >= '0' && c <= '9' {
			return c
		}
		return -1
	}, req.Phone)
	if strings.HasPrefix(phone, "00") {
		phone = phone[2:]
	}
	if len(phone) < 8 {
		fail(w, 400, errors.New("Bitte Nummer mit Ländervorwahl eingeben, z. B. 491701234567"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	code, err := cli.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		fail(w, 500, err)
		return
	}
	a.setState("pairing", "Code am Haupt-Handy eingeben")
	writeJSON(w, map[string]string{"code": code})
}

func (a *App) hPairRestart(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	cli.Disconnect()
	go a.connect()
	writeJSON(w, map[string]bool{"ok": true})
}

func (a *App) hChats(w http.ResponseWriter, r *http.Request) {
	if a.db == nil {
		writeJSON(w, []interface{}{})
		return
	}
	rows, err := a.db.Query(`SELECT jid,name,hint,last,ts,unread FROM chats ORDER BY ts DESC LIMIT 300`)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type chat struct {
		JID    string `json:"jid"`
		Name   string `json:"name"`
		Last   string `json:"last"`
		TS     int64  `json:"ts"`
		Unread int    `json:"unread"`
		Group  bool   `json:"group"`
	}
	out := []chat{}
	for rows.Next() {
		var c chat
		var name, hint string
		if rows.Scan(&c.JID, &name, &hint, &c.Last, &c.TS, &c.Unread) == nil {
			c.Name = pickName(c.JID, name, hint)
			c.Group = strings.HasSuffix(c.JID, "@"+types.GroupServer)
			out = append(out, c)
		}
	}
	writeJSON(w, out)
}

func (a *App) hMessages(w http.ResponseWriter, r *http.Request) {
	if a.db == nil {
		writeJSON(w, []interface{}{})
		return
	}
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	before, _ := strconv.ParseInt(q.Get("before"), 10, 64)
	if before <= 0 {
		before = 1 << 62
	}
	rows, err := a.db.Query(`SELECT id,chat,sender,sender_name,mine,ts,text,state FROM messages
WHERE chat=? AND ts<? ORDER BY ts DESC LIMIT ?`, q.Get("chat"), before, limit)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	out := []Msg{}
	for rows.Next() {
		var m Msg
		var mine int
		if rows.Scan(&m.ID, &m.Chat, &m.Sender, &m.SenderName, &mine, &m.TS, &m.Text, &m.State) == nil {
			m.Mine = mine == 1
			out = append(out, m)
		}
	}
	for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
		out[i], out[j] = out[j], out[i]
	}
	writeJSON(w, out)
}

func (a *App) hSend(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	var req struct {
		Chat string `json:"chat"`
		Text string `json:"text"`
	}
	if err := readBody(r, &req); err != nil {
		fail(w, 400, err)
		return
	}
	req.Text = strings.TrimSpace(req.Text)
	jid, err := types.ParseJID(req.Chat)
	if err != nil || req.Text == "" {
		fail(w, 400, errors.New("ungültiger Chat oder leere Nachricht"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 60*time.Second)
	defer cancel()
	resp, err := cli.SendMessage(ctx, jid, &waE2E.Message{Conversation: proto.String(req.Text)})
	if err != nil {
		fail(w, 502, fmt.Errorf("Senden fehlgeschlagen: %w", err))
		return
	}
	m := Msg{ID: string(resp.ID), Chat: jid.String(), Mine: true, TS: resp.Timestamp.Unix(), Text: req.Text, State: 1}
	if cli.Store.ID != nil {
		m.Sender = cli.Store.ID.ToNonAD().String()
	}
	if a.insertMessage(&m, false) {
		m.ChatName = a.displayName(m.Chat)
		a.hub.Publish(Event{Type: "message", Chat: m.Chat, Msg: &m})
	}
	writeJSON(w, m)
}

func (a *App) hRead(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil || a.db == nil {
		return
	}
	var req struct {
		Chat string `json:"chat"`
	}
	if err := readBody(r, &req); err != nil {
		fail(w, 400, err)
		return
	}
	chat, err := types.ParseJID(req.Chat)
	if err != nil {
		fail(w, 400, err)
		return
	}
	rows, err := a.db.Query(`SELECT id,sender FROM messages WHERE chat=? AND mine=0 AND state=0 ORDER BY ts DESC LIMIT 100`, req.Chat)
	if err != nil {
		fail(w, 500, err)
		return
	}
	bySender := map[string][]types.MessageID{}
	for rows.Next() {
		var id, sender string
		if rows.Scan(&id, &sender) == nil {
			bySender[sender] = append(bySender[sender], types.MessageID(id))
		}
	}
	rows.Close()
	a.db.Exec(`UPDATE messages SET state=3 WHERE chat=? AND mine=0 AND state=0`, req.Chat)
	a.db.Exec(`UPDATE chats SET unread=0 WHERE jid=?`, req.Chat)
	if cli.IsLoggedIn() {
		for sender, ids := range bySender {
			senderJID := types.EmptyJID
			if chat.Server == types.GroupServer {
				senderJID, _ = types.ParseJID(sender)
			}
			if err := cli.MarkRead(r.Context(), ids, time.Now(), chat, senderJID); err != nil {
				fmt.Println("mark read:", err)
			}
		}
	}
	a.hub.Publish(Event{Type: "chats"})
	writeJSON(w, map[string]bool{"ok": true})
}

func (a *App) hEvents(w http.ResponseWriter, r *http.Request) {
	since, err := strconv.ParseInt(r.URL.Query().Get("since"), 10, 64)
	if err != nil {
		since = -1
	}
	wait, _ := strconv.Atoi(r.URL.Query().Get("wait"))
	if wait < 0 || wait > 55 {
		wait = 25
	}
	evs, seq, wake := a.hub.Since(since)
	if since < 0 || since > seq {
		writeJSON(w, map[string]interface{}{"seq": seq, "events": []Event{}, "reset": true})
		return
	}
	if len(evs) == 0 && wait > 0 {
		select {
		case <-wake:
		case <-time.After(time.Duration(wait) * time.Second):
		case <-r.Context().Done():
			return
		}
		evs, seq, _ = a.hub.Since(since)
	}
	writeJSON(w, map[string]interface{}{"seq": seq, "events": evs})
}

func (a *App) hLogout(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	if cli.Store.ID != nil {
		ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
		err := cli.Logout(ctx)
		cancel()
		if err != nil {
			fmt.Println("logout:", err)
			cli.Store.Delete(context.Background())
		}
	}
	writeJSON(w, map[string]bool{"ok": true})
	go a.wipeAndRestart()
}
