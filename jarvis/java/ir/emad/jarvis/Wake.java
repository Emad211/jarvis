package ir.emad.jarvis;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The wake word: everything that decides "did the user just say «هی جارویس»", plus the probe
 * that says which engines this handset actually has, plus the recorder that captures the one
 * command that follows.
 *
 * Why on-device recognition and not cloud streaming
 * ------------------------------------------------
 * A microphone held open against a network recogniser is the expensive way to listen: every
 * second of room noise becomes an upload and a decode. This class only ever uses
 * `createOnDeviceSpeechRecognizer()` (Android System Intelligence, `com.google.android.as`),
 * which runs the acoustic model on the phone and sends nothing anywhere.
 *
 * Why the wake phrase is matched in English
 * -----------------------------------------
 * Measured on this handset (SM-A536E, Android 16): AiAi's on-device packs have **no Persian**
 * (`on[fa-IR]=err12`, LANGUAGE_NOT_SUPPORTED), so a Persian recogniser cannot be the always-on
 * part. They do have `en-US`, and «هی جارویس» is phonetically "hey Jarvis" — so the engine
 * listens in English, matches the stem "jarvis", and the Persian command that follows is
 * recorded as raw PCM and transcribed by the cloud STT already in use (1.2-1.8 s measured).
 * That split is also the cheapest one: the keyword spotting is local and free, and only the
 * utterance the user actually meant to say is uploaded.
 */
final class Wake {

    static final String TAG = "JarvisWake";

    /** The phrase as the user says it. Matched after normalisation. */
    static final String PHRASE = "هی جارویس";

    /**
     * Language of the always-on recogniser. Set from the probe: Android System Intelligence on
     * this phone has exactly ONE on-device pack installed (en-GB, matching the device locale),
     * and asking for any other language fails at runtime with `error 13
     * LANGUAGE_UNAVAILABLE`. So the engine follows whatever pack exists.
     */
    static volatile String lang = "en-GB";

    /** Prefer the offline acoustic model (default). Switchable so both paths can be measured. */
    static volatile boolean preferOffline = true;

    /** Stems that count as the wake word, in either script. "jarvis" is what an English model
     *  produces for «جارویس»; the Persian spellings cover a model that outputs them. */
    private static final String[] STEMS = {"jarvis", "jervis", "javis", "jarvus",
            "جارویس", "جارویز", "جاروس", "جرویس"};

    interface Heard {
        /** `command` is what followed the phrase, possibly empty. */
        void onWake(String command, String full);

        /** Engine state changed: idle | listening | error:… */
        void onState(String state);

        /** A finished utterance that did NOT contain the phrase (telemetry only). */
        void onOther(String text);
    }

    private Wake() {
    }

