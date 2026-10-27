package ir.emad.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The surfaces that used to live in a second app (Hermes Control): personas, the tool shelf
 * (skills / MCP / plugins), cron jobs, and services + logs. They are plain sheets over the
 * same daemon, so one install gives the user everything.
 *
 * Everything here is read-mostly and stateless: fetch, render, act, refetch. The daemon owns
 * every write, so a half-broken panel can never corrupt state.
 */
final class Panels {

    interface Done {
        void on();
    }

    /** Hands a chosen session id back to the chat screen ("" means "start a new one"). */
    interface Open {
        void to(String sessionId);
    }

    private Panels() {
    }

    // -------------------------------------------------------------------- chats --
    /**
     * Past conversations. Tap switches to one, hold offers the destructive actions — the list
     * is the daemon's own `/sessions`, so a conversation deleted here is deleted on disk.
     */
    static void chats(Activity a, String current, Open open, Done after) {
        Api.get("/sessions", 15000, res -> {
            JSONArray arr = res.optJSONArray("sessions");
            int n = arr == null ? 0 : arr.length();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.section(a, n == 0 ? "هنوز گفتگویی نیست" : n + " گفتگو"), Ui.fill());
            box.addView(Sheet.item(a, "گفتگوی تازه", "شروع از صفر", Ui.ACCENT,
                    () -> open.to("")), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            for (int i = 0; i < n; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                final String id = s.optString("id", "");
                final String title = s.optString("title", "").isEmpty()
                        ? "بدون عنوان" : s.optString("title", "");
                String model = ChatAdapter.shortModel(s.optString("model", ""));
                String sub = s.optInt("turns", 0) + " نوبت"
                        + (model.isEmpty() ? "" : " · " + model)
                        + (s.optBoolean("busy") ? " · در حال اجرا" : "");
                String when = Ui.ago(s.optDouble("updated", 0));
                if (!when.isEmpty()) {
                    sub += " · " + when;
                }
                box.addView(Sheet.item(a, title, sub, id.equals(current) ? Ui.ACCENT : null,
                        () -> open.to(id), () -> chatActions(a, id, title, after)), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            Sheet.show(a, "گفتگوها", box);
        }, msg -> toast(a, "لیست نیامد: " + msg));
    }

    private static void chatActions(Activity a, String id, String title, Done after) {
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.item(a, "حذف این گفتگو", title, Ui.RED, () ->
                Sheet.confirm(a, "حذف گفتگو",
                        "«" + title + "» و همهٔ پیام‌هایش پاک می‌شود. برگشتی ندارد.",
                        "حذف کن", Ui.RED, () -> Api.delete("/session/" + id, 20000, res -> {
                            toast(a, "پاک شد");
                            if (after != null) {
                                after.on();
                            }
                        }, msg -> toast(a, "پاک نشد: " + msg)))), Ui.fill());
        Sheet.show(a, title, box);
    }

    // ------------------------------------------------------------------- personas --
    /** Personas are prompt layers, not models: switching one rebuilds the warm agent. */
    static void personas(Activity a, String sessionId, String current, Done after) {
        AlertDialog spin = busy(a, "شخصیت‌ها…");
        Api.getArray("/personas", 20000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.section(a, "یک شخصیت انتخاب کن"), Ui.fill());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                final String id = p.optString("id", "");
                final String label = p.optString("label", id);
                boolean active = id.equals(current) || p.optBoolean("active", false);
                String sub = p.optString("description", "");
                box.addView(Sheet.item(a, p.optString("emoji", "") + "  " + label,
                        active ? "فعال" + (sub.isEmpty() ? "" : " · " + sub) : sub,
                        active ? Ui.ACCENT : null,
                        () -> bind(a, sessionId, id, label, after)), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            box.addView(Sheet.item(a, "شخصیت تازه", "نام و دستورِ کوتاهِ رفتار", null,
                    () -> newPersona(a, after)), Ui.fill());
            Sheet.show(a, "شخصیت‌ها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "لیست نیامد: " + msg);
        });
    }

    private static void bind(Activity a, String sessionId, String id, String label, Done after) {
        if (sessionId == null || sessionId.isEmpty()) {
            toast(a, "اول یک گفتگو بساز");
            return;
        }
        Api.post("/session/" + sessionId + "/persona", Api.body("id", id), 40000, res -> {
            toast(a, res.optBoolean("ok", false) ? "شخصیت: " + label : "نشد");
            if (after != null) {
                after.on();
            }
        }, msg -> toast(a, "نشد: " + msg));
    }

    private static void newPersona(Activity a, Done after) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText label = Ui.input(a, "نام (مثلاً برنامه‌نویس)");
        final EditText prompt = Ui.input(a, "چطور حرف بزند و چه کاری بکند");
        prompt.setSingleLine(false);
        prompt.setMinLines(4);
        prompt.setGravity(Gravity.TOP | Gravity.END);
        box.addView(Sheet.section(a, "نام"), Ui.fill());
        box.addView(label, Ui.fill());
        box.addView(Sheet.section(a, "دستور"), Ui.fill());
        box.addView(prompt, Ui.fill());
        box.addView(Sheet.item(a, "ذخیره", null, Ui.GREEN, () -> {
            String name = label.getText().toString().trim();
            String body = prompt.getText().toString().trim();
            if (name.isEmpty() || body.isEmpty()) {
                toast(a, "نام و دستور لازم است");
                return;
            }
            Api.post("/personas", Api.body("label", name, "prompt", body), 30000, res -> {
                toast(a, res.optBoolean("ok", false) ? "ساخته شد" : "نشد");
                if (after != null) {
                    after.on();
                }
            }, msg -> toast(a, "نشد: " + msg));
        }), Ui.fill());
        Sheet.show(a, "شخصیت تازه", box);
    }

