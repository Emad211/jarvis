package ir.emad.jarvis;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The phone's own data — calendar, contacts, SMS, installed apps — for the agent.
 *
 * Read-only on purpose. Every query is a plain {@link ContentResolver} read through a projection
 * that names its columns explicitly (never {@code null}, which would pull every column including
 * ones that may be absent on a given OEM build), and every intent that needs a runtime permission
 * checks for it *first* and answers {@code {ok:false, error, needs:"<permission>"}} instead of
 * letting the SecurityException escape. {@link Bridge} turns that into a sentence for the user.
 *
 * Why a static class and not a Service: none of this needs a component of its own. The context
 * comes from the caller ({@link Bridge} holds the live activity), so there is nothing to start,
 * bind to, or forget to unbind. SMS is read only — this app never sends, deletes or modifies a
 * message.
 */
final class Data {

    private static final String TAG = "JarvisData";

    /** Caps. A 20 000-row SMS thread would be useless to a model and expensive to serialise. */
    static final int MAX_CONTACTS = 10;
    static final int MAX_APPS = 40;
    static final int MAX_SMS = 20;
    static final int MAX_RANGE_DAYS = 31;

    /**
     * Reading a contact name needs READ_CONTACTS. READ_PROFILE is deliberately NOT used: it is
     * deprecated (API 29) and the provider drops it on newer builds, so requiring it would report
     * "permission" on a phone where contacts are perfectly readable.
     */
    private static final String[] READ_CONTACTS = {Manifest.permission.READ_CONTACTS};

    private Data() {
    }

    // -------------------------------------------------------------------- handle --

