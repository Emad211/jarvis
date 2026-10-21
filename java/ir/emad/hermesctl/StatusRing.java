package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Glowing status ring with a headline + caption drawn in the centre. */
public class StatusRing extends View {

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    private float progress = 0.72f;
    private int ringColor = Ui.GREEN;
    private String label = "؟";
    private String sub = "";
    private boolean pulsing = false;

    public StatusRing(Context ctx) {
        super(ctx);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setColor(Ui.withAlpha(Color.WHITE, 18));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeCap(Paint.Cap.ROUND);
        title.setTextAlign(Paint.Align.CENTER);
        caption.setTextAlign(Paint.Align.CENTER);
        title.setTypeface(Ui.BOLD);
        caption.setTypeface(Ui.REG);
    }

    public void set(String label, String sub, float progress, int color, boolean pulsing) {
        this.label = label;
        this.sub = sub;
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.ringColor = color;
        this.pulsing = pulsing;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int size = (int) Ui.dp(getContext(), 132);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        float stroke = Ui.dp(getContext(), 11);
        float pad = stroke / 2f + Ui.dp(getContext(), 6);
        box.set(pad, pad, w - pad, h - pad);

        track.setStrokeWidth(stroke);
        c.drawArc(box, 0, 360, false, track);

        arc.setStrokeWidth(stroke);
        arc.setColor(ringColor);
        glow.setStrokeWidth(stroke * 1.9f);
        glow.setColor(Ui.withAlpha(ringColor, pulsing ? 46 : 26));
        float sweep = 360f * progress;

        c.drawArc(box, -90, sweep, false, glow);
        c.drawArc(box, -90, sweep, false, arc);

        // small marker at the head of the arc
        double rad = Math.toRadians(-90 + sweep);
        float cx = box.centerX() + (box.width() / 2f) * (float) Math.cos(rad);
        float cy = box.centerY() + (box.height() / 2f) * (float) Math.sin(rad);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.WHITE);
        c.drawCircle(cx, cy, stroke * 0.42f, dot);

        title.setColor(Ui.TEXT);
        title.setTextSize(Ui.dp(getContext(), 17));
        caption.setColor(Ui.DIM);
        caption.setTextSize(Ui.dp(getContext(), 10.5f));

        float baseY = h / 2f - Ui.dp(getContext(), 2);
        c.drawText(label, w / 2f, baseY, title);
        if (sub != null && sub.length() > 0) {
            c.drawText(sub, w / 2f, baseY + Ui.dp(getContext(), 17), caption);
        }
    }
}
