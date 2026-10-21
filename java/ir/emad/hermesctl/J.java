package ir.emad.hermesctl;

import org.json.JSONArray;
import org.json.JSONObject;

/** Null-safe JSON accessors — the server omits keys rather than sending nulls. */
public final class J {

    private J() {
    }

    public static String str(JSONObject o, String key) {
        return str(o, key, "");
    }

    public static String str(JSONObject o, String key, String fallback) {
        if (o == null) {
            return fallback;
        }
        String v = o.optString(key, fallback);
        return (v == null || "null".equals(v)) ? fallback : v;
    }

    public static int i(JSONObject o, String key, int fallback) {
        return o == null ? fallback : o.optInt(key, fallback);
    }

    public static long l(JSONObject o, String key, long fallback) {
        return o == null ? fallback : o.optLong(key, fallback);
    }

    public static boolean b(JSONObject o, String key) {
        return o != null && o.optBoolean(key, false);
    }

    public static double d(JSONObject o, String key, double fallback) {
        return o == null ? fallback : o.optDouble(key, fallback);
    }

    public static boolean has(JSONObject o, String key) {
        return o != null && o.has(key) && !o.isNull(key);
    }

    public static JSONObject obj(JSONObject o, String key) {
        return o == null ? new JSONObject() : o.optJSONObject(key);
    }

    public static JSONArray arr(JSONObject o, String key) {
        JSONArray a = o == null ? null : o.optJSONArray(key);
        return a == null ? new JSONArray() : a;
    }

    public static JSONObject at(JSONArray a, int index) {
        if (a == null || index < 0 || index >= a.length()) {
            return new JSONObject();
        }
        JSONObject o = a.optJSONObject(index);
        return o == null ? new JSONObject() : o;
    }

    public static String[] keys(JSONObject o) {
        if (o == null) {
            return new String[0];
        }
        JSONArray names = o.names();
        if (names == null) {
            return new String[0];
        }
        String[] out = new String[names.length()];
        for (int i = 0; i < names.length(); i++) {
            out[i] = names.optString(i);
        }
        return out;
    }
}