    // ------------------------------------------------------------------ matching --
    /** Persian/English normalisation: fold Arabic yeh/kaf, drop ZWNJ, diacritics, punctuation. */
    static String norm(String s) {
        if (s == null) {
            return "";
        }
        String t = Normalizer.normalize(s, Normalizer.Form.NFKC);
        t = t.replace('\u064a', '\u06cc')       // ي -> ی
                .replace('\u0643', '\u06a9')    // ك -> ک
                .replace('\u200c', ' ')         // ZWNJ -> space
                .replace('\u200d', ' ')
                .replace('\u200e', ' ')
                .replace('\u200f', ' ');
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u064b' || c == '\u064c' || c == '\u064d' || c == '\u064e'
                    || c == '\u064f' || c == '\u0650' || c == '\u0651' || c == '\u0652') {
                continue;                        // harakat
            }
            if (Character.isLetterOrDigit(c) || c == ' ') {
                b.append(Character.toLowerCase(c));
            } else {
                b.append(' ');
            }
        }
        return b.toString().replaceAll("\\s+", " ").trim();
    }

    /** True when the utterance contains the wake stem. */
    static boolean isWake(String text) {
        String compact = norm(text).replace(" ", "");
        for (String stem : STEMS) {
            if (compact.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /** Everything the user said after the phrase, original casing (may be empty). */
    static String commandAfter(String text) {
        String n = norm(text);
        String compact = n.replace(" ", "");
        int hit = -1;
        int end = -1;
        for (String stem : STEMS) {
            int at = compact.indexOf(stem);
            if (at >= 0 && (hit < 0 || at < hit)) {
                hit = at;
                end = at + stem.length();
            }
        }
        if (hit < 0) {
            return "";
        }
        // Walk the original words with the same letter count, so digits and case survive.
        String[] words = text.trim().split("\\s+");
        int used = 0;
        for (int i = 0; i < words.length; i++) {
            used += norm(words[i]).replace(" ", "").length();
            if (used >= end) {
                StringBuilder out = new StringBuilder();
                for (int j = i + 1; j < words.length; j++) {
                    if (out.length() > 0) {
                        out.append(' ');
                    }
                    out.append(words[j]);
                }
                return out.toString().trim();
            }
        }
        return "";
    }

    // --------------------------------------------------------------------- probe --
    interface Probed {
        void on(String report);
    }

    /**
     * What this handset can do, in one string.
     *
     * Everything here hops on the main thread: `SpeechRecognizer` may only be created there,
     * and its support callback is delivered there too. The first version awaited that callback
     * with a latch on the main thread and deadlocked (every query answered "timeout"); the
     * second called it from a worker and every `create` threw. So: main thread, chained
     * callbacks, one recogniser alive at a time, and a watchdog per step.
     */
    static void probeAsync(Context ctx, Probed done) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            StringBuilder b = new StringBuilder();
            b.append("sdk=").append(Build.VERSION.SDK_INT).append(" model=").append(Build.MODEL);
            try {
                b.append(" onDevice=")
                        .append(SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx));
            } catch (Throwable t) {
                b.append(" onDevice=err:").append(t.getClass().getSimpleName());
            }
            b.append(" services=").append(services(ctx));
            String[][] steps = {{"on", "fa-IR"}, {"on", lang}, {"net", "fa-IR"}, {"net", lang}};
            step(ctx, steps, 0, b, done);
        });
    }

    private static String services(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            StringBuilder s = new StringBuilder();
            for (ResolveInfo r : pm.queryIntentServices(
                    new Intent("android.speech.RecognitionService"), 0)) {
                s.append(r.serviceInfo.packageName).append('/')
                        .append(r.serviceInfo.name).append(' ');
            }
            return s.toString().trim();
        } catch (Throwable t) {
            return "err";
        }
    }

    /** First installed on-device language, from a report like `…on[fa-IR]=ok,inst=[en-GB],…`. */
    static String installedLanguage(String report) {
        int at = report.indexOf("inst=[");
        if (at < 0) {
            return "";
        }
        int end = report.indexOf(']', at);
        if (end < 0) {
            return "";
        }
        String list = report.substring(at + 6, end).trim();
        if (list.isEmpty() || list.startsWith("err")) {
            return "";
        }
        return list.split(",")[0].trim();
    }

    private static void step(Context ctx, String[][] steps, int i, StringBuilder b, Probed done) {
        if (i >= steps.length) {
            String picked = installedLanguage(b.toString());
            if (!picked.isEmpty()) {
                lang = picked;             // follow the pack that is actually on this phone
            }
            done.on(b.toString());
            return;
        }
        final boolean onDevice = "on".equals(steps[i][0]);
        final String lang = steps[i][1];
        final boolean[] finished = {false};
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        main.postDelayed(() -> {
            if (!finished[0]) {
                finished[0] = true;
                b.append(' ').append(onDevice ? "on" : "net").append('[').append(lang)
                        .append("]=timeout");
                step(ctx, steps, i + 1, b, done);
            }
        }, 3500);
        query(ctx, onDevice, lang, result -> {
            if (finished[0]) {
                return;
            }
            finished[0] = true;
            b.append(' ').append(onDevice ? "on" : "net").append('[').append(lang).append("]=")
                    .append(result);
            step(ctx, steps, i + 1, b, done);
        });
    }

    /**
     * "ok inst=[…] pend=[…]" / "err12" / "throw:…": does this recogniser support `lang`, and is
     * the on-device pack installed (installed = usable offline right now, which is the only
     * mode the always-on part is allowed to use).
     */
    private static void query(Context ctx, boolean onDevice, String lang, Probed cb) {
        if (Build.VERSION.SDK_INT < 33) {
            cb.on("api<33");
            return;
        }
        SpeechRecognizer r = null;
        try {
            r = onDevice ? SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
                    : SpeechRecognizer.createSpeechRecognizer(ctx);
            if (r == null) {
                cb.on("none");
                return;
            }
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            final SpeechRecognizer rec = r;
            r.checkRecognitionSupport(i, ctx.getMainExecutor(), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport s) {
                    String out = "ok inst=" + s.getInstalledOnDeviceLanguages()
                            + " pend=" + s.getPendingOnDeviceLanguages();
                    try {
                        rec.destroy();
                    } catch (Exception ignored) {
                        // fine
                    }
                    cb.on(out.replace(' ', ','));
                }

                @Override
                public void onError(int error) {
                    try {
                        rec.destroy();
                    } catch (Exception ignored) {
                        // fine
                    }
                    cb.on("err" + error);
                }
            });
        } catch (Throwable t) {
            if (r != null) {
                try {
                    r.destroy();
                } catch (Exception ignored) {
                    // fine
                }
            }
            cb.on("throw:" + t.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------------- engine --
    /**
     * A restarting on-device recogniser. Sessions end on silence; the engine opens a new one
     * after a short delay, and backs off when the platform keeps erroring out so a broken
     * recogniser can never spin.
     */
    static final class Engine implements RecognitionListener {

        private final Context ctx;
        private final Heard heard;
        private final android.os.Handler main;
        private SpeechRecognizer rec;
        private boolean running;
        private boolean paused;
        private long backoff = 250;
        private long startedAt;
        /** False between a fired phrase and the next startListening: partials and results for
         *  the same utterance keep arriving and must not fire a second command. */
        private boolean armed;
        /** True while we are cancelling on purpose, so the resulting ERROR_CLIENT is not a fault. */
        private boolean cancelling;

        Engine(Context ctx, Heard heard) {
            this.ctx = ctx.getApplicationContext();
            this.heard = heard;
            this.main = new android.os.Handler(android.os.Looper.getMainLooper());
        }

        boolean onDevice() {
            try {
                return SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
            } catch (Throwable t) {
                return false;
            }
        }

        void start() {
            if (running) {
                return;
            }
            running = true;
            begin();
        }

        void stop() {
            running = false;
            SpeechRecognizer r = rec;
            rec = null;
            if (r != null) {
                try {
                    r.cancel();
                    r.destroy();
                } catch (Exception ignored) {
                    // fine
                }
            }
        }

        /** Pause while Jarvis talks, so the answer does not wake us up again. The recogniser
         *  itself is kept: recreating it is what broke listening after a minute (measured). */
        void setPaused(boolean value) {
            paused = value;
            if (paused) {
                if (rec != null) {
                    try {
                        rec.cancel();
                    } catch (Exception ignored) {
                        // fine
                    }
                }
            } else if (running) {
                begin();
            }
        }

        private Intent intent() {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            // Long sessions = less restart churn = less battery. The command recorder uses its
            // own short silence timeout.
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    8000L);
            if (Build.VERSION.SDK_INT >= 23 && preferOffline) {
                i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            }
            return i;
        }

        private void begin() {
            if (!running || paused) {
                return;
            }
            // ONE recogniser for the life of the service. Measured: destroying and recreating it
            // for every short session works for the first minute and then stops delivering any
            // results at all — the service keeps cycling "listening/idle" while producing
            // nothing, even with the wake phrase played at full volume two metres away.
            if (rec == null) {
                try {
                    rec = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx);
                } catch (Throwable t) {
                    heard.onState("error:create:" + t.getClass().getSimpleName());
                    main.postDelayed(this::begin, backoff);
                    return;
                }
                if (rec == null) {
                    heard.onState("error:no-ondevice-stt");
                    return;
                }
                rec.setRecognitionListener(this);
            }
            startedAt = System.currentTimeMillis();
            armed = true;
            cancelling = false;
            try {
                rec.startListening(intent());
                heard.onState("listening");
            } catch (Throwable t) {
                // ERROR_RECOGNIZER_BUSY after an abrupt stop: cancel and try once more.
                heard.onState("error:start:" + t.getClass().getSimpleName());
                try {
                    rec.cancel();
                } catch (Exception ignored) {
                    // fine
                }
                main.postDelayed(() -> {
                    try {
                        if (rec != null) {
                            rec.startListening(intent());
                            heard.onState("listening");
                        }
                    } catch (Throwable t2) {
                        heard.onState("error:restart:" + t2.getClass().getSimpleName());
                        recycle(backoff);
                    }
                }, 350);
            }
        }

        /** Throw the recogniser away and build a fresh one (after repeated platform errors). */
        private void recycle(long delayMs) {
            SpeechRecognizer r = rec;
            rec = null;
            if (r != null) {
                try {
                    r.cancel();
                    r.destroy();
                } catch (Exception ignored) {
                    // fine
                }
            }
            if (!running || paused) {
                return;
            }
            main.postDelayed(this::begin, Math.max(200, delayMs));
        }

        private void restartSoon(long delayMs) {
            if (!running || paused) {
                return;
            }
            main.postDelayed(this::begin, Math.max(120, delayMs));
        }

        /** Stop the session and hand the phrase up. */
        private void fire(String text) {
            armed = false;
            cancelling = true;
            if (rec != null) {
                try {
                    rec.cancel();
                } catch (Exception ignored) {
                    // fine
                }
            }
            heard.onWake(commandAfter(text), text);
        }

        // ---- RecognitionListener ----
        @Override
        public void onReadyForSpeech(Bundle params) {
            backoff = 250;
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onPartialResults(Bundle partial) {
            if (!armed) {
                return;
            }
            ArrayList<String> list = partial == null ? null
                    : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) {
                return;
            }
            Log.i(TAG, "partial " + list);
            // Partials are what make the wake word feel instant: act on the first hypothesis
            // that contains the phrase instead of waiting for end-of-speech.
            for (String s : list) {
                if (isWake(s)) {
                    fire(s);
                    return;
                }
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (!armed) {
                return;
            }
            ArrayList<String> list = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            Log.i(TAG, "results " + list);
            boolean woke = false;
            if (list != null) {
                for (String s : list) {
                    if (isWake(s)) {
                        heard.onWake(commandAfter(s), s);
                        woke = true;
                        break;
                    }
                }
                if (!woke && !list.isEmpty()) {
                    heard.onOther(list.get(0));
                }
            }
            backoff = 250;
            restartSoon(120);
        }

        @Override
        public void onError(int error) {
            if (cancelling) {
                cancelling = false;          // our own cancel, not a fault
                return;
            }
            if (!armed) {
                return;
            }
            if (error == SpeechRecognizer.ERROR_NO_MATCH
                    || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                // Normal in a quiet room. Restart at once, but not in a tight loop: a session
                // that lasted under a second earns a small delay.
                long age = System.currentTimeMillis() - startedAt;
                heard.onState("idle");
                restartSoon(age < 700 ? 400 : 120);
                return;
            }
            backoff = Math.min(8000, Math.max(400, backoff * 2));
            heard.onState("error:" + error);
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                    || error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                    || error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
                recycle(backoff);          // the instance itself is suspect
            } else {
                restartSoon(backoff);
            }
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }

    // ------------------------------------------------------------------ recorder --
    /**
     * Records the one sentence after the wake word as 16 kHz mono PCM and hands back a WAV.
     *
     * The always-on recogniser is English, so it cannot transcribe Persian; raw samples are what
     * the daemon's cloud STT wants anyway (`POST /voice/in`). Recording stops on 900 ms of
     * silence after speech, or at the hard cap.
     */
    static final class Recorder {

        interface Done {
            /** `wav` is a complete 44-byte-header WAV, or null when nothing usable was said. */
            void on(byte[] wav, int ms);
        }

        static final int RATE = 16000;
        private static final int FRAME = 320;              // 20 ms
        private static final int SILENCE_MS = 1000;        // after speech
        private static final int MIN_MS = 400;
        private static final int MAX_MS = 8000;
        /** int16 RMS that counts as speech. Measured on this phone: a quiet room sits under
         *  150, a spoken sentence 1500-6000. */
        private static final int SPEECH_RMS = 500;
        /** Loud frames in a row before we believe it is speech. The two wake beeps are short;
         *  counting them as speech ended the recording before the user said a word (measured:
         *  a 3.4 s clip of beep + room noise came back from STT as "What is my name?"). */
        private static final int ONSET_FRAMES = 3;

        private Recorder() {
        }

        /** Runs the capture on its own thread; `done` is called back after the mic is released. */
        static void record(final int maxMs, final Done done) {
            new Thread(() -> {
                AudioRecord rec = null;
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                int total = 0;
                boolean speech = false;
                byte[] result = null;
                try {
                    int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    int bufSize = Math.max(min, FRAME * 4);
                    // MIC, not VOICE_RECOGNITION: the latter runs echo cancellation, which
                    // cancels exactly the audio the phone is playing (measured: the command
                    // recorded as pure silence whenever the reply was still audible).
                    rec = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            bufSize * 2);
                    if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                        rec.release();
                        rec = null;
                        done.on(null, 0);
                        return;
                    }
                    rec.startRecording();
                    short[] buf = new short[FRAME];
                    int quietRun = 0;
                    int loudRun = 0;
                    int cap = maxMs <= 0 ? MAX_MS : Math.min(maxMs, MAX_MS);
                    while (total < cap) {
                        int n = rec.read(buf, 0, buf.length);
                        if (n <= 0) {
                            break;
                        }
                        total += (n * 1000) / RATE;
                        long sum = 0;
                        for (int i = 0; i < n; i++) {
                            sum += (long) buf[i] * buf[i];
                        }
                        double rms = Math.sqrt(sum / (double) Math.max(1, n));
                        if (rms > SPEECH_RMS) {
                            loudRun++;
                            if (loudRun >= ONSET_FRAMES) {
                                speech = true;
                            }
                            quietRun = 0;
                        } else {
                            loudRun = 0;
                            if (speech) {
                                quietRun += (n * 1000) / RATE;
                            }
                        }
                        byte[] bytes = new byte[n * 2];
                        for (int i = 0; i < n; i++) {
                            bytes[i * 2] = (byte) (buf[i] & 0xff);
                            bytes[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xff);
                        }
                        pcm.write(bytes, 0, bytes.length);
                        if (speech && quietRun >= SILENCE_MS) {
                            break;
                        }
                    }
                    byte[] raw = pcm.toByteArray();
                    if (speech && total >= MIN_MS && raw.length > 0) {
                        result = wav(raw);
                    }
                } catch (Throwable t) {
                    result = null;
                } finally {
                    if (rec != null) {
                        try {
                            rec.stop();
                        } catch (Exception ignored) {
                            // not started
                        }
                        try {
                            rec.release();
                        } catch (Exception ignored) {
                            // already released
                        }
                    }
                }
                // Call back only once the microphone is really free: the recogniser cannot be
                // recreated while a raw recorder still owns the input (measured RuntimeException).
                done.on(result, total);
            }, "wake-rec").start();
        }

        /** 44-byte RIFF header + PCM16LE payload, the shape `/voice/in` already accepts. */
        static byte[] wav(byte[] pcm) {
            ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
            b.put("RIFF".getBytes());
            b.putInt(36 + pcm.length);
            b.put("WAVEfmt ".getBytes());
            b.putInt(16);
            b.putShort((short) 1);            // PCM
            b.putShort((short) 1);            // mono
            b.putInt(RATE);
            b.putInt(RATE * 2);               // byte rate
            b.putShort((short) 2);            // block align
            b.putShort((short) 16);           // bits per sample
            b.put("data".getBytes());
            b.putInt(pcm.length);
            b.put(pcm);
            return b.array();
        }
    }
}
