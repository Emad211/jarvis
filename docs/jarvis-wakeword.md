# Wake word and always-listening — what this phone can actually do

Status: **step 1 shipped and measured** (2026-10-27). The phrase is detected on the phone,
offline, by a foreground service; the one command that follows is transcribed by the cloud STT
we already pay for, and becomes a normal console turn.

Files: `jarvis/java/ir/emad/jarvis/Wake.java` (probe, matcher, engine, recorder),
`jarvis/java/ir/emad/jarvis/WakeService.java` (foreground service, notification, state push),
`phone/.local/bin/jarvisd_voice.py` (`GET/POST /voice/wake`), and small hooks in
`Voice.java` / `MainActivity.java`.

---

## 1. What exists on this handset (measured, not assumed)

Everything below is the output of the probe that `WakeService` logs at start-up on
SM-A536E / Android 16 (SDK 36):

```
probe sdk=36 model=SM-A536E onDevice=true
 services=com.google.android.as/com.google.android.apps.miphone.aiai.app.AiAiSpeechRecognitionService
          com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService
 on[fa-IR]=ok,inst=[en-GB],pend=[]   on[en-GB]=ok,inst=[en-GB],pend=[]
 net[fa-IR]=err14  net[en-GB]=err14
```

Read that as:

| question | answer |
|---|---|
| Is there an on-device recogniser? | **Yes** — `SpeechRecognizer.isOnDeviceRecognitionAvailable()` = true, served by Android System Intelligence (`com.google.android.as`). |
| Which languages are installed for it? | **One: `en-GB`** (matches the device locale). `inst` = installed packs, `pend` = pending downloads. |
| Persian, on device? | **No.** Running it with `fa-IR` returns `error 12 LANGUAGE_NOT_SUPPORTED`. |
| `en-US`? | **No** — `error 13 LANGUAGE_UNAVAILABLE`, because only `en-GB` is installed. |
| Network recogniser (`com.google.android.googlequicksearchbox`)? | Present as a service, but **`err14` for every language** — unusable here, which is just as well: it must never be the always-on path. |
| Device locale | `en-GB` (`getprop persist.sys.locale`). |

Two failure modes cost an hour of debugging and are worth writing down:

* `SpeechRecognizer` may only be **created on the main thread**, and its
  `checkRecognitionSupport` callback is delivered **on the main thread**. Awaiting it with a
  latch on the main thread deadlocks (first probe: every query answered `timeout`); creating it
  from a worker thread throws (`probe: on[…] = throw:RuntimeException`). The probe is therefore
  a chain of main-thread callbacks with a watchdog per step.
* Asking an on-device recogniser for a language whose pack is missing does not fall back — it
  fails. The language must come from `inst=[…]`, so the engine adopts whatever pack is present.

## 2. The three options, as they apply here

| option | verdict on this phone |
|---|---|
| **1. `SpeechRecognizer` / `RecognizerIntent` with the local recogniser, looping over it** | **Chosen.** It exists, it is free, it runs offline, and it needs no model file, no download and no licence. The catch is the language: only `en-GB` — so the wake phrase is matched **as English**, which works because «هی جارویس» is phonetically "Hey Jarvis". |
| 2. A tiny KWS TFLite model trained for a custom phrase | Not needed here (option 1 works), and it would cost a Gradle-less project an `.aar` extraction + a model file. Kept as the fallback if the English pack ever disappears. |
| 3. Porcupine (Picovoice) | Not used: it needs an AccessKey tied to an account and adds a commercial SDK to a no-Gradle build, for a capability the platform already provides. Its "free for personal use" tier was not verified against the licence text because it was not needed. |

### Why matching Persian speech with an English model is not a hack
The wake word only has to be *detected*, not transcribed. Measured: ElevenLabs' Persian voice
(Roya) saying «هی جارویس» through the speaker at 2 m comes back from the `en-GB` recogniser as

```
partial [Hey] → partial [Hey Jar] → partial [Hey Jarvis]   (~600 ms after the phrase starts)
```

The matcher normalises Persian (Arabic yeh/kaf folding, ZWNJ, harakat) and accepts the stems
`jarvis jervis javis jarvus جارویس جارویز جاروس جرویس`, so either script matches. Everything
after the phrase is **not** taken from the English model — it is recorded as raw PCM and sent to
the Persian STT.

## 3. Architecture

