package org.legacywa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
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
            ((TextView) bubble.getChildAt(1)).setText(m.optString("text"));
            TextView meta = (TextView) bubble.getChildAt(2);
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
