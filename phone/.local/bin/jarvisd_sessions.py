#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_sessions — delete and prune conversations.

The console accumulated ~30 test sessions and there was no way to remove one: every JSON file
under ~/.hermes/jarvis/sessions/ is re-loaded into the in-memory `HUB` on the next request, so
unlinking alone would resurrect it, and clearing `HUB` alone would leave the file behind.

    DELETE /session/{sid}             -> {ok, id, deleted}            (409 when it must not go)
    POST   /sessions/prune {keep: N}  -> {ok, keep, kept[], deleted[], skipped[]}

Two things are never deleted without `force`:
  * a session that is currently running a turn (`session.busy` — the daemon would then write
    its answer into a file nobody reads);
  * a session the app is bound to. `~/.hermes/jarvis/bot_sessions.json` maps *session -> bot*
    for every bot the console opened, so its keys are exactly the conversations that are live
    in the UI. The most recently updated session is protected too, because that is what the
    console reopens on the next launch.

Import-safe: no file reads, no network and no daemon import at import time ("__main__" is
looked up lazily, when a request arrives).
"""

from __future__ import annotations

import json
import os
import re
from typing import Any, Dict, List, Optional, Set

# FastAPI resolves route annotations in the module namespace (postponed annotations), so these
# two must be module-level names or the request bodies arrive as query parameters.
from fastapi import Request
from fastapi.responses import JSONResponse

SID_RE = re.compile(r"^[A-Za-z0-9_-]{4,64}$")


# --------------------------------------------------------------------------- daemon glue --
def _daemon() -> Any:
    """The running jarvisd module.

    The daemon is started as a script (`python jarvisd.py`), so its module object is
    `sys.modules["__main__"]`; under a test harness it is importable as `jarvisd`. Either way
    this is the only handle on the live `HUB`, and reaching it lazily keeps the import clean.
    """
    import sys
    mod = sys.modules.get("jarvisd") or sys.modules.get("__main__")
    if mod is not None and hasattr(mod, "HUB"):
        return mod
    try:
        import jarvisd  # noqa: PLC0415
        return jarvisd
    except Exception:  # noqa: BLE001
        return None


def _hub() -> Any:
    mod = _daemon()
    return getattr(mod, "HUB", None) if mod is not None else None


def _sessions_dir(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "sessions")


def _session_file(ctx, sid: str) -> str:
    return os.path.join(_sessions_dir(ctx), sid + ".json")


def _bound_sessions(ctx) -> Set[str]:
    """Session ids the console is bound to (bot_sessions.json is session -> bot)."""
    path = os.path.join(ctx["JARVIS_DIR"], "bot_sessions.json")
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return {str(k) for k in data} if isinstance(data, dict) else set()
    except (OSError, ValueError):
        return set()


def _on_disk(ctx) -> Dict[str, float]:
    """{sid: updated} for every session file, newest first by value."""
    out: Dict[str, float] = {}
    try:
        names = os.listdir(_sessions_dir(ctx))
    except OSError:
        return out
    for name in names:
        if not name.endswith(".json"):
            continue
        sid = name[:-5]
        if not SID_RE.match(sid):
            continue
        try:
            out[sid] = os.path.getmtime(os.path.join(_sessions_dir(ctx), name))
        except OSError:
            continue
    return out


def _live(ctx) -> List[Dict[str, Any]]:
    """Every known session with its updated/busy flag, from HUB when available."""
    hub = _hub()
    rows: List[Dict[str, Any]] = []
    seen: Set[str] = set()
    if hub is not None:
        try:
            for session in hub.list():
                seen.add(session.id)
                rows.append({"id": session.id, "updated": float(session.updated or 0),
                             "busy": bool(getattr(session, "busy", False)),
                             "live": True})
        except Exception:  # noqa: BLE001
            pass
    for sid, mtime in _on_disk(ctx).items():
        if sid not in seen:
            rows.append({"id": sid, "updated": float(mtime), "busy": False, "live": False})
    rows.sort(key=lambda r: r["updated"], reverse=True)
    return rows


def _busy(ctx, sid: str) -> bool:
    """True while a turn is running: deleting that session would make the daemon write its
    answer into a file nobody will read again, so this is refused even with `force`."""
    hub = _hub()
    if hub is None:
        return False
    try:
        session = hub.get(sid)
        return bool(getattr(session, "busy", False)) if session is not None else False
    except Exception:  # noqa: BLE001
        return False


def _drop(ctx, sid: str, keep_file: bool = False) -> Dict[str, Any]:
    """Remove one session from HUB and from disk. Returns {removed_hub, removed_file}."""
    removed_hub = False
    hub = _hub()
    if hub is not None:
        with hub.lock:
            removed_hub = hub.sessions.pop(sid, None) is not None
    removed_file = False
    if not keep_file:
        for path in (_session_file(ctx, sid), _session_file(ctx, sid) + ".tmp"):
            try:
                os.unlink(path)
                removed_file = True
            except FileNotFoundError:
                pass
            except OSError as exc:
                ctx["notify"]("sessions: cannot delete %s: %s" % (path, exc))
    return {"removed_hub": removed_hub, "removed_file": removed_file}


def _forget_binding(ctx, sid: str) -> None:
    path = os.path.join(ctx["JARVIS_DIR"], "bot_sessions.json")
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except (OSError, ValueError):
        return
    if not isinstance(data, dict) or sid not in data:
        return
    data.pop(sid, None)
    try:
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=1)
        os.replace(tmp, path)
    except OSError as exc:
        ctx["notify"]("sessions: binding cleanup failed: %s" % exc)


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901
    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001
            return {}
        return data if isinstance(data, dict) else {}

    def why_protected(sid: str, rows: List[Dict[str, Any]], newest: str) -> str:
        if _busy(ctx, sid):
            return "busy: a turn is running"
        if sid in _bound_sessions(ctx):
            return "bound to a bot in the console"
        if sid == newest:
            return "most recent session (the console reopens it)"
        return ""

    @app.delete("/session/{sid}")
    def session_delete(sid: str, force: int = 0):
        if not SID_RE.match(sid):
            return err("bad_session", sid)
        rows = _live(ctx)
        known = {r["id"] for r in rows}
        if sid not in known:
            return err("not_found", sid, 404)
        if _busy(ctx, sid):
            return err("busy", "%s is running a turn; cancel it first" % sid, 409)
        newest = rows[0]["id"] if rows else ""
        reason = why_protected(sid, rows, newest)
        if reason and not force:
            return err("protected", "%s: %s" % (sid, reason), 409)
        result = _drop(ctx, sid)
        if not result["removed_hub"] and not result["removed_file"]:
            return err("delete_failed", "%s could not be removed" % sid, 500)
        if force:
            _forget_binding(ctx, sid)
        ctx["notify"]("sessions: deleted %s%s" % (sid, " (forced)" if force else ""))
        return {"ok": True, "id": sid, "deleted": True, "forced": bool(force),
                "overrode": reason, "removed_hub": result["removed_hub"],
                "removed_file": result["removed_file"]}

    @app.post("/sessions/prune")
    async def sessions_prune(req: Request):
        data = await body(req)
        try:
            keep = int(data.get("keep", 10))
        except (TypeError, ValueError):
            return err("bad_keep", "keep must be an integer")
        if keep < 1:
            return err("bad_keep", "keep must be >= 1: the console's current session is the "
                                   "most recent one, so a prune that keeps nothing would "
                                   "pull it out from under the app")
        keep = min(keep, 500)
        force = bool(data.get("force"))
        rows = _live(ctx)
        newest = rows[0]["id"] if rows else ""
        kept = [r["id"] for r in rows[:keep]]
        deleted: List[str] = []
        skipped: List[Dict[str, str]] = []
        for row in rows[keep:]:
            sid = row["id"]
            if _busy(ctx, sid):
                skipped.append({"id": sid, "reason": "busy: a turn is running"})
                continue
            reason = why_protected(sid, rows, newest)
            if reason and not force:
                skipped.append({"id": sid, "reason": reason})
                continue
            result = _drop(ctx, sid)
            if result["removed_hub"] or result["removed_file"]:
                deleted.append(sid)
                if force and sid in _bound_sessions(ctx):
                    _forget_binding(ctx, sid)
            else:
                skipped.append({"id": sid, "reason": "nothing to delete"})
        ctx["notify"]("sessions: pruned %d, kept %d" % (len(deleted), len(kept)))
        return {"ok": True, "keep": keep, "total_before": len(rows),
                "kept": kept, "deleted": deleted, "skipped": skipped,
                "total_after": len(_live(ctx))}