```
                 ┌───────────────────── WakeService (foreground, type=microphone) ───────────────────┐
                 │  notification: «گوش میدهم: "هی جارویس"» + [خاموش کن]                            │
                 │                                                                                 │
 mic ──► Wake.Engine (AiAi on-device, en-GB, restarting sessions, none-stop)                      │
                 │      │ match stem "jarvis"                                                      │
                 │      ▼                                                                          │
                 │  beep ×2 + vibrate ──► Wake.Recorder (AudioRecord MIC, 16 kHz mono,             │
                 │                                     1 s silence tail, 8 s cap)                   │
                 │      │  WAV                                                                     │
                 └──────┼──────────────────────────────────────────────────────────────────────────┘
                        ▼
              POST /voice/in  (jarvisd → Gemini flash-lite, the path already measured at 1.2–1.8 s)
                        ▼
              MainActivity: the transcript is sent as a normal turn (streaming answer + TTS)
                        ▼
              while TTS plays, the service is paused (`Voice.speak` → WakeService.speaking())
                        ▼
              resumes listening when the answer ends
```

Battery decisions that are deliberate:

* **Nothing is streamed while listening.** The recogniser is a local acoustic model; the mic
  never touches the network until a phrase was actually heard.
* **One recogniser instance for the life of the service.** Sessions end on silence and are
  restarted with a 120 ms delay (400 ms if a session died in under 700 ms), so a noisy room
  cannot turn it into a hot loop. Destroy-and-recreate was measured to stop delivering results
  after about a minute, so it is only used as error recovery.
* **No wake lock.** The audio HAL already keeps the CPU out of deep sleep while capture is
  active; a `PARTIAL_WAKE_LOCK` on top of that is what actually kills the battery.
* **Paused while Jarvis speaks**, otherwise the answer wakes it again.
* Android 14+ rules are respected the honest way: `foregroundServiceType="microphone"`, the
  `FOREGROUND_SERVICE_MICROPHONE` permission, a permanent notification with a stop action, and
  the service is only ever started from a user tap (the app's own toggle) — never from the
  background, which the platform forbids for while-in-use permissions anyway.

## 4. User flow (exactly what happens)

1. The user opens the voice panel and turns **«همیشه گوش بده»** on (or `wake_on` from the panel).
   The app asks for the microphone and notification permissions if they are missing, then starts
   the service; a permanent notification appears: **«گوش میدهم: "هی جارویس"»**.
2. The user says **«هی جارویس»** — anywhere within earshot, screen off, any app in front.
   Detected in ~0.6 s. Phone beeps twice and vibrates once; the notification becomes
   **«دستور را بگو…»**.
3. The user says the command (**«تلگرام را باز کن»**). Recording stops on 1 s of silence (max 8 s).
   The notification becomes **«مکث (در حال پاسخ)»** while the console answers aloud.
4. The console opens with the command already sent; the answer streams and is spoken; listening
   resumes when the answer finishes.
5. Turning it off is one tap in the notification shade (**خاموش کن**) or the panel toggle. No
   listening ever happens without that notification on screen.

## 5. `/voice/wake` contract (daemon is the source of truth)

| method | path | body | answer |
|---|---|---|---|
| GET | `/voice/wake` | — | `{enabled, phrase, engine, listening, last_heard, state, in_sync, stale, app, uptime_s, probe, on_device_only}` |
| POST | `/voice/wake` | partial object | the same, merged |

The service pushes `{enabled, listening, engine:"ondevice-aiai/en-GB", last_heard, state, probe,
app, uptime_s}` on every state change; a panel (or curl) may push `{enabled:false}` to ask for
off, and `listening` is cleared because a wish is not a fact. `stale` turns true 120 s after the
last app report and `in_sync` is false whenever "the toggle says on" and "something is
listening" disagree — the panel can then say so instead of pretending.

## 6. Proof

Wake, from `adb logcat -s JarvisWake` (real run, phone speaker → phone mic):

```
10:46:10.532  partial [Hey]
10:46:10.573  partial [Hey Jar]
10:46:10.613  partial [Hey Jarvis]
10:46:10.614  WAKE full=Hey Jarvis
10:46:18.280  STT ms=3455 wavms=3380 text=…
10:46:18.280  DELIVER …
```

Battery numbers and the turn transcript against live `:8780` are in the section below once the
`jarvis` service restart lands (the running daemon still has the pre-wake module).

## 7. Known limits — stated, not hidden

* **No Persian on-device model.** The wake phrase is matched by an English model. This works for
  «هی جارویس» but a Persian-only phrase with no English-sounding core (e.g. «بیدار شو») would
  not be detected. Fixing that properly means shipping a Persian KWS model (option 2), or the
  user downloading Google's Persian offline pack and re-testing.
* **The command still goes to the cloud.** That is the designed split (local keyword, remote
  transcription) and it is also what keeps the battery honest; a fully offline command path
  needs a Persian on-device model, which this phone does not have.
* **The mic is blocked while another app holds it** (a call, another recorder). The service then
  sits at `error:…` in the notification and recovers when the mic is free.
* **The screen must be able to wake for the answer** — the console is brought to the front, which
  is how the answer gets a UI and the TTS. A fully background answer would need a second TTS
  player inside the service; not done in step 1.
