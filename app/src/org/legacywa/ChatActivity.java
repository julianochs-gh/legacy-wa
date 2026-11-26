package org.legacywa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.LruCache;
import android.widget.ImageView;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class ChatActivity extends Activity {
    private final Handler ui = new Handler();
    private String jid;
    private boolean group;
    private volatile boolean active;
    private Thread poller;

    private ListView list;
    private EditText input;
    private Button send;
    private final MsgAdapter adapter = new MsgAdapter();
    private final ExecutorService thumbs = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> thumbCache = new LruCache<String, Bitmap>(80);
    private ProgressDialog busy;
    private File cameraFile;
    private MediaRecorder recorder;
    private File recordFile;
    private String recordMime;
    private long recordStart;

    private static final int REQ_GALLERY = 1, REQ_CAMERA = 2, REQ_VIDEO = 3, REQ_FILE = 4;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        jid = getIntent().getStringExtra("jid");
        group = jid != null && jid.endsWith("@g.us");
        String name = getIntent().getStringExtra("name");
        setTitle(name != null ? name : jid);
        getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFECE5DD);

        list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setStackFromBottom(true);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL);
        list.setCacheColorHint(Color.TRANSPARENT);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Color.WHITE);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button attach = new Button(this);
        attach.setText("+");
        attach.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAttachMenu();
            }
        });
        bar.addView(attach, new LinearLayout.LayoutParams(Ui.dp(this, 48), -2));
        input = new EditText(this);
        input.setHint("Nachricht");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        send = new Button(this);
        send.setText("Senden");
        send.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendMessage();
            }
        });
        bar.addView(send);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Api.activeChat = jid;
        EngineService.cancelChatNotification(this, jid);
        active = true;
        poller = new Thread(new Runnable() {
            public void run() {
                pollLoop();
            }
        }, "chat-poll");
        poller.start();
    }

    @Override
    protected void onPause() {
        active = false;
        if (jid.equals(Api.activeChat)) Api.activeChat = null;
        if (poller != null) poller.interrupt();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        stopRecorder();
        super.onDestroy();
    }

    // ------------------------------------------------------------ attachments

    private void showAttachMenu() {
        final String[] items = {"Foto aufnehmen", "Bild aus Galerie", "Video", "Datei / Dokument", "Sprachaufnahme"};
        new AlertDialog.Builder(this).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) {
                try {
                    switch (which) {
                        case 0:
                            File dir = new File(Environment.getExternalStorageDirectory(), "LegacyWA/Kamera");
                            dir.mkdirs();
                            cameraFile = new File(dir, "IMG_" + System.currentTimeMillis() + ".jpg");
                            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                            cam.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(cameraFile));
                            startActivityForResult(cam, REQ_CAMERA);
                            break;
                        case 1:
                            pick("image/*", REQ_GALLERY);
                            break;
                        case 2:
                            pick("video/*", REQ_VIDEO);
                            break;
                        case 3:
                            pick("*/*", REQ_FILE);
                            break;
                        case 4:
                            startRecording();
                            break;
                    }
                } catch (ActivityNotFoundException e) {
                    toast("Keine passende App auf dem Gerät gefunden");
                } catch (Exception e) {
                    toast(e.getMessage());
                }
            }
        }).show();
    }

    private void pick(String type, int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType(type);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Auswählen"), req);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (cameraFile != null) out.putString("cameraFile", cameraFile.getAbsolutePath());
    }

    @Override
    protected void onRestoreInstanceState(Bundle in) {
        super.onRestoreInstanceState(in);
        String cf = in.getString("cameraFile");
        if (cf != null) cameraFile = new File(cf);
    }

    @Override
    protected void onActivityResult(int req, int result, final Intent data) {
        super.onActivityResult(req, result, data);
        if (result != RESULT_OK) return;
        final Uri uri = req == REQ_CAMERA ? (cameraFile != null ? Uri.fromFile(cameraFile) : null)
                : (data != null ? data.getData() : null);
        if (uri == null) {
            toast("Keine Datei erhalten");
            return;
        }
        if (req == REQ_GALLERY || req == REQ_CAMERA || req == REQ_VIDEO) {
            askCaption(req, uri);
        } else {
            startUpload(req, uri, "");
        }
    }

    private void askCaption(final int req, final Uri uri) {
        final EditText cap = new EditText(this);
        cap.setHint("Bildunterschrift (optional)");
        new AlertDialog.Builder(this)
                .setTitle(req == REQ_VIDEO ? "Video senden" : "Bild senden")
                .setView(cap)
                .setPositiveButton("Senden", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        startUpload(req, uri, cap.getText().toString().trim());
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void startUpload(final int req, final Uri uri, final String caption) {
        showBusy("Wird vorbereitet…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    Media.Prepared p;
                    if (req == REQ_GALLERY || req == REQ_CAMERA) {
                        p = Media.image(ChatActivity.this, uri, req == REQ_CAMERA ? cameraFile : null);
                    } else if (req == REQ_VIDEO) {
                        p = Media.video(ChatActivity.this, uri);
                    } else {
                        p = Media.file(ChatActivity.this, uri);
                    }
                    upload(p, caption);
                } catch (final Throwable e) {
                    uploadFailed(e);
                }
            }
        }).start();
    }

    private void upload(Media.Prepared p, String caption) throws Exception {
        ui.post(new Runnable() {
            public void run() {
                showBusy("Wird gesendet…");
            }
        });
        String path = "/sendmedia?chat=" + Api.enc(jid) + "&kind=" + p.kind + "&mime=" + Api.enc(p.mime)
                + "&name=" + Api.enc(p.name == null ? "" : p.name) + "&caption=" + Api.enc(caption == null ? "" : caption)
                + "&w=" + p.width + "&h=" + p.height + "&seconds=" + p.seconds;
        final JSONObject m = new JSONObject(Api.upload(path, p.thumb, Media.open(this, p), p.length));
        ui.post(new Runnable() {
            public void run() {
                hideBusy();
                adapter.add(m);
                list.setSelection(adapter.getCount() - 1);
            }
        });
    }

    private void uploadFailed(final Throwable e) {
        ui.post(new Runnable() {
            public void run() {
                hideBusy();
                String msg = e instanceof OutOfMemoryError ? "Nicht genug Speicher für diese Datei" : e.getMessage();
                new AlertDialog.Builder(ChatActivity.this).setTitle("Senden fehlgeschlagen")
                        .setMessage(msg == null ? e.toString() : msg).setPositiveButton("OK", null).show();
            }
        });
    }

    // ------------------------------------------------------------ voice recording

    private void startRecording() {
        stopRecorder();
        recordFile = new File(getCacheDir(), "aufnahme_" + System.currentTimeMillis() + ".m4a");
        recorder = new MediaRecorder();
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(recordFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recordMime = "audio/mp4";
        } catch (Exception aacFailed) {
            // some old devices have no AAC encoder: fall back to AMR
            try {
                recorder.reset();
                recordFile = new File(getCacheDir(), "aufnahme_" + System.currentTimeMillis() + ".amr");
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                recorder.setOutputFile(recordFile.getAbsolutePath());
                recorder.prepare();
                recorder.start();
                recordMime = "audio/amr";
            } catch (Exception e) {
                stopRecorder();
                toast("Aufnahme nicht möglich: " + e.getMessage());
                return;
            }
        }
        recordStart = System.currentTimeMillis();
        new AlertDialog.Builder(this)
                .setTitle("Sprachaufnahme")
                .setMessage("● Aufnahme läuft… Sprich jetzt.")
                .setCancelable(false)
                .setPositiveButton("Stopp & senden", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finishRecording(true);
                    }
                })
                .setNegativeButton("Verwerfen", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        finishRecording(false);
                    }
                })
                .show();
    }

    private void finishRecording(boolean send) {
        final int seconds = (int) ((System.currentTimeMillis() - recordStart) / 1000);
        final File f = recordFile;
        final String mime = recordMime;
        try {
            if (recorder != null) recorder.stop();
        } catch (Exception e) {
            send = false;
            toast("Aufnahme zu kurz");
        }
        stopRecorder();
        if (!send || f == null) {
            if (f != null) f.delete();
            return;
        }
        showBusy("Wird gesendet…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    upload(Media.recording(f, mime, Math.max(1, seconds)), "");
                } catch (Throwable e) {
                    uploadFailed(e);
                } finally {
                    f.delete();
                }
            }
        }).start();
    }

    private void stopRecorder() {
        if (recorder != null) {
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
    }

    // ------------------------------------------------------------ opening attachments

    private void openMedia(final JSONObject m) {
        final String kind = m.optString("kind");
        final String id = m.optString("id");
        final String mime = m.optString("mime");
        final File target = Media.downloadTarget(kind, id, mime, m.optString("fileName"));
        if (target.exists() && target.length() > 0) {
            view(target, mime, kind);
            return;
        }
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            toast("Keine SD-Karte / kein Speicher verfügbar");
            return;
        }
        showBusy("Wird heruntergeladen…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    Api.download("/media?chat=" + Api.enc(jid) + "&id=" + Api.enc(id), target);
                    MediaScannerConnection.scanFile(ChatActivity.this, new String[] {target.getAbsolutePath()}, null, null);
                    ui.post(new Runnable() {
                        public void run() {
                            hideBusy();
                            view(target, mime, kind);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            hideBusy();
                            toast(e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void view(File f, String mime, String kind) {
        String type = Media.cleanMime(mime);
        if ("ptt".equals(kind) || "audio/ogg".equals(type)) {
            toast("Gespeichert in " + f.getParent() + ". Hinweis: Sprachnachrichten (Opus) kann Android 4 meist nicht abspielen.");
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.fromFile(f), type);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("Gespeichert: " + f.getAbsolutePath() + " (keine App zum Öffnen gefunden)");
        }
    }

    private void loadThumb(final ImageView iv, final String key, final String path) {
        iv.setTag(key);
        Bitmap cached = thumbCache.get(key);
        if (cached != null) {
            setThumb(iv, cached);
            return;
        }
        iv.setImageDrawable(null);
        iv.getLayoutParams().height = Ui.dp(this, 120);
        thumbs.execute(new Runnable() {
            public void run() {
                try {
                    byte[] b = Api.getBytes(path);
                    final Bitmap bmp = BitmapFactory.decodeByteArray(b, 0, b.length);
                    if (bmp == null) return;
                    thumbCache.put(key, bmp);
                    ui.post(new Runnable() {
                        public void run() {
                            if (key.equals(iv.getTag())) setThumb(iv, bmp);
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        });
    }

    private void setThumb(ImageView iv, Bitmap bmp) {
        iv.setImageBitmap(bmp);
        int w = iv.getLayoutParams().width;
        iv.getLayoutParams().height = Math.max(Ui.dp(this, 40), w * bmp.getHeight() / Math.max(1, bmp.getWidth()));
        iv.requestLayout();
    }

    private void showBusy(String text) {
        if (busy == null) {
            busy = new ProgressDialog(this);
            busy.setCancelable(false);
        }
        busy.setMessage(text);
        if (!busy.isShowing()) busy.show();
    }

    private void hideBusy() {
        if (busy != null && busy.isShowing()) busy.dismiss();
    }

    private void toast(String s) {
        Toast.makeText(this, s == null ? "Fehler" : s, Toast.LENGTH_LONG).show();
    }

    private void pollLoop() {
        long since = -1;
        boolean reload = true;
        while (active) {
            try {
                if (!Api.ready()) {
                    reload = true;
                    since = -1;
                    EngineService.sleep(1000);
                    continue;
                }
                if (reload) {
                    if (since < 0) {
                        since = new JSONObject(Api.get("/events?since=-1")).getLong("seq");
                    }
                    JSONArray arr = new JSONArray(Api.get("/messages?limit=200&chat=" + Api.enc(jid)));
                    final List<JSONObject> msgs = new ArrayList<JSONObject>();
                    for (int i = 0; i < arr.length(); i++) msgs.add(arr.getJSONObject(i));
                    ui.post(new Runnable() {
                        public void run() {
                            adapter.setAll(msgs);
                        }
                    });
                    markRead();
                    reload = false;
                }
                JSONObject ev = new JSONObject(Api.get("/events?since=" + since + "&wait=25", 40000));
                long seq = ev.getLong("seq");
                if (ev.optBoolean("reset")) {
                    since = -1;
                    reload = true;
                    continue;
                }
                JSONArray evs = ev.getJSONArray("events");
                boolean incoming = false;
                for (int i = 0; i < evs.length(); i++) {
                    final JSONObject e = evs.getJSONObject(i);
                    if (!jid.equals(e.optString("chat"))) continue;
                    String type = e.optString("type");
                    if ("message".equals(type)) {
                        final JSONObject m = e.getJSONObject("msg");
                        if (!m.optBoolean("mine")) incoming = true;
                        ui.post(new Runnable() {
                            public void run() {
                                adapter.add(m);
                            }
                        });
                    } else if ("receipt".equals(type)) {
                        ui.post(new Runnable() {
                            public void run() {
                                adapter.receipt(e.optJSONArray("ids"), e.optInt("state"));
                            }
                        });
                    }
                }
                since = seq;
                if (incoming) markRead();
            } catch (Exception e) {
                if (!active) break;
                EngineService.sleep(2000);
            }
        }
    }

    private void markRead() {
        try {
            JSONObject b = new JSONObject();
            b.put("chat", jid);
            Api.post("/read", b);
        } catch (Exception ignored) {
        }
    }

    private void sendMessage() {
        final String text = input.getText().toString().trim();
        if (text.length() == 0) return;
        send.setEnabled(false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("chat", jid);
                    b.put("text", text);
                    final JSONObject m = new JSONObject(Api.post("/send", b));
                    ui.post(new Runnable() {
                        public void run() {
                            input.setText("");
                            send.setEnabled(true);
                            adapter.add(m);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            send.setEnabled(true);
                            Toast.makeText(ChatActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ adapter

    private class MsgAdapter extends BaseAdapter {
        private final List<JSONObject> items = new ArrayList<JSONObject>();
        private final Map<String, JSONObject> byId = new HashMap<String, JSONObject>();

        void setAll(List<JSONObject> msgs) {
            items.clear();
            byId.clear();
            for (JSONObject m : msgs) {
                items.add(m);
                byId.put(m.optString("id"), m);
            }
            notifyDataSetChanged();
            list.setSelection(items.size() - 1);
        }

        void add(JSONObject m) {
            String id = m.optString("id");
            JSONObject existing = byId.get(id);
            if (existing != null) {
                try {
                    existing.put("state", Math.max(existing.optInt("state"), m.optInt("state")));
                } catch (Exception ignored) {
                }
            } else {
                items.add(m);
                byId.put(id, m);
            }
            notifyDataSetChanged();
        }

        void receipt(JSONArray ids, int state) {
            if (ids == null) return;
            for (int i = 0; i < ids.length(); i++) {
                JSONObject m = byId.get(ids.optString(i));
                if (m != null && m.optInt("state") < state) {
                    try {
                        m.put("state", state);
                    } catch (Exception ignored) {
                    }
                }
            }
            notifyDataSetChanged();
        }

        public int getCount() {
            return items.size();
        }

        public Object getItem(int p) {
            return items.get(p);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(ChatActivity.this);
                int p = Ui.dp(ChatActivity.this, 3);
                row.setPadding(Ui.dp(ChatActivity.this, 8), p, Ui.dp(ChatActivity.this, 8), p);
                LinearLayout bubble = new LinearLayout(ChatActivity.this);
                bubble.setOrientation(LinearLayout.VERTICAL);
                int bp = Ui.dp(ChatActivity.this, 8);
                bubble.setPadding(bp, Ui.dp(ChatActivity.this, 5), bp, Ui.dp(ChatActivity.this, 4));
                TextView sender = new TextView(ChatActivity.this);
                sender.setTextSize(13);
                sender.setTextColor(0xFF1F7A8C);
                bubble.addView(sender);
                ImageView thumb = new ImageView(ChatActivity.this);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(ChatActivity.this, 220), Ui.dp(ChatActivity.this, 120));
                tlp.bottomMargin = Ui.dp(ChatActivity.this, 3);
                bubble.addView(thumb, tlp);
                TextView text = new TextView(ChatActivity.this);
                text.setTextSize(16);
                text.setTextColor(0xFF111111);
                text.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
                text.setTextIsSelectable(true);
                bubble.addView(text);
                TextView meta = new TextView(ChatActivity.this);
                meta.setTextSize(11);
                meta.setGravity(Gravity.RIGHT);
                bubble.addView(meta, new LinearLayout.LayoutParams(-1, -2));
                row.addView(bubble, new LinearLayout.LayoutParams(-2, -2));
            }
            JSONObject m = items.get(pos);
            boolean mine = m.optBoolean("mine");
            row.setGravity(mine ? Gravity.RIGHT : Gravity.LEFT);
            LinearLayout bubble = (LinearLayout) row.getChildAt(0);
            bubble.setBackgroundDrawable(Ui.rounded(ChatActivity.this, mine ? 0xFFDCF8C6 : Color.WHITE, 8));
            TextView sender = (TextView) bubble.getChildAt(0);
            String sn = m.optString("senderName");
            if (group && !mine) {
                sender.setText(sn.length() > 0 ? sn : m.optString("sender").split("@")[0]);
                sender.setVisibility(View.VISIBLE);
            } else {
                sender.setVisibility(View.GONE);
            }
            final JSONObject msg = m;
            String kind = m.optString("kind");
            ImageView thumb = (ImageView) bubble.getChildAt(1);
            TextView text = (TextView) bubble.getChildAt(2);
            String body = m.optString("text");
            if (m.optBoolean("hasThumb")) {
                thumb.setVisibility(View.VISIBLE);
                loadThumb(thumb, jid + "/" + m.optString("id"),
                        "/thumb?chat=" + Api.enc(jid) + "&id=" + Api.enc(m.optString("id")));
                if (body.startsWith("[Bild]")) body = body.substring(6).trim();
                else if (body.startsWith("[Video]")) body = "\u25B6 Video  " + body.substring(7).trim();
            } else {
                thumb.setVisibility(View.GONE);
                thumb.setTag(null);
            }
            if (kind.length() > 0 && !"image".equals(kind)) body = body + "\n\u2193 Tippen zum Öffnen";
            text.setText(body);
            text.setVisibility(body.length() > 0 ? View.VISIBLE : View.GONE);
            if (kind.length() > 0) {
                View.OnClickListener open = new View.OnClickListener() {
                    public void onClick(View v) {
                        openMedia(msg);
                    }
                };
                bubble.setOnClickListener(open);
                thumb.setOnClickListener(open);
                text.setTextIsSelectable(false);
                text.setOnClickListener(open);
            } else {
                bubble.setOnClickListener(null);
                bubble.setClickable(false);
                text.setOnClickListener(null);
                text.setTextIsSelectable(true);
            }
            TextView meta = (TextView) bubble.getChildAt(3);
            String t = Ui.time(m.optLong("ts"), true);
            if (mine) {
                int st = m.optInt("state");
                t += st >= 3 ? "  ✓✓" : st == 2 ? "  ✓✓" : "  ✓";
                meta.setTextColor(st >= 3 ? 0xFF34B7F1 : 0xFF888888);
            } else {
                meta.setTextColor(0xFF888888);
            }
            meta.setText(t);
            return row;
        }
    }
}
