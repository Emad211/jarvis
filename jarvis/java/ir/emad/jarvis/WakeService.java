package ir.emad.jarvis;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Always-listening for the wake word, as a foreground service with a notification that says
 * exactly what is happening. Android 14+ allows long microphone use only this way, and the
 * visible notification is the point: if the phone is listening, Emad can see it, and turning it
 * off is one tap away in the shade.
 *
 * What is local and what is not
 * -----------------------------
 * Local: the microphone, the keyword spotting (`Wake.Engine`, Android System Intelligence's
 * on-device recogniser, English pack) and the decision that the phrase was said. Nothing is
 * uploaded while listening.
 * Remote: only the one command that follows the phrase. It is recorded to a 16 kHz WAV by
 * `Wake.Recorder` and transcribed by the daemon's existing `POST /voice/in`, because the
 * on-device packs have no Persian (measured) and that cloud path is the accurate one.
 *
 * The command then becomes a normal console turn, so it streams its answer and speaks it with
 * the same TTS the typed path uses.
 */
public final class WakeService extends Service implements Wake.Heard {

    private static final String TAG = Wake.TAG;
    private static final String CHANNEL = "wake";
    private static final int NOTIF_ID = 8801;
    private static final String PREFS = "jarvis";

    static final String ACTION_START = "ir.emad.jarvis.wake.START";
    static final String ACTION_STOP = "ir.emad.jarvis.wake.STOP";
    static final String ACTION_SPEAKING = "ir.emad.jarvis.wake.SPEAKING";
    static final String EXTRA_SPEAK_MS = "ms";

    private Wake.Engine engine;
    private Handler main;
    private SharedPreferences prefs;
    private String state = "starting";
    private String lastHeard = "";
    private long startedAt;
    private String probe = "";
    private long pauseUntil;
    private volatile boolean probeDone;
    private boolean wantListening;
    /** One wake at a time: a second phrase while a command is being captured is noise. */
    private boolean busy;

