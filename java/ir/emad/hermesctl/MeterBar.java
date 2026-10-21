package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Slim rounded meter (battery / memory / storage), filled from the right for RTL. */
public class MeterBar extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float progress;

    public MeterBar(Context ctx) {
        super(ctx);
        bg.setColor(Ui.alpha(0xFFFFFFFF, 18));
    }

    public void set(float progress, int color) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        fg.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Ui.dp(getContext(), 6));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        r.set(0, 0, getWidth(), h);
        c.drawRoundRect(r, h / 2f, h / 2f, bg);
        if (progress > 0) {
            r.set(getWidth() * (1f - progress), 0, getWidth(), h);
            c.drawRoundRect(r, h / 2f, h / 2f, fg);
        }
    }
}
