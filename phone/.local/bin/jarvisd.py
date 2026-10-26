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
sys.path.insert(0, os.path.join(HOME, ".local", "bin"))
os.chdir(HOME)

import jarvis_fast as fast  # noqa: E402  (deterministic device actions, no model)

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
        # The free tier answers 429 in bursts; the chain turns that into a slower answer
        # instead of an error message.
        fallback_model=_fallback_chain(),
        stream_delta_callback=on_delta,
        thinking_callback=on_think,
        tool_start_callback=on_tool_start,
        tool_complete_callback=on_tool_done,
        tool_progress_callback=on_progress,
        status_callback=on_status,
        clarify_callback=on_clarify,
    )
    return agent, on_approval


def _fallback_chain() -> Optional[List[Dict[str, str]]]:
    """The configured fallback providers, as AIAgent.fallback_model expects them."""
    cfg = load_config()
    entries = []
    for item in cfg.get("fallback_providers") or []:
        if isinstance(item, dict) and item.get("provider") and item.get("model"):
            entries.append({"provider": str(item["provider"]), "model": str(item["model"])})
    return entries or None


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
    # The agent starts every turn from the history we hand it: without this the console
    # would forget the previous message ("my name is Emad" ... "what is my name?").
    history = [{"role": m.get("role"), "content": m.get("text")}
               for m in session.messages
               if m.get("role") in ("user", "assistant") and m.get("text")]
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
        result = agent.run_conversation(user_message=text, conversation_history=history,
                                        task_id="jv-%s-%d" % (session.id, turn_id))
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
            "chips": fast.CHIPS,
            "personas": [{"id": "jarvis", "label": "جارویس"}]}


@app.post("/fast")
async def fast_run(req: Request):
    """Deterministic device actions: no model, ~100-300 ms on this phone."""
    body = await _json(req)
    intent = str(body.get("intent") or "")
    args = body.get("args") if isinstance(body.get("args"), dict) else {}
    loop = asyncio.get_running_loop()
    res = await loop.run_in_executor(None, fast.run, intent, args)
    return res


@app.get("/media/{name}")
def media(name: str):
    if not re.fullmatch(r"jv_[0-9a-f]{10,16}\.png", name):
        return JSONResponse({"error": "bad name"}, status_code=400)
    path = os.path.join(os.path.expanduser("~/shots"), name)
    if not os.path.isfile(path):
        return JSONResponse({"error": "gone"}, status_code=404)
    return FileResponse(path, media_type="image/png")


# ------------------------------------------------------------------------------- setup --
# First-run path for a stranger's phone. The app never receives an existing key: it only
# learns whether one is present, and it can write a new one. Writing goes through the same
# env-file rules as gwctl (`*_API_KEY` / `*_TOKEN`, empty deletes).
PROVIDERS = [
    {"id": "google_ai_studio", "label": "Google AI Studio", "key_env": "GEMINI_API_KEY",
     "key_url": "https://aistudio.google.com/apikey", "voice": True,
     "models": [
         {"id": "gemini-3.5-flash-lite", "label": "Gemini 3.5 Flash Lite (fastest)", "tools": True, "vision": True},
         {"id": "gemini-3.5-flash", "label": "Gemini 3.5 Flash", "tools": True, "vision": True},
         {"id": "gemini-2.5-flash", "label": "Gemini 2.5 Flash", "tools": True, "vision": True},
     ]},
    {"id": "openrouter", "label": "OpenRouter", "key_env": "OPENROUTER_API_KEY",
     "key_url": "https://openrouter.ai/keys", "voice": False,
     "models": [
         {"id": "nvidia/nemotron-3-ultra-550b-a55b:free", "label": "Nemotron 3 Ultra (free)",
          "tools": True, "vision": False},
     ]},
    {"id": "agentrouter", "label": "AgentRouter", "key_env": "AGENTROUTER_API_KEY",
     "key_url": "https://agentrouter.org", "voice": False,
     "models": [
         {"id": "gpt-6.1-sol", "label": "GPT 6.1 Sol", "tools": True, "vision": False},
     ]},
]
KEY_ENV_OK = re.compile(r"^[A-Z0-9_]*(_API_KEY|_TOKEN)$")
ENV_PATH = os.path.join(HERMES, ".env")


