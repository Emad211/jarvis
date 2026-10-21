package ir.emad.hermesctl;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny JSON-over-HTTP client for the on-phone gwctl server (127.0.0.1:8777). */
public final class Api {

    public static final String BASE = "http://127.0.0.1:8777";

    public interface Ok {
        void on(JSONObject body);
    }

    public interface Fail {
        void on(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Api() {
    }

    public static void get(String path, int timeoutMs, Ok ok, Fail fail) {
        call("GET", path, null, timeoutMs, ok, fail);
    }

    public static void post(String path, JSONObject body, int timeoutMs, Ok ok, Fail fail) {
        call("POST", path, body == null ? new JSONObject() : body, timeoutMs, ok, fail);
    }

    /** Builds a JSON body from alternating key/value pairs. */
    public static JSONObject body(Object... kv) {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            try {
                o.put(String.valueOf(kv[i]), kv[i + 1]);
            } catch (Exception ignored) {
                // keys are always strings here
            }
        }
        return o;
    }

    private static void call(String method, String path, JSONObject body, int timeoutMs,
                             Ok ok, Fail fail) {
        POOL.execute(() -> {
            try {
                JSONObject out = exec(method, path, body, timeoutMs);
                UI.post(() -> ok.on(out));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                UI.post(() -> {
                    if (fail != null) {
                        fail.on(msg);
                    }
                });
            }
        });
    }

    /** Blocking GET for the background watchdog (already off the UI thread). */
    public static JSONObject getBlocking(String path, int timeoutMs) throws Exception {
        return exec("GET", path, null, timeoutMs);
    }

    private static JSONObject exec(String method, String path, JSONObject body, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream()).trim();
            if (code >= 400) {
                String detail = text;
                try {
                    detail = new JSONObject(text).optString("error", text);
                } catch (Exception ignored) {
                    // not JSON: show the raw body
                }
                throw new Exception("HTTP " + code + " " + (detail.length() > 200
                        ? detail.substring(0, 200) : detail));
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
