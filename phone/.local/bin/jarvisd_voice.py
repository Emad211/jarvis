#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_voice — speech in / speech out for the Jarvis console.

Replaces the inline handlers that used to sit at the bottom of `jarvisd.py` (`/voice/in`,
`/voice/out` and the `stt()` / `tts()` helpers) with one module that owns the provider
choice, the audio normalisation and the numbers behind `/voice/status`.

    POST /voice/in        raw WAV (or raw PCM, or JSON {wav|audio|b64}) -> {ok, text, ms, model}
    POST /voice/out       {text, voice?, provider?}                     -> {ok, url, ms, model}
    POST /voice/stream    raw WAV body                                  -> SSE partial* + final
    GET  /voice/status                                                  -> {stt_model, stt_ms, tts_model, tts_ms, ready}
    POST /voice/probe     {}                                            -> round trip, measured
    GET  /audio/{name}                                                  (already served by jarvisd)

Measured on this phone (Samsung A53, Termux, VPN Berlin exit), 2026-10-26
-------------------------------------------------------------------------
* `gemini-3.5-transcribe` -- the specialist STT model -- starts answering `429 You exceeded
  your current quota` on this free-tier key after a few dozen audio requests, so it cannot be
  the interactive default. It stays configured as `stt_fallback` for when billing is linked.
* `gemini-3.5-flash-lite` transcribes Persian correctly on the same key: 0 % CER on a clean
  TTS command, `هوا را` for `هوا رو` on a colloquial one, ~1.9 s wall clock for a 3-4 s clip
  (network to generativelanguage.googleapis.com included).
* ElevenLabs (`scribe_v1`, `eleven_v4_turbo`) is unreachable from this phone: the whole host
  answers a 403 HTML page from Google's frontend before any key is looked at.
* Gemini TTS returns 24 kHz PCM inside a WAV; it is re-encoded to a real mp3 with ffmpeg so
  `MediaPlayer` keeps working through the existing `/audio/{name}` route, unchanged.

Config (first hit wins): `~/.hermes/config.yaml` -> `voice:` block, then `JARVIS_VOICE_<KEY>`
in the environment, then DEFAULTS below.

    voice:
      stt_model: gemini-3.5-flash-lite
      stt_fallback: gemini-3.5-transcribe
      tts_provider: gemini            # gemini | elevenlabs
      tts_model: gemini-3.8-flash-tts
      tts_voice: Kore

