package ir.emad.hermesctl;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Dashboard: bot health, quick actions, services, team profiles, device resources. */
final class HomeScreen extends Screen {

    private final JSONObject overview = new JSONObject();
    private final JSONObject system = new JSONObject();

    HomeScreen(MainActivity a) {
        super(a);
    }

    @Override
    boolean live() {
        return true;
    }

    @Override
    void fetch() {
        Api.get("/status", 15000, st -> {
            MainActivity.copyInto(a.status, st);
            Api.get("/overview", 20000, ov -> {
                MainActivity.copyInto(overview, ov);
                Api.get("/system", 20000, sys -> {
                    MainActivity.copyInto(system, sys);
                    done(this::render);
                }, m -> done(this::render));
            }, m -> done(this::render));
        }, m -> {
            a.reachable = false;
            failed("وضعیت", m);
        });
    }

    private void render() {
        JSONObject st = a.status;
        int color = a.stateColor();
        String tg = J.str(st, "telegram", "unknown");
        String overall = J.str(st, "overall", "offline");

        // hero ---------------------------------------------------------------------
        LinearLayout hero = Ui.card(a);
        hero.setBackground(Ui.gradient(a, Ui.alpha(color, 46), Ui.SURFACE, 26));
        hero.setPadding(Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 18), Ui.dp(a, 18));
        LinearLayout heroRow = Ui.row(a);
        StatusRing ring = new StatusRing(a, 76);
        ring.set("online".equals(overall) ? ("connected".equals(tg) ? 1f : 0.7f)
                : ("starting".equals(overall) ? 0.4f : 0.06f), color);
        heroRow.addView(ring);
        LinearLayout info = Ui.col(a);
        info.addView(Ui.text(a, "online".equals(overall) ? "ربات فعال است"
                : "starting".equals(overall) ? "در حال بالا آمدن…" : "ربات خاموش است", 19f, Ui.TEXT, true));
        TextView model = Ui.oneLine(Ui.ltr(a, J.str(st, "model", "—"), 12.5f, Ui.ACCENT, true));
        model.setPadding(0, Ui.dp(a, 8), 0, 0);
        info.addView(model);
        LinearLayout chips = Ui.row(a);
        chips.setPadding(0, Ui.dp(a, 10), 0, 0);
        chips.addView(Ui.pill(a, "تلگرام: " + telegramLabel(tg), "connected".equals(tg) ? Ui.GREEN : Ui.AMBER));
        long up = J.l(st, "uptime_seconds", -1);
        if (up >= 0) {
            chips.addView(Ui.pill(a, Ui.duration(up), Ui.DIM), Ui.margins(Ui.wrap(), a, 6, 0, 0, 0));
        }
        info.addView(chips);
        heroRow.addView(info, Ui.margins(Ui.weight(1f), a, 16, 0, 0, 0));
        hero.addView(heroRow);
        String err = J.str(st, "telegram_error", "");
        if (!"connected".equals(tg) && !err.isEmpty()) {
            TextView e = Ui.ltr(a, err, 11f, Ui.AMBER, false);
            e.setPadding(0, Ui.dp(a, 12), 0, 0);
            hero.addView(e);
        }
        add(hero);

        if (a.pendingRestart) {
            add(a.restartBanner());
        }

