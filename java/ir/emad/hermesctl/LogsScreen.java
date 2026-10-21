package ir.emad.hermesctl;

import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;

import org.json.JSONArray;
import org.json.JSONObject;

/** Tail of any stack log, with file switcher, search and auto-follow. */
final class LogsScreen extends Screen {

    private static final int[] LINES = {80, 200, 500, 1000};
    private final JSONObject files = new JSONObject();
    private final JSONObject log = new JSONObject();
    private String file = "gw-live.log";
    private String filter = "";
    private int lines = 200;
    private boolean follow = true;

    LogsScreen(MainActivity a) {
        super(a);
    }

    @Override
    boolean live() {
        return follow;
    }

    @Override
    void fetch() {
        Api.get("/logs/files", 15000, f -> {
            MainActivity.copyInto(files, f);
            String path = "/logs?file=" + android.net.Uri.encode(file) + "&lines=" + lines
                    + (filter.isEmpty() ? "" : "&filter=" + android.net.Uri.encode(filter));
            Api.get(path, 20000, l -> {
                MainActivity.copyInto(log, l);
                done(this::render);
            }, m -> failed("لاگ", m));
        }, m -> failed("لاگ", m));
    }

    private void render() {
        LinearLayout strip = Ui.row(a);
        JSONArray arr = J.arr(files, "files");
        for (int i = 0; i < arr.length(); i++) {
            String name = J.str(J.at(arr, i), "name");
            strip.addView(Ui.chip(a, name.replace(".log", ""), name.equals(file), v -> {
                file = name;
                refresh();
            }), Ui.margins(Ui.wrap(), a, 0, 0, 6, 0));
        }
        add(Ui.chips(a, strip));

        LinearLayout search = Ui.row(a);
        EditText input = Ui.input(a, "جستجو در لاگ…");
        input.setText(filter);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((v, id, e) -> {
            filter = input.getText().toString().trim();
            refresh();
            return true;
        });
        search.addView(input, Ui.weight(1f));
        search.addView(Ui.iconButton(a, Icon.SEARCH, Ui.ACCENT, v -> {
            filter = input.getText().toString().trim();
            refresh();
        }), Ui.margins(new LinearLayout.LayoutParams(Ui.dp(a, 46), Ui.dp(a, 46)), a, 8, 0, 0, 0));
        add(search);
        ((LinearLayout.LayoutParams) search.getLayoutParams()).topMargin = Ui.dp(a, 12);

        LinearLayout opts = Ui.row(a);
        opts.setPadding(0, Ui.dp(a, 10), 0, Ui.dp(a, 10));
        for (int n : LINES) {
            opts.addView(Ui.chip(a, String.valueOf(n), n == lines, v -> {
                lines = n;
                refresh();
            }), Ui.margins(Ui.wrap(), a, 0, 0, 6, 0));
        }
        opts.addView(Ui.space(a, 0, 0), Ui.weight(1f));
        opts.addView(Ui.text(a, "دنبال کن", 12f, Ui.DIM, false));
        Switch sw = new Switch(a);
        sw.setChecked(follow);
        sw.setOnCheckedChangeListener((b, on) -> follow = on);
        opts.addView(sw);
        add(opts);

        LinearLayout card = Ui.card(a);
        card.setBackground(Ui.outline(a, 0xFF0E131D, Ui.STROKE, 18));
        StringBuilder sb = new StringBuilder();
        JSONArray rows = J.arr(log, "lines");
        for (int i = 0; i < rows.length(); i++) {
            sb.append(rows.optString(i)).append('\n');
        }
        card.addView(Ui.mono(a, sb.length() == 0 ? "(خالی)" : sb.toString().trim(), 9f, Ui.DIM));
        add(card);
        add(Ui.text(a, file + " · " + J.i(log, "matched", 0) + " خط · " + Ui.size(J.l(log, "size", 0)),
                11f, Ui.FAINT, false));
        if (follow) {
            root.post(() -> root.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }
}