Import-safe: no network, no file writes, no subprocess at import time.
"""

from __future__ import annotations

# FastAPI resolves the string annotations of route handlers in THIS module's namespace
# (postponed annotations above), so these names must be module-level, like jarvisd_tools.
from fastapi import Request
from fastapi.responses import JSONResponse, StreamingResponse

import array
import asyncio
import base64
import io
import json
import os
import re
import struct
import subprocess
import threading
import time
import uuid
import wave
from typing import Any, Dict, List, Optional, Tuple

GEMINI = "https://generativelanguage.googleapis.com/v1beta"
OPENROUTER = "https://openrouter.ai/api/v1"
ELEVEN = "https://api.elevenlabs.io/v1"
WHISPER_MODEL = "openai/whisper-large-v3"

TARGET_RATE = 16000
MAX_SECONDS = 60.0          # longer than this is a recording, not an utterance
SILENCE_PEAK = 250          # |s16| peak below this = no speech at all (about -42 dBFS)
TRIM_FLOOR = 180            # frame RMS below this counts as silence when trimming
STT_PROMPT = "Transcribe this speech verbatim, in its own language, no commentary."

DEFAULTS: Dict[str, Any] = {
    # The specialist STT model has almost no free-tier quota (429 today), so the general
    # flash-lite -- which accepts audio and answers on the same key -- is the default.
    "stt_model": "gemini-3.5-flash-lite",
    "stt_fallback": "gemini-3.5-transcribe",
    # Whisper through OpenRouter was the most accurate candidate measured (0 % CER on the
    # clean clips) and ~0.4 s faster, but it is a paid call (~$0.00002 per 3 s clip).
    # Off by default: `voice.stt_or_fallback: true` switches it on.
    "stt_or_fallback": False,
    "stt_language": "fa",
    "tts_provider": "gemini",                 # gemini | elevenlabs
    "tts_model": "gemini-3.8-flash-tts",
    # Free-tier TTS quota is per model and runs out during the day: 3.8-flash-tts starts
    # answering 429 while these two still render Persian. Tried in order, first one that
    # answers wins, so the voice keeps working when the daily quota of the default is gone.
    "tts_fallback_models": "gemini-2.5-flash-preview-tts,gemini-3.1-flash-tts-preview",
    "tts_voice": "Kore",                      # Gemini prebuilt voice
    "tts_fallback_provider": "none",          # set to elevenlabs once its 403 clears
    "el_model": "eleven_v4_turbo",            # the multilingual one that speaks Persian
    "el_voice_id": "XzieuK0bwYWT9Kl1LhLs",
    "max_seconds": MAX_SECONDS,
    # A full sentence, not a fragment: LLM-based STT hallucinates on 2-3 word clips, so a
    # probe with a real sentence is the only honest round-trip check.
    "probe_text": "این یک آزمایش کوتاه برای صدای فارسی جارویس است.",
}

SECRET_RE = re.compile(r"(AIza[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9]{8,}"
                       r"|Bearer\s+[A-Za-z0-9._-]{8,})")
AUDIO_NAME_RE = re.compile(r"^jv_[0-9a-f]{10}\.mp3$")

_LOCK = threading.Lock()
_STATS: Dict[str, Any] = {"stt_model": "", "stt_ms": 0, "tts_model": "", "tts_ms": 0,
                          "stt_at": 0.0, "tts_at": 0.0, "stt_error": "", "tts_error": "",
                          "saved_at": 0.0}
_CTX: Dict[str, Any] = {}
_CLIENT: Optional[Any] = None
_CLIENT_LOCK = threading.Lock()
_SEM: Optional[asyncio.Semaphore] = None
_NOTED = set()
_CFG_CACHE: Tuple[float, Dict[str, Any]] = (0.0, {})


# --------------------------------------------------------------------------- helpers --
def _home() -> str:
    return _CTX.get("HOME") or os.path.expanduser("~")


def _hermes() -> str:
    return _CTX.get("HERMES") or os.path.join(_home(), ".hermes")


def _audio_dir() -> str:
    # The exact directory jarvisd already serves from `GET /audio/{name}`.
    return os.path.join(_hermes(), "cache", "audio")


def _stats_path() -> str:
    return os.path.join(_hermes(), "jarvis", "voice_stats.json")


def redact(text: str) -> str:
    """Never let a key reach a log line or an HTTP error body."""
    return SECRET_RE.sub("<redacted>", text or "")


def _notify(msg: str) -> None:
    cleaned = redact(msg)
    fn = _CTX.get("notify")
    if fn:
        try:
            fn(cleaned)
            return
        except Exception:  # noqa: BLE001
            pass
    print("jarvisd_voice: %s" % cleaned, flush=True)


def _once(key: str, msg: str) -> None:
    """Warn once per process instead of on every utterance."""
    with _LOCK:
        if key in _NOTED:
            return
        _NOTED.add(key)
    _notify(msg)


def _now() -> float:
    fn = _CTX.get("now")
    return float(fn()) if fn else time.time()


def _jdump(obj: Any) -> str:
    fn = _CTX.get("jdump")
    if fn:
        try:
            return fn(obj)
        except Exception:  # noqa: BLE001
            pass
    return json.dumps(obj, ensure_ascii=False, default=str)


def _read_env_file(name: str) -> str:
    try:
        with open(os.path.join(_hermes(), ".env"), encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def _read_env(name: str) -> str:
    fn = _CTX.get("read_env")
    if fn:
        try:
            return fn(name) or ""
        except Exception:  # noqa: BLE001
            return ""
    return _read_env_file(name) or os.environ.get(name, "")


def config(refresh: bool = False) -> Dict[str, Any]:
    """DEFAULTS <- config.yaml `voice:` <- JARVIS_VOICE_<KEY> env. Cached for 5 s."""
    global _CFG_CACHE
    stamp, cached = _CFG_CACHE
    if not refresh and cached and time.time() - stamp < 5.0:
        return dict(cached)
    cfg = dict(DEFAULTS)
    raw: Dict[str, Any] = {}
    try:
        import yaml  # noqa: PLC0415  (optional: absent is fine)
        with open(os.path.join(_hermes(), "config.yaml"), encoding="utf-8") as fh:
            doc = yaml.safe_load(fh) or {}
        block = doc.get("voice")
        if isinstance(block, dict):
            raw = dict(block)
        else:
            # No `voice:` block yet: read the existing stt:/tts: layout so the console is
            # never worse than it was before this module existed.
            stt = doc.get("stt") if isinstance(doc.get("stt"), dict) else {}
            tts = doc.get("tts") if isinstance(doc.get("tts"), dict) else {}
            gem = stt.get("gemini") if isinstance(stt.get("gemini"), dict) else {}
            raw.setdefault("stt_model", gem.get("model") or stt.get("model") or "")
            raw.setdefault("stt_language", stt.get("language") or "")
            if tts:
                tgem = tts.get("gemini") if isinstance(tts.get("gemini"), dict) else {}
                tel = tts.get("elevenlabs") if isinstance(tts.get("elevenlabs"), dict) else {}
                raw.setdefault("tts_provider", tts.get("provider") or "")
                raw.setdefault("tts_model", tgem.get("model") or "")
                raw.setdefault("tts_voice", tgem.get("voice") or "")
                raw.setdefault("el_model", tel.get("model_id") or "")
                raw.setdefault("el_voice_id", tel.get("voice_id") or "")
    except Exception:  # noqa: BLE001
        pass
    for key, val in raw.items():
        if val not in (None, ""):
            cfg[key] = val
    for key, default in DEFAULTS.items():
        env = os.environ.get("JARVIS_VOICE_" + key.upper())
        if env in (None, ""):
            continue
        if isinstance(default, bool):
            cfg[key] = str(env).strip().lower() in ("1", "true", "yes", "on")
        elif isinstance(default, (int, float)):
            try:
                cfg[key] = type(default)(float(env))
            except ValueError:
                pass
        else:
            cfg[key] = env
    _CFG_CACHE = (time.time(), dict(cfg))
    return cfg


# -------------------------------------------------------------------------- WAV prep --
class Prepared:
    """Normalised audio plus the facts worth returning to the caller."""

    __slots__ = ("wav", "dur_s", "src_rate", "src_channels", "src_bits", "in_bytes",
                 "silent", "peak", "trimmed_s", "note")

    def __init__(self, **kw):
        for slot in self.__slots__:
            setattr(self, slot, kw.get(slot))

    def meta(self) -> Dict[str, Any]:
        return {"dur_s": self.dur_s, "src_rate": self.src_rate,
                "src_channels": self.src_channels, "src_bits": self.src_bits,
                "in_bytes": self.in_bytes, "trimmed_s": self.trimmed_s,
                "silent": bool(self.silent), "peak": self.peak, "note": self.note}


def _parse_riff(blob: bytes) -> Optional[Dict[str, Any]]:
    """Walk the RIFF chunks ourselves: the app's 44-byte header, ffmpeg's extra `LIST`
    chunks and `WAVE_FORMAT_EXTENSIBLE` all parse here. Returns None when it is not RIFF."""
    if len(blob) < 12 or blob[0:4] != b"RIFF" or blob[8:12] != b"WAVE":
        return None
    pos, fmt, data = 12, None, None
    while pos + 8 <= len(blob):
        cid = blob[pos:pos + 4]
        size = struct.unpack("<I", blob[pos + 4:pos + 8])[0]
        body = blob[pos + 8:pos + 8 + size]
        if cid == b"fmt " and len(body) >= 16:
            tag, channels, rate, _byte_rate, _align, bits = struct.unpack("<HHIIHH", body[:16])
            if tag == 0xFFFE and len(body) >= 26:      # extensible: sub-format in the tail
                tag = struct.unpack("<H", body[24:26])[0]
            fmt = {"format": tag, "channels": channels or 1, "rate": rate or TARGET_RATE,
                   "bits": bits or 16}
        elif cid == b"data":
            data = body if body else blob[pos + 8:]
            break
        pos += 8 + size + (size & 1)
    if fmt is None or not data:
        return None
    fmt["data"] = data
    return fmt


def _detect_raw(blob: bytes) -> Dict[str, Any]:
    """No RIFF header: the caller sent bare PCM. Guess the layout.

    float32 when the length divides by 4 and the head stays inside [-1.6, 1.6];
    16-bit little-endian otherwise -- which is exactly what `Voice.java` always sends.
    """
    n = len(blob)
    if n % 4 == 0 and n >= 64:
        try:
            vals = array.array("f")
            vals.frombytes(blob[: n - (n % 4)])
            head = vals[: min(len(vals), 4000)]
            if head and all(-1.6 <= v <= 1.6 for v in head) and any(v != 0 for v in head):
                return {"format": 3, "channels": 1, "rate": TARGET_RATE, "bits": 32,
                        "data": blob, "note": "raw-f32"}
        except Exception:  # noqa: BLE001
            pass
    if n % 2 == 0:
        return {"format": 1, "channels": 1, "rate": TARGET_RATE, "bits": 16,
                "data": blob, "note": "raw-s16le"}
    return {"format": 1, "channels": 1, "rate": TARGET_RATE, "bits": 8,
            "data": blob, "note": "raw-u8"}


def _to_s16_mono(pcm: bytes, fmt: Dict[str, Any]) -> array.array:
    """Any supported PCM layout -> array('h') of mono s16 samples."""
    tag = int(fmt["format"])
    channels = max(1, int(fmt["channels"]))
    bits = int(fmt["bits"])
    src: array.array
    if tag == 3 and bits in (32, 64):                     # float
        fl = array.array("f" if bits == 32 else "d")
        step = 4 if bits == 32 else 8
        fl.frombytes(pcm[: len(pcm) - (len(pcm) % step)])
        src = array.array("h", bytes(2 * len(fl)))
        for i, v in enumerate(fl):
            s = int(v * 32767.0)
            src[i] = 32767 if s > 32767 else (-32768 if s < -32768 else s)
    elif bits == 8:                                       # unsigned 8-bit
        src = array.array("h", bytes(2 * len(pcm)))
        for i in range(len(pcm)):
            src[i] = (pcm[i] - 128) << 8
    elif bits == 16:
        src = array.array("h")
        src.frombytes(pcm[: len(pcm) - (len(pcm) % 2)])
    elif bits == 24:
        n = len(pcm) // 3
        src = array.array("h", bytes(2 * n))
        for i in range(n):
            j = i * 3
            v = pcm[j + 1] | (pcm[j + 2] << 8)
            src[i] = v - 65536 if v & 0x8000 else v
    elif bits == 32:                                      # int32 PCM
        n = len(pcm) // 4
        vals = array.array("i")
        vals.frombytes(pcm[: 4 * n])
        src = array.array("h", bytes(2 * n))
        for i, v in enumerate(vals):
            src[i] = v >> 16
    else:                                                 # unknown width: assume 16-bit
        src = array.array("h")
        src.frombytes(pcm[: len(pcm) - (len(pcm) % 2)])
    if channels > 1 and src:                              # downmix by averaging
        n = len(src) // channels
        mono = array.array("h", bytes(2 * n))
        for i in range(n):
            base = i * channels
            mono[i] = int(sum(src[base:base + channels]) / channels)
        return mono
    return src


def _resample(samples: array.array, src_rate: int, dst_rate: int = TARGET_RATE) -> array.array:
    """Linear interpolation; a box pre-filter when decimating so sibilants do not alias."""
    if src_rate == dst_rate or src_rate <= 0 or not samples:
        return samples
    ratio = dst_rate / float(src_rate)
    if ratio < 1.0:                                  # moving average of ~1/ratio samples
        k = max(1, int(round(1.0 / ratio)))
        if k > 1:
            acc = array.array("h", bytes(2 * len(samples)))
            run = 0
            for i, v in enumerate(samples):
                run += v
                if i >= k:
                    run -= samples[i - k]
                acc[i] = int(run / k)
            samples = acc
    n_out = int(len(samples) * ratio)
    out = array.array("h", bytes(2 * n_out))
    last = len(samples) - 1
    for i in range(n_out):
        pos = i / ratio
        i0 = int(pos)
        if i0 >= last:
            out[i] = samples[last]
        else:
            frac = pos - i0
            out[i] = int(samples[i0] * (1.0 - frac) + samples[i0 + 1] * frac)
    return out


def _trim(samples: array.array, rate: int) -> Tuple[array.array, float]:
    """Cut dead air at both ends, keeping 120 ms of it: a 3 s tap is often 2.4 s of speech."""
    if not samples:
        return samples, 0.0
    frame = max(1, int(rate * 0.01))
    n = len(samples)
    loud: List[bool] = []
    for start in range(0, n, frame):
        chunk = samples[start:start + frame:4]
        if not chunk:
            break
        loud.append((sum(v * v for v in chunk) / len(chunk)) ** 0.5 >= TRIM_FLOOR)
    if not any(loud):
        return samples, 0.0
    first = next(i for i, v in enumerate(loud) if v)
    last = len(loud) - 1 - next(i for i, v in enumerate(reversed(loud)) if v)
    pad = int(0.12 * rate)
    a = max(0, (first - 12) * frame)
    b = min(n, (last + 12) * frame + pad)
    if b - a >= n:
        return samples, 0.0
    return samples[a:b], round((n - (b - a)) / float(rate), 3)


def _wav_bytes(samples: array.array, rate: int = TARGET_RATE) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(samples.tobytes())
    return buf.getvalue()


def _looks_container(blob: bytes) -> bool:
    head = blob[:16]
    return head[:3] == b"ID3" or head[:2] in (b"\xff\xfb", b"\xff\xf3", b"\xff\xf2") \
        or head[:4] in (b"OggS", b"fLaC") or head[4:8] == b"ftyp"


def _ffmpeg_to_wav(blob: bytes) -> bytes:
    """Decode an mp3/ogg/m4a body to 16 kHz mono WAV (used by /voice/probe and by any
    client that posts a compressed clip instead of a WAV)."""
    ff = _ffmpeg()
    if not ff:
        raise ValueError("compressed-audio-needs-ffmpeg")
    proc = subprocess.run([ff, "-y", "-v", "error", "-i", "pipe:0", "-ac", "1", "-ar",
                           str(TARGET_RATE), "-f", "wav", "pipe:1"],
                          input=blob, capture_output=True, timeout=30)
    if proc.returncode != 0 or not proc.stdout:
        raise ValueError("decode-failed: %s" % redact(proc.stderr[-120:].decode("utf-8", "replace")))
    return proc.stdout


def prepare(blob: bytes) -> Prepared:
    """Normalise whatever arrived into 16 kHz mono s16 WAV, and notice pure silence."""
    if not blob:
        raise ValueError("empty-body")
    note = ""
    if _looks_container(blob):
        blob = _ffmpeg_to_wav(blob)
        note = "decoded"
    fmt = _parse_riff(blob)
    if fmt is None:
        fmt = _detect_raw(blob)
        note = (note + "+" + fmt.get("note", "")).strip("+")
    src_rate = int(fmt["rate"])
    src_channels = int(fmt["channels"])
    src_bits = int(fmt["bits"])
    # A long non-16 kHz / stereo body costs seconds in pure Python; ffmpeg does it in ~150 ms.
    approx = len(fmt["data"]) // max(1, (src_bits // 8) * src_channels)
    if (src_rate != TARGET_RATE or src_channels > 1) and approx > 50_000 and _ffmpeg():
        blob = _ffmpeg_to_wav(blob)
        note = (note + "+ffmpeg").strip("+")
        again = _parse_riff(blob)
        if again is not None:
            fmt = again
            src_rate = int(fmt["rate"])
            src_channels = int(fmt["channels"])
            src_bits = int(fmt["bits"])
    samples = _to_s16_mono(fmt["data"], fmt)
    if src_rate != TARGET_RATE:
        samples = _resample(samples, src_rate)
    limit = int(TARGET_RATE * float(config().get("max_seconds") or MAX_SECONDS))
    if len(samples) > limit:
        samples = samples[:limit]
        note = (note + "+truncated").strip("+")
    samples, cut = _trim(samples, TARGET_RATE)
    peak = 0
    for v in samples[::7]:
        a = v if v >= 0 else -v
        if a > peak:
            peak = a
    return Prepared(wav=_wav_bytes(samples), dur_s=round(len(samples) / float(TARGET_RATE), 2),
                    src_rate=src_rate, src_channels=src_channels, src_bits=src_bits,
                    in_bytes=len(blob), silent=peak < SILENCE_PEAK, peak=peak,
                    trimmed_s=cut, note=note)


# ------------------------------------------------------------------------- transport --
def _client() -> Any:
    """One keep-alive client for the life of the process: a fresh TLS handshake to
    generativelanguage.googleapis.com costs 350-600 ms on this phone, which is a third of
    the whole budget for a 3 s utterance."""
    global _CLIENT
    import httpx  # noqa: PLC0415
    with _CLIENT_LOCK:
        if _CLIENT is None or getattr(_CLIENT, "is_closed", False):
            _CLIENT = httpx.AsyncClient(
                timeout=httpx.Timeout(connect=8.0, read=60.0, write=20.0, pool=5.0),
                limits=httpx.Limits(max_connections=8, max_keepalive_connections=6,
                                    keepalive_expiry=120.0),
                headers={"User-Agent": "jarvisd-voice/1"},
            )
        return _CLIENT


async def aclose() -> None:
    global _CLIENT
    with _CLIENT_LOCK:
        client, _CLIENT = _CLIENT, None
    if client is not None:
        try:
            await client.aclose()
        except Exception:  # noqa: BLE001
            pass


def _sem() -> asyncio.Semaphore:
    global _SEM
    if _SEM is None:
        _SEM = asyncio.Semaphore(4)
    return _SEM


def _gem_key() -> str:
    return _read_env("GEMINI_API_KEY") or _read_env("GOOGLE_AI_STUDIO_API_KEY")


def _or_key() -> str:
    return _read_env("OPENROUTER_API_KEY")


def _el_key() -> str:
    return _read_env("ELEVENLABS_API_KEY")


async def _post(url: str, *, headers: Dict[str, str], json_body: Any = None,
                retries: int = 2) -> Any:
    """POST with a short retry on the 429/503 bursts the free tier throws."""
    client = _client()
    resp = None
    for attempt in range(retries + 1):
        try:
            resp = await client.post(url, headers=headers, json=json_body)
        except Exception:  # noqa: BLE001  (timeout, DNS, TLS)
            if attempt >= retries:
                raise
            await asyncio.sleep(0.35 * (attempt + 1))
            continue
        if resp.status_code in (429, 503) and attempt < retries:
            await asyncio.sleep(0.4 * (attempt + 1))
            continue
        return resp
    return resp


# ------------------------------------------------------------------------------- STT --
def _gem_stt_body(wav: bytes) -> Dict[str, Any]:
    # Plain generateContent with the audio inline: no temperature / responseModalities,
    # because the transcribe models reject or empty out on some of those fields.
    return {"contents": [{"parts": [
        {"inlineData": {"mimeType": "audio/wav", "data": base64.b64encode(wav).decode()}},
        {"text": STT_PROMPT},
    ]}]}


def _gem_text(payload: Dict[str, Any]) -> str:
    parts = (((payload.get("candidates") or [{}])[0].get("content") or {}).get("parts") or [])
    return "".join(p.get("text", "") for p in parts).strip().strip('"').strip()


async def _stt_gemini(model: str, wav: bytes, lang: str = "") -> Tuple[str, int]:
    key = _gem_key()
    if not key:
        raise RuntimeError("no-gemini-key")
    resp = await _post("%s/models/%s:generateContent" % (GEMINI, model),
                       headers={"x-goog-api-key": key}, json_body=_gem_stt_body(wav))
    if resp.status_code != 200:
        raise RuntimeError("http-%d %s" % (resp.status_code, redact(resp.text[:160])))
    return _gem_text(resp.json()), resp.status_code


async def _stt_whisper(wav: bytes, lang: str = "") -> Tuple[str, int]:
    key = _or_key()
    if not key:
        raise RuntimeError("no-openrouter-key")
    body: Dict[str, Any] = {"model": WHISPER_MODEL,
                            "input_audio": {"data": base64.b64encode(wav).decode(),
                                            "format": "wav"}}
    if lang:
        body["language"] = lang
    resp = await _post(OPENROUTER + "/audio/transcriptions",
                       headers={"Authorization": "Bearer " + key}, json_body=body)
    if resp.status_code != 200:
        raise RuntimeError("http-%d %s" % (resp.status_code, redact(resp.text[:160])))
    return (resp.json().get("text") or "").strip(), resp.status_code


def _stt_chain(cfg: Dict[str, Any]) -> List[Tuple[str, Any]]:
    """(label, function) in the order we are willing to pay for them."""
    chain: List[Tuple[str, Any]] = [(str(cfg["stt_model"]), _stt_gemini)]
    fallback = str(cfg.get("stt_fallback") or "")
    if fallback and fallback != cfg["stt_model"]:
        chain.append((fallback, _stt_gemini))
    if cfg.get("stt_or_fallback") and _or_key():
        chain.append(("openrouter/" + WHISPER_MODEL, _stt_whisper))
    return chain


async def transcribe(wav_bytes: bytes) -> Dict[str, Any]:
    """Normalise, then transcribe with the first provider that answers.

    `ms` is the wall clock the caller waited, local normalisation included -- that is what
    the app actually feels.
    """
    t0 = time.perf_counter()
    cfg = config()
    try:
        prep = prepare(wav_bytes)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "text": "", "ms": int((time.perf_counter() - t0) * 1000),
                "model": "", "error": "bad-audio", "detail": redact(str(exc))}
    ms_local = int((time.perf_counter() - t0) * 1000)
    if prep.silent:
        return dict({"ok": True, "text": "", "ms": ms_local, "model": "silence-gate",
                     "silent": True, "ms_local": ms_local}, **prep.meta())
    if not _gem_key() and not _or_key():
        return {"ok": False, "text": "", "ms": ms_local, "model": "", "error": "no-key",
                "detail": "GEMINI_API_KEY is not set"}
    lang = str(cfg.get("stt_language") or "")
    errors: List[str] = []
    async with _sem():
        for label, fn in _stt_chain(cfg):
            ttry = time.perf_counter()
            try:
                if fn is _stt_gemini:
                    text, _code = await fn(label, prep.wav, lang)
                else:
                    text, _code = await fn(prep.wav, lang)
            except Exception as exc:  # noqa: BLE001
                errors.append("%s: %s" % (label, redact(str(exc))))
                continue
            ms = int((time.perf_counter() - t0) * 1000)
            _remember("stt", label, ms, "")
            out = {"ok": True, "text": text, "ms": ms, "model": label,
                   "ms_stt": int((time.perf_counter() - ttry) * 1000),
                   "ms_local": ms_local, "tried": len(errors) + 1}
            out.update(prep.meta())
            if errors:
                out["fell_back_from"] = errors
            return out
    ms = int((time.perf_counter() - t0) * 1000)
    detail = " | ".join(errors) or "no-provider"
    _remember("stt", "", ms, detail)
    _once("stt-fail", "stt failed: %s" % detail)
    return {"ok": False, "text": "", "ms": ms, "model": "", "error": "stt-failed",
            "detail": detail}


async def transcribe_stream(wav_bytes: bytes):
    """Async generator of {"kind": "partial"|"final"|"error", ...} for POST /voice/stream.

    Gemini `streamGenerateContent` for a 3 s clip delivers the whole transcription in one
    delta, so the first `partial` lands at about the time the plain call finishes: this is a
    progress signal, not a latency win (measured, not assumed). Anything that cannot stream
    falls back to one `final` event from `transcribe()`.
    """
    t0 = time.perf_counter()
    try:
        prep = prepare(wav_bytes)
    except Exception as exc:  # noqa: BLE001
        yield {"kind": "error", "ok": False, "error": "bad-audio", "detail": redact(str(exc))}
        return
    if prep.silent:
        yield dict({"kind": "final", "ok": True, "text": "",
                    "ms": int((time.perf_counter() - t0) * 1000),
                    "model": "silence-gate", "silent": True}, **prep.meta())
        return
    cfg = config()
    model = str(cfg["stt_model"])
    key = _gem_key()
    if not key:
        result = await transcribe(wav_bytes)
        yield dict(result, kind="final" if result.get("ok") else "error")
        return
    url = "%s/models/%s:streamGenerateContent?alt=sse" % (GEMINI, model)
    acc, first, events = "", None, 0
    try:
        async with _client().stream("POST", url, headers={"x-goog-api-key": key},
                                    json=_gem_stt_body(prep.wav)) as resp:
            if resp.status_code != 200:
                detail = redact((await resp.aread()).decode("utf-8", "replace")[:160])
                raise RuntimeError("http-%d %s" % (resp.status_code, detail))
            async for line in resp.aiter_lines():
                if not line or not line.startswith("data:"):
                    continue
                if first is None:
                    first = int((time.perf_counter() - t0) * 1000)
                try:
                    chunk = json.loads(line[5:].strip())
                except Exception:  # noqa: BLE001
                    continue
                parts = (((chunk.get("candidates") or [{}])[0].get("content") or {})
                         .get("parts") or [])
                for part in parts:
                    delta = part.get("text", "")
                    if not delta:
                        continue
                    acc += delta
                    events += 1
                    yield {"kind": "partial", "ok": True, "text": acc,
                           "ms": int((time.perf_counter() - t0) * 1000)}
    except Exception as exc:  # noqa: BLE001
        _notify("voice stream fell back: %s" % redact(str(exc)))
        result = await transcribe(wav_bytes)
        yield dict(result, kind="final" if result.get("ok") else "error")
        return
    ms = int((time.perf_counter() - t0) * 1000)
    _remember("stt", model, ms, "")
    yield dict({"kind": "final", "ok": True, "text": acc.strip(), "ms": ms, "model": model,
                "ms_first": first, "partials": events}, **prep.meta())


def _remember(kind: str, model: str, ms: int, error: str) -> None:
    """Keep the last *successful* timing; a failure only records its reason, so `/voice/status`
    never reports the latency of an error response as if it were a rendered utterance."""
    with _LOCK:
        if model:
            _STATS["%s_model" % kind] = model
        if ms and not error:
            _STATS["%s_ms" % kind] = int(ms)
            _STATS["%s_at" % kind] = _now()
        _STATS["%s_error" % kind] = error
    _save_stats()


def _save_stats() -> None:
    """Persist the last numbers so `/voice/status` is meaningful right after a restart."""
    if _now() - float(_STATS.get("saved_at") or 0) < 5.0:
        return
    try:
        path = _stats_path()
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with _LOCK:
            payload = dict(_STATS)
        payload["saved_at"] = _now()
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump(payload, fh, ensure_ascii=False)
        os.replace(tmp, path)
        with _LOCK:
            _STATS["saved_at"] = payload["saved_at"]
    except Exception:  # noqa: BLE001
        pass


def _load_stats() -> None:
    try:
        with open(_stats_path(), encoding="utf-8") as fh:
            data = json.load(fh)
    except Exception:  # noqa: BLE001
        return
    if not isinstance(data, dict):
        return
    with _LOCK:
        for key in ("stt_model", "stt_ms", "stt_at", "stt_error",
                    "tts_model", "tts_ms", "tts_at", "tts_error"):
            if key in data:
                _STATS[key] = data[key]


# ------------------------------------------------------------------------------- TTS --
def _ffmpeg() -> str:
    for cand in ("/data/data/com.termux/files/usr/bin/ffmpeg",
                 os.path.join(os.environ.get("PREFIX", ""), "bin", "ffmpeg")):
        if cand and os.path.isfile(cand) and os.access(cand, os.X_OK):
            return cand
    return ""


def _encode_mp3(wav_bytes: bytes, out_path: str) -> bool:
    """Gemini returns PCM in a WAV; MediaPlayer wants a real mp3 at /audio/{name}.mp3."""
    ff = _ffmpeg()
    if not ff:
        return False
    try:
        proc = subprocess.run(
            [ff, "-y", "-v", "error", "-f", "wav", "-i", "pipe:0",
             "-ac", "1", "-c:a", "libmp3lame", "-b:a", "64k", out_path],
            input=wav_bytes, capture_output=True, timeout=30)
        return proc.returncode == 0 and os.path.getsize(out_path) > 512
    except Exception:  # noqa: BLE001
        return False


def _pcm_to_wav(raw: bytes, rate: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(raw)
    return buf.getvalue()


def _audio_from_gemini(payload: Dict[str, Any]) -> bytes:
    parts = (((payload.get("candidates") or [{}])[0].get("content") or {}).get("parts") or [])
    inline = next((p["inlineData"] for p in parts if "inlineData" in p), None)
    if not inline:
        raise RuntimeError("no-audio-in-response")
    raw = base64.b64decode(inline.get("data") or "")
    mime = (inline.get("mimeType") or "").lower()
    if "wav" in mime or raw[:4] == b"RIFF":
        return raw
    rate = 24000
    for bit in mime.split(";"):
        if bit.strip().startswith("rate="):
            try:
                rate = int(bit.strip().split("=", 1)[1])
            except ValueError:
                pass
    return _pcm_to_wav(raw, rate)          # audio/L16 -> wrap it


async def _tts_gemini(text: str, voice: str, model: str) -> bytes:
    key = _gem_key()
    if not key:
        raise RuntimeError("no-gemini-key")
    body = {"contents": [{"parts": [{"text": text}]}],
            "generationConfig": {"responseModalities": ["AUDIO"],
                                 "speechConfig": {"voiceConfig": {
                                     "prebuiltVoiceConfig": {"voiceName": voice}}}}}
    resp = await _post("%s/models/%s:generateContent" % (GEMINI, model),
                       headers={"x-goog-api-key": key}, json_body=body, retries=1)
    if resp.status_code != 200:
        raise RuntimeError("http-%d %s" % (resp.status_code, redact(resp.text[:160])))
    return _audio_from_gemini(resp.json())


async def _tts_eleven(text: str, voice_id: str, model: str) -> bytes:
    key = _el_key()
    if not key:
        raise RuntimeError("no-elevenlabs-key")
    body = {"text": text, "model_id": model,
            "voice_settings": {"stability": 0.5, "similarity_boost": 0.75, "style": 0.0}}
    resp = await _post("%s/text-to-speech/%s?output_format=mp3_44100_128" % (ELEVEN, voice_id),
                       headers={"xi-api-key": key}, json_body=body, retries=0)
    if resp.status_code != 200:
        raise RuntimeError("http-%d %s" % (resp.status_code, redact(resp.text[:120])))
    return resp.content


async def speak(text: str, voice: str = "", provider: str = "") -> Dict[str, Any]:
    """Render `text` to an mp3 in the directory `GET /audio/{name}` already serves."""
    t0 = time.perf_counter()
    cfg = config()
    text = (text or "").strip()
    if not text:
        return {"ok": False, "url": "", "ms": 0, "model": "", "error": "empty-text"}
    if len(text) > 2000:
        text = text[:2000]
    provider = (provider or str(cfg.get("tts_provider") or "gemini")).lower()
    out_dir = _audio_dir()
    os.makedirs(out_dir, exist_ok=True)
    name = "jv_%s.mp3" % uuid.uuid4().hex[:10]
    if not AUDIO_NAME_RE.match(name):
        return {"ok": False, "url": "", "ms": 0, "model": "", "error": "bad-name"}
    path = os.path.join(out_dir, name)
    gem_voice = voice if provider == "gemini" and voice else str(cfg.get("tts_voice") or "Kore")
    el_voice = voice if provider == "elevenlabs" and voice else str(cfg.get("el_voice_id") or "")
    gem_models: List[str] = []
    for model in [str(cfg.get("tts_model") or "")] + \
            str(cfg.get("tts_fallback_models") or "").split(","):
        model = model.strip()
        if model and model not in gem_models:
            gem_models.append(model)
    attempts: List[Tuple[str, str, str]] = []
    fb = str(cfg.get("tts_fallback_provider") or "none").lower()
    if provider == "elevenlabs":
        attempts.append(("elevenlabs", el_voice, str(cfg["el_model"])))
        if fb == "gemini":
            attempts += [("gemini", gem_voice, m) for m in gem_models]
    else:
        attempts += [("gemini", gem_voice, m) for m in gem_models]
        if fb == "elevenlabs":
            attempts.append(("elevenlabs", el_voice, str(cfg["el_model"])))
    errors: List[str] = []
    async with _sem():
        for prov, vc, mdl in attempts:
            try:
                if prov == "elevenlabs":
                    audio = await _tts_eleven(text, vc, mdl)
                    model = "elevenlabs/" + mdl
                    if not _encode_mp3(_ffmpeg_to_wav(audio), path):
                        with open(path, "wb") as fh:      # already an mp3 from ElevenLabs
                            fh.write(audio)
                else:
                    audio = await _tts_gemini(text, vc, mdl)
                    model = mdl
                    if not _encode_mp3(audio, path):
                        with open(path, "wb") as fh:      # no ffmpeg: MediaPlayer sniffs WAV
                            fh.write(audio)
            except Exception as exc:  # noqa: BLE001
                errors.append("%s/%s: %s" % (prov, mdl, redact(str(exc))))
                continue
            ms = int((time.perf_counter() - t0) * 1000)
            _remember("tts", model, ms, "")
            out = {"ok": True, "url": "/audio/" + name, "ms": ms, "model": model,
                   "voice": vc, "bytes": os.path.getsize(path),
                   "seconds": _audio_seconds(path)}
            if errors:
                out["fell_back_from"] = errors
            return out
    ms = int((time.perf_counter() - t0) * 1000)
    detail = " | ".join(errors) or "no-provider"
    _remember("tts", "", ms, detail)
    _once("tts-fail", "tts failed: %s" % detail)
    return {"ok": False, "url": "", "ms": ms, "model": "", "error": "tts-failed",
            "detail": detail}


def _audio_seconds(path: str) -> float:
    """Duration of the rendered file, for the app's playback hint (0.0 if unmeasurable)."""
    ff = _ffmpeg()
    probe = "ffprobe"
    if ff:
        cand = os.path.join(os.path.dirname(ff), "ffprobe")
        if os.path.isfile(cand):
            probe = cand
    try:
        proc = subprocess.run([probe, "-v", "error", "-show_entries", "format=duration",
                               "-of", "csv=p=0", path], capture_output=True, timeout=15)
        return round(float((proc.stdout or b"0").decode().strip() or 0), 2)
    except Exception:  # noqa: BLE001
        return 0.0