    // ---------------------------------------------------------------------- tools --
    /** What the agent can actually do: skills it may load, MCP servers, plugins. */
    static void tools(Activity a) {
        AlertDialog spin = busy(a, "ابزارها…");
        Api.getArray("/skills", 60000, skills -> Api.getArray("/mcp", 20000, mcp -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            int on = 0;
            for (int i = 0; i < skills.length(); i++) {
                JSONObject s = skills.optJSONObject(i);
                if (s != null && s.optBoolean("enabled", false)) {
                    on++;
                }
            }
            box.addView(Sheet.section(a, "مهارت‌ها  " + on + " از " + skills.length() + " روشن"),
                    Ui.fill());
            box.addView(Sheet.item(a, "فهرست مهارت‌ها", "روشن/خاموش کردن هر مهارت", null,
                    () -> skillList(a)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());

            box.addView(Sheet.section(a, "سرورهای MCP  " + mcp.length()), Ui.fill());
            for (int i = 0; i < mcp.length(); i++) {
                JSONObject m = mcp.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                String name = m.optString("name", "");
                JSONArray tools = m.optJSONArray("tools");
                String sub = m.optString("target", "")
                        + (tools != null ? " · " + tools.length() + " ابزار" : "");
                box.addView(Sheet.item(a, name, sub, m.optBoolean("enabled", true) ? Ui.GREEN : null,
                        () -> mcpActions(a, name)), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            if (mcp.length() == 0) {
                box.addView(Sheet.item(a, "سروری وصل نیست",
                        "هر سرور MCP با آدرس یا فرمان اضافه می‌شود", null,
                        () -> addMcp(a)), Ui.fill());
            } else {
                box.addView(Sheet.item(a, "افزودن سرور MCP", null, null, () -> addMcp(a)),
                        Ui.fill());
            }
            box.addView(Sheet.divider(a), Ui.fill());
            box.addView(Sheet.item(a, "پلاگین‌ها", "افزونه‌های بومی هرمس", null,
                    () -> plugins(a)), Ui.fill());
            Sheet.show(a, "ابزارها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "MCP نیامد: " + msg);
        }), msg -> {
            spin.dismiss();
            toast(a, "مهارت‌ها نیامد: " + msg);
        });
    }

    private static void skillList(Activity a) {
        AlertDialog spin = busy(a, "مهارت‌ها…");
        Api.getArray("/skills", 60000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject s = rows.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                final String id = s.optString("id", "");
                final boolean enabled = s.optBoolean("enabled", false);
                String sub = s.optString("scope", "") + " · " + s.optString("source", "");
                box.addView(Sheet.item(a, s.optString("name", id),
                        enabled ? "روشن · " + sub : "خاموش · " + sub,
                        enabled ? Ui.GREEN : Ui.FAINT,
                        () -> {
                            Api.post("/skills/" + id + "/toggle",
                                    Api.body("enabled", !enabled), 30000,
                                    res -> toast(a, res.optBoolean("ok", false)
                                            ? (enabled ? "خاموش شد" : "روشن شد") : "نشد"),
                                    msg -> toast(a, "نشد: " + msg));
                        }), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            Sheet.show(a, "مهارت‌ها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "لیست نیامد: " + msg);
        });
    }

    private static void mcpActions(Activity a, String name) {
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.item(a, "تست اتصال", "یک بار وصل می‌شود و ابزارها را می‌شمارد",
                null, () -> {
                    AlertDialog spin = busy(a, "تست…");
                    Api.post("/mcp/" + name + "/test", Api.body(), 60000, res -> {
                        spin.dismiss();
                        JSONArray tools = res.optJSONArray("tools");
                        toast(a, res.optBoolean("ok", false)
                                ? "وصل شد · " + (tools == null ? 0 : tools.length()) + " ابزار"
                                : "نشد: " + res.optString("error", "?"));
                    }, msg -> {
                        spin.dismiss();
                        toast(a, "نشد: " + msg);
                    });
                }), Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "حذف", name, null,
                () -> Api.delete("/mcp/" + name, 20000, res -> toast(a, "حذف شد"),
                        msg -> toast(a, "نشد: " + msg))), Ui.fill());
        Sheet.show(a, name, box);
    }

    private static void addMcp(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText name = Ui.input(a, "نام (مثلاً files)");
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText url = Ui.input(a, "https://host/mcp");
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(Sheet.section(a, "نام"), Ui.fill());
        box.addView(name, Ui.fill());
        box.addView(Sheet.section(a, "آدرس سرور MCP"), Ui.fill());
        box.addView(url, Ui.fill());
        box.addView(Sheet.item(a, "افزودن", null, Ui.GREEN, () -> {
            String n = name.getText().toString().trim();
            String u = url.getText().toString().trim();
            if (n.isEmpty() || u.isEmpty()) {
                toast(a, "نام و آدرس لازم است");
                return;
            }
            Api.post("/mcp", Api.body("name", n, "url", u), 60000, res ->
                            toast(a, res.optBoolean("ok", false) ? "اضافه شد" : "نشد"),
                    msg -> toast(a, "نشد: " + msg));
        }), Ui.fill());
        Sheet.show(a, "سرور MCP تازه", box);
    }

    private static void plugins(Activity a) {
        AlertDialog spin = busy(a, "پلاگین‌ها…");
        Api.getArray("/plugins", 40000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                final String name = p.optString("name", "");
                final boolean enabled = p.optBoolean("enabled", false);
                box.addView(Sheet.item(a, name,
                        (enabled ? "روشن" : "خاموش") + (p.optString("version").isEmpty()
                                ? "" : " · " + p.optString("version")),
                        enabled ? Ui.GREEN : Ui.FAINT,
                        () -> Api.post("/plugins/" + name + "/toggle",
                                Api.body("enabled", !enabled), 40000,
                                res -> toast(a, res.optBoolean("ok", false)
                                        ? "تغییر کرد" : "نشد"),
                                msg -> toast(a, "نشد: " + msg))), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            if (rows.length() == 0) {
                box.addView(Ui.text(a, "پلاگینی نصب نیست", 12.5f, Ui.FAINT, false), Ui.fill());
            }
            Sheet.show(a, "پلاگین‌ها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    // ----------------------------------------------------------------------- cron --
    static void cron(Activity a) {
        AlertDialog spin = busy(a, "زمان‌بندی…");
        Api.getArray("/cron", 25000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.item(a, "کار تازه", "روزی یک بار، هر ساعت، هر روز…", null,
                    () -> newCron(a)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            if (rows.length() == 0) {
                box.addView(Ui.text(a, "هنوز کاری زمان‌بندی نشده", 12.5f, Ui.FAINT, false),
                        Ui.fill());
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject j = rows.optJSONObject(i);
                if (j == null) {
                    continue;
                }
                final String id = j.optString("id", "");
                boolean enabled = j.optBoolean("enabled", true);
                String when = clock(j.optString("next_run", ""));
                String state = j.optString("last_status", "");
                // Persian label first, machine values after an isolate: without it the ISO
                // stamp and the cron expression get shuffled into the middle of the sentence.
                String sub = "بعدی " + when
                        + (state.isEmpty() ? "" : " · آخرین " + state)
                        + " · " + ltr(j.optString("schedule", ""));
                box.addView(Sheet.item(a, j.optString("name", id), sub,
                        enabled ? Ui.GREEN : Ui.FAINT, () -> cronActions(a, id, j.optString("name"))),
                        Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            Sheet.show(a, "زمان‌بندی", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    private static void cronActions(Activity a, String id, String name) {
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.item(a, "همین حالا اجرا کن", null, null,
                () -> Api.post("/cron/" + id + "/run", Api.body(), 30000,
                        res -> toast(a, "به صف رفت"), msg -> toast(a, "نشد: " + msg))),
                Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "توقف موقت", null, null,
                () -> Api.post("/cron/" + id + "/pause", Api.body("paused", true), 30000,
                        res -> toast(a, "متوقف شد"), msg -> toast(a, "نشد: " + msg))), Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "از سر گرفتن", null, null,
                () -> Api.post("/cron/" + id + "/pause", Api.body("paused", false), 30000,
                        res -> toast(a, "فعال شد"), msg -> toast(a, "نشد: " + msg))), Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "حذف", name, null,
                () -> Api.delete("/cron/" + id, 30000, res -> toast(a, "حذف شد"),
                        msg -> toast(a, "نشد: " + msg))), Ui.fill());
        Sheet.show(a, name, box);
    }

    private static void newCron(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText name = Ui.input(a, "نام کار (مثلاً خلاصه صبح)");
        final EditText prompt = Ui.input(a, "چه کاری انجام دهد");
        prompt.setSingleLine(false);
        prompt.setMinLines(3);
        prompt.setGravity(Gravity.TOP | Gravity.END);
        final EditText sched = Ui.input(a, "0 9 * * *");
        sched.setTextDirection(View.TEXT_DIRECTION_LTR);
        sched.setText("0 9 * * *");
        box.addView(Sheet.section(a, "نام"), Ui.fill());
        box.addView(name, Ui.fill());
        box.addView(Sheet.section(a, "دستور"), Ui.fill());
        box.addView(prompt, Ui.fill());
        box.addView(Sheet.section(a, "زمان (cron: دقیقه ساعت روز ماه هفته)"), Ui.fill());
        box.addView(sched, Ui.fill());
        box.addView(Sheet.item(a, "ذخیره", null, Ui.GREEN, () -> {
            String n = name.getText().toString().trim();
            String p = prompt.getText().toString().trim();
            String s = sched.getText().toString().trim();
            if (n.isEmpty() || p.isEmpty() || s.isEmpty()) {
                toast(a, "همه فیلدها لازم است");
                return;
            }
            Api.post("/cron", Api.body("name", n, "prompt", p, "schedule", s), 40000,
                    res -> toast(a, res.optBoolean("ok", false) ? "ساخته شد" : "نشد"),
                    msg -> toast(a, "نشد: " + msg));
        }), Ui.fill());
        Sheet.show(a, "کار تازه", box);
    }

    // ------------------------------------------------------------------- services --
    static void services(Activity a) {
        AlertDialog spin = busy(a, "سرویس‌ها…");
        Api.getArray("/services", 20000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.section(a, "سرویس‌های گوشی"), Ui.fill());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject s = rows.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                final String name = s.optString("name", "");
                boolean up = "running".equals(s.optString("state", ""));
                long upSec = s.optLong("uptime_s", 0);
                String sub = (up ? "روشن" : "خاموش")
                        + (up ? " · " + human(upSec) : "")
                        + (s.optInt("port", 0) > 0 ? " · :" + s.optInt("port") : "");
                box.addView(Sheet.item(a, name, sub, up ? Ui.GREEN : Ui.RED,
                        () -> serviceActions(a, name)), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            box.addView(Sheet.item(a, "لاگ جارویس", "آخرین خطوط", null,
                    () -> logs(a, "jarvis.log")), Ui.fill());
            Sheet.show(a, "سرویس‌ها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    private static void serviceActions(Activity a, String name) {
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.item(a, "ری‌استارت", name + " بدون بستن گفتگو", null, () -> {
            AlertDialog spin = busy(a, "ری‌استارت…");
            Api.post("/services/" + name + "/restart", Api.body(), 60000,
                    res -> {
                        spin.dismiss();
                        toast(a, res.optBoolean("ok", false) ? "ری‌استارت شد" : "نشد");
                    }, msg -> {
                        spin.dismiss();
                        toast(a, "نشد: " + msg);
                    });
        }), Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "لاگ این سرویس", null, null,
                () -> logs(a, name + ".log")), Ui.fill());
        Sheet.show(a, name, box);
    }

    static void logs(Activity a, String file) {
        AlertDialog spin = busy(a, "لاگ…");
        Api.get("/logs?name=" + file + "&n=120", 25000, res -> {
            spin.dismiss();
            JSONArray lines = res.optJSONArray("lines");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; lines != null && i < lines.length(); i++) {
                sb.append(lines.optString(i, "")).append('\n');
            }
            LinearLayout box = Ui.col(a);
            box.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 12));
            android.widget.TextView text = Ui.mono(a,
                    sb.length() == 0 ? "خالی" : sb.toString(), 10.5f, Ui.DIM);
            text.setTextDirection(View.TEXT_DIRECTION_LTR);
            text.setGravity(Gravity.START);
            text.setTextIsSelectable(true);
            box.addView(text, Ui.fill());
            Sheet.show(a, file, box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    // --------------------------------------------------------------------- learned --
    /**
     * The visible half of self-improvement: every skill the agent wrote or rewrote, newest
     * first, plus what the curator last did. Without this the learning is invisible, and an
     * invisible feature may as well not exist.
     */
    static void learned(Activity a) {
        AlertDialog spin = busy(a, "دفتر یادگیری…");
        Api.getArray("/skills/feed?limit=60", 30000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.section(a, "چه یاد گرفت  " + rows.length() + " مورد"), Ui.fill());
            if (rows.length() == 0) {
                box.addView(Ui.text(a, "هنوز چیزی یاد نگرفته — اولین کاری که انجام بدهد، "
                        + "مهارتش را می‌نویسد.", 12.5f, Ui.FAINT, false), Ui.fill());
            }
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                String kind = r.optString("kind", "");
                int color = "learned".equals(kind) ? Ui.GREEN
                        : "archived".equals(kind) ? Ui.FAINT : Ui.ACCENT;
                // The feed's `detail` repeats the skill name that is already in the title, and
                // mixing it in turned every row into a bidi tangle. Kind + time is enough.
                String sub = ("learned".equals(kind) ? "یاد گرفت"
                        : "archived".equals(kind) ? "بایگانی شد" : "ویرایش کرد")
                        + " · " + clock(r.optLong("at", 0));
                box.addView(Sheet.item(a, r.optString("title", ""), sub, color,
                        () -> expertise(a, r.optString("skill", r.optString("id", "")))),
                        Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            box.addView(Sheet.section(a, "نگهبان حافظه"), Ui.fill());
            box.addView(Sheet.item(a, "الان مرور کن",
                            "مهارت‌های تکراری را ادغام و کهنه‌ها را بایگانی می‌کند", null,
                            () -> Api.post("/curator/run", Api.body(), 40000,
                                    res -> toast(a, res.optBoolean("started", false)
                                            ? "شروع شد" : "همین حالا در حال اجراست"),
                                    msg -> toast(a, "نشد: " + msg))), Ui.fill());
            Sheet.show(a, "یادگیری", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    /** One learned skill: its file, so the user can see exactly what the agent wrote. */
    private static void expertise(Activity a, String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        AlertDialog spin = busy(a, "مهارت…");
        Api.getArray("/skills?q=" + id, 40000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 12));
            JSONObject hit = null;
            for (int i = 0; i < rows.length(); i++) {
                JSONObject r = rows.optJSONObject(i);
                if (r != null && id.equals(r.optString("id", ""))) {
                    hit = r;
                    break;
                }
            }
            if (hit == null) {
                box.addView(Ui.text(a, "پیدا نشد", 12.5f, Ui.FAINT, false), Ui.fill());
            } else {
                box.addView(Sheet.section(a, hit.optString("id", "")), Ui.fill());
                box.addView(Ui.text(a, hit.optString("description", ""), 13f, Ui.DIM, false),
                        Ui.fill());
                box.addView(Sheet.section(a, "استفاده  " + hit.optInt("uses", 0) + " بار"), Ui.fill());
            }
            Sheet.show(a, "مهارت", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    // ----------------------------------------------------------------------- voice --
    /**
     * Voice settings with full control: which engine speaks, which voice, which model.
     * ElevenLabs has the Persian voices (Roya, Hooman, Connery…) and answers in ~2.4 s;
     * Gemini is free but needs quota. The choice is stored by the daemon, not by the app.
     */
    static void voice(Activity a) {
        AlertDialog spin = busy(a, "تنظیمات صدا…");
        Api.get("/voice/config", 20000, cfg -> {
            spin.dismiss();
            String engine = cfg.optString("tts_provider", "gemini");
            JSONObject keys = cfg.optJSONObject("has_key");
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.section(a, "موتور صدا"), Ui.fill());
            for (String id : new String[]{"elevenlabs", "gemini"}) {
                boolean hasKey = keys != null && keys.optBoolean(id, false);
                boolean on = id.equals(engine);
                String label = "elevenlabs".equals(id)
                        ? "ElevenLabs — صداهای فارسی" : "Gemini — رایگان با سهمیه";
                String sub = on ? "فعال" : (hasKey ? "کلید دارد" : "بدون کلید");
                box.addView(Sheet.item(a, label, sub, on ? Ui.ACCENT
                        : (hasKey ? Ui.GREEN : null),
                        () -> setVoice(a, Api.body("tts_provider", id), "موتور صدا")), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }

            String current = "elevenlabs".equals(engine)
                    ? cfg.optString("el_voice_id", "") : cfg.optString("tts_voice", "");
            box.addView(Sheet.section(a, "صدا"), Ui.fill());
            box.addView(Sheet.item(a, "انتخاب صدا",
                            current.isEmpty() ? "فهرست صداهای همین موتور" : "فعلی: " + current,
                            null, () -> voicePicker(a, engine, current)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            box.addView(Sheet.item(a, "مدل", "elevenlabs".equals(engine)
                    ? cfg.optString("el_model", "") : cfg.optString("tts_model", ""), null,
                    () -> modelPicker(a, engine)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            box.addView(Sheet.item(a, "یک جمله تست کن",
                    "«سلام عماد، من جارویس هستم»", null, () -> {
                        Api.post("/voice/out", Api.body("text",
                                "سلام عماد، من جارویس هستم و آماده‌ام."), 90000, res -> {
                            if (!res.optBoolean("ok", false)) {
                                toast(a, "صدا نیامد: " + res.optString("error", "?"));
                                return;
                            }
                            toast(a, res.optString("model", "") + " · " + res.optInt("ms", 0) + "ms");
                        }, msg -> toast(a, "نشد: " + msg));
                    }), Ui.fill());
            Sheet.show(a, "صدا", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "تنظیمات صدا نیامد: " + msg);
        });
    }

    private static void setVoice(Activity a, JSONObject body, String what) {
        Api.post("/voice/config", body, 20000, res -> {
            toast(a, res.optBoolean("ok", false) ? what + " ثبت شد" : "نشد");
            voice(a);
        }, msg -> toast(a, "نشد: " + msg));
    }

    private static void voicePicker(Activity a, String engine, String current) {
        AlertDialog spin = busy(a, "صداها…");
        Api.get("/voice/voices?engine=" + engine, 30000, res -> {
            spin.dismiss();
            JSONArray rows = res.optJSONArray("voices");
            LinearLayout box = Ui.col(a);
            if (rows == null || rows.length() == 0) {
                box.addView(Ui.text(a, "فهرست صداها نیامد", 12.5f, Ui.FAINT, false), Ui.fill());
            }
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject v = rows.optJSONObject(i);
                if (v == null) {
                    continue;
                }
                final String id = v.optString("id", "");
                String name = v.optString("name", id);
                String sub = v.optString("lang", "") + (v.optString("accent").isEmpty()
                        ? "" : " · " + v.optString("accent"));
                boolean on = id.equals(current);
                box.addView(Sheet.item(a, name, on ? "فعال" : sub, on ? Ui.ACCENT : null,
                        () -> setVoice(a, "elevenlabs".equals(engine)
                                        ? Api.body("el_voice_id", id)
                                        : Api.body("tts_voice", id), "صدا")), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            Sheet.show(a, "صدا", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    private static void modelPicker(Activity a, String engine) {
        LinearLayout box = Ui.col(a);
        String[] models = "elevenlabs".equals(engine)
                ? new String[]{"eleven_v3", "eleven_v4_turbo", "eleven_multilingual_v2", "eleven_turbo_v2_5"}
                : new String[]{"gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts",
                "gemini-3.1-flash-tts-preview"};
        for (String m : models) {
            box.addView(Sheet.item(a, m, "elevenlabs".equals(engine) ? "ElevenLabs" : "Gemini",
                    null, () -> setVoice(a, "elevenlabs".equals(engine)
                            ? Api.body("el_model", m) : Api.body("tts_model", m), "مدل")), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
        }
        Sheet.show(a, "مدل صدا", box);
    }

    // -------------------------------------------------------------------- toolsets --
    /**
     * How much of itself the agent may use for this conversation. The old build silently ran a
     * seven-tool subset, which is why it could not search the web or write code; the presets
     * make that a visible choice instead of a hidden default.
     */
    static void toolsets(Activity a, String sessionId, String current, Done after) {
        String now = current == null || current.isEmpty() ? "smart" : current;
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.section(a, "حالت ابزار برای این گفتگو"), Ui.fill());
        String[][] rows = {
                {"fast", "سریع", "گفتگو و کنترل گوشی؛ بدون وب و بدون کد"},
                {"smart", "هوشمند", "وب، کد، حافظه، مهارت، تیم‌سازی و زمان‌بندی"},
                {"full", "کامل", "همه‌چیز: مرورگر، تصویر، کامپیوتر و صدا"},
        };
        for (String[] row : rows) {
            boolean on = row[0].equals(now);
            box.addView(Sheet.item(a, row[1] + "  (" + row[0] + ")",
                    on ? "فعال · " + row[2] : row[2], on ? Ui.ACCENT : null,
                    () -> {
                        if (sessionId == null || sessionId.isEmpty()) {
                            toast(a, "اول یک گفتگو بساز");
                            return;
                        }
                        Api.post("/session/" + sessionId + "/toolsets",
                                Api.body("preset", row[0]), 30000, res -> {
                                    toast(a, "حالت: " + row[1]);
                                    if (after != null) {
                                        after.on();
                                    }
                                }, msg -> toast(a, "نشد: " + msg));
                    }), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
        }
        Sheet.show(a, "حالت ابزار", box);
    }

    // -------------------------------------------------------------------- research --
    /**
     * Ask one question, watch the agent plan, search and read, then get a report that cites a
     * URL for every claim. Polling (not SSE) on purpose: a research run lives 20–70 s and the
     * sheet has to survive the app being backgrounded.
     */
    static void research(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText q = Ui.input(a, "سؤالت را بنویس…");
        q.setSingleLine(false);
        q.setMinLines(3);
        q.setGravity(Gravity.TOP | Gravity.END);
        box.addView(Sheet.section(a, "پژوهش"), Ui.fill());
        box.addView(q, Ui.fill());
        for (int depth = 1; depth <= 3; depth++) {
            final int d = depth;
            String sub = depth == 1 ? "کوتاه — چند جستجو"
                    : depth == 2 ? "معمولی — ۵ جستجو و تا ۳ منبع (پیش‌فرض)"
                    : "عمیق — زیرعامل‌ها هم کمک می‌کنند";
            box.addView(Sheet.item(a, "عمق " + depth, sub, depth == 2 ? Ui.ACCENT : null,
                    () -> startResearch(a, q.getText().toString().trim(), d)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
        }
        Sheet.show(a, "پژوهش", box);
    }

    private static void startResearch(Activity a, String question, int depth) {
        if (question.isEmpty()) {
            toast(a, "سؤال خالی است");
            return;
        }
        AlertDialog spin = busy(a, "شروع…");
        Api.post("/research", Api.body("question", question, "depth", depth), 30000, res -> {
            spin.dismiss();
            String id = res.optString("id", "");
            if (id.isEmpty()) {
                toast(a, "شروع نشد: " + res.optString("error", "?"));
                return;
            }
            pollResearch(a, id, question, 0);
        }, msg -> {
            spin.dismiss();
            toast(a, "نشد: " + msg);
        });
    }

    private static void pollResearch(Activity a, String id, String question, int tick) {
        Api.get("/research/" + id, 20000, d -> {
            String state = d.optString("state", "");
            boolean done = "done".equals(state) || "failed".equals(state)
                    || "cancelled".equals(state);
            if (!done && tick < 120) {
                renderResearch(a, d, question, true);
                a.getWindow().getDecorView().postDelayed(
                        () -> pollResearch(a, id, question, tick + 1), 1500);
                return;
            }
            renderResearch(a, d, question, false);
        }, msg -> toast(a, "وضعیت نیامد: " + msg));
    }

    private static void renderResearch(Activity a, JSONObject d, String question, boolean live) {
        LinearLayout box = Ui.col(a);
        String state = d.optString("state", "");
        String ms = d.optLong("ms", 0) > 0 ? (d.optLong("ms", 0) / 1000) + " ثانیه" : "";
        box.addView(Sheet.section(a, live ? "در حال کار… (" + state + ")"
                : "پایان (" + state + (ms.isEmpty() ? "" : " · " + ms) + ")"), Ui.fill());
        box.addView(Ui.text(a, question, 12.5f, Ui.DIM, false), Ui.fill());

        JSONArray steps = d.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s == null) {
                continue;
            }
            String st = s.optString("state", "");
            int color = "ok".equals(st) ? Ui.GREEN : "cancelled".equals(st) ? Ui.FAINT
                    : "failed".equals(st) ? Ui.RED : Ui.ACCENT;
            long stepMs = s.optLong("ms", 0);
            String detail = s.optString("detail", "");
            box.addView(Sheet.item(a, s.optString("title", s.optString("kind", "")),
                    (stepMs > 0 ? stepMs + "ms" : st)
                            + (detail.isEmpty() ? "" : " · " + detail), color, null), Ui.fill());
        }

        String report = d.optString("report", "");
        if (!report.isEmpty()) {
            android.widget.TextView body = Ui.text(a, "", 14f, Ui.TEXT, false);
            body.setText(Md.render(report, Ui.ACCENT, Ui.alpha(Ui.ACCENT, 22)));
            body.setTextIsSelectable(true);
            body.setPadding(0, Ui.dp(a, 12), 0, Ui.dp(a, 8));
            box.addView(body, Ui.fill());
        }

        JSONArray sources = d.optJSONArray("sources");
        if (sources != null && sources.length() > 0) {
            box.addView(Sheet.section(a, "منابع  " + sources.length()), Ui.fill());
            for (int i = 0; i < sources.length(); i++) {
                JSONObject s = sources.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                final String url = s.optString("url", "");
                box.addView(Sheet.item(a, s.optString("title", url),
                        "[" + s.optInt("n", i + 1) + "] " + url, null,
                        () -> open(a, url)), Ui.fill());
            }
        }
        Sheet.show(a, "پژوهش", box);
    }

    private static void open(Activity a, String url) {
        try {
            a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast(a, "مرورگر باز نشد");
        }
    }

    // ------------------------------------------------------------------------ bots --
    /** Bots are Hermes profiles: separate memory, skills, cron and SOUL per assistant. */
    static void bots(Activity a, String sessionId, String current, Done after) {
        AlertDialog spin = busy(a, "بات‌ها…");
        Api.getArray("/bots", 30000, rows -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            box.addView(Sheet.item(a, "بات تازه", "نام و مدلش را بده", null,
                    () -> newBot(a, after)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject b = rows.optJSONObject(i);
                if (b == null) {
                    continue;
                }
                final String id = b.optString("id", "");
                boolean active = id.equals(current) || (current.isEmpty() && b.optBoolean("default"));
                String model = b.optString("model", "");
                StringBuilder sub = new StringBuilder();
                sub.append(model.isEmpty() ? "مدل پیش‌فرض" : model);
                if (b.optBoolean("isolated", false)) {
                    sub.append(" · حافظهٔ مستقل");
                }
                if (b.optInt("sessions", 0) > 0) {
                    sub.append(" · ").append(b.optInt("sessions", 0)).append(" گفتگو");
                }
                if (active) {
                    sub.append(" · فعال");
                }
                box.addView(Sheet.item(a, b.optString("emoji", "") + "  " + b.optString("label", id),
                        sub.toString(), active ? Ui.ACCENT : null,
                        () -> {
                            if (sessionId == null || sessionId.isEmpty()) {
                                toast(a, "اول یک گفتگو بساز");
                                return;
                            }
                            Api.post("/session/" + sessionId + "/bot", Api.body("id", id), 60000,
                                    res -> {
                                        toast(a, res.optBoolean("ok", false)
                                                ? "بات: " + b.optString("label", id) : "نشد");
                                        if (after != null) {
                                            after.on();
                                        }
                                    }, msg -> toast(a, "نشد: " + msg));
                        }), Ui.fill());
                box.addView(Sheet.divider(a), Ui.fill());
            }
            Sheet.show(a, "بات‌ها", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "نیامد: " + msg);
        });
    }

    private static void newBot(Activity a, Done after) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText label = Ui.input(a, "نام بات (مثلاً برنامه‌نویس)");
        final EditText model = Ui.input(a, "مدل (خالی = پیش‌فرض)");
        model.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(Sheet.section(a, "نام"), Ui.fill());
        box.addView(label, Ui.fill());
        box.addView(Sheet.section(a, "مدل"), Ui.fill());
        box.addView(model, Ui.fill());
        box.addView(Sheet.item(a, "ساختن", "پروفایل جدا از مالک کپی می‌شود", Ui.GREEN, () -> {
            String name = label.getText().toString().trim();
            if (name.isEmpty()) {
                toast(a, "نام لازم است");
                return;
            }
            JSONObject body = Api.body("label", name);
            String m = model.getText().toString().trim();
            if (!m.isEmpty()) {
                try {
                    body.put("model", m);
                } catch (Exception ignored) {
                    // key is a constant string
                }
            }
            AlertDialog spin = busy(a, "ساخت بات… (چند ثانیه)");
            Api.post("/bots", body, 120000, res -> {
                spin.dismiss();
                toast(a, res.optBoolean("ok", false) ? "ساخته شد: " + res.optString("id", "") : "نشد");
                if (after != null) {
                    after.on();
                }
            }, msg -> {
                spin.dismiss();
                toast(a, "نشد: " + msg);
            });
        }), Ui.fill());
        Sheet.show(a, "بات تازه", box);
    }

    // -------------------------------------------------------------------- a11y --
    /**
     * Device control without Shizuku or adb. The service is part of this app, so the whole
     * prerequisite chain a stranger cannot follow (wireless debugging, a pairing code, a second
     * app started by hand) collapses into one system toggle — which this screen explains and
     * deep-links to.
     */
    static void hands(Activity a) {
        boolean on = A11y.ready() && Bridge.VERSION > 0;
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.section(a, on ? "دست‌های جارویس: روشن" : "دست‌های جارویس: خاموش"),
                Ui.fill());
        box.addView(Ui.text(a, on
                        ? "جارویس می‌تواند صفحه را بخواند، لمس کند، تایپ کند و اسکرین‌شات بگیرد — "
                        + "بدون کابل و بدون برنامهٔ اضافه."
                        : "برای کار با گوشی، این سرویس را یک‌بار روشن کن. جایگزینش Shizuku یا "
                        + "کابل adb است که هر دو نصب و جفت‌سازی دستی می‌خواهند.",
                12.5f, Ui.DIM, false), Ui.fill());
        box.addView(Sheet.item(a, on ? "تنظیمات دسترسی را باز کن" : "روشن‌کردن سرویس",
                "تنظیمات → دسترسی‌پذیری → جارویس", on ? Ui.GREEN : Ui.AMBER,
                () -> {
                    try {
                        a.startActivity(new android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Exception e) {
                        toast(a, "تنظیمات باز نشد");
                    }
                }), Ui.fill());
        box.addView(Sheet.divider(a), Ui.fill());
        box.addView(Sheet.item(a, "امتحان کن", "یک‌بار صفحه را می‌خواند", null, () -> {
            Api.post("/fast", Api.body("intent", "info", "args", new JSONObject()), 15000,
                    res -> toast(a, res.optBoolean("ok", false)
                            ? "کار می‌کند: " + res.optString("source", "")
                            : "نشد: " + res.optString("error", "?")),
                    msg -> toast(a, "نشد: " + msg));
        }), Ui.fill());
        Sheet.show(a, "کنترل گوشی", box);
    }

    // --------------------------------------------------------------------- helpers --
    /** Our own loading panel: a dark rounded card with one line, not the system spinner box. */
    private static AlertDialog busy(Activity a, String text) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 20), Ui.dp(a, 16), Ui.dp(a, 20), Ui.dp(a, 16));
        box.setBackground(Ui.round(a, Ui.SURFACE_2, Ui.R_CARD));
        box.setLayoutParams(new ViewGroup.LayoutParams(Ui.dp(a, 200),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(Ui.text(a, text, 13f, Ui.DIM, false));
        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setView(box).setCancelable(false).create();
        d.show();
        return d;
    }

    private static void toast(Activity a, String msg) {
        if (a instanceof MainActivity) {
            ((MainActivity) a).toast(msg);
        }
    }

    // -------------------------------------------------------------------- project --
    /**
     * Project / code mode: the folder the agent works in, its files, running a command in it,
     * and git. Every destructive step (run, commit) goes through {@link Sheet#confirm} — the
     * daemon refuses those without `approve:true`, so the confirm sheet is not decoration.
     */
    static void project(Activity a) {
        AlertDialog spin = busy(a, "پوشهٔ کار…");
        Api.get("/project", 20000, res -> {
            spin.dismiss();
            LinearLayout box = Ui.col(a);
            String dir = res.optString("dir", "");
            box.addView(Sheet.section(a, "پوشهٔ کار"), Ui.fill());
            box.addView(Sheet.item(a, shortDir(dir),
                    res.optInt("entries", 0) + " فایل" + gitLine(res.optJSONObject("git")),
                    null, () -> openDir(a)), Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            box.addView(Sheet.item(a, "فایل‌ها", "دیدن و کپی کردن محتوا", null,
                    () -> tree(a, ".")), Ui.fill());
            box.addView(Sheet.item(a, "اجرای دستور", "با تأیید، داخل همین پوشه", Ui.AMBER,
                    () -> runDialog(a)), Ui.fill());
            box.addView(Sheet.item(a, "گیت", "وضعیت، تاریخچه، دیف، کامیت", null,
                    () -> gitMenu(a)), Ui.fill());
            box.addView(Sheet.item(a, "تغییر پوشه", "مسیری زیر خانهٔ ترمکس", null,
                    () -> openDir(a)), Ui.fill());
            Sheet.show(a, "پروژه", box);
        }, msg -> {
            spin.dismiss();
            toast(a, "پروژه نیامد: " + msg);
        });
    }

    private static String shortDir(String dir) {
        if (dir == null || dir.isEmpty()) {
            return "انتخاب نشده";
        }
        int cut = dir.lastIndexOf('/');
        return cut > 0 ? dir.substring(cut + 1) : dir;
    }

    private static String gitLine(JSONObject git) {
        if (git == null || !git.optBoolean("repo", false)) {
            return " · گیت ندارد";
        }
        String branch = git.optString("branch", "");
        int dirty = git.optInt("dirty", 0);
        return " · " + (branch.isEmpty() ? "master" : branch)
                + (dirty > 0 ? " · " + dirty + " تغییر" : " · پاک");
    }

    private static void openDir(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText path = Ui.input(a, "~/projects/app");
        box.addView(Sheet.section(a, "مسیر پوشه"), Ui.fill());
        box.addView(path, Ui.fill());
        box.addView(Sheet.item(a, "باز کن", null, Ui.ACCENT, () -> {
            String dir = path.getText().toString().trim();
            if (dir.isEmpty()) {
                toast(a, "مسیر لازم است");
                return;
            }
            Api.post("/project/open", Api.body("dir", dir), 20000, res -> {
                if (!res.optBoolean("ok", false)) {
                    toast(a, "باز نشد: " + res.optString("error", "?"));
                    return;
                }
                toast(a, "پوشه عوض شد");
                project(a);
            }, msg -> toast(a, "باز نشد: " + msg));
        }), Ui.fill());
        Sheet.show(a, "تغییر پوشه", box);
    }

    private static void tree(Activity a, String path) {
        Api.get("/project/tree?path=" + android.net.Uri.encode(path), 20000, res -> {
            LinearLayout box = Ui.col(a);
            JSONArray items = res.optJSONArray("items");
            box.addView(Sheet.section(a, ".".equals(path) ? "ریشهٔ پروژه" : path), Ui.fill());
            if (!".".equals(path)) {
                String up = path.lastIndexOf('/') > 0
                        ? path.substring(0, path.lastIndexOf('/')) : ".";
                box.addView(Sheet.item(a, "بازگشت به بالا", up, null, () -> tree(a, up)), Ui.fill());
            }
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                final String rel = it.optString("path", "");
                final boolean isDir = it.optBoolean("dir", false);
                box.addView(Sheet.item(a, it.optString("name", rel),
                        isDir ? "پوشه" : bytes(it.optLong("size", 0)), null,
                        isDir ? () -> tree(a, rel) : () -> file(a, rel)), Ui.fill());
            }
            if (items == null || items.length() == 0) {
                box.addView(Sheet.item(a, "خالی است", null, null, null), Ui.fill());
            }
            Sheet.show(a, "فایل‌ها", box);
        }, msg -> toast(a, "فایل‌ها نیامد: " + msg));
    }

    private static String bytes(long n) {
        if (n < 1024) {
            return n + " بایت";
        }
        return String.format(java.util.Locale.US, "%.1f کیلوبایت", n / 1024f);
    }

    private static void file(Activity a, String path) {
        Api.get("/project/file?path=" + android.net.Uri.encode(path), 20000, res -> {
            if (!res.optBoolean("ok", false)) {
                toast(a, "فایل نیامد: " + res.optString("error", "?"));
                return;
            }
            LinearLayout box = Ui.col(a);
            box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
            if (res.optBoolean("binary", false)) {
                box.addView(Sheet.item(a, "فایل دودویی است", path, null, null), Ui.fill());
                Sheet.show(a, path, box);
                return;
            }
            final String text = res.optString("text", "");
            TextView code = Ui.mono(a, text, 12f, Ui.TEXT);
            code.setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 10));
            code.setTextIsSelectable(true);
            box.addView(code, Ui.fill());
            box.addView(Sheet.divider(a), Ui.fill());
            box.addView(Sheet.item(a, "کپی در کلیپ‌بورد", bytes(text.length()), null, () -> {
                // A foreground activity may set the clipboard directly; only reading needs Clip.
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("jarvis", text));
                    toast(a, "کپی شد");
                }
            }), Ui.fill());
            Sheet.show(a, path, box);
        }, msg -> toast(a, "فایل نیامد: " + msg));
    }

    private static void runDialog(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText cmd = Ui.input(a, "python3 main.py");
        box.addView(Sheet.section(a, "دستور"), Ui.fill());
        box.addView(cmd, Ui.fill());
        box.addView(Sheet.item(a, "ادامه", "بعد از این یک تأیید می‌آید", Ui.AMBER, () -> {
            String line = cmd.getText().toString().trim();
            if (line.isEmpty()) {
                toast(a, "دستور خالی است");
                return;
            }
            Sheet.confirm(a, "اجرا در پوشهٔ پروژه", line, "اجرا کن", Ui.AMBER,
                    () -> run(a, line));
        }), Ui.fill());
        Sheet.show(a, "اجرای دستور", box);
    }

    private static void run(Activity a, String cmd) {
        AlertDialog spin = busy(a, "در حال اجرا…");
        Api.post("/project/run", Api.body("cmd", cmd, "approve", true), 120000, res -> {
            spin.dismiss();
            StringBuilder out = new StringBuilder();
            if (!res.optString("stdout", "").isEmpty()) {
                out.append(res.optString("stdout"));
            }
            if (!res.optString("stderr", "").isEmpty()) {
                out.append(out.length() > 0 ? "\n" : "").append(res.optString("stderr"));
            }
            String head = (res.optBoolean("ok", false) ? "کد " : "خطا، کد ")
                    + res.optInt("rc", 0) + " · " + res.optInt("ms", 0) + "ms"
                    + (res.optBoolean("timed_out", false) ? " · زمان تمام شد" : "");
            LinearLayout box = Ui.col(a);
            box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
            box.addView(Ui.text(a, head, 12f,
                    res.optBoolean("ok", false) ? Ui.GREEN : Ui.RED, false), Ui.fill());
            TextView text = Ui.mono(a, out.length() == 0 ? "(بی‌خروجی)" : out.toString(), 12f, Ui.TEXT);
            text.setPadding(0, Ui.dp(a, 8), 0, 0);
            text.setTextIsSelectable(true);
            box.addView(text, Ui.fill());
            Sheet.show(a, cmd, box);
        }, msg -> {
            spin.dismiss();
            toast(a, "اجرا نشد: " + msg);
        });
    }

    private static void gitMenu(Activity a) {
        LinearLayout box = Ui.col(a);
        box.addView(Sheet.item(a, "وضعیت", "git status", null,
                () -> git(a, "status", "")), Ui.fill());
        box.addView(Sheet.item(a, "تاریخچه", "۲۰ کامیت آخر", null,
                () -> git(a, "log", "")), Ui.fill());
        box.addView(Sheet.item(a, "تغییرات", "git diff", null,
                () -> git(a, "diff", "")), Ui.fill());
        box.addView(Sheet.item(a, "کامیت", "پیام می‌پرسد و تأیید لازم دارد", Ui.AMBER,
                () -> commitDialog(a)), Ui.fill());
        Sheet.show(a, "گیت", box);
    }

    private static void commitDialog(Activity a) {
        LinearLayout box = Ui.col(a);
        box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
        final EditText msg = Ui.input(a, "پیام کامیت");
        box.addView(Sheet.section(a, "پیام"), Ui.fill());
        box.addView(msg, Ui.fill());
        box.addView(Sheet.item(a, "ثبت کامیت", null, Ui.AMBER, () -> {
            String line = msg.getText().toString().trim();
            if (line.isEmpty()) {
                toast(a, "پیام لازم است");
                return;
            }
            Sheet.confirm(a, "کامیت", line, "کامیت کن", Ui.AMBER, () -> git(a, "commit", line));
        }), Ui.fill());
        Sheet.show(a, "کامیت", box);
    }

    private static void git(Activity a, String action, String message) {
        AlertDialog spin = busy(a, "گیت…");
        JSONObject payload = Api.body("action", action, "approve", true);
        if (!message.isEmpty()) {
            try {
                payload.put("message", message);
            } catch (org.json.JSONException ignored) {
                // put only throws on a null key; the message is plain text
            }
        }
        Api.post("/project/git", payload, 60000, res -> {
            spin.dismiss();
            String out = res.optString("stdout", "");
            if (!res.optString("stderr", "").trim().isEmpty()) {
                out += (out.isEmpty() ? "" : "\n") + res.optString("stderr", "");
            }
            LinearLayout box = Ui.col(a);
            box.setPadding(Ui.dp(a, 18), Ui.dp(a, 4), Ui.dp(a, 18), Ui.dp(a, 12));
            box.addView(Ui.text(a, "کد " + res.optInt("rc", 0), 12f,
                    res.optInt("rc", 0) == 0 ? Ui.GREEN : Ui.RED, false), Ui.fill());
            TextView text = Ui.mono(a, out.trim().isEmpty() ? "(بی‌خروجی)" : out, 12f, Ui.TEXT);
            text.setPadding(0, Ui.dp(a, 8), 0, 0);
            text.setTextIsSelectable(true);
            box.addView(text, Ui.fill());
            Sheet.show(a, "گیت " + action, box);
        }, msg -> {
            spin.dismiss();
            toast(a, "گیت نشد: " + msg);
        });
    }

    /** Wrap a machine string (cron expression, URL, id) so bidi cannot reorder it. */
    static String ltr(String value) {
        return value == null || value.isEmpty() ? "" : "\u2066" + value + "\u2069";
    }

    /** `2026-10-26T14:23:00+03:30` -> `14:23`; anything unparsable is passed through. */
    static String clock(String iso) {
        if (iso == null || iso.length() < 16 || iso.indexOf('T') != 10) {
            return iso == null ? "" : iso;
        }
        return iso.substring(11, 16);
    }

    /** Epoch seconds -> `14:23` in the phone's own zone. */
    static String clock(long epoch) {
        if (epoch <= 0) {
            return "";
        }
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(new java.util.Date(epoch * 1000L));
    }

    private static String human(long sec) {
        if (sec < 90) {
            return sec + "s";
        }
        if (sec < 5400) {
            return (sec / 60) + "m";
        }
        return (sec / 3600) + "h";
    }
}
