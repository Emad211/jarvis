#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd — the local brain for the Jarvis console app.

Why this exists
---------------
The Telegram gateway spawns a fresh `hermes -z "…"` per message (~13 s before the model
is called) and cannot stream tool output. This daemon keeps the Hermes agent **warm,
inside one process** and speaks to the app over loopback with Server-Sent Events:

    cold turn    ~8-9 s   (first turn after start; prompt assembly)
    warm turn    ~1.8 s   to first token, tool events as they happen
        measured 2026-10-26 on the phone, gemini-3.5-flash-lite, reasoning=none

Design rules
------------
* Bind 127.0.0.1 only, like gwctl. Never expose the agent to the LAN.
* One warm `AIAgent` per session, reused across turns; the process is the cache.
* The app never sees a provider key: keys stay in ~/.hermes/.env, read here.
* Approvals and clarifying questions block the agent turn until the app answers,
  because `tools.terminal_tool.set_approval_callback` is thread-local.
* Every event is numbered and replayable, so a reconnect resumes mid-turn.

API (all JSON unless noted)
---------------------------
GET  /health                          {ok, warm, sessions, model, version}
GET  /catalog                         models, toolsets, presets for the pickers
POST /session/new          {}         -> {session}
GET  /sessions                        -> [{id,title,turns,updated,busy}]
GET  /session/<sid>                   -> {messages:[...], model, reasoning}
POST /session/<sid>/turn   {text}     -> {ok, seq}
GET  /session/<sid>/events?from=0     -> text/event-stream of numbered events
POST /session/<sid>/cancel            -> {ok}
POST /session/<sid>/model  {model,provider}
POST /session/<sid>/reasoning {effort}
POST /ask/<ask_id>         {value}    -> {ok}   (approval / clarify answer)
POST /voice/in             WAV body   -> {text}
POST /voice/out            {text}     -> {url, seconds}
GET  /audio/<name>                    audio/mpeg stream for TTS playback

