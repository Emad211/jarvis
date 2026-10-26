package ir.emad.jarvis;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The phone's hands, without Shizuku and without an adb pairing.
 *
 * Every fast-path action used to go through `adb shell` (92 ms, needs wireless debugging paired)
 * or Shizuku's `rish` (3 s, needs the app plus a manual start). Both are install steps a stranger
 * cannot be walked through. This service is part of our own app, so the user grants one system
 * permission once and the phone is drivable — no USB, no pairing, no second app.
 *
 * What it can do without any extra permission: read the screen tree, tap and swipe (gesture
 * dispatch), global actions (home/back/recents/notifications), type into the focused field, take
 * a screenshot (API 30+), clipboard, open apps and URLs, dial, and read battery/device info.
 * What still needs one more toggle: changing the volume key-by-key is fine through AudioManager,
 * but writing system brightness needs WRITE_SETTINGS.
 *
 * The service is dumb on purpose: it holds no policy and no state. {@link Bridge} decides what to
 * ask it and answers over loopback, exactly like the old shell lane did.
 */
public class A11y extends AccessibilityService {

    /** Set while the user has the service enabled; null means "no hands". */
    private static volatile A11y live;

    static A11y get() {
        return live;
    }

    static boolean ready() {
        return live != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        live = this;
    }

    @Override
    public void onDestroy() {
        live = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // no reactive behaviour: everything is pull-based from Bridge
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }

    // ------------------------------------------------------------------ the hands --

    /** Tap at absolute screen pixels. Waits for the gesture to be accepted. */
    boolean tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.Builder b = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
        return dispatchGesture(b.build(), null, null);
    }

    /** Swipe from (x1,y1) to (x2,y2) over {@code ms} milliseconds. */
    boolean swipe(float x1, float y1, float x2, float y2, long ms) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.Builder b = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(60, ms)));
        return dispatchGesture(b.build(), null, null);
    }

    /** home | back | recents | notifications | quicksettings | lock (API 28+). */
    boolean global(String action) {
        int code;
        switch (action == null ? "" : action) {
            case "home":
                code = GLOBAL_ACTION_HOME;
                break;
            case "back":
                code = GLOBAL_ACTION_BACK;
                break;
            case "recents":
                code = GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                code = GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quicksettings":
                code = GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "lock":
                if (Build.VERSION.SDK_INT < 28) {
                    return false;
                }
                code = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default:
                return false;
        }
        return performGlobalAction(code);
    }

    /** Type into whatever field has focus, replacing its text. */
    boolean type(String text) {
        AccessibilityNodeInfo node = focused();
        if (node == null) {
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok;
    }

    /** The focused editable node, or the first editable node on screen as a fallback. */
    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus != null) {
            root.recycle();
            return focus;
        }
        List<AccessibilityNodeInfo> editables = root.findAccessibilityNodeInfosByViewId("");
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        collectEditable(root, found);
        root.recycle();
        if (found.isEmpty()) {
            return null;
        }
        AccessibilityNodeInfo first = found.get(0);
        for (AccessibilityNodeInfo n : found) {
            if (n != first) {
                n.recycle();
            }
        }
        if (editables != null) {
            editables.clear();
        }
        return first;
    }

    private void collectEditable(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) {
            return;
        }
        if (node.isEditable()) {
            out.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectEditable(child, out);
                child.recycle();
            }
        }
    }

    /**
     * A flat dump of what is on screen — the replacement for `uiautomator dump`, which needs a
     * shell. Each line is `text @ x,y` in screen pixels, which is exactly what a caller needs
     * to decide where to tap next.
     */
    String screen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        walk(root, sb, 0);
        root.recycle();
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 40) {
            return;
        }
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        CharSequence label = (text != null && text.length() > 0) ? text : desc;
        if (label != null && label.length() > 0) {
            android.graphics.Rect r = new android.graphics.Rect();
            node.getBoundsInScreen(r);
            sb.append(label.toString().replace('\n', ' ')).append(" @ ")
                    .append(r.centerX()).append(',').append(r.centerY());
            if (node.isClickable()) {
                sb.append(" *");
            }
            sb.append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                walk(child, sb, depth + 1);
                child.recycle();
            }
        }
    }

    /**
     * Screenshot to a PNG under the app's cache dir; returns the absolute path.
     * API 30+ only: before that Android gives an accessibility service no pixels at all.
     */
    String screenshot(String name) {
        if (Build.VERSION.SDK_INT < 30) {
            return "";
        }
        final Bitmap[] box = new Bitmap[1];
        final Object lock = new Object();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override
            public void onSuccess(ScreenshotResult result) {
                box[0] = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            @Override
            public void onFailure(int errorCode) {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(2500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (box[0] == null) {
            return "";
        }
        try {
            File dir = new File(getCacheDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) {
                return "";
            }
            File out = new File(dir, name == null || name.isEmpty() ? "shot.png" : name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                box[0].compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        } finally {
            box[0].recycle();
        }
    }

    // ---------------------------------------------------------------- simple ones --

    boolean openApp(String pkg) {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        return true;
    }

    boolean openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    String clipboardGet() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || cm.getPrimaryClip() == null || cm.getPrimaryClip().getItemCount() == 0) {
            return "";
        }
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        return t == null ? "" : t.toString();
    }

    boolean clipboardSet(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return false;
        }
        cm.setPrimaryClip(ClipData.newPlainText("jarvis", text));
        return true;
    }

    /** up | down | mute, one step. */
    boolean volume(String direction) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return false;
        }
        int dir = "up".equals(direction) ? AudioManager.ADJUST_RAISE
                : "down".equals(direction) ? AudioManager.ADJUST_LOWER
                : AudioManager.ADJUST_TOGGLE_MUTE;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
        return true;
    }
}
