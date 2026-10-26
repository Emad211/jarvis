#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_bots — a bot is a real Hermes profile, bound to sessions (M2.5).

Why profiles
------------
Measured on this phone (2026-10-26, `~/.tmp_bots_probe*.py`): Hermes resolves *everything*
that makes an assistant distinct from ``get_hermes_home()``:

    system_prompt.py:383-390   SOUL.md            -> <home>/SOUL.md      (identity)
    tools/memory_tool.py:64    memory             -> <home>/memories/    (MEMORY.md, USER.md)
    system_prompt.py:314       skills index       -> <home>/skills/
    hermes_state.py:362+       session history    -> <home>/state.db
    cron                       scheduled jobs     -> <home>/cron/jobs.json

and ``hermes_constants.set_hermes_home_override()`` re-points that resolution *per thread*
(``get_hermes_home()`` -> ContextVar -> ``HERMES_HOME`` env -> default). Two probes proved the
consequences:

    session_db=bot/state.db only   -> bot SOUL *and* the owner's SOUL in the prompt, memory global
    override set in the turn thread -> bot SOUL only, bot memories, bot state.db, skills from bot

So a bot is a directory: ``~/.hermes/profiles/<id>/`` created by ``hermes profile create``, and
isolation costs one context manager per turn. ``HERMES_PROFILE`` is *not* a home selector (it is
used for secret scoping and cron naming), and ``agent_init`` has no ``home=`` parameter — this is
the only supported lever.

Wiring (owned by jarvisd.py, three lines inside the turn's worker thread — ContextVars do not
cross ``threading.Thread``, so it *must* be there):

    import jarvisd_bots
    with jarvisd_bots.turn_scope(CTX, session):        # env override + model/preset defaults
        ...  existing body of run_turn(session, text) ...

and in ``POST /session/new``: ``jarvisd_bots.stamp_new_session(CTX, sid)`` so a new session
inherits the default bot. Nothing else in the daemon has to know that bots exist: the scope makes
``AIAgent``'s own ``SessionDB``, memory tool and prompt builder resolve inside the bot's home.

API (same shape as the personas module)
    GET    /bots                          [{id,label,emoji,persona,model,provider,preset,
                                            sessions,cron,isolated,default,active}]
    GET    /bots/{id}                     one row
    POST   /bots                          {label, emoji?, persona?, model?, provider?,
                                           preset?, skills?} -> {ok, id}
    PATCH  /bots/{id}                     same fields -> {ok, bot}
    DELETE /bots/{id}[?force=1]           {ok}  (409 if it owns sessions unless force=1)
    POST   /session/{sid}/bot             {id} -> {ok, bot, rebuilt}
    GET    /bots/{id}/stats               {sessions, last_used, memory_facts, skills, cron_jobs}

Storage: ``~/.hermes/jarvis/bots.json`` (registry) and ``~/.hermes/jarvis/bot_sessions.json``
(session -> bot bindings). The bindings live here on purpose: ``Session.save()`` does not know
about bots, so a field written into the session JSON would be dropped on the next turn.