def _provider(pid: str) -> Optional[Dict[str, Any]]:
    for p in PROVIDERS:
        if p["id"] == pid:
            return p
    return None


def _env_write(name: str, value: str) -> None:
    """Replace or append one KEY=value line, atomically, mode 600. Empty deletes it."""
    lines: List[str] = []
    had = False
    if os.path.exists(ENV_PATH):
        with open(ENV_PATH, encoding="utf-8") as fh:
            for line in fh:
                if line.split("=", 1)[0].strip() == name:
                    had = True
                    continue
                lines.append(line if line.endswith("\n") else line + "\n")
    if value:
        lines.append("%s=%s\n" % (name, value))
    tmp = ENV_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.writelines(lines)
    os.chmod(tmp, 0o600)
    os.replace(tmp, ENV_PATH)
    if not had and not value:
        return


@app.get("/setup/state")
def setup_state():
    cfg = load_config()
    providers = []
    for p in PROVIDERS:
        providers.append(dict(p, has_key=bool(read_env(p["key_env"])),
                              key_length=len(read_env(p["key_env"]))))
    model = cfg.get("model") or {}
    return {"ok": True, "port": PORT, "version": VERSION,
            "needs_setup": not any(p["has_key"] for p in providers),
            "config": {"exists": os.path.exists(os.path.join(HERMES, "config.yaml")),
                       "provider": model.get("provider", ""),
                       "model": model.get("default", "")},
            "runtime": {"warm": HUB.warm, "sessions": len(HUB.sessions)},
            "providers": providers}


@app.post("/setup/key")
async def setup_key(req: Request):
    body = await _json(req)
    p = _provider(str(body.get("provider") or ""))
    if not p:
        return JSONResponse({"ok": False, "error": "unknown_provider"}, status_code=400)
    key = str(body.get("key") or "").strip()
    if not key:
        return JSONResponse({"ok": False, "error": "empty_key"}, status_code=400)
    if any(c.isspace() for c in key):
        return JSONResponse({"ok": False, "error": "key_has_whitespace"}, status_code=400)
    if "'" in key or '"' in key:
        return JSONResponse({"ok": False, "error": "key_has_quotes"}, status_code=400)
    if not KEY_ENV_OK.match(p["key_env"]):
        return JSONResponse({"ok": False, "error": "key_env_not_allowed"}, status_code=403)
    try:
        _env_write(p["key_env"], key)
    except OSError as exc:
        return JSONResponse({"ok": False, "error": "env_write_failed", "detail": str(exc)},
                            status_code=500)
    # the warm agent captured the old environment; drop it so the next turn re-reads
    for s in HUB.sessions.values():
        s.agent = None
    return {"ok": True, "provider": p["id"], "key_env": p["key_env"],
            "key_length": len(key), "agents_cleared": True}


@app.post("/setup/test")
async def setup_test(req: Request):
    """One tiny call to the provider with the stored key. Never echoes the key."""
    body = await _json(req)
    p = _provider(str(body.get("provider") or ""))
    if not p:
        return {"ok": False, "status": 0, "error": "unknown_provider"}
    key = read_env(p["key_env"])
    if not key:
        return {"ok": False, "status": 0, "error": "no_key"}
    model = str(body.get("model") or p["models"][0]["id"])
    started = now()
    try:
        import httpx
        if p["id"] == "google_ai_studio":
            url = ("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions")
            headers = {"Authorization": "Bearer " + key}
            payload = {"model": model, "max_tokens": 16,
                       "messages": [{"role": "user", "content": "reply with: ok"}]}
        elif p["id"] == "openrouter":
            url = "https://openrouter.ai/api/v1/chat/completions"
            headers = {"Authorization": "Bearer " + key}
            payload = {"model": model, "max_tokens": 16,
                       "messages": [{"role": "user", "content": "reply with: ok"}]}
        else:  # agentrouter talks the Anthropic messages shape
            url = "https://agentrouter.org/v1/messages"
            headers = {"x-api-key": key, "anthropic-version": "2023-06-01",
                       "User-Agent": "claude-cli/1.0.0 (external, cli)"}
            payload = {"model": model, "max_tokens": 16,
                       "messages": [{"role": "user", "content": "reply with: ok"}]}
        async with httpx.AsyncClient(timeout=45) as client:
            r = await client.post(url, headers=headers, json=payload)
        ms = int((now() - started) * 1000)
        if r.status_code != 200:
            kind = "quota" if r.status_code == 429 else ("auth" if r.status_code in (401, 403)
                                                         else "bad_model" if r.status_code == 404
                                                         else "http_%d" % r.status_code)
            return {"ok": False, "status": r.status_code, "ms": ms, "error": kind,
                    "detail": r.text[:200]}
        return {"ok": True, "status": 200, "ms": ms, "reply": r.text[:200], "provider": p["id"]}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "status": 0, "error": "network",
                "detail": "%s: %s" % (type(exc).__name__, exc)}


