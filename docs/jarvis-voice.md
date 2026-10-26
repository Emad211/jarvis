# Jarvis voice: STT/TTS measurement on the phone (2026-10-26)

Samsung A53, Termux, no root. All numbers below were produced **on the phone**, over the
phone's own network, from `~/.hermes/.env` keys, with `curl` against the live daemon or the
Hermes venv (`$PREFIX/lib/hermes-agent/app/venv/bin/python`, httpx 0.28.1). No key value is
reproduced anywhere in this document.

> **Correction to the brief.** `phone/.local/bin/jarvisd_voice.py` **already exists and is
> deployed and live** (1102 lines, md5 `9c30cbe9111563f1f4a62e9d7ac2ff75`, byte-identical to
> this repo's mirror). `jarvisd.py:560` imports it at startup and the log says
> `jarvisd: jarvisd_voice loaded`. `GET /voice/status` answers on :8780. I did **not**
> rewrite it — it is more complete than the stub in the brief (it has `/voice/stream`,
> `/voice/probe`, **silence gating**, RIFF/f32/s16 width detection, ffmpeg decode of
> mp3/ogg, a keep-alive client, and persisted stats). What follows is measurement + the
> defects that measurement exposed.
>
> One caveat on that md5: the phone's file was being rewritten while I hashed it, so a
> *live* `md5sum` returned `faed56fd…`; two clean `base64` pulls both returned
> `9c30cbe9…`, identical to the mirror. Nothing was lost.

---

## 1. Test set (`~/voice-test/`, 7 clips)

**There is no microphone reachable from Termux over SSH, so every clip is TTS-synthesized**
(Gemini `gemini-3.8-flash-tts`, voice `Kore`, then ffmpeg → 16 kHz mono s16le WAV). This is a
real limitation: TTS audio is cleaner than a real mic in a room, so WER below is
**optimistic** for noisy real-world input. The reference texts are the ground truth.

| id | kind | reference text | audio |
|---|---|---|---|
| `c1_question` | question | هوا فردا چطوره؟ | 2.01 s |
| `c2_device` | device command | تنظیمات رو باز کن | 2.09 s |
| `c3_number_date` | number/date | ساعت ده و نیم جلسه دارم، بیست و سوم مهر | 3.85 s |
| `c4_name` | name | به امیرحسین زنگ بزن | 2.09 s |
| `c5_long` | long sentence | لطفاً پیام‌های امروز رو بخون و اگه خبر مهمی بود بهم بگو | 4.53 s |
| `c6_phone` | digits | شماره من صفر نهصد و دوازده، سه چهار پنج، شش هفت هشت | 5.53 s |
| `c7_mixed` | code-switch | فایل report نهایی رو برام ایمیل کن | 3.18 s |

Files: `~/voice-test/clips/*.wav`, refs in `~/voice-test/refs.json`. All 7 verified distinct
by md5, and `c2_device` verified by ear-through-STT to actually say «تنظیمات رو باز کن».

---

## 2. STT benchmark — which systems actually answer

| System | Result on this key | Why |
|---|---|---|
| **Gemini `gemini-3.5-transcribe`** | ❌ **all calls 429** | `RESOURCE_EXHAUSTED`, `GenerateRequestsPerDayPerProjectPerModel-FreeTier`, **quotaValue 25**, `retry in 20h8m`. Model is *listed* (200 on `/models/…`) but not callable. |
| **Gemini `gemini-3.5-flash-lite` + instruction** | ✅ **works, 7/7 clips** | the only viable path today |
| Gemini `gemini-3.5-transcribe-live` | ❌ 400 on `:generateContent` | live-only model; not callable this way |
| **ElevenLabs `scribe_v1`** | ❌ **403 on every request** | see §2.2 — network block, not a key problem |

### 2.1 Results — `gemini-3.5-flash-lite`, instruction-based

Instruction used (Persian, explicit):
> این فایل صوتی را کلمه به کلمه و دقیقاً همان‌طور که گفته شده به زبان فارسی بنویس. هیچ توضیح،
> ترجمه، عنوان یا علامت اضافه‌ای اضافه نکن. اگر شماره یا عددی گفته شد، همان عدد را بنویس.

Two latency columns matter and they differ a lot:

- **cold** = a fresh `httpx.Client()` per call (what my offline harness did): a TLS handshake
  on every call. **2.0–5.5 s**, median 2.5 s.
- **warm** = through the **live daemon** (`POST /voice/in`), which reuses one keep-alive
  client (`jarvisd_voice.py:496-510`). **1.1–2.4 s**, median ~1.6 s.

| clip | audio | **warm ms (live `/voice/in`)** | cold ms (3 runs, median) | transcript | verdict |
|---|---|---|---|---|---|
| `c1_question` | 2.01 s | 2773 | 3921 | هوا فردا چطوره؟ | ✅ exact |
| `c3_number_date` | 3.85 s | **1395** | 3109 | ساعت ۱۰ و نیم جلسه دارم ۲۳ مهر | ✅ correct (numerals) |
| `c4_name` | 2.09 s | **1115** | 2491 | به امیرحسین زنگ بزن. | ✅ exact |
| `c5_long` | 4.53 s | 1537 | 2195 | لطفاً پیام‌های امروز رو بخون و اگه خبر مهمی بود بهم بگو. | ✅ exact |
| `c6_phone` | 5.53 s | 2400 | 2076 | شماره من 0912 345678 | ✅ all digits preserved |
| `c7_mixed` | 3.18 s | 1668 | 2474 | فایلِ ریپورتِ نهایی رو برام ایمیل کن. | ⚠️ `report` → «ریپورت» |
| `c2_device` | 2.09 s | — (429 window) | 4750 | تنظیمات را باز کن. | ✅ correct; «رو»→«را» is register, not error |

**Accuracy verdict: every transcript is usable.** Six of seven are semantically exact; the
only genuine miss is the English word `report` transliterated to «ریپورت».

**On WER, honestly.** Naive word-WER says 19–26%, and that number is *misleading here*:
the ASR writes spoken numbers as numerals (`ده و نیم` → `۱۰:۳۰`, `صفر نهصد و دوازده` →
`۰۹۱۲`), which is the *more* useful form for a UI but scores as many token errors. My
re-scorer (`_rescore.py`, digit-string comparison) narrows it but still under-credits
compound numbers (`بیست و سوم` vs `۲۳`). **Rule of thumb from these 7 clips: ~1 in 7
utterances carries one real error, and it is a loanword/code-switch word, not a Persian
word.** Treat 19–26% word-WER as a pessimistic bound driven by numeral formatting.

**Non-determinism, measured:** the same `c6_phone` clip through the live endpoint 3× gave
`۰۹۱۲ ۳۴۵ ۶۷۸`, then `0912345678`, then `0912345678`; an earlier run produced
`۰۹۱۲ ۳۴۵ ۶۷۸۸` — **one extra digit**. Spacing/punctuation varies run to run, and rarely a
digit is duplicated. Any number the app acts on should be confirmed, not trusted blindly.

### 2.2 Why ElevenLabs is out (not the key)

An HTML 403 ("Your client does not have permission to get URL … from this server") is
returned by `https://api.elevenlabs.io/` — **the bare domain, no key sent** — and equally by
`https://elevenlabs.io/`. `api.openai.com` and `generativelanguage.googleapis.com` answer
normally from the same phone. The TLS certificate for `api.elevenlabs.io` is genuine
(issuer `Google Trust Services WR3`), so this is **not** a MITM proxy — it is the CDN edge
rejecting this source address. Egress is `81.171.72.78` (Amsterdam, datacenter AS), which
ElevenLabs' Cloudflare edge blocks. No VPN/tunnel binary is installed (`v2ray`, `xray`,
`sing-box`, `wireguard`, `tun2socks`, `openvpn` all absent), so this cannot be fixed from
inside Termux. `scribe_v1` STT, `eleven_v4_turbo` TTS and even `/v1/voices` are therefore all
unreachable — **the 403 reported for TTS is a whole-host block, and STT is blocked by the
same cause**, not a separate permission.