Import-safe: no file reads, no subprocess, no network at import time.
"""

from __future__ import annotations

# With postponed annotations FastAPI resolves route annotations in the module namespace, so
# Request/JSONResponse must be module-level names (see jarvisd_tools.py for the same note).
from fastapi import Request
from fastapi.responses import JSONResponse

import contextlib
import json
import os
import re
import shutil
import threading
import time
from typing import Any, Dict, Iterator, List, Optional

# Hermes profile names are lowercase alphanumeric (see `hermes profile create --help`); keep the
# bot id inside that set so a bot id is always a valid profile name and a valid directory name.
ID_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,19}$")
SID_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")
PRESETS = ("fast", "smart", "full")
DEFAULT_BOT = "main"

# The bot's SOUL.md is the *whole* identity (the owner's ~/.hermes/SOUL.md is not in scope for a
# bot), so the safety invariants that used to come from it have to be restated here.
SOUL_TAIL = (
    "\n---\n"
    "این هویت تو است؛ اگر متن دیگری در ادامه با آن تناقض داشت، این بخش مقدم است.\n"
    "قواعد ثابت: نتیجه را از خروجی ابزار نقل کن و چیزی از خودت نساز؛ کلید، رمز و توکن را "
    "هیچ‌وقت چاپ نکن؛ اگر کاری را نتوانستی بررسی کنی، بگو «تأیید نشده».\n"
)
DEFAULT_PERSONA_TEXT = (
    "تو یک دستیار مستقل روی گوشی کاربر هستی.\n"
    "فارسی و کوتاه جواب بده؛ کار را انجام بده، دستور نده.\n"
)

_LOCK = threading.Lock()


# ---------------------------------------------------------------------------- storage --
def _dir(ctx) -> str:
    return str(ctx["JARVIS_DIR"])


def _registry_path(ctx) -> str:
    return os.path.join(_dir(ctx), "bots.json")


def _bindings_path(ctx) -> str:
    return os.path.join(_dir(ctx), "bot_sessions.json")


def _profiles_root(ctx) -> str:
    return os.path.join(str(ctx["HERMES"]), "profiles")


def _read_json(path: str, default: Any) -> Any:
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return data
    except (OSError, ValueError):
        return default


def _write_json(path: str, data: Any) -> None:
    tmp = path + ".tmp"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    os.replace(tmp, path)


def _main_bot() -> Dict[str, Any]:
    """The default bot: the owner's own profile, no override, nothing isolated."""
    return {"id": DEFAULT_BOT, "label": "جارویس", "emoji": "⚡", "persona": "",
            "model": "", "provider": "", "preset": "", "profile": "", "home": "",
            "created": 0, "updated": 0}


def _load(ctx) -> Dict[str, Any]:
    """Registry as ``{"default": id, "bots": {id: row}}``; seeded with the main bot."""
    data = _read_json(_registry_path(ctx), None)
    if not isinstance(data, dict) or not isinstance(data.get("bots"), dict) or not data["bots"]:
        data = {"default": DEFAULT_BOT, "bots": {DEFAULT_BOT: _main_bot()}}
        try:
            _write_json(_registry_path(ctx), data)
        except OSError:
            pass
    bots = data["bots"]
    if DEFAULT_BOT not in bots:
        bots[DEFAULT_BOT] = _main_bot()
    for row in bots.values():
        row.setdefault("persona", "")
        row.setdefault("model", "")
        row.setdefault("provider", "")
        row.setdefault("preset", "")
        row.setdefault("profile", "")
        row.setdefault("home", "")
        row.setdefault("emoji", "•")
    default = str(data.get("default") or DEFAULT_BOT)
    if default not in bots:
        default = DEFAULT_BOT
    data["default"] = default
    return data


def _save(ctx, data: Dict[str, Any]) -> None:
    _write_json(_registry_path(ctx), data)


def _sessions_dir(ctx) -> str:
    return os.path.join(_dir(ctx), "sessions")


def _session_file(ctx, sid: str) -> str:
    return os.path.join(_sessions_dir(ctx), sid + ".json")


def _bindings(ctx) -> Dict[str, str]:
    data = _read_json(_bindings_path(ctx), {})
    if not isinstance(data, dict):
        return {}
    return {str(k): str(v) for k, v in data.items() if isinstance(v, str)}


def _save_bindings(ctx, data: Dict[str, str]) -> None:
    _write_json(_bindings_path(ctx), data)


# ----------------------------------------------------------------------------- helpers --
def _slug(label: str) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", (label or "").lower()).strip("-")
    return (base[:20].strip("-") or "")


def _unique_id(ctx, data: Dict[str, Any], label: str) -> str:
    base = _slug(label)
    if not base:
        base = "bot"
    rid, n = base, 1
    while rid in data["bots"] or os.path.exists(os.path.join(_profiles_root(ctx), rid)):
        n += 1
        rid = ("%s%d" % (base[:17], n))[:20]
    return rid


