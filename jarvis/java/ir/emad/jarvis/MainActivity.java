package ir.emad.jarvis;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The console: one chat against the on-phone agent, with live tool traffic, approvals and
 * voice. It talks only to 127.0.0.1 — the daemon owns the keys and the agent process.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "jarvis";
    private static final long NOTIFY_MS = 80;

    final List<Msg> messages = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private ListView list;
    private ChatAdapter adapter;
    private LinearLayout chips;
    private View empty;
    private EditText input;
    private TextView stateLine;
    private TextView modelChip;
    private Icon micIcon;
    private Icon sendIcon;
    private View dot;

    private SharedPreferences prefs;
    private Voice voice;
    private String sessionId = "";
    private int lastSeq;
    private Msg streaming;
    private Thread sse;
    private boolean ttsOn;
    private String model = "";
    /** Which persona this session runs on ("" = the daemon's default). */
    private String persona = "";
    /** Tool preset for this conversation: fast | smart | full ("" = daemon default). */
    private String toolsetPreset = "";
    private String effort = "none";
    private String catalogJson = "";
    private long lastNotify;
    private String lastTurnText = "";
    private boolean reconnecting;
    private boolean busy;

    // ------------------------------------------------------------------ lifecycle --
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        Api.base(prefs.getString("base", "http://127.0.0.1:8780"));
        ttsOn = prefs.getBoolean("tts", false);
        model = prefs.getString("model", "");
        effort = prefs.getString("effort", "none");
        voice = new Voice(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.SURFACE);
        buildShell();
        askNotificationPermission();
        // The phone's hands: a loopback listener so jarvisd can act without adb or Shizuku.
        // It answers /ping even when the accessibility service is still off, so the daemon can
        // tell "app not running" apart from "permission not granted".
        Bridge.start(this);
        connect(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sse == null || !sse.isAlive()) {
            connect(false);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // keep the SSE thread: a backgrounded turn keeps streaming into the transcript
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        voice.stopPlayback();
        if (sse != null) {
            sse.interrupt();
        }
    }

    static void ui(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private void askNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        }
    }

    // --------------------------------------------------------------------- shell --
    private void buildShell() {
        LinearLayout root = Ui.col(this);
        root.setBackgroundColor(Ui.BG);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);
        setContentView(root);

        // header: menu, title + live state, new chat
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 6));
        header.addView(Ui.iconButton(this, Icon.MENU, Ui.DIM, v -> menu()));
        LinearLayout titles = Ui.col(this);
        LinearLayout titleRow = Ui.row(this);
        titleRow.addView(Ui.text(this, "جارویس", 19f, Ui.TEXT, true));
        titles.addView(titleRow);
        LinearLayout stateRow = Ui.row(this);
        stateRow.setPadding(0, Ui.dp(this, 3), 0, 0);
        dot = Ui.dot(this, Ui.FAINT, 7);
        stateRow.addView(dot);
        stateLine = Ui.oneLine(Ui.text(this, "در حال اتصال…", 11.5f, Ui.DIM, false));
        stateRow.addView(stateLine, Ui.margins(Ui.weight(1f), this, 6, 0, 0, 0));
        titles.addView(stateRow);
        header.addView(titles, Ui.margins(Ui.weight(1f), this, 12, 0, 12, 0));
        modelChip = Ui.pill(this, "مدل", Ui.ACCENT);
        modelChip.setOnClickListener(v -> pickModel());
        header.addView(modelChip, Ui.margins(Ui.wrap(), this, 0, 0, 8, 0));
        header.addView(Ui.iconButton(this, Icon.PLUS, Ui.DIM, v -> openSession("")));
        root.addView(header, Ui.fill());

        // transcript + empty state stacked in one frame
        FrameLayout stack = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 10));
        list.setClipToPadding(false);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        adapter = new ChatAdapter(this, messages, v -> {
            messageMenu((Msg) v.getTag());
            return true;
        });
        list.setAdapter(adapter);
        stack.addView(list, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        empty = emptyState();
        stack.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(stack, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // quick actions: deterministic device intents, no model, no waiting
        chips = Ui.row(this);
        chips.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 4));
        strip.addView(chips);
        root.addView(strip, Ui.fill());

        // input: send (start edge = thumb side in RTL), field, mic
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 14));
        sendIcon = Ui.iconButton(this, Icon.SEND, Ui.ON_ACCENT, v -> onSendTapped());
        sendIcon.setBackground(Ui.ripple(this, Ui.round(this, Ui.ACCENT, 23), Ui.ON_ACCENT));
        sendIcon.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
        bar.addView(sendIcon);
        micIcon = Ui.iconButton(this, Icon.MIC, Ui.DIM, v -> toggleMic());
        bar.addView(micIcon, Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        input = Ui.input(this, "پیام به جارویس…");
        input.setMaxLines(4);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setRawInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN;
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || enter) {
                hideKeyboard();
                send(false);
                return true;
            }
            return false;
        });
        bar.addView(input, Ui.margins(Ui.weight(1f), this, 8, 0, 0, 0));
        root.addView(bar, Ui.fill());
    }

    /** What a new user sees first: a greeting, three ways to start, nothing else. */
    private View emptyState() {
        LinearLayout box = Ui.col(this);
        box.setGravity(android.view.Gravity.CENTER);
        box.setPadding(Ui.dp(this, 28), 0, Ui.dp(this, 28), Ui.dp(this, 40));

        box.addView(new Icon(this, Icon.SPARK, Ui.ACCENT),
                new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));

        TextView hi = Ui.text(this, "سلام عماد", 26f, Ui.TEXT, true);
        hi.setGravity(android.view.Gravity.CENTER);
        box.addView(hi, Ui.margins(Ui.fill(), this, 0, 16, 0, 0));
        TextView sub = Ui.text(this, "چه کاری برات انجام بدم؟", 14f, Ui.DIM, false);
        sub.setGravity(android.view.Gravity.CENTER);
        box.addView(sub, Ui.margins(Ui.fill(), this, 0, 8, 0, 0));

        String[][] ideas = {
                {"تلگرام رو باز کن", "باز کردن تلگرام"},
                {"اسکرین‌شات بگیر", "تصویر صفحه"},
                {"ساعت چنده؟", "ساعت و تاریخ"},
                {"یک اسکریپت پایتون برای مرتب کردن دانلودها بنویس", "کدنویسی"},
        };
        for (String[] idea : ideas) {
            LinearLayout card = Ui.row(this);
            card.setPadding(Ui.dp(this, 14), Ui.dp(this, 13), Ui.dp(this, 14), Ui.dp(this, 13));
            card.setBackground(Ui.ripple(this, Ui.outline(this, Ui.SURFACE, Ui.STROKE, Ui.R_CARD),
                    Ui.ACCENT));
            card.addView(Ui.text(this, idea[0], 13.5f, Ui.TEXT, false), Ui.weight(1f));
            card.addView(Ui.text(this, idea[1], 10.5f, Ui.FAINT, false));
            card.setOnClickListener(v -> {
                input.setText(idea[0]);
                input.setSelection(input.getText().length());
                send(false);
            });
            box.addView(card, Ui.margins(Ui.fill(), this, 0, 12, 0, 0));
        }
        return box;
    }

    private void updateEmptyState() {
        if (empty != null) {
            empty.setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
            list.setVisibility(messages.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    // ------------------------------------------------------------------ daemon io --
    private void connect(boolean newTurn) {
        setState("در حال اتصال…", Ui.AMBER);
        Api.get("/health", 8000, res -> {
            setState(res.optBoolean("warm") ? "آماده" : "دارد گرم می‌شود…",
                    res.optBoolean("warm") ? Ui.GREEN : Ui.AMBER);
            loadCatalog();
            if (sessionId.isEmpty()) {
                openSession(prefs.getString("session", ""));
            } else {
                attachStream();
            }
            firstRunIfNeeded();
        }, msg -> {
            setState("به سرویس وصل نیست — Termux/جارویس را روشن کن", Ui.RED);
            note("دسترسی به 127.0.0.1:8780 نیست: " + msg);
        });
    }

    private void openSession(String wanted) {
        if (wanted == null || wanted.isEmpty()) {
            Api.post("/session/new", Api.body(), 15000, res -> {
                sessionId = res.optString("session", "");
                prefs.edit().putString("session", sessionId).apply();
                messages.clear();
                notifyList();
                attachStream();
            }, msg -> note("ساخت گفتگو نشد: " + msg));
            return;
        }
        sessionId = wanted;
        Api.get("/session/" + wanted, 15000, res -> {
            messages.clear();
            JSONArray arr = res.optJSONArray("messages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null) {
                        continue;
                    }
                    Msg msg = new Msg(m.optString("role", Msg.NOTE));
                    msg.text.append(m.optString("text", ""));
                    messages.add(msg);
                }
            }
            lastSeq = res.optInt("seq", 0);
            String sessionModel = res.optString("model", "");
            if (!sessionModel.isEmpty()) {
                model = sessionModel;
                modelChip.setText(ChatAdapter.shortModel(model));
            }
            effort = res.optString("reasoning", effort);
            persona = res.optString("persona", "");
            toolsetPreset = res.optString("toolsets", "");
            notifyList();
            attachStream();
        }, msg -> {
            prefs.edit().remove("session").apply();
            sessionId = "";
            note("گفتگوی قبلی باز نشد: " + msg);
            openSession("");
        });
    }

    private void attachStream() {
        if (sessionId.isEmpty()) {
            return;
        }
        if (sse != null) {
            sse.interrupt();
        }
        sse = Api.stream("/session/" + sessionId + "/events?from=" + lastSeq, this::onEvent,
                msg -> onStreamLost(msg));
    }

    /** The daemon restarted or the socket died: back off, then resume from lastSeq. */
    private void onStreamLost(String message) {
        setState("اتصال قطع شد — تلاش دوباره", Ui.AMBER);
        if (reconnecting) {
            return;
        }
        reconnecting = true;
        ui.postDelayed(() -> {
            reconnecting = false;
            Api.get("/health", 8000, res -> {
                setState("آماده", Ui.GREEN);
                attachStream();
            }, msg -> onStreamLost(msg));
        }, 2500);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager km =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (km != null && input != null) {
            km.hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
    }

    private void loadCatalog() {
        Api.get("/catalog", 15000, res -> {
            catalogJson = res.toString();
            String current = res.optString("current", "");
            if (model.isEmpty()) {
                model = current;
            }
            modelChip.setText(ChatAdapter.shortModel(model));
            fillChips(res.optJSONArray("chips"));
        }, msg -> {
            // catalog is only for the pickers; the chat works without it
        });
    }

    /** One-tap actions from the daemon's catalog: these never touch the model. */
    private void fillChips(JSONArray defs) {
        if (chips == null || defs == null) {
            return;
        }
        chips.removeAllViews();
        for (int i = 0; i < defs.length(); i++) {
            JSONObject def = defs.optJSONObject(i);
            if (def == null) {
                continue;
            }
            String label = def.optString("label", def.optString("intent", ""));
            String intent = def.optString("intent", "");
            JSONObject args = def.optJSONObject("args");
            TextView chip = Ui.pill(this, label, Ui.ACCENT);
            chip.setOnClickListener(v -> runFast(intent, args, label));
            chips.addView(chip, Ui.margins(Ui.wrap(), this, 0, 0, 6, 6));
        }
    }

    /** Runs a deterministic device action and shows its verdict in the transcript. */
    private void runFast(String intent, JSONObject args, String label) {
        if (intent.isEmpty()) {
            return;
        }
        final Msg row = new Msg(Msg.ACTION);
        row.actionLabel = label;
        row.actionOk = true;
        row.text.append("…");
        messages.add(row);
        notifyList();
        JSONObject payload = Api.body("intent", intent);
        if (args != null) {
            try {
                payload.put("args", args);
            } catch (Exception ignored) {
                // args are optional
            }
        }
        Api.post("/fast", payload, 30000, res -> {
            row.actionOk = res.optBoolean("ok", false);
            row.text.setLength(0);
            row.text.append(res.optString("detail", ""));
            row.media = res.optString("media", "");
            row.totalMs = res.optInt("ms", 0);
            row.actionSource = res.optString("source", "");
            notifyList();
        }, msg -> {
            row.actionOk = false;
            row.text.setLength(0);
            row.text.append(msg);
            notifyList();
        });
    }

    // -------------------------------------------------------------------- events --
    private void onEvent(String kind, JSONObject data) {
        lastSeq = Math.max(lastSeq, data.optInt("_seq", 0));
        switch (kind) {
            case "turn.start": {
                String text = data.optString("text", "");
                streaming = new Msg(Msg.ASSISTANT);
                streaming.streaming = true;
                streaming.model = model;
                messages.add(streaming);
                notifyList();
                busy = true;
                sendIcon.set(Icon.STOP, Ui.ON_ACCENT);
                setState("فکر می‌کند…", Ui.ACCENT);
                break;
            }
            case "text.delta": {
                if (streaming == null) {
                    streaming = new Msg(Msg.ASSISTANT);
                    streaming.streaming = true;
                    messages.add(streaming);
                }
                streaming.text.append(data.optString("text", ""));
                notifyList();
                setState("می‌نویسد…", Ui.ACCENT);
                break;
            }
            case "tool.start": {
                if (streaming == null) {
                    streaming = new Msg(Msg.ASSISTANT);
                    streaming.streaming = true;
                    messages.add(streaming);
                }
                String name = data.optString("name", "tool");
                streaming.tools.add(new Msg.ToolEv(data.optString("id", ""), name,
                        data.optString("args", "")));
                notifyList();
                setState("ابزار: " + name, Ui.ACCENT);
                break;
            }
            case "tool.end": {
                if (streaming != null) {
                    String id = data.optString("id", "");
                    String name = data.optString("name", "");
                    String result = data.optString("result", "");
                    Msg.ToolEv target = null;
                    for (int i = streaming.tools.size() - 1; i >= 0; i--) {
                        Msg.ToolEv t = streaming.tools.get(i);
                        if (!t.done && (t.id.equals(id) || t.name.equals(name))) {
                            target = t;
                            break;
                        }
                    }
                    if (target == null && !streaming.tools.isEmpty()) {
                        target = streaming.tools.get(streaming.tools.size() - 1);
                    }
                    if (target != null) {
                        target.result = result;
                        target.done = true;
                        target.failed = result.startsWith("Error") || result.startsWith("error")
                                || result.contains("\"ok\": false")
                                || result.startsWith("Traceback");
                    }
                }
                notifyList();
                break;
            }
            case "status": {
                String what = data.optString("kind", "");
                String text = data.optString("text", "");
                if ("ttft".equals(what)) {
                    try {
                        if (streaming != null) {
                            streaming.ttftMs = Integer.parseInt(text.trim());
                        }
                    } catch (Exception ignored) {
                        // not a number: ignore, the footer simply omits it
                    }
                } else if (!text.isEmpty() && streaming != null) {
                    setState(brief(text), Ui.DIM);
                }
                break;
            }
            case "ask":
                showAsk(data);
                break;
            case "team.result": {
                // A background sub-agent finished. It can land minutes after the turn that
                // launched it, so it arrives as its own transcript entry, not as a delta.
                String goal = data.optString("goal", "");
                String summary = data.optString("summary", "");
                if (summary.isEmpty()) {
                    break;
                }
                if (streaming != null) {
                    streaming.streaming = false;
                    streaming = null;
                }
                Msg t = new Msg(Msg.ASSISTANT);
                t.text.append(goal.isEmpty() ? summary : "**" + goal + "**\n\n" + summary);
                t.model = data.optString("model", "");
                t.totalMs = data.optInt("ms", 0);
                messages.add(t);
                busy = false;
                sendIcon.set(Icon.SEND, Ui.ON_ACCENT);
                setState("آماده", Ui.GREEN);
                notifyList();
                break;
            }
            case "error":
                note("خطا: " + data.optString("text", "?"));
                break;
            case "turn.end": {
                int ms = data.optInt("ms", 0);
                if (streaming != null) {
                    streaming.streaming = false;
                    streaming.totalMs = ms;
                    if (streaming.text.length() == 0) {
                        String reply = data.optString("reply", "");
                        if (!reply.isEmpty()) {
                            streaming.text.append(reply);
                        }
                    }
                    final String spoken = streaming.text.toString();
                    if (ttsOn && spoken.length() > 0) {
                        voice.speak(spoken);
                    }
                }
                notifyList();
                streaming = null;
                busy = false;
                sendIcon.set(Icon.SEND, Ui.ON_ACCENT);
                setState("آماده", Ui.GREEN);
                break;
            }
            default:
                break;
        }
    }

    private static String brief(String text) {
        String t = text.replace("\n", " ");
        return t.length() > 48 ? t.substring(0, 47) + "…" : t;
    }

    private void showAsk(JSONObject data) {
        String id = data.optString("id", "");
        String kind = data.optString("kind", "");
        if (id.isEmpty()) {
            return;
        }
        if ("approval".equals(kind)) {
            String command = data.optString("command", "");
            String description = data.optString("description", "");
            LinearLayout box = Ui.col(this);
            box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
            if (!description.isEmpty()) {
                box.addView(Ui.text(this, description, 13f, Ui.TEXT, false));
            }
            TextView code = Ui.mono(this, command, 12f, Ui.AMBER);
            code.setPadding(0, Ui.dp(this, 10), 0, 0);
            code.setTextIsSelectable(true);
            box.addView(code);
            box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("اجازهٔ اجرا؟")
                    .setView(box)
                    .setPositiveButton("یک‌بار", (d, w) -> answer(id, "once"))
                    .setNeutralButton("همیشه", (d, w) -> answer(id, "always"))
                    .setNegativeButton("نه", (d, w) -> answer(id, "deny"))
                    .setCancelable(false)
                    .show();
            setState("منتظر اجازهٔ توست", Ui.AMBER);
            return;
        }
        // clarify: the agent is asking a question mid-turn
        String question = data.optString("question", "");
        final EditText answer = Ui.input(this, "پاسخ");
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.text(this, question, 13.5f, Ui.TEXT, false));
        box.addView(answer, Ui.margins(Ui.fill(), this, 0, 10, 0, 0));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("سؤال جارویس")
                .setView(box)
                .setPositiveButton("بفرست", (d, w) -> answer(id, answer.getText().toString()))
                .setNegativeButton("رد کن", (d, w) -> answer(id, ""))
                .setCancelable(false)
                .show();
    }

    private void answer(String id, String value) {
        Api.post("/ask/" + id, Api.body("value", value), 15000,
                res -> setState("ادامه می‌دهد…", Ui.ACCENT),
                msg -> toast("پاسخ نرفت: " + msg));
    }

    // ------------------------------------------------------------------- actions --
    /** The send button doubles as a stop button while a turn is running. */
    private void onSendTapped() {
        if (busy) {
            Api.post("/session/" + sessionId + "/cancel", Api.body(), 15000,
                    res -> setState("متوقف شد", Ui.DIM), msg -> { });
            return;
        }
        send(false);
    }

    private void send(boolean fromVoice) {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || sessionId.isEmpty()) {
            return;
        }
        input.setText("");
        hideKeyboard();
        lastTurnText = text;
        Msg mine = new Msg(Msg.USER).text(text);
        messages.add(mine);
        notifyList();
        setState("می‌فرستم…", Ui.ACCENT);
        Api.post("/session/" + sessionId + "/turn", Api.body("text", text), 20000, res -> {
            if (!res.optBoolean("ok", false)) {
                note("ارسال نشد: " + res.optString("error", "?"));
            }
        }, msg -> note("ارسال نشد: " + msg));
    }

    private void toggleMic() {
        if (voice.isRecording()) {
            micIcon.set(Icon.MIC, Ui.ACCENT);
            setState("می‌نویسد…", Ui.ACCENT);
            voice.stop(text -> {
                input.setText(text);
                input.setSelection(text.length());
                send(true);
            }, msg -> {
                setState("صدا: " + msg, Ui.RED);
                toast(msg);
            });
            return;
        }
        if (checkSelfPermission("android.permission.RECORD_AUDIO")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.RECORD_AUDIO"}, 1);
            return;
        }
        if (voice.start()) {
            micIcon.set(Icon.STOP, Ui.RED);
            setState("می‌شنود… (دوباره بزن تا بفرستد)", Ui.RED);
        } else {
            toast("میکروفون باز نشد");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(requestCode, perms, granted);
        if (requestCode == 1 && granted.length > 0
                && granted[0] == PackageManager.PERMISSION_GRANTED) {
            toggleMic();
        }
    }

    private void menu() {
        java.util.List<String> items = java.util.Arrays.asList(
                "گفتگوی تازه",
                "گفتگوهای قبلی",
                "شخصیت‌ها",
                "چه یاد گرفت",
                "پژوهش",
                "بات‌ها",
                "ابزارها و مهارت‌ها",
                "پروژه و کد",
                "حالت ابزار این گفتگو",
                "زمان‌بندی",
                "سرویس‌ها و لاگ‌ها",
                "پرووایدرها و کلیدها",
                "تنظیمات",
                "راه‌اندازی اولیه",
                "صدا و صدای جارویس",
                ttsOn ? "صدا روی پاسخ‌ها: روشن" : "صدا روی پاسخ‌ها: خاموش",
                "کنترل گوشی",
                "کپی آخرین پاسخ");
        Sheet.rows(this, "منو", items, which -> {
                    switch (which) {
                        case 0:
                            openSession("");
                            break;
                        case 1:
                            openChats();
                            break;
                        case 2:
                            Panels.personas(this, sessionId, persona, () -> {
                                persona = "";
                                openSession(sessionId);
                            });
                            break;
                        case 3:
                            Panels.learned(this);
                            break;
                        case 4:
                            Panels.research(this);
                            break;
                        case 5:
                            Panels.bots(this, sessionId, "", () -> openSession(sessionId));
                            break;
                        case 6:
                            Panels.tools(this);
                            break;
                        case 7:
                            Panels.project(this);
                            break;
                        case 8:
                            Panels.toolsets(this, sessionId, toolsetPreset, () -> { });
                            break;
                        case 9:
                            Panels.cron(this);
                            break;
                        case 10:
                            Panels.services(this);
                            break;
                        case 11:
                            providersDialog();
                            break;
                        case 12:
                            settings();
                            break;
                        case 13:
                            setupWizard();
                            break;
                        case 14:
                            Panels.voice(this);
                            break;
                        case 15:
                            ttsOn = !ttsOn;
                            prefs.edit().putBoolean("tts", ttsOn).apply();
                            toast(ttsOn ? "صدا روشن شد" : "صدا خاموش شد");
                            break;
                        case 16:
                            Panels.hands(this);
                            break;
                        default:
                            copyLast();
                            break;
                    }
                });
    }

    // -------------------------------------------------------------------- first run --
    /** Shows the wizard automatically on a phone that has no provider key yet. */
    private void firstRunIfNeeded() {
        Api.get("/setup/state", 12000, res -> {
            if (res.optBoolean("needs_setup", false)) {
                note("برای شروع، یک کلید پرووایدر لازم است.");
                setupWizard();
            }
        }, msg -> {
            // an older daemon has no setup endpoints; the console still works if keys exist
        });
    }

    // ------------------------------------------------------------------- providers --
    /**
     * Any provider, any shape. Each row carries a measured dot: green when the daemon's last
     * real request to it succeeded, red when it did not (a provider can be perfectly healthy
     * and still be unreachable from this phone's current network), grey when not tried yet.
     */
    private void providersDialog() {
        Api.get("/providers", 15000, res -> {
            JSONArray rows = res.optJSONArray("providers");
            if (rows == null) {
                toast("لیست پرووایدرها نیامد");
                return;
            }
            JSONObject cur = res.optJSONObject("current");
            String curLine = cur == null ? "—"
                    : cur.optString("provider", "—") + " · " + cur.optString("model", "—");

            LinearLayout box = Ui.col(this);
            box.addView(Sheet.section(this, "مدل فعلی  " + curLine), Ui.fill());
            box.addView(Sheet.item(this, "بهترین را خودکار پیدا کن",
                            "همه را تست می‌کند و اولین‌ای که واقعاً جواب می‌دهد را پیش‌فرض می‌کند",
                            null, this::autoselect), Ui.fill());
            box.addView(Sheet.divider(this), Ui.fill());
            box.addView(Sheet.item(this, "افزودن پرووایدر دلخواه",
                            "هر آدرسی: OpenAI · Anthropic · Gemini · محلی",
                            null, this::addProviderDialog), Ui.fill());
            box.addView(Sheet.divider(this), Ui.fill());
            box.addView(Sheet.section(this, "پرووایدرها"), Ui.fill());

            for (int i = 0; i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                final String id = p.optString("id", "");
                final String label = p.optString("label", id);
                boolean hasKey = p.optBoolean("has_key", false);
                boolean prober = p.has("reachable") && !p.isNull("reachable");
                Boolean reach = prober ? p.optBoolean("reachable") : null;
                // A provider with no key has never been tried, so it gets no dot at all:
                // a red dot there would read as "broken" instead of "not configured".
                Integer dot = !hasKey || reach == null ? null : (reach ? Ui.GREEN : Ui.RED);

                String note = p.optString("note", "");
                StringBuilder sub = new StringBuilder();
                if (!hasKey) {
                    sub.append("بدون کلید");
                    if (!note.isEmpty()) {
                        sub.append(" · ").append(note);
                    }
                } else {
                    sub.append("کلید ✓");
                    if (reach != null) {
                        sub.append(reach ? " · پاسخ می‌دهد" : " · در دسترس نیست");
                        if (reach && p.optLong("probed_ms", 0) > 0) {
                            sub.append(" · ").append(p.optLong("probed_ms", 0)).append("ms");
                        }
                    }
                    if (p.optBoolean("is_default", false)) {
                        sub.append(" · پیش‌فرض");
                    }
                    if (!note.isEmpty()) {
                        sub.append(" · ").append(note);
                    }
                }
                String title = label;
                box.addView(Sheet.item(this, title, sub.toString(), dot,
                        () -> providerActions(id, label)), Ui.fill());
                box.addView(Sheet.divider(this), Ui.fill());
            }
            box.addView(Sheet.item(this, "همه را دوباره تست کن",
                            "وضعیت سبز/قرمز را از نو اندازه می‌گیرد", null, this::probeAll),
                    Ui.fill());
            Sheet.show(this, "پرووایدرها", box);
        }, msg -> toast("لیست نیامد: " + msg));
    }

    /** Measures every provider through the daemon and reopens the list with fresh dots. */
    private void probeAll() {
        AlertDialog spin = progress("تست همه…");
        Api.post("/providers/probe", Api.body(), 90000, res -> {
            spin.dismiss();
            int ok = 0;
            JSONArray results = res.optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null && r.optBoolean("ok", false)) {
                    ok++;
                }
            }
            toast(ok + " پرووایدر جواب داد");
            providersDialog();
        }, msg -> {
            spin.dismiss();
            toast("تست نشد: " + msg);
        });
    }

    /** Picks the first provider that answers and makes it the default model. */
    private void autoselect() {
        AlertDialog spin = progress("تست همه پرووایدرها…");
        Api.post("/providers/autoselect", Api.body(), 120000, res -> {
            spin.dismiss();
            JSONObject chosen = res.optJSONObject("chosen");
            if (chosen == null) {
                toast("هیچ پرووایدری جواب نداد — اتصال را ببین");
                return;
            }
            String mid = chosen.optString("model", "");
            this.model = mid;
            prefs.edit().putString("model", mid).apply();
            modelChip.setText(ChatAdapter.shortModel(mid));
            toast(chosen.optString("id", "") + " · " + chosen.optLong("ms", 0) + " ms");
            if (!sessionId.isEmpty() && !mid.isEmpty()) {
                Api.post("/session/" + sessionId + "/model", Api.body("model", mid), 15000,
                        r -> { }, m -> { });
            }
            loadCatalog();
        }, msg -> {
            spin.dismiss();
            toast("انتخاب نشد: " + msg);
        });
    }

    private void providerActions(String id, String label) {
        LinearLayout box = Ui.col(this);
        box.addView(Sheet.item(this, "تست کلید", "یک درخواست کوچک واقعی می‌فرستد",
                null, () -> {
                    AlertDialog spin = progress("تست…");
                    Api.post("/providers/test", Api.body("id", id), 60000, res -> {
                        spin.dismiss();
                        toast(res.optBoolean("ok", false)
                                ? "کار می‌کند (" + res.optInt("ms", 0) + " ms)"
                                : "نشد: " + res.optString("error", "?"));
                    }, msg -> {
                        spin.dismiss();
                        toast("نشد: " + msg);
                    });
                }), Ui.fill());
        box.addView(Sheet.divider(this), Ui.fill());
        box.addView(Sheet.item(this, "انتخاب مدل و پیش‌فرض‌کردن", id,
                null, () -> chooseModel(id)), Ui.fill());
        box.addView(Sheet.divider(this), Ui.fill());
        box.addView(Sheet.item(this, "حذف این پرووایدر", "کلیدش هم پاک می‌شود",
                null, () -> {
                    Api.delete("/providers/" + id, 20000, res -> {
                        toast("حذف شد");
                        loadCatalog();
                    }, msg -> toast("حذف نشد: " + msg));
                }), Ui.fill());
        Sheet.show(this, label, box);
    }

    /** Model list of one provider, straight from the daemon's registry. */
    private void chooseModel(String id) {
        Api.get("/providers", 15000, res -> {
            JSONArray rows = res.optJSONArray("providers");
            LinearLayout box = Ui.col(this);
            for (int i = 0; rows != null && i < rows.length(); i++) {
                JSONObject p = rows.optJSONObject(i);
                if (p == null || !id.equals(p.optString("id", ""))) {
                    continue;
                }
                JSONArray models = p.optJSONArray("models");
                for (int j = 0; models != null && j < models.length(); j++) {
                    final String mid = models.optString(j, "");
                    if (mid.isEmpty()) {
                        continue;
                    }
                    boolean current = p.optBoolean("is_current_model", false)
                            && j == 0;
                    box.addView(Sheet.item(this, mid, current ? "مدل فعلی" : "",
                            current ? Ui.ACCENT : null, () -> applyModel(id, mid)), Ui.fill());
                    box.addView(Sheet.divider(this), Ui.fill());
                }
            }
            Sheet.show(this, "مدل‌های " + id, box);
        }, msg -> toast(msg));
    }

    private void addProviderDialog() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        final EditText label = Ui.input(this, "نام (مثلاً Local Llama)");
        final EditText base = Ui.input(this, "https://host/v1");
        base.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText key = Ui.input(this, "کلید API (اختیاری)");
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        final EditText models = Ui.input(this, "m1, m2");
        models.setTextDirection(View.TEXT_DIRECTION_LTR);
        labelText(box, "نام");
        box.addView(label, Ui.margins(Ui.fill(), this, 0, 4, 0, 10));
        labelText(box, "آدرس پایه — هر شکلی: OpenAI، Anthropic، Gemini، محلی");
        box.addView(base, Ui.margins(Ui.fill(), this, 0, 4, 0, 10));
        labelText(box, "کلید (اگر لازم ندارد خالی بگذار)");
        box.addView(key, Ui.margins(Ui.fill(), this, 0, 4, 0, 10));
        labelText(box, "مدل‌ها، با کاما");
        box.addView(models, Ui.margins(Ui.fill(), this, 0, 4, 0, 0));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        android.widget.ScrollView scroller = new android.widget.ScrollView(this);
        scroller.addView(box);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("پرووایدر دلخواه")
                .setView(scroller)
                .setPositiveButton("ذخیره", (d, w) -> {
                    String url = base.getText().toString().trim();
                    String kind = url.contains("anthropic") ? "anthropic"
                            : url.contains("generativelanguage") ? "gemini" : "openai";
                    AlertDialog spin = progress("ثبت پرووایدر…");
                    Api.post("/providers", Api.body(
                            "label", label.getText().toString().trim(),
                            "base_url", url,
                            "kind", kind,
                            "key", key.getText().toString().trim(),
                            "models", models.getText().toString().trim()), 60000, res -> {
                        spin.dismiss();
                        if (!res.optBoolean("ok", false)) {
                            toast("ثبت نشد: " + res.optString("error", "?"));
                            return;
                        }
                        toast("ثبت شد: " + res.optString("id", ""));
                        loadCatalog();
                    }, msg -> {
                        spin.dismiss();
                        toast("ثبت نشد: " + msg);
                    });
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    private void labelText(LinearLayout box, String text) {
        box.addView(Ui.text(this, text, 11.5f, Ui.DIM, false));
    }

    /** Provider → key → live test → default model. Everything is written by the daemon. */
    private void setupWizard() {
        Api.get("/setup/state", 12000, state -> {
            JSONArray providers = state.optJSONArray("providers");
            if (providers == null || providers.length() == 0) {
                toast("لیست پرووایدرها نیامد");
                return;
            }
            final List<String> ids = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (int i = 0; i < providers.length(); i++) {
                JSONObject p = providers.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                ids.add(p.optString("id", ""));
                labels.add(p.optString("label", "") + (p.optBoolean("has_key") ? "  ✓" : ""));
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("کدام پرووایدر؟")
                    .setItems(labels.toArray(new String[0]), (d, which) ->
                            askKey(providerById(providers, ids.get(which)), providers))
                    .setNegativeButton("بعداً", null)
                    .show();
        }, msg -> toast("وضعیت راه‌اندازی نیامد: " + msg));
    }

    private static JSONObject providerById(JSONArray providers, String id) {
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && id.equals(p.optString("id"))) {
                return p;
            }
        }
        return new JSONObject();
    }

    private void askKey(JSONObject provider, JSONArray providers) {
        String id = provider.optString("id", "");
        String env = provider.optString("key_env", "");
        String url = provider.optString("key_url", "");
        final EditText key = Ui.input(this, env);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        box.addView(Ui.text(this, "کلید " + provider.optString("label", id)
                + " را بچسبان (فقط روی گوشی ذخیره می‌شود):", 12.5f, Ui.DIM, false));
        box.addView(key, Ui.margins(Ui.fill(), this, 0, 8, 0, 0));
        if (!url.isEmpty()) {
            TextView link = Ui.text(this, "گرفتن کلید: " + url, 11.5f, Ui.ACCENT, false);
            link.setOnClickListener(v -> openUrl(url));
            box.addView(link, Ui.margins(Ui.fill(), this, 0, 8, 0, 0));
        }
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("کلید API")
                .setView(box)
                .setPositiveButton("ذخیره و تست", (d, w) ->
                        saveKey(provider, key.getText().toString().trim(), providers))
                .setNegativeButton("لغو", null)
                .show();
    }

    private void saveKey(JSONObject provider, String key, JSONArray providers) {
        final String id = provider.optString("id", "");
        if (key.isEmpty()) {
            toast("کلید خالی است");
            return;
        }
        AlertDialog dlg = progress("ذخیره و تست کلید…");
        Api.post("/setup/key", Api.body("provider", id, "key", key), 20000, res -> {
            if (!res.optBoolean("ok", false)) {
                dlg.dismiss();
                toast("ذخیره نشد: " + res.optString("error", "?"));
                return;
            }
            Api.post("/setup/test", Api.body("provider", id), 60000, test -> {
                dlg.dismiss();
                if (!test.optBoolean("ok", false)) {
                    a11yToast("کلید ذخیره شد ولی تست رد شد: " + test.optString("error", "?")
                            + " (" + test.optInt("ms", 0) + " ms)");
                    pickModel(provider);
                    return;
                }
                toast("کلید کار می‌کند (" + test.optInt("ms", 0) + " ms)");
                pickModel(provider);
            }, msg -> {
                dlg.dismiss();
                toast("تست نشد: " + msg);
                pickModel(provider);
            });
        }, msg -> {
            dlg.dismiss();
            toast("ذخیره نشد: " + msg);
        });
    }

    private void pickModel(JSONObject provider) {
        JSONArray models = provider.optJSONArray("models");
        if (models == null || models.length() == 0) {
            loadCatalog();
            return;
        }
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject m = models.optJSONObject(i);
            if (m != null) {
                ids.add(m.optString("id", ""));
                labels.add(m.optString("label", m.optString("id", "")));
            }
        }
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("مدل پیش‌فرض")
                .setItems(labels.toArray(new String[0]), (d, which) ->
                        applyModel(provider.optString("id", ""), ids.get(which)))
                .setNegativeButton("بعداً", (d, w) -> loadCatalog())
                .show();
    }

    private void applyModel(String provider, String model) {
        AlertDialog dlg = progress("ثبت مدل…");
        Api.post("/setup/model", Api.body("provider", provider, "model", model), 60000, res -> {
            dlg.dismiss();
            if (!res.optBoolean("ok", false)) {
                toast("ثبت نشد: " + res.optString("detail", "?"));
                return;
            }
            this.model = model;
            prefs.edit().putString("model", model).apply();
            modelChip.setText(ChatAdapter.shortModel(model));
            toast("آماده است — بپرس");
            loadCatalog();
            if (!sessionId.isEmpty()) {
                Api.post("/session/" + sessionId + "/model", Api.body("model", model), 15000,
                        r -> { }, m -> { });
            }
        }, msg -> {
            dlg.dismiss();
            toast("ثبت نشد: " + msg);
        });
    }

    private void a11yToast(String text) {
        toast(text.length() > 80 ? text.substring(0, 79) + "…" : text);
    }

    private void messageMenu(Msg m) {
        String[] items = {"کپی", "بازخوانی صوتی"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("پیام")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        copy(m.text.toString());
                    } else {
                        voice.speak(m.text.toString());
                    }
                }).show();
    }

    private void copyLast() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg m = messages.get(i);
            if (!m.isUser() && m.text.length() > 0) {
                copy(m.text.toString());
                return;
            }
        }
        toast("پاسخی نیست");
    }

    private void copy(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("jarvis", text));
            toast("کپی شد");
        }
    }

    /**
     * The conversations panel. It reopens itself after a delete so the list never shows a
     * conversation that is already gone; if the deleted one was open, reopening it fails and
     * the existing fallback starts a fresh session instead.
     */
    private void openChats() {
        Panels.chats(this, sessionId, id -> {
            prefs.edit().putString("session", id).apply();
            lastSeq = 0;
            openSession(id);
        }, () -> openSession(sessionId));
    }


    private void pickModel() {
        if (catalogJson.isEmpty()) {
            loadCatalog();
            toast("لیست مدل‌ها می‌آید…");
            return;
        }
        try {
            JSONObject cat = new JSONObject(catalogJson);
            JSONArray models = cat.optJSONArray("models");
            final List<String> ids = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (int i = 0; models != null && i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) {
                    continue;
                }
                ids.add(m.optString("model", ""));
                labels.add(m.optString("label", "") + "  ·  " + m.optString("provider", ""));
            }
            Sheet.rows(this, "مدل", labels, which -> {
                model = ids.get(which);
                prefs.edit().putString("model", model).apply();
                modelChip.setText(ChatAdapter.shortModel(model));
                if (!sessionId.isEmpty()) {
                    Api.post("/session/" + sessionId + "/model", Api.body("model", model),
                            15000, res -> toast("مدل عوض شد"), msg -> toast(msg));
                }
            });
        } catch (Exception e) {
            toast("کاتالوگ خوانده نشد");
        }
    }

    private void settings() {
        LinearLayout box = Ui.col(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), 0);
        final EditText base = Ui.input(this, "آدرس سرویس");
        base.setText(Api.base());
        base.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(Ui.text(this, "آدرس jarvisd", 12f, Ui.DIM, false));
        box.addView(base, Ui.margins(Ui.fill(), this, 0, 4, 0, 12));

        final EditText effortField = Ui.input(this, "none / low / medium");
        effortField.setText(effort);
        effortField.setTextDirection(View.TEXT_DIRECTION_LTR);
        box.addView(Ui.text(this, "سطح استدلال (کمتر = سریع‌تر)", 12f, Ui.DIM, false));
        box.addView(effortField, Ui.margins(Ui.fill(), this, 0, 4, 0, 0));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("تنظیمات")
                .setView(box)
                .setPositiveButton("ذخیره", (d, w) -> {
                    Api.base(base.getText().toString().trim());
                    prefs.edit().putString("base", Api.base()).apply();
                    effort = effortField.getText().toString().trim();
                    if (effort.isEmpty()) {
                        effort = "none";
                    }
                    prefs.edit().putString("effort", effort).apply();
                    if (!sessionId.isEmpty()) {
                        Api.post("/session/" + sessionId + "/reasoning",
                                Api.body("effort", effort), 15000, res -> { }, msg -> { });
                    }
                    lastSeq = 0;
                    messages.clear();
                    notifyList();
                    connect(true);
                })
                .setNegativeButton("لغو", null)
                .show();
    }

    // --------------------------------------------------------------------- helpers --
    private void note(String text) {
        messages.add(new Msg(Msg.NOTE).text(text));
        notifyList();
    }

    private void setState(String text, int color) {
        stateLine.setText(text);
        stateLine.setTextColor(color);
        dot.setBackground(Ui.round(this, color, 9));
    }

    void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** Small modal spinner for an action that may take a few seconds. */
    AlertDialog progress(String message) {
        LinearLayout box = Ui.row(this);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        android.widget.ProgressBar spin = new android.widget.ProgressBar(this);
        box.addView(spin, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));
        box.addView(Ui.text(this, message, 13f, Ui.TEXT, false),
                Ui.margins(Ui.wrap(), this, 12, 0, 0, 0));
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setView(box).setCancelable(false).show();
    }

    void openUrl(String url) {
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)));
        } catch (Exception e) {
            toast("مرورگری پیدا نشد");
        }
    }

    private void notifyList() {
        updateEmptyState();
        long now = System.currentTimeMillis();
        if (now - lastNotify < NOTIFY_MS) {
            ui.postDelayed(() -> {
                adapter.notifyDataSetChanged();
                scrollEnd();
            }, NOTIFY_MS);
            lastNotify = now;
            return;
        }
        lastNotify = now;
        adapter.notifyDataSetChanged();
        scrollEnd();
    }

    private void scrollEnd() {
        if (messages.isEmpty()) {
            return;
        }
        list.setSelection(messages.size() - 1);
    }
}