def status() -> Dict[str, Any]:
    """Last measured numbers, cached -- never a probe, because the app polls this."""
    cfg = config()
    with _LOCK:
        stats = dict(_STATS)
    has_gem = bool(_gem_key())
    has_or = bool(_or_key())
    chain = [str(cfg["stt_model"])]
    if cfg.get("stt_fallback"):
        chain.append(str(cfg["stt_fallback"]))
    if cfg.get("stt_or_fallback"):
        chain.append("openrouter/" + WHISPER_MODEL)
    return {"stt_model": stats.get("stt_model") or str(cfg["stt_model"]),
            "stt_ms": int(stats.get("stt_ms") or 0),
            "tts_model": stats.get("tts_model") or str(cfg["tts_model"]),
            "tts_ms": int(stats.get("tts_ms") or 0),
            "ready": bool(has_gem or has_or),
            "stt_at": stats.get("stt_at") or 0,
            "tts_at": stats.get("tts_at") or 0,
            "stt_error": redact(stats.get("stt_error") or ""),
            "tts_error": redact(stats.get("tts_error") or ""),
            "stt_chain": chain,
            "tts_provider": str(cfg.get("tts_provider") or ""),
            "tts_voice": str(cfg.get("tts_voice") or ""),
            "keys": {"gemini": has_gem, "openrouter": has_or,
                     "elevenlabs": bool(_el_key())}}


