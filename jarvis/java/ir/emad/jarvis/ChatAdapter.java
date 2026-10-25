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
 * Transcript list. User turns sit on the start edge, Jarvis on the end edge, and every
 * tool call appears inside the answer it belongs to — so a turn reads as one block even
 * while it is still streaming.
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
            holder.setPadding(0, Ui.dp(ctx, 3), 0, Ui.dp(ctx, 3));
        }
        holder.removeAllViews();
        Msg m = items.get(position);
        LinearLayout bubble = Ui.col(ctx);
        bubble.setPadding(Ui.dp(ctx, 14), Ui.dp(ctx, 11), Ui.dp(ctx, 14), Ui.dp(ctx, 10));

        if (m.isUser()) {
            bubble.setBackground(Ui.round(ctx, Ui.USER, Ui.R_BUBBLE));
            TextView t = Ui.text(ctx, m.text, 15f, Ui.TEXT, false);
            t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
            bubble.addView(t);
            holder.setGravity(Gravity.END);  // own turn: the end edge (left in RTL mirroring)
            holder.addView(bubble, widthLimit());
        } else if (Msg.NOTE.equals(m.role)) {
            // A note is a system message: red text on the normal surface, not a red block.
            bubble.setBackground(Ui.outline(ctx, Ui.SURFACE, Ui.STROKE, Ui.R_BUBBLE));
            bubble.addView(Ui.text(ctx, m.text, 12.5f, Ui.RED, false));
            holder.setGravity(Gravity.CENTER_HORIZONTAL);
            holder.addView(bubble, widthLimit());
        } else if (Msg.ACTION.equals(m.role)) {
            // A fast-path action: the same card language as a tool, outside the agent turn.
            bubble.setBackground(Ui.outline(ctx, Ui.SURFACE, Ui.STROKE, Ui.R_BUBBLE));
            LinearLayout head = Ui.row(ctx);
            int tone = m.actionOk ? Ui.GREEN : Ui.RED;
            head.addView(new Icon(ctx, m.actionOk ? Icon.CHECK : Icon.WARN, tone),
                    new LinearLayout.LayoutParams(Ui.dp(ctx, 15), Ui.dp(ctx, 15)));
            head.addView(Ui.text(ctx, m.actionLabel, 12.5f, Ui.TEXT, true),
                    Ui.margins(Ui.wrap(), ctx, 6, 0, 0, 0));
            if (m.totalMs != null) {
                head.addView(Ui.text(ctx, m.totalMs + " ms", 10.5f, Ui.FAINT, false),
                        Ui.margins(Ui.wrap(), ctx, 8, 0, 0, 0));
            }
            bubble.addView(head);
            if (m.text.length() > 0) {
                TextView detail = Ui.text(ctx, m.text, 12.5f, Ui.DIM, false);
                detail.setPadding(0, Ui.dp(ctx, 6), 0, 0);
                detail.setTextIsSelectable(true);
                bubble.addView(detail);
            }
            if (!m.media.isEmpty()) {
                android.widget.ImageView shot = new android.widget.ImageView(ctx);
                shot.setAdjustViewBounds(true);
                shot.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                shot.setBackground(Ui.round(ctx, Ui.SURFACE_2, Ui.R_CHIP));
                // A preview, not the artefact: cap the height so one screenshot cannot
                // swallow the transcript. Tapping opens it full-size through the message menu.
                int h = Ui.dp(ctx, 220);
                bubble.addView(shot, Ui.margins(
                        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h),
                        ctx, 0, 8, 0, 0));
                Img.load(shot, m.media);
            }
            holder.setGravity(Gravity.START);
            holder.addView(bubble, widthLimit());
        } else {
            bubble.setBackground(Ui.outline(ctx, Ui.SURFACE, Ui.STROKE, Ui.R_BUBBLE));
            for (Msg.ToolEv tool : m.tools) {
                bubble.addView(toolCard(tool));
            }
            TextView t = Ui.text(ctx, "", 15f, Ui.TEXT, false);
            if (m.text.length() == 0 && m.streaming) {
                t.setText("…");
            } else {
                t.setText(Md.render(m.text.toString(), Ui.ACCENT, Ui.alpha(Ui.ACCENT, 26)));
            }
            t.setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
            t.setTextIsSelectable(true);
            bubble.addView(t);
            LinearLayout foot = Ui.row(ctx);
            foot.setPadding(0, Ui.dp(ctx, 7), 0, 0);
            if (m.streaming) {
                foot.addView(Ui.pill(ctx, "در حال نوشتن…", Ui.ACCENT));
            } else {
                if (m.ttftMs != null) {
                    foot.addView(Ui.pill(ctx, firstToken(m.ttftMs), Ui.FAINT));
                }
                if (m.model != null && !m.model.isEmpty()) {
                    foot.addView(Ui.pill(ctx, shortModel(m.model), Ui.FAINT),
                            Ui.margins(Ui.wrap(), ctx, 6, 0, 0, 0));
                }
                TextView time = Ui.text(ctx, Ui.clock(m.ts), 10.5f, Ui.FAINT, false);
                foot.addView(time, Ui.margins(Ui.wrap(), ctx, 6, 0, 0, 0));
            }
            bubble.addView(foot);
            holder.setGravity(Gravity.START);
            holder.addView(bubble, widthLimit());
        }
        if (onLong != null) {
            bubble.setTag(m);
            bubble.setOnLongClickListener(onLong);
        }
        return holder;
    }

    /** Bubbles never fill the whole width: the ragged edge is what makes turns readable. */
    private LinearLayout.LayoutParams widthLimit() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Ui.dp(ctx, 8));
        lp.setMarginEnd(Ui.dp(ctx, 8));
        return lp;
    }

    private View toolCard(Msg.ToolEv tool) {
        LinearLayout card = Ui.col(ctx);
        card.setPadding(Ui.dp(ctx, 10), Ui.dp(ctx, 8), Ui.dp(ctx, 10), Ui.dp(ctx, 8));
        // Nested inside a bordered bubble, so no second border: just a darker fill.
        card.setBackground(Ui.round(ctx, Ui.SURFACE_2, Ui.R_CHIP));
        LinearLayout head = Ui.row(ctx);
        // State lives in the icon and its colour only — never in the card background.
        int color = tool.failed ? Ui.RED : tool.done ? Ui.GREEN : Ui.AMBER;
        head.addView(new Icon(ctx, tool.failed ? Icon.WARN : tool.done ? Icon.CHECK : Icon.BOLT,
                color), new LinearLayout.LayoutParams(Ui.dp(ctx, 15), Ui.dp(ctx, 15)));
        TextView name = Ui.mono(ctx, tool.name, 11.5f, Ui.TEXT);
        name.setTypeface(Ui.BOLD);
        head.addView(name, Ui.margins(Ui.wrap(), ctx, 6, 0, 0, 0));
        if (tool.done) {
            head.addView(Ui.text(ctx, tool.elapsedMs() + " ms", 10.5f, Ui.FAINT, false),
                    Ui.margins(Ui.wrap(), ctx, 8, 0, 0, 0));
        }
        card.addView(head);
        if (tool.args != null && !tool.args.isEmpty() && !"{}".equals(tool.args)) {
            TextView a = Ui.mono(ctx, clamp(tool.args, 240), 10.5f, Ui.DIM);
            a.setPadding(0, Ui.dp(ctx, 5), 0, 0);
            card.addView(a);
        }
        if (tool.done && tool.result != null && !tool.result.isEmpty()) {
            TextView r = Ui.mono(ctx, clamp(tool.result, 420), 10.5f,
                    tool.failed ? Ui.RED : Ui.FAINT);
            r.setPadding(0, Ui.dp(ctx, 5), 0, 0);
            card.addView(r);
        }
        LinearLayout.LayoutParams lp = Ui.fill();
        lp.bottomMargin = Ui.dp(ctx, 8);
        card.setLayoutParams(lp);
        return card;
    }

    private static String clamp(String s, int max) {
        String v = s.replace("\r", "");
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    private static String firstToken(int ms) {
        return ms < 1000 ? ms + " ms" : String.format(java.util.Locale.US, "%.1f s", ms / 1000f);
    }

    static String shortModel(String model) {
        if (model == null) {
            return "";
        }
        int slash = model.lastIndexOf('/');
        return slash >= 0 ? model.substring(slash + 1) : model;
    }
}