@app.post("/setup/model")
async def setup_model(req: Request):
    body = await _json(req)
    provider = str(body.get("provider") or "")
    model = str(body.get("model") or "")
    if not provider or not model:
        return JSONResponse({"ok": False, "error": "provider and model required"},
                            status_code=400)
    cfg = load_config()
    previous = (cfg.get("model") or {})
    try:
        import shutil
        backup_dir = os.path.join(HERMES, "hcontrol-backups")
        os.makedirs(backup_dir, exist_ok=True)
        shutil.copy2(os.path.join(HERMES, "config.yaml"),
                     os.path.join(backup_dir, "config-%d.yaml" % int(now())))
    except OSError:
        pass
    code, out = _hermes("config", "set", "model.provider", provider)
    code2, out2 = _hermes("config", "set", "model.default", model)
    for s in HUB.sessions.values():
        s.agent = None
    return {"ok": code == 0 and code2 == 0, "provider": provider, "model": model,
            "previous": {"provider": previous.get("provider", ""),
                         "model": previous.get("default", "")},
            "restart_required": False, "detail": (out + " " + out2)[-200:]}


def _hermes(*args: str) -> tuple:
    import subprocess
    try:
        p = subprocess.run(["hermes", *args], capture_output=True, text=True, timeout=60,
                           env=dict(os.environ, HOME=HOME))
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return 127, str(exc)


# ------------------------------------------------------------------- custom providers --
# "Any provider, in any shape": the app posts a url + a key + a wire format and this
# registers it in two places — our own registry (what the UI lists) and Hermes' config
# (what the agent actually uses). Writes go through `hermes config set`, never by editing
# YAML ourselves, so backups/validation stay Hermes' job.
CUSTOM_PATH = os.path.join(HERMES, "jarvis", "providers.json")
KINDS = {
    "openai":    {"api_mode": "chat_completions", "hint": "OpenAI-compatible /chat/completions"},
    "anthropic": {"api_mode": "anthropic_messages", "hint": "Anthropic /v1/messages"},
    "gemini":    {"api_mode": "chat_completions", "hint": "Gemini OpenAI-compatible /v1beta/openai"},
}


def custom_providers() -> List[Dict[str, Any]]:
    try:
        with open(CUSTOM_PATH, encoding="utf-8") as fh:
            data = json.load(fh)
        return [p for p in data if isinstance(p, dict) and p.get("id")]
    except (OSError, ValueError):
        return []


def save_custom(rows: List[Dict[str, Any]]) -> None:
    os.makedirs(os.path.dirname(CUSTOM_PATH), exist_ok=True)
    tmp = CUSTOM_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(rows, fh, ensure_ascii=False, indent=1)
    os.replace(tmp, CUSTOM_PATH)


def _slug(text: str) -> str:
    s = re.sub(r"[^a-z0-9]+", "-", (text or "").lower()).strip("-")
    return s[:32] or "provider"