# ---------------------------------------------------------------------------- routes --
def register(app, ctx) -> None:
    """Attach /voice/* to jarvisd; called once by jarvisd at startup."""
    global _CTX
    _CTX = dict(ctx or {})
    _load_stats()
    paths = {getattr(route, "path", "") for route in getattr(app, "routes", [])}
    if "/voice/status" in paths:
        return                                    # already registered (double import)

    def err(code: str, detail: str = "", status_code: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": redact(detail)},
                            status_code=status_code)

    async def body_bytes(req) -> bytes:
        """The app posts the WAV as the whole body; accept JSON {wav|audio|b64|data} too."""
        blob = await req.body()
        ctype = (req.headers.get("content-type") or "").lower()
        if "json" not in ctype:
            return blob
        try:
            data = json.loads(blob)
        except Exception:  # noqa: BLE001
            return b""
        if not isinstance(data, dict):
            return b""
        for key in ("wav", "audio", "b64", "data"):
            val = data.get(key)
            if isinstance(val, str) and val:
                try:
                    return base64.b64decode(val.split(",")[-1])
                except Exception:  # noqa: BLE001
                    return b""
        return b""

    @app.post("/voice/in")
    async def voice_in(request: Request):
        blob = await body_bytes(request)
        if not blob:
            return err("empty", "no audio in the body")
        out = await transcribe(blob)
        if not out.get("ok"):
            return JSONResponse(dict(out, ok=False), status_code=502)
        return out

    @app.post("/voice/out")
    async def voice_out(request: Request):
        try:
            body = await request.json()
        except Exception:  # noqa: BLE001
            body = {}
        body = body if isinstance(body, dict) else {}
        text = str(body.get("text") or "").strip()
        if not text:
            return err("empty", "text is required")
        out = await speak(text, voice=str(body.get("voice") or ""),
                          provider=str(body.get("provider") or ""))
        if not out.get("ok"):
            return JSONResponse(dict(out, ok=False), status_code=502)
        return out

    @app.post("/voice/stream")
    async def voice_stream(request: Request):
        blob = await body_bytes(request)
        if not blob:
            return err("empty", "no audio in the body")

        async def gen():
            seq = 0
            base = _now()
            async for ev in transcribe_stream(blob):
                seq += 1
                kind = ev.pop("kind")
                frame = {"seq": seq, "kind": kind, "ts": round(base + seq * 0.001, 3),
                         "data": ev}
                yield "id: %d\nevent: %s\ndata: %s\n\n" % (seq, kind, _jdump(frame))

        return StreamingResponse(gen(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache",
                                          "X-Accel-Buffering": "no"})

    @app.get("/voice/status")
    def voice_status():
        return status()

    @app.post("/voice/probe")
    async def voice_probe():
        """Round trip: speak a sentence, transcribe that file, report both timings."""
        tts = await speak(str(config().get("probe_text") or "تست صدای جارویس."))
        audio = b""
        if tts.get("ok"):
            try:
                with open(os.path.join(_audio_dir(), os.path.basename(tts["url"])), "rb") as fh:
                    audio = fh.read()
            except OSError:
                audio = b""
        stt = await transcribe(audio) if audio else {"ok": False, "error": "no-probe-audio"}
        keep = ("ok", "text", "ms", "model", "error", "detail", "ms_stt", "ms_local")
        return {"ok": bool(stt.get("ok")),
                "stt": {k: stt.get(k) for k in keep if k in stt},
                "tts": {k: tts.get(k) for k in keep + ("url", "seconds") if k in tts},
                "status": status()}

    try:
        app.add_event_handler("shutdown", aclose)
    except Exception:  # noqa: BLE001
        pass
    cfg = config()
    _notify("voice ready: stt=%s (%s) tts=%s/%s" % (cfg["stt_model"], cfg.get("tts_voice"),
                                                     cfg["tts_provider"], cfg["tts_model"]))


# ------------------------------------------------------------------------- self-test --
def _selftest(path: str) -> None:
    """`python jarvisd_voice.py clip.wav` -- transcribe one file, then speak one line."""
    with open(path, "rb") as fh:
        blob = fh.read()
    print(_jdump(asyncio.run(transcribe(blob))))
    print(_jdump(asyncio.run(speak("این یک آزمایش صدای فارسی است."))))
    print(_jdump(status()))


if __name__ == "__main__":
    import sys
    _CTX = {"HERMES": os.path.join(os.path.expanduser("~"), ".hermes"),
            "HOME": os.path.expanduser("~"), "now": time.time,
            "jdump": lambda o: json.dumps(o, ensure_ascii=False, default=str),
            "notify": lambda m: print(m, flush=True)}
    _load_stats()
    if len(sys.argv) > 1:
        _selftest(sys.argv[1])
    else:
        print(_jdump(status()))
