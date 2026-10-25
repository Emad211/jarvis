package ir.emad.jarvis;

import java.util.ArrayList;
import java.util.List;

/** One entry in the transcript: a user turn, the assistant's answer, or a notice. */
final class Msg {

    static final String USER = "user";
    static final String ASSISTANT = "assistant";
    static final String NOTE = "note";

    final String role;
    final StringBuilder text = new StringBuilder();
    final List<ToolEv> tools = new ArrayList<>();
    final long ts = System.currentTimeMillis();
    boolean streaming;
    Integer ttftMs;
    Integer totalMs;
    String model;
    int seq;

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