def _preset_ok(value: str) -> bool:
    if not value:
        return True
    if value in PRESETS:
        return True
    # jarvisd also accepts a csv list of raw toolset names (preset_toolsets()).
    return "," in value and all(p.strip() for p in value.split(","))


def _bot_dir_ok(home: str) -> bool:
    return bool(home) and os.path.isdir(home)


# ------------------------------------------------------------------------ profile make --
def _write_soul(home: str, label: str, persona_text: str) -> None:
    path = os.path.join(home, "SOUL.md")
    body = "# %s\n\n%s%s" % (label or "bot", persona_text.strip() + "\n", SOUL_TAIL)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(body)
    os.replace(tmp, path)


def _persona_id(bid: str) -> str:
    return ("bot-" + bid)[:24]


def _write_persona_file(ctx, pid: str, label: str, emoji: str, text: str) -> bool:
    """Keep a copy of the bot's voice in the personas directory.

    jarvisd appends ``persona_prompt(session.persona or "jarvis")`` to the brief, so a bot that
    did not own a persona file would be spoken to as «جارویس» from the *last* layer — which beats
    its own SOUL (measured: bot «نویسنده» introduced itself as Jarvis). The bot's persona id is
    pointed at this copy, so both layers say the same thing and the SOUL keeps precedence.
    """
    path = os.path.join(_dir(ctx), "personas", pid + ".md")
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        body = ("---\nlabel: %s\nemoji: %s\ndescription: %s\n---\n%s\n"
                % (label[:40], (emoji or "•")[:8], ("bot %s" % pid)[:120], text.strip()))
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(body)
        os.replace(tmp, path)
        return True
    except OSError as exc:
        ctx["notify"]("bots: persona file %s failed: %s" % (pid, exc))
        return False


def _skeleton(ctx, bid: str, clone: bool) -> str:
    """Fallback when `hermes profile create` is unavailable: build the home by hand."""
    home = os.path.join(_profiles_root(ctx), bid)
    os.makedirs(home, exist_ok=True)
    for sub in ("memories", "cron", "sessions", "logs", "workspace"):
        os.makedirs(os.path.join(home, sub), exist_ok=True)
    hermes_home = str(ctx["HERMES"])
    for name in ("config.yaml", ".env"):
        src = os.path.join(hermes_home, name)
        dst = os.path.join(home, name)
        if os.path.isfile(src) and not os.path.exists(dst):
            try:
                shutil.copyfile(src, dst)
            except OSError:
                pass
    if clone:
        src = os.path.join(hermes_home, "skills")
        dst = os.path.join(home, "skills")
        if os.path.isdir(src) and not os.path.isdir(dst):
            try:
                shutil.copytree(src, dst)
            except OSError:
                pass
    else:
        os.makedirs(os.path.join(home, "skills"), exist_ok=True)
    return home


def _ensure_profile(ctx, bid: str, clone: bool = True) -> str:
    """`~/.hermes/profiles/<bid>` via the CLI, with a hand-built fallback. Idempotent."""
    home = os.path.join(_profiles_root(ctx), bid)
    if os.path.isdir(home):
        return home
    runner = ctx.get("hermes")
    if callable(runner):
        args = ["profile", "create", bid, "--no-alias"]
        args.append("--clone" if clone else "--no-skills")
        try:
            code, out = runner(*args)
            if code != 0:
                ctx["notify"]("bots: profile create %s -> %s: %s" % (bid, code, out.strip()[:200]))
        except Exception as exc:  # noqa: BLE001
            ctx["notify"]("bots: profile create %s failed: %s" % (bid, exc))
    if not os.path.isdir(home):
        home = _skeleton(ctx, bid, clone)
    return home


def _profile_exists(home: str) -> bool:
    return bool(home) and os.path.isfile(os.path.join(home, "SOUL.md"))


