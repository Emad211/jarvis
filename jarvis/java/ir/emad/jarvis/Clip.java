package ir.emad.jarvis;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
 * It is deliberately invisible: no animation, no task, transparent theme, and it finishes as soon
 * as the clipboard answers.
 *
 * Two things this file learned the hard way, both encoded below:
 *  - a read queued from {@code onCreate} runs *before* the window is focused, so the first attempt
 *    is allowed to come back empty — it is retried until the clipboard answers or the budget ends;
 *  - every attempt records what the system said (hasPrimaryClip/itemCount/MIME/length) into the
 *    token file, because "empty string" alone cannot tell "clipboard is empty" from "not allowed".
 */
public class Clip extends Activity {

    static final String TOKEN = "token";
    /** Optional: text to put on the clipboard before reading, so the read path can be tested. */
    static final String SEED = "seed";

    private static final String TAG = "JarvisClip";
    /** 40 ms × 50 ≈ 2 s: longer than any focus grant, shorter than the bridge's 2.5 s wait. */
    private static final int MAX_TRIES = 50;
    private static final long RETRY_MS = 40;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean focused;
    private boolean finished;
    private int tries;
    private long started;
    private String seed;

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
        started = System.currentTimeMillis();
        Intent it = getIntent();
        seed = it == null ? null : it.getStringExtra(SEED);
        if (seed != null) {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("jarvis", seed));
                }
            } catch (Exception e) {
                Log.w(TAG, "seed failed: " + e);
            }
        }
        // First attempt on the next frame; it may be too early (see the class comment), so it is
        // only an attempt, never the answer.
        handler.post(this::attempt);
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) {
            focused = true;
            attempt();
        }
    }

    private void attempt() {
        if (finished) {
            return;
        }
        Result r = read();
        boolean answered = r.hasClip && r.items > 0;
        if (answered || tries >= MAX_TRIES) {
            store(r);
            return;
        }
        tries++;
        handler.postDelayed(this::attempt, RETRY_MS);
    }

    private static final class Result {
        boolean hasClip;
        int items;
        String text = "";
        String mime = "";
        String error = "";
    }

    private Result read() {
        Result r = new Result();
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                r.error = "no_service";
                return r;
            }
            r.hasClip = cm.hasPrimaryClip();
            if (!r.hasClip) {
                return r;
            }
            ClipData d = cm.getPrimaryClip();
            if (d == null) {
                r.error = "null_clip";
                return r;
            }
            r.items = d.getItemCount();
            if (d.getDescription() != null) {
                String[] kinds = d.getDescription().filterMimeTypes("text/plain");
                r.mime = kinds != null && kinds.length > 0 ? "text" : "other";
            }
            if (r.items > 0) {
                CharSequence cs = d.getItemAt(0).coerceToText(this);
                r.text = cs == null ? "" : cs.toString();
            }
        } catch (Exception e) {
            r.error = e.getClass().getSimpleName();
            Log.w(TAG, "clipboard read failed: " + e);
        }
        return r;
    }

    private void store(Result r) {
        finished = true;
        String token = getIntent() == null ? "" : getIntent().getStringExtra(TOKEN);
        try {
            // write the text first, the token last: the reader only trusts a token that matches
            // a completed write, so it can never pick up a half-written clipboard
            try (FileOutputStream out = new FileOutputStream(outFile(this))) {
                out.write(r.text.getBytes(StandardCharsets.UTF_8));
            }
            String diag = "tries=" + tries
                    + "|ms=" + (System.currentTimeMillis() - started)
                    + "|focused=" + focused
                    + "|has=" + r.hasClip
                    + "|items=" + r.items
                    + "|len=" + r.text.length()
                    + "|seeded=" + (seed != null)
                    + "|mime=" + r.mime
                    + (r.error.isEmpty() ? "" : "|err=" + r.error);
            try (FileOutputStream tok = new FileOutputStream(tokenFile(this))) {
                tok.write((token + "|" + System.currentTimeMillis() + "|" + diag)
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "clipboard store failed: " + e);
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }
}
