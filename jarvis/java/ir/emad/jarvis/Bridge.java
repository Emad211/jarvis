package ir.emad.jarvis;

import android.os.BatteryManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny loopback server that lets the daemon use the phone without adb or Shizuku.
 *
 * `jarvisd` runs in Termux and cannot call Android APIs. Until now the only way for the agent to
 * act on the phone was `adb shell` (needs wireless debugging paired) or Shizuku's `rish` (needs
 * another app started by hand) — two things a stranger will not get through. The app can do all
 * of it itself if it holds the accessibility service, so the app exposes that ability here, on
 * **127.0.0.1 only**: the same trust model as gwctl and jarvisd, and nothing on the network.
 *
 * Protocol (deliberately trivial, one request per connection):
 *     POST /act   {"intent":"tap","args":{"x":540,"y":1200}}
 *     GET  /ping  -> {"ok":true,"a11y":true,"version":1}
 * Body is bounded and everything unknown is refused, so a stray local process cannot use this as
 * a general-purpose remote control for the phone.
 */
final class Bridge {

    /** Same value as jarvisd's ADB/ACTION lane expects; bumped when the protocol changes. */
    static final int VERSION = 1;
    private static final String TAG = "JarvisBridge";
    private static final int PORT = 8791;
    private static volatile boolean running;
    private static ServerSocket server;

    private Bridge() {
    }

