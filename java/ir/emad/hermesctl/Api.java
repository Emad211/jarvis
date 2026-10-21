package ir.emad.hermesctl;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tiny JSON-over-HTTP client for the on-phone gwctl server. */
public final class Api {

    public static final String BASE = "http://127.0.0.1:8777";

    public interface Cb {
        void ok(JSONObject body);

        void fail(String message);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Api() {
    }

    public static void get(String path, int timeoutMs, Cb cb) {
        call("GET", path, null, timeoutMs, cb);
    }

    public static void post(String path, JSONObject body, int timeoutMs, Cb cb) {
        call("POST", path, body == null ? new JSONObject() : body, timeoutMs, cb);
    }

    private static void call(final String method, final String path,
                             final JSONObject body, final int timeoutMs, final Cb cb) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject out = exec(method, path, body, timeoutMs);
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ok(out);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                    UI.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.fail(msg);
                        }
                    });
                }
            }
        });
    }

    /** Blocking call for the background watchdog (it already runs off the UI thread). */
    public static JSONObject execForJob(String path, int timeoutMs) throws Exception {
        return exec("GET", path, null, timeoutMs);
    }

    private static JSONObject exec(String method, String path, JSONObject body, int timeoutMs)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
        }
        c.disconnect();
        String text = sb.toString().trim();
        if (code >= 400) {
            String detail = text.length() > 220 ? text.substring(0, 220) : text;
            throw new Exception("HTTP " + code + " " + detail);
        }
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }
}
