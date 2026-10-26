#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_ops — services, restarts and logs for the console (folded in from Hermes Control).

`hermes-svc` already supervises the whole stack in tmux with restart loops, so this
module is a *read-mostly* view of it plus one guarded write:

    GET  /services                     [{name, state, pid, uptime_s, port, health}]
    POST /services/{name}/restart      {ok}       whitelist: jarvis, gwctl, agy, router
    GET  /logs?name=&n=&grep=          {name, lines[]}

Facts that shaped it (measured on the phone):
  * `hermes-svc list` prints `name state` per known service — the authoritative list.
  * Ports: gwctl :8777, jarvis :8780, agy :8790, router :20128.
  * `~/.hermes/*.log` is where `hermes-svc` redirects each service (`spec()` in the
    script); `tmux capture-pane` is only the fallback.
  * `/proc/uptime` is unreadable from Termux here, so uptime comes from `ps -o etime`.

Import-safe: no file reads, no subprocess, no network at import time.
"""

from __future__ import annotations

# See jarvisd_tools.py: with postponed annotations, FastAPI resolves route annotations in the
# module namespace, so Request/JSONResponse must be module-level names.
from fastapi import Request
from fastapi.responses import JSONResponse

import os
import re
import shutil
import socket
import subprocess
from typing import Any, Dict, List, Tuple

SERVICE_PORTS = {"gwctl": 8777, "jarvis": 8780, "agy": 8790, "router": 20128}
SERVICE_NEEDLES = {"gwctl": "gwctl.py", "jarvis": "jarvisd.py", "agy": "agy_bridge.py",
                   "router": "omniroute", "tgwatch": "telegram-watch", "gw": "gateway run"}
LOG_FILES = {"gwctl": "gwctl.log", "jarvis": "jarvis.log", "agy": "bridge.log",
             "router": "router.log", "gw": "gw-live.log", "tgwatch": "hermes-svc.log",
             "svc": "hermes-svc.log", "services": "hermes-svc.log"}
# The only names a console tap may restart. Anything else is refused, always.
RESTARTABLE = ("jarvis", "gwctl", "agy", "router")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
SECRET_RE = re.compile(
    r"(sk-[A-Za-z0-9]{8,}|AIza[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{10,}"
    r"|Bearer\s+[A-Za-z0-9._-]{8,}|eyJ[A-Za-z0-9._-]{20,})")
TAIL_BYTES = 512 * 1024


# --------------------------------------------------------------------------- helpers --
def _env(ctx) -> Dict[str, str]:
    env = dict(os.environ)
    env["HOME"] = ctx["HOME"]
    prefix = os.environ.get("PREFIX") or "/data/data/com.termux/files/usr"
    env["PATH"] = os.pathsep.join([os.path.join(ctx["HOME"], ".local", "bin"),
                                   os.path.join(prefix, "bin"), env.get("PATH", "")])
    env.setdefault("TERM", "dumb")
    return env


def _run(ctx, argv: List[str], timeout: int = 30) -> Tuple[int, str]:
    """Local command with a closed stdin: nothing here may wait for a tty."""
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                              env=_env(ctx), stdin=subprocess.DEVNULL)
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except FileNotFoundError:
        return 127, "%s not found" % argv[0]
    except subprocess.TimeoutExpired:
        return 124, "timeout after %ss" % timeout
    except OSError as exc:
        return 126, str(exc)


def _redact(line: str) -> str:
    return SECRET_RE.sub("***", line)


def _svc_names(ctx) -> List[Tuple[str, str]]:
    """[(name, state)] straight from `hermes-svc list`."""
    code, out = _run(ctx, ["hermes-svc", "list"], timeout=25)
    rows: List[Tuple[str, str]] = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) >= 2 and parts[1] in ("running", "stopped"):
            rows.append((parts[0], parts[1]))
    if rows:
        return rows
    code, out = _run(ctx, ["tmux", "list-sessions", "-F", "#{session_name}"], timeout=15)
    for line in out.splitlines():
        name = line.strip().split("|")[0]
        if name and NAME_RE.match(name):
            rows.append((name, "running"))
    return rows


def _pane_pid(ctx, name: str) -> int:
    code, out = _run(ctx, ["tmux", "list-panes", "-t", "=" + name, "-F", "#{pane_pid}"],
                     timeout=15)
    for line in out.splitlines():
        try:
            return int(line.strip())
        except ValueError:
            continue
    return 0


def _cmdline(pid: int) -> str:
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as fh:
            return fh.read().replace(b"\x00", b" ").decode("utf-8", "replace")
    except OSError:
        return ""


def _service_pid(ctx, name: str) -> int:
    """The pid of the *service* inside the tmux restart loop, not the loop itself."""
    needle = SERVICE_NEEDLES.get(name, name)
    pane = _pane_pid(ctx, name)
    candidates: List[int] = []
    if pane:
        code, out = _run(ctx, ["pgrep", "-P", str(pane)], timeout=10)
        for line in out.split():
            if line.isdigit():
                candidates.append(int(line))
    for pid in candidates:
        if needle in _cmdline(pid):
            return pid
    if candidates:
        return candidates[-1]
    code, out = _run(ctx, ["pgrep", "-f", needle], timeout=10)
    own = os.getpid()
    for line in out.split():
        if line.isdigit() and int(line) != own:
            return int(line)
    return 0


def _uptime_s(ctx, pid: int) -> int:
    if pid <= 0:
        return 0
    code, out = _run(ctx, ["ps", "-o", "etime=", "-p", str(pid)], timeout=10)
    text = out.strip().splitlines()[0].strip() if out.strip() else ""
    if not text:
        return 0
    days = 0
    if "-" in text:
        head, text = text.split("-", 1)
        days = int(head) if head.isdigit() else 0
    parts = text.split(":")
    try:
        nums = [int(p) for p in parts]
    except ValueError:
        return 0
    while len(nums) < 3:
        nums.insert(0, 0)
    return days * 86400 + nums[0] * 3600 + nums[1] * 60 + nums[2]


def _port_open(port: int, timeout: float = 0.35) -> bool:
    if not port:
        return False
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=timeout):
            return True
    except OSError:
        return False


def _log_path(ctx, name: str) -> str:
    if name.startswith("gw-"):
        profile = name[3:]
        if NAME_RE.match(profile):
            return os.path.join(ctx["HERMES"], "profiles", profile, "gw-live.log")
        return ""
    rel = LOG_FILES.get(name)
    return os.path.join(ctx["HERMES"], rel) if rel else ""


def _tail(path: str, n: int) -> List[str]:
    try:
        size = os.path.getsize(path)
        with open(path, "rb") as fh:
            if size > TAIL_BYTES:
                fh.seek(size - TAIL_BYTES)
            raw = fh.read()
    except OSError:
        return []
    lines = raw.decode("utf-8", "replace").splitlines()
    if size > TAIL_BYTES and lines:
        lines = lines[1:]  # the first line is probably a fragment of a longer one
    return lines[-n:]


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901
    from fastapi.responses import JSONResponse

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    @app.get("/services")
    def services():
        try:
            rows: List[Dict[str, Any]] = []
            names = _svc_names(ctx)
            if not names:
                return err("unsupported", "hermes-svc is not installed on this phone")
            for name, state in names:
                port = SERVICE_PORTS.get(name, 0)
                pid = _service_pid(ctx, name) if state == "running" else 0
                open_now = _port_open(port) if port else None
                if port:
                    health = "ok" if open_now else "down"
                else:
                    health = "ok" if state == "running" else "down"
                row = {"name": name, "state": state, "pid": pid,
                       "uptime_s": _uptime_s(ctx, pid), "port": port, "health": health,
                       "port_open": open_now, "restartable": name in RESTARTABLE}
                if name.startswith("gw-") and not port:
                    profile = name[3:]
                    row["profile"] = profile
                rows.append(row)
            return rows
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/services/{name}/restart")
    def restart(name: str):
        if not NAME_RE.match(name):
            return err("bad_name", name)
        if name not in RESTARTABLE:
            return err("not_allowed",
                       "%s is not restartable from the console (allowed: %s)"
                       % (name, ", ".join(RESTARTABLE)), 403)
        if not shutil.which("hermes-svc"):
            return err("unsupported", "hermes-svc is not installed on this phone", 501)
        if name == "jarvis":
            # Restarting the daemon we are answering from: let the HTTP response leave
            # first, then hand the restart to a detached shell that outlives us.
            shell = shutil.which("sh") or "/system/bin/sh"
            try:
                subprocess.Popen([shell, "-c", "sleep 1; exec hermes-svc restart jarvis"],
                                 stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                 stderr=subprocess.DEVNULL, env=_env(ctx),
                                 start_new_session=True)
            except OSError as exc:
                return err("spawn_failed", str(exc), 500)
            ctx["notify"]("ops: jarvis restart scheduled by console")
            return {"ok": True, "restarting": True, "name": name, "detail": "restart in ~1s"}
        code, out = _run(ctx, ["hermes-svc", "restart", name], timeout=90)
        ctx["notify"]("ops: restarted %s rc=%s" % (name, code))
        return {"ok": code == 0, "name": name,
                "detail": _redact(" ".join(out.split()))[:300]}

    @app.get("/logs")
    def logs(name: str = "jarvis", n: int = 200, grep: str = ""):
        try:
            if not NAME_RE.match(name or ""):
                return err("bad_name", name)
            try:
                want = int(n)
            except (TypeError, ValueError):
                want = 200
            want = max(1, min(want, 5000))
            path = _log_path(ctx, name)
            source = "file"
            lines = _tail(path, want) if path else []
            if not lines:
                source = "tmux"
                code, out = _run(ctx, ["tmux", "capture-pane", "-p", "-t", "=" + name,
                                       "-S", "-%d" % want], timeout=20)
                lines = out.splitlines()[-want:]
                if code != 0 and not lines:
                    return err("not_found", "no log file and no tmux session for %s" % name,
                               404)
            lines = [_redact(ln.rstrip()) for ln in lines]
            needle = (grep or "").strip().lower()
            if needle:
                lines = [ln for ln in lines if needle in ln.lower()]
            return {"name": name, "lines": lines, "source": source}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)