# -------------------------------------------------------------------------- public API --
def default_bot_id(ctx) -> str:
    return str(_load(ctx).get("default") or DEFAULT_BOT)


def get_bot(ctx, bid: str) -> Optional[Dict[str, Any]]:
    if not isinstance(bid, str) or not ID_RE.match(bid):
        return None
    return (_load(ctx)["bots"]).get(bid)


def list_bots(ctx, active: str = "") -> List[Dict[str, Any]]:
    data = _load(ctx)
    counts = _session_counts(ctx)
    rows: List[Dict[str, Any]] = []
    for bid, row in data["bots"].items():
        out = dict(row)
        out["sessions"] = counts.get(bid, 0)
        out["cron"] = _cron_count(row.get("home") or "")
        out["isolated"] = bool(row.get("home")) and _profile_exists(row.get("home") or "")
        out["default"] = bid == data["default"]
        out["active"] = bid == (active or data["default"])
        rows.append(out)
    rows.sort(key=lambda r: (0 if r["id"] == data["default"] else 1, r["label"]))
    return rows


def bot_for_session(ctx, sid: str) -> Dict[str, Any]:
    """The bot this session runs on. Unknown/vanished/broken -> the default bot."""
    data = _load(ctx)
    bid = _bindings(ctx).get(sid, "")
    if not bid:
        try:
            with open(_session_file(ctx, sid), encoding="utf-8") as fh:
                mirror = json.load(fh)
            if isinstance(mirror, dict):
                bid = str(mirror.get("bot") or "")
        except (OSError, ValueError):
            bid = ""
    row = data["bots"].get(bid) if bid else None
    if not row:
        row = data["bots"][data["default"]]
    if row.get("profile") and not _bot_dir_ok(row.get("home") or ""):
        # The profile was removed behind our back: degrade to the main home rather than
        # running the bot's sessions against a directory that no longer exists.
        ctx["notify"]("bots: %s home missing (%s); falling back to the main profile"
                      % (row["id"], row.get("home")))
        row = dict(row)
        row["home"] = ""
    return row