@app.get("/providers")
def providers_list():
    rows = []
    for p in PROVIDERS:
        rows.append({"id": p["id"], "label": p["label"], "builtin": True,
                     "kind": "gemini" if p["id"] == "google_ai_studio" else "openai",
                     "base_url": "", "models": [m["id"] for m in p["models"]],
                     "key_env": p["key_env"], "has_key": bool(read_env(p["key_env"]))})
    for p in custom_providers():
        rows.append({"id": p["id"], "label": p.get("label", p["id"]), "builtin": False,
                     "kind": p.get("kind", "openai"), "base_url": p.get("base_url", ""),
                     "models": p.get("models") or [], "key_env": p.get("key_env", ""),
                     "has_key": bool(read_env(p.get("key_env", ""))),
                     "headers": p.get("headers") or {}})
    return {"providers": rows, "kinds": [{"id": k, "hint": v["hint"]} for k, v in KINDS.items()]}


@app.post("/providers")
async def providers_add(req: Request):
    body = await _json(req)
    label = str(body.get("label") or "").strip()
    base_url = str(body.get("base_url") or "").strip().rstrip("/")
    kind = str(body.get("kind") or "openai").strip()
    key = str(body.get("key") or "").strip()
    models = body.get("models") or []
    if isinstance(models, str):
        models = [m.strip() for m in re.split(r"[,\n]", models) if m.strip()]
    models = [str(m).strip() for m in models if str(m).strip()]
    headers = body.get("headers") if isinstance(body.get("headers"), dict) else {}
    if not label or not base_url:
        return JSONResponse({"ok": False, "error": "label and base_url required"},
                            status_code=400)
    if kind not in KINDS:
        return JSONResponse({"ok": False, "error": "unknown_kind"}, status_code=400)
    if not re.match(r"^https?://", base_url):
        return JSONResponse({"ok": False, "error": "base_url must be http(s)"}, status_code=400)
    pid = _slug(body.get("id") or label)
    key_env = str(body.get("key_env") or "").strip().upper()
    if not key_env:
        key_env = re.sub(r"[^A-Z0-9]+", "_", pid.upper()) + "_API_KEY"
    if not KEY_ENV_OK.match(key_env):
        return JSONResponse({"ok": False, "error": "key_env_not_allowed"}, status_code=403)

    if key:
        try:
            _env_write(key_env, key)
        except OSError as exc:
            return JSONResponse({"ok": False, "error": "env_write_failed", "detail": str(exc)},
                                status_code=500)

    rows = [p for p in custom_providers() if p.get("id") != pid]
    rows.append({"id": pid, "label": label, "kind": kind, "base_url": base_url,
                 "key_env": key_env, "models": models,
                 "headers": {str(k): str(v) for k, v in headers.items()}})
    save_custom(rows)

    # Hermes: keep its own config authoritative for the agent, one scalar at a time.
    api_mode = KINDS[kind]["api_mode"]
    _hermes("config", "set", "providers.%s.name" % pid, label)
    _hermes("config", "set", "providers.%s.key_env" % pid, key_env)
    if api_mode == "anthropic_messages":
        _hermes("config", "set", "providers.%s.api_mode" % pid, api_mode)
        _hermes("config", "set", "providers.%s.base_url" % pid, base_url)
    else:
        _hermes("config", "set", "providers.%s.transport" % pid, "chat_completions")
        _hermes("config", "set", "providers.%s.api" % pid, base_url)
    if models:
        _hermes("config", "set", "providers.%s.models" % pid, json.dumps(models))
        _hermes("config", "set", "providers.%s.default_model" % pid, models[0])
    if headers:
        _hermes("config", "set", "providers.%s.extra_headers" % pid,
                json.dumps({str(k): str(v) for k, v in headers.items()}))
    for s in HUB.sessions.values():
        s.agent = None
    return {"ok": True, "id": pid, "key_env": key_env, "models": models,
            "has_key": bool(read_env(key_env)), "agents_cleared": True}


@app.delete("/providers/{pid}")
async def providers_delete(pid: str):
    rows = [p for p in custom_providers() if p.get("id") != pid]
    save_custom(rows)
    _hermes("config", "unset", "providers.%s" % pid)
    for s in HUB.sessions.values():
        s.agent = None
    return {"ok": True, "id": pid}