---

## 3. TTS quality — same Persian sentence, four models

Sentence: `سلام امیر، الان ساعت ده و نیمه. پیام‌های امروزت رو خوندم و دو خبر مهم داری.`
voice `Kore`, ffmpeg → 22.05 kHz mono 64 kbps MP3.

| model | latency | audio | bytes | real-time factor | status |
|---|---|---|---|---|---|
| **`gemini-3.8-flash-lite-tts`** | **4.39 s** (3.27 s in an earlier run) | 7.01 s | 56 886 | **1.60×** | ✅ **recommended** |
| `gemini-3.1-flash-tts-preview` | 7.41 s | 7.44 s | 60 230 | 1.00× | ✅ works, 1.7× slower |
| `gemini-3.8-flash-tts` (current default) | 9.39 s | 6.13 s | 49 781 | 0.65× | ⚠️ **10 req/day cap**, 429s in normal use |
| `gemini-2.5-flash-preview-tts` | 6.23 s | — | — | — | ❌ **HTTP 400** "Model tried to generate text" — it is a *configured fallback* and it is broken |

**Intelligibility check (round trip: render → ffmpeg to 16 kHz → live `/voice/in`):**

| voice | transcript of its own audio |
|---|---|
| `3.8-flash-lite-tts` | سلام امیر، الان ساعت ۱۰:۳۰، پیام‌های امروزتو خوندم و دو خبر مهم داری. |
| `3.1-flash-tts-preview` | سلام امیر، الان ساعت ۱۰:۳۰ء، پیام های امروزتو خوندم و دو خبر مهم داری. |