def apply_to_session(ctx, session, bot: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Point a live session at its bot: model/preset defaults + a forced rebuild on change.

    Only fills a field the session has not set itself, so an explicit model pick in the app
    survives; ``bind_session`` is the call that *overwrites* those choices.
    """
    bot = bot or bot_for_session(ctx, session.id)
    if not getattr(session, "model", ""):
        session.model = str(bot.get("model") or "")
    if not getattr(session, "toolsets", ""):
        session.toolsets = str(bot.get("preset") or "")
    if bot["id"] != DEFAULT_BOT and bot.get("persona"):
        # jarvisd appends `persona_prompt(session.persona or "jarvis")` *after* the SOUL; without
        # this, the last layer re-christens every bot «جارویس» (measured before the fix).
        session.persona = str(bot["persona"])
    key = (bot["id"], session.model, session.toolsets, bot.get("home") or "")
    if getattr(session, "_bot_key", None) != key:
        session._bot_key = key
        session.agent = None
    try:
        session.bot = bot["id"]
    except Exception:  # noqa: BLE001  (a future Session without a bot slot)
        pass
    return bot


@contextlib.contextmanager
def turn_scope(ctx, session) -> Iterator[Dict[str, Any]]:
    """Make one turn resolve inside the session's bot home, then put the thread back.

    Must be entered **in the worker thread that runs the turn**: Hermes stores the override in a
    ContextVar, and ContextVars do not propagate into ``threading.Thread``.
    """
    bot = apply_to_session(ctx, session)
    home = str(bot.get("home") or "")
    if not home or not _bot_dir_ok(home):
        yield bot
        return
    import hermes_constants as hc
    token = hc.set_hermes_home_override(home)
    try:
        yield bot
    finally:
        hc.reset_hermes_home_override(token)


def bind_session(ctx, sid: str, bid: str, clear: bool = True) -> bool:
    """Bind ``sid`` to ``bid``: registry, in-memory session, and a rebuild request."""
    bot = get_bot(ctx, bid)
    if bot is None:
        return False
    with _LOCK:
        bindings = _bindings(ctx)
        bindings[sid] = bid
        try:
            _save_bindings(ctx, bindings)
        except OSError as exc:
            ctx["notify"]("bots: bind %s -> %s failed: %s" % (sid, bid, exc))
            return False
    _mirror_into_session_file(ctx, sid, bid)
    session = _live_session(sid)
    if session is not None:
        # An explicit bind overwrites the session's own model/preset choices: that is the
        # point of switching to a different assistant.
        session.model = str(bot.get("model") or "")
        session.toolsets = str(bot.get("preset") or "")
        if bid != DEFAULT_BOT and bot.get("persona"):
            session.persona = str(bot["persona"])
        session._bot_key = None
        try:
            session.bot = bid
        except Exception:  # noqa: BLE001
            pass
        if clear:
            session.agent = None
        if hasattr(session, "save"):
            try:
                session.save()
            except Exception:  # noqa: BLE001
                pass
    if clear and session is None:
        hook = ctx.get("rebuild_session")
        if callable(hook):
            try:
                hook(sid)
            except Exception as exc:  # noqa: BLE001
                ctx["notify"]("bots: rebuild %s failed: %s" % (sid, exc))
    return True


def stamp_new_session(ctx, sid: str) -> str:
    """Give a brand-new session the default bot. Called by POST /session/new."""
    bid = default_bot_id(ctx)
    bind_session(ctx, sid, bid, clear=False)
    return bid


def create_bot(ctx, spec: Dict[str, Any]) -> Dict[str, Any]:
    """Create the registry row and the profile home. Raises ValueError on bad input."""
    label = str(spec.get("label") or "").strip()
    if not label:
        raise ValueError("bad_label")
    preset = str(spec.get("preset") or "").strip()
    if not _preset_ok(preset):
        raise ValueError("bad_preset")
    persona = str(spec.get("persona") or "").strip()
    persona_text = _persona_text(ctx, persona) if persona else DEFAULT_PERSONA_TEXT
    clone = str(spec.get("skills") or "clone").lower() != "none"
    want = str(spec.get("id") or "").strip()
    if want and not ID_RE.match(want):
        raise ValueError("bad_id")
    with _LOCK:
        data = _load(ctx)
        if want:
            if want in data["bots"]:
                raise ValueError("duplicate_id")
            bid = want
        else:
            bid = _unique_id(ctx, data, label)
        home = _ensure_profile(ctx, bid, clone)
        _write_soul(home, label, persona_text)
        pid = _persona_id(bid)
        _write_persona_file(ctx, pid, label, str(spec.get("emoji") or "🤖"), persona_text)
        now = time.time()
        data["bots"][bid] = {"id": bid, "label": label[:32],
                             "emoji": str(spec.get("emoji") or "🤖")[:8],
                             "persona": pid, "model": str(spec.get("model") or ""),
                             "provider": str(spec.get("provider") or ""), "preset": preset,
                             "profile": bid, "home": home,
                             "created": now, "updated": now}
        _save(ctx, data)
    ctx["notify"]("bots: created %s (%s) at %s" % (bid, label, home))
    return data["bots"][bid]


def update_bot(ctx, bid: str, spec: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    with _LOCK:
        data = _load(ctx)
        row = data["bots"].get(bid)
        if row is None:
            return None
        if "label" in spec and str(spec["label"]).strip():
            row["label"] = str(spec["label"]).strip()[:32]
        if "emoji" in spec and str(spec["emoji"]).strip():
            row["emoji"] = str(spec["emoji"]).strip()[:8]
        for field in ("model", "provider"):
            if field in spec:
                row[field] = str(spec[field] or "")
        if "preset" in spec:
            preset = str(spec["preset"] or "")
            if not _preset_ok(preset):
                raise ValueError("bad_preset")
            row["preset"] = preset
        if "persona" in spec:
            source = str(spec["persona"] or "").strip()
            pid = _persona_id(bid)
            text = _persona_text(ctx, source) if source else DEFAULT_PERSONA_TEXT
            row["persona"] = pid
            home = row.get("home") or ""
            if home and os.path.isdir(home):
                _write_soul(home, row["label"], text)
            _write_persona_file(ctx, pid, row["label"], row["emoji"], text)
        row["updated"] = time.time()
        _save(ctx, data)
    # Every session bound to this bot will rebuild on its next turn (the preset/model changed).
    for sid in _sessions_for(ctx, bid):
        session = _live_session(sid)
        if session is not None:
            session._bot_key = None
    return row


def delete_bot(ctx, bid: str, force: bool = False) -> tuple:
    """(ok, error_code). Refuses the default bot and any bot that still owns sessions."""
    with _LOCK:
        data = _load(ctx)
        row = data["bots"].get(bid)
        if row is None:
            return False, "not_found"
        if bid == data["default"]:
            return False, "is_default"
        owned = _sessions_for(ctx, bid)
        if owned and not force:
            return False, "has_sessions"
        del data["bots"][bid]
        try:
            _save(ctx, data)
        except OSError:
            return False, "write_failed"
    bindings = _bindings(ctx)
    changed = False
    for sid in owned:
        if bindings.get(sid) == bid:
            bindings.pop(sid, None)
            changed = True
    if changed:
        try:
            _save_bindings(ctx, bindings)
        except OSError:
            pass
    ctx["notify"]("bots: deleted %s (sessions=%d, profile kept at %s)"
                  % (bid, len(owned), row.get("home") or "-"))
    return True, ""


def bot_stats(ctx, bot: Dict[str, Any]) -> Dict[str, Any]:
    sessions = _sessions_for(ctx, bot["id"])
    last = 0.0
    for sid in sessions:
        try:
            last = max(last, os.path.getmtime(_session_file(ctx, sid)))
        except OSError:
            pass
    home = str(bot.get("home") or "")
    facts = _memory_facts(home)
    return {"bot": bot["id"], "sessions": len(sessions), "last_used": last,
            "memory_facts": facts, "skills": _skill_count(home),
            "cron_jobs": _cron_count(home), "isolated": bool(home) and _profile_exists(home),
            "home": home}


# ---------------------------------------------------------------------------- internals --
def _persona_text(ctx, pid: str) -> str:
    """Reuse the personas module so a bot and a session persona share one vocabulary."""
    if not pid:
        return DEFAULT_PERSONA_TEXT
    try:
        import jarvisd_personas
        text = jarvisd_personas.persona_prompt(pid, ctx)
        if text:
            return text
    except Exception as exc:  # noqa: BLE001
        ctx["notify"]("bots: persona %s unresolved: %s" % (pid, exc))
    return DEFAULT_PERSONA_TEXT


def _live_session(sid: str):
    try:
        import jarvisd
        return jarvisd.HUB.get(sid)
    except Exception:  # noqa: BLE001  (standalone import, tests)
        return None


def _mirror_into_session_file(ctx, sid: str, bid: str) -> None:
    """Best-effort `bot` field in the session JSON, for the app and for /sessions listing."""
    path = _session_file(ctx, sid)
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        if not isinstance(data, dict) or data.get("bot") == bid:
            return
        data["bot"] = bid
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False)
        os.replace(tmp, path)
    except (OSError, ValueError):
        return


def _session_counts(ctx) -> Dict[str, int]:
    counts: Dict[str, int] = {}
    for bid in _bindings(ctx).values():
        counts[bid] = counts.get(bid, 0) + 1
    return counts


def _sessions_for(ctx, bid: str) -> List[str]:
    return [sid for sid, value in _bindings(ctx).items() if value == bid]


def _memory_facts(home: str) -> int:
    if not home:
        return 0
    total = 0
    for name in ("MEMORY.md", "USER.md"):
        try:
            with open(os.path.join(home, "memories", name), encoding="utf-8") as fh:
                total += sum(1 for line in fh if line.lstrip().startswith(("- ", "* ")))
        except OSError:
            pass
    return total


def _skill_count(home: str) -> int:
    if not home:
        return 0
    root = os.path.join(home, "skills")
    n = 0
    for _dirpath, _dirnames, filenames in os.walk(root):
        n += sum(1 for f in filenames if f == "SKILL.md")
    return n


def _cron_count(home: str) -> int:
    if not home:
        return 0
    data = _read_json(os.path.join(home, "cron", "jobs.json"), {})
    if isinstance(data, dict):
        jobs = data.get("jobs")
        if isinstance(jobs, list):
            return len(jobs)
    return 0


# ----------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901  (one flat block of routes is easier to audit)
    """Attach the bot routes. ``ctx`` is the daemon's shared context dict."""

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001  (a bad body is a 400, never a 500)
            return {}
        return data if isinstance(data, dict) else {}

    def row_out(row: Dict[str, Any]) -> Dict[str, Any]:
        out = dict(row)
        out["cron"] = _cron_count(row.get("home") or "")
        out["sessions"] = len(_sessions_for(ctx, row["id"]))
        out["isolated"] = bool(row.get("home")) and _profile_exists(row.get("home") or "")
        return out

    @app.get("/bots")
    def bots_list(session: str = ""):
        try:
            active = bot_for_session(ctx, session)["id"] if session and SID_RE.match(session) else ""
            return list_bots(ctx, active)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.get("/bots/{bid}")
    def bots_get(bid: str):
        row = get_bot(ctx, bid)
        if row is None:
            return err("not_found", bid, 404)
        return row_out(row)

    @app.post("/bots")
    async def bots_create(req: Request):
        spec = await body(req)
        try:
            row = create_bot(ctx, spec)
        except ValueError as exc:
            return err(str(exc), "check label/preset", 400)
        except OSError as exc:
            return err("write_failed", str(exc), 500)
        return {"ok": True, "id": row["id"], "bot": row_out(row)}

    @app.patch("/bots/{bid}")
    async def bots_patch(bid: str, req: Request):
        if not ID_RE.match(bid):
            return err("bad_id", bid)
        spec = await body(req)
        try:
            row = update_bot(ctx, bid, spec)
        except ValueError as exc:
            return err(str(exc), "check preset", 400)
        except OSError as exc:
            return err("write_failed", str(exc), 500)
        if row is None:
            return err("not_found", bid, 404)
        return {"ok": True, "bot": row_out(row)}

    @app.delete("/bots/{bid}")
    def bots_delete(bid: str, force: int = 0):
        if not ID_RE.match(bid):
            return err("bad_id", bid)
        ok, code = delete_bot(ctx, bid, force=bool(force))
        if ok:
            return {"ok": True}
        status = 404 if code == "not_found" else (409 if code != "write_failed" else 500)
        return err(code, bid, status)

    @app.post("/session/{sid}/bot")
    async def session_bot(sid: str, req: Request):
        if not SID_RE.match(sid):
            return err("bad_session", sid)
        data = await body(req)
        bid = str(data.get("id") or "").strip()
        if not ID_RE.match(bid):
            return err("bad_id", bid)
        row = get_bot(ctx, bid)
        if row is None:
            return err("not_found", bid, 404)
        if not bind_session(ctx, sid, bid):
            return err("bind_failed", sid, 500)
        ctx["notify"]("bots: session %s -> %s" % (sid, bid))
        return {"ok": True, "bot": row_out(row), "rebuilt": True}

    @app.get("/bots/{bid}/stats")
    def bots_stats(bid: str):
        row = get_bot(ctx, bid)
        if row is None:
            return err("not_found", bid, 404)
        try:
            return bot_stats(ctx, row)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)
