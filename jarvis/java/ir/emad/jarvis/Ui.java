package ir.emad.jarvis;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Design tokens and the handful of widgets the console reuses. */
final class Ui {

    // Palette (docs/design-system.md): two neutrals, one accent, three status colours.
    // Same token set as Hermes Control — the two apps must look like one system.
    static final int BG = 0xFF07090E;
    static final int SURFACE = 0xFF11151D;
    static final int SURFACE_2 = 0xFF1A2029;
    static final int STROKE = 0xFF222A36;
    static final int TEXT = 0xFFEDF1F7;
    static final int DIM = 0xFF9BA6B6;
    static final int FAINT = 0xFF6A7484;
    static final int ACCENT = 0xFF7FA6FF;
    static final int GREEN = 0xFF4ADE80;
    static final int AMBER = 0xFFFBBF24;
    static final int RED = 0xFFF87171;
    /** User bubbles: a slightly lifted neutral, so the eye finds its own turns first. */
    static final int USER = 0xFF1D2A44;
    static final int ON_ACCENT = 0xFF06101F;

    /** One radius scale; nothing in the console invents its own number. */
    static final float R_CHIP = 12f;
    static final float R_INPUT = 14f;
    static final float R_BUTTON = 16f;
    static final float R_BUBBLE = 20f;

    static Typeface REG = Typeface.SANS_SERIF;
    static Typeface BOLD = Typeface.DEFAULT_BOLD;
    static Typeface MONO = Typeface.MONOSPACE;

    private Ui() {
    }

    static void init(Context ctx) {
        try {
            REG = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_regular.ttf");
            BOLD = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_bold.ttf");
        } catch (Exception ignored) {
            // system sans keeps working
        }
    }

    static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    // --------------------------------------------------------------- drawables --
    static GradientDrawable round(Context ctx, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    static GradientDrawable outline(Context ctx, int fill, int stroke, float radiusDp) {
        GradientDrawable d = round(ctx, fill, radiusDp);
        d.setStroke(Math.max(1, dp(ctx, 1)), stroke);
        return d;
    }

    static Drawable ripple(Context ctx, Drawable base, int tint) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(tint, 70)), base,
                base == null ? round(ctx, Color.WHITE, 16) : null);
    }

    // ------------------------------------------------------------------ layout --
    static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    static LinearLayout col(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, Context ctx,
                                             float start, float top, float end, float bottom) {
        lp.setMarginStart(dp(ctx, start));
        lp.topMargin = dp(ctx, top);
        lp.setMarginEnd(dp(ctx, end));
        lp.bottomMargin = dp(ctx, bottom);
        return lp;
    }

    // -------------------------------------------------------------------- text --
    static TextView text(Context ctx, CharSequence value, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REG);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(ctx, 3), 1f);
        return t;
    }

    /** Code / machine output: left-to-right regardless of the surrounding RTL layout. */
    static TextView mono(Context ctx, CharSequence value, float sp, int color) {
        TextView t = text(ctx, value, sp, color, false);
        t.setTypeface(MONO);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        t.setGravity(Gravity.START);
        return t;
    }

    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Quiet chip: a border and a tint, not a filled pastel block. */
    static TextView pill(Context ctx, String label, int color) {
        TextView t = text(ctx, label, 11f, color, true);
        t.setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4));
        t.setBackground(outline(ctx, alpha(color, 18), alpha(color, 70), R_CHIP));
        return t;
    }

    static TextView button(Context ctx, String label, int color, View.OnClickListener l) {
        TextView t = text(ctx, label, 14f, ON_ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));
        t.setBackground(ripple(ctx, round(ctx, color, R_BUTTON), Color.WHITE));
        t.setOnClickListener(l);
        return t;
    }

    static TextView tonal(Context ctx, String label, int color, View.OnClickListener l) {
        TextView t = text(ctx, label, 13f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 13), dp(ctx, 9), dp(ctx, 13), dp(ctx, 9));
        t.setBackground(ripple(ctx, round(ctx, alpha(color, 32), R_BUTTON), color));
        t.setOnClickListener(l);
        return t;
    }

    static Icon iconButton(Context ctx, int glyph, int color, View.OnClickListener l) {
        Icon i = new Icon(ctx, glyph, color);
        int p = dp(ctx, 10);
        i.setPadding(p, p, p, p);
        i.setBackground(ripple(ctx, round(ctx, alpha(color, 26), R_CHIP), color));
        i.setOnClickListener(l);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42)));
        return i;
    }

    static View dot(Context ctx, int color, float sizeDp) {
        View v = new View(ctx);
        v.setBackground(round(ctx, color, sizeDp));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        return v;
    }

    static EditText input(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTypeface(REG);
        e.setTextSize(15f);
        e.setBackground(outline(ctx, SURFACE_2, STROKE, R_INPUT));
        e.setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11));
        return e;
    }

    static String clock(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Tehran"));
        return f.format(new java.util.Date(millis));
    }
}
