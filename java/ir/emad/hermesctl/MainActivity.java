package ir.emad.hermesctl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Hermes Control — full control centre for the phone's Hermes stack. */
public class MainActivity extends Activity {

    private static final String TERMUX_PKG = "com.termux";
    private static final String RUN_COMMAND_PERM = "com.termux.permission.RUN_COMMAND";
    private static final String WAKE_SCRIPT =
            "/data/data/com.termux/files/home/.local/bin/gwctl-start.sh";
    private static final int REQ_PERMS = 4711;
    private static final long AUTO_MS = 15000L;

    private static final int TAB_HOME = 0;
    private static final int TAB_MODEL = 1;
    private static final int TAB_KEYS = 2;
    private static final int TAB_SETTINGS = 3;
    private static final int TAB_LOGS = 4;
    private static final int TAB_SYSTEM = 5;

    private static final String[] TAB_LABELS = {"خانه", "مدل", "کلیدها", "تنظیمات", "لاگ", "سیستم"};
    private static final String[] TAB_ICONS = {"⌂", "◈", "⚿", "⚙", "▤", "✚"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout frame;
    private LinearLayout navBar;
    private TextView headerSub;
    private TextView headerBadge;
    private View headerDot;

    private int tab = TAB_HOME;
    private boolean busy = false;
    private boolean pendingRestart = false;
    private boolean watchdogArmed = false;
    private boolean waitForMutations = false;
    private boolean serverReachable = true;

    private String logFile = "gw-live.log";
    private int logLines = 120;
    private String logFilter = "";
    private boolean logAuto = true;

    private final JSONObject cacheStatus = new JSONObject();
    private final JSONObject cacheSystem = new JSONObject();
    private final JSONObject cacheConfig = new JSONObject();
    private final JSONObject cacheModels = new JSONObject();
    private final JSONObject cacheKeys = new JSONObject();
    private final JSONObject cacheChecks = new JSONObject();
    private final JSONObject cacheCron = new JSONObject();
    private final JSONObject cacheLogs = new JSONObject();
    private final JSONObject cacheLogFiles = new JSONObject();
    private final JSONObject cacheVersion = new JSONObject();

    private final Runnable autoTick = new Runnable() {
        @Override
        public void run() {
            if (tab == TAB_HOME) {
                loadHome(true);
            } else if (tab == TAB_LOGS && logAuto) {
                loadLogs(true);
            } else if (tab == TAB_MODEL || tab == TAB_KEYS) {
                loadStatus(true);
            }
            ui.postDelayed(this, AUTO_MS);
        }
    };

    // ------------------------------------------------------------------ life --
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        buildShell();
        requestPerms();
        // `am start … --ei tab N` / a launcher shortcut can open straight onto a tab.
        int startTab = TAB_HOME;
        try {
            startTab = getIntent().getIntExtra("tab", TAB_HOME);
        } catch (Exception ignored) {
        }
        if (startTab < 0 || startTab > TAB_SYSTEM) {
            startTab = TAB_HOME;
        }
        switchTab(startTab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadStatus(false);
        ui.removeCallbacks(autoTick);
        ui.postDelayed(autoTick, AUTO_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(autoTick);
    }

    private void requestPerms() {
        List<String> want = new ArrayList<String>();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            want.add("android.permission.POST_NOTIFICATIONS");
        }
        if (checkSelfPermission(RUN_COMMAND_PERM) != PackageManager.PERMISSION_GRANTED) {
            want.add(RUN_COMMAND_PERM);
        }
        if (!want.isEmpty()) {
            try {
                requestPermissions(want.toArray(new String[0]), REQ_PERMS);
            } catch (Exception ignored) {
                // Termux absent or permission unknown: the wake button just stays hidden.
            }
        }
        if (!watchdogArmed) {
            Watchdog.schedule(this);
            watchdogArmed = true;
        }
    }

    // ----------------------------------------------------------------- shell --
    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);
        setContentView(root);

        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 18), Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 12));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBox = Ui.col(this);
        header.addView(titleBox, Ui.weighted(this, 1f));

        LinearLayout titleRow = Ui.row(this);
        headerDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 9), Ui.dp(this, 9));
        dotLp.leftMargin = Ui.dp(this, 8);
        headerDot.setBackground(Ui.round(Ui.FAINT, 20, this));
        titleRow.addView(headerDot, dotLp);

        titleRow.addView(Ui.text(this, "Hermes Control", 20f, Ui.TEXT, true));
        titleBox.addView(titleRow);

        headerSub = Ui.text(this, "در حال بررسی…", 11.5f, Ui.DIM, false);
        headerSub.setPadding(0, Ui.dp(this, 4), 0, 0);
        titleBox.addView(headerSub);

        headerBadge = Ui.pill(this, "…", Ui.DIM);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.rightMargin = Ui.dp(this, 10);
        header.addView(headerBadge, badgeLp);

        TextView refresh = Ui.text(this, "⟳", 19f, Ui.ACCENT, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setWidth(Ui.dp(this, 42));
        refresh.setHeight(Ui.dp(this, 42));
        refresh.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Ui.withAlpha(Ui.ACCENT, 70)),
                Ui.roundStroke(Ui.SURFACE_2, Ui.STROKE, 14, this), null));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadStatus(true);
                reloadCurrent(true);
            }
        });
        header.addView(refresh);

        frame = new FrameLayout(this);
        root.addView(frame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navBar = Ui.row(this);
        navBar.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 10));
        navBar.setBackgroundColor(Ui.SURFACE);
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buildNav();
    }

    private void buildNav() {
        navBar.removeAllViews();
        for (int i = 0; i < TAB_LABELS.length; i++) {
            final int index = i;
            boolean active = (i == tab);

            LinearLayout item = Ui.col(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, Ui.dp(this, 5), 0, 0);

            TextView icon = Ui.text(this, TAB_ICONS[i], 14f, active ? Ui.ACCENT : Ui.FAINT, true);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon);

            TextView label = Ui.text(this, TAB_LABELS[i], 9.5f, active ? Ui.ACCENT : Ui.FAINT, active);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Ui.dp(this, 3), 0, 0);
            item.addView(label);

            View bar = new View(this);
            bar.setBackground(active ? Ui.round(Ui.ACCENT, 4, this) : Ui.round(0x00000000, 4, this));
            LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 16), Ui.dp(this, 3));
            barLp.topMargin = Ui.dp(this, 5);
            item.addView(bar, barLp);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab(index);
                }
            });
            navBar.addView(item, Ui.weighted(this, 1f));
        }
    }

    private void switchTab(int index) {
        tab = index;
        buildNav();
        reloadCurrent(false);
    }

    private void reloadCurrent(boolean force) {
        switch (tab) {
            case TAB_HOME:
                loadHome(force);
                break;
            case TAB_MODEL:
                loadModels(force);
                break;
            case TAB_KEYS:
                loadKeys(force);
                break;
            case TAB_SETTINGS:
                loadConfig(force);
                break;
            case TAB_LOGS:
                loadLogs(force);
                break;
            case TAB_SYSTEM:
                loadSystemTab(force);
                break;
            default:
                break;
        }
    }

    // --------------------------------------------------------------- loading --
    private ScrollView newScreen() {
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout content = Ui.col(this);
        content.setPadding(Ui.dp(this, 15), Ui.dp(this, 2), Ui.dp(this, 15), Ui.dp(this, 26));
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setTag("content");
        sv.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return sv;
    }

    private LinearLayout contentOf(View screen) {
        return (LinearLayout) screen.findViewWithTag("content");
    }

    private void showScreen(View screen) {
        frame.removeAllViews();
        frame.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- status --
    private void loadStatus(final boolean silent) {
        Api.get("/status", 12000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                serverReachable = true;
                copyInto(cacheStatus, body);
                paintHeader();
                if (!silent && tab == TAB_HOME) {
                    loadHome(false);
                }
            }

            @Override
            public void fail(String message) {
                serverReachable = false;
                paintHeaderOffline();
            }
        });
    }

    private void paintHeader() {
        String overall = J.str(cacheStatus, "overall", "unknown");
        String tg = J.str(cacheStatus, "telegram", "unknown");
        int color = stateColor(overall, tg);
        headerDot.setBackground(Ui.round(color, 20, this));
        headerBadge.setText(stateLabel(overall, tg));
        headerBadge.setTextColor(color);
        headerBadge.setBackground(pillBg(color));

        String model = J.str(cacheStatus, "model", "");
        String up = Ui.humanDuration(J.l(cacheStatus, "uptime_seconds", -1));
        headerSub.setText(model + "  ·  " + up);
        headerSub.setOnClickListener(null);
    }

    private void paintHeaderOffline() {
        headerDot.setBackground(Ui.round(Ui.RED, 20, this));
        headerBadge.setText("بی‌پاسخ");
        headerBadge.setTextColor(Ui.RED);
        headerBadge.setBackground(pillBg(Ui.RED));
        headerSub.setText("سرویس کنترل جواب نمی‌دهد — برای بیدار کردن لمس کن");
        headerSub.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wakeServer();
            }
        });
    }

    private android.graphics.drawable.GradientDrawable pillBg(int color) {
        android.graphics.drawable.GradientDrawable d =
                Ui.round(Ui.withAlpha(color, 34), 30, this);
        d.setStroke(Ui.dp(this, 1), Ui.withAlpha(color, 90));
        return d;
    }

    private int stateColor(String overall, String tg) {
        if ("online".equals(overall)) {
            return "connected".equals(tg) ? Ui.GREEN : Ui.AMBER;
        }
        if ("starting".equals(overall)) {
            return Ui.AMBER;
        }
        return Ui.RED;
    }

    private String stateLabel(String overall, String tg) {
        if ("online".equals(overall)) {
            return "connected".equals(tg) ? "آنلاین" : "در انتظار تلگرام";
        }
        if ("starting".equals(overall)) {
            return "در حال بالا آمدن";
        }
        return "خاموش";
    }

    // ------------------------------------------------------------------ HOME --
    private void loadHome(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/system", 25000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                copyInto(cacheSystem, body);
                renderHome(box);
            }

            @Override
            public void fail(String message) {
                renderHome(box);
            }
        });
    }

    private void renderHome(LinearLayout box) {
        box.removeAllViews();
        if (!serverReachable) {
            box.addView(offlineCard());
            return;
        }

        String overall = J.str(cacheStatus, "overall", "unknown");
        String tg = J.str(cacheStatus, "telegram", "unknown");
        int color = stateColor(overall, tg);

        LinearLayout hero = Ui.card(this);
        hero.setBackground(Ui.gradient(Ui.withAlpha(color, 34), Ui.SURFACE, 22, this));
        LinearLayout heroRow = Ui.row(this);
        hero.addView(heroRow);

        StatusRing ring = new StatusRing(this);
        float progress = "online".equals(overall) ? ("connected".equals(tg) ? 1f : 0.72f)
                : ("starting".equals(overall) ? 0.45f : 0.08f);
        ring.set("online".equals(overall) ? "●" : ("starting".equals(overall) ? "◐" : "○"),
                J.str(cacheStatus, "overall", ""), progress, color, true);
        heroRow.addView(ring);

        LinearLayout heroInfo = Ui.col(this);
        LinearLayout.LayoutParams infoLp = Ui.weighted(this, 1f);
        infoLp.leftMargin = Ui.dp(this, 12);
        heroRow.addView(heroInfo, infoLp);

        heroInfo.addView(Ui.text(this,
                "online".equals(overall) ? "ربات فعال است"
                        : ("starting".equals(overall) ? "در حال بالا آمدن" : "ربات خاموش است"),
                16.5f, Ui.TEXT, true));

        TextView model = Ui.text(this, J.str(cacheStatus, "model", "—"), 12f, Ui.ACCENT, true);
        model.setPadding(0, Ui.dp(this, 7), 0, 0);
        model.setTextDirection(View.TEXT_DIRECTION_LTR);
        heroInfo.addView(model);

        LinearLayout chips = Ui.row(this);
        chips.setPadding(0, Ui.dp(this, 10), 0, 0);
        chips.addView(Ui.pill(this, "تلگرام: " + telegramLabel(tg),
                "connected".equals(tg) ? Ui.GREEN : Ui.AMBER));
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chLp.rightMargin = Ui.dp(this, 6);
        chips.addView(Ui.pill(this, "v" + J.str(cacheStatus, "version", "?"), Ui.ACCENT_2), chLp);
        heroInfo.addView(chips);

        TextView meta = Ui.text(this,
                "PID " + J.str(cacheStatus, "pid", "—")
                        + " · RAM " + Ui.humanSize(J.l(cacheStatus, "rss_kb", 0) * 1024L)
                        + "\nفعال از " + Ui.humanDuration(J.l(cacheStatus, "uptime_seconds", -1)),
                10.5f, Ui.DIM, false);
        meta.setPadding(0, Ui.dp(this, 10), 0, 0);
        meta.setLineSpacing(Ui.dp(this, 3), 1f);
        heroInfo.addView(meta);
        box.addView(hero);

        // quick actions
        LinearLayout actions = Ui.row(this);
        actions.addView(Ui.button(this, "⟳  ری‌استارت ربات", Ui.GREEN,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirm("ری‌استارت گیتوی",
                                "ربات متوقف و دوباره روشن می‌شود (حدود ۳۰ ثانیه).",
                                "برو", new Runnable() {
                                    @Override
                                    public void run() {
                                        doRestart();
                                    }
                                });
                    }
                }), Ui.weighted(this, 1f));

        LinearLayout.LayoutParams stopLp = Ui.weighted(this, 0.42f);
        stopLp.leftMargin = Ui.dp(this, 8);
        actions.addView(Ui.buttonGhost(this, "توقف", Ui.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("توقف گیتوی", "ربات تلگرام خاموش می‌شود تا خودت روشنش کنی.",
                        "توقف کن", new Runnable() {
                            @Override
                            public void run() {
                                doStop();
                            }
                        });
            }
        }), stopLp);
        box.addView(actions);

        LinearLayout health = Ui.row(this);
        LinearLayout.LayoutParams healthLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        healthLp.topMargin = Ui.dp(this, 9);
        box.addView(health, healthLp);

        health.addView(Ui.buttonGhost(this, "🩺  بررسی سلامت", Ui.ACCENT_2,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        switchTab(TAB_SYSTEM);
                    }
                }), Ui.weighted(this, 1f));

        LinearLayout.LayoutParams abLp = Ui.weighted(this, 1f);
        abLp.leftMargin = Ui.dp(this, 8);
        health.addView(Ui.buttonGhost(this, "⚡  تست ایجنت", Ui.PINK,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        runAgentTest();
                    }
                }), abLp);

        if (pendingRestart) {
            box.addView(restartBanner());
        }

        // resources
        if (cacheSystem.length() > 0) {
            box.addView(Ui.sectionTitle(this, "منابع دستگاه"));
            LinearLayout res = Ui.card(this);

            JSONObject bat = J.obj(cacheSystem, "battery");
            int batPct = J.i(bat, "percent", -1);
            addMeter(res, "🔋 باتری",
                    batPct < 0 ? "—" : batPct + "%  " + J.str(bat, "status", ""),
                    batPct / 100f,
                    batPct <= 15 ? Ui.RED : (batPct <= 30 ? Ui.AMBER : Ui.GREEN));

            JSONObject mem = J.obj(cacheSystem, "memory");
            double memPct = J.d(mem, "percent", 0);
            addMeter(res, "🧠 حافظه",
                    String.format(java.util.Locale.US, "%.0f%% · %s آزاد", memPct,
                            Ui.humanSize(J.l(mem, "available_kb", 0) * 1024L)),
                    (float) (memPct / 100.0), memoryColor(memPct));

            double swapPct = J.d(mem, "swap_percent", 0);
            if (swapPct > 0) {
                addMeter(res, "🔁 سوآپ",
                        String.format(java.util.Locale.US, "%.0f%%", swapPct),
                        (float) (swapPct / 100.0), swapPct > 70 ? Ui.AMBER : Ui.ACCENT_2);
            }

            JSONObject storage = J.obj(cacheSystem, "storage");
            String[] storeKeys = J.keys(storage);
            if (storeKeys.length > 0) {
                JSONObject data = J.obj(storage, storeKeys[0]);
                double pct = J.d(data, "percent", 0);
                addMeter(res, "💾 فضای ذخیره",
                        String.format(java.util.Locale.US, "%.1f%% · %s آزاد", pct,
                                Ui.humanSize(J.l(data, "free", 0))),
                        (float) (pct / 100.0),
                        pct > 90 ? Ui.RED : (pct > 80 ? Ui.AMBER : Ui.ACCENT));
            }

            JSONObject load = J.obj(cacheSystem, "load");
            addInfoRow(res, "⚖️ بار پردازنده",
                    J.str(load, "1m", "0") + " / " + J.str(load, "5m", "0")
                            + " / " + J.str(load, "15m", "0"), Ui.DIM);
            box.addView(res);
        }

        if (J.arr(cacheStatus, "log_tail").length() > 0) {
            box.addView(Ui.sectionTitle(this, "آخرین رویدادها"));
            LinearLayout logCard = Ui.card(this);
            logCard.setBackground(Ui.roundStroke(Ui.BG, Ui.STROKE, 18, this));
            TextView logText = Ui.text(this, joinLines(J.arr(cacheStatus, "log_tail"), 6),
                    9f, Ui.DIM, false);
            logText.setTypeface(Typeface.MONOSPACE);
            logText.setTextDirection(View.TEXT_DIRECTION_LTR);
            logText.setGravity(Gravity.START);
            logText.setLineSpacing(Ui.dp(this, 2), 1f);
            logCard.addView(logText);
            box.addView(logCard);
        }
    }

    private void addMeter(LinearLayout parent, String label, String value, float progress,
                          int color) {
        LinearLayout head = Ui.row(this);
        head.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 5));
        head.addView(Ui.text(this, label, 11.5f, Ui.DIM, false), Ui.weighted(this, 1f));
        head.addView(Ui.text(this, value, 11.5f, color, true));
        parent.addView(head);

        MeterBar bar = new MeterBar(this);
        bar.set(progress, color);
        parent.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 7)));
    }

    private int memoryColor(double pct) {
        if (pct >= 90) {
            return Ui.RED;
        }
        if (pct >= 75) {
            return Ui.AMBER;
        }
        return Ui.GREEN;
    }

    private void addInfoRow(LinearLayout parent, String key, String value, int color) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, Ui.dp(this, 9), 0, 0);
        r.addView(Ui.text(this, key, 11.5f, Ui.DIM, false), Ui.weighted(this, 1f));
        TextView v = Ui.text(this, value, 11.5f, color, true);
        v.setTextDirection(View.TEXT_DIRECTION_LTR);
        r.addView(v);
        parent.addView(r);
    }

    private View restartBanner() {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.roundStroke(Ui.withAlpha(Ui.AMBER, 26),
                Ui.withAlpha(Ui.AMBER, 120), 18, this));
        c.addView(Ui.text(this, "⚠️ تغییرات ذخیره شد — برای اعمال، گیتوی باید ری‌استارت شود.",
                12f, Ui.AMBER, true));
        TextView b = Ui.button(this, "همین حالا ری‌استارت کن", Ui.AMBER,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        doRestart();
                    }
                });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        c.addView(b, lp);
        return c;
    }

    private View offlineCard() {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.roundStroke(Ui.withAlpha(Ui.RED, 26), Ui.withAlpha(Ui.RED, 120),
                18, this));
        c.addView(Ui.text(this, "سرویس کنترل در دسترس نیست", 15f, Ui.RED, true));
        TextView d = Ui.text(this,
                "اپ به 127.0.0.1:8777 وصل نشد. ترموکس خوابیده یا gwctl خاموش است.",
                12f, Ui.DIM, false);
        d.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        c.addView(d);
        c.addView(Ui.button(this, "بیدار کردن سرویس کنترل", Ui.AMBER,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        wakeServer();
                    }
                }));
        return c;
    }

    private String telegramLabel(String tg) {
        if ("connected".equals(tg)) {
            return "وصل";
        }
        if ("connecting".equals(tg)) {
            return "در حال اتصال";
        }
        if ("disconnected".equals(tg)) {
            return "قطع";
        }
        return tg;
    }

    private String joinLines(JSONArray arr, int max) {
        StringBuilder sb = new StringBuilder();
        int start = Math.max(0, arr.length() - max);
        for (int i = start; i < arr.length(); i++) {
            sb.append(arr.optString(i, "")).append('\n');
        }
        return sb.toString().trim();
    }

    // ----------------------------------------------------------------- MODEL --
    private void loadModels(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/models", 45000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                copyInto(cacheModels, body);
                Api.get("/config", 45000, new Api.Cb() {
                    @Override
                    public void ok(JSONObject cfg) {
                        copyInto(cacheConfig, cfg);
                        renderModels(box);
                    }

                    @Override
                    public void fail(String message) {
                        renderModels(box);
                    }
                });
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("خواندن مدل‌ها", message));
            }
        });
    }

    private void renderModels(LinearLayout box) {
        box.removeAllViews();
        String current = J.str(cacheModels, "current", J.str(cacheConfig, "model", "—"));

        LinearLayout cur = Ui.card(this);
        cur.setBackground(Ui.gradient(Ui.withAlpha(Ui.ACCENT, 30), Ui.SURFACE, 22, this));
        cur.addView(Ui.text(this, "مدل فعال", 11f, Ui.DIM, false));
        TextView name = Ui.text(this, current, 16.5f, Ui.TEXT, true);
        name.setPadding(0, Ui.dp(this, 6), 0, 0);
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        cur.addView(name);

        JSONObject modeField = findField(cacheConfig, "model.api_mode");
        String mode = modeField == null ? "" : J.str(modeField, "value", "");
        cur.addView(Ui.text(this, "پروتکل: " + (mode.isEmpty() ? "—" : mode),
                10.5f, Ui.FAINT, false));

        LinearLayout act = Ui.row(this);
        act.setPadding(0, Ui.dp(this, 12), 0, 0);
        act.addView(Ui.buttonGhost(this, "⚡ تست مدل", Ui.GREEN,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        testCurrentModel();
                    }
                }), Ui.weighted(this, 1f));
        LinearLayout.LayoutParams l2 = Ui.weighted(this, 1f);
        l2.leftMargin = Ui.dp(this, 8);
        act.addView(Ui.buttonGhost(this, "✓ تست ایجنت", Ui.PINK,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        runAgentTest();
                    }
                }), l2);
        cur.addView(act);
        box.addView(cur);

        if (pendingRestart) {
            box.addView(restartBanner());
        }

        JSONObject effortField = findField(cacheConfig, "agent.reasoning_effort");
        if (effortField != null) {
            box.addView(Ui.sectionTitle(this, "سطح استدلال"));
            LinearLayout strip = Ui.row(this);
            JSONArray opts = J.arr(effortField, "options");
            final String curVal = J.str(effortField, "value", "");
            for (int i = 0; i < opts.length(); i++) {
                final String opt = opts.optString(i);
                TextView chip = Ui.chip(this, opt, opt.equals(curVal),
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                postConfig("agent.reasoning_effort", opt);
                            }
                        });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.leftMargin = Ui.dp(this, 6);
                strip.addView(chip, lp);
            }
            box.addView(Ui.hscroll(this, strip));
        }

        JSONArray configured = J.arr(cacheModels, "configured");
        if (configured.length() > 0) {
            box.addView(Ui.sectionTitle(this, "مدل‌های کانفیگ‌شده — برای تغییر لمس کن"));
            LinearLayout list = Ui.card(this);
            String lastProvider = "";
            for (int i = 0; i < configured.length(); i++) {
                JSONObject m = J.at(configured, i);
                final String provider = J.str(m, "provider");
                final String model = J.str(m, "model");
                if (!provider.equals(lastProvider)) {
                    if (i > 0) {
                        Ui.divider(this, list);
                    }
                    list.addView(Ui.text(this, provider, 10f, Ui.FAINT, true));
                    lastProvider = provider;
                }

                boolean isCurrent = (provider + "/" + model).equals(current);
                LinearLayout row = Ui.row(this);
                row.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 5));

                View dot = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 7), Ui.dp(this, 7));
                dlp.leftMargin = Ui.dp(this, 8);
                dot.setBackground(Ui.round(isCurrent ? Ui.GREEN : Ui.FAINT, 20, this));
                row.addView(dot, dlp);

                LinearLayout names = Ui.col(this);
                names.addView(Ui.text(this, model, 12.5f, isCurrent ? Ui.TEXT : Ui.DIM, isCurrent));
                String sub = "";
                long ctx = J.l(m, "context", 0);
                if (ctx > 0) {
                    sub = "context " + (ctx / 1000) + "k";
                }
                if (J.b(m, "tools")) {
                    sub += (sub.isEmpty() ? "" : " · ") + "ابزار ✓";
                }
                if (!sub.isEmpty()) {
                    names.addView(Ui.text(this, sub, 9.5f, Ui.FAINT, false));
                }
                row.addView(names, Ui.weighted(this, 1f));

                if (isCurrent) {
                    row.addView(Ui.pill(this, "فعال", Ui.GREEN));
                }
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirmSwitchModel(provider, model);
                    }
                });
                list.addView(row);
            }
            box.addView(list);
        }

        JSONArray live = J.arr(cacheModels, "live");
        String liveError = J.str(cacheModels, "live_error", "");
        if (live.length() > 0 || !liveError.isEmpty()) {
            box.addView(Ui.sectionTitle(this, "مدل‌های زنده‌ی ارائه‌دهنده"));
            LinearLayout lc = Ui.card(this);
            if (!liveError.isEmpty()) {
                lc.addView(Ui.text(this, liveError, 11f, Ui.RED, false));
            }
            for (int i = 0; i < live.length(); i++) {
                lc.addView(Ui.text(this, "• " + live.optString(i), 12f, Ui.DIM, false));
            }
            box.addView(lc);
        }
    }

    private void confirmSwitchModel(final String provider, final String model) {
        confirm("تغییر مدل",
                "مدل فعال به " + provider + " / " + model + " تغییر کند؟\nگیتوی باید ری‌استارت شود.",
                "تغییر بده", new Runnable() {
                    @Override
                    public void run() {
                        JSONObject body = new JSONObject();
                        try {
                            body.put("provider", provider);
                            body.put("model", model);
                        } catch (Exception ignored) {
                        }
                        final AlertDialog dlg = progressDialog("در حال تغییر مدل…");
                        Api.post("/model/switch", body, 30000, new Api.Cb() {
                            @Override
                            public void ok(JSONObject res) {
                                dlg.dismiss();
                                if (J.b(res, "ok")) {
                                    pendingRestart = true;
                                    toast("مدل عوض شد — ری‌استارت لازم است");
                                    loadStatus(true);
                                    loadModels(false);
                                } else {
                                    toast("نشد: " + J.str(res, "error", "?"));
                                }
                            }

                            @Override
                            public void fail(String message) {
                                dlg.dismiss();
                                toast("خطا: " + message);
                            }
                        });
                    }
                });
    }

    private void testCurrentModel() {
        String current = J.str(cacheStatus, "model", J.str(cacheModels, "current", ""));
        String provider = current.contains("/") ? current.split("/")[0] : "agentrouter";
        String model = current.contains("/") ? current.split("/", 2)[1] : current;

        final AlertDialog dlg = progressDialog("در حال تست مدل…");
        JSONObject body = new JSONObject();
        try {
            body.put("provider", provider);
            body.put("model", model);
            body.put("prompt", "reply with exactly: PONG");
        } catch (Exception ignored) {
        }
        Api.post("/model/test", body, 120000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                boolean ok = J.b(res, "ok");
                String detail = ok
                        ? ("پاسخ: " + J.str(res, "reply", "—") + "\n\nزمان: "
                        + J.i(res, "ms", 0) + " ms\nپروتکل: " + J.str(res, "mode", ""))
                        : J.str(res, "error", "?");
                showResult("تست مدل", ok, detail);
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                showResult("تست مدل", false, message);
            }
        });
    }

    private void runAgentTest() {
        final AlertDialog dlg = progressDialog("تست کامل ایجنت — تا ۶۰ ثانیه…");
        JSONObject body = new JSONObject();
        try {
            body.put("prompt", "reply with exactly: READY");
        } catch (Exception ignored) {
        }
        Api.post("/agent/test", body, 200000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                boolean ok = J.b(res, "ok");
                showResult("تست ایجنت", ok, ok
                        ? "پاسخ: " + J.str(res, "reply", "—") + "\n\nزمان کل: "
                        + (J.i(res, "ms", 0) / 1000) + " ثانیه"
                        : "کد خروج " + J.i(res, "code", -1) + "\n" + J.str(res, "reply", ""));
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                showResult("تست ایجنت", false, message);
            }
        });
    }

    // ------------------------------------------------------------------ KEYS --
    private void loadKeys(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/keys", 25000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                copyInto(cacheKeys, body);
                renderKeys(box);
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("خواندن کلیدها", message));
            }
        });
    }

    private void renderKeys(LinearLayout box) {
        box.removeAllViews();
        JSONObject providers = J.obj(cacheKeys, "providers");
        String[] names = J.keys(providers);

        if (names.length == 0) {
            box.addView(Ui.text(this, "هیچ کلیدی ثبت نشده.", 13f, Ui.DIM, false));
            return;
        }

        for (int p = 0; p < names.length; p++) {
            final String provider = names[p];
            JSONArray keys = J.arr(providers, provider);
            if (keys.length() == 0) {
                continue;
            }

            box.addView(Ui.sectionTitle(this, provider + "  ·  " + keys.length() + " کلید"));
            LinearLayout card = Ui.card(this);

            for (int i = 0; i < keys.length(); i++) {
                final JSONObject k = J.at(keys, i);
                if (i > 0) {
                    Ui.divider(this, card);
                }

                long cooldown = J.l(k, "cooldown", -1);
                boolean healthy = cooldown <= 0 && "ok".equals(J.str(k, "status", "ok"));

                LinearLayout head = Ui.row(this);
                View dot = new View(this);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 8), Ui.dp(this, 8));
                dlp.leftMargin = Ui.dp(this, 8);
                dot.setBackground(Ui.round(healthy ? Ui.GREEN : Ui.AMBER, 20, this));
                head.addView(dot, dlp);

                LinearLayout names2 = Ui.col(this);
                names2.addView(Ui.text(this, J.str(k, "label", "key"), 12.5f, Ui.TEXT, true));
                TextView masked = Ui.text(this, J.str(k, "key", "—"), 10f, Ui.FAINT, false);
                masked.setTextDirection(View.TEXT_DIRECTION_LTR);
                names2.addView(masked);
                head.addView(names2, Ui.weighted(this, 1f));

                if (cooldown > 0) {
                    head.addView(Ui.pill(this, "کول‌داون " + Ui.humanDuration(cooldown), Ui.AMBER));
                } else if (healthy) {
                    head.addView(Ui.pill(this, "سالم", Ui.GREEN));
                } else {
                    head.addView(Ui.pill(this, J.str(k, "status", "?"), Ui.RED));
                }
                card.addView(head);

                String err = J.str(k, "error", "");
                if (!err.isEmpty()) {
                    TextView e = Ui.text(this, err, 10f, Ui.RED, false);
                    e.setPadding(0, Ui.dp(this, 5), 0, 0);
                    e.setTextDirection(View.TEXT_DIRECTION_LTR);
                    card.addView(e);
                }

                LinearLayout tools = Ui.row(this);
                tools.setPadding(0, Ui.dp(this, 8), 0, 0);
                tools.addView(Ui.text(this, "درخواست‌ها: " + J.i(k, "requests", 0),
                        10f, Ui.FAINT, false), Ui.weighted(this, 1f));

                LinearLayout.LayoutParams tbLp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 58), ViewGroup.LayoutParams.WRAP_CONTENT);
                tools.addView(Ui.buttonGhost(this, "تست", Ui.ACCENT,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                testOneKey(provider, J.str(k, "id", ""),
                                        J.str(k, "label", ""));
                            }
                        }), tbLp);

                LinearLayout.LayoutParams dbLp = new LinearLayout.LayoutParams(
                        Ui.dp(this, 58), ViewGroup.LayoutParams.WRAP_CONTENT);
                dbLp.leftMargin = Ui.dp(this, 6);
                tools.addView(Ui.buttonGhost(this, "حذف", Ui.RED,
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                confirm("حذف کلید",
                                        "کلید «" + J.str(k, "label", "") + "» حذف شود؟",
                                        "حذف کن", new Runnable() {
                                            @Override
                                            public void run() {
                                                removeKey(provider, J.str(k, "id", ""));
                                            }
                                        });
                            }
                        }), dbLp);
                card.addView(tools);
            }

            LinearLayout actions = Ui.row(this);
            actions.setPadding(0, Ui.dp(this, 12), 0, 0);
            actions.addView(Ui.buttonGhost(this, "⚡ تست همه", Ui.GREEN,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            testAllKeys(provider);
                        }
                    }), Ui.weighted(this, 1f));

            LinearLayout.LayoutParams rLp = Ui.weighted(this, 1f);
            rLp.leftMargin = Ui.dp(this, 6);
            actions.addView(Ui.buttonGhost(this, "↺ ریست", Ui.AMBER,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            resetKeys(provider);
                        }
                    }), rLp);

            LinearLayout.LayoutParams aLp = Ui.weighted(this, 1f);
            aLp.leftMargin = Ui.dp(this, 6);
            actions.addView(Ui.buttonGhost(this, "+ کلید", Ui.ACCENT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            addKeyDialog(provider);
                        }
                    }), aLp);

            card.addView(actions);
            box.addView(card);
        }

        TextView note = Ui.text(this,
                "کلیدها خودکار می‌چرخند: کلیدی که خطا بدهد کول‌داون می‌شود و بعدی فعال می‌شود.",
                10f, Ui.FAINT, false);
        note.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), 0);
        note.setLineSpacing(Ui.dp(this, 3), 1f);
        box.addView(note);
    }

    private void testAllKeys(final String provider) {
        final AlertDialog dlg = progressDialog("در حال تست کلیدها…");
        JSONObject body = new JSONObject();
        try {
            body.put("provider", provider);
        } catch (Exception ignored) {
        }
        Api.post("/keys/test", body, 90000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                JSONArray results = J.arr(res, "results");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = J.at(results, i);
                    boolean ok = J.b(r, "ok");
                    sb.append(ok ? "✓ " : "✗ ")
                            .append(J.str(r, "label", "?"))
                            .append("  ").append(J.i(r, "ms", 0)).append(" ms");
                    if (!ok) {
                        sb.append("  ").append(J.str(r, "detail", ""));
                    }
                    sb.append('\n');
                }
                showResult("نتیجه تست کلیدها", true, sb.toString().trim());
                loadKeys(false);
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                toast("خطا: " + message);
            }
        });
    }

    private void testOneKey(String provider, String id, String label) {
        final AlertDialog dlg = progressDialog("تست " + label + "…");
        JSONObject body = new JSONObject();
        try {
            body.put("provider", provider);
            body.put("id", id);
        } catch (Exception ignored) {
        }
        Api.post("/keys/test", body, 60000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                JSONObject r = J.at(J.arr(res, "results"), 0);
                boolean ok = J.b(r, "ok");
                showResult("تست " + label, ok, ok
                        ? "کلید سالم است · " + J.i(r, "ms", 0) + " ms"
                        : J.str(r, "detail", "خطای نامشخص"));
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                toast("خطا: " + message);
            }
        });
    }

    private void resetKeys(String provider) {
        JSONObject body = new JSONObject();
        try {
            body.put("provider", provider);
        } catch (Exception ignored) {
        }
        Api.post("/keys/reset", body, 30000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                toast("کول‌داون‌ها پاک شد (" + J.i(res, "cleared", 0) + " فیلد)");
                loadKeys(false);
            }

            @Override
            public void fail(String message) {
                toast("خطا: " + message);
            }
        });
    }

    private void removeKey(String provider, String id) {
        JSONObject body = new JSONObject();
        try {
            body.put("provider", provider);
            body.put("id", id);
        } catch (Exception ignored) {
        }
        Api.post("/keys/remove", body, 30000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                toast("حذف شد (" + J.i(res, "count", 0) + " کلید ماند)");
                loadKeys(false);
            }

            @Override
            public void fail(String message) {
                toast("خطا: " + message);
            }
        });
    }

    private void addKeyDialog(final String provider) {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        final EditText label = Ui.input(this, "برچسب (مثلاً daily-4)");
        final EditText key = Ui.input(this, "sk-...");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 10);
        box.addView(key, lp);

        new AlertDialog.Builder(this)
                .setTitle("افزودن کلید به " + provider)
                .setView(box)
                .setPositiveButton("افزودن", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String value = key.getText().toString().trim();
                        if (value.isEmpty()) {
                            toast("کلید خالی است");
                            return;
                        }
                        JSONObject body = new JSONObject();
                        try {
                            body.put("provider", provider);
                            body.put("label", label.getText().toString().trim());
                            body.put("key", value);
                        } catch (Exception ignored) {
                        }
                        Api.post("/keys/add", body, 30000, new Api.Cb() {
                            @Override
                            public void ok(JSONObject res) {
                                toast("کلید اضافه شد");
                                loadKeys(false);
                            }

                            @Override
                            public void fail(String message) {
                                toast("خطا: " + message);
                            }
                        });
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    // -------------------------------------------------------------- SETTINGS --
    private void loadConfig(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/config", 45000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                copyInto(cacheConfig, body);
                renderConfig(box);
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("خواندن تنظیمات", message));
            }
        });
    }

    private void renderConfig(LinearLayout box) {
        box.removeAllViews();
        if (pendingRestart) {
            box.addView(restartBanner());
        }

        JSONArray fields = J.arr(cacheConfig, "fields");
        String group = "";
        LinearLayout card = null;

        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = J.at(fields, i);
            String fGroup = J.str(f, "group", "سایر");
            if (!fGroup.equals(group)) {
                group = fGroup;
                box.addView(Ui.sectionTitle(this, fGroup));
                card = Ui.card(this);
                box.addView(card);
            }
            if (card != null) {
                card.addView(fieldRow(f));
            }
        }

        box.addView(Ui.sectionTitle(this, "کانفیگ کامل (رمزها ماسک شده)"));
        LinearLayout raw = Ui.card(this);
        raw.setBackground(Ui.roundStroke(Ui.BG, Ui.STROKE, 18, this));
        TextView rawText = Ui.text(this, J.str(cacheConfig, "raw", ""), 8.5f, Ui.DIM, false);
        rawText.setTypeface(Typeface.MONOSPACE);
        rawText.setTextDirection(View.TEXT_DIRECTION_LTR);
        rawText.setGravity(Gravity.START);
        rawText.setTextIsSelectable(true);
        raw.addView(rawText);
        box.addView(raw);
    }

    private View fieldRow(final JSONObject f) {
        final String key = J.str(f, "key", "");
        final String type = J.str(f, "type", "str");
        String label = J.str(f, "label", key);

        LinearLayout row = Ui.row(this);
        row.setPadding(0, Ui.dp(this, 9), 0, Ui.dp(this, 9));

        LinearLayout names = Ui.col(this);
        names.addView(Ui.text(this, label, 12f, Ui.TEXT, false));
        names.addView(Ui.text(this, key, 9f, Ui.FAINT, false));
        row.addView(names, Ui.weighted(this, 1f));

        if ("bool".equals(type)) {
            Switch sw = new Switch(this);
            sw.setChecked(J.b(f, "value"));
            sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                    postConfig(key, isChecked);
                }
            });
            row.addView(sw);
        } else if ("int".equals(type) || "float".equals(type)) {
            final JSONObject field = f;
            LinearLayout stepper = Ui.row(this);
            TextView val = Ui.text(this, fmtNum(J.d(f, "value", 0), "int".equals(type)),
                    12f, Ui.ACCENT, true);
            val.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);

            TextView plus = Ui.buttonGhost(this, "+", Ui.ACCENT_2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepValue(field, 1);
                }
            });
            TextView minus = Ui.buttonGhost(this, "−", Ui.ACCENT_2, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stepValue(field, -1);
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 38), ViewGroup.LayoutParams.WRAP_CONTENT);
            stepper.addView(plus, slp);
            stepper.addView(val);
            LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(
                    Ui.dp(this, 38), ViewGroup.LayoutParams.WRAP_CONTENT);
            stepper.addView(minus, slp2);
            row.addView(stepper);
        } else if ("enum".equals(type)) {
            LinearLayout tappable = Ui.row(this);
            tappable.addView(Ui.pill(this, shortValue(J.str(f, "value", "—")), Ui.ACCENT));
            tappable.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chooseEnum(f);
                }
            });
            row.addView(tappable);
        } else {
            LinearLayout tappable = Ui.row(this);
            tappable.addView(Ui.pill(this, shortValue(J.str(f, "value", "—")), Ui.ACCENT_2));
            tappable.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editString(f);
                }
            });
            row.addView(tappable);
        }
        return row;
    }

    private String shortValue(String v) {
        if (v == null || v.isEmpty() || "null".equals(v)) {
            return "—";
        }
        return v.length() > 18 ? v.substring(0, 16) + "…" : v;
    }

    private String fmtNum(double v, boolean isInt) {
        if (isInt) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.US, "%.2f", v);
    }

    private void stepValue(JSONObject f, int direction) {
        String key = J.str(f, "key", "");
        String type = J.str(f, "type", "int");
        double value = J.d(f, "value", 0);
        double step = J.d(f, "step", 1) * direction;
        double next = value + step;
        double min = J.d(f, "min", -1e9);
        double max = J.d(f, "max", 1e9);
        next = Math.max(min, Math.min(max, next));
        if ("int".equals(type)) {
            postConfig(key, (long) next);
        } else {
            postConfig(key, Math.round(next * 100.0) / 100.0);
        }
    }

    private void chooseEnum(final JSONObject f) {
        final String key = J.str(f, "key", "");
        JSONArray options = J.arr(f, "options");
        final String[] items = new String[options.length()];
        for (int i = 0; i < options.length(); i++) {
            items[i] = options.optString(i);
        }
        if (items.length == 0) {
            editString(f);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(J.str(f, "label", key))
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        postConfig(key, items[which]);
                    }
                })
                .show();
    }

    private void editString(final JSONObject f) {
        final String key = J.str(f, "key", "");
        final EditText input = Ui.input(this, J.str(f, "label", key));
        input.setText(J.str(f, "value", ""));
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), 0);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle(J.str(f, "label", key))
                .setView(box)
                .setPositiveButton("ذخیره", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        postConfig(key, input.getText().toString());
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void postConfig(String key, Object value) {
        JSONObject body = new JSONObject();
        try {
            body.put("key", key);
            body.put("value", value);
        } catch (Exception ignored) {
        }
        waitForMutations = true;
        Api.post("/config/set", body, 30000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                waitForMutations = false;
                if (!J.b(res, "ok")) {
                    toast("نشد: " + J.str(res, "error", "?"));
                    loadConfig(true);
                    return;
                }
                if (J.b(res, "restart_required")) {
                    pendingRestart = true;
                }
                toast("ذخیره شد");
                loadConfig(true);
            }

            @Override
            public void fail(String message) {
                waitForMutations = false;
                toast("خطا: " + message);
                loadConfig(true);
            }
        });
    }

    // ------------------------------------------------------------------ LOGS --
    private void loadLogs(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/logs/files", 20000, new Api.Cb() {
            @Override
            public void ok(JSONObject files) {
                copyInto(cacheLogFiles, files);
                JSONArray arr = J.arr(files, "files");
                boolean exists = false;
                for (int i = 0; i < arr.length(); i++) {
                    if (logFile.equals(J.str(J.at(arr, i), "name"))) {
                        exists = true;
                    }
                }
                if (!exists && arr.length() > 0) {
                    logFile = J.str(J.at(arr, 0), "name");
                }
                fetchLogs(box);
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("خواندن فایل‌های لاگ", message));
            }
        });
    }

    private void fetchLogs(final LinearLayout box) {
        String path = "/logs?file=" + android.net.Uri.encode(logFile)
                + "&lines=" + logLines
                + (logFilter.isEmpty() ? "" : "&filter=" + android.net.Uri.encode(logFilter));
        Api.get(path, 30000, new Api.Cb() {
            @Override
            public void ok(JSONObject body) {
                copyInto(cacheLogs, body);
                renderLogs(box);
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("خواندن لاگ", message));
            }
        });
    }

    private void renderLogs(LinearLayout box) {
        box.removeAllViews();

        JSONArray files = J.arr(cacheLogFiles, "files");
        LinearLayout chips = Ui.row(this);
        for (int i = 0; i < files.length(); i++) {
            final String name = J.str(J.at(files, i), "name");
            TextView chip = Ui.chip(this, name.replace(".log", ""), name.equals(logFile),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            logFile = name;
                            loadLogs(false);
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(this, 6);
            chips.addView(chip, lp);
        }
        box.addView(Ui.hscroll(this, chips));

        LinearLayout controls = Ui.row(this);
        controls.setPadding(0, Ui.dp(this, 12), 0, 0);
        final EditText filter = Ui.input(this, "جستجو در لاگ…");
        filter.setText(logFilter);
        controls.addView(filter, Ui.weighted(this, 1f));

        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                Ui.dp(this, 50), ViewGroup.LayoutParams.MATCH_PARENT);
        glp.leftMargin = Ui.dp(this, 8);
        controls.addView(Ui.buttonGhost(this, "🔍", Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                logFilter = filter.getText().toString().trim();
                loadLogs(false);
            }
        }), glp);
        box.addView(controls);

        LinearLayout opts = Ui.row(this);
        opts.setPadding(0, Ui.dp(this, 10), 0, 0);
        final int[] lineOptions = {60, 120, 300, 800};
        for (int i = 0; i < lineOptions.length; i++) {
            final int n = lineOptions[i];
            TextView chip = Ui.chip(this, String.valueOf(n), n == logLines,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            logLines = n;
                            loadLogs(false);
                        }
                    });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Ui.dp(this, 6);
            opts.addView(chip, lp);
        }
        opts.addView(Ui.text(this, "   خودکار", 10.5f, Ui.DIM, false));
        Switch auto = new Switch(this);
        auto.setChecked(logAuto);
        auto.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean checked) {
                logAuto = checked;
            }
        });
        opts.addView(auto);
        box.addView(opts);

        String body = joinLines(J.arr(cacheLogs, "lines"), logLines);
        if (body.isEmpty()) {
            body = "(خالی)";
        }
        LinearLayout logCard = Ui.card(this);
        logCard.setBackground(Ui.roundStroke(Ui.BG, Ui.STROKE, 16, this));
        TextView text = Ui.text(this, body, 8.4f, Ui.DIM, false);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextDirection(View.TEXT_DIRECTION_LTR);
        text.setGravity(Gravity.START);
        text.setTextIsSelectable(true);
        text.setLineSpacing(Ui.dp(this, 1.6f), 1f);
        logCard.addView(text);
        box.addView(logCard);

        box.addView(Ui.text(this, "فایل: " + logFile + " · " + J.i(cacheLogs, "matched", 0)
                        + " خط · " + Ui.humanSize(J.l(cacheLogs, "size", 0)),
                9.5f, Ui.FAINT, false));
    }

    // ---------------------------------------------------------------- SYSTEM --
    private void loadSystemTab(final boolean silent) {
        final ScrollView screen = newScreen();
        if (!silent || frame.getChildCount() == 0) {
            showScreen(screen);
        }
        final LinearLayout box = contentOf(screen);

        Api.get("/checks", 90000, new Api.Cb() {
            @Override
            public void ok(JSONObject checks) {
                copyInto(cacheChecks, checks);
                Api.get("/cron", 25000, new Api.Cb() {
                    @Override
                    public void ok(JSONObject cron) {
                        copyInto(cacheCron, cron);
                        Api.get("/version", 45000, new Api.Cb() {
                            @Override
                            public void ok(JSONObject ver) {
                                copyInto(cacheVersion, ver);
                                renderSystem(box);
                            }

                            @Override
                            public void fail(String message) {
                                renderSystem(box);
                            }
                        });
                    }

                    @Override
                    public void fail(String message) {
                        renderSystem(box);
                    }
                });
            }

            @Override
            public void fail(String message) {
                box.removeAllViews();
                box.addView(errorCard("بررسی سلامت", message));
            }
        });
    }

    private void renderSystem(LinearLayout box) {
        box.removeAllViews();

        box.addView(Ui.sectionTitle(this, "بررسی سلامت"));
        LinearLayout checks = Ui.card(this);
        JSONArray arr = J.arr(cacheChecks, "checks");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject c = J.at(arr, i);
            if (i > 0) {
                Ui.divider(this, checks);
            }
            LinearLayout row = Ui.row(this);
            boolean ok = J.b(c, "ok");
            boolean skipped = J.b(c, "skipped");
            int tone = skipped ? Ui.AMBER : (ok ? Ui.GREEN : Ui.RED);

            TextView icon = Ui.text(this, skipped ? "?" : (ok ? "✓" : "✗"), 14f, tone, true);
            icon.setWidth(Ui.dp(this, 24));
            icon.setGravity(Gravity.CENTER);
            row.addView(icon);

            LinearLayout col = Ui.col(this);
            col.addView(Ui.text(this, J.str(c, "label", "?"), 12f, Ui.TEXT, false));
            String detail = J.str(c, "detail", "");
            if (!detail.isEmpty()) {
                TextView d = Ui.text(this, detail, 9.5f, ok || skipped ? Ui.FAINT : Ui.RED, false);
                d.setTextDirection(View.TEXT_DIRECTION_LTR);
                col.addView(d);
            }
            row.addView(col, Ui.weighted(this, 1f));

            int ms = J.i(c, "ms", 0);
            if (ms > 0) {
                row.addView(Ui.text(this, ms + " ms", 9f, Ui.FAINT, false));
            }
            checks.addView(row);
        }
        if (arr.length() == 0) {
            checks.addView(Ui.text(this, "نتیجه‌ای نیست", 12f, Ui.DIM, false));
        }
        box.addView(checks);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Ui.dp(this, 4);
        box.addView(Ui.button(this, "اجرای دوباره‌ی بررسی‌ها", Ui.ACCENT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadSystemTab(false);
                    }
                }), rlp);

        box.addView(Ui.sectionTitle(this, "کارهای زمان‌بندی‌شده"));
        JSONArray jobs = J.arr(cacheCron, "jobs");
        if (jobs.length() == 0) {
            LinearLayout c = Ui.card(this);
            c.addView(Ui.text(this, "هیچ کاری زمان‌بندی نشده.", 12f, Ui.DIM, false));
            box.addView(c);
        }
        for (int i = 0; i < jobs.length(); i++) {
            final JSONObject job = J.at(jobs, i);
            LinearLayout card = Ui.card(this);

            LinearLayout head = Ui.row(this);
            head.addView(Ui.text(this, J.str(job, "name", "?"), 12f, Ui.TEXT, true),
                    Ui.weighted(this, 1f));
            head.addView(Ui.pill(this, J.str(job, "state", "?"),
                    "scheduled".equals(J.str(job, "state")) ? Ui.GREEN : Ui.AMBER));
            card.addView(head);

            String info = "زمان‌بندی: " + J.str(job, "schedule", "?")
                    + "  ·  حالت: " + J.str(job, "mode", "?")
                    + "\nآخرین اجرا: " + J.str(job, "last_run", "—")
                    + "\nاجرای بعدی: " + J.str(job, "next_run", "—");
            TextView inf = Ui.text(this, info, 10f, Ui.DIM, false);
            inf.setPadding(0, Ui.dp(this, 7), 0, 0);
            inf.setLineSpacing(Ui.dp(this, 3), 1f);
            card.addView(inf);

            String err = J.str(job, "error", "");
            if (!err.isEmpty()) {
                TextView e = Ui.text(this, err, 9.5f, Ui.RED, false);
                e.setPadding(0, Ui.dp(this, 6), 0, 0);
                card.addView(e);
            }

            final String id = J.str(job, "id", "");
            LinearLayout acts = Ui.row(this);
            acts.setPadding(0, Ui.dp(this, 10), 0, 0);
            acts.addView(Ui.buttonGhost(this, "▶ اجرا", Ui.GREEN,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            cronAction(id, "run");
                        }
                    }), Ui.weighted(this, 1f));
            LinearLayout.LayoutParams p1 = Ui.weighted(this, 1f);
            p1.leftMargin = Ui.dp(this, 6);
            acts.addView(Ui.buttonGhost(this, "⏸ توقف", Ui.AMBER,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            cronAction(id, "pause");
                        }
                    }), p1);
            LinearLayout.LayoutParams p2 = Ui.weighted(this, 1f);
            p2.leftMargin = Ui.dp(this, 6);
            acts.addView(Ui.buttonGhost(this, "⏵ ادامه", Ui.ACCENT,
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            cronAction(id, "resume");
                        }
                    }), p2);
            card.addView(acts);
            box.addView(card);
        }

        box.addView(Ui.sectionTitle(this, "اطلاعات"));
        LinearLayout info2 = Ui.card(this);
        addInfoRow(info2, "Hermes", J.str(cacheVersion, "hermes", "—"), Ui.TEXT);
        addInfoRow(info2, "gwctl", "v" + J.str(cacheVersion, "gwctl", "—"), Ui.TEXT);
        addInfoRow(info2, "API", String.valueOf(J.i(cacheStatus, "api", 0)), Ui.TEXT);
        addInfoRow(info2, "وضعیت", stateLabel(J.str(cacheStatus, "overall", "?"),
                J.str(cacheStatus, "telegram", "?")), Ui.TEXT);
        addInfoRow(info2, "مدل", J.str(cacheStatus, "model", "—"), Ui.ACCENT);
        box.addView(info2);
    }

    private void cronAction(String id, String action) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("action", action);
        } catch (Exception ignored) {
        }
        final AlertDialog dlg = progressDialog("در حال اجرای دستور…");
        Api.post("/cron/action", body, 120000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                toast(J.b(res, "ok") ? "انجام شد" : "نشد: " + J.str(res, "output", ""));
                loadSystemTab(false);
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                toast("خطا: " + message);
            }
        });
    }

    // ---------------------------------------------------------------- shared --
    private void doRestart() {
        final AlertDialog dlg = progressDialog("در حال ری‌استارت گیتوی…");
        Api.post("/gateway/restart", new JSONObject(), 120000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                pendingRestart = false;
                toast(J.b(res, "started") ? "گیتوی روشن شد" : "روشن نشد — لاگ را ببین");
                loadStatus(false);
                reloadCurrent(true);
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                toast("خطا: " + message);
            }
        });
    }

    private void doStop() {
        final AlertDialog dlg = progressDialog("در حال توقف…");
        Api.post("/gateway/stop", new JSONObject(), 60000, new Api.Cb() {
            @Override
            public void ok(JSONObject res) {
                dlg.dismiss();
                toast("گیتوی متوقف شد");
                loadStatus(false);
                reloadCurrent(true);
            }

            @Override
            public void fail(String message) {
                dlg.dismiss();
                toast("خطا: " + message);
            }
        });
    }

    private void wakeServer() {
        try {
            Intent i = new Intent();
            i.setClassName(TERMUX_PKG, "com.termux.app.RunCommandService");
            i.setAction("com.termux.RUN_COMMAND");
            i.putExtra("com.termux.RUN_COMMAND_PATH", WAKE_SCRIPT);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            startService(i);
            toast("درخواست ارسال شد — چند ثانیه صبر کن");
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    loadStatus(false);
                    reloadCurrent(true);
                }
            }, 6000);
        } catch (Exception e) {
            toast("ارسال به ترموکس نشد: " + e.getClass().getSimpleName());
        }
    }

    private AlertDialog progressDialog(String message) {
        LinearLayout box = Ui.row(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
        box.addView(Ui.text(this, "⏳", 16f, Ui.ACCENT, true));
        TextView t = Ui.text(this, message, 12.5f, Ui.TEXT, false);
        t.setPadding(Ui.dp(this, 12), 0, 0, 0);
        box.addView(t);
        return new AlertDialog.Builder(this).setView(box).setCancelable(false).show();
    }

    private void showResult(String title, boolean ok, String body) {
        LinearLayout c = Ui.col(this);
        c.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        c.addView(Ui.text(this, ok ? "✓" : "✗", 20f, ok ? Ui.GREEN : Ui.RED, true));
        TextView t = Ui.text(this, body, 12f, Ui.TEXT, false);
        t.setPadding(0, Ui.dp(this, 8), 0, 0);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setLineSpacing(Ui.dp(this, 3), 1f);
        c.addView(t);
        new AlertDialog.Builder(this).setTitle(title).setView(c)
                .setPositiveButton("بستن", null).show();
    }

    private void confirm(String title, String message, String positive, final Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        action.run();
                    }
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private View errorCard(String what, String message) {
        LinearLayout c = Ui.card(this);
        c.setBackground(Ui.roundStroke(Ui.withAlpha(Ui.RED, 24), Ui.withAlpha(Ui.RED, 110),
                18, this));
        c.addView(Ui.text(this, "خطا در " + what, 14f, Ui.RED, true));
        TextView t = Ui.text(this, message, 11f, Ui.DIM, false);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        c.addView(t);
        c.addView(Ui.button(this, "تلاش دوباره", Ui.ACCENT, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadStatus(false);
                reloadCurrent(true);
            }
        }));
        return c;
    }

    private JSONObject findField(JSONObject cfg, String key) {
        JSONArray fields = J.arr(cfg, "fields");
        for (int i = 0; i < fields.length(); i++) {
            JSONObject f = J.at(fields, i);
            if (key.equals(J.str(f, "key"))) {
                return f;
            }
        }
        return null;
    }

    private void copyInto(JSONObject target, JSONObject source) {
        for (String key : J.keys(source)) {
            try {
                target.put(key, source.get(key));
            } catch (Exception ignored) {
            }
        }
    }
}
