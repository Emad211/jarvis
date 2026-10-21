package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Status gauge: a soft track, a glowing arc for "how healthy", and a dot in the centre. */
public class StatusRing extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final int sizeDp;
    private float progress;
    private int color = Ui.FAINT;

    public StatusRing(Context ctx, int sizeDp) {
        super(ctx);
        this.sizeDp = sizeDp;
        for (Paint p : new Paint[]{track, arc, glow}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(Ui.alpha(0xFFFFFFFF, 16));
    }

    public void set(float progress, int color) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.color = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int d = Ui.dp(getContext(), sizeDp);
        setMeasuredDimension(d, d);
    }

    @Override
    protected void onDraw(Canvas c) {
        float stroke = getWidth() * 0.085f;
        float pad = stroke * 1.2f;
        box.set(pad, pad, getWidth() - pad, getHeight() - pad);
        track.setStrokeWidth(stroke);
        arc.setStrokeWidth(stroke);
        arc.setColor(color);
        glow.setStrokeWidth(stroke * 2.2f);
        glow.setColor(Ui.alpha(color, 40));
        c.drawArc(box, 0, 360, false, track);
        c.drawArc(box, -90, 360 * progress, false, glow);
        c.drawArc(box, -90, 360 * progress, false, arc);
        core.setColor(Ui.alpha(color, 60));
        c.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() * 0.16f, core);
        core.setColor(color);
        c.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() * 0.09f, core);
    }
}