    // ------------------------------------------------------------------- public API --
    static void start(Context ctx) {
        Intent i = new Intent(ctx, WakeService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    static void stop(Context ctx) {
        ctx.startService(new Intent(ctx, WakeService.class).setAction(ACTION_STOP));
    }

    static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("wake", false);
    }

    /** Called by the console when it starts speaking, so the answer cannot wake us again. */
    static void speaking(Context ctx, long ms) {
        Intent i = new Intent(ctx, WakeService.class).setAction(ACTION_SPEAKING);
        i.putExtra(EXTRA_SPEAK_MS, ms);
        try {
            ctx.startService(i);
        } catch (Throwable ignored) {
            // service not running: nothing is listening, so nothing to pause
        }
    }

    // --------------------------------------------------------------------- lifecycle --
    @Override
    public void onCreate() {
        super.onCreate();
        main = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        startedAt = System.currentTimeMillis();
        channel();
        startForeground(NOTIF_ID, notification("گوش می‌دهم: «" + Wake.PHRASE + "»",
                "تشخیص روی همین گوشی، بدون اینترنت", false));
        // The probe has to run on the main thread (SpeechRecognizer rules), so it is chained
        // there and the engine only starts once we know what the phone can actually do.
        Wake.probeAsync(this, report -> {
            probe = report;
            probeDone = true;
            Log.i(TAG, "probe " + probe);
            if (wantListening) {
                startEngine();
            }
            push(prefs.getBoolean("wake", false), state);
        });
    }

    private void startEngine() {
        if (engine != null) {
            return;
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            state = "error:no-ondevice-stt";
            updateNotification();
            push(true, "تشخیص روی گوشی نیست");
            return;
        }
        engine = new Wake.Engine(this, this);
        engine.start();
        push(true, state);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs.edit().putBoolean("wake", false).apply();
            push(false, "خاموش شد");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SPEAKING.equals(action)) {
            pauseFor(intent.getLongExtra(EXTRA_SPEAK_MS, 4000));
            return START_STICKY;
        }
        // A sticky restart arrives with a null intent. That must NOT silently switch the mic
        // back on: the user's saved choice decides (measured 2026-10-27: after stopping the
        // service, a system restart came back listening and the panel correctly flagged
        // in_sync:false because nothing had asked for it).
        boolean wanted = intent != null || prefs.getBoolean("wake", false);
        if (!wanted) {
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean("wake", true).apply();
        wantListening = true;
        if (probeDone) {
            startEngine();
        }
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        push(false, "متوقف شد");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------------ engine --
    @Override
    public void onWake(String command, String full) {
        if (busy) {
            return;
        }
        busy = true;
        Log.i(TAG, "WAKE full=" + full);
        lastHeard = full;
        state = "wake";
        push(true, "شنیدم: " + full);
        vibrate();
        beep();
        // Always record the command: the always-on recogniser is English, so anything it heard
        // after the phrase is not a usable Persian transcript.
        recordCommand();
    }

    @Override
    public void onState(String newState) {
        state = newState;
        if ("listening".equals(newState) || "idle".equals(newState)) {
            updateNotification();
        }
        Log.i(TAG, "state " + newState + " uptime=" + (System.currentTimeMillis() - startedAt) / 1000
                + "s");
    }

    @Override
    public void onOther(String text) {
        Log.d(TAG, "heard-other " + text);
    }

    // ------------------------------------------------------------------ command path --
    private void recordCommand() {
        state = "command";
        updateNotification();
        if (engine != null) {
            engine.setPaused(true);
        }
        main.postDelayed(() -> Wake.Recorder.record(8000, (wav, ms) -> {
            if (wav == null) {
                failCommand("چیزی نشنیدم");
            } else {
                transcribe(wav, ms);
            }
        }), 800);   // after the beeps and after the recogniser let go of the microphone
    }

    /** Hand the recorded command to the daemon's STT (`POST /voice/in`). */
    private void transcribe(final byte[] wav, final int ms) {
        state = "transcribing";
        updateNotification();
        new Thread(() -> {
            String text = "";
            String why = "";
            HttpURLConnection c = null;
            long t0 = System.currentTimeMillis();
            try {
                c = (HttpURLConnection) new URL(
                        MainActivity.daemonBase(this) + "/voice/in").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(3000);
                c.setReadTimeout(30000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "audio/wav");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(wav);
                }
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String body = read(in);
                if (code >= 400) {
                    why = "HTTP " + code;
                } else {
                    JSONObject o = new JSONObject(body);
                    text = o.optString("text", "").trim();
                }
            } catch (Exception e) {
                why = e.getClass().getSimpleName();
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            long elapsed = System.currentTimeMillis() - t0;
            Log.i(TAG, "STT ms=" + elapsed + " wavms=" + ms + " text=" + text + " err=" + why);
            final String heard = text;
            final String err = why;
            main.post(() -> {
                String low = heard.toLowerCase();
                boolean noise = heard.length() < 2 || heard.startsWith("<")
                        || low.contains("<noise>") || low.contains("[noise]")
                        || low.contains("<unk>") || low.equals("...");
                if (noise) {
                    failCommand(err.isEmpty() ? "متنی نشنیدم" : err);
                } else {
                    deliver(heard);
                }
            });
        }, "wake-stt").start();
    }

    private void failCommand(String why) {
        busy = false;
        state = "idle";
        Log.i(TAG, "command failed: " + why);
        updateNotification();
        push(true, "no-command: " + why);
        if (engine != null) {
            engine.setPaused(false);
        }
    }

    /** Hand one command to the console exactly as if it had been typed. */
    private void deliver(String command) {
        lastHeard = command;
        state = "delivered";
        Log.i(TAG, "DELIVER " + command);
        push(true, command);
        Intent go = new Intent(this, MainActivity.class);
        go.setAction("ir.emad.jarvis.WAKE_TURN");
        go.putExtra("wake_text", command);
        go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
        try {
            startActivity(go);
        } catch (Throwable t) {
            Log.w(TAG, "cannot open console: " + t);
        }
        pauseFor(25000);
    }

    private void pauseFor(long ms) {
        pauseUntil = System.currentTimeMillis() + ms;
        if (engine != null) {
            engine.setPaused(true);
        }
        updateNotification();
        main.postDelayed(() -> {
            if (System.currentTimeMillis() >= pauseUntil && engine != null) {
                busy = false;
                engine.setPaused(false);
                updateNotification();
            }
        }, ms + 50);
    }

    private void vibrate() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(60);
            }
        } catch (Throwable ignored) {
            // no vibrator, no problem
        }
    }

    /** Two short tones so the user knows when to speak the command. */
    private void beep() {
        try {
            ToneGenerator t = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
            main.postDelayed(() -> {
                try {
                    t.startTone(ToneGenerator.TONE_PROP_BEEP, 90);
                } catch (Throwable ignored) {
                    // tone failed, the vibration already told the user
                }
            }, 140);
            main.postDelayed(t::release, 700);
        } catch (Throwable ignored) {
            // no tone, fine
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // -------------------------------------------------------------------- notification --
    private void channel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        NotificationChannel c = new NotificationChannel(CHANNEL, "Wake word",
                NotificationManager.IMPORTANCE_LOW);
        c.setDescription("میکروفون برای تشخیص «" + Wake.PHRASE + "» روی خود گوشی");
        c.setShowBadge(false);
        nm.createNotificationChannel(c);
    }

    private void updateNotification() {
        boolean paused = System.currentTimeMillis() < pauseUntil;
        String title;
        String sub;
        if (paused) {
            title = "مکث (در حال پاسخ)";
            sub = "بعد از پاسخ دوباره گوش می‌دهم";
        } else if ("command".equals(state) || "transcribing".equals(state)) {
            title = "دستور را بگو…";
            sub = "یک جمله، بعد از «" + Wake.PHRASE + "»";
        } else {
            title = "گوش می‌دهم: «" + Wake.PHRASE + "»";
            sub = "تشخیص روی همین گوشی، بدون اینترنت";
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIF_ID, notification(title, sub, true));
        }
    }

    private Notification notification(String title, String sub, boolean withStop) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(ir.emad.jarvis.R.drawable.ic_launcher)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .setShowWhen(false);
        if (withStop) {
            Intent stop = new Intent(this, WakeService.class).setAction(ACTION_STOP);
            PendingIntent sp = PendingIntent.getService(this, 1, stop,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, "خاموش کن", sp).build());
        }
        return b.build();
    }

    // ------------------------------------------------------------------- daemon state --
    /** Tell jarvisd what this service is doing; the panel renders it, never guesses. */
    private void push(final boolean enabled, final String heard) {
        final JSONObject body = new JSONObject();
        try {
            body.put("enabled", enabled);
            body.put("listening", enabled && engine != null
                    && System.currentTimeMillis() >= pauseUntil);
            body.put("phrase", Wake.PHRASE);
            body.put("engine", probe.contains("onDevice=true")
                    ? "ondevice-aiai/" + Wake.lang : "unavailable");
            body.put("probe", probe);
            body.put("state", state);
            body.put("last_heard", heard == null ? "" : heard);
            body.put("app", "ir.emad.jarvis");
            body.put("uptime_s", (System.currentTimeMillis() - startedAt) / 1000);
        } catch (Exception ignored) {
            return;
        }
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(
                        MainActivity.daemonBase(this) + "/voice/wake").openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(2500);
                c.setReadTimeout(4000);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                if (code >= 400) {
                    Log.w(TAG, "wake state refused: HTTP " + code);
                }
            } catch (Exception e) {
                Log.w(TAG, "wake state not sent: " + e);
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
        }, "wake-state").start();
    }
}