        // quick actions --------------------------------------------------------------
        LinearLayout actions = Ui.row(a);
        actions.addView(action(Icon.REFRESH, "ری‌استارت", Ui.ACCENT, v -> a.confirm(
                "ری‌استارت ربات", "ربات حدود ۳۰ ثانیه قطع و دوباره وصل می‌شود.", "ری‌استارت",
                a::restartGateway)), Ui.weight(1f));
        actions.addView(action(Icon.BOLT, "تست ایجنت", Ui.TEAL, v -> agentTest()),
                Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
        actions.addView(action(Icon.SEND, "پیام", Ui.GREEN, v -> sendMessage()),
                Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
        actions.addView(action(Icon.POWER, "توقف", Ui.RED, v -> a.confirm("توقف ربات",
                "ربات تلگرام خاموش می‌شود تا دوباره روشنش کنی.", "توقف", this::stop)),
                Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
        add(actions);

        // services -------------------------------------------------------------------
        JSONArray services = J.arr(overview, "services");
        if (services.length() > 0) {
            section("سرویس‌ها");
            LinearLayout card = Ui.card(a);
            for (int i = 0; i < services.length(); i++) {
                JSONObject s = J.at(services, i);
                String name = J.str(s, "name");
                boolean on = J.b(s, "running");
                if (i > 0) {
                    Ui.divider(a, card);
                }
                Icon icon = new Icon(a, Icon.SERVER, on ? Ui.GREEN : Ui.FAINT);
                card.addView(Ui.listRow(a, icon, serviceLabel(name), name + (on ? " · فعال" : " · خاموش"),
                        Ui.iconButton(a, on ? Icon.REFRESH : Icon.PLAY, on ? Ui.ACCENT : Ui.GREEN,
                                v -> serviceAction(name, on ? "restart" : "start")), null));
            }
            add(card);
        }

        // team -----------------------------------------------------------------------
        JSONArray profiles = J.arr(overview, "profiles");
        if (profiles.length() > 1) {
            section("تیم");
            LinearLayout card = Ui.card(a);
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject p = J.at(profiles, i);
                if (i > 0) {
                    Ui.divider(a, card);
                }
                boolean on = "online".equals(J.str(p, "overall"));
                card.addView(Ui.listRow(a, new Icon(a, Icon.USERS, on ? Ui.GREEN : Ui.FAINT),
                        J.str(p, "profile"), J.str(p, "model") + " · " + telegramLabel(J.str(p, "telegram")),
                        Ui.dot(a, on ? Ui.GREEN : Ui.RED, 9), null));
            }
            add(card);
        }

        // resources ------------------------------------------------------------------
        if (system.length() > 0) {
            section("دستگاه");
            LinearLayout card = Ui.card(a);
            JSONObject bat = J.obj(system, "battery");
            int pct = J.i(bat, "percent", -1);
            meter(card, "باتری", pct < 0 ? "—" : pct + "٪  " + batteryStatus(J.str(bat, "status")),
                    pct / 100f, pct <= 15 ? Ui.RED : pct <= 30 ? Ui.AMBER : Ui.GREEN);
            JSONObject mem = J.obj(system, "memory");
            double m = J.d(mem, "percent", 0);
            meter(card, "حافظه", String.format(java.util.Locale.US, "%.0f٪ · %s آزاد", m,
                    Ui.size(J.l(mem, "available_kb", 0) * 1024L)), (float) m / 100f,
                    m >= 90 ? Ui.RED : m >= 75 ? Ui.AMBER : Ui.TEAL);
            JSONObject data = J.obj(J.obj(system, "storage"), "داده");
            double d = J.d(data, "percent", 0);
            meter(card, "فضای ذخیره", String.format(java.util.Locale.US, "%.0f٪ · %s آزاد", d,
                    Ui.size(J.l(data, "free", 0))), (float) d / 100f,
                    d > 90 ? Ui.RED : d > 80 ? Ui.AMBER : Ui.ACCENT);
            add(card);
        }

        // recent log -----------------------------------------------------------------
        JSONArray tail = J.arr(st, "log_tail");
        if (tail.length() > 0) {
            section("آخرین رویدادها");
            LinearLayout card = Ui.card(a);
            card.setBackground(Ui.outline(a, 0xFF0E131D, Ui.STROKE, 18));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tail.length(); i++) {
                String line = tail.optString(i).trim();
                if (!line.isEmpty() && !line.startsWith("│") && !line.startsWith("└") && !line.startsWith("┌")) {
                    sb.append(line).append('\n');
                }
            }
            card.addView(Ui.mono(a, sb.toString().trim(), 9.5f, Ui.DIM));
            add(card);
        }
    }

