package ir.emad.jarvis;

import java.util.ArrayList;
import java.util.List;

/** One entry in the transcript: a user turn, the assistant's answer, or a notice. */
final class Msg {

    static final String USER = "user";
    static final String ASSISTANT = "assistant";
    static final String NOTE = "note";
    static final String ACTION = "action";

    /**
     * Hermes writes its own transport failures into the answer stream as plain text
     * ("API call failed after 3 retries …"). That is an internal diagnostic, not an answer:
     * the transcript shows a short Persian card instead and keeps the first line as detail.
     */
    static boolean looksFailed(String t) {
        if (t == null || t.isEmpty()) {
            return false;
        }
        return t.startsWith("API call failed") || t.startsWith("❌ API failed")
                || t.startsWith("⚠️") || t.contains("can't reach the model provider");
    }

    final String role;
    final StringBuilder text = new StringBuilder();
    final List<ToolEv> tools = new ArrayList<>();
    final long ts = System.currentTimeMillis();
    boolean streaming;
    Integer ttftMs;
    Integer totalMs;
    String model;
    int seq;
    /** For ACTION rows: the fast-path verdict and any media the daemon produced. */
    boolean actionOk;
    String actionLabel = "";
    String media = "";
    /** Which lane actually ran it: "bridge" (the app's own hands), "adb" or "rish". */
    String actionSource = "";

    Msg(String role) {
        this.role = role;
    }

    Msg text(CharSequence value) {
        text.append(value);
        return this;
    }

    boolean isUser() {
        return USER.equals(role);
    }

    /** A tool call inside an assistant turn, rendered as a compact card. */
    static final class ToolEv {
        final String id;
        final String name;
        final long startMs = System.currentTimeMillis();
        String args = "";
        String result = "";
        boolean done;
        boolean failed;

        ToolEv(String id, String name, String args) {
            this.id = id == null ? "" : id;
            this.name = name == null || name.isEmpty() ? "tool" : name;
            this.args = args == null ? "" : args;
        }

        long elapsedMs() {
            return System.currentTimeMillis() - startMs;
        }
    }
}
