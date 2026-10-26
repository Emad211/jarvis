package ir.emad.jarvis;

import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Bottom sheet, hand-rolled because the app ships without the Material library.
 *
 * Android's own dialogs were the loudest "this looks cheap" tell: Roboto text in a
 * different grey, purple accent buttons, a box floating in the middle of a phone screen.
 * One sheet class gives every secondary surface (menu, sessions, providers, model picker)
 * the same shape: scrim, 56dp rows, the app's own type and colours.
 */
final class Sheet {

    interface Pick {
        void on(int index);
    }

    private static FrameLayout host;

    private Sheet() {
    }

    /** Shows `content` in a sheet with a title row and a close affordance. */
    static void show(Activity a, String title, View content) {
        dismiss();
        FrameLayout root = new FrameLayout(a);
        root.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(a);
        scrim.setBackgroundColor(Color.argb(0, 0, 0, 0));
        final long[] shownAt = {0L};
        scrim.setOnClickListener(v -> {
            // The sheet is added while the button's own touch is still in flight; without
            // this guard the release lands on the scrim and closes what just opened.
            if (android.os.SystemClock.uptimeMillis() - shownAt[0] > 250) {
                dismiss();
            }
        });
        root.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout panel = Ui.col(a);
        panel.setBackground(topRounded(a, Ui.SURFACE, 22));
        panel.setPadding(0, 0, 0, Ui.dp(a, 12));

        LinearLayout head = Ui.row(a);
        head.setPadding(Ui.dp(a, 18), Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 8));
        head.addView(Ui.text(a, title, 15f, Ui.TEXT, true), Ui.weight(1f));
        head.addView(Ui.iconButton(a, Icon.CLOSE, Ui.FAINT, v -> dismiss()));
        panel.addView(head, Ui.fill());

        View handle = new View(a);
        handle.setBackground(Ui.round(a, Ui.STROKE_HI, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = Ui.dp(a, 4);
        hp.topMargin = Ui.dp(a, 8);
        panel.addView(handle, 0, hp);

        android.widget.ScrollView scroller = new android.widget.ScrollView(a);
        scroller.addView(content);
        panel.addView(scroller, Ui.fill());

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pp.gravity = Gravity.BOTTOM;
        root.addView(panel, pp);

        ViewGroup decor = (ViewGroup) a.getWindow().getDecorView();
        host = root;
        panel.setTranslationY(Ui.dp(a, 40));
        panel.setAlpha(0f);
        decor.post(() -> {
            decor.addView(root);
            shownAt[0] = android.os.SystemClock.uptimeMillis();
            panel.animate().translationY(0f).alpha(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).start();
        });
    }

    /** A list of tappable rows in a sheet; the sheet closes after a pick. */
    static void rows(Activity a, String title, List<String> labels, Pick pick) {
        LinearLayout box = Ui.col(a);
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            LinearLayout row = Ui.row(a);
            row.setPadding(Ui.dp(a, 18), Ui.dp(a, 15), Ui.dp(a, 18), Ui.dp(a, 15));
            row.setBackground(Ui.ripple(a, null, Ui.ACCENT));
            row.addView(Ui.text(a, labels.get(i), 14.5f, Ui.TEXT, false), Ui.weight(1f));
            row.addView(new Icon(a, Icon.CHEVRON, Ui.FAINT),
                    new LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14)));
            row.setOnClickListener(v -> {
                dismiss();
                pick.on(index);
            });
            box.addView(row, Ui.fill());
            if (i < labels.size() - 1) {
                View line = new View(a);
                line.setBackgroundColor(Ui.alpha(Ui.STROKE, 200));
                box.addView(line, Ui.margins(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1), a, 18, 0, 18, 0));
            }
        }
        show(a, title, box);
    }

    static void dismiss() {
        if (host != null && host.getParent() instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) host.getParent();
            final FrameLayout dying = host;
            host = null;
            dying.animate().alpha(0f).setDuration(120).withEndAction(() -> {
                parent.removeView(dying);
            }).start();
        }
    }

    static boolean visible() {
        return host != null;
    }

    private static android.graphics.drawable.GradientDrawable topRounded(Activity a, int color,
                                                                        float radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        float r = Ui.dp(a, radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setStroke(Math.max(1, Ui.dp(a, 1)), Ui.STROKE);
        return d;
    }
}