    static void start(MainActivity activity) {
        if (running) {
            return;
        }
        running = true;
        Thread t = new Thread(() -> {
            try {
                server = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"));
                while (running) {
                    Socket client = server.accept();
                    try {
                        handle(activity, client);
                    } catch (Exception e) {
                        Log.w(TAG, "request failed: " + e);
                    } finally {
                        try {
                            client.close();
                        } catch (Exception ignored) {
                            // closed already
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "bridge stopped: " + e);
            } finally {
                running = false;
            }
        }, "jarvis-bridge");
        t.setDaemon(true);
        t.start();
    }

    static void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (Exception ignored) {
            // nothing to close
        }
    }

    private static void handle(MainActivity activity, Socket client) throws Exception {
        client.setSoTimeout(15000);
        BufferedReader in = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) {
            return;
        }
        int length = 0;
        String line;
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("content-length")) {
                length = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        StringBuilder body = new StringBuilder();
        if (length > 0) {
            if (length > 65536) {
                reply(client, 413, "{\"ok\":false,\"error\":\"too_large\"}");
                return;
            }
            char[] buf = new char[length];
            int read = 0;
            while (read < length) {
                int n = in.read(buf, read, length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            body.append(buf, 0, read);
        }

        boolean ping = request.startsWith("GET /ping");
        if (ping) {
            reply(client, 200, new JSONObject()
                    .put("ok", true).put("version", VERSION).put("a11y", A11y.ready())
                    .toString());
            return;
        }
        if (!request.startsWith("POST /act")) {
            reply(client, 404, "{\"ok\":false,\"error\":\"no_route\"}");
            return;
        }
        JSONObject payload;
        try {
            payload = new JSONObject(body.toString());
        } catch (Exception e) {
            reply(client, 400, "{\"ok\":false,\"error\":\"bad_json\"}");
            return;
        }
        reply(client, 200, act(activity, payload).toString());
    }

    /** Dispatches one intent. Every branch answers {ok, ...} and never throws. */
    private static JSONObject act(MainActivity activity, JSONObject payload) {
        JSONObject out = new JSONObject();
        String intent = payload.optString("intent", "");
        JSONObject args = payload.optJSONObject("args");
        if (args == null) {
            args = new JSONObject();
        }
        A11y a11y = A11y.get();
        // These seven read system data through ordinary Android APIs; they do not need the
        // accessibility service, so a null `a11y` must not be reported as their failure.
        boolean needsA11y = true;
        try {
            switch (intent) {
                case "notif_list":
                case "notif_clear":
                    needsA11y = false;
                    JSONObject n = Notif.handle(intent, args);
                    out.put("ok", n.optBoolean("ok", false));
                    out.put("error", n.optString("error", ""));
                    out.put("needs", n.optString("needs", ""));
                    out.put("count", n.optInt("count", 0));
                    out.put("items", n.optJSONArray("items") == null
                            ? new org.json.JSONArray() : n.optJSONArray("items"));
                    return out;
                case "calendar_today":
                case "calendar_range":
                case "contacts_search":
                case "sms_recent":
                case "apps_list":
                    needsA11y = false;
                    JSONObject d = Data.handle(activity, intent, args);
                    out.put("ok", d.optBoolean("ok", false));
                    out.put("error", d.optString("error", ""));
                    out.put("needs", d.optString("needs", ""));
                    out.put("count", d.optInt("count", 0));
                    // `detail` carries the real Java message (e.g. why a calendar query was
                    // rejected). Dropping it turns a fixable bug into "it just fails".
                    out.put("detail", d.optString("detail", ""));
                    out.put("items", d.optJSONArray("items") == null
                            ? new org.json.JSONArray() : d.optJSONArray("items"));
                    return out;
                default:
                    break;
            }
        } catch (Exception e) {
            try {
                out.put("ok", false).put("error", e.getClass().getSimpleName());
            } catch (Exception ignored) {
                // JSONObject.put only throws on NaN
            }
            return out;
        }
        try {
            out.put("ok", true);
            out.put("intent", intent);
            switch (intent) {
                case "info":
                    out.put("model", android.os.Build.MODEL);
                    out.put("sdk", android.os.Build.VERSION.SDK_INT);
                    BatteryManager bm = (BatteryManager) activity.getSystemService(
                            android.content.Context.BATTERY_SERVICE);
                    out.put("battery", bm == null ? -1
                            : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY));
                    out.put("a11y", a11y != null);
                    break;
                case "screen":
                    out.put("ok", a11y != null);
                    out.put("text", a11y == null ? "" : a11y.screen());
                    break;
                case "screenshot": {
                    String path = a11y == null ? "" : a11y.screenshot(args.optString("name"));
                    out.put("ok", a11y != null && !path.isEmpty());
                    out.put("path", path);
                    // `inline` exists because the file lives in the app's private cache, which
                    // Termux cannot open. Sending the bytes over loopback is what lets the fast
                    // path take a screenshot without adb at all.
                    if (args.optBoolean("inline", false) && !path.isEmpty()) {
                        out.put("b64", base64(path));
                    }
                    break;
                }
                case "open_app": {
                    String pkg = args.optString("package");
                    out.put("ok", a11y != null && a11y.openApp(pkg));
                    break;
                }
                case "open_url": {
                    String url = args.optString("url");
                    out.put("ok", a11y != null && a11y.openUrl(url));
                    break;
                }
                case "tap": {
                    float x = (float) args.optDouble("x", -1);
                    float y = (float) args.optDouble("y", -1);
                    out.put("ok", a11y != null && x >= 0 && y >= 0 && a11y.tap(x, y));
                    break;
                }
                case "swipe": {
                    out.put("ok", a11y != null && a11y.swipe(
                            (float) args.optDouble("x1", 0), (float) args.optDouble("y1", 0),
                            (float) args.optDouble("x2", 0), (float) args.optDouble("y2", 0),
                            args.optLong("ms", 300)));
                    break;
                }
                case "key": {
                    out.put("ok", a11y != null && a11y.global(args.optString("name")));
                    break;
                }
                case "notifications": {
                    out.put("ok", a11y != null && a11y.global("notifications"));
                    break;
                }
                case "type": {
                    out.put("ok", a11y != null && a11y.type(args.optString("text")));
                    break;
                }
                case "clipboard_get": {
                    out.put("ok", a11y != null);
                    out.put("text", a11y == null ? "" : a11y.clipboardGet());
                    break;
                }
                case "clipboard_focus": {
                    // The legal route: a translucent activity takes focus for one frame, because
                    // only the focused app may read the clipboard on Android 10+.
                    String token = Long.toHexString(System.nanoTime());
                    android.content.Intent i = new android.content.Intent(activity, Clip.class);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                            | android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                            | android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    i.putExtra(Clip.TOKEN, token);
                    String seed = args.optString("seed", "");
                    if (!seed.isEmpty()) {
                        // diagnostic only: let the activity put text on the clipboard itself, so
                        // the read path can be tested without accessibility or another app
                        i.putExtra(Clip.SEED, seed);
                    }
                    activity.startActivity(i);
                    String text = "";
                    String diag = "timeout";
                    long deadline = System.currentTimeMillis() + 3000;
                    while (System.currentTimeMillis() < deadline) {
                        String seen = readSmall(Clip.token(activity));
                        if (seen.startsWith(token + "|")) {
                            text = readSmall(Clip.outFile(activity));
                            // token|<ts>|<diag...>
                            int second = seen.indexOf('|', token.length() + 1);
                            diag = second > 0 ? seen.substring(second + 1) : seen;
                            break;
                        }
                        try {
                            Thread.sleep(40);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    out.put("ok", true);
                    out.put("text", text);
                    out.put("diag", diag);
                    break;
                }
                case "clipboard_set": {
                    out.put("ok", a11y != null && a11y.clipboardSet(args.optString("text")));
                    break;
                }
                case "volume": {
                    out.put("ok", a11y != null && a11y.volume(args.optString("direction", "up")));
                    break;
                }
                case "dial": {
                    String number = args.optString("number");
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_DIAL,
                            android.net.Uri.parse("tel:" + number));
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(i);
                    out.put("ok", true);
                    break;
                }
                default:
                    out.put("ok", false).put("error", "unknown_intent");
                    break;
            }
            if (a11y == null && needsA11y) {
                out.put("error", "a11y_off");
            }
        } catch (Exception e) {
            try {
                out.put("ok", false).put("error", e.getClass().getSimpleName());
            } catch (Exception ignored) {
                // JSONObject.put only throws on NaN
            }
        }
        return out;
    }

    /** File -> text (small files only: the clipboard store, not a general file reader). */
    private static String readSmall(java.io.File f) {
        try {
            if (!f.exists()) {
                return "";
            }
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[(int) f.length()];
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
                return new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** File -> base64, or "" when unreadable. Used only for the screenshot inline path. */
    private static String base64(String path) {
        try {
            java.io.File f = new java.io.File(path);
            byte[] buf = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int read = 0;
                while (read < buf.length) {
                    int n = in.read(buf, read, buf.length - read);
                    if (n < 0) {
                        break;
                    }
                    read += n;
                }
            }
            return android.util.Base64.encodeToString(buf, android.util.Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private static void reply(Socket client, int code, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream os = client.getOutputStream();
        os.write(head.getBytes(StandardCharsets.UTF_8));
        os.write(bytes);
        os.flush();
    }
}
