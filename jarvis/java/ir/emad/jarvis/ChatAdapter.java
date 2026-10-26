package ir.emad.jarvis;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Transcript. Deliberately asymmetric: the user's turns are the only bubbles, so the eye
 * follows them down the page, and Jarvis' answers sit on the background like a document —
 * which is what they are. Tool calls are quoted inside the answer they belong to.
 */
final class ChatAdapter extends BaseAdapter {

    private final Context ctx;
    private final List<Msg> items;
    private final View.OnLongClickListener onLong;

    ChatAdapter(Context ctx, List<Msg> items, View.OnLongClickListener onLong) {
        this.ctx = ctx;
        this.items = items;
        this.onLong = onLong;
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Object getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convert, ViewGroup parent) {
        LinearLayout holder = (LinearLayout) convert;
        if (holder == null) {
            holder = Ui.col(ctx);
            holder.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 4), Ui.dp(ctx, 16), Ui.dp(ctx, 4));
        }
        holder.removeAllViews();
        Msg m = items.get(position);

        if (m.isUser()) {
            LinearLayout bubble = Ui.col(ctx);
            bubble.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 11), Ui.dp(ctx, 16), Ui.dp(ctx, 11));
            bubble.setBackground(Ui.round(ctx, Ui.USER, 20));
            TextView t = Ui.text(ctx, m.text, 15.5f, Ui.TEXT, false);
            t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
            bubble.addView(t);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            holder.setGravity(Gravity.END);
            holder.addView(bubble, lp);
        } else if (Msg.NOTE.equals(m.role)) {
            TextView t = Ui.text(ctx, m.text, 12.5f, Ui.DIM, false);
            t.setGravity(Gravity.CENTER);
            holder.setGravity(Gravity.CENTER_HORIZONTAL);
            holder.addView(t, Ui.fill());
        } else if (Msg.ACTION.equals(m.role)) {
            holder.addView(actionCard(m));
        } else if (Msg.looksFailed(m.text.toString())) {
            holder.setGravity(Gravity.START);
            holder.addView(failureCard(m), Ui.fill());
        } else {
            holder.setGravity(Gravity.START);
            holder.addView(answer(m));
        }
        if (onLong != null) {
            holder.setTag(m);
            holder.setOnLongClickListener(onLong);
        }
        return holder;
    }

    /** A failed turn: one short Persian line, the machine detail kept but de-emphasised. */
    private View failureCard(Msg m) {
        LinearLayout card = Ui.col(ctx);
        card.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 12), Ui.dp(ctx, 14), Ui.dp(ctx, 12));
        card.setBackground(Ui.round(ctx, Ui.SURFACE_2, Ui.R_CARD));
        LinearLayout head = Ui.row(ctx);
        head.addView(new Icon(ctx, Icon.WARN, Ui.RED),
                new LinearLayout.LayoutParams(Ui.dp(ctx, 13), Ui.dp(ctx, 13)));
        head.addView(Ui.text(ctx, "پاسخ نیامد", 12.5f, Ui.RED, true),
                Ui.margins(Ui.wrap(), ctx, 6, 0, 0, 0));
        card.addView(head);
        card.addView(Ui.text(ctx, "پرووایدر جواب نداد. اتصال یا کلید را ببین، یا در «پرووایدرها» "
                        + "گزینهٔ «بهترین را خودکار پیدا کن» را بزن.", 12.5f, Ui.DIM, false),
                Ui.margins(Ui.fill(), ctx, 0, 6, 0, 0));
        String raw = m.text.toString().split("\n")[0].trim();
        int comma = raw.indexOf("      ");
        if (comma > 0) {
            raw = raw.substring(0, comma);
        }
        card.addView(Ui.mono(ctx, raw.length() > 120 ? raw.substring(0, 120) + "…" : raw,
                        10f, Ui.FAINT), Ui.margins(Ui.fill(), ctx, 0, 6, 0, 0));
        return card;
    }

    /** The answer itself: no bubble, a small role line, quiet metrics. */
    private View answer(Msg m) {
        LinearLayout wrap = Ui.col(ctx);
        LinearLayout roleRow = Ui.row(ctx);
        roleRow.addView(new Icon(ctx, Icon.BOLT, m.streaming ? Ui.ACCENT : Ui.FAINT),
                new LinearLayout.LayoutParams(Ui.dp(ctx, 12), Ui.dp(ctx, 12)));
        roleRow.addView(Ui.text(ctx, "JARVIS", 10.5f, Ui.FAINT, true),
                Ui.margins(Ui.wrap(), ctx, 5, 0, 0, 0));
        wrap.addView(roleRow);

        LinearLayout body = Ui.col(ctx);
        for (Msg.ToolEv tool : m.tools) {
            body.addView(toolCard(tool), Ui.margins(Ui.fill(), ctx, 0, 8, 0, 0));
        }
        TextView text = Ui.text(ctx, "", 15.5f, Ui.TEXT, false);
        String prose = m.text.toString();
        // Agent-side tools report attachments as `MEDIA:/abs/path` lines. The app cannot open
        // an arbitrary Termux path, so the answer shows the fact and drops the path.
        StringBuilder cleaned = new StringBuilder();
        int shots = 0;
        for (String line : prose.split("\n")) {
            if (line.trim().startsWith("MEDIA:")) {
                shots++;
                continue;
            }
            cleaned.append(line).append('\n');
        }
        if (shots > 0) {
            prose = cleaned.toString().trim();
            if (prose.isEmpty()) {
                // No emoji here: the bundled Persian font has no glyph for 📷 and it renders
                // as an empty box, which looks like a bug rather than a picture.
                prose = shots == 1 ? "یک تصویر گرفته شد." : shots + " تصویر گرفته شد.";
            }
        }
        text.setText(Md.render(prose.trim(), Ui.ACCENT, Ui.alpha(Ui.ACCENT, 22)));
        text.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        text.setTextIsSelectable(true);
        text.setLineSpacing(Ui.dp(ctx, 5), 1f);
        text.setPadding(0, Ui.dp(ctx, 8), 0, 0);
        body.addView(text);

        LinearLayout foot = Ui.row(ctx);
        foot.setPadding(0, Ui.dp(ctx, 10), 0, 0);
        if (m.streaming) {
            foot.addView(Ui.text(ctx, "در حال نوشتن…", 10.5f, Ui.ACCENT, false));
        } else {
            // One latin run: model, first token, total. Split views made RTL bidi shuffle
            // the numbers into an unreadable soup ("9.881s · 9.8sgemini-3.5-flash").
            StringBuilder meta = new StringBuilder();
            if (m.model != null && !m.model.isEmpty()) {
                meta.append(shortModel(m.model));
            }
            if (m.ttftMs != null) {
                meta.append(meta.length() > 0 ? " · " : "").append(firstToken(m.ttftMs));
            }
            if (m.totalMs != null) {
                meta.append(meta.length() > 0 ? " · " : "")
                        .append(String.format(java.util.Locale.US, "%.1fs", m.totalMs / 1000f));
            }
            foot.addView(Ui.mono(ctx, meta.toString(), 10.5f, Ui.FAINT));
            foot.addView(Ui.spacer(ctx), Ui.weight(1f));
            foot.addView(Ui.text(ctx, Ui.clock(m.ts), 10.5f, Ui.FAINT, false));
        }
        body.addView(foot);
        wrap.addView(body, Ui.fill());
        return wrap;
    }

    private View actionCard(Msg m) {
        LinearLayout card = Ui.row(ctx);
        card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 10), Ui.dp(ctx, 12), Ui.dp(ctx, 10));
        card.setBackground(Ui.outline(ctx, Ui.SURFACE, Ui.STROKE, 16));
        int tone = m.actionOk ? Ui.GREEN : Ui.RED;
        card.addView(new Icon(ctx, m.actionOk ? Icon.CHECK : Icon.WARN, tone),
                new LinearLayout.LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16)));
        LinearLayout texts = Ui.col(ctx);
        texts.addView(Ui.text(ctx, m.actionLabel, 13f, Ui.TEXT, true));
        if (m.text.length() > 0) {
            TextView d = Ui.text(ctx, m.text, 12f, Ui.DIM, false);
            d.setPadding(0, Ui.dp(ctx, 3), 0, 0);
            texts.addView(d);
        }
        card.addView(texts, Ui.margins(Ui.weight(1f), ctx, 8, 0, 0, 0));
        if (m.totalMs != null) {
            // The lane matters as much as the number: "bridge" means the app acted on itself
            // with no adb and no Shizuku, and that is the whole prerequisite story in one word.
            String lane = "";
            if ("bridge".equals(m.actionSource)) {
                lane = " · خود اپ";
            } else if (!m.actionSource.isEmpty()) {
                lane = " · " + m.actionSource;
            }
            card.addView(Ui.text(ctx, m.totalMs + "ms" + lane, 10.5f, Ui.FAINT, false));
        }
        LinearLayout holder = Ui.col(ctx);
        if (!m.media.isEmpty()) {
            card.setLayoutParams(Ui.fill());
            holder.addView(card, Ui.fill());
            android.widget.ImageView shot = new android.widget.ImageView(ctx);
            shot.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            shot.setBackground(Ui.round(ctx, Ui.SURFACE_2, 14));
            holder.addView(shot, Ui.margins(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 240)), ctx, 0, 6, 0, 0));
            Img.load(shot, m.media);
        } else {
            holder.addView(card, Ui.fill());
        }
        return holder;
    }

    private View toolCard(Msg.ToolEv tool) {
        LinearLayout card = Ui.col(ctx);
        card.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 9), Ui.dp(ctx, 12), Ui.dp(ctx, 9));
        card.setBackground(Ui.outline(ctx, Ui.SURFACE, Ui.STROKE, 14));
        LinearLayout head = Ui.row(ctx);
        int color = tool.failed ? Ui.RED : tool.done ? Ui.GREEN : Ui.AMBER;
        head.addView(new Icon(ctx, tool.failed ? Icon.WARN : tool.done ? Icon.CHECK : Icon.BOLT,
                color), new LinearLayout.LayoutParams(Ui.dp(ctx, 14), Ui.dp(ctx, 14)));
        TextView name = Ui.mono(ctx, tool.name, 12f, Ui.TEXT);
        name.setTypeface(Ui.BOLD);
        head.addView(name, Ui.margins(Ui.wrap(), ctx, 7, 0, 0, 0));
        head.addView(Ui.spacer(ctx), Ui.weight(1f));
        if (tool.done) {
            head.addView(Ui.text(ctx, tool.elapsedMs() + " ms", 10.5f, Ui.FAINT, false));
        }
        card.addView(head);
        String args = clamp(tool.args, 200);
        if (!args.isEmpty() && !"{}".equals(args)) {
            TextView a = Ui.mono(ctx, args, 10.5f, Ui.DIM);
            a.setPadding(0, Ui.dp(ctx, 6), 0, 0);
            card.addView(a);
        }
        if (tool.done && tool.result != null && !tool.result.isEmpty()) {
            TextView r = Ui.mono(ctx, clamp(tool.result, 500), 10.5f,
                    tool.failed ? Ui.RED : Ui.FAINT);
            r.setPadding(0, Ui.dp(ctx, 6), 0, 0);
            card.addView(r);
        }
        return card;
    }

    private static String clamp(String s, int max) {
        String v = s == null ? "" : s.replace("\r", "");
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String firstToken(int ms) {
        return ms < 1000 ? ms + "ms" : String.format(java.util.Locale.US, "%.1fs", ms / 1000f);
    }

    static String shortModel(String model) {
        if (model == null) {
            return "";
        }
        int slash = model.lastIndexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }
}

