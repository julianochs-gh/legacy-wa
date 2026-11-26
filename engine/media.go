package main

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

const maxUpload = 64 << 20

// fillMedia records what kind of attachment a message carries and keeps the
// serialized message so the file can be downloaded later on demand.
func fillMedia(m *Msg, msg *waE2E.Message) {
	if msg == nil {
		return
	}
	if e := msg.GetEphemeralMessage(); e != nil && e.GetMessage() != nil {
		msg = e.GetMessage()
	}
	switch {
	case msg.GetImageMessage() != nil:
		x := msg.GetImageMessage()
		m.Kind, m.Mime, m.HasThumb = "image", x.GetMimetype(), len(x.GetJPEGThumbnail()) > 0
	case msg.GetVideoMessage() != nil:
		x := msg.GetVideoMessage()
		m.Kind, m.Mime, m.HasThumb = "video", x.GetMimetype(), len(x.GetJPEGThumbnail()) > 0
	case msg.GetAudioMessage() != nil:
		x := msg.GetAudioMessage()
		m.Kind, m.Mime = "audio", x.GetMimetype()
		if x.GetPTT() {
			m.Kind = "ptt"
		}
	case msg.GetDocumentMessage() != nil:
		x := msg.GetDocumentMessage()
		m.Kind, m.Mime, m.FileName = "document", x.GetMimetype(), x.GetFileName()
		m.HasThumb = len(x.GetJPEGThumbnail()) > 0
	case msg.GetStickerMessage() != nil:
		m.Kind, m.Mime = "sticker", msg.GetStickerMessage().GetMimetype()
	default:
		return
	}
	m.Raw, _ = proto.Marshal(msg)
}

func downloadable(msg *waE2E.Message) whatsmeow.DownloadableMessage {
	switch {
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage()
	case msg.GetAudioMessage() != nil:
		return msg.GetAudioMessage()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage()
	case msg.GetStickerMessage() != nil:
		return msg.GetStickerMessage()
	}
	return nil
}

func thumbOf(msg *waE2E.Message) []byte {
	switch {
	case msg.GetImageMessage() != nil:
		return msg.GetImageMessage().GetJPEGThumbnail()
	case msg.GetVideoMessage() != nil:
		return msg.GetVideoMessage().GetJPEGThumbnail()
	case msg.GetDocumentMessage() != nil:
		return msg.GetDocumentMessage().GetJPEGThumbnail()
	}
	return nil
}

func (a *App) loadRaw(chat, id string) (*waE2E.Message, string, string, string, error) {
	var raw []byte
	var kind, mimeType, fname string
	err := a.db.QueryRow(`SELECT raw, kind, mime, fname FROM messages WHERE chat=? AND id=?`, chat, id).
		Scan(&raw, &kind, &mimeType, &fname)
	if err != nil {
		return nil, "", "", "", errors.New("Nachricht nicht gefunden")
	}
	if len(raw) == 0 {
		return nil, "", "", "", errors.New("keine Datei an dieser Nachricht")
	}
	var msg waE2E.Message
	if err := proto.Unmarshal(raw, &msg); err != nil {
		return nil, "", "", "", err
	}
	return &msg, kind, mimeType, fname, nil
}

func safeName(s string) string {
	return strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == 0 || r == ':' {
			return '_'
		}
		return r
	}, s)
}

func extFor(mimeType, kind string) string {
	base := strings.TrimSpace(strings.SplitN(mimeType, ";", 2)[0])
	switch base {
	case "image/jpeg":
		return ".jpg"
	case "audio/ogg":
		return ".ogg"
	case "audio/mp4", "audio/aac":
		return ".m4a"
	case "video/mp4":
		return ".mp4"
	case "image/webp":
		return ".webp"
	}
	if exts, _ := mime.ExtensionsByType(base); len(exts) > 0 {
		return exts[0]
	}
	switch kind {
	case "image":
		return ".jpg"
	case "video":
		return ".mp4"
	case "audio", "ptt":
		return ".ogg"
	}
	return ".bin"
}

func (a *App) mediaPath(id, mimeType, kind string) string {
	return filepath.Join(a.data, "media", safeName(id)+extFor(mimeType, kind))
}

// GET /thumb?chat=&id=  -> embedded JPEG preview
func (a *App) hThumb(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	msg, _, _, _, err := a.loadRaw(q.Get("chat"), q.Get("id"))
	if err != nil {
		fail(w, 404, err)
		return
	}
	t := thumbOf(msg)
	if len(t) == 0 {
		http.Error(w, "keine Vorschau", 404)
		return
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "max-age=86400")
	w.Write(t)
}

