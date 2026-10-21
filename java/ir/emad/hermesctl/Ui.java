package ir.emad.hermesctl;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Design system: palette, type, cards, chips, pills, buttons. */
public final class Ui {

    public static final int BG = Color.parseColor("#080D17");
    public static final int SURFACE = Color.parseColor("#111A2B");
    public static final int SURFACE_2 = Color.parseColor("#18243A");
    public static final int STROKE = Color.parseColor("#223049");
    public static final int TEXT = Color.parseColor("#E8EEF9");
    public static final int DIM = Color.parseColor("#93A4C0");
    public static final int FAINT = Color.parseColor("#5C6C87");
    public static final int ACCENT = Color.parseColor("#38BDF8");
    public static final int ACCENT_2 = Color.parseColor("#818CF8");
    public static final int GREEN = Color.parseColor("#34D399");
    public static final int AMBER = Color.parseColor("#FBBF24");
    public static final int RED = Color.parseColor("#F87171");
    public static final int PINK = Color.parseColor("#F472B6");

    public static Typeface REG;
    public static Typeface BOLD;

    private Ui() {
    }

    public static void init(Context ctx) {
        if (REG != null) {
            return;
        }
        try {
            REG = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_regular.ttf");
            BOLD = Typeface.createFromAsset(ctx.getAssets(), "fonts/vazirmatn_bold.ttf");
        } catch (Exception e) {
            REG = Typeface.SANS_SERIF;
            BOLD = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
        }
    }

    public static int dp(Context ctx, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                ctx.getResources().getDisplayMetrics());
    }

    public static GradientDrawable round(int color, int radiusDp, Context ctx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    public static GradientDrawable roundStroke(int color, int strokeColor, int radiusDp,
                                              Context ctx) {
        GradientDrawable d = round(color, radiusDp, ctx);
        d.setStroke(dp(ctx, 1), strokeColor);
        return d;
    }

    public static GradientDrawable gradient(int from, int to, int radiusDp, Context ctx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{from, to});
        d.setCornerRadius(dp(ctx, radiusDp));
        return d;
    }

    public static TextView text(Context ctx, String value, float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? BOLD : REG);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Card container with padding, rounded corners and a hairline border. */
    public static LinearLayout card(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 16), dp(ctx, 15), dp(ctx, 16), dp(ctx, 15));
        box.setBackground(roundStroke(SURFACE, STROKE, 20, ctx));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(ctx, 12);
        box.setLayoutParams(lp);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return box;
    }

    public static TextView sectionTitle(Context ctx, String title) {
        TextView t = text(ctx, title, 13.5f, DIM, true);
        t.setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 9));
        return t;
    }

    public static LinearLayout row(Context ctx) {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return r;
    }

    public static LinearLayout col(Context ctx) {
        LinearLayout c = new LinearLayout(ctx);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return c;
    }

    public static LinearLayout.LayoutParams lp(int w, int h, Context ctx) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams weighted(Context ctx, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        return lp;
    }

    /** Small rounded status pill, e.g. "وصل" in green. */
    public static TextView pill(Context ctx, String label, int color) {
        TextView t = text(ctx, label, 11f, color, true);
        t.setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5));
        GradientDrawable bg = round(withAlpha(color, 34), 30, ctx);
        bg.setStroke(dp(ctx, 1), withAlpha(color, 90));
        t.setBackground(bg);
        return t;
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    /** Filled action button. */
    public static TextView button(Context ctx, String label, int color, View.OnClickListener onClick) {
        TextView t = text(ctx, label, 14f, Color.parseColor("#0A1020"), true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(color, 90)),
                round(color, 14, ctx), null));
        t.setOnClickListener(onClick);
        t.setClickable(true);
        return t;
    }

    /** Subtle (outline) action button. */
    public static TextView buttonGhost(Context ctx, String label, int color,
                                       View.OnClickListener onClick) {
        TextView t = text(ctx, label, 13.5f, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        GradientDrawable bg = round(withAlpha(color, 26), 14, ctx);
        bg.setStroke(dp(ctx, 1), withAlpha(color, 110));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(color, 60)), bg, null));
        t.setOnClickListener(onClick);
        t.setClickable(true);
        return t;
    }

    public static TextView chip(Context ctx, String label, boolean active, View.OnClickListener onClick) {
        TextView t = text(ctx, label, 12f, active ? Color.parseColor("#08111F") : DIM, true);
        t.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        t.setBackground(active ? round(ACCENT, 20, ctx) : roundStroke(SURFACE_2, STROKE, 20, ctx));
        t.setOnClickListener(onClick);
        t.setClickable(true);
        return t;
    }

    /** Horizontal scroller so chip strips never overflow the screen. */
    public static android.widget.HorizontalScrollView hscroll(Context ctx, View child) {
        android.widget.HorizontalScrollView s = new android.widget.HorizontalScrollView(ctx);
        s.setHorizontalScrollBarEnabled(false);
        s.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        s.addView(child);
        return s;
    }

    public static void divider(Context ctx, LinearLayout parent) {
        View v = new View(ctx);
        v.setBackgroundColor(STROKE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.7f)));
        lp.topMargin = dp(ctx, 10);
        lp.bottomMargin = dp(ctx, 10);
        parent.addView(v, lp);
    }

    public static String humanDuration(Long seconds) {
        if (seconds == null || seconds < 0) {
            return "—";
        }
        long s = seconds;
        if (s < 60) {
            return s + " ثانیه";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " دقیقه";
        }
        long h = m / 60;
        if (h < 24) {
            return h + " ساعت و " + (m % 60) + " دقیقه";
        }
        return (h / 24) + " روز و " + (h % 24) + " ساعت";
    }

    public static String humanSize(long bytes) {
        double v = bytes;
        String[] units = {"بایت", "کیلوبایت", "مگابایت", "گیگابایت"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.US, i >= 2 ? "%.1f %s" : "%.0f %s", v, units[i]);
    }

    public static EditText input(Context ctx, String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextColor(TEXT);
        e.setHintTextColor(FAINT);
        e.setTypeface(REG);
        e.setTextSize(14f);
        e.setBackground(roundStroke(SURFACE_2, STROKE, 12, ctx));
        e.setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12));
        e.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return e;
    }
}
