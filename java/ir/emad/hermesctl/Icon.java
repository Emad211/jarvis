package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Line icons drawn on a 24×24 grid (Lucide-style: 2-unit stroke, round caps), so the app
 * needs no icon font or image assets and the glyphs stay crisp at any size.
 */
public class Icon extends View {

    public static final int HOME = 0, MODEL = 1, KEY = 2, LOGS = 3, SETTINGS = 4, REFRESH = 5,
            PLAY = 6, STOP = 7, BOLT = 8, SEND = 9, PLUS = 10, TRASH = 11, UP = 12, DOWN = 13,
            CLOSE = 14, CHECK = 15, ALERT = 16, USERS = 17, SERVER = 18, LINK = 19, POWER = 20,
            SHIELD = 21, CLOCK = 22, SEARCH = 23;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private int glyph;
    private float s;

    public Icon(Context ctx, int glyph, int color) {
        super(ctx);
        this.glyph = glyph;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    public void set(int glyph, int color) {
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
            case HOME:
                line(c, 3, 10.5f, 12, 3, 21, 10.5f);
                line(c, 5, 9, 5, 21, 19, 21, 19, 9);
                line(c, 10, 21, 10, 15, 14, 15, 14, 21);
                break;
            case MODEL:
                rect(c, 6, 6, 18, 18, 2.5f);
                rect(c, 9.5f, 9.5f, 14.5f, 14.5f, 1);
                for (float p : new float[]{9.5f, 14.5f}) {
                    line(c, p, 2, p, 6);
                    line(c, p, 18, p, 22);
                    line(c, 2, p, 6, p);
                    line(c, 18, p, 22, p);
                }
                break;
            case KEY:
                circle(c, 7.5f, 15.5f, 4.5f);
                line(c, 10.7f, 12.3f, 21, 2);
                line(c, 16, 7, 19, 10);
                line(c, 18.5f, 4.5f, 21, 7);
                break;
            case LOGS:
                line(c, 8, 6, 21, 6);
                line(c, 8, 12, 21, 12);
                line(c, 8, 18, 21, 18);
                circle(c, 3.5f, 6, 0.6f);
                circle(c, 3.5f, 12, 0.6f);
                circle(c, 3.5f, 18, 0.6f);
                break;
            case SETTINGS:
                line(c, 4, 6, 20, 6);
                line(c, 4, 12, 20, 12);
                line(c, 4, 18, 20, 18);
                circle(c, 9, 6, 2.2f);
                circle(c, 15, 12, 2.2f);
                circle(c, 8, 18, 2.2f);
                break;
            case REFRESH:
                arc(c, 12, 12, 8, -60, 290);
                line(c, 16, 3, 16.5f, 6.5f, 20, 6);
                break;
            case PLAY:
                line(c, 7, 4, 19, 12, 7, 20, 7, 4);
                break;
            case STOP:
                rect(c, 6, 6, 18, 18, 2);
                break;
            case BOLT:
                line(c, 13, 2, 4, 14, 12, 14, 11, 22, 20, 10, 12, 10, 13, 2);
                break;
            case SEND:
                line(c, 22, 2, 11, 13);
                line(c, 22, 2, 15, 22, 11, 13, 2, 9, 22, 2);
                break;
            case PLUS:
                line(c, 12, 5, 12, 19);
                line(c, 5, 12, 19, 12);
                break;
            case TRASH:
                line(c, 3, 6, 21, 6);
                line(c, 8, 6, 8, 4, 16, 4, 16, 6);
                line(c, 5.5f, 6, 6.5f, 21, 17.5f, 21, 18.5f, 6);
                break;
            case UP:
                line(c, 6, 15, 12, 9, 18, 15);
                break;
            case DOWN:
                line(c, 6, 9, 12, 15, 18, 9);
                break;
            case CLOSE:
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
                break;
            case CHECK:
                line(c, 4, 12.5f, 9.5f, 18, 20, 6.5f);
                break;
            case ALERT:
                line(c, 12, 3, 22, 20, 2, 20, 12, 3);
                line(c, 12, 9.5f, 12, 13.5f);
                circle(c, 12, 16.8f, 0.5f);
                break;
            case USERS:
                circle(c, 9, 8, 3.5f);
                arc(c, 9, 21, 6.5f, 180, 180);
                arc(c, 16.5f, 8, 3, -90, 180);
                line(c, 18, 14.5f, 21.5f, 19);
                break;
            case SERVER:
                rect(c, 3, 3, 21, 10, 2);
                rect(c, 3, 14, 21, 21, 2);
                circle(c, 7, 6.5f, 0.6f);
                circle(c, 7, 17.5f, 0.6f);
                break;
            case LINK:
                line(c, 14, 4, 20, 4, 20, 10);
                line(c, 20, 4, 11, 13);
                line(c, 17, 14, 17, 20, 4, 20, 4, 7, 10, 7);
                break;
            case POWER:
                line(c, 12, 2.5f, 12, 11);
                arc(c, 12, 13, 8, -55, 290);
                break;
            case SHIELD:
                line(c, 12, 2.5f, 20, 6, 20, 12);
                line(c, 12, 2.5f, 4, 6, 4, 12);
                path.reset();
                path.moveTo(4 * s, 12 * s);
                path.quadTo(5 * s, 19 * s, 12 * s, 22 * s);
                path.quadTo(19 * s, 19 * s, 20 * s, 12 * s);
                c.drawPath(path, paint);
                break;
            case CLOCK:
                circle(c, 12, 12, 9);
                line(c, 12, 7, 12, 12, 15.5f, 14);
                break;
            case SEARCH:
                circle(c, 10.5f, 10.5f, 6.5f);
                line(c, 15.5f, 15.5f, 21, 21);
                break;
            default:
                circle(c, 12, 12, 2);
        }
    }
}