// GET /media?chat=&id=  -> the full file (downloaded from WhatsApp on first use)
func (a *App) hMedia(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	q := r.URL.Query()
	id := q.Get("id")
	msg, kind, mimeType, fname, err := a.loadRaw(q.Get("chat"), id)
	if err != nil {
		fail(w, 404, err)
		return
	}
	path := a.mediaPath(id, mimeType, kind)
	data, err := os.ReadFile(path)
	if err != nil {
		dm := downloadable(msg)
		if dm == nil {
			fail(w, 404, errors.New("nicht herunterladbar"))
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
		data, err = cli.Download(ctx, dm)
		cancel()
		if err != nil {
			fail(w, 502, fmt.Errorf("Download fehlgeschlagen: %w", err))
			return
		}
		os.WriteFile(path, data, 0600)
	}
	if fname == "" {
		fname = safeName(id) + extFor(mimeType, kind)
	}
	if mimeType == "" {
		mimeType = "application/octet-stream"
	}
	w.Header().Set("Content-Type", mimeType)
	w.Header().Set("X-File-Name", mime.QEncoding.Encode("utf-8", safeName(fname)))
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Write(data)
}

// POST /sendmedia?chat=&kind=&mime=&name=&caption=&w=&h=&seconds=
// Body: 4-byte big-endian thumbnail length, JPEG thumbnail, file bytes.
func (a *App) hSendMedia(w http.ResponseWriter, r *http.Request) {
	cli := a.needClient(w)
	if cli == nil {
		return
	}
	q := r.URL.Query()
	jid, err := types.ParseJID(q.Get("chat"))
	if err != nil {
		fail(w, 400, err)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, maxUpload+1))
	if err != nil {
		fail(w, 400, err)
		return
	}
	if len(body) > maxUpload {
		fail(w, 413, errors.New("Datei ist zu groß (max. 64 MB)"))
		return
	}
	if len(body) < 4 {
		fail(w, 400, errors.New("leere Datei"))
		return
	}
	tl := int(binary.BigEndian.Uint32(body[:4]))
	if 4+tl > len(body) {
		fail(w, 400, errors.New("ungültige Daten"))
		return
	}
	thumb := body[4 : 4+tl]
	data := body[4+tl:]
	if len(data) == 0 {
		fail(w, 400, errors.New("leere Datei"))
		return
	}
	if len(thumb) == 0 {
		thumb = nil
	}

	kind := q.Get("kind")
	mimeType := q.Get("mime")
	if mimeType == "" {
		mimeType = http.DetectContentType(data)
	}
	name := q.Get("name")
	caption := q.Get("caption")
	width, _ := strconv.Atoi(q.Get("w"))
	height, _ := strconv.Atoi(q.Get("h"))
	seconds, _ := strconv.Atoi(q.Get("seconds"))

	mt := whatsmeow.MediaDocument
	switch kind {
	case "image":
		mt = whatsmeow.MediaImage
	case "video":
		mt = whatsmeow.MediaVideo
	case "audio":
		mt = whatsmeow.MediaAudio
	default:
		kind = "document"
	}

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Minute)
	defer cancel()
	up, err := cli.Upload(ctx, data, mt)
	if err != nil {
		fail(w, 502, fmt.Errorf("Hochladen fehlgeschlagen: %w", err))
		return
	}
	size := proto.Uint64(uint64(len(data)))
	var capt *string
	if caption != "" {
		capt = proto.String(caption)
	}
	msg := &waE2E.Message{}
	text := ""
	switch kind {
	case "image":
		msg.ImageMessage = &waE2E.ImageMessage{
			Caption: capt, Mimetype: proto.String(mimeType),
			URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: size, JPEGThumbnail: thumb,
			Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
		}
		text = withCaption("[Bild]", caption)
	case "video":
		msg.VideoMessage = &waE2E.VideoMessage{
			Caption: capt, Mimetype: proto.String(mimeType),
			URL: proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: size, JPEGThumbnail: thumb, Seconds: proto.Uint32(uint32(seconds)),
			Width: proto.Uint32(uint32(width)), Height: proto.Uint32(uint32(height)),
		}
		text = withCaption("[Video]", caption)
	case "audio":
		msg.AudioMessage = &waE2E.AudioMessage{
			Mimetype: proto.String(mimeType),
			URL:      proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: size, Seconds: proto.Uint32(uint32(seconds)),
		}
		text = "[Audio]"
	default:
		if name == "" {
			name = "Datei" + extFor(mimeType, kind)
		}
		msg.DocumentMessage = &waE2E.DocumentMessage{
			Mimetype: proto.String(mimeType), FileName: proto.String(name), Title: proto.String(name),
			Caption: capt,
			URL:     proto.String(up.URL), DirectPath: proto.String(up.DirectPath),
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: size, JPEGThumbnail: thumb,
		}
		text = withCaption("[Dokument]", name)
	}

	resp, err := cli.SendMessage(ctx, jid, msg)
	if err != nil {
		fail(w, 502, fmt.Errorf("Senden fehlgeschlagen: %w", err))
		return
	}
	m := Msg{ID: string(resp.ID), Chat: jid.String(), Mine: true, TS: resp.Timestamp.Unix(), Text: text, State: 1}
	if cli.Store.ID != nil {
		m.Sender = cli.Store.ID.ToNonAD().String()
	}
	fillMedia(&m, msg)
	os.WriteFile(a.mediaPath(m.ID, m.Mime, m.Kind), data, 0600)
	if a.insertMessage(&m, false) {
		m.ChatName = a.displayName(m.Chat)
		a.hub.Publish(Event{Type: "message", Chat: m.Chat, Msg: &m})
	}
	writeJSON(w, m)
}
