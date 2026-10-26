package ir.emad.jarvis;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The clipboard's one legal loophole.
 *
 * Since Android 10 only the **focused** app (or the default keyboard) may read the clipboard, and
 * a background service never is that app — measured here, both the accessibility lane and
 * termux-clipboard-get come back empty. A translucent activity that briefly takes window focus is
 * exempt, so this activity reads the clipboard, stores the text where {@link Bridge} can pick it
 * up, and finishes immediately.
 *
 * It is deliberately invisible: no animation, no task, transparent theme, and it finishes in the
 * same frame it was created. The user sees nothing (or at most one 60 ms flicker on a slow phone).
 */
public class Clip extends Activity {

    static final String TOKEN = "token";
    private static final String TAG = "JarvisClip";

    /** Where the last read is parked, in the app's own private storage. */
    static File outFile(Context ctx) {
        return new File(ctx.getFilesDir(), "clipboard.txt");
    }

    private static File tokenFile(Context ctx) {
        return new File(ctx.getFilesDir(), "clipboard.token");
    }

    /** Package-visible for Bridge, which polls it to know the read finished. */
    static File token(Context ctx) {
        return tokenFile(ctx);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // The clipboard can only be read once this window holds focus, and in onCreate it may
        // not yet. Read after the first traversal instead of guessing that it does.
        getWindow().getDecorView().post(this::readAndFinish);
    }

    @Override
    public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            readAndFinish();
        }
    }

    private void readAndFinish() {
        if (done) {
            return;
        }
        done = true;
        String token = getIntent() == null ? "" : getIntent().getStringExtra(TOKEN);
        String text = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null
                    && cm.getPrimaryClip().getItemCount() > 0) {
                CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
                text = cs == null ? "" : cs.toString();
            }
        } catch (Exception e) {
            Log.w(TAG, "clipboard read failed: " + e);
        }
        try {
            // write the text first, the token last: the reader only trusts a token that matches
            // a completed write, so it can never pick up a half-written clipboard
            try (FileOutputStream out = new FileOutputStream(outFile(this))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            try (FileOutputStream tok = new FileOutputStream(tokenFile(this))) {
                tok.write((token + ":" + System.currentTimeMillis())
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "clipboard store failed: " + e);
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private boolean done;
}