    /**
     * One request from {@link Bridge}. Never throws; always answers {@code {ok, ...}}.
     *
     * <ul>
     *   <li>{@code calendar_today} — today's events (local midnight → midnight)</li>
     *   <li>{@code calendar_range} — {@code {days}} from today, 1..{@link #MAX_RANGE_DAYS}</li>
     *   <li>{@code contacts_search} — {@code {query}}, max {@link #MAX_CONTACTS} rows</li>
     *   <li>{@code sms_recent} — {@code {limit}}, read-only inbox, max {@link #MAX_SMS}</li>
     *   <li>{@code apps_list} — {@code {query}}, launchable apps, max {@link #MAX_APPS}</li>
     * </ul>
     *
     * Success is {@code {ok:true, items:[...], count:n}}; a missing permission is
     * {@code {ok:false, error:"permission", needs:"<android permission name>"}}. An empty result
     * is still {@code ok:true} with {@code count:0} — "nothing found" and "not allowed" must never
     * look alike.
     */
    static JSONObject handle(Context ctx, String intent, JSONObject args) {
        JSONObject out = new JSONObject();
        JSONObject a = args == null ? new JSONObject() : args;
        String what = intent == null ? "" : intent;
        try {
            out.put("intent", what);
            if (ctx == null) {
                return out.put("ok", false).put("error", "no_context");
            }
            switch (what) {
                case "calendar_today":
                    return calendar(out, ctx, 1);
                case "calendar_range":
                    return calendar(out, ctx, a.optInt("days", 7));
                case "contacts_search":
                    return contacts(out, ctx, a.optString("query", ""));
                case "sms_recent":
                    return sms(out, ctx, a.optInt("limit", MAX_SMS));
                case "apps_list":
                    return apps(out, ctx, a.optString("query", ""));
                default:
                    return out.put("ok", false).put("error", "unknown_intent");
            }
        } catch (SecurityException e) {
            // A permission revoked between the check and the query is still a permission error,
            // not a crash — report it with the same shape so the caller's branch still works.
            try {
                return out.put("ok", false)
                        .put("error", "permission")
                        .put("needs", guessPermission(what))
                        .put("detail", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
                return out;
            }
        } catch (Exception e) {
            try {
                Log.w(TAG, what + " failed: " + e);
                return out.put("ok", false)
                        .put("error", e.getClass().getSimpleName())
                        .put("detail", String.valueOf(e.getMessage()));
            } catch (Exception ignored) {
                return out;
            }
        }
    }

    /** Which permission an intent would need; used only to label a SecurityException. */
    private static String guessPermission(String intent) {
        switch (intent == null ? "" : intent) {
            case "calendar_today":
            case "calendar_range":
                return Manifest.permission.READ_CALENDAR;
            case "contacts_search":
                return Manifest.permission.READ_CONTACTS;
            case "sms_recent":
                return Manifest.permission.READ_SMS;
            default:
                return "";
        }
    }

    /** {@code true} when every one of the given permissions is granted. */
    private static boolean granted(Context ctx, String... perms) {
        for (String p : perms) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private static JSONObject needs(JSONObject out, String perm, String why) throws Exception {
        return out.put("ok", false)
                .put("error", "permission")
                .put("needs", perm)
                .put("items", new JSONArray())
                .put("detail", why);
    }

    // ------------------------------------------------------------------ calendar --

    /**
     * Events overlapping [now, now+days).
     *
     * The query is the {@code CalendarContract.Events.CONTENT_URI} "instances" form
     * ({@link CalendarContract#CONTENT_URI}) rather than the raw events table, so recurring events
     * come back expanded — reading the events table would return the series definition instead of
     * the occurrence that actually happens on the requested day.
     *
     * Only DTSTART/DTEND/TITLE/ALL_DAY/EVENT_LOCATION are projected. Notably NOT
     * {@code Events.CALENDAR_ID} or {@code CALENDAR_DISPLAY_NAME}: they exist, but the latter is
     * not part of the documents projection and differs across OEMs.
     */
    private static JSONObject calendar(JSONObject out, Context ctx, int days) throws Exception {
        if (!granted(ctx, Manifest.permission.READ_CALENDAR)) {
            return needs(out, Manifest.permission.READ_CALENDAR, "calendar is not readable");
        }
        int n = days;
        if (n < 1) {
            n = 1;
        }
        if (n > MAX_RANGE_DAYS) {
            n = MAX_RANGE_DAYS;
        }
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);
        long from = start.getTimeInMillis();
        long to = from + (long) n * 24L * 60L * 60L * 1000L;

        // `instances/when/<from>/<to>` is the documented shape and the only one this phone's
        // provider accepts: the query-parameter form answered
        // `IllegalArgumentException: Invalid URI parameter: begin` (measured 2026-10-27).
        Uri.Builder b = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(b, from);
        ContentUris.appendId(b, to);
        String[] proj = {
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
        };
        JSONArray items = new JSONArray();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(
                    b.build(), proj,
                    CalendarContract.Instances.VISIBLE + "=1", null,
                    CalendarContract.Instances.BEGIN + " ASC");
            if (c != null) {
                int iBegin = c.getColumnIndex(proj[0]);
                int iEnd = c.getColumnIndex(proj[1]);
                int iTitle = c.getColumnIndex(proj[2]);
                int iAllDay = c.getColumnIndex(proj[3]);
                int iLoc = c.getColumnIndex(proj[4]);
                while (c.moveToNext() && items.length() < 200) {
                    JSONObject o = new JSONObject();
                    long begin = iBegin >= 0 ? c.getLong(iBegin) : 0L;
                    long end = iEnd >= 0 ? c.getLong(iEnd) : 0L;
                    o.put("id", items.length());
                    o.put("title", iTitle >= 0 ? c.getString(iTitle) : "");
                    o.put("begin", begin);
                    o.put("end", end);
                    o.put("all_day", iAllDay >= 0 && c.getInt(iAllDay) == 1);
                    o.put("location", iLoc >= 0 ? c.getString(iLoc) : "");
                    o.put("begin_iso", iso(begin));
                    o.put("end_iso", iso(end));
                    items.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        out.put("ok", true);
        out.put("items", items);
        out.put("count", items.length());
        out.put("from", from);
        out.put("to", to);
        out.put("days", n);
        return out;
    }

    private static String iso(long ms) {
        if (ms <= 0) {
            return "";
        }
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(ms));
    }

    // ------------------------------------------------------------------ contacts --

    /**
     * Contacts matching {@code query} by display name.
     *
     * Uses the Contacts {@code CONTENT_FILTER_URI}, which does the matching server-side (the
     * provider's own normalisation handles Arabic/Persian yeh and kaf variants better than a
     * LIKE on DISPLAY_NAME would). Only {@code DISPLAY_NAME} and {@code _ID} are projected —
     * deliberately not phone numbers or emails: the brief says search, and pulling a number for
     * every row would widen what leaves the phone for no benefit.
     */
    private static JSONObject contacts(JSONObject out, Context ctx, String query) throws Exception {
        if (!granted(ctx, READ_CONTACTS)) {
            return needs(out, Manifest.permission.READ_CONTACTS, "contacts are not readable");
        }
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return out.put("ok", false).put("error", "query_required")
                    .put("items", new JSONArray())
                    .put("detail", "contacts_search needs {query}");
        }
        JSONArray items = new JSONArray();
        Cursor c = null;
        try {
            // Contactables (API 23+) is the union view over every data row, so one query finds a
            // contact whether the match came from the name, a phone number or an email. Its
            // FILTER uri is the documented "search contacts" entry point.
            Uri uri = Uri.withAppendedPath(
                    ContactsContract.CommonDataKinds.Contactables.CONTENT_FILTER_URI,
                    Uri.encode(q));
            String[] proj = {
                    ContactsContract.CommonDataKinds.Contactables.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Contactables.CONTACT_ID,
            };
            c = ctx.getContentResolver().query(uri, proj, null, null, null);
            if (c != null) {
                int iName = c.getColumnIndex(proj[0]);
                int iId = c.getColumnIndex(proj[1]);
                Map<String, JSONObject> seen = new LinkedHashMap<>();
                while (c.moveToNext() && seen.size() < MAX_CONTACTS) {
                    String name = iName >= 0 ? c.getString(iName) : "";
                    long id = iId >= 0 ? c.getLong(iId) : 0L;
                    String key = id + "|" + (name == null ? "" : name);
                    if (seen.containsKey(key)) {
                        // Contactables returns one row per data row (name, phone, email…), so
                        // the same person appears several times; collapse to one.
                        continue;
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", String.valueOf(id));
                    o.put("name", name == null ? "" : name);
                    seen.put(key, o);
                    items.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        out.put("ok", true);
        out.put("items", items);
        out.put("count", items.length());
        out.put("query", q);
        return out;
    }

    // ----------------------------------------------------------------------- sms --

    /**
     * The newest {@code limit} inbox messages, read-only.
     *
     * {@link Telephony.Sms#Inbox} carries a hard-coded {@code type=1} in its sort/selection, so
     * it already excludes sent/draft/outbox rows. Address, body, date and read-flag are
     * projected. Bodies are truncated to keep one pathological SMS from dominating the payload.
     */
    private static JSONObject sms(JSONObject out, Context ctx, int limit) throws Exception {
        if (!granted(ctx, Manifest.permission.READ_SMS)) {
            return needs(out, Manifest.permission.READ_SMS, "messages are not readable");
        }
        int n = limit <= 0 ? MAX_SMS : Math.min(limit, MAX_SMS);
        JSONArray items = new JSONArray();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                            Telephony.Sms.DATE, Telephony.Sms.READ},
                    null, null, Telephony.Sms.DATE + " DESC");
            if (c != null) {
                int iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS);
                int iBody = c.getColumnIndex(Telephony.Sms.BODY);
                int iDate = c.getColumnIndex(Telephony.Sms.DATE);
                int iRead = c.getColumnIndex(Telephony.Sms.READ);
                while (c.moveToNext() && items.length() < n) {
                    String body = iBody >= 0 ? c.getString(iBody) : "";
                    if (body != null && body.length() > 400) {
                        body = body.substring(0, 400) + "…";
                    }
                    JSONObject o = new JSONObject();
                    o.put("id", items.length());
                    o.put("from", iAddr >= 0 ? c.getString(iAddr) : "");
                    o.put("text", body == null ? "" : body);
                    long date = iDate >= 0 ? c.getLong(iDate) : 0L;
                    o.put("date", date);
                    o.put("date_iso", iso(date));
                    o.put("read", iRead >= 0 && c.getInt(iRead) == 1);
                    items.put(o);
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        out.put("ok", true);
        out.put("items", items);
        out.put("count", items.length());
        out.put("limit", n);
        return out;
    }

    // ---------------------------------------------------------------------- apps --

    /**
     * Launchable apps whose label or package matches {@code query} (empty query = all).
     *
     * Uses the launcher {@code ACTION_MAIN}/{@code CATEGORY_LAUNCHER} query rather than
     * {@code getInstalledApplications}, because that is exactly the set the user can see and the
     * agent can meaningfully "open".
     */
    private static JSONObject apps(JSONObject out, Context ctx, String query) throws Exception {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
        List<JSONObject> rows = new ArrayList<>();
        try {
            for (ResolveInfo ri : resolved) {
                if (ri == null || ri.activityInfo == null) {
                    continue;
                }
                String pkg = ri.activityInfo.packageName == null ? "" : ri.activityInfo.packageName;
                CharSequence lbl = ri.loadLabel(pm);
                String label = lbl == null ? pkg : lbl.toString();
                if (!q.isEmpty()
                        && !label.toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !pkg.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    continue;
                }
                JSONObject o = new JSONObject();
                o.put("package", pkg);
                o.put("label", label);
                o.put("activity", ri.activityInfo.name == null ? "" : ri.activityInfo.name);
                rows.add(o);
                if (rows.size() >= MAX_APPS * 4) {
                    // Enough candidates before sorting; avoid building a huge list.
                    break;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "apps scan: " + e);
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject x, JSONObject y) {
                return x.optString("label", "").compareToIgnoreCase(y.optString("label", ""));
            }
        });
        JSONArray items = new JSONArray();
        for (JSONObject o : rows) {
            if (items.length() >= MAX_APPS) {
                break;
            }
            items.put(o);
        }
        out.put("ok", true);
        out.put("items", items);
        out.put("count", items.length());
        out.put("query", q);
        out.put("truncated", rows.size() > items.length());
        return out;
    }

    // -------------------------------------------------------------------- helpers --

    /** Which runtime permissions this class can need, for the caller's request flow. */
    static String[] permissions() {
        return new String[]{
                Manifest.permission.READ_CALENDAR,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_SMS,
        };
    }

    /** {@code true} when the intent needs no runtime permission (apps_list). */
    static boolean needsNoPermission(String intent) {
        return "apps_list".equals(intent);
    }

    /** Single-row convenience used by tests and by the daemon's status probe. */
    static JSONObject available(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            out.put("calendar", granted(ctx, Manifest.permission.READ_CALENDAR));
            out.put("contacts", granted(ctx, READ_CONTACTS));
            out.put("sms", granted(ctx, Manifest.permission.READ_SMS));
            out.put("apps", true);
        } catch (Exception ignored) {
            // JSONObject.put is the only throwing call and these are booleans
        }
        return out;
    }
}