    private View action(int glyph, String label, int color, View.OnClickListener l) {
        LinearLayout t = Ui.col(a);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 12));
        t.setBackground(Ui.ripple(a, Ui.outline(a, Ui.SURFACE, Ui.STROKE, 20), color));
        Icon i = new Icon(a, glyph, color);
        int p = Ui.dp(a, 9);
        i.setPadding(p, p, p, p);
        i.setBackground(Ui.round(a, Ui.alpha(color, 30), 14));
        t.addView(i);
        TextView tv = Ui.text(a, label, 12f, Ui.TEXT, true);
        tv.setPadding(0, Ui.dp(a, 8), 0, 0);
        t.addView(tv);
        t.setOnClickListener(l);
        t.setLayoutParams(Ui.margins(Ui.weight(1f), a, 0, 4, 0, 0));
        return t;
    }

    private void meter(LinearLayout card, String label, String value, float progress, int color) {
        LinearLayout head = Ui.row(a);
        head.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 7));
        head.addView(Ui.text(a, label, 12.5f, Ui.DIM, false), Ui.weight(1f));
        head.addView(Ui.text(a, value, 12.5f, Ui.TEXT, true));
        card.addView(head);
        MeterBar bar = new MeterBar(a);
        bar.set(progress, color);
        card.addView(bar, Ui.margins(Ui.fill(), a, 0, 0, 0, 6));
    }

    private void serviceAction(String name, String action) {
        android.app.AlertDialog dlg = a.progress(name + "…");
        Api.post("/services/action", Api.body("name", name, "action", action), 60000, res -> {
            dlg.dismiss();
            a.toast(J.b(res, "ok") ? "انجام شد" : J.str(res, "output", "نشد"));
            refresh();
        }, m -> {
            dlg.dismiss();
            a.toast("خطا: " + m);
        });
    }

    private void stop() {
        android.app.AlertDialog dlg = a.progress("در حال توقف…");
        Api.post("/gateway/stop", null, 60000, res -> {
            dlg.dismiss();
            a.pollStatus();
            refresh();
        }, m -> {
            dlg.dismiss();
            a.toast("خطا: " + m);
        });
    }

    private void agentTest() {
        android.app.AlertDialog dlg = a.progress("تست کامل ایجنت (تا ۲ دقیقه)…");
        Api.post("/agent/test", Api.body("prompt", "reply with exactly the word: READY"), 200000, res -> {
            dlg.dismiss();
            boolean ok = J.b(res, "ok");
            a.result("تست ایجنت", ok, (ok ? "" : "کد خروج " + J.i(res, "code", -1) + "\n")
                    + J.str(res, "reply", "") + "\n\nزمان: " + J.i(res, "ms", 0) / 1000 + " ثانیه");
        }, m -> {
            dlg.dismiss();
            a.result("تست ایجنت", false, m);
        });
    }

    private void sendMessage() {
        a.prompt("ارسال پیام به تلگرام", new String[]{"متن پیام", "مقصد (خالی = کانال خانه)"}, null, -1,
                v -> {
                    if (v[0].isEmpty()) {
                        return;
                    }
                    Api.post("/send", Api.body("text", v[0], "target", v[1].isEmpty() ? "telegram" : v[1]),
                            60000, res -> a.toast(J.b(res, "ok") ? "ارسال شد" : "نشد: " + J.str(res, "detail")),
                            m -> a.toast("خطا: " + m));
                });
    }

    static String telegramLabel(String tg) {
        switch (tg) {
            case "connected":
                return "وصل";
            case "connecting":
                return "در حال اتصال";
            case "retrying":
                return "تلاش دوباره";
            case "disconnected":
                return "قطع";
            default:
                return tg.isEmpty() ? "نامشخص" : tg;
        }
    }

    private static String serviceLabel(String name) {
        switch (name) {
            case "gwctl":
                return "سرویس کنترل";
            case "agy":
                return "پل Antigravity";
            case "router":
                return "OmniRoute";
            case "gw":
                return "ربات اصلی";
            default:
                return name.startsWith("gw-") ? "ربات " + name.substring(3) : name;
        }
    }

    private static String batteryStatus(String s) {
        switch (s) {
            case "CHARGING":
                return "در حال شارژ";
            case "FULL":
                return "پر";
            default:
                return "";
        }
    }
}
