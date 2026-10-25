package ir.emad.hermesctl;

import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Health checks, editable Hermes settings, scheduled jobs, raw config, versions. */
final class SettingsScreen extends Screen {

    private final JSONObject config = new JSONObject();
    private final JSONObject checks = new JSONObject();
    private final JSONObject cron = new JSONObject();
    private final JSONObject version = new JSONObject();
    /** Health checks hit the network (~10-20 s), so only run them on open and on demand. */
    private boolean runChecks = true;
    /** True once the first /config render happened; later redraws never reset the tab. */
    private boolean loadedOnce;
    /** True while the first render still lacks the health checks. */
    private boolean progress;

    SettingsScreen(MainActivity a) {
        super(a);
    }

    @Override
    void fetch() {
        Api.get("/config", 30000, c -> {
            MainActivity.copyInto(config, c);
            Runnable rest = () -> Api.get("/cron", 20000, cr -> {
                MainActivity.copyInto(cron, cr);
                Api.get("/version", 45000, v -> {
                    MainActivity.copyInto(version, v);
                    done(this::render);
                }, m -> done(this::render));
            }, m -> done(this::render));
            // /checks probes the VPN, DNS, Telegram and every provider (~10-30 s on this
            // phone). Draw the settings we already have instead of a blank tab, then
            // draw again with the health rows once they arrive.
            if (!loadedOnce) {
                loadedOnce = true;
                progress = runChecks;
                done(this::render);
            }
            if (!runChecks) {
                rest.run();
                return;
            }
            runChecks = false;
            Api.get("/checks", 120000, ch -> {
                MainActivity.copyInto(checks, ch);
                progress = false;
                rest.run();
            }, m -> {
                progress = false;
                rest.run();
            });
        }, m -> failed("تنظیمات", m));
    }

    private void render() {
        if (a.pendingRestart) {
            add(a.restartBanner());
        }

        // health ---------------------------------------------------------------------
        section("سلامت");
        LinearLayout hc = Ui.card(a);
        JSONArray list = J.arr(checks, "checks");
        boolean checking = list.length() == 0 && progress;
        for (int i = 0; i < list.length(); i++) {
            JSONObject c = J.at(list, i);
            boolean ok = J.b(c, "ok");
            boolean skipped = J.b(c, "skipped");
            int tone = skipped ? Ui.FAINT : ok ? Ui.GREEN : Ui.RED;
            if (i > 0) {
                Ui.divider(a, hc);
            }
            int ms = J.i(c, "ms", 0);
            hc.addView(Ui.listRow(a, new Icon(a, skipped ? Icon.CLOCK : ok ? Icon.CHECK : Icon.ALERT, tone),
                    J.str(c, "label"), J.str(c, "detail"),
                    ms > 0 ? Ui.text(a, ms + " ms", 11f, Ui.FAINT, false) : null, null));
        }
        if (list.length() == 0) {
            hc.addView(Ui.text(a, checking ? "در حال بررسی…" : "نتیجه‌ای نیامد", 12.5f, Ui.DIM, false));
        }
        hc.addView(Ui.tonal(a, "بررسی دوباره", Ui.ACCENT, v -> {
            runChecks = true;
            refresh();
        }), Ui.margins(Ui.fill(), a, 0, 12, 0, 0));
        add(hc);

        // editable fields ------------------------------------------------------------
        JSONArray fields = J.arr(config, "fields");
        String group = null;
        LinearLayout card = null;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = J.at(fields, i);
            String g = J.str(f, "group", "سایر");
            if (!g.equals(group)) {
                group = g;
                section(g);
                card = Ui.card(a);
                add(card);
            } else {
                Ui.divider(a, card);
            }
            card.addView(fieldRow(f));
        }