Both are fully intelligible and equally accurate; the lite model is cleaner (proper ZWNJ in
«پیام‌های», no stray «ء»). Both squash «رو خوندم» into «تو خوندم» — a prosody artifact of
the `Kore` voice at this speed, identical across models, so not a reason to pick one.

Samples: `~/voice-test/samples/tts_gemini-3.8-flash-lite-tts.mp3`,
`~/voice-test/samples/tts_gemini-3.1-flash-tts-preview.mp3`.

**Live TTS through the daemon is slower than the table above** because the default model is
dead and every call burns a failed attempt first:

```
run1: 9177 ms model=gemini-3.1-flash-tts-preview (fell_back_from: 3.8-flash-tts http-429)
run2: 6768 ms model=gemini-3.1-flash-tts-preview
run3: 7905 ms model=gemini-3.1-flash-tts-preview
```

Changing the default to `gemini-3.8-flash-lite-tts` should bring this to ~4–5 s.

**Verified, without editing any file** (by overriding `JARVIS_VOICE_TTS_MODEL` /
`JARVIS_VOICE_TTS_FALLBACK_MODELS`, which `config()` honours at `jarvisd_voice.py:233-245`,
and calling `speak()` in-process twice):

```
True  5343 ms  gemini-3.8-flash-lite-tts  6.96 s  56301 B
True  4873 ms  gemini-3.8-flash-lite-tts  6.12 s  49581 B
```

So the proposed change takes live TTS from **6.8–9.2 s → 4.9–5.3 s** and removes the wasted
429 attempt on every call. It is a two-line `DEFAULTS` edit, no routing change.

---

## 4. Quota reality (this is the binding constraint)

| model | free-tier cap | state at end of measurement |
|---|---|---|
| `gemini-3.5-transcribe` | **25 requests/day/project/model** | exhausted, resets in ~20 h |
| `gemini-3.8-flash-tts` | **10 requests/day/project/model** | exhausted |
| `gemini-3.1-flash-tts-preview` | separate per-model bucket | exhausted |
| `gemini-2.5-flash-preview-tts` | separate per-model bucket | exhausted |
| **`gemini-3.8-flash-lite-tts`** | separate per-model bucket | ✅ **still answering** |
| `gemini-3.5-flash-lite` (chat+audio) | much larger (separate metric) | served ~40 calls fine |

**My benchmarking consumed the TTS quota of three models.** Final probe, in order:

```
gemini-3.8-flash-lite-tts        OK   audio_b64=64476
gemini-3.1-flash-tts-preview     HTTP 429 quota exceeded
gemini-3.8-flash-tts             HTTP 429 quota exceeded
gemini-2.5-flash-preview-tts     HTTP 429 quota exceeded
```

A live `/voice/out` after that burn fails with all three configured attempts exhausted:

```
{"ok": false, "ms": 6415}   # 3.8-flash-tts 429 | 2.5-preview-tts 429 | 3.1-preview 429
```

**Consequence:** right now the console **cannot speak** with the shipped default chain, but it
**can** speak with `gemini-3.8-flash-lite-tts` — which is the one model I did not exhaust.
The dedicated STT model and three of the four TTS models are unusable for interactive use on
this key. That, not accuracy, is why the recommendation below is what it is.

> Operational note: each 429 carries a `retry in <N>h` and the free-tier TTS buckets are
> **per day**, so a single demo or a couple of dozen testing clicks can silence the voice
> until local midnight. The app needs a visible "voice unavailable today" state, not a spinner
> that hangs — the current `/voice/status` already exposes `tts_error` for exactly this, but
> only *after* a failure.

---

## 5. Defects found in the deployed module

### 5.1 Duplicate voice routes — FIXED during this session (by someone else)

When I started, `jarvisd.py` still had its own inline `/voice/in` and `/voice/out`
(`:1354`, `:1363`) registered on the *same* `app` as the module's, producing:

```
WARN Duplicate Operation ID voice_in_voice_in_post for function voice_in at .../jarvisd.py
WARN Duplicate Operation ID voice_out_voice_out_post for function voice_out at .../jarvisd.py
```

and `GET /openapi.json` showed `/voice/in operationId=voice_in_voice_in_post` (the
**jarvisd.py** function) with **`/voice/out` `requestBody: None`** — i.e. the schema stopped
documenting the JSON body the module expects. It worked only because Starlette dispatches the
last-registered route first.