Events: text.delta · thinking.delta · tool.start · tool.end · tool.progress · status ·
ask · turn.start · turn.end · error · usage
"""

from __future__ import annotations

import asyncio
import base64
import json
import os
import re
import sys
import threading
import time
import uuid
from collections import deque
from typing import Any, Dict, List, Optional

HOME = os.path.expanduser("~")
HERMES = os.path.join(HOME, ".hermes")
APP = "/data/data/com.termux/files/usr/lib/hermes-agent/app"
sys.path.insert(0, APP)
os.chdir(HOME)

VERSION = "0.1"
PORT = int(os.environ.get("JARVIS_PORT", "8780"))
AUDIO_DIR = os.path.join(HERMES, "cache", "audio")
SESSIONS_DIR = os.path.join(HERMES, "jarvis", "sessions")
EVENT_KEEP = 600  # per-session replay window

# The console's own personality: short, action-first, no ceremony. Personas arrive in M2;
# for now this is what the app runs on, so the Telegram bot keeps its own SOUL.md.
JARVIS_BRIEF = (
    "You are Jarvis, the user's agent running inside his Android phone. "
    "Be direct and fast: answer in the user's language (Persian by default), keep replies "
    "short, and prefer doing the task with a tool over describing how to do it. "
    "When you use a tool, say in one line what you are doing. Never invent results: if a "
    "tool failed, say what failed. Never print API keys or passwords."
)

# Toolsets offered by the app; preset "fast" keeps the prompt small (quicker first token).
FAST_TOOLSETS = ["terminal", "file", "todo", "memory", "vision", "clarify"]
TOOLSETS = [
    "web", "browser", "terminal", "file", "code_execution", "vision", "image_gen", "tts",
    "skills", "todo", "memory", "session_search", "clarify", "delegation", "cronjob",
    "computer_use",
]

# Measured on this phone (2026-10-26): lite + reasoning off + a warm agent answers with
# its first token in ~1.8 s, the same turn on gemini-3.5-flash costs ~0.6 s more, and
# gemini-3.8-flash is quota-exhausted on the free tier. Speed is the point of this app.
DEFAULT_MODEL = os.environ.get("JARVIS_MODEL", "")
DEFAULT_REASONING = os.environ.get("JARVIS_REASONING", "none")


# --------------------------------------------------------------------------- helpers --
def read_env(name: str) -> str:
    try:
        with open(os.path.join(HERMES, ".env"), encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip().strip('"').strip("'")
    except OSError:
        pass
    return ""


def load_config() -> Dict[str, Any]:
    try:
        import yaml
        with open(os.path.join(HERMES, "config.yaml"), encoding="utf-8") as fh:
            return yaml.safe_load(fh) or {}
    except Exception:  # noqa: BLE001
        return {}


def now() -> float:
    return time.time()


def jdump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False, default=str)


# -------------------------------------------------------------------------- event hub --
class Session:
    """One conversation: its warm agent, its event log, its subscriber queues."""

    def __init__(self, sid: str, title: str = ""):
        self.id = sid
        self.title = title or "گفتگوی تازه"
        self.created = now()
        self.updated = self.created
        self.events: deque = deque(maxlen=EVENT_KEEP)
        self.seq = 0
        self.subs: List[asyncio.Queue] = []
        self.lock = threading.Lock()
        self.busy = False
        self.cancel = threading.Event()
        self.agent = None
        self.model = ""
        self.reasoning = DEFAULT_REASONING
        self.messages: List[Dict[str, Any]] = []
        self.asks: Dict[str, Dict[str, Any]] = {}
        self.turn_seq = 0

    # events ---------------------------------------------------------------------
    def emit(self, kind: str, data: Optional[Dict[str, Any]] = None) -> int:
        with self.lock:
            self.seq += 1
            ev = {"seq": self.seq, "kind": kind, "ts": round(now(), 3), "data": data or {}}
            self.events.append(ev)
            subs = list(self.subs)
        for q in subs:
            try:
                q.put_nowait(ev)
            except asyncio.QueueFull:
                pass
        return self.seq

    def since(self, seq: int) -> List[dict]:
        with self.lock:
            return [e for e in self.events if e["seq"] > seq]

    # persistence ----------------------------------------------------------------
    def path(self) -> str:
        return os.path.join(SESSIONS_DIR, self.id + ".json")

    def save(self) -> None:
        """Transcript survives a daemon restart: one small JSON file per session."""
        try:
            os.makedirs(SESSIONS_DIR, exist_ok=True)
            data = {"id": self.id, "title": self.title, "created": self.created,
                    "updated": self.updated, "model": self.model,
                    "reasoning": self.reasoning, "turns": self.turn_seq,
                    "messages": self.messages}
            tmp = self.path() + ".tmp"
            with open(tmp, "w", encoding="utf-8") as fh:
                json.dump(data, fh, ensure_ascii=False)
            os.replace(tmp, self.path())
        except OSError as exc:
            print("jarvisd: session save failed: %s" % exc, flush=True)

    @classmethod
    def load(cls, name: str) -> Optional["Session"]:
        try:
            with open(os.path.join(SESSIONS_DIR, name), encoding="utf-8") as fh:
                data = json.load(fh)
        except (OSError, ValueError):
            return None
        s = cls(str(data.get("id") or name[:-5]), str(data.get("title") or ""))
        s.created = float(data.get("created") or s.created)
        s.updated = float(data.get("updated") or s.updated)
        s.model = str(data.get("model") or "")
        s.reasoning = str(data.get("reasoning") or DEFAULT_REASONING)
        s.turn_seq = int(data.get("turns") or 0)
        s.messages = data.get("messages") or []
        return s


class Hub:
    def __init__(self) -> None:
        self.sessions: Dict[str, Session] = {}
        self.lock = threading.Lock()
        self.warm = False
        self._loaded = False

    def _load_once(self) -> None:
        if self._loaded:
            return
        self._loaded = True
        try:
            names = [n for n in os.listdir(SESSIONS_DIR) if n.endswith(".json")]
        except OSError:
            return
        for name in names:
            s = Session.load(name)
            if s and s.id != "warmup":
                with self.lock:
                    self.sessions[s.id] = s

    def get(self, sid: str) -> Optional[Session]:
        self._load_once()
        with self.lock:
            return self.sessions.get(sid)

    def new(self, title: str = "") -> Session:
        sid = uuid.uuid4().hex[:12]
        s = Session(sid, title)
        with self.lock:
            self.sessions[sid] = s
        return s

    def list(self) -> List[Session]:
        self._load_once()
        with self.lock:
            return sorted(self.sessions.values(), key=lambda s: s.updated, reverse=True)


HUB = Hub()


# ------------------------------------------------------------------------ agent runner --
def build_agent(session: Session, model: str, toolsets: List[str], reasoning: str):
    """Construct (or rebuild) the warm agent for a session. Called inside the worker thread."""
    from run_agent import AIAgent

    def on_delta(d):
        if d:  # the runtime signals "stream closed" with an empty/None delta
            session.emit("text.delta", {"text": d})

    def on_think(d):
        if d:
            session.emit("thinking.delta", {"text": d})

    # tool_start_callback(tool_call_id, function_name, display_args)
    def on_tool_start(call_id, name, args=None):
        session.emit("tool.start", {"id": str(call_id), "name": str(name),
                                    "args": _short(args)})

    # tool_complete_callback(tool_call_id, name, display_args, display_result)
    def on_tool_done(call_id, name, args=None, result=None):
        session.emit("tool.end", {"id": str(call_id), "name": str(name),
                                  "args": _short(args), "result": _short(result, 1200)})

    # tool_progress_callback(kind, function_name, preview, display_args)
    def on_progress(kind, name=None, preview=None, args=None):
        session.emit("tool.progress", {"kind": str(kind), "name": _short(name, 80),
                                       "detail": _short(preview, 300)})

    def on_status(kind, message=""):
        session.emit("status", {"kind": str(kind), "text": _short(message, 300)})

    def on_clarify(question, choices=None):
        return ask(session, "clarify", {"question": _short(question, 1200),
                                        "choices": _short(choices, 400)}, timeout=300)

    def on_approval(command, description="", **kwargs):
        answer = ask(session, "approval", {"command": _short(command, 1500),
                                           "description": _short(description, 600),
                                           "permanent": bool(kwargs.get("allow_permanent"))},
                     timeout=int(load_config().get("approvals", {}).get("timeout") or 300))
        return {"allow": "once", "always": "always", "session": "session"}.get(answer, "deny")

    agent = AIAgent(
        model=model,
        quiet_mode=True,
        verbose_logging=False,
        enabled_toolsets=toolsets or None,
        reasoning_config={"effort": reasoning} if reasoning else None,
        ephemeral_system_prompt=JARVIS_BRIEF,
        session_id=session.id,
        platform="jarvis",
        user_id="emad",
        chat_id=session.id,
        stream_delta_callback=on_delta,
        thinking_callback=on_think,
        tool_start_callback=on_tool_start,
        tool_complete_callback=on_tool_done,
        tool_progress_callback=on_progress,
        status_callback=on_status,
        clarify_callback=on_clarify,
    )
    return agent, on_approval


def _short(value: Any, limit: int = 300) -> str:
    if value is None:
        return ""
    if not isinstance(value, str):
        try:
            value = jdump(value)
        except Exception:  # noqa: BLE001
            value = str(value)
    value = value.replace("\r", "")
    return value if len(value) <= limit else value[: limit - 1] + "…"


def ask(session: Session, kind: str, payload: Dict[str, Any], timeout: int = 300) -> str:
    """Publish a question and block this thread until the app answers (or times out)."""
    ask_id = uuid.uuid4().hex[:8]
    done = threading.Event()
    box: Dict[str, str] = {"value": ""}
    session.asks[ask_id] = {"event": done, "box": box, "kind": kind}
    session.emit("ask", dict(payload, id=ask_id, kind=kind, timeout=timeout))
    if not done.wait(timeout):
        session.asks.pop(ask_id, None)
        session.emit("status", {"kind": "ask_timeout", "text": "پاسخی نیامد"})
        return "timeout"
    session.asks.pop(ask_id, None)
    return box["value"]


def answer_ask(ask_id: str, value: str) -> bool:
    for s in HUB.sessions.values():
        entry = s.asks.get(ask_id)
        if entry:
            entry["box"]["value"] = value
            entry["event"].set()
            return True
    return False


def _progress(session: Session, kind: str, name=None, preview=None, args=None,
              state: Optional[Dict[str, Any]] = None) -> None:
    session.emit("tool.progress", {"kind": str(kind), "name": _short(name, 80),
                                  "detail": _short(preview, 300)})
    # Some providers stream the final answer through the reasoning channel and never emit
    # text deltas. If nothing has been shown yet, that text IS the answer: surface it.
    if (state is not None and kind == "reasoning.available" and state.get("t") is None
            and preview and str(name or "") == "_thinking"):
        state["t"] = now()
        session.emit("status", {"kind": "ttft",
                                "text": str(round((now() - state.get("started", now())) * 1000))})
        session.emit("text.delta", {"text": str(preview)})


def run_turn(session: Session, text: str) -> None:
    """Worker-thread body: one user turn through the warm agent."""
    from tools.terminal_tool import set_approval_callback

    session.busy = True
    session.turn_seq += 1
    turn_id = session.turn_seq
    started = now()
    session.emit("turn.start", {"turn": turn_id, "text": text[:4000]})
    session.messages.append({"role": "user", "text": text, "ts": started})
    first_delta = {"t": None}

    def wrap_delta(d):
        if not d:
            return
        if first_delta["t"] is None:
            first_delta["t"] = now()
            session.emit("status", {"kind": "ttft", "text": str(round((now() - started) * 1000))})
        session.emit("text.delta", {"text": d})

    try:
        cfg = load_config()
        model = session.model or ((cfg.get("model") or {}).get("default") or "gemini-3.5-flash")
        reasoning = session.reasoning
        toolsets = FAST_TOOLSETS
        if session.agent is None or getattr(session.agent, "_jarvis_key", None) != (model, reasoning):
            agent, approval = build_agent(session, model, toolsets, reasoning)
            # Swap only after a successful build, and route this thread's approvals.
            session.agent = agent
            setattr(agent, "_jarvis_key", (model, reasoning))
        else:
            approval = _approval_for(session, model, reasoning)
        set_approval_callback(approval)
        agent = session.agent
        agent.stream_delta_callback = wrap_delta
        agent.tool_progress_callback = (
            lambda kind, name=None, preview=None, args=None:
            _progress(session, kind, name, preview, args,
                      {"t": first_delta["t"], "started": started}))
        result = agent.run_conversation(user_message=text, task_id="jv-%s-%d" % (session.id, turn_id))
        reply = (result or {}).get("final_response", "") or ""
        error = (result or {}).get("error")
        session.messages.append({"role": "assistant", "text": reply, "ts": now(),
                                 "ms": int((now() - started) * 1000)})
        if error and not reply:
            session.emit("error", {"text": _short(error, 600)})
        session.emit("turn.end", {"turn": turn_id, "ms": int((now() - started) * 1000),
                                  "reply": reply[:8000]})
    except Exception as exc:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        session.emit("error", {"text": "%s: %s" % (type(exc).__name__, exc)})
        session.emit("turn.end", {"turn": turn_id, "ms": int((now() - started) * 1000),
                                  "reply": "", "failed": True})
    finally:
        session.busy = False
        session.updated = now()
        if session.title in ("", "گفتگوی تازه"):
            session.title = text.strip().splitlines()[0][:40] if text.strip() else session.title
        session.save()


def _approval_for(session: Session, model: str, reasoning: str):
    """Rebuild only the approval closure for a cached agent."""
    def on_approval(command, description="", **kwargs):
        answer = ask(session, "approval", {"command": _short(command, 1500),
                                           "description": _short(description, 600),
                                           "permanent": bool(kwargs.get("allow_permanent"))},
                     timeout=int(load_config().get("approvals", {}).get("timeout") or 300))
        return {"allow": "once", "always": "always", "session": "session"}.get(answer, "deny")

    def on_clarify(question, choices=None):
        return ask(session, "clarify", {"question": _short(question, 1200),
                                        "choices": _short(choices, 400)}, timeout=300)
    try:
        session.agent.clarify_callback = on_clarify
    except Exception:  # noqa: BLE001
        pass
    return on_approval


# ------------------------------------------------------------------------------- voices --
GEMINI = "https://generativelanguage.googleapis.com/v1beta"


def _gem_key() -> str:
    return read_env("GEMINI_API_KEY") or read_env("GOOGLE_AI_STUDIO_API_KEY")


async def stt(wav: bytes) -> str:
    import httpx
    key = _gem_key()
    if not key:
        return ""
    body = {"contents": [{"parts": [
        {"inlineData": {"mimeType": "audio/wav", "data": base64.b64encode(wav).decode()}},
        {"text": "Transcribe this speech verbatim, in its own language, no commentary."},
    ]}]}
    async with httpx.AsyncClient(timeout=90) as client:
        r = await client.post(f"{GEMINI}/models/gemini-3.5-transcribe:generateContent",
                              headers={"x-goog-api-key": key}, json=body)
        if r.status_code != 200:
            return ""
        parts = (((r.json().get("candidates") or [{}])[0].get("content") or {}).get("parts") or [])
        return "".join(p.get("text", "") for p in parts).strip()


def tts(text: str) -> Dict[str, Any]:
    """Render speech with the configured Gemini TTS voice; returns {url, seconds}."""
    from tools.tts_tool import _generate_gemini_tts  # same path the bot uses
    os.makedirs(AUDIO_DIR, exist_ok=True)
    name = "jv_%s.mp3" % uuid.uuid4().hex[:10]
    out = os.path.join(AUDIO_DIR, name)
    cfg = {"provider": "gemini", "gemini": {"voice": "Kore", "model": "gemini-3.8-flash-tts"}}
    asyncio.run(_generate_gemini_tts(text, out, cfg))
    seconds = 0.0
    try:
        import wave
        with wave.open(out):
            pass
    except Exception:  # noqa: BLE001
        pass
    return {"url": "/audio/" + name, "seconds": seconds}


# ------------------------------------------------------------------------------ FastAPI --
from fastapi import FastAPI, Request, Response  # noqa: E402
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse  # noqa: E402

app = FastAPI(title="jarvisd", version=VERSION)


@app.get("/health")
def health():
    return {"ok": True, "version": VERSION, "warm": HUB.warm, "page": 1,
            "sessions": len(HUB.sessions)}


@app.get("/catalog")
def catalog():
    cfg = load_config()
    models = []
    providers = cfg.get("providers") or {}
    for pid, conf in providers.items():
        entries = (conf or {}).get("models")
        if isinstance(entries, dict):
            for mid, meta in entries.items():
                models.append({"provider": pid, "model": mid,
                               "label": (meta or {}).get("display_name") or mid})
        elif isinstance(entries, list):
            for mid in entries:
                models.append({"provider": pid, "model": str(mid), "label": str(mid)})
        elif (conf or {}).get("model"):
            models.append({"provider": pid, "model": conf["model"], "label": conf["model"]})
    aliases = list(((cfg.get("model") or {}).get("aliases") or {}).keys())
    current = ((cfg.get("model") or {}).get("default") or "")
    return {"models": models, "aliases": aliases, "current": current,
            "toolsets": TOOLSETS, "presets": {"fast": FAST_TOOLSETS},
            "reasoning": ["none", "minimal", "low", "medium", "high"],
            "personas": [{"id": "jarvis", "label": "جارویس"}]}


@app.post("/session/new")
async def session_new(req: Request):
    body = await _json(req)
    s = HUB.new(body.get("title", ""))
    s.save()
    return {"session": s.id}


@app.get("/sessions")
def sessions():
    return {"sessions": [
        {"id": s.id, "title": s.title, "turns": s.turn_seq, "busy": s.busy,
         "updated": s.updated, "model": s.model or _default_model()}
        for s in HUB.list() if s.id != "warmup"]}


@app.get("/session/{sid}")
def session_get(sid: str):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    return {"session": s.id, "title": s.title, "model": s.model or _default_model(),
            "reasoning": s.reasoning, "busy": s.busy, "messages": s.messages,
            "seq": s.seq}


@app.post("/session/{sid}/turn")
async def session_turn(sid: str, req: Request):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    body = await _json(req)
    text = (body.get("text") or "").strip()
    if not text:
        return {"ok": False, "error": "empty"}
    if s.busy:
        return JSONResponse({"ok": False, "error": "busy"}, status_code=409)
    loop = asyncio.get_running_loop()
    s.cancel.clear()

    def worker():
        run_turn(s, text)

    # One daemon thread per turn: run_conversation is synchronous and long-lived, and the
    # agent object must stay on the thread that built it.
    threading.Thread(target=_thread_main, args=(loop, worker), name="turn-" + s.id,
                     daemon=True).start()
    return {"ok": True, "seq": s.seq}


def _thread_main(loop, fn):
    fn()


@app.post("/session/{sid}/cancel")
def session_cancel(sid: str):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    s.cancel.set()
    try:
        from agent.async_utils import interrupt_agent  # noqa: F401
    except Exception:  # noqa: BLE001
        pass
    if s.agent is not None:
        try:
            s.agent.interrupt()
        except Exception:  # noqa: BLE001
            pass
    return {"ok": True}


@app.post("/session/{sid}/model")
async def session_model(sid: str, req: Request):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    body = await _json(req)
    s.model = (body.get("model") or "").strip()
    s.agent = None  # rebuild lazily with the new model
    return {"ok": True, "model": s.model}


@app.post("/session/{sid}/reasoning")
async def session_reasoning(sid: str, req: Request):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    body = await _json(req)
    s.reasoning = (body.get("effort") or "none").strip()
    s.agent = None
    return {"ok": True, "reasoning": s.reasoning}


@app.get("/session/{sid}/events")
async def session_events(sid: str, request: Request, from_: int = 0):
    s = HUB.get(sid)
    if not s:
        return JSONResponse({"error": "no such session"}, status_code=404)
    start = from_ if from_ else s.seq

    async def stream():
        q: asyncio.Queue = asyncio.Queue(maxsize=500)
        s.subs.append(q)
        try:
            for ev in s.since(start - 1 if from_ else start):
                yield _sse(ev)
            while True:
                if await request.is_disconnected():
                    break
                try:
                    ev = await asyncio.wait_for(q.get(), timeout=15)
                    yield _sse(ev)
                except asyncio.TimeoutError:
                    yield ": ping\n\n"
        finally:
            try:
                s.subs.remove(q)
            except ValueError:
                pass

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/ask/{ask_id}")
async def ask_answer(ask_id: str, req: Request):
    body = await _json(req)
    ok = answer_ask(ask_id, str(body.get("value", "")))
    return {"ok": ok}


@app.post("/voice/in")
async def voice_in(request: Request):
    wav = await request.body()
    if not wav:
        return {"ok": False, "error": "empty"}
    text = await stt(wav)
    return {"ok": bool(text), "text": text}


@app.post("/voice/out")
async def voice_out(req: Request):
    body = await _json(req)
    text = (body.get("text") or "").strip()
    if not text:
        return {"ok": False, "error": "empty"}
    try:
        info = await asyncio.get_running_loop().run_in_executor(None, tts, text)
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": "%s: %s" % (type(exc).__name__, exc)}
    return dict(ok=True, **info)


@app.get("/audio/{name}")
def audio(name: str):
    if not re.fullmatch(r"jv_[0-9a-f]{10}\.mp3", name):
        return JSONResponse({"error": "bad name"}, status_code=400)
    path = os.path.join(AUDIO_DIR, name)
    if not os.path.isfile(path):
        return JSONResponse({"error": "gone"}, status_code=404)
    return FileResponse(path, media_type="audio/mpeg")


@app.get("/")
def root():
    return {"name": "jarvisd", "version": VERSION, "docs": "/docs"}


async def _json(req: Request) -> Dict[str, Any]:
    try:
        body = await req.json()
        return body if isinstance(body, dict) else {}
    except Exception:  # noqa: BLE001
        return {}


def _default_model() -> str:
    if DEFAULT_MODEL:
        return DEFAULT_MODEL
    cfg = (load_config().get("model") or {})
    cur = cfg.get("default") or ""
    # The gateway's primary (3.8-flash today) 429s on the free tier; the console prefers
    # the fastest model that actually answers, and the app can override per session.
    return cur if cur and "3.8-flash" not in cur else "gemini-3.5-flash-lite"


def _sse(ev: Dict[str, Any]) -> str:
    # The full envelope goes in `data` as well: clients that only parse data (curl, simple
    # HTTP wrappers) then still see kind and seq without tracking the `event:` line.
    return "id: %d\nevent: %s\ndata: %s\n\n" % (ev["seq"], ev["kind"], jdump(ev))


def warm_up() -> None:
    """Build one agent at startup so the first real turn is not the slow one."""
    try:
        s = Session("warmup", "گرم‌کردن")
        HUB.sessions["warmup"] = s
        agent, _ = build_agent(s, _default_model(), FAST_TOOLSETS, "none")
        s.agent = agent
        HUB.warm = True
        print("jarvisd: warm agent ready (%s)" % _default_model(), flush=True)
    except Exception as exc:  # noqa: BLE001
        import traceback
        traceback.print_exc()
        print("jarvisd: warm-up failed: %s" % exc, flush=True)


def main() -> None:
    import uvicorn
    threading.Thread(target=warm_up, name="warmup", daemon=True).start()
    print("jarvisd %s on http://127.0.0.1:%d" % (VERSION, PORT), flush=True)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning", access_log=False)


if __name__ == "__main__":
    main()
