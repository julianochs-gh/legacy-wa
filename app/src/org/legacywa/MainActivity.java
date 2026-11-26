package org.legacywa;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private final Handler ui = new Handler();
    private volatile boolean active;
    private Thread poller;

    private TextView header;
    private LinearLayout statusBox, pairBox;
    private TextView statusText;
    private ProgressBar statusProgress;
    private Button retryButton;
    private ImageView qrImage;
    private TextView pairHint;
    private Button reloadQr;
    private ListView list;
    private final ChatAdapter adapter = new ChatAdapter();

    private String shownQrId = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startService(new Intent(this, EngineService.class));
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        header = new TextView(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        header.setBackgroundColor(0xFF075E54);
        header.setTextColor(Color.WHITE);
        header.setTextSize(13);
        header.setVisibility(View.GONE);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // status / error view
        statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(this, 20);
        statusBox.setPadding(pad, pad, pad, pad);
        statusProgress = new ProgressBar(this);
        statusBox.addView(statusProgress);
        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setPadding(0, pad, 0, pad);
        statusText.setTextIsSelectable(true);
        statusBox.addView(statusText);
        retryButton = new Button(this);
        retryButton.setText("Engine neu starten");
        retryButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restartEngine();
            }
        });
        statusBox.addView(retryButton);
        ScrollView statusScroll = new ScrollView(this);
        statusScroll.addView(statusBox);
        root.addView(statusScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // pairing view
        pairBox = new LinearLayout(this);
        pairBox.setOrientation(LinearLayout.VERTICAL);
        pairBox.setGravity(Gravity.CENTER_HORIZONTAL);
        pairBox.setPadding(pad, pad, pad, pad);
        pairHint = new TextView(this);
        pairHint.setText("Auf dem Haupt-Handy: WhatsApp → Einstellungen → Verknüpfte Geräte → "
                + "Gerät hinzufügen, dann diesen Code scannen.");
        pairHint.setTextSize(15);
        pairBox.addView(pairHint);
        qrImage = new ImageView(this);
        qrImage.setBackgroundColor(Color.WHITE);
        int qrSize = Math.min(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels) - 2 * pad;
        LinearLayout.LayoutParams qrLp = new LinearLayout.LayoutParams(qrSize, qrSize);
        qrLp.topMargin = pad;
        qrLp.bottomMargin = pad;
        pairBox.addView(qrImage, qrLp);
        reloadQr = new Button(this);
        reloadQr.setText("QR-Code neu laden");
        reloadQr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                postAsync("/pair/restart", new JSONObject(), null);
            }
        });
        pairBox.addView(reloadQr);
        Button phone = new Button(this);
        phone.setText("Stattdessen mit Telefonnummer koppeln");
        phone.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                askPhone();
            }
        });
        pairBox.addView(phone);
        ScrollView pairScroll = new ScrollView(this);
        pairScroll.addView(pairBox);
        pairScroll.setVisibility(View.GONE);
        root.addView(pairScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // chat list
        list = new ListView(this);
        list.setAdapter(adapter);
        list.setVisibility(View.GONE);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                JSONObject c = adapter.items.get(position);
                Intent i = new Intent(MainActivity.this, ChatActivity.class);
                i.putExtra("jid", c.optString("jid"));
                i.putExtra("name", c.optString("name"));
                startActivity(i);
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        showStatus("Engine wird gestartet…", true, false);
    }

    private void show(View v) {
        View status = (View) statusBox.getParent();
        View pair = (View) pairBox.getParent();
        status.setVisibility(v == status ? View.VISIBLE : View.GONE);
        pair.setVisibility(v == pair ? View.VISIBLE : View.GONE);
        list.setVisibility(v == list ? View.VISIBLE : View.GONE);
    }

    private void showStatus(String text, boolean progress, boolean retry) {
        statusText.setText(text);
        statusProgress.setVisibility(progress ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(retry ? View.VISIBLE : View.GONE);
        header.setVisibility(View.GONE);
        show((View) statusBox.getParent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        active = true;
        poller = new Thread(new Runnable() {
            public void run() {
                pollLoop();
            }
        }, "main-poll");
        poller.start();
    }

    @Override
    protected void onPause() {
        active = false;
        if (poller != null) poller.interrupt();
        super.onPause();
    }

    // ------------------------------------------------------------ polling

    private void pollLoop() {
        long since = -1;
        boolean needChats = true;
        int waited = 0;
        while (active) {
            try {
                if (!Api.ready()) {
                    final String err = Api.engineError;
                    waited++;
                    ui.post(new Runnable() {
                        public void run() {
                            if (err != null) showStatus(err, false, true);
                            else showStatus("Engine wird gestartet…", true, false);
                        }
                    });
                    since = -1;
                    needChats = true;
                    EngineService.sleep(1000);
                    continue;
                }
                final JSONObject st = new JSONObject(Api.get("/status"));
                final String state = st.optString("state");
                if (st.optBoolean("loggedIn")) {
                    if (needChats) {
                        loadChats();
                        needChats = false;
                    }
                    ui.post(new Runnable() {
                        public void run() {
                            showChats(st);
                        }
                    });
                    JSONObject ev = new JSONObject(Api.get("/events?since=" + since + "&wait=20", 30000));
                    long seq = ev.getLong("seq");
                    if (ev.optBoolean("reset") || ev.getJSONArray("events").length() > 0) needChats = true;
                    since = seq;
                } else if ("qr".equals(state) || "qr_timeout".equals(state) || "pairing".equals(state)) {
                    final String qrId = st.optString("qrId");
                    Bitmap bmp = null;
                    if (st.optBoolean("hasQR") && !qrId.equals(shownQrId)) {
                        byte[] png = Api.getBytes("/qr.png?size=512");
                        bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                    }
                    final Bitmap fb = bmp;
                    ui.post(new Runnable() {
                        public void run() {
                            showPair(state, st.optString("detail"), fb, qrId);
                        }
                    });
                    EngineService.sleep(1500);
                } else {
                    final String text = describe(state, st.optString("detail"));
                    final boolean busy = !"error".equals(state) && !"outdated".equals(state);
                    ui.post(new Runnable() {
                        public void run() {
                            showStatus(text, busy, !busy);
                        }
                    });
                    EngineService.sleep(1500);
                }
            } catch (final Exception e) {
                if (!active) break;
                EngineService.sleep(1500);
            }
        }
    }

    private static String describe(String state, String detail) {
        String base;
        if ("starting".equals(state)) base = "Engine startet…";
        else if ("connecting".equals(state)) base = "Verbinde mit WhatsApp…";
        else if ("reconnecting".equals(state)) base = "Verbindung wird wiederhergestellt…";
        else if ("logged_out".equals(state)) base = "Abgemeldet. Starte neu…";
        else if ("outdated".equals(state)) base = "WhatsApp lehnt die Version ab.";
        else if ("error".equals(state)) base = "Fehler";
        else base = state;
        return detail.length() > 0 ? base + "\n\n" + detail : base;
    }

    private void loadChats() throws Exception {
        JSONArray arr = new JSONArray(Api.get("/chats"));
        final List<JSONObject> items = new ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
        ui.post(new Runnable() {
            public void run() {
                adapter.items = items;
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void showChats(JSONObject st) {
        String state = st.optString("state");
        if ("connected".equals(state)) {
            header.setVisibility(View.GONE);
        } else {
            header.setText(describe(state, st.optString("detail")).replace("\n\n", " – "));
            header.setVisibility(View.VISIBLE);
        }
        show(list);
        if (adapter.items.isEmpty()) {
            header.setText("Verbunden. Chats werden synchronisiert – das kann einige Minuten dauern…");
            header.setVisibility(View.VISIBLE);
        }
    }

    private void showPair(String state, String detail, Bitmap bmp, String qrId) {
        show((View) pairBox.getParent());
        if (bmp != null) {
            qrImage.setImageBitmap(bmp);
            shownQrId = qrId;
        }
        boolean expired = "qr_timeout".equals(state);
        qrImage.setAlpha(expired ? 0.15f : 1f);
        reloadQr.setVisibility(expired ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------ actions

    private void askPhone() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("z. B. 491701234567");
        new AlertDialog.Builder(this)
                .setTitle("Deine WhatsApp-Nummer")
                .setMessage("Mit Ländervorwahl, ohne + und ohne führende 0.")
                .setView(input)
                .setPositiveButton("Code anfordern", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("phone", input.getText().toString());
                        } catch (Exception ignored) {
                        }
                        postAsync("/pair/phone", body, new Callback() {
                            public void done(JSONObject r) {
                                showPairCode(r.optString("code"));
                            }
                        });
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void showPairCode(String code) {
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTextSize(34);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
        tv.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Kopplungscode")
                .setMessage("Auf dem Haupt-Handy: Verknüpfte Geräte → Gerät hinzufügen → "
                        + "„Stattdessen mit Telefonnummer verknüpfen“ und diesen Code eingeben:")
                .setView(tv)
                .setPositiveButton("OK", null)
                .show();
    }

    interface Callback {
        void done(JSONObject result);
    }

    private void postAsync(final String path, final JSONObject body, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                try {
                    String s = Api.post(path, body);
                    final JSONObject r = s.trim().startsWith("{") ? new JSONObject(s) : new JSONObject();
                    if (cb != null) ui.post(new Runnable() {
                        public void run() {
                            cb.done(r);
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    private void restartEngine() {
        Intent i = new Intent(this, EngineService.class);
        i.setAction(EngineService.ACTION_RESTART);
        startService(i);
        showStatus("Engine wird neu gestartet…", true, false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Log anzeigen");
        menu.add(0, 2, 0, "Engine neu starten");
        menu.add(0, 3, 0, "Abmelden");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                showLog();
                return true;
            case 2:
                restartEngine();
                return true;
            case 3:
                new AlertDialog.Builder(this)
                        .setMessage("Dieses Gerät von WhatsApp abmelden und alle Chats hier löschen?")
                        .setPositiveButton("Abmelden", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                postAsync("/logout", new JSONObject(), null);
                            }
                        })
                        .setNegativeButton("Abbrechen", null)
                        .show();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showLog() {
        String text;
        try {
            File f = new File(getFilesDir(), "engine.log");
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            long start = Math.max(0, len - 12000);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            raf.close();
            text = new String(buf, "UTF-8");
            if (start > 0) {
                FileInputStream head = new FileInputStream(f);
                byte[] h = new byte[300];
                int n = head.read(h);
                head.close();
                String first = new String(h, 0, Math.max(n, 0), "UTF-8");
                int nl = first.indexOf('\n');
                text = (nl > 0 ? first.substring(0, nl) : first) + "\n…\n" + text;
            }
        } catch (Exception e) {
            text = "Kein Log vorhanden (" + e.getMessage() + ")";
        }
        if (Api.engineError != null) text = Api.engineError + "\n----\n" + text;
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        int p = Ui.dp(this, 8);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Engine-Log").setView(sv).setPositiveButton("Schließen", null).show();
    }

    // ------------------------------------------------------------ adapter

    private class ChatAdapter extends BaseAdapter {
        List<JSONObject> items = new ArrayList<JSONObject>();

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
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(MainActivity.this, 10);
                row.setPadding(Ui.dp(MainActivity.this, 14), p, Ui.dp(MainActivity.this, 14), p);
                LinearLayout top = new LinearLayout(MainActivity.this);
                TextView name = new TextView(MainActivity.this);
                name.setTextSize(17);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                name.setSingleLine(true);
                name.setTextColor(0xFF111111);
                top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                TextView time = new TextView(MainActivity.this);
                time.setTextSize(12);
                top.addView(time);
                row.addView(top);
                LinearLayout bottom = new LinearLayout(MainActivity.this);
                bottom.setGravity(Gravity.CENTER_VERTICAL);
                TextView last = new TextView(MainActivity.this);
                last.setSingleLine(true);
                last.setTextSize(14);
                bottom.addView(last, new LinearLayout.LayoutParams(0, -2, 1));
                TextView badge = new TextView(MainActivity.this);
                badge.setTextColor(Color.WHITE);
                badge.setTextSize(12);
                badge.setPadding(Ui.dp(MainActivity.this, 7), 0, Ui.dp(MainActivity.this, 7), 0);
                badge.setBackgroundDrawable(Ui.rounded(MainActivity.this, 0xFF25D366, 10));
                bottom.addView(badge);
                row.addView(bottom);
            }
            JSONObject c = items.get(pos);
            LinearLayout top = (LinearLayout) row.getChildAt(0);
            LinearLayout bottom = (LinearLayout) row.getChildAt(1);
            ((TextView) top.getChildAt(0)).setText(c.optString("name"));
            ((TextView) top.getChildAt(1)).setText(Ui.time(c.optLong("ts"), true));
            ((TextView) bottom.getChildAt(0)).setText(c.optString("last"));
            int unread = c.optInt("unread");
            TextView badge = (TextView) bottom.getChildAt(1);
            badge.setText(String.valueOf(unread));
            badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
            return row;
        }
    }
}
