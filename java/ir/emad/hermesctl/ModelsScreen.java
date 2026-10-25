package ir.emad.hermesctl;

import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Primary model, the ordered fallback chain (edit / reorder / test), configured models. */
final class ModelsScreen extends Screen {

    private final JSONObject models = new JSONObject();
    private final JSONObject config = new JSONObject();
    private final List<String[]> chain = new ArrayList<>();
    private boolean chainDirty;

    ModelsScreen(MainActivity a) {
        super(a);
    }

    @Override
    void fetch() {
        Api.get("/models", 30000, m -> {
            MainActivity.copyInto(models, m);
            chain.clear();
            JSONArray fb = J.arr(m, "fallback");
            for (int i = 0; i < fb.length(); i++) {
                JSONObject e = J.at(fb, i);
                chain.add(new String[]{J.str(e, "provider"), J.str(e, "model")});
            }
            chainDirty = false;
            Api.get("/config", 30000, c -> {
                MainActivity.copyInto(config, c);
                done(this::render);
            }, x -> done(this::render));
        }, x -> failed("مدل‌ها", x));
    }

    private void render() {
        String current = J.str(models, "current", "—");
        String[] cur = split(current);

        LinearLayout card = Ui.card(a);
        // Flat card: the primary model is already the biggest, boldest text on the screen,
        // so it does not also need an accent wash behind it.
        card.addView(Ui.text(a, "مدل اصلی", 12f, Ui.DIM, false));
        TextView name = Ui.ltr(a, cur[1], 18f, Ui.TEXT, true);
        name.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 2));
        card.addView(name);
        card.addView(Ui.ltr(a, "via " + cur[0], 12f, Ui.ACCENT, false));
        LinearLayout row = Ui.row(a);
        row.addView(Ui.tonal(a, "تست مدل", Ui.GREEN, v -> test(cur[0], cur[1])), Ui.weight(1f));
        row.addView(Ui.tonal(a, "تغییر مدل", Ui.ACCENT, v -> editPrimary(cur)),
                Ui.margins(Ui.weight(1f), a, 8, 0, 0, 0));
        card.addView(row, Ui.margins(Ui.fill(), a, 0, 14, 0, 0));
        add(card);

        if (a.pendingRestart) {
            add(a.restartBanner());
        }

        // fallback chain -------------------------------------------------------------
        section("زنجیره‌ی جایگزین — اگر مدل اصلی خطا داد، به ترتیب امتحان می‌شوند");
        LinearLayout list = Ui.card(a);
        for (int i = 0; i < chain.size(); i++) {
            final int idx = i;
            String[] e = chain.get(i);
            if (i > 0) {
                Ui.divider(a, list);
            }
            LinearLayout r = Ui.row(a);
            r.setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 8));
            TextView num = Ui.text(a, String.valueOf(i + 1), 13f, Ui.ACCENT, true);
            num.setGravity(android.view.Gravity.CENTER);
            num.setBackground(Ui.round(a, Ui.alpha(Ui.ACCENT, 30), 10));
            r.addView(num, new LinearLayout.LayoutParams(Ui.dp(a, 28), Ui.dp(a, 28)));
            LinearLayout texts = Ui.col(a);
            TextView id = Ui.oneLine(Ui.ltr(a, e[1], 13f, Ui.TEXT, true));
            // The row is too narrow for openrouter ids; long-press shows the full one.
            id.setOnLongClickListener(v -> {
                a.toast(e[0] + " / " + e[1]);
                return true;
            });
            texts.addView(id);
            texts.addView(Ui.ltr(a, e[0], 11f, Ui.DIM, false));
            texts.setOnClickListener(v -> test(e[0], e[1]));
            r.addView(texts, Ui.margins(Ui.weight(1f), a, 10, 0, 6, 0));
            // In an RTL row the first child is the rightmost: DOWN (towards higher index)
            // goes on the left, UP on the right, matching where the neighbours are.
            boolean canDown = idx < chain.size() - 1;
            r.addView(canDown ? Ui.iconButton(a, Icon.DOWN, Ui.DIM, v -> move(idx, 1))
                    : Ui.iconButton(a, Icon.DOWN, Ui.alpha(Ui.DIM, 70), null),
                    Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 0, 0, 4, 0));
            boolean canUp = idx > 0;
            r.addView(canUp ? Ui.iconButton(a, Icon.UP, Ui.DIM, v -> move(idx, -1))
                    : Ui.iconButton(a, Icon.UP, Ui.alpha(Ui.DIM, 70), null),
                    Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 0, 0, 4, 0));
            r.addView(Ui.iconButton(a, Icon.TRASH, Ui.RED, v -> {
                chain.remove(idx);
                chainDirty = true;
                done(this::render);
            }), Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)), a, 4, 0, 0, 0));
            list.addView(r);
        }
        if (chain.isEmpty()) {
            list.addView(Ui.text(a, "زنجیره خالی است — اگر مدل اصلی قطع شود ربات جواب نمی‌دهد.", 12.5f,
                    Ui.AMBER, false));
        }
        LinearLayout acts = Ui.row(a);
        acts.addView(Ui.tonal(a, "+ افزودن", Ui.ACCENT, v -> addToChain()), Ui.wrap());
        if (chainDirty) {
            acts.addView(Ui.button(a, "ذخیره‌ی زنجیره", Ui.GREEN, v -> saveChain()),
                    Ui.margins(Ui.weight(1f), a, 0, 0, 8, 0));
        }
        list.addView(acts, Ui.margins(Ui.fill(), a, 0, 12, 0, 0));
        add(list);
        TextView hint = Ui.text(a, "روی هر مدل بزن تا تست شود. پرووایدرهایی که کلید ندارند خودکار رد می‌شوند؛ "
                + "کلیدها را در تب «کلیدها» وارد کن.", 11.5f, Ui.DIM, false);
        hint.setPadding(Ui.dp(a, 6), 0, Ui.dp(a, 6), 0);
        add(hint);

        // reasoning effort -----------------------------------------------------------
        JSONObject effort = field("agent.reasoning_effort");
        if (effort != null) {
            section("سطح استدلال");
            LinearLayout strip = Ui.row(a);
            JSONArray opts = J.arr(effort, "options");
            String val = J.str(effort, "value", "");
            for (int i = 0; i < opts.length(); i++) {
                String opt = opts.optString(i);
                strip.addView(Ui.chip(a, opt, opt.equals(val), v -> setConfig("agent.reasoning_effort", opt)),
                        Ui.margins(Ui.wrap(), a, 0, 0, 6, 0));
            }
            add(Ui.chips(a, strip));
        }

        // configured -----------------------------------------------------------------
        JSONArray configured = J.arr(models, "configured");
        if (configured.length() > 0) {
            section("مدل‌های تعریف‌شده");
            LinearLayout c = Ui.card(a);
            for (int i = 0; i < configured.length(); i++) {
                JSONObject m = J.at(configured, i);
                String p = J.str(m, "provider");
                String id = J.str(m, "model");
                boolean on = (p + "/" + id).equals(current);
                if (i > 0) {
                    Ui.divider(a, c);
                }
                long ctx = J.l(m, "context", 0);
                c.addView(Ui.listRow(a, null, id, p + (ctx > 0 ? " · " + ctx / 1000 + "k" : ""),
                        on ? Ui.pill(a, "اصلی", Ui.GREEN) : null, on ? null : v -> confirmPrimary(p, id)));
            }
            add(c);
        }
    }

    private JSONObject field(String key) {
        JSONArray fields = J.arr(config, "fields");
        for (int i = 0; i < fields.length(); i++) {
            if (key.equals(J.str(J.at(fields, i), "key"))) {
                return J.at(fields, i);
            }
        }
        return null;
    }

    private static String[] split(String providerModel) {
        int slash = providerModel.indexOf('/');
        return slash < 0 ? new String[]{"", providerModel}
                : new String[]{providerModel.substring(0, slash), providerModel.substring(slash + 1)};
    }

    private void move(int i, int dir) {
        int j = i + dir;
        if (j < 0 || j >= chain.size()) {
            return;
        }
        String[] t = chain.get(i);
        chain.set(i, chain.get(j));
        chain.set(j, t);
        chainDirty = true;
        done(this::render);
    }

    private void addToChain() {
        a.prompt("افزودن به زنجیره", new String[]{"provider (مثلاً openrouter)", "model id"}, null, -1, v -> {
            if (v[0].isEmpty() || v[1].isEmpty()) {
                return;
            }
            chain.add(new String[]{v[0], v[1]});
            chainDirty = true;
            done(this::render);
        });
    }

    private void saveChain() {
        JSONArray arr = new JSONArray();
        for (String[] e : chain) {
            arr.put(Api.body("provider", e[0], "model", e[1]));
        }
        Api.post("/fallback/set", Api.body("chain", arr), 30000, res -> {
            a.pendingRestart = true;
            a.toast("زنجیره ذخیره شد");
            refresh();
        }, m -> a.toast("خطا: " + m));
    }

    private void editPrimary(String[] cur) {
        a.prompt("مدل اصلی", new String[]{"provider", "model id"}, cur, -1, v -> {
            if (!v[0].isEmpty() && !v[1].isEmpty()) {
                switchTo(v[0], v[1]);
            }
        });
    }

    private void confirmPrimary(String provider, String model) {
        a.confirm("تغییر مدل اصلی", provider + " / " + model, "تغییر بده", () -> switchTo(provider, model));
    }

    private void switchTo(String provider, String model) {
        android.app.AlertDialog dlg = a.progress("در حال تغییر مدل…");
        Api.post("/model/switch", Api.body("provider", provider, "model", model), 60000, res -> {
            dlg.dismiss();
            if (J.b(res, "ok")) {
                a.pendingRestart = true;
                a.toast("مدل عوض شد — ری‌استارت لازم است");
            } else {
                a.toast("نشد: " + J.str(res, "detail", J.str(res, "error", "?")));
            }
            refresh();
        }, m -> {
            dlg.dismiss();
            a.toast("خطا: " + m);
        });
    }

    private void setConfig(String key, Object value) {
        Api.post("/config/set", Api.body("key", key, "value", value), 30000, res -> {
            if (J.b(res, "restart_required")) {
                a.pendingRestart = true;
            }
            refresh();
        }, m -> a.toast("خطا: " + m));
    }

    private void test(String provider, String model) {
        android.app.AlertDialog dlg = a.progress("تست " + model + "…");
        Api.post("/model/test", Api.body("provider", provider, "model", model,
                "prompt", "در یک جمله‌ی کوتاه فارسی سلام کن."), 120000, res -> {
            dlg.dismiss();
            boolean ok = J.b(res, "ok");
            a.result(model, ok, ok ? J.str(res, "reply", "—") + "\n\n" + J.i(res, "ms", 0) + " ms · "
                    + J.str(res, "mode") : "HTTP " + J.i(res, "status", 0) + "\n" + J.str(res, "error", "?"));
        }, m -> {
            dlg.dismiss();
            a.result(model, false, m);
        });
    }
}
