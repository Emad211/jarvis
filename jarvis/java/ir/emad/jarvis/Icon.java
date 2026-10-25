package ir.emad.jarvis;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.content.Context;
import android.view.View;

/**
 * Line icons on a 24×24 grid (2-unit stroke, round caps) — no icon font, no assets.
 * The console needs only a handful; they are drawn the same way as in Hermes Control.
 */
class Icon extends View {

    static final int SEND = 0, MIC = 1, STOP = 2, SPEAKER = 3, PLUS = 4, MENU = 5, CHECK = 6,
            CLOSE = 7, BOLT = 8, CHAT = 9, GEAR = 10, WARN = 11, TRASH = 12, COPY = 13,
            MIC_OFF = 14;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int glyph;
    private float s;

    Icon(Context ctx, int glyph, int color) {
        super(ctx);
        this.glyph = glyph;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void set(int glyph, int color) {
        this.glyph = glyph;
        paint.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int d = Ui.dp(getContext(), 22);
        setMeasuredDimension(resolveSize(d + getPaddingLeft() + getPaddingRight(), w),
                resolveSize(d + getPaddingTop() + getPaddingBottom(), h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth() - getPaddingLeft() - getPaddingRight();
        float h = getHeight() - getPaddingTop() - getPaddingBottom();
        s = Math.min(w, h) / 24f;
        c.save();
        c.translate(getPaddingLeft() + (w - 24 * s) / 2f, getPaddingTop() + (h - 24 * s) / 2f);
        paint.setStrokeWidth(2f * s);
        drawGlyph(c);
        c.restore();
    }

    private void line(Canvas c, float... p) {
        path.reset();
        path.moveTo(p[0] * s, p[1] * s);
        for (int i = 2; i + 1 < p.length; i += 2) {
            path.lineTo(p[i] * s, p[i + 1] * s);
        }
        c.drawPath(path, paint);
    }

    private void circle(Canvas c, float x, float y, float r) {
        c.drawCircle(x * s, y * s, r * s, paint);
    }

    private void rect(Canvas c, float l, float t, float r, float b, float rad) {
        oval.set(l * s, t * s, r * s, b * s);
        c.drawRoundRect(oval, rad * s, rad * s, paint);
    }

    private void arc(Canvas c, float x, float y, float r, float start, float sweep) {
        oval.set((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s);
        c.drawArc(oval, start, sweep, false, paint);
    }

    private void drawGlyph(Canvas c) {
        switch (glyph) {
            case SEND:
                line(c, 22, 2, 11, 13);
                line(c, 22, 2, 15, 22, 11, 13, 2, 9, 22, 2);
                break;
            case MIC:
                line(c, 12, 3, 9, 6, 9, 11, 12, 14, 15, 11, 15, 6, 12, 3);
                arc(c, 12, 12, 6, 0, 180);
                line(c, 12, 18, 12, 21);
                line(c, 8.5f, 21, 15.5f, 21);
                break;
            case MIC_OFF:
                line(c, 12, 3, 9, 6, 9, 11, 12, 14, 15, 11, 15, 6, 12, 3);
                arc(c, 12, 12, 6, 0, 180);
                line(c, 12, 18, 12, 21);
                line(c, 4, 4, 20, 20);
                break;
            case STOP:
                rect(c, 7, 7, 17, 17, 2.5f);
                break;
            case SPEAKER:
                line(c, 4, 9, 8, 9, 13, 5, 13, 19, 8, 15, 4, 15, 4, 9);
                arc(c, 14, 12, 4, -55, 110);
                arc(c, 14, 12, 7.5f, -55, 110);
                break;
            case PLUS:
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case MENU:
                line(c, 4, 7, 20, 7);
                line(c, 4, 12, 20, 12);
                line(c, 4, 17, 20, 17);
                break;
            case CHECK:
                line(c, 4, 12.5f, 9.5f, 18, 20, 6.5f);
                break;
            case CLOSE:
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case BOLT:
                line(c, 13, 2, 4, 14, 12, 14, 11, 22, 20, 10, 12, 10, 13, 2);
                break;
            case CHAT:
                rect(c, 3, 4, 21, 17, 3);
                line(c, 8, 21, 12, 17);
                break;
            case GEAR:
                circle(c, 12, 12, 3.2f);
                circle(c, 12, 12, 8);
                line(c, 12, 2, 12, 4);
                line(c, 12, 20, 12, 22);
                line(c, 2, 12, 4, 12);
                line(c, 20, 12, 22, 12);
                break;
            case WARN:
                line(c, 12, 3, 22, 20, 2, 20, 12, 3);
                line(c, 12, 9.5f, 12, 13.5f);
                circle(c, 12, 16.8f, 0.5f);
                break;
            case TRASH:
                line(c, 3, 6, 21, 6);
                line(c, 8, 6, 8, 4, 16, 4, 16, 6);
                line(c, 5.5f, 6, 6.5f, 21, 17.5f, 21, 18.5f, 6);
                break;
            case COPY:
                rect(c, 9, 9, 21, 21, 2);
                line(c, 5, 15, 3, 15, 3, 3, 15, 3, 15, 5);
                break;
            default:
                circle(c, 12, 12, 2);
        }
    }
}
