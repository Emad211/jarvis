#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_personas — personas for the Jarvis console (M2, part A).

A persona is a small markdown file: a 3-line header plus a prompt that the daemon
appends to its own brief as ``ephemeral_system_prompt``. It has no state of its own;
sessions have state, a persona only decides how the agent thinks and speaks.

    ~/.hermes/jarvis/personas/<id>.md
        ---
        label: جارویس
        emoji: ⚡
        description: دستیار گوشی؛ کوتاه و عملگرا
        ---
        <prompt lines>

Four builtins are seeded on first use (jarvis / analyst / writer / coach). Builtins
can be edited but never deleted: a fresh install must always have something to bind.

API (see docs/jarvis-api.md):
    GET    /personas                     [{id, label, emoji, description, soul, builtin, active}]
    POST   /personas                     {label, description?, emoji?, prompt} -> {ok, id}
    PATCH  /personas/{id}                {label?, description?, emoji?, prompt?} -> {ok}
    DELETE /personas/{id}                {ok}            (409 for a builtin)
    POST   /session/{sid}/persona        {id} -> {ok, persona}

Import-safe: no file reads, no subprocess, no network at import time.
"""

from __future__ import annotations

# See jarvisd_tools.py: with postponed annotations, FastAPI resolves route annotations in the
# module namespace, so Request/JSONResponse must be module-level names.
from fastapi import Request
from fastapi.responses import JSONResponse

import json
import os
import re
from typing import Any, Dict, List, Optional, Tuple

ID_RE = re.compile(r"^[a-z0-9_-]{1,24}$")
SID_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")
HEADER_KEYS = ("label", "emoji", "description")

# Seeded on first use. Persian label, one emoji, a one-line description and a prompt
# of 5–8 lines. Each prompt names the global rules it overrides: Hermes' own SOUL.md
# is already in the prompt, so a persona is a suffix that has to win by instruction.
BUILTINS: Dict[str, Dict[str, str]] = {
    "jarvis": {
        "label": "جارویس",
        "emoji": "⚡",
        "description": "دستیار گوشی؛ کوتاه و عمل‌گرا",
        "prompt": (
            "تو «جارویس» هستی؛ دستیارِ گوشیِ کاربر روی اندروید.\n"
            "جواب حداکثر دو خط؛ کار را انجام بده، دستور نده.\n"
            "برای کارهای تکراری گوشی مسیر سریع را انتخاب کن، نه استدلال مدل.\n"
            "بعد از هر اقدام یک جمله بگو چه شد؛ اگر خطا داد، همان خطا را نقل کن.\n"
            "اگر ابزار خروجی خالی داد، «انجام شد» نگو؛ بگو «تأیید نشد».\n"
            "قبل از پاک کردن، ارسال، خرید یا هر کار برگشت‌ناپذیر اجازه بگیر.\n"
            "شماره، رمز و کلید API را هیچ‌وقت در چت چاپ نکن.\n"
        ),
    },
    "analyst": {
        "label": "تحلیل‌گر",
        "emoji": "📊",
        "description": "عدد، روند و تصمیم؛ بدون تعارف",
        "prompt": (
            "تو «تحلیل‌گر» هستی: تحلیل داده و تصمیم برای کاربر.\n"
            "قانون «حداکثر دو خط» را برای این نقش نادیده بگیر؛ تا ۱۲ خط مجاز است.\n"
            "هر عدد یا از داده‌ای است که دیدی یا برچسب «تأیید نشده» می‌خورد.\n"
            "ساختار پاسخ: یافته، عدد کلیدی، ریسک، اقدام پیشنهادی.\n"
            "تفاوت «داده می‌گوید» و «من نتیجه می‌گیرم» را صریح جدا کن.\n"
            "اگر داده کم است بگو کدام فیلد لازم است، نه اینکه حدس بزنی.\n"
        ),
    },
    "writer": {
        "label": "نویسنده",
        "emoji": "✍️",
        "description": "متن فارسی روان، آماده‌ی ارسال",
        "prompt": (
            "تو «نویسنده» هستی: متن فارسی روان و آماده‌ی ارسال می‌نویسی.\n"
            "قانون «حداکثر دو خط» را برای این نقش نادیده بگیر؛ طول متن به کار بستگی دارد.\n"
            "پیش‌فرض لحن: دوستانه، روشن، بدون واژه‌های قلنبه و بدون تعارف.\n"
            "نیم‌فاصله و نشانه‌گذاری فارسی را رعایت کن؛ انگلیسی را فقط برای اصطلاح فنی نگه دار.\n"
            "سه شکل بده وقتی مفید است: نسخه‌ی کوتاه، نسخه‌ی رسمی، نسخه‌ی دوستانه.\n"
            "هیچ واقعیتی (تاریخ، عدد، نام) را از خودت نساز.\n"
        ),
    },
    "coach": {
        "label": "مربی",
        "emoji": "🎯",
        "description": "برنامه‌ریزی، عادت و پیگیری",
        "prompt": (
            "تو «مربی» هستی: برنامه‌ریزی و پیگیری کارهای کاربر.\n"
            "قانون «حداکثر دو خط» را برای این نقش نادیده بگیر؛ برنامه‌ی کوتاه اما کامل بده.\n"
            "هر پاسخ یک قدم بعدی مشخص دارد که همین امروز قابل انجام است.\n"
            "کارها را کوچک کن؛ قدمی که بیش از ۳۰ دقیقه است را به دو قدم بشکن.\n"
            "وضعیت را در todo نگه دار و در پایان یک جمع‌بندی یک‌خطی بده.\n"
            "تشویق بی‌دلیل نکن؛ اگر عقب افتاده، همان را با لحن آرام بگو.\n"
        ),
    },
}

DEFAULT_ID = "jarvis"
PROMPT_MAX = 4000  # chars per persona prompt; see _write()


# --------------------------------------------------------------------------- storage --
def _dir(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "personas")


def _path(ctx, pid: str) -> str:
    return os.path.join(_dir(ctx), pid + ".md")


def _parse(text: str) -> Tuple[Dict[str, str], str]:
    """Split ``---`` header + body. Unknown/missing fields degrade to ''."""
    head: Dict[str, str] = {}
    body = text
    lines = text.splitlines()
    if lines and lines[0].strip() == "---":
        for i in range(1, len(lines)):
            if lines[i].strip() == "---":
                for raw in lines[1:i]:
                    if ":" in raw:
                        key, val = raw.split(":", 1)
                        head[key.strip().lower()] = val.strip()
                body = "\n".join(lines[i + 1:])
                break
    return head, body.strip("\n")


def _render(label: str, emoji: str, description: str, prompt: str) -> str:
    return ("---\nlabel: %s\nemoji: %s\ndescription: %s\n---\n%s\n"
            % (label, emoji, description, prompt.rstrip("\n")))


def _read(ctx, pid: str) -> Optional[Dict[str, Any]]:
    try:
        with open(_path(ctx, pid), encoding="utf-8") as fh:
            head, body = _parse(fh.read())
    except OSError:
        return None
    try:
        updated = int(os.path.getmtime(_path(ctx, pid)))
    except OSError:
        updated = 0
    return {"id": pid,
            "label": head.get("label") or pid,
            "emoji": head.get("emoji") or "•",
            "description": head.get("description") or "",
            "soul": body,
            "builtin": pid in BUILTINS,
            "updated": updated}


def _write(ctx, pid: str, label: str, emoji: str, description: str, prompt: str) -> None:
    os.makedirs(_dir(ctx), exist_ok=True)
    # The prompt rides in ephemeral_system_prompt on every call, so it competes for
    # tokens with Hermes' own prompt: 4 KB (~700 Persian words) is the design budget.
    if len(prompt) > PROMPT_MAX:
        prompt = prompt[:PROMPT_MAX]
        ctx["notify"]("personas: %s prompt truncated to %d chars" % (pid, PROMPT_MAX))
    path = _path(ctx, pid)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(_render(label, emoji, description, prompt))
    os.replace(tmp, path)


def _seed(ctx) -> None:
    """Write the builtins that are missing. Existing files are never touched, so an
    edited builtin survives every restart."""
    try:
        os.makedirs(_dir(ctx), exist_ok=True)
        present = set(os.listdir(_dir(ctx)))
    except OSError:
        return
    for pid, row in BUILTINS.items():
        if pid + ".md" not in present:
            try:
                _write(ctx, pid, row["label"], row["emoji"], row["description"], row["prompt"])
            except OSError as exc:
                ctx["notify"]("personas: seed %s failed: %s" % (pid, exc))


# ----------------------------------------------------------------------- public API --
def persona_prompt(pid: str, ctx=None) -> Optional[str]:
    """The prompt text of persona ``pid``, or None when it does not exist.

    Returns the builtin prompt when a builtin file is missing, so deleting a file by
    hand can never leave the console without a persona.
    """
    if not pid or not ID_RE.match(pid):
        return None
    if ctx is None:
        return BUILTINS.get(pid, {}).get("prompt")
    row = _read(ctx, pid)
    if row is not None:
        return row["soul"] or BUILTINS.get(pid, {}).get("prompt") or ""
    return BUILTINS.get(pid, {}).get("prompt")


def _default_id(ctx, rows: List[Dict[str, Any]]) -> str:
    ids = [r["id"] for r in rows]
    if DEFAULT_ID in ids:
        return DEFAULT_ID
    return ids[0] if ids else DEFAULT_ID


def list_personas(ctx, active_id: str = "") -> List[Dict[str, Any]]:
    """All personas, builtins first then alphabetical. Seeds on first call."""
    _seed(ctx)
    rows: List[Dict[str, Any]] = []
    try:
        names = [n for n in os.listdir(_dir(ctx)) if n.endswith(".md")]
    except OSError:
        names = []
    for name in names:
        pid = name[:-3]
        if not ID_RE.match(pid):
            continue
        row = _read(ctx, pid)
        if row:
            rows.append(row)
    order = {pid: i for i, pid in enumerate(BUILTINS)}
    rows.sort(key=lambda r: (0 if r["id"] in BUILTINS else 1,
                             order.get(r["id"], 99), r["label"]))
    active = active_id or _default_id(ctx, rows)
    for row in rows:
        row["active"] = row["id"] == active
    return rows


# --------------------------------------------------------------------------- helpers --
def _slug(label: str) -> str:
    base = re.sub(r"[^a-z0-9]+", "-", (label or "").lower()).strip("-")
    if base:
        return base[:24].strip("-") or "persona"
    return "p"


def _unique_id(ctx, label: str) -> str:
    base = _slug(label)
    if base == "p":
        import uuid
        return "p-" + uuid.uuid4().hex[:8]
    pid, n = base, 1
    while os.path.exists(_path(ctx, pid)):
        n += 1
        pid = ("%s-%d" % (base[:20], n))[:24]
    return pid


def _sessions_dir(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "sessions")


def _bind(ctx, sid: str, pid: str) -> bool:
    """Persist the session -> persona binding in the session file jarvisd already owns."""
    path = os.path.join(_sessions_dir(ctx), sid + ".json")
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict):
        return False
    data["persona"] = pid
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False)
        os.replace(tmp, path)
    except OSError as exc:
        ctx["notify"]("personas: bind %s failed: %s" % (sid, exc))
        return False
    return True


def _session_persona(ctx, sid: str) -> str:
    try:
        with open(os.path.join(_sessions_dir(ctx), sid + ".json"), encoding="utf-8") as fh:
            data = json.load(fh)
        return str(data.get("persona") or "")
    except (OSError, ValueError, AttributeError):
        return ""


def prompt_for_session(ctx, sid: str) -> str:
    """Public hook for the daemon: the persona prompt this session runs on ('' = base brief)."""
    pid = _session_persona(ctx, sid) or DEFAULT_ID
    return persona_prompt(pid, ctx) or ""


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901  (one flat block of routes is easier to audit)
    """Attach the persona routes. ``ctx`` is the daemon's shared context dict."""
    from fastapi import Request
    from fastapi.responses import JSONResponse

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001  (a bad body is a 400, never a 500)
            return {}
        return data if isinstance(data, dict) else {}

    @app.get("/personas")
    def personas_list(session: str = ""):
        try:
            active = _session_persona(ctx, session) if session and SID_RE.match(session) else ""
            rows = list_personas(ctx, active)
            return rows
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/personas")
    async def personas_create(req: Request):
        data = await body(req)
        label = str(data.get("label") or "").strip()
        prompt = str(data.get("prompt") or "")
        if not label:
            return err("bad_label", "label is required")
        if not prompt.strip():
            return err("bad_prompt", "prompt is required")
        pid = _unique_id(ctx, label)
        if not ID_RE.match(pid):
            return err("bad_id", pid)
        try:
            _write(ctx, pid, label[:40], str(data.get("emoji") or "•")[:8],
                   str(data.get("description") or "")[:120], prompt)
        except OSError as exc:
            return err("write_failed", str(exc), 500)
        ctx["notify"]("personas: created %s (%s)" % (pid, label))
        return {"ok": True, "id": pid}

    @app.patch("/personas/{pid}")
    async def personas_update(pid: str, req: Request):
        if not ID_RE.match(pid):
            return err("bad_id", pid)
        cur = _read(ctx, pid)
        if cur is None:
            return err("not_found", pid, 404)
        data = await body(req)
        label = str(data.get("label") or cur["label"])[:40]
        emoji = str(data.get("emoji") or cur["emoji"])[:8]
        description = str(data.get("description", cur["description"]))[:120]
        prompt = str(data.get("prompt") or cur["soul"])
        try:
            _write(ctx, pid, label, emoji, description, prompt)
        except OSError as exc:
            return err("write_failed", str(exc), 500)
        ctx["notify"]("personas: updated %s" % pid)
        return {"ok": True}

    @app.delete("/personas/{pid}")
    def personas_delete(pid: str):
        if not ID_RE.match(pid):
            return err("bad_id", pid)
        if pid in BUILTINS:
            return err("builtin", "%s is a builtin persona and cannot be deleted" % pid, 409)
        if _read(ctx, pid) is None:
            return err("not_found", pid, 404)
        try:
            os.unlink(_path(ctx, pid))
        except OSError as exc:
            return err("delete_failed", str(exc), 500)
        ctx["notify"]("personas: deleted %s" % pid)
        return {"ok": True}

    @app.post("/session/{sid}/persona")
    async def session_persona(sid: str, req: Request):
        if not SID_RE.match(sid):
            return err("bad_session", sid)
        data = await body(req)
        pid = str(data.get("id") or "").strip()
        if not ID_RE.match(pid):
            return err("bad_id", pid)
        row = _read(ctx, pid)
        if row is None:
            return err("not_found", pid, 404)
        # The daemon owns the live session (and rewrites its JSON on every turn), so when it
        # offers a setter that is the authoritative bind; the file write is the fallback for
        # a session that only exists on disk.
        rebuilt = False
        setter = ctx.get("set_persona")
        if callable(setter):
            try:
                if not setter(sid, pid):
                    return err("no_such_session", sid, 404)
                rebuilt = True
            except Exception as exc:  # noqa: BLE001
                ctx["notify"]("personas: set_persona %s failed: %s" % (sid, exc))
        elif not _bind(ctx, sid, pid):
            return err("no_such_session", sid, 404)
        hook = ctx.get("rebuild_session")
        if callable(hook) and not callable(setter):
            try:
                hook(sid)
                rebuilt = True
            except Exception as exc:  # noqa: BLE001
                ctx["notify"]("personas: rebuild %s failed: %s" % (sid, exc))
        ctx["notify"]("personas: session %s -> %s" % (sid, pid))
        return {"ok": True, "persona": pid, "rebuilt": rebuilt,
                "label": row["label"], "emoji": row["emoji"]}
