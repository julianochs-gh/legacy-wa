package org.legacywa;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

import org.json.JSONObject;

/** Talks to the local engine on 127.0.0.1. */
public final class Api {
    public static volatile int port = 0;
    public static volatile String token = "";
    public static volatile String activeChat = null;
    public static volatile String engineError = null;

    static {
        System.setProperty("http.keepAlive", "false");
    }

    private Api() {}

    public static boolean ready() {
        return port != 0;
    }

    public static String get(String path) throws IOException {
        return new String(request("GET", path, null, 30000), "UTF-8");
    }

    public static String get(String path, int timeoutMs) throws IOException {
        return new String(request("GET", path, null, timeoutMs), "UTF-8");
    }

    public static byte[] getBytes(String path) throws IOException {
        return request("GET", path, null, 30000);
    }

    public static String post(String path, JSONObject body) throws IOException {
        byte[] b = (body == null ? "{}" : body.toString()).getBytes("UTF-8");
        return new String(request("POST", path, b, 70000), "UTF-8");
    }

    public static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static byte[] request(String method, String path, byte[] body, int timeoutMs) throws IOException {
        int p = port;
        if (p == 0) throw new IOException("Engine läuft noch nicht");
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + p + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setRequestProperty("X-Token", token);
            c.setRequestProperty("Connection", "close");
            c.setConnectTimeout(5000);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(body.length);
                OutputStream os = c.getOutputStream();
                os.write(body);
                os.close();
            }
            int code = c.getResponseCode();
            if (code >= 400) {
                InputStream es = c.getErrorStream();
                String msg = es == null ? ("HTTP " + code) : new String(readAll(es), "UTF-8").trim();
                throw new IOException(msg);
            }
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}
