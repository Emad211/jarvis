package ir.emad.hermesctl;

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
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Design system: palette, type scale, surfaces and the few widgets every screen shares. */
public final class Ui {

    // Palette: deep slate background, one cool accent, semantic status colours.
    public static final int BG = 0xFF0B0F17;
    public static final int SURFACE = 0xFF131A26;
    public static final int SURFACE_2 = 0xFF1B2433;
    public static final int STROKE = 0xFF253147;
    public static final int TEXT = 0xFFEEF2F8;
    public static final int DIM = 0xFF9AA7BC;
    public static final int FAINT = 0xFF64718A;
    public static final int ACCENT = 0xFF8AA4FF;
    public static final int TEAL = 0xFF5EEAD4;
    public static final int GREEN = 0xFF4ADE80;
    public static final int AMBER = 0xFFFBBF24;
    public static final int RED = 0xFFF87171;
    public static final int ON_ACCENT = 0xFF0B1022;

    public static Typeface REG = Typeface.SANS_SERIF;
    public static Typeface BOLD = Typeface.DEFAULT_BOLD;

    private Ui() {
    }

    public static void init(Context ctx) {
        try {
            REG = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_regular.ttf");
            BOLD = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_bold.ttf");
        } catch (Exception ignored) {
            // system sans keeps working
        }
    }

