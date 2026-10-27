package ir.emad.jarvis;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Microphone capture, speech-to-text and speech playback.
 *
 * Audio never leaves the phone's loopback: the app records 16 kHz mono PCM, wraps it in a
 * WAV header and hands it to jarvisd, which owns the Google key. TTS comes back the same
 * way — jarvisd renders an mp3, the app streams it from http://127.0.0.1:8780/audio/…
 */
final class Voice {

    interface Text {
        void on(String text);
    }

    interface Error {
        void on(String message);
    }

    private static final int RATE = 16000;
    private final MainActivity a;
    private AudioRecord recorder;
    private Thread pump;
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
    private MediaPlayer player;
    /** Rendered sentence urls, keyed by sentence index; "" means that sentence failed. */
    private final java.util.Map<Integer, String> ready = new java.util.HashMap<>();
    private int nextToPlay;
    /** Bumped by every new utterance so late answers from an old one are ignored. */
    private int generation;
    /** When the free TTS quota is done, every model answers 429; stop hammering it. */
    private long ttsCooldownUntil;

    Voice(MainActivity a) {
        this.a = a;
    }

    boolean isRecording() {
        return recording.get();
    }

    boolean start() {
        if (recording.get()) {
            return true;
        }
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, RATE / 2);
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 2);
        } catch (Exception e) {
            return false;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            return false;
        }
        pcm.reset();
        recording.set(true);
        recorder.startRecording();
        pump = new Thread(() -> {
            byte[] buf = new byte[size];
            while (recording.get()) {
                int n = recorder.read(buf, 0, buf.length);
                if (n > 0) {
                    pcm.write(buf, 0, n);
                }
            }
        }, "mic");
        pump.setDaemon(true);
        pump.start();
        return true;
    }

    /** Stop recording and transcribe. Returns null when there was nothing to send. */
    void stop(Text ok, Error fail) {
        if (!recording.getAndSet(false)) {
            return;
        }
        try {
            recorder.stop();
        } catch (Exception ignored) {
            // already stopped
        }
        try {
            recorder.release();
        } catch (Exception ignored) {
            // already released
        }
        recorder = null;
        byte[] raw = pcm.toByteArray();
        pcm.reset();
        if (raw.length < RATE / 4) {  // under ~250 ms of audio: a mis-tap, not speech
            fail.on("خیلی کوتاه بود");
            return;
        }
        byte[] wav = wav(raw);
        a.toast("می‌فرستم برای تبدیل به متن…");
        Api.postBytes("/voice/in", wav, "audio/wav", 90000, res -> {
            String text = res.optString("text", "").trim();
            if (text.isEmpty()) {
                fail.on("متنی تشخیص داده نشد");
            } else {
                ok.on(text);
            }
        }, fail::on);
    }

    /**
     * Speak an answer sentence by sentence.
     *
     * Rendering the whole reply first meant the phone stayed silent for 5–9 s (measured), which
     * is exactly the "it is slow" feeling this app is supposed to kill. Each sentence is sent
     * as its own request and played as soon as it arrives, so the first words start ~1–2 s in
     * while the rest is still being rendered. A failed sentence is skipped, not fatal.
     */
    void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (System.currentTimeMillis() < ttsCooldownUntil) {
            return; // quota is gone: silence beats 2.4 s of failed requests on every answer
        }
        String trimmed = text.length() > 1200 ? text.substring(0, 1200) : text;
        java.util.List<String> chunks = chunk(trimmed);
        stopPlayback();
        final int gen = ++generation;
        ready.clear();
        nextToPlay = 0;
        for (int i = 0; i < chunks.size(); i++) {
            final int index = i;
            Api.post("/voice/out", Api.body("text", chunks.get(i)), 120000, res -> {
                if (gen != generation) {
                    return; // a newer utterance took over
                }
                ready.put(index, Api.base() + res.optString("url", ""));
                playIfNext();
            }, msg -> {
                if (gen != generation) {
                    return;
                }
                ready.put(index, ""); // fails: skip this sentence, keep the rest
                if (index == 0) {
                    // The very first sentence failing means the service, not the sentence:
                    // back off for ten minutes and say so once.
                    ttsCooldownUntil = System.currentTimeMillis() + 600000L;
                    a.toast("صدا فعلاً در دسترس نیست (سهمیه)");
                }
                playIfNext();
            });
        }
    }

    /** Plays sentence `nextToPlay` if it has arrived, else waits for its request. */
    private void playIfNext() {
        if (player != null) {
            return;
        }
        while (ready.containsKey(nextToPlay)) {
            String url = ready.remove(nextToPlay);
            nextToPlay++;
            if (url != null && !url.isEmpty()) {
                play(url);
                return;
            }
        }
    }

    /** Split on sentence ends and newlines; ~180 chars per request keeps latency low. */
    private static java.util.List<String> chunk(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            for (String part : line.split("(?<=[.!?؟۔؛:])\\s+")) {
                if (part.trim().isEmpty()) {
                    continue;
                }
                if (cur.length() > 0 && cur.length() + part.length() > 180) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                cur.append(part).append(' ');
            }
        }
        if (cur.toString().trim().length() > 0) {
            out.add(cur.toString().trim());
        }
        if (out.isEmpty()) {
            out.add(text.trim());
        }
        return out;
    }

    private void play(String url) {
        // Keep the wake engine deaf while we talk: the answer must never wake Jarvis again.
        WakeService.speaking(a, 12000);
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                stopPlayback();
                playIfNext();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                a.toast("پخش نشد (" + what + ")");
                stopPlayback();
                playIfNext();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            a.toast("پخش نشد: " + e.getClass().getSimpleName());
            stopPlayback();
            playIfNext();
        }
    }

    void stopPlayback() {
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignored) {
                // not started
            }
            try {
                player.release();
            } catch (Exception ignored) {
                // already released
            }
            player = null;
        }
    }

    boolean playing() {
        return player != null;
    }

    /** 44-byte RIFF header + PCM16LE payload. */
    private static byte[] wav(byte[] pcm) {
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
