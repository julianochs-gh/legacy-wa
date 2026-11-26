package main

import (
	"context"
	"errors"
	"net/http"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/types"
)

// normChat maps hidden-user (LID) chat IDs to the phone-number JID when known,
// so one person never ends up as two chats and contact names can be found.
func (a *App) normChat(jid types.JID) types.JID {
	jid = jid.ToNonAD()
	if jid.Server != types.HiddenUserServer {
		return jid
	}
	cli := a.client()
	if cli == nil || cli.Store.LIDs == nil {
		return jid
	}
	pn, err := cli.Store.LIDs.GetPNForLID(context.Background(), jid)
	if err == nil && !pn.IsEmpty() {
		return pn.ToNonAD()
	}
	return jid
}

// contactName returns the address-book name synced from the main phone.
func (a *App) contactName(ctx context.Context, jid types.JID, allowPush bool) string {
	cli := a.client()
	if cli == nil {
		return ""
	}
	c, err := cli.Store.Contacts.GetContact(ctx, jid)
	if err != nil || !c.Found {
		if jid.Server == types.HiddenUserServer {
			if pn := a.normChat(jid); pn != jid {
				return a.contactName(ctx, pn, allowPush)
			}
		}
		return ""
	}
	names := []string{c.FullName, c.FirstName, c.BusinessName}
	if allowPush {
		names = append(names, c.PushName)
	}
	for _, n := range names {
		if n != "" {
			return n
		}
	}
	return ""
}

var refreshPending int32

// scheduleNameRefresh re-reads contact names for all 1:1 chats a few seconds
// after contacts changed (contact sync comes in many small events).
func (a *App) scheduleNameRefresh() {
	if !atomic.CompareAndSwapInt32(&refreshPending, 0, 1) {
		return
	}
	go func() {
		time.Sleep(4 * time.Second)
		atomic.StoreInt32(&refreshPending, 0)
		a.refreshNames()
	}()
}

func (a *App) refreshNames() {
	if a.db == nil || a.client() == nil {
		return
	}
	rows, err := a.db.Query(`SELECT jid, name FROM chats`)
	if err != nil {
		return
	}
	type row struct{ jid, name string }
	var list []row
	for rows.Next() {
		var r row
		if rows.Scan(&r.jid, &r.name) == nil {
			list = append(list, r)
		}
	}
	rows.Close()
	ctx := context.Background()
	changed := false
	for _, r := range list {
		jid, err := types.ParseJID(r.jid)
		if err != nil || jid.Server == types.GroupServer {
			if err == nil && r.name == "" {
				a.queueName(r.jid)
			}
			continue
		}
		if n := a.contactName(ctx, jid, true); n != "" && n != r.name {
			a.db.Exec(`UPDATE chats SET name=? WHERE jid=?`, n, r.jid)
			changed = true
		}
	}
	if changed {
		a.hub.Publish(Event{Type: "chats"})
	}
}

// GET /contacts -> WhatsApp contacts synced from the main phone's address book
func (a *App) hContacts(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	all, err := cli.Store.Contacts.GetAllContacts(r.Context())
	if err != nil {
		fail(w, 500, err)
		return
	}
	type contact struct {
		JID    string `json:"jid"`
		Name   string `json:"name"`
		Number string `json:"number"`
	}
	seen := map[string]bool{}
	out := []contact{}
	for jid, c := range all {
		name := c.FullName
		if name == "" {
			name = c.FirstName
		}
		if name == "" {
			name = c.BusinessName
		}
		if name == "" {
			continue // not in the address book
		}
		jid = a.normChat(jid)
		if jid.Server != types.DefaultUserServer && jid.Server != types.HiddenUserServer {
			continue
		}
		if seen[jid.String()] {
			continue
		}
		seen[jid.String()] = true
		num := ""
		if jid.Server == types.DefaultUserServer {
			num = "+" + jid.User
		}
		out = append(out, contact{JID: jid.String(), Name: name, Number: num})
	}
	sort.Slice(out, func(i, j int) bool {
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	writeJSON(w, out)
}

// POST /contacts/refresh -> ask the main phone for a full contact resync
func (a *App) hContactsRefresh(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	if err := cli.FetchAppState(ctx, appstate.WAPatchCriticalUnblockLow, true, false); err != nil {
		fail(w, 502, err)
		return
	}
	a.refreshNames()
	writeJSON(w, map[string]bool{"ok": true})
}

// POST /resolve {"phone": "..."} -> JID if the number uses WhatsApp
func (a *App) hResolve(w http.ResponseWriter, r *http.Request) {
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
	if len(phone) < 7 {
		fail(w, 400, errors.New("Bitte Nummer mit Ländervorwahl eingeben, z. B. 491701234567"))
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	res, err := cli.IsOnWhatsApp(ctx, []string{"+" + phone})
	if err != nil {
		fail(w, 502, err)
		return
	}
	for _, x := range res {
		if x.IsIn {
			jid := x.JID.ToNonAD()
			name := a.contactName(ctx, jid, true)
			if name == "" {
				name = "+" + jid.User
			}
			writeJSON(w, map[string]string{"jid": jid.String(), "name": name})
			return
		}
	}
	fail(w, 404, errors.New("Diese Nummer nutzt kein WhatsApp"))
}
