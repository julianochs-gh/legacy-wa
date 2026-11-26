package org.legacywa;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.LinkedList;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/** Extracts, starts and supervises the Go engine; shows message notifications. */
public class EngineService extends Service {
    public static final String ACTION_RESTART = "org.legacywa.RESTART";
    private static final int EXIT_RESTART = 3;

    private volatile boolean stopped;
    private volatile Process proc;
    private volatile int quickCrashes;
    private Thread runner;
    private Thread notifier;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(1, buildNotification("LegacyWA", "Läuft im Hintergrund", new Intent(this, MainActivity.class), false));
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LegacyWA");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            quickCrashes = 0;
            Api.engineError = null;
            Process p = proc;
            if (p != null) p.destroy();
        }
        synchronized (this) {
            if (runner == null || !runner.isAlive()) {
                runner = new Thread(new Runnable() {
                    public void run() {
                        superviseLoop();
                    }
                }, "engine");
                runner.start();
            }
            if (notifier == null || !notifier.isAlive()) {
                notifier = new Thread(new Runnable() {
                    public void run() {
                        notifyLoop();
                    }
                }, "notifier");
                notifier.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopped = true;
        Process p = proc;
        if (p != null) p.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    // ------------------------------------------------------------ engine

    private void superviseLoop() {
        while (!stopped) {
            long started = System.currentTimeMillis();
            int code = -1;
            try {
                code = runEngine();
            } catch (Throwable t) {
                Api.engineError = "Engine konnte nicht gestartet werden:\n" + t;
            }
            Api.port = 0;
            if (stopped) break;
            if (code == EXIT_RESTART) {
                quickCrashes = 0;
                continue;
            }
            if (System.currentTimeMillis() - started < 15000) {
                quickCrashes++;
            } else {
                quickCrashes = 0;
            }
            if (quickCrashes >= 4) {
                // Give up until the user presses "Engine neu starten".
                return;
            }
            sleep(3000);
        }
    }

    private int runEngine() throws Exception {
        String abi = Build.CPU_ABI + " " + Build.CPU_ABI2;
        if (!abi.contains("armeabi-v7a") && !abi.contains("arm64")) {
            throw new IllegalStateException("Dieses Gerät meldet CPU " + abi.trim()
                    + ". Die Engine braucht ARMv7 (armeabi-v7a).");
        }
        File bin = new File(getFilesDir(), "engine");
        File ca = new File(getFilesDir(), "cacert.pem");
        extract("engine", bin);
        extract("cacert.pem", ca);
        bin.setExecutable(true, true);
        try {
            Runtime.getRuntime().exec(new String[] {"chmod", "700", bin.getAbsolutePath()}).waitFor();
        } catch (Exception ignored) {
        }
        File data = new File(getFilesDir(), "data");
        data.mkdirs();

        String token = Long.toHexString(new SecureRandom().nextLong()) + Long.toHexString(System.nanoTime());
        ProcessBuilder pb = new ProcessBuilder(bin.getAbsolutePath(),
                "-listen", "127.0.0.1:0",
                "-data", data.getAbsolutePath(),
                "-dns", dnsServers());
        Map<String, String> env = pb.environment();
        env.put("LEGACY_TOKEN", token);
        env.put("HOME", data.getAbsolutePath());
        env.put("TMPDIR", getCacheDir().getAbsolutePath());
        env.put("SSL_CERT_FILE", ca.getAbsolutePath());
        pb.directory(data);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        proc = p;
        LinkedList<String> tail = new LinkedList<String>();
        OutputStream log = new FileOutputStream(new File(getFilesDir(), "engine.log"), false);
        long logged = 0;
        try {
            log.write(("Gerät: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                    + ", Kernel " + System.getProperty("os.version") + ", CPU " + abi.trim() + "\n").getBytes("UTF-8"));
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("ENGINE_READY ")) {
                    Api.token = token;
                    Api.port = Integer.parseInt(line.substring(13).trim());
                    Api.engineError = null;
                }
                tail.add(line);
                if (tail.size() > 40) tail.removeFirst();
                if (logged < 2000000) {
                    byte[] b = (line + "\n").getBytes("UTF-8");
                    log.write(b);
                    logged += b.length;
                }
            }
        } finally {
            log.close();
        }
        int code = p.waitFor();
        proc = null;
        Api.port = 0;
        if (code != EXIT_RESTART && !stopped) {
            StringBuilder sb = new StringBuilder();
            sb.append("Engine beendet (Code ").append(code).append("). Kernel: ")
              .append(System.getProperty("os.version")).append("\n\n");
            for (String s : tail) sb.append(s).append('\n');
            Api.engineError = sb.toString();
        }
        return code;
    }

    /** Copies an asset into the app's files dir when the APK was updated. */
    private void extract(String asset, File target) throws Exception {
        PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
        String stamp = pi.versionCode + ":" + pi.lastUpdateTime;
        SharedPreferences prefs = getSharedPreferences("engine", MODE_PRIVATE);
        if (target.exists() && stamp.equals(prefs.getString("stamp_" + asset, ""))) return;
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        InputStream in = getAssets().open(asset);
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        target.delete();
        if (!tmp.renameTo(target)) throw new IllegalStateException("Konnte " + asset + " nicht entpacken");
        prefs.edit().putString("stamp_" + asset, stamp).commit();
    }

    private static String dnsServers() {
        StringBuilder sb = new StringBuilder();
        String[] keys = {"net.dns1", "net.dns2", "net.rmnet0.dns1", "net.rmnet0.dns2", "net.wlan0.dns1", "dhcp.wlan0.dns1"};
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Method get = sp.getMethod("get", String.class);
            for (String k : keys) {
                String v = (String) get.invoke(null, k);
                if (v != null && v.length() > 0 && v.indexOf(':') < 0 && sb.indexOf(v) < 0) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ notifications

    private void notifyLoop() {
        long since = -1;
        while (!stopped) {
            if (!Api.ready()) {
                since = -1;
                sleep(1500);
                continue;
            }
            try {
                JSONObject o = new JSONObject(Api.get("/events?since=" + since + "&wait=25", 40000));
                long seq = o.getLong("seq");
                if (o.optBoolean("reset")) {
                    since = seq;
                    continue;
                }
                JSONArray evs = o.getJSONArray("events");
                for (int i = 0; i < evs.length(); i++) {
                    JSONObject e = evs.getJSONObject(i);
                    if (!"message".equals(e.optString("type"))) continue;
                    JSONObject m = e.optJSONObject("msg");
                    if (m == null || m.optBoolean("mine")) continue;
                    String chat = m.optString("chat");
                    if (chat.equals(Api.activeChat)) continue;
                    showMessage(chat, m);
                }
                since = seq;
            } catch (Exception e) {
                sleep(3000);
            }
        }
    }

    /** Unread notification counts per chat; cleared when the chat is opened. */
    private static final java.util.Map<String, Integer> unread = new java.util.HashMap<String, Integer>();

    private void showMessage(String chat, JSONObject m) {
        String chatName = m.optString("chatName", chat);
        String text = m.optString("text");
        boolean group = chat.endsWith("@g.us");
        if (group && m.optString("senderName").length() > 0) text = m.optString("senderName") + ": " + text;
        int count;
        synchronized (unread) {
            Integer c = unread.get(chat);
            count = (c == null ? 0 : c) + 1;
            unread.put(chat, count);
        }
        Intent i = new Intent(this, ChatActivity.class);
        i.putExtra("jid", chat);
        i.putExtra("name", chatName);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        String title = count > 1 ? chatName + " (" + count + " neue Nachrichten)" : chatName;
        Notification n = buildNotification(title, text, i, true);
        n.number = count;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(chat, 2, n);
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String title, String text, Intent intent, boolean alert) {
        PendingIntent pi = PendingIntent.getActivity(this, (title + text).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle(title)
                .setContentText(text)
                .setTicker(alert ? title + ": " + text : null)
                .setContentIntent(pi)
                .setWhen(System.currentTimeMillis());
        if (alert) {
            b.setAutoCancel(true)
             .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE)
             .setLights(0xFF25D366, 800, 2000);
        } else {
            b.setOngoing(true);
        }
        return b.getNotification();
    }

    public static void cancelChatNotification(Context ctx, String chat) {
        synchronized (unread) {
            unread.remove(chat);
        }
        ((NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE)).cancel(chat, 2);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
