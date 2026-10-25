package ir.emad.hermesctl;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One tab. Each screen owns a single scroll view for its whole life, and every refresh
 * re-renders into that same container — so background refreshes are visible and the
 * scroll position survives.
 */
abstract class Screen {

    final MainActivity a;
    final ScrollView root;
    final LinearLayout box;
    private boolean loaded;
    private boolean inFlight;

    Screen(MainActivity a) {
        this.a = a;
        root = new ScrollView(a);
        root.setFillViewport(true);
        root.setClipToPadding(false);
        box = Ui.col(a);
        box.setPadding(Ui.dp(a, 16), Ui.dp(a, 4), Ui.dp(a, 16), Ui.dp(a, 28));
        root.addView(box, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Fetch and render. Shows a placeholder only before the first successful load. */
    final void refresh() {
        if (inFlight) {
            return;
        }
        inFlight = true;
        if (!loaded) {
            box.removeAllViews();
            TextView t = Ui.text(a, "در حال دریافت…", 13f, Ui.FAINT, false);
            t.setPadding(0, Ui.dp(a, 40), 0, 0);
            t.setGravity(android.view.Gravity.CENTER);
            box.addView(t, Ui.fill());
        }
        fetch();
    }

    /** Subclasses call this with the rendering step once their data has arrived. */
    final void done(Runnable render) {
        inFlight = false;
        loaded = true;
        box.removeAllViews();
        render.run();
    }

    final void failed(String what, String message) {
        inFlight = false;
        box.removeAllViews();
        box.addView(a.errorCard(what, message));
        // The real fix for "gwctl is asleep" is always the same: ask Termux to run boot.
        if (!a.reachable) {
            box.addView(Ui.button(a, "بیدار کردن سرویس‌ها", Ui.AMBER, v -> a.wakeServer()),
                    Ui.margins(Ui.fill(), a, 0, 4, 0, 0));
        }
    }

    /** Whether this tab should auto-refresh every few seconds while visible. */
    boolean live() {
        return false;
    }

    abstract void fetch();

    // helpers shared by screens ---------------------------------------------------------
    void add(View v) {
        box.addView(v);
    }

    void section(String title) {
        box.addView(Ui.section(a, title));
    }
}