        // cron -----------------------------------------------------------------------
        section("کارهای زمان‌بندی‌شده");
        LinearLayout cc = Ui.card(a);
        JSONArray jobs = J.arr(cron, "jobs");
        if (jobs.length() == 0) {
            cc.addView(Ui.text(a, "هیچ کاری زمان‌بندی نشده. از خود ربات بخواه، مثلاً: «هر روز ۹ صبح قیمت طلا رو بفرست».",
                    12.5f, Ui.DIM, false));
        }
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = J.at(jobs, i);
            String id = J.str(j, "id");
            if (i > 0) {
                Ui.divider(a, cc);
            }
            LinearLayout trailing = Ui.row(a);
            trailing.addView(Ui.iconButton(a, Icon.PLAY, Ui.GREEN, v -> cronAction(id, "run")));
            boolean paused = "paused".equals(J.str(j, "state"));
            trailing.addView(Ui.iconButton(a, paused ? Icon.REFRESH : Icon.STOP, Ui.AMBER,
                    v -> cronAction(id, paused ? "resume" : "pause")),
                    Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 6, 0, 0, 0));
            cc.addView(Ui.listRow(a, new Icon(a, Icon.CLOCK, paused ? Ui.FAINT : Ui.ACCENT),
                    J.str(j, "name", id), J.str(j, "schedule") + " · بعدی: " + J.str(j, "next_run", "—")
                            + (J.str(j, "error").isEmpty() ? "" : "\n" + J.str(j, "error")), trailing, null));
        }
        add(cc);

        // about ----------------------------------------------------------------------
        section("درباره");
        LinearLayout about = Ui.card(a);
        about.addView(Ui.listRow(a, null, "Hermes", J.str(version, "hermes", "—"), null, null));
        Ui.divider(a, about);
        about.addView(Ui.listRow(a, null, "gwctl", "v" + J.str(version, "gwctl", "—") + " · API "
                + J.i(version, "api", 0), null, null));
        Ui.divider(a, about);
        about.addView(Ui.listRow(a, null, "کانفیگ کامل", "رمزها ماسک شده — برای دیدن لمس کن", null,
                v -> a.result("config.yaml", true, J.str(config, "raw", ""))));
        add(about);
    }

    private LinearLayout fieldRow(JSONObject f) {
        String key = J.str(f, "key");
        String type = J.str(f, "type", "str");
        LinearLayout r = Ui.row(a);
        r.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
        LinearLayout names = Ui.col(a);
        names.addView(Ui.text(a, J.str(f, "label", key), 13.5f, Ui.TEXT, false));
        TextView k = Ui.ltr(a, key, 10.5f, Ui.FAINT, false);
        k.setPadding(0, Ui.dp(a, 3), 0, 0);
        names.addView(k);
        r.addView(names, Ui.weight(1f));

        switch (type) {
            case "bool": {
                Switch sw = new Switch(a);
                sw.setChecked(J.b(f, "value"));
                sw.setOnCheckedChangeListener((b, on) -> set(key, on));
                r.addView(sw);
                break;
            }
            case "int":
            case "float": {
                boolean isInt = "int".equals(type);
                double value = J.d(f, "value", 0);
                double step = J.d(f, "step", 1);
                r.addView(Ui.iconButton(a, Icon.PLUS, Ui.ACCENT, v -> set(key, bump(f, value, step))));
                TextView val = Ui.ltr(a, isInt ? String.valueOf((long) value)
                        : String.format(java.util.Locale.US, "%.1f", value), 14f, Ui.TEXT, true);
                val.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
                r.addView(val);
                r.addView(Ui.iconButton(a, Icon.DOWN, Ui.ACCENT, v -> set(key, bump(f, value, -step))));
                break;
            }
            case "enum": {
                JSONArray opts = J.arr(f, "options");
                String[] items = new String[opts.length()];
                for (int i = 0; i < items.length; i++) {
                    items[i] = opts.optString(i);
                }
                r.addView(Ui.pill(a, shortValue(J.str(f, "value", "—")), Ui.ACCENT));
                r.setBackground(Ui.ripple(a, null, Ui.ACCENT));
                r.setOnClickListener(v -> a.choose(J.str(f, "label", key), items, w -> set(key, items[w])));
                break;
            }
            default: {
                r.addView(Ui.pill(a, shortValue(J.str(f, "value", "—")), Ui.TEAL));
                r.setBackground(Ui.ripple(a, null, Ui.ACCENT));
                r.setOnClickListener(v -> a.prompt(J.str(f, "label", key), new String[]{key},
                        new String[]{J.str(f, "value", "")}, -1, out -> set(key, out[0])));
            }
        }
        return r;
    }

    private static Object bump(JSONObject f, double value, double step) {
        double next = Math.max(J.d(f, "min", -1e12), Math.min(J.d(f, "max", 1e12), value + step));
        return "int".equals(J.str(f, "type")) ? (Object) (long) Math.round(next)
                : (Object) (Math.round(next * 100.0) / 100.0);
    }

    private static String shortValue(String v) {
        return v.isEmpty() ? "—" : v.length() > 20 ? v.substring(0, 18) + "…" : v;
    }

    private void set(String key, Object value) {
        Api.post("/config/set", Api.body("key", key, "value", value), 30000, res -> {
            if (!J.b(res, "ok")) {
                a.toast("نشد: " + J.str(res, "detail", J.str(res, "error", "?")));
            } else if (J.b(res, "restart_required")) {
                a.pendingRestart = true;
            }
            refresh();
        }, m -> {
            a.toast("خطا: " + m);
            refresh();
        });
    }

    private void cronAction(String id, String action) {
        android.app.AlertDialog dlg = a.progress("در حال اجرا…");
        Api.post("/cron/action", Api.body("id", id, "action", action), 120000, res -> {
            dlg.dismiss();
            a.toast(J.b(res, "ok") ? "انجام شد" : "نشد: " + J.str(res, "output"));
            refresh();
        }, m -> {
            dlg.dismiss();
            a.toast("خطا: " + m);
        });
    }
}