@app.post("/providers/test")
async def providers_test(req: Request):
    """One tiny chat against a custom provider, using whatever wire format it declares."""
    body = await _json(req)
    pid = str(body.get("id") or "")
    row = next((p for p in custom_providers() if p.get("id") == pid), None)
    if not row:
        return {"ok": False, "error": "unknown_provider"}
    key = read_env(row.get("key_env", ""))
    if not key:
        return {"ok": False, "error": "no_key"}
    model = str(body.get("model") or (row.get("models") or [""])[0])
    if not model:
        return {"ok": False, "error": "no_model"}
    kind = row.get("kind", "openai")
    headers = dict(row.get("headers") or {})
    started = now()
    try:
        import httpx
        async with httpx.AsyncClient(timeout=45) as client:
            if kind == "anthropic":
                url = row["base_url"]
                if not url.endswith("/messages"):
                    url = url + "/messages"
                h = dict(headers, **{"x-api-key": key, "anthropic-version": "2023-06-01",
                                     "Content-Type": "application/json"})
                payload = {"model": model, "max_tokens": 16,
                           "messages": [{"role": "user", "content": "reply with: ok"}]}
            else:
                url = row["base_url"]
                if not url.endswith("/chat/completions"):
                    url = url + "/chat/completions"
                h = dict(headers, **{"Authorization": "Bearer " + key,
                                     "Content-Type": "application/json"})
                payload = {"model": model, "max_tokens": 16,
                           "messages": [{"role": "user", "content": "reply with: ok"}]}
            r = await client.post(url, headers=h, json=payload)
        ms = int((now() - started) * 1000)
        if r.status_code != 200:
            return {"ok": False, "status": r.status_code, "ms": ms,
                    "error": "http_%d" % r.status_code, "detail": r.text[:300]}
        return {"ok": True, "status": 200, "ms": ms, "reply": r.text[:200], "model": model}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "status": 0, "error": "network",
                "detail": "%s: %s" % (type(exc).__name__, exc)}


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

    # A plain-language command that maps to a device action never wakes the model: the
    # user gets the answer in the same seconds as a chip tap.
    routed = fast.route(text)
    if routed and body.get("fast", True):
        def worker():
            run_fast_turn(s, text, routed)
    else:
        def worker():
            run_turn(s, text)

    # One daemon thread per turn: run_conversation is synchronous and long-lived, and the
    # agent object must stay on the thread that built it.
    threading.Thread(target=_thread_main, args=(loop, worker), name="turn-" + s.id,
                     daemon=True).start()
    return {"ok": True, "seq": s.seq, "fast": bool(routed)}


def run_fast_turn(session: Session, text: str, routed: Dict[str, Any]) -> None:
    """Answer a routed command with the fast path, shaped like a one-tool agent turn."""
    session.busy = True
    session.turn_seq += 1
    turn_id = session.turn_seq
    started = now()
    session.emit("turn.start", {"turn": turn_id, "text": text[:4000], "mode": "fast"})
    session.messages.append({"role": "user", "text": text, "ts": started})
    label = routed.get("label") or routed.get("intent", "")
    call_id = "fast_%d" % int(started * 1000)
    session.emit("tool.start", {"id": call_id, "name": "fast:" + str(routed.get("intent")),
                                "args": _short(routed.get("args"), 200)})
    try:
        res = fast.run(str(routed.get("intent")), routed.get("args") or {})
        ok = bool(res.get("ok"))
        detail = res.get("detail") or ("انجام شد" if ok else "نشد")
        session.emit("tool.end", {"id": call_id, "name": "fast:" + str(routed.get("intent")),
                                  "ok": ok, "result": _short(detail, 400)})
        reply = (label + ": " + detail) if ok else (label + " نشد — " + detail)
        session.emit("text.delta", {"text": reply})
        session.messages.append({"role": "assistant", "text": reply, "ts": now(),
                                 "ms": int(res.get("ms") or 0), "fast": True})
        session.emit("turn.end", {"turn": turn_id, "ms": int((now() - started) * 1000),
                                  "reply": reply, "fast": True,
                                  "media": res.get("media", "")})
    except Exception as exc:  # noqa: BLE001
        session.emit("error", {"text": "%s: %s" % (type(exc).__name__, exc)})
        session.emit("turn.end", {"turn": turn_id, "ms": int((now() - started) * 1000),
                                  "reply": "", "failed": True})
    finally:
        session.busy = False
        session.updated = now()


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
