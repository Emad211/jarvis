package ir.emad.hermesctl;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** Provider API keys: one slot per free provider, plus Hermes' rotating credential pools. */
final class KeysScreen extends Screen {

    private final JSONObject keys = new JSONObject();

    KeysScreen(MainActivity a) {
        super(a);
    }

    @Override
    void fetch() {
        Api.get("/keys", 20000, k -> {
            MainActivity.copyInto(keys, k);
            done(this::render);
        }, m -> failed("کلیدها", m));
    }

    private void render() {
        if (a.pendingRestart) {
            add(a.restartBanner());
        }

        section("کلید پرووایدرها");
        LinearLayout slots = Ui.card(a);
        JSONArray arr = J.arr(keys, "slots");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject s = J.at(arr, i);
            String provider = J.str(s, "provider");
            String env = J.str(s, "env");
            String url = J.str(s, "url");
            boolean set = J.b(s, "set");
            if (i > 0) {
                Ui.divider(a, slots);
            }
            LinearLayout trailing = Ui.row(a);
            trailing.addView(Ui.iconButton(a, Icon.LINK, Ui.DIM, v -> a.openUrl(url)));
            trailing.addView(Ui.iconButton(a, set ? Icon.KEY : Icon.PLUS, set ? Ui.GREEN : Ui.ACCENT,
                    v -> editSlot(provider, env, set)),
                    Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 6, 0, 0, 0));
            slots.addView(Ui.listRow(a, null, provider,
                    set ? env + "  ·  " + J.str(s, "key") : env + "  ·  ثبت نشده", trailing, null));
        }
        add(slots);
        TextView note = Ui.text(a, "دکمه‌ی لینک صفحه‌ی ساخت کلید رایگان را باز می‌کند. بعد از ثبت کلید، "
                + "پرووایدر خودکار وارد زنجیره‌ی جایگزین می‌شود (ری‌استارت لازم است).", 11.5f, Ui.FAINT, false);
        note.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
        add(note);

        JSONObject providers = J.obj(keys, "providers");
        for (String provider : J.keys(providers)) {
            JSONArray rows = J.arr(providers, provider);
            if (rows.length() == 0) {
                continue;
            }
            section("استخر " + provider + " · " + rows.length() + " کلید");
            LinearLayout card = Ui.card(a);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject k = J.at(rows, i);
                if (i > 0) {
                    Ui.divider(a, card);
                }
                long cooldown = J.l(k, "cooldown", -1);
                String status = J.str(k, "status", "ok");
                boolean healthy = cooldown <= 0 && "ok".equals(status);
                String sub = J.str(k, "key", "") + "  ·  " + J.i(k, "requests", 0) + " درخواست"
                        + (J.str(k, "error").isEmpty() ? "" : "\n" + J.str(k, "error"));
                LinearLayout trailing = Ui.row(a);
                trailing.addView(Ui.pill(a, cooldown > 0 ? Ui.duration(cooldown)
                        : healthy ? "سالم" : status, healthy ? Ui.GREEN : Ui.AMBER));
                String id = J.str(k, "id");
                String label = J.str(k, "label", "key");
                trailing.addView(Ui.iconButton(a, Icon.BOLT, Ui.ACCENT, v -> test(provider, id, label)),
                        Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 6, 0, 0, 0));
                trailing.addView(Ui.iconButton(a, Icon.TRASH, Ui.RED, v -> a.confirm("حذف کلید",
                        "کلید «" + label + "» حذف شود؟", "حذف", () -> post("/keys/remove",
                                Api.body("provider", provider, "id", id), "حذف شد"))),
                        Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 4, 0, 0, 0));
                card.addView(Ui.listRow(a, null, label, sub, trailing, null));
            }
            LinearLayout acts = Ui.row(a);
            acts.addView(Ui.tonal(a, "تست همه", Ui.GREEN, v -> test(provider, null, provider)), Ui.weight(1f));
            acts.addView(Ui.tonal(a, "ریست خطاها", Ui.AMBER, v -> post("/keys/reset",
                    Api.body("provider", provider), "پاک شد")), Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
            acts.addView(Ui.tonal(a, "+ کلید", Ui.ACCENT, v -> addPoolKey(provider)),
                    Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
            card.addView(acts, Ui.margins(Ui.fill(), a, 0, 12, 0, 0));
            add(card);
        }
    }

    private void editSlot(String provider, String env, boolean set) {
        a.prompt((set ? "تغییر کلید " : "ثبت کلید ") + provider + (set ? " (خالی = حذف)" : ""),
                new String[]{env}, null, 0, v -> {
                    if (!set && v[0].isEmpty()) {
                        return;
                    }
                    Api.post("/env/set", Api.body("name", env, "value", v[0]), 20000, res -> {
                        if (J.b(res, "ok")) {
                            a.pendingRestart = true;
                            a.toast(v[0].isEmpty() ? "حذف شد" : "ذخیره شد");
                        } else {
                            a.toast("نشد: " + J.str(res, "error"));
                        }
                        refresh();
                    }, m -> a.toast("خطا: " + m));
                });
    }

    private void addPoolKey(String provider) {
        a.prompt("کلید جدید برای " + provider, new String[]{"برچسب (مثلاً daily-4)", "sk-..."}, null, 1, v -> {
            if (!v[1].isEmpty()) {
                post("/keys/add", Api.body("provider", provider, "label", v[0], "key", v[1]), "اضافه شد");
            }
        });
    }

    private void post(String path, JSONObject body, String okMsg) {
        Api.post(path, body, 60000, res -> {
            a.toast(J.b(res, "ok") ? okMsg : "نشد: " + J.str(res, "detail", J.str(res, "error")));
            refresh();
        }, m -> a.toast("خطا: " + m));
    }

    private void test(String provider, String id, String label) {
        android.app.AlertDialog dlg = a.progress("تست " + label + "…");
        Api.post("/keys/test", id == null ? Api.body("provider", provider)
                : Api.body("provider", provider, "id", id), 90000, res -> {
            dlg.dismiss();
            JSONArray results = J.arr(res, "results");
            StringBuilder sb = new StringBuilder();
            boolean all = results.length() > 0;
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = J.at(results, i);
                all &= J.b(r, "ok");
                sb.append(J.b(r, "ok") ? "✓ " : "✗ ").append(J.str(r, "label")).append("  ")
                        .append(J.i(r, "ms", 0)).append(" ms");
                if (!J.b(r, "ok")) {
                    sb.append("\n   ").append(J.str(r, "detail"));
                }
                sb.append('\n');
            }
            a.result("تست کلید", all, results.length() == 0 ? "کلیدی برای تست نیست (کلیدهای env از اینجا تست نمی‌شوند)"
                    : sb.toString().trim());
        }, m -> {
            dlg.dismiss();
            a.result("تست کلید", false, m);
        });
    }
}
