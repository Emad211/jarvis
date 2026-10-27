package ir.emad.jarvis;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTP + SSE client for the on-phone jarvisd (127.0.0.1:8780 by default). */
final class Api {

    interface Ok {
        void on(JSONObject body);
    }

    interface OkArray {
        void on(JSONArray rows);
    }

    interface Fail {
        void on(String message);
    }

    interface Event {
        void on(String kind, JSONObject data);
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    private static volatile String base = "http://127.0.0.1:8780";

    private Api() {
    }

    static String base() {
        return base;
    }

    static void base(String value) {
        base = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    static JSONObject body(Object... kv) {
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

    static void get(String path, int timeoutMs, Ok ok, Fail fail) {
        call("GET", path, null, timeoutMs, ok, fail);
    }

    static void post(String path, JSONObject payload, int timeoutMs, Ok ok, Fail fail) {
        call("POST", path, payload == null ? new JSONObject() : payload, timeoutMs, ok, fail);
    }

    static void delete(String path, int timeoutMs, Ok ok, Fail fail) {
        call("DELETE", path, null, timeoutMs, ok, fail);
    }

    static void patch(String path, JSONObject payload, int timeoutMs, Ok ok, Fail fail) {
        call("PATCH", path, payload == null ? new JSONObject() : payload, timeoutMs, ok, fail);
    }

    /**
     * GET an endpoint whose body is a top-level JSON array (`/personas`, `/skills`, `/cron`,
     * `/services`, `/mcp`, …). `JSONObject` cannot parse those, so they need their own path.
     */
    static void getArray(String path, int timeoutMs, OkArray ok, Fail fail) {
        POOL.execute(() -> {
            try {
                String text = execText("GET", path, null, timeoutMs, null);
                JSONArray rows = text.isEmpty() ? new JSONArray() : new JSONArray(text);
                MainActivity.ui(() -> ok.on(rows));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MainActivity.ui(() -> {
                    if (fail != null) {
                        fail.on(msg);
                    }
                });
            }
        });
    }

    private static void call(String method, String path, JSONObject payload, int timeoutMs,
                             Ok ok, Fail fail) {
        POOL.execute(() -> {
            try {
                JSONObject out = exec(method, path, payload, timeoutMs, null);
                MainActivity.ui(() -> ok.on(out));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MainActivity.ui(() -> {
                    if (fail != null) {
                        fail.on(msg);
                    }
                });
            }
        });
    }

    private static JSONObject exec(String method, String path, JSONObject payload, int timeoutMs,
                                   String[] out) throws Exception {
        String text = execText(method, path, payload, timeoutMs, out);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String execText(String method, String path, JSONObject payload, int timeoutMs,
                                   String[] out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(4000);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("Accept", "application/json");
            if (payload != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream()).trim();
            if (out != null) {
                out[0] = text;
            }
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
            return text;
        } finally {
            c.disconnect();
        }
    }

    /** POST raw bytes (a WAV recording) and read the JSON answer. */
    static void postBytes(String path, byte[] data, String contentType, int timeoutMs,
                          Ok ok, Fail fail) {
        POOL.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
                try {
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(timeoutMs);
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", contentType);
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(data);
                    }
                    int code = c.getResponseCode();
                    String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
                    if (code >= 400) {
                        throw new Exception("HTTP " + code);
                    }
                    JSONObject out = new JSONObject(text);
                    MainActivity.ui(() -> ok.on(out));
                } finally {
                    c.disconnect();
                }
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                MainActivity.ui(() -> {
                    if (fail != null) {
                        fail.on(msg);
                    }
                });
            }
        });
    }

    /**
     * Stream /events over SSE. Runs until the thread is interrupted or the stream ends.
     * `from` is the last sequence number the caller already has (0 = only new events).
     */
    static Thread stream(String path, Event handler, Fail fail) {
        Thread t = new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(base + path).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(4000);
                c.setReadTimeout(0); // keep-alive pings keep the socket busy
                c.setRequestProperty("Accept", "text/event-stream");
                c.setRequestProperty("Cache-Control", "no-cache");
                int code = c.getResponseCode();
                if (code >= 400) {
                    throw new Exception("HTTP " + code);
                }
                BufferedReader r = new BufferedReader(new InputStreamReader(
                        c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        break;
                    }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            dispatch(data.toString(), handler);
                            data.setLength(0);
                        }
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        data.append(line.substring(5).trim());
                    }
                    // "id:" / "event:" / ": ping" lines are redundant: the payload
                    // carries the whole envelope.
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                            : e.getMessage();
                    MainActivity.ui(() -> {
                        if (fail != null) {
                            fail.on(msg);
                        }
                    });
                }
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "sse");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void dispatch(String json, Event handler) {
        try {
            JSONObject ev = new JSONObject(json);
            String kind = ev.optString("kind", "");
            JSONObject data = ev.optJSONObject("data");
            if (data == null) {
                data = new JSONObject();
            }
            data.put("_seq", ev.optInt("seq", 0));
            JSONObject payload = data;
            String finalKind = kind;
            MainActivity.ui(() -> handler.on(finalKind, payload));
        } catch (Exception ignored) {
            // a truncated frame is not worth crashing over; the next one will arrive
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