**This has since been cleaned up in the live file:** `jarvisd.py` no longer registers any
`@app.post("/voice/...")`, and a comment now records the ownership split:

```python
# jarvisd.py:1312
# Voice lives in jarvisd_voice.py: it owns /voice/in, /voice/out, /voice/stream and
# /voice/status. Only the audio store stays here, because it is plain file serving.
```

So **no code change is needed for the routing** — verify with
`curl -s localhost:8780/openapi.json` that `/voice/out` has a requestBody again.

### 5.2 Remaining: dead / wasted fallbacks (still live)

- `tts_fallback_models` lists **`gemini-2.5-flash-preview-tts`**, which returns
  **HTTP 400** ("Model tried to generate text") — a wasted attempt on every failover, and it
  429s now too.
- `_STATS` is a **process-global that `_save_stats()` persists to
  `~/.hermes/jarvis/voice_stats.json`**. Calling `speak()` in-process from a maintenance
  script (as I did) overwrites the daemon's real numbers: `/voice/status` then reports a
  `tts_ms` that the daemon never measured. Harmless but misleading — worth a `pid`/`source`
  field in that file.
- `stt()` / `tts()` in the *old* `jarvisd.py` are gone along with the routes, so the stale
  `gemini-3.5-transcribe` code path no longer shadows the module. Good.

### Snippet to apply (the only change I recommend)

`jarvisd_voice.py:85` and `:89` in `DEFAULTS`:

```python
    "tts_model": "gemini-3.8-flash-lite-tts",           # was gemini-3.8-flash-tts (10 req/day)
    "tts_fallback_models": "gemini-3.1-flash-tts-preview",   # drop 2.5-preview-tts (HTTP 400/429)
```

Live `~/.hermes/config.yaml` still points at the dead models, and `config()` prefers the
legacy `stt:`/`tts:` layout when there is no `voice:` block
(`jarvisd_voice.py:213-227`) — so the fix must also land there, or the `DEFAULTS` edit is
overridden:

```yaml
voice:
  stt_model: gemini-3.5-flash-lite
  stt_fallback: gemini-3.5-transcribe
  tts_model: gemini-3.8-flash-lite-tts
  tts_fallback_models: gemini-3.1-flash-tts-preview
  tts_voice: Kore
```

I did **not** apply either change: `jarvisd.py` was being edited concurrently by another
worker during this session (its size changed twice, 58 500 → 58 848 B), and the rules put
daemon edits with the lead.

---

## 6. Recommendation

**STT: `gemini-3.5-flash-lite` with the explicit verbatim-Persian instruction, keep-alive
client, warm in the daemon.** 1.1–2.7 s for 2–5.5 s utterances, every transcript usable.
Last-state re-verification through the live endpoint after all benchmarking:

```
c1_question   1988 ms  هوا فردا چطوره؟
c2_device     2720 ms  تنظیمات را باز کن.
c4_name       2484 ms  به امیر حسین زنگ بزن.
```

`gemini-3.5-transcribe` stays configured as a fallback only — it is 25 requests/day and will
be empty most days.

**TTS: `gemini-3.8-flash-lite-tts`, voice `Kore` — and this is now mandatory, not just
faster.** It is the only TTS model with quota left (§4); the three others 429. Verified
rendering while the chain was otherwise dead:

```
True 4692 ms gemini-3.8-flash-lite-tts 3.16 s      (TTS left intact)
True 4873 ms gemini-3.8-flash-lite-tts 6.12 s      (full sentence, §3)
```

4.4–5.3 s for a 7 s sentence (~1.5× real time), intelligible, cleaner ZWNJ than the preview
model, and no wasted 429 attempt on every call.

**ElevenLabs: not available.** Whole host blocked at the edge from this phone's egress; would
need a VPN exit outside the datacenter AS. Do not spend time on the key.

**Latency shape the user will feel** («میخوام سری واکنش نشون بده»): ~1.5 s from end of
speech to text, then the agent turn (measured separately at 1.8–4 s to first token), then
~4.5 s for the spoken reply. The reply speech is now the slowest leg — if that matters,
speak the first sentence as soon as it is complete instead of waiting for the full answer.

### What I could not verify

- **Real-microphone accuracy.** All clips are TTS; no mic path exists over SSH. Real room
  audio will be worse than the 1-in-7 figure above. **UNVERIFIED.**
- **`/voice/stream` partial cadence.** The module documents that a 3 s clip arrives in one
  delta (so streaming is a progress signal, not a latency win); I did not re-time it.
- **ElevenLabs quality** — unreachable, so no comparative claim is possible.
- Whether the `Kore` voice's «رو خوندم»→«تو خوندم» artifact is audible to a native speaker or
  is only an STT artifact of that rendering. Worth one human listen.
