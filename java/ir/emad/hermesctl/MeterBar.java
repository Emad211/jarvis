package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Slim rounded meter used for battery / memory / storage. */
public class MeterBar extends View {

    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float progress = 0f;
    private int color = Ui.ACCENT;

    public MeterBar(Context ctx) {
        super(ctx);
        bg.setColor(Ui.withAlpha(android.graphics.Color.WHITE, 16));
    }

    public void set(float progress, int color) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        this.color = color;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (int) Ui.dp(getContext(), 7));
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        float rad = h / 2f;
        r.set(0, 0, getWidth(), h);
        c.drawRoundRect(r, rad, rad, bg);
        if (progress <= 0) {
            return;
        }
        fg.setColor(color);
        RectF fill = new RectF(getWidth() * (1f - progress), 0, getWidth(), h);
        // RTL: fill from the right edge, like the rest of the layout
        c.drawRoundRect(fill, rad, rad, fg);
    }
}