    public static int dp(Context ctx, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics()));
    }

    public static int alpha(int color, int a) {
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color));
    }

    // ------------------------------------------------------------- drawables --
    public static GradientDrawable round(Context ctx, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    public static GradientDrawable outline(Context ctx, int fill, int stroke, float radiusDp) {
        GradientDrawable d = round(ctx, fill, radiusDp);
        d.setStroke(Math.max(1, dp(ctx, 1)), stroke);
        return d;
    }

    public static GradientDrawable gradient(Context ctx, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TR_BL,
                new int[]{from, to});
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    public static Drawable ripple(Context ctx, Drawable base, int tint) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(tint, 60)), base,
                base == null ? round(ctx, Color.WHITE, 14) : null);
    }

    // ---------------------------------------------------------------- layout --
    public static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    public static LinearLayout col(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    public static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    public static LinearLayout.LayoutParams margins(LinearLayout.LayoutParams lp, Context ctx,
                                                    float start, float top, float end, float bottom) {
        lp.setMarginStart(dp(ctx, start));
        lp.topMargin = dp(ctx, top);
        lp.setMarginEnd(dp(ctx, end));
        lp.bottomMargin = dp(ctx, bottom);
        return lp;
    }

    public static View space(Context ctx, float w, float h) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, w), dp(ctx, h)));
        return v;
    }

    // ------------------------------------------------------------------ text --
    public static TextView text(Context ctx, CharSequence value, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REG);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(ctx, 2), 1f);
        return t;
    }

    /** Latin/technical text (model ids, keys, logs) laid out left-to-right in its own box. */
    public static TextView ltr(Context ctx, CharSequence value, float sp, int color, boolean bold) {
        TextView t = text(ctx, value, sp, color, bold);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        // Pinning the edge keeps RTL paragraphs ("… · خط ۲ · KB") in logical order;
        // without this the first-strong heuristic reorders mixed Persian/Latin lines.
        // LTR-forced text belongs on the left even inside an RTL container.
        t.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        return t;
    }

    public static TextView mono(Context ctx, CharSequence value, float sp, int color) {
        TextView t = ltr(ctx, value, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setGravity(Gravity.START);
        t.setTextIsSelectable(true);
        return t;
    }

    public static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    public static TextView section(Context ctx, String title) {
        TextView t = text(ctx, title, 13f, DIM, true);
        t.setPadding(dp(ctx, 6), dp(ctx, 18), dp(ctx, 6), dp(ctx, 8));
        return t;
    }

    // --------------------------------------------------------------- widgets --
    /** Rounded surface with padding; the basic building block of every screen. */
    public static LinearLayout card(Context ctx) {
        LinearLayout box = col(ctx);
        box.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        box.setBackground(outline(ctx, SURFACE, STROKE, 22));
        box.setLayoutParams(margins(fill(), ctx, 0, 0, 0, 10));
        return box;
    }

    /** List row: optional leading icon, title + subtitle, trailing view. */
    public static LinearLayout listRow(Context ctx, Icon icon, String title, CharSequence subtitle,
                                       View trailing, View.OnClickListener onClick) {
        LinearLayout r = row(ctx);
        r.setPadding(dp(ctx, 4), dp(ctx, 10), dp(ctx, 4), dp(ctx, 10));
        if (icon != null) {
            r.addView(icon, margins(new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)),
                    ctx, 0, 0, 12, 0));
        }
        LinearLayout texts = col(ctx);
        texts.addView(oneLine(text(ctx, title, 14f, TEXT, true)));
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(ctx, subtitle, 11.5f, DIM, false);
            s.setPadding(0, dp(ctx, 4), 0, 0);
            texts.addView(s);
        }
        r.addView(texts, weight(1f));
        if (trailing != null) {
            r.addView(trailing, margins(wrap(), ctx, 8, 0, 0, 0));
        }
        if (onClick != null) {
            r.setBackground(ripple(ctx, null, ACCENT));
            r.setOnClickListener(onClick);
        }
        return r;
    }

    public static TextView pill(Context ctx, String label, int color) {
        TextView t = text(ctx, label, 11f, color, true);
        t.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        t.setBackground(round(ctx, alpha(color, 36), 20));
        return t;
    }

    public static View dot(Context ctx, int color, float sizeDp) {
        View v = new View(ctx);
        v.setBackground(round(ctx, color, sizeDp));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)));
        return v;
    }

    /** Filled primary button. */
    public static TextView button(Context ctx, String label, int color, View.OnClickListener l) {
        TextView t = text(ctx, label, 14f, ON_ACCENT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 16), dp(ctx, 13), dp(ctx, 16), dp(ctx, 13));
        t.setBackground(ripple(ctx, round(ctx, color, 16), Color.WHITE));
        t.setOnClickListener(l);
        return t;
    }

    /** Tonal secondary button. */
    public static TextView tonal(Context ctx, String label, int color, View.OnClickListener l) {
        TextView t = text(ctx, label, 13.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 14), dp(ctx, 11), dp(ctx, 14), dp(ctx, 11));
        t.setBackground(ripple(ctx, round(ctx, alpha(color, 30), 14), color));
        t.setOnClickListener(l);
        return t;
    }

    /** Small square icon button. */
    public static Icon iconButton(Context ctx, int glyph, int color, View.OnClickListener l) {
        Icon i = new Icon(ctx, glyph, color);
        int p = dp(ctx, 8);
        i.setPadding(p, p, p, p);
        i.setBackground(ripple(ctx, round(ctx, alpha(color, 22), 12), color));
        i.setOnClickListener(l);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(ctx, 38), dp(ctx, 38)));
        return i;
    }

    public static TextView chip(Context ctx, String label, boolean active, View.OnClickListener l) {
        TextView t = text(ctx, label, 12.5f, active ? ON_ACCENT : DIM, true);
        t.setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8));
        t.setBackground(ripple(ctx, active ? round(ctx, ACCENT, 20)
                : outline(ctx, SURFACE_2, STROKE, 20), ACCENT));
        t.setOnClickListener(l);
        return t;
    }

    public static HorizontalScrollView chips(Context ctx, LinearLayout strip) {
        HorizontalScrollView s = new HorizontalScrollView(ctx);
        s.setHorizontalScrollBarEnabled(false);
        s.addView(strip);
        return s;
    }

    public static void divider(Context ctx, LinearLayout parent) {
        View v = new View(ctx);
        v.setBackgroundColor(alpha(STROKE, 200));
        parent.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dp(ctx, 0.6f))));
    }

    public static EditText input(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTypeface(REG);
        e.setTextSize(14f);
        e.setBackground(outline(ctx, SURFACE_2, STROKE, 14));
        e.setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12));
        return e;
    }

    // ------------------------------------------------------------ formatting --
    public static String duration(long seconds) {
        if (seconds < 0) {
            return "—";
        }
        long m = seconds / 60;
        long h = m / 60;
        if (seconds < 60) {
            return seconds + " ثانیه";
        }
        if (m < 60) {
            return m + " دقیقه";
        }
        if (h < 24) {
            return h + " ساعت " + (m % 60) + " دقیقه";
        }
        return (h / 24) + " روز " + (h % 24) + " ساعت";
    }

    public static String size(long bytes) {
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.US, i >= 2 ? "%.1f %s" : "%.0f %s", v, units[i]);
    }
}
