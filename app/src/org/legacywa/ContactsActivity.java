package org.legacywa;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

/** WhatsApp contacts (synced from the main phone) to start a new chat. */
public class ContactsActivity extends Activity {
    private final Handler ui = new Handler();
    private final List<JSONObject> all = new ArrayList<JSONObject>();
    private final List<JSONObject> shown = new ArrayList<JSONObject>();
    private final Adapter adapter = new Adapter();
    private EditText filter;
    private TextView empty;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        filter = new EditText(this);
        filter.setHint("Suchen…");
        filter.setSingleLine(true);
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            public void afterTextChanged(Editable s) {
                applyFilter();
            }
        });
        root.addView(filter, new LinearLayout.LayoutParams(-1, -2));

        empty = new TextView(this);
        int p = Ui.dp(this, 16);
        empty.setPadding(p, p, p, p);
        empty.setText("Kontakte werden geladen…");
        root.addView(empty, new LinearLayout.LayoutParams(-1, -2));

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == 0) {
                    askNumber();
                    return;
                }
                JSONObject c = shown.get(pos - 1);
                openChat(c.optString("jid"), c.optString("name"));
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONArray arr = new JSONArray(Api.get("/contacts"));
                    final List<JSONObject> items = new ArrayList<JSONObject>();
                    for (int i = 0; i < arr.length(); i++) items.add(arr.getJSONObject(i));
                    ui.post(new Runnable() {
                        public void run() {
                            all.clear();
                            all.addAll(items);
                            applyFilter();
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            empty.setText("Kontakte konnten nicht geladen werden: " + e.getMessage());
                            empty.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();
    }

    private void applyFilter() {
        String q = filter.getText().toString().trim().toLowerCase(Locale.GERMANY);
        shown.clear();
        for (JSONObject c : all) {
            if (q.length() == 0 || c.optString("name").toLowerCase(Locale.GERMANY).contains(q)
                    || c.optString("number").replace(" ", "").contains(q)) {
                shown.add(c);
            }
        }
        adapter.notifyDataSetChanged();
        if (all.isEmpty()) {
            empty.setText("Noch keine Kontakte. Sie werden nach dem Koppeln vom Haupt-Handy übertragen – "
                    + "das kann einige Minuten dauern. Menü → „Kontakte aktualisieren“ fordert sie neu an.");
            empty.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private void openChat(String jid, String name) {
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("jid", jid);
        i.putExtra("name", name);
        startActivity(i);
        finish();
    }

    private void askNumber() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setHint("z. B. 491701234567");
        new AlertDialog.Builder(this)
                .setTitle("Chat mit Telefonnummer")
                .setMessage("Nummer mit Ländervorwahl eingeben:")
                .setView(input)
                .setPositiveButton("Chat öffnen", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        resolve(input.getText().toString());
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void resolve(final String phone) {
        final ProgressDialog pd = ProgressDialog.show(this, null, "Prüfe Nummer…", true, false);
        new Thread(new Runnable() {
            public void run() {
                try {
                    JSONObject b = new JSONObject();
                    b.put("phone", phone);
                    final JSONObject r = new JSONObject(Api.post("/resolve", b));
                    ui.post(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            openChat(r.optString("jid"), r.optString("name"));
                        }
                    });
                } catch (final Exception e) {
                    ui.post(new Runnable() {
                        public void run() {
                            pd.dismiss();
                            Toast.makeText(ContactsActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Kontakte aktualisieren");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        if (item.getItemId() == 1) {
            refresh(this, new Runnable() {
                public void run() {
                    load();
                }
            });
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Asks the main phone for a full contact resync. */
    static void refresh(final Activity a, final Runnable after) {
        final ProgressDialog pd = ProgressDialog.show(a, null, "Kontakte werden vom Haupt-Handy geladen…", true, false);
        final Handler h = new Handler();
        new Thread(new Runnable() {
            public void run() {
                String err = null;
                try {
                    Api.post("/contacts/refresh", new JSONObject());
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fe = err;
                h.post(new Runnable() {
                    public void run() {
                        pd.dismiss();
                        Toast.makeText(a, fe == null ? "Kontakte aktualisiert" : "Fehler: " + fe, Toast.LENGTH_LONG).show();
                        if (after != null) after.run();
                    }
                });
            }
        }).start();
    }

    private class Adapter extends BaseAdapter {
        public int getCount() {
            return shown.size() + 1;
        }

        public Object getItem(int p) {
            return p == 0 ? null : shown.get(p - 1);
        }

        public long getItemId(int p) {
            return p;
        }

        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row = (LinearLayout) convert;
            if (row == null) {
                row = new LinearLayout(ContactsActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(ContactsActivity.this, 10);
                row.setPadding(Ui.dp(ContactsActivity.this, 14), p, Ui.dp(ContactsActivity.this, 14), p);
                TextView name = new TextView(ContactsActivity.this);
                name.setTextSize(17);
                name.setTextColor(0xFF111111);
                row.addView(name);
                TextView number = new TextView(ContactsActivity.this);
                number.setTextSize(13);
                row.addView(number);
            }
            TextView name = (TextView) row.getChildAt(0);
            TextView number = (TextView) row.getChildAt(1);
            if (pos == 0) {
                name.setText("☎  Nummer eingeben…");
                name.setTypeface(Typeface.DEFAULT_BOLD);
                number.setText("Chat mit jemandem, der nicht in den Kontakten ist");
            } else {
                JSONObject c = shown.get(pos - 1);
                name.setText(c.optString("name"));
                name.setTypeface(Typeface.DEFAULT);
                number.setText(c.optString("number"));
            }
            return row;
        }
    }
}
