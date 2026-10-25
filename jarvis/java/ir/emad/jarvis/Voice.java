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

    /** Play a rendered answer. `url` is the path jarvisd returned ("/audio/x.mp3"). */
    void speak(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String trimmed = text.length() > 1200 ? text.substring(0, 1200) : text;
        Api.post("/voice/out", Api.body("text", trimmed), 120000, res -> {
            String url = res.optString("url", "");
            if (url.isEmpty()) {
                a.toast("صدا ساخته نشد");
                return;
            }
            play(Api.base() + url);
        }, msg -> a.toast("صدا: " + msg));
    }

    private void play(String url) {
        stopPlayback();
        try {
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.setOnErrorListener((mp, what, extra) -> {
                a.toast("پخش نشد (" + what + ")");
                stopPlayback();
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            a.toast("پخش نشد: " + e.getClass().getSimpleName());
            stopPlayback();
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
