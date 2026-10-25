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
    private String effort = "none";
    private String catalogJson = "";
    private long lastNotify;
    private String lastTurnText = "";
    private boolean reconnecting;

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

        // header: dot + name + state, model chip, menu
        LinearLayout header = Ui.row(this);
        header.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout titles = Ui.col(this);
        LinearLayout titleRow = Ui.row(this);
        dot = Ui.dot(this, Ui.FAINT, 9);
        titleRow.addView(dot);
        titleRow.addView(Ui.text(this, "JARVIS", 17f, Ui.TEXT, true),
                Ui.margins(Ui.wrap(), this, 8, 0, 0, 0));
        titles.addView(titleRow);
        stateLine = Ui.oneLine(Ui.text(this, "در حال اتصال…", 11.5f, Ui.DIM, false));
        stateLine.setPadding(0, Ui.dp(this, 4), 0, 0);
        titles.addView(stateLine);
        header.addView(titles, Ui.weight(1f));
        modelChip = Ui.pill(this, "مدل", Ui.ACCENT);
        modelChip.setOnClickListener(v -> pickModel());
        header.addView(modelChip, Ui.margins(Ui.wrap(), this, 6, 0, 6, 0));
        header.addView(Ui.iconButton(this, Icon.MENU, Ui.DIM, v -> menu()));
        root.addView(header, Ui.fill());

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setSelector(android.R.color.transparent);
        list.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 8));
        list.setClipToPadding(false);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        adapter = new ChatAdapter(this, messages, v -> {
            messageMenu((Msg) v.getTag());
            return true;
        });
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // quick actions above the input: new chat, listen (TTS toggle)
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 12));
        micIcon = Ui.iconButton(this, Icon.MIC, Ui.ACCENT, v -> toggleMic());
        bar.addView(micIcon);
        input = Ui.input(this, "به جارویس بگو چه کار کن…");
        input.setMaxLines(4);
        // Enter on the soft keyboard sends, like every chat app.
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
        bar.addView(input, Ui.margins(Ui.weight(1f), this, 8, 0, 8, 0));
        sendIcon = Ui.iconButton(this, Icon.SEND, Ui.GREEN, v -> send(false));
        bar.addView(sendIcon);
        root.addView(bar, Ui.fill());

        // Enter on a hardware keyboard sends; the button is the normal path on a phone.
        input.setOnKeyListener((v, code, e) -> false);
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
                adapter.notifyDataSetChanged();
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
            adapter.notifyDataSetChanged();
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
        }, msg -> {
            // catalog is only for the pickers; the chat works without it
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
                setState("ابزار: " + name, Ui.TEAL);
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
                setState("آماده" + (ms > 0 ? " · " + (ms / 1000f) + "s" : ""), Ui.GREEN);
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
        adapter.notifyDataSetChanged();
        scrollEnd();
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
        String[] items = {
                "گفتگوی تازه",
                "گفتگوهای قبلی",
                "تنظیمات",
                "صدا روی پاسخ‌ها: " + (ttsOn ? "روشن" : "خاموش"),
                "کپی آخرین پاسخ",
        };
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("منو")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            openSession("");
                            break;
                        case 1:
                            sessionsDialog();
                            break;
                        case 2:
                            settings();
                            break;
                        case 3:
                            ttsOn = !ttsOn;
                            prefs.edit().putBoolean("tts", ttsOn).apply();
                            toast(ttsOn ? "صدا روشن شد" : "صدا خاموش شد");
                            break;
                        default:
                            copyLast();
                            break;
                    }
                }).show();
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

    private void sessionsDialog() {
        Api.get("/sessions", 15000, res -> {
            JSONArray arr = res.optJSONArray("sessions") != null
                    ? res.optJSONArray("sessions") : new JSONArray();
            final List<String> ids = new ArrayList<>();
            final List<String> labels = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                ids.add(s.optString("id", ""));
                String title = s.optString("title", "بدون عنوان");
                labels.add(title + "  ·  " + s.optInt("turns", 0) + " نوبت"
                        + (s.optBoolean("busy") ? "  ·  در حال اجرا" : ""));
            }
            labels.add(0, "➕ گفتگوی تازه");
            ids.add(0, "");
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("گفتگوها")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        String id = ids.get(which);
                        prefs.edit().putString("session", id).apply();
                        lastSeq = 0;
                        openSession(id);
                    }).show();
        }, msg -> toast("لیست نیامد: " + msg));
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
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("مدل")
                    .setItems(labels.toArray(new String[0]), (d, which) -> {
                        model = ids.get(which);
                        prefs.edit().putString("model", model).apply();
                        modelChip.setText(ChatAdapter.shortModel(model));
                        if (!sessionId.isEmpty()) {
                            Api.post("/session/" + sessionId + "/model", Api.body("model", model),
                                    15000, res -> toast("مدل عوض شد"), msg -> toast(msg));
                        }
                    }).show();
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
                    adapter.notifyDataSetChanged();
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

    private void notifyList() {
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
