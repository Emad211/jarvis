package ir.emad.jarvis;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The phone's notification shade, for the agent, without adb and without Shizuku.
 *
 * A {@link NotificationListenerService} the user enables once in Settings → Notifications →
 * Device &amp; app notifications → Jarvis. While enabled it keeps the last {@link #CAP} posted
 * notifications in memory and answers {@link #handle} for {@link Bridge}. Nothing is written to
 * disk, ever: the list is a plain in-memory ring, and it is dropped when the service dies. The
 * same "dumb service, Bridge decides" split as {@link A11y}: this class holds no policy.
 *
 * Why in memory only: notification text is the most personal data on the phone (2FA codes,
 * private messages, bank alerts). Persisting it would put it in a file any backup or a stray
 * `run-as` could read, so it deliberately does not survive a process restart. If the agent needs
 * history, it is the caller's job to copy what it read into its own store.
 *
 * The service is also the only place that can *dismiss* someone else's notification on modern
 * Android, which is why {@code notif_clear} lives here rather than in {@link A11y}.
 */
public class Notif extends NotificationListenerService {

    private static final String TAG = "JarvisNotif";

    /** How many notifications we keep. The brief says "about 40"; this is that number. */
    static final int CAP = 40;

    /** Newest-first ring of what we have seen. Guarded by {@link #LOCK}. */
    private static final ArrayDeque<Item> KEEP = new ArrayDeque<>();
    private static final Object LOCK = new Object();

    /** Non-null while the user has the listener enabled. Same idea as {@code A11y.live}. */
    private static volatile Notif live;

    /** Set the first time the user turns the listener off; surfaced by {@link #lastError()}. */
    private static volatile String lastError = "";

    /** package -> label memo, so a list of 40 items does not hit PackageManager 40 times. */
    private static final Map<String, String> LABELS = new HashMap<>();

    /** Monotonic id source. Ids are used as cursors by {@code since_id}. */
    private static long nextId = 1;

    // ------------------------------------------------------------------ lifecycle --

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        live = this;
        lastError = "";
        // Seed from whatever is already in the shade, so the first list is not empty just
        // because the service started a second ago.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    remember(sbn);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "seed from active failed: " + e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        live = null;
        lastError = "listener_off";
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        live = null;
        synchronized (LOCK) {
            KEEP.clear();
        }
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        remember(sbn);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        String key = sbn.getKey();
        synchronized (LOCK) {
            Iterator<Item> it = KEEP.iterator();
            while (it.hasNext()) {
                if (key.equals(it.next().key)) {
                    it.remove();
                }
            }
        }
    }

    // --------------------------------------------------------------------- state --

    /** True when the user has enabled the listener in system settings. */
    static boolean ready() {
        return live != null;
    }

    /**
     * Ask the system to bind this listener again.
     *
     * Toggling the listener in Settings does not always bind a service that was already
     * registered before the toggle (measured 2026-10-27: the setting read back as enabled while
     * `ready()` stayed false), and the only old workaround was to reboot the phone.
     */
    static void rebind(Context ctx) {
        try {
            requestRebind(new android.content.ComponentName(ctx, Notif.class));
        } catch (Exception e) {
            Log.w(TAG, "rebind failed: " + e);
        }
    }

    /** Last failure, or "" when nothing has gone wrong since the listener connected. */
    static String lastError() {
        return lastError;
    }

    /** One remembered notification. Immutable once built. */
    private static final class Item {
        final long id;
        final String key;
        final String pkg;
        final String app;
        final String title;
        final String text;
        final long time;
        final boolean ongoing;

        Item(long id, String key, String pkg, String app, String title, String text,
             long time, boolean ongoing) {
            this.id = id;
            this.key = key;
            this.pkg = pkg;
            this.app = app;
            this.title = title;
            this.text = text;
            this.time = time;
            this.ongoing = ongoing;
        }

        JSONObject json() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("package", pkg);
                o.put("app", app);
                o.put("title", title);
                o.put("text", text);
                o.put("time", time);
                o.put("ongoing", ongoing);
            } catch (Exception ignored) {
                // JSONObject.put only throws on NaN/Infinity; none of these are numbers.
            }
            return o;
        }
    }

    /** Copy the fields we care about out of a {@link StatusBarNotification} into the ring. */
    private static void remember(StatusBarNotification sbn) {
        if (sbn == null) {
            return;
        }
        try {
            Notification n = sbn.getNotification();
            if (n == null) {
                return;
            }
            Bundle ex = n.extras;
            String title = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_TITLE));
            String text = ex == null ? "" : str(ex.getCharSequence(Notification.EXTRA_TEXT));
            if (text.isEmpty() && ex != null) {
                // Some apps (messaging styles) only fill the big-text variant.
                text = str(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
            }
            String pkg = sbn.getPackageName() == null ? "" : sbn.getPackageName();
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                    || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
            String key = sbn.getKey();
            if (key == null) {
                // getKey() is API 20+ and always present on the builds we ship to, but the
                // fallback keeps a null out of equals() below.
                key = pkg + "/" + nextId;
            }
            long time = sbn.getPostTime();
            String app = label(pkg);
            synchronized (LOCK) {
                // A repost of the same key replaces the old row instead of duplicating it.
                Iterator<Item> it = KEEP.iterator();
                long reuse = 0;
                while (it.hasNext()) {
                    Item old = it.next();
                    if (key.equals(old.key)) {
                        reuse = old.id;
                        it.remove();
                        break;
                    }
                }
                long id = reuse != 0 ? reuse : nextId++;
                KEEP.addFirst(new Item(id, key, pkg, app, title, text, time, ongoing));
                while (KEEP.size() > CAP) {
                    KEEP.removeLast();
                }
            }
            announce(pkg, app, title, text, time);
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName();
            Log.w(TAG, "remember failed: " + e);
        }
    }

    /** Last announce per package, so a chatty app cannot flood the daemon. */
    private static final Map<String, Long> ANNOUNCED = new HashMap<>();
    private static final long ANNOUNCE_GAP_MS = 2000;

    /**
     * Tell the daemon a notification arrived, so a `notification` automation can fire.
     *
     * The listener is the only place that sees this at the moment it happens; without the push
     * the daemon could only poll. Ours is skipped (an automation triggered by Jarvis's own
     * notification would loop) and repeats are throttled per package.
     */
    private static void announce(String pkg, String app, String title, String text, long time) {
        if (pkg == null || pkg.isEmpty() || (live != null && pkg.equals(live.getPackageName()))) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (ANNOUNCED) {
            Long last = ANNOUNCED.get(pkg);
            if (last != null && now - last < ANNOUNCE_GAP_MS) {
                return;
            }
            ANNOUNCED.put(pkg, now);
        }
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            body.put("kind", "notification");
            body.put("package", pkg);
            body.put("app", app == null ? "" : app);
            body.put("title", title == null ? "" : title);
            body.put("text", text == null ? "" : text);
            body.put("ts", time > 0 ? time / 1000.0 : now / 1000.0);
        } catch (org.json.JSONException ignored) {
            return;
        }
        Api.post("/automation/event", body, 4000, res -> { }, err -> { });
    }

    /** Best-effort app label; falls back to the package name. */
    private static String label(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return "";
        }
        synchronized (LABELS) {
            String cached = LABELS.get(pkg);
            if (cached != null) {
                return cached;
            }
        }
        String out = pkg;
        try {
            Context ctx = live;
            if (ctx != null) {
                PackageManager pm = ctx.getPackageManager();
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                CharSequence l = pm.getApplicationLabel(ai);
                if (l != null && l.length() > 0) {
                    out = l.toString();
                }
            }
        } catch (Exception ignored) {
            // uninstalled or hidden package: the package name is still useful
        }
        synchronized (LABELS) {
            if (LABELS.size() > 200) {
                LABELS.clear();
            }
            LABELS.put(pkg, out);
        }
        return out;
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    // -------------------------------------------------------------------- handle --

    /**
     * One request from {@link Bridge}. Never throws; always answers {@code {ok, ...}}.
     *
     * <ul>
     *   <li>{@code notif_list} — optional {@code package}, {@code since_id}, {@code limit}
     *       (default 40, capped at {@link #CAP})</li>
     *   <li>{@code notif_clear} — dismisses every clearable notification currently posted</li>
     * </ul>
     */
    static JSONObject handle(String intent, JSONObject args) {
        JSONObject out = new JSONObject();
        JSONObject a = args == null ? new JSONObject() : args;
        try {
            out.put("ok", true);
            out.put("intent", intent == null ? "" : intent);
            String what = intent == null ? "" : intent;
            switch (what) {
                case "notif_list":
                    list(out, a);
                    break;
                case "notif_clear":
                    clear(out);
                    break;
                default:
                    out.put("ok", false).put("error", "unknown_intent");
                    break;
            }
        } catch (Exception e) {
            try {
                out.put("ok", false).put("error", e.getClass().getSimpleName());
            } catch (Exception ignored) {
                // unreachable
            }
        }
        return out;
    }

    private static void list(JSONObject out, JSONObject args) throws Exception {
        out.put("ready", ready());
        if (!ready()) {
            // Not a crash and not a silent empty list: the caller must be able to tell the user
            // to flip one switch, so say exactly that.
            out.put("ok", false)
                    .put("error", "listener_off")
                    .put("needs", "notification_listener")
                    .put("items", new JSONArray());
            return;
        }
        String wantPkg = args.optString("package", "");
        long since = args.optLong("since_id", 0L);
        int limit = args.optInt("limit", CAP);
        if (limit <= 0 || limit > CAP) {
            limit = CAP;
        }
        JSONArray items = new JSONArray();
        long newest = 0;
        JSONObject active = null;
        synchronized (LOCK) {
            for (Item it : KEEP) {
                if (wantPkg != null && wantPkg.length() > 0 && !wantPkg.equals(it.pkg)) {
                    continue;
                }
                if (since > 0 && it.id <= since) {
                    // Ring is newest-first, so everything after this point is older than the
                    // cursor and can be skipped without scanning the rest.
                    break;
                }
                if (items.length() >= limit) {
                    break;
                }
                items.put(it.json());
                if (it.id > newest) {
                    newest = it.id;
                }
            }
            if (!KEEP.isEmpty()) {
                active = KEEP.peekFirst().json();
            }
        }
        out.put("items", items);
        out.put("count", items.length());
        out.put("since_id", newest);
        out.put("active_count", activeCount());
        out.put("last", active == null ? JSONObject.NULL : active);
    }

    /** Number of notifications currently in the shade, or -1 when we cannot see them. */
    private static int activeCount() {
        try {
            Notif n = live;
            if (n == null) {
                return -1;
            }
            StatusBarNotification[] a = n.getActiveNotifications();
            return a == null ? 0 : a.length;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void clear(JSONObject out) throws Exception {
        if (!ready()) {
            out.put("ok", false)
                    .put("error", "listener_off")
                    .put("needs", "notification_listener");
            return;
        }
        int dismissed = 0;
        int kept = 0;
        try {
            StatusBarNotification[] active = get(live);
            if (active != null) {
                for (StatusBarNotification sbn : active) {
                    Notification n = sbn.getNotification();
                    boolean ongoing = n != null && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
                    boolean noClear = n != null && (n.flags & Notification.FLAG_NO_CLEAR) != 0;
                    if (ongoing || noClear) {
                        // Music players, ongoing calls, system alerts: not ours to dismiss.
                        kept++;
                        continue;
                    }
                    try {
                        if (Build.VERSION.SDK_INT >= 21) {
                            live.cancelNotification(sbn.getKey());
                        } else {
                            live.cancelNotification(sbn.getPackageName(), sbn.getTag(), sbn.getId());
                        }
                        dismissed++;
                    } catch (Exception e) {
                        Log.w(TAG, "cancel failed: " + e);
                    }
                }
            }
        } catch (Exception e) {
            out.put("ok", false).put("error", "clear_failed").put("detail", String.valueOf(e));
            return;
        }
        synchronized (LOCK) {
            if (dismissed > 0) {
                KEEP.clear();
            }
        }
        out.put("dismissed", dismissed);
        out.put("kept", kept);
    }

    private static StatusBarNotification[] get(Notif n) {
        try {
            return n.getActiveNotifications();
        } catch (Exception e) {
            return null;
        }
    }

    /** Convenience for a caller that wants the whole list as a Java object. */
    static List<JSONObject> snapshot() {
        List<JSONObject> out = new ArrayList<>();
        synchronized (LOCK) {
            for (Item it : KEEP) {
                out.add(it.json());
            }
        }
        return out;
    }
}
