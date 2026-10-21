package ir.emad.hermesctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Hermes Control — shell (header, tabs, status polling) plus dialogs shared by screens. */
public class MainActivity extends Activity {

    private static final String RUN_COMMAND_PERM = "com.termux.permission.RUN_COMMAND";
    private static final String WAKE_SCRIPT =
            "/data/data/com.termux/files/home/.local/bin/gwctl-start.sh";
    private static final long TICK_MS = 12000L;

    private static final String[] LABELS = {"خانه", "مدل‌ها", "کلیدها", "لاگ", "تنظیمات"};
    private static final int[] ICONS = {Icon.HOME, Icon.MODEL, Icon.KEY, Icon.LOGS, Icon.SETTINGS};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Screen[] screens = new Screen[LABELS.length];
    private FrameLayout frame;
    private LinearLayout nav;
    private View headerDot;
    private TextView headerTitle;
    private TextView headerSub;
    private int tab;

    /** Latest /status payload; screens read it instead of fetching it again. */
    final JSONObject status = new JSONObject();
    boolean reachable = true;
    boolean pendingRestart;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pollStatus();
            if (screens[tab] != null && screens[tab].live()) {
                screens[tab].refresh();
            }
            ui.postDelayed(this, TICK_MS);
        }
    };

    // ------------------------------------------------------------------ life --
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        Window w = getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.SURFACE);
        buildShell();
        requestPerms();
        Watchdog.schedule(this);
        int start = getIntent().getIntExtra("tab", 0);
        select(start >= 0 && start < LABELS.length ? start : 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    private void requestPerms() {
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        if (checkSelfPermission(RUN_COMMAND_PERM) != PackageManager.PERMISSION_GRANTED) {
            want.add(RUN_COMMAND_PERM);
        }
        if (!want.isEmpty()) {
            requestPermissions(want.toArray(new String[0]), 1);
        }
    }

    // ----------------------------------------------------------------- shell --
    private void buildShell() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 10));
        root.addView(header, Ui.fill());

        LinearLayout titles = Ui.col(this);
        LinearLayout titleRow = Ui.row(this);
        headerDot = Ui.dot(this, Ui.FAINT, 10);
        titleRow.addView(headerDot);
        headerTitle = Ui.text(this, "هرمس", 22f, Ui.TEXT, true);
        titleRow.addView(headerTitle, Ui.margins(Ui.wrap(), this, 10, 0, 0, 0));
        titles.addView(titleRow);
        headerSub = Ui.oneLine(Ui.text(this, "در حال اتصال…", 12f, Ui.DIM, false));
        headerSub.setPadding(0, Ui.dp(this, 6), 0, 0);
        titles.addView(headerSub);
        header.addView(titles, Ui.weight(1f));
        header.addView(Ui.iconButton(this, Icon.REFRESH, Ui.ACCENT, v -> {
            pollStatus();
            current().refresh();
        }));

        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nav = Ui.row(this);
        nav.setBackgroundColor(Ui.SURFACE);
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 10));
        root.addView(nav, Ui.fill());
        for (int i = 0; i < LABELS.length; i++) {
            final int index = i;
            LinearLayout item = Ui.col(this);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            Icon icon = new Icon(this, ICONS[i], Ui.FAINT);
            int p = Ui.dp(this, 4);
            icon.setPadding(Ui.dp(this, 16), p, Ui.dp(this, 16), p);
            item.addView(icon);
            TextView label = Ui.text(this, LABELS[i], 11f, Ui.FAINT, false);
            label.setPadding(0, Ui.dp(this, 4), 0, 0);
            item.addView(label);
            item.setOnClickListener(v -> select(index));
            nav.addView(item, Ui.weight(1f));
        }
    }

    private Screen current() {
        if (screens[tab] == null) {
            switch (tab) {
                case 1:
                    screens[tab] = new ModelsScreen(this);
                    break;
                case 2:
                    screens[tab] = new KeysScreen(this);
                    break;
                case 3:
                    screens[tab] = new LogsScreen(this);
                    break;
                case 4:
                    screens[tab] = new SettingsScreen(this);
                    break;
                default:
                    screens[tab] = new HomeScreen(this);
            }
        }
        return screens[tab];
    }

    void select(int index) {
        tab = index;
        for (int i = 0; i < nav.getChildCount(); i++) {
            LinearLayout item = (LinearLayout) nav.getChildAt(i);
            boolean on = i == index;
            Icon icon = (Icon) item.getChildAt(0);
            icon.set(ICONS[i], on ? Ui.ACCENT : Ui.FAINT);
            icon.setBackground(on ? Ui.round(this, Ui.alpha(Ui.ACCENT, 38), 16) : null);
            TextView label = (TextView) item.getChildAt(1);
            label.setTextColor(on ? Ui.TEXT : Ui.FAINT);
            label.setTypeface(on ? Ui.BOLD : Ui.REG);
        }
        Screen s = current();
        frame.removeAllViews();
        frame.addView(s.root);
        s.refresh();
    }

    // ---------------------------------------------------------------- status --
    void pollStatus() {
        Api.get("/status", 12000, body -> {
            reachable = true;
            copyInto(status, body);
            paintHeader();
        }, msg -> {
            reachable = false;
            paintHeader();
        });
    }

    private void paintHeader() {
        if (!reachable) {
            headerDot.setBackground(Ui.round(this, Ui.RED, 10));
            headerSub.setText("سرویس کنترل جواب نمی‌دهد — لمس کن تا بیدار شود");
            headerSub.setTextColor(Ui.RED);
            headerSub.setOnClickListener(v -> wakeServer());
            return;
        }
        int color = stateColor();
        headerDot.setBackground(Ui.round(this, color, 10));
        headerSub.setTextColor(Ui.DIM);
        headerSub.setOnClickListener(null);
        headerSub.setText(stateLabel() + "  ·  " + J.str(status, "model", "—"));
    }

    int stateColor() {
        String overall = J.str(status, "overall", "");
        if ("online".equals(overall)) {
            return "connected".equals(J.str(status, "telegram", "")) ? Ui.GREEN : Ui.AMBER;
        }
        return "starting".equals(overall) ? Ui.AMBER : Ui.RED;
    }

    String stateLabel() {
        String overall = J.str(status, "overall", "");
        if ("online".equals(overall)) {
            return "connected".equals(J.str(status, "telegram", "")) ? "آنلاین" : "منتظر تلگرام";
        }
        return "starting".equals(overall) ? "در حال بالا آمدن" : "خاموش";
    }

    // --------------------------------------------------------------- actions --
    void restartGateway() {
        AlertDialog dlg = progress("ری‌استارت ربات…");
        Api.post("/gateway/restart", null, 120000, res -> {
            dlg.dismiss();
            pendingRestart = false;
            toast(J.b(res, "started") ? "ربات دوباره روشن شد" : "روشن نشد — لاگ را ببین");
            pollStatus();
            current().refresh();
        }, msg -> {
            dlg.dismiss();
            toast("خطا: " + msg);
        });
    }

    /** Asks Termux to run gwctl-start.sh, which brings every stopped service back. */
    void wakeServer() {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setClassName("com.termux", "com.termux.app.RunCommandService");
            i.putExtra("com.termux.RUN_COMMAND_PATH", WAKE_SCRIPT);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            // Termux is usually in the background exactly when this is needed, and a
            // background app may only start another app's service as a foreground service.
            startForegroundService(i);
            toast("درخواست به ترموکس رفت — چند ثانیه صبر کن");
            ui.postDelayed(() -> {
                pollStatus();
                current().refresh();
            }, 7000);
        } catch (Exception e) {
            toast("ترموکس جواب نداد: " + e.getClass().getSimpleName());
        }
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("مرورگری پیدا نشد");
        }
    }

    // --------------------------------------------------------------- dialogs --
    void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
    }

    AlertDialog progress(String message) {
        LinearLayout box = Ui.row(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 20));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        box.addView(spin, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
        box.addView(Ui.text(this, message, 14f, Ui.TEXT, false), Ui.margins(Ui.wrap(), this, 14, 0, 0, 0));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return dialog().setView(box).setCancelable(false).show();
    }

    void confirm(String title, String message, String yes, Runnable action) {
        dialog().setTitle(title).setMessage(message)
                .setPositiveButton(yes, (d, w) -> action.run())
                .setNegativeButton("لغو", null).show();
    }

    void result(String title, boolean ok, String body) {
        LinearLayout c = Ui.col(this);
        c.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 6));
        LinearLayout head = Ui.row(this);
        head.addView(new Icon(this, ok ? Icon.CHECK : Icon.ALERT, ok ? Ui.GREEN : Ui.RED));
        head.addView(Ui.text(this, ok ? "موفق" : "ناموفق", 15f, ok ? Ui.GREEN : Ui.RED, true),
                Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        c.addView(head);
        TextView t = Ui.text(this, body, 13f, Ui.TEXT, false);
        t.setTextIsSelectable(true);
        t.setPadding(0, Ui.dp(this, 12), 0, 0);
        c.addView(t);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        dialog().setTitle(title).setView(c).setPositiveButton("بستن", null).show();
    }

    interface Submit {
        void on(String[] values);
    }

    /** Dialog with one text field per hint; `secretIndex` (or -1) is masked. */
    void prompt(String title, String[] hints, String[] values, int secretIndex, Submit submit) {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), 0);
        EditText[] fields = new EditText[hints.length];
        for (int i = 0; i < hints.length; i++) {
            fields[i] = Ui.input(this, hints[i]);
            fields[i].setTextDirection(View.TEXT_DIRECTION_LTR);
            if (values != null && i < values.length && values[i] != null) {
                fields[i].setText(values[i]);
            }
            if (i == secretIndex) {
                fields[i].setInputType(InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            box.addView(fields[i], Ui.margins(Ui.fill(), this, 0, i == 0 ? 0 : 10, 0, 0));
        }
        dialog().setTitle(title).setView(box).setNegativeButton("لغو", null)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String[] out = new String[fields.length];
                    for (int i = 0; i < fields.length; i++) {
                        out[i] = fields[i].getText().toString().trim();
                    }
                    submit.on(out);
                }).show();
    }

    interface Pick {
        void on(int which);
    }

    void choose(String title, String[] items, Pick pick) {
        dialog().setTitle(title).setItems(items, (d, w) -> pick.on(w)).show();
    }

    // ---------------------------------------------------------- shared views --
    View errorCard(String what, String message) {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.outline(this, Ui.alpha(Ui.RED, 20), Ui.alpha(Ui.RED, 90), 22));
        LinearLayout head = Ui.row(this);
        head.addView(new Icon(this, Icon.ALERT, Ui.RED));
        head.addView(Ui.text(this, reachable ? "خطا در " + what : "سرویس کنترل خاموش است",
                15f, Ui.RED, true), Ui.margins(Ui.wrap(), this, 10, 0, 0, 0));
        c.addView(head);
        TextView t = Ui.ltr(this, reachable ? message
                : "اپ به 127.0.0.1:8777 وصل نشد. ترموکس خوابیده یا gwctl خاموش است.", 12f, Ui.DIM, false);
        t.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 14));
        c.addView(t);
        c.addView(reachable ? Ui.tonal(this, "تلاش دوباره", Ui.ACCENT, v -> current().refresh())
                : Ui.button(this, "بیدار کردن سرویس‌ها", Ui.AMBER, v -> wakeServer()), Ui.fill());
        return c;
    }

    View restartBanner() {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.outline(this, Ui.alpha(Ui.AMBER, 22), Ui.alpha(Ui.AMBER, 100), 22));
        LinearLayout r = Ui.row(this);
        r.addView(new Icon(this, Icon.ALERT, Ui.AMBER));
        r.addView(Ui.text(this, "تغییرات ذخیره شد؛ برای اعمال، ربات باید ری‌استارت شود.", 13f,
                Ui.AMBER, true), Ui.margins(Ui.weight(1f), this, 10, 0, 0, 0));
        c.addView(r);
        c.addView(Ui.button(this, "ری‌استارت کن", Ui.AMBER, v -> restartGateway()),
                Ui.margins(Ui.fill(), this, 0, 12, 0, 0));
        return c;
    }

    static void copyInto(JSONObject target, JSONObject source) {
        for (String key : J.keys(source)) {
            try {
                target.put(key, source.get(key));
            } catch (Exception ignored) {
                // JSONObject.get cannot fail for a key it just listed
            }
        }
    }
}
