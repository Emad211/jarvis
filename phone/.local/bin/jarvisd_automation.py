#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_automation — "when X happens, do Y" for the Jarvis console.

    GET    /automations                 [{id, name, trigger, action, enabled, pending,
                                          last_run, last_status, runs, note, next_run}]
    POST   /automations                 {name, trigger, action, enabled?} -> {ok, id}
    PATCH  /automations/{id}            same fields, all optional        -> {ok}
    DELETE /automations/{id}                                             -> {ok}
    POST   /automations/{id}/run        run now, returns the action result
    GET    /automations/{id}/log?n=20   the last N runs of one automation

Trigger kinds
    time          {"kind": "time", "cron": "*/1 * * * *"}   → compiled into a `hermes cron` job
    app_open      {"kind": "app_open", "package": "org.telegram.messenger"}
    notification  {"kind": "notification", "package": "…", "match": "…"}   (needs the app's
                                                                          NotificationListener)
    manual        {"kind": "manual"}                       → only via POST /automations/{id}/run

Action kinds (exactly one action per automation)
    a fast intent  {"intent": "volume", "args": {"action": "down"}}   ← the 16 intents of
                                                                        jarvis_fast.py
    agent          {"kind": "agent_prompt", "prompt": "خلاصهٔ امروز را بگو"}
    notify         {"kind": "notify", "text": "…"}

Design decisions, all against measured behaviour (2026-10-26)
-------------------------------------------------------------
* **No new scheduler.** A `time` trigger becomes one Hermes cron job whose script POSTs back to
  `/automations/{id}/run`. `hermes cron` already ticks every 55 s from the `cron` unit in
  `hermes-svc`; one job per automation is the least machinery that can work (`--no-agent
  --script`), and jobs live in Hermes' own store (`~/.hermes/cron/jobs.json`), so they survive a
  restart just like the definitions here.
* **The definitions live here**, in `~/.hermes/jarvis/automations.json`. On start the module
  reconciles: any enabled `time` automation without a live job gets one created again. That is
  what makes a restart harmless.
* **`app_open` is polled, not observed.** The app's accessibility bridge has no "what is in
  front" intent (its `screen` action is a UI dump, too coarse and too slow to poll), so the
  watcher asks the platform instead: `dumpsys activity activities` over loopback `adb`, or over
  `rish` (Shizuku) when adb is not there — the same two lanes `jarvis_fast.py` already ranks.
  No app change, no new dependency. When neither lane answers, an `app_open` automation is
  reported `pending: true` with the reason in `note` — it is created, listed and runnable by
  hand, never silently dead. Measured lane cost: adb ~90 ms, rish ~3 s → poll interval 5 s,
  lane re-probe 60 s.
* **`notification` triggers are pending today.** They need a `NotificationListenerService` in
  the app (next round). The trigger is accepted, stored and listed with
  `pending: true, note: "منتظر NotificationListenerService در اپ"`.
* **Every run is recorded** in `~/.hermes/jarvis/automation_log.jsonl` with `ok`, `ms` and the
  action's own result, so the app can show "last run: ok, 120 ms". The log is trimmed to the
  last 1000 lines when it passes 1 MB.
* **`agent_prompt` runs `hermes -z <prompt>`** (one-shot turn) with a 240 s budget: the
  in-process warm agent belongs to a *session* in jarvisd.py and is not reachable from a module,
  and `hermes -z` is the documented one-shot path (measured 13–120 s). Toolset restriction for
  that turn is not enforced here — the prompt carries the intent, not a flag (see docs).
* Import-safe: no file reads, no subprocess, no network, no threads at import time. Everything
  starts from `register(app, ctx)`, and both background threads swallow their own exceptions —
  an automation problem must never take the console down.
"""

from __future__ import annotations

# With `from __future__ import annotations`, FastAPI resolves route annotations in the module
# namespace: Request/JSONResponse must be module-level names (same rule as jarvisd_tools.py).
from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

VERSION = 1
MAX_AUTOMATIONS = 50
NAME_RE = re.compile(r"^[0-9a-f]{8}$")
PKG_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
CRON_RE = re.compile(r"^\s*\S+(\s+\S+){4}\s*$")
JOB_ID_RE = re.compile(r"Created job:\s*([0-9a-zA-Z]+)")
FOCUS_RE = re.compile(r"([A-Za-z][A-Za-z0-9_.]*)/([A-Za-z0-9_.$]+)")
SECRET_RE = re.compile(
    r"(sk-[A-Za-z0-9]{8,}|AIza[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{10,}"
    r"|Bearer\s+[A-Za-z0-9._-]{8,}|eyJ[A-Za-z0-9._-]{20,})")
LOG_KEEP = 1000
LOG_MAX_BYTES = 1_000_000
WATCH_INTERVAL = 5.0
LANE_TTL = 60.0
RECONCILE_INTERVAL = 300.0

# Shared across requests: which trigger lane currently answers ("" = none), and when we last
# asked. The watcher refreshes it, so GET /automations never blocks on a 3 s `rish` call.
_LANE: Dict[str, Any] = {"kind": "", "at": 0.0}
_LANE_LOCK = threading.Lock()
_STARTED = [False]


# ------------------------------------------------------------------------------- helpers --
def _now() -> float:
    return time.time()


def _iso(ts: Optional[float] = None) -> str:
    try:
        return datetime.fromtimestamp(float(ts if ts is not None else _now())).isoformat(
            timespec="seconds")
    except (OverflowError, OSError, ValueError):
        return ""


def _redact(text: str, limit: int = 400) -> str:
    """Trim and scrub anything that looks like a credential before it is stored or shown."""
    out = SECRET_RE.sub("...", " ".join(str(text or "").split()))
    return out[:limit]


def _env(ctx) -> Dict[str, str]:
    env = dict(os.environ)
    env["HOME"] = ctx["HOME"]
    prefix = os.environ.get("PREFIX") or "/data/data/com.termux/files/usr"
    env["PATH"] = os.pathsep.join([os.path.join(ctx["HOME"], ".local", "bin"),
                                   os.path.join(prefix, "bin"), env.get("PATH", "")])
    env.setdefault("TERM", "dumb")
    return env


def _hermes_cli(ctx, *args: str, timeout: float = 120.0) -> Tuple[int, str]:
    """Call the `hermes` CLI with our own timeout.

    ctx["hermes"] is capped at 60 s, which is too short for `cron create` on a cold start and
    far too short for `hermes -z`. Same command, same env, our budget.
    """
    try:
        proc = subprocess.run(["hermes", *args], capture_output=True, text=True,
                              timeout=timeout, env=_env(ctx))
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except FileNotFoundError:
        return 127, "hermes: command not found"
    except subprocess.TimeoutExpired:
        return 124, "timeout after %.0fs" % timeout
    except Exception as exc:  # noqa: BLE001
        return 1, "%s: %s" % (type(exc).__name__, exc)


# --------------------------------------------------------------------------------- store --
def _store_path(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "automations.json")


def _log_path(ctx) -> str:
    return os.path.join(ctx["JARVIS_DIR"], "automation_log.jsonl")


def _jobs_path(ctx) -> str:
    return os.path.join(ctx["HERMES"], "cron", "jobs.json")


def _load(ctx, lock) -> List[Dict[str, Any]]:
    with lock:
        try:
            with open(_store_path(ctx), encoding="utf-8") as fh:
                raw = json.load(fh)
        except (OSError, ValueError):
            return []
    rows = raw.get("automations") if isinstance(raw, dict) else raw
    return [r for r in (rows or []) if isinstance(r, dict) and r.get("id")]


def _save(ctx, lock, rows: List[Dict[str, Any]]) -> None:
    path = _store_path(ctx)
    with lock:
        try:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
                json.dump({"version": VERSION, "automations": rows}, fh,
                          ensure_ascii=False, indent=1)
            os.replace(tmp, path)
        except OSError as exc:
            ctx["notify"]("automation: save failed: %s" % exc)


def _find(rows: List[Dict[str, Any]], aid: str) -> Optional[Dict[str, Any]]:
    for row in rows:
        if row.get("id") == aid:
            return row
    return None


def _append_log(ctx, entry: Dict[str, Any]) -> None:
    path = _log_path(ctx)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(entry, ensure_ascii=False, default=str) + "\n")
        if os.path.getsize(path) > LOG_MAX_BYTES:
            with open(path, encoding="utf-8") as fh:
                keep = fh.readlines()[-LOG_KEEP:]
            tmp = path + ".tmp"
            with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
                fh.writelines(keep)
            os.replace(tmp, path)
    except OSError as exc:
        ctx["notify"]("automation: log write failed: %s" % exc)


def _read_log(ctx, aid: str, limit: int = 20) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    try:
        with open(_log_path(ctx), encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except ValueError:
                    continue
                if isinstance(entry, dict) and entry.get("id") == aid:
                    rows.append(entry)
    except OSError:
        return []
    return rows[-max(1, min(limit, 200)):]


# ------------------------------------------------------------------------------ triggers --
def _norm_trigger(raw: Any) -> Dict[str, Any]:
    """Validate one trigger. Raises ValueError with a machine code as the message."""
    if not isinstance(raw, dict):
        raise ValueError("bad_trigger")
    kind = str(raw.get("kind") or "").strip()
    if kind == "time":
        cron = str(raw.get("cron") or raw.get("schedule") or "").strip()
        if not CRON_RE.match(cron) or len(cron) > 80:
            raise ValueError("bad_cron")
        return {"kind": "time", "cron": cron}
    if kind == "app_open":
        pkg = str(raw.get("package") or "").strip()
        if not PKG_RE.match(pkg):
            raise ValueError("bad_package")
        return {"kind": "app_open", "package": pkg}
    if kind == "notification":
        pkg = str(raw.get("package") or "").strip()
        if pkg and not PKG_RE.match(pkg):
            raise ValueError("bad_package")
        return {"kind": "notification", "package": pkg,
                "match": str(raw.get("match") or "").strip()[:120]}
    if kind == "manual":
        return {"kind": "manual"}
    raise ValueError("bad_trigger_kind")


def _norm_action(raw: Any) -> Dict[str, Any]:
    """Validate one action. A fast intent, an agent prompt, or a notification."""
    if not isinstance(raw, dict):
        raise ValueError("bad_action")
    kind = str(raw.get("kind") or "").strip()
    intent = str(raw.get("intent") or "").strip()
    if kind in ("agent_prompt", "agent"):
        prompt = str(raw.get("prompt") or raw.get("text") or "").strip()
        if not prompt or len(prompt) > 4000:
            raise ValueError("bad_prompt")
        return {"kind": "agent_prompt", "prompt": prompt}
    if kind == "notify":
        text = str(raw.get("text") or "").strip()
        if not text or len(text) > 400:
            raise ValueError("bad_text")
        return {"kind": "notify", "text": text}
    if intent or kind in ("intent", "fast", ""):
        if not intent:
            raise ValueError("bad_intent")
        args = raw.get("args")
        if args is None:
            args = {}
        if not isinstance(args, dict):
            raise ValueError("bad_args")
        # The intent layer speaks `action`; people (and the brief) say `direction`.
        if intent == "volume" and "direction" in args and "action" not in args:
            args = dict(args, action=args.get("direction"))
        return {"kind": "intent", "intent": intent, "args": args}
    raise ValueError("bad_action_kind")


def _trigger_label(trigger: Dict[str, Any]) -> str:
    kind = trigger.get("kind")
    if kind == "time":
        return "cron %s" % trigger.get("cron", "")
    if kind == "app_open":
        return "app_open %s" % trigger.get("package", "")
    if kind == "notification":
        return "notification %s %s" % (trigger.get("package") or "*", trigger.get("match") or "")
    return str(kind or "manual")


def _action_label(action: Dict[str, Any]) -> str:
    """Human-readable one-liner for the log ("intent volume {action=down}")."""
    kind = action.get("kind")
    if kind == "intent":
        args = action.get("args") or {}
        tail = ""
        if args:
            tail = " " + " ".join("%s=%s" % (k, _redact(str(v), 40)) for k, v in args.items())
        return "intent %s%s" % (action.get("intent", ""), tail)
    if kind == "agent_prompt":
        return "agent_prompt «%s»" % _redact(action.get("prompt", ""), 60)
    if kind == "notify":
        return "notify «%s»" % _redact(action.get("text", ""), 60)
    return str(kind or "?")


# ------------------------------------------------------------------------------ actions --
def _run_intent(intent: str, args: Dict[str, Any]) -> Dict[str, Any]:
    """One of the 16 deterministic device intents, through the same code the chat uses."""
    started = _now()
    try:
        # Lazy import: a broken fast module must not stop automations from registering.
        import jarvis_fast as fast  # noqa: PLC0415
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "intent": intent, "ms": 0, "error": "fast_unavailable",
                "detail": _redact("%s: %s" % (type(exc).__name__, exc), 200)}
    if intent not in getattr(fast, "INTENTS", {}):
        return {"ok": False, "intent": intent, "ms": 0, "error": "unknown_intent",
                "detail": intent}
    try:
        res = fast.run(intent, dict(args or {})) or {}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "intent": intent, "ms": int((_now() - started) * 1000),
                "error": "exception", "detail": _redact("%s: %s" % (type(exc).__name__, exc), 200)}
    out = dict(res)
    out.setdefault("intent", intent)
    out["ms"] = int(res.get("ms") or (_now() - started) * 1000)
    if "detail" in out:
        out["detail"] = _redact(out["detail"], 400)
    return out


def _run_notify(ctx, text: str) -> Dict[str, Any]:
    """Toast when Termux:API is there, else the console's own notification channel."""
    started = _now()
    for cmd, channel in ((["termux-toast", "-g", "top", text], "toast"),
                         (["termux-notification", "--title", "Jarvis",
                           "--content", text], "notification")):
        if shutil.which(cmd[0]):
            try:
                proc = subprocess.run(cmd, capture_output=True, text=True, timeout=15,
                                      env=_env(ctx))
                if proc.returncode == 0:
                    return {"ok": True, "channel": channel,
                            "ms": int((_now() - started) * 1000),
                            "detail": _redact(text, 120)}
            except Exception:  # noqa: BLE001  (fall through to the next channel)
                continue
    ctx["notify"]("automation notify: %s" % text)
    return {"ok": True, "channel": "log", "ms": int((_now() - started) * 1000),
            "detail": _redact(text, 120)}


def _run_agent_prompt(ctx, prompt: str) -> Dict[str, Any]:
    """One one-shot agent turn. Slow by nature (13–120 s measured) — the caller waits."""
    started = _now()
    code, out = _hermes_cli(ctx, "-z", prompt, timeout=240)
    ms = int((_now() - started) * 1000)
    reply = _redact(out.strip(), 2000)
    return {"ok": code == 0 and bool(reply), "ms": ms, "rc": code, "reply": reply,
            "detail": reply[:200]}


def _run_action(ctx, action: Dict[str, Any]) -> Dict[str, Any]:
    kind = action.get("kind")
    if kind == "intent":
        return _run_intent(str(action.get("intent") or ""), action.get("args") or {})
    if kind == "agent_prompt":
        return _run_agent_prompt(ctx, str(action.get("prompt") or ""))
    if kind == "notify":
        return _run_notify(ctx, str(action.get("text") or ""))
    return {"ok": False, "error": "bad_action"}


# ---------------------------------------------------------------------- cron compiling --
def _wrapper_path(ctx, aid: str) -> str:
    # `hermes cron --script` rejects an absolute path: it resolves the name relative to
    # ~/.hermes/scripts/, so the wrapper has to live there and be passed as a bare name.
    return os.path.join(ctx["HERMES"], "scripts", "jarvis_automation_%s.sh" % aid)


def _write_wrapper(ctx, aid: str, name: str, port: int) -> Optional[str]:
    """A 6-line script `hermes cron` can run with --no-agent --script.

    It only POSTs back to this daemon: the scheduler decides *when*, the daemon decides *what*,
    so an automation keeps working across restarts and there is exactly one code path per run.
    """
    path = _wrapper_path(ctx, aid)
    url = "http://127.0.0.1:%d/automations/%s/run" % (port, aid)
    if shutil.which("curl"):
        # 250 s: an agent_prompt action budgets 240 s, and curl must outlive it.
        call = ("curl -sS -m 250 --fail -X POST -H 'Content-Type: application/json' "
                "-d '{}' '%s' >/dev/null 2>>\"$HOME/.hermes/jarvis/automation_cron.log\""
                % url)
    elif shutil.which("python3"):
        call = ("python3 -c 'import urllib.request as u; u.urlopen(u.Request(\"%s\", "
                "data=b\"{}\", headers={\"Content-Type\": \"application/json\"}, "
                "method=\"POST\"), timeout=250).read()' "
                ">>\"$HOME/.hermes/jarvis/automation_cron.log\" 2>&1" % url)
    else:
        return None
    body = ("#!/data/data/com.termux/files/usr/bin/sh\n"
            "# Generated by jarvisd_automation for automation %s (%s).\n"
            "# Rewritten on every reconcile — edit the automation, not this file.\n"
            "%s\n" % (aid, _redact(name, 60).replace("\n", " "), call))
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(body)
        os.chmod(tmp, 0o700)
        os.replace(tmp, path)
        return os.path.basename(path)
    except OSError as exc:
        ctx["notify"]("automation: wrapper write failed: %s" % exc)
        return None


def _cron_create(ctx, auto: Dict[str, Any], port: int) -> Tuple[str, str, str]:
    """Create the cron job for a time automation. Returns (job_id, cmd_used, error).

    The `--no-agent --script` pair is what makes this a *script* job (no model turn). Hermes'
    CLI has grown different positional/flag combinations across versions, so this tries the
    documented shape first and then two narrower ones, and records which one worked — a
    mismatch is visible in `note` instead of silently producing a dead automation.
    """
    aid = str(auto.get("id") or "")
    wrapper = _write_wrapper(ctx, aid, str(auto.get("name") or ""), port)
    if not wrapper:
        return "", "", "curl/python3 هر دو نیستند؛ اسکریپت کرون نوشته نشد"
    sched = str((auto.get("trigger") or {}).get("cron") or "")
    label = "automation: %s" % _redact(auto.get("name") or aid, 60)
    ladder = [
        ["cron", "create", sched, label, "--name", label[:80], "--deliver", "local",
         "--no-agent", "--script", wrapper],
        ["cron", "create", sched, label, "--no-agent", "--script", wrapper],
        ["cron", "create", sched, "--no-agent", "--script", wrapper],
    ]
    last = ""
    for args in ladder:
        code, out = _hermes_cli(ctx, *args, timeout=120)
        text = _redact(out, 300)
        match = JOB_ID_RE.search(out or "")
        if code == 0 and match:
            return match.group(1), " ".join(args[:3]) + " … --no-agent --script", ""
        last = text or ("exit %d" % code)
    return "", "", last


def _cron_remove(ctx, job_id: str) -> bool:
    if not job_id:
        return False
    code, out = _hermes_cli(ctx, "cron", "remove", job_id, timeout=60)
    text = (out or "").lower()
    return code == 0 or "removed" in text or "not found" in text or "no job" in text


def _cron_pause(ctx, job_id: str, paused: bool) -> None:
    if not job_id:
        return
    _hermes_cli(ctx, "cron", "pause" if paused else "resume", job_id, timeout=60)


def _cron_jobs(ctx) -> Dict[str, Dict[str, Any]]:
    """Read Hermes' job store (id → job) for next_run/last_run. Never writes it."""
    try:
        with open(_jobs_path(ctx), encoding="utf-8") as fh:
            raw = json.load(fh)
    except (OSError, ValueError):
        return {}
    jobs = raw.get("jobs") if isinstance(raw, dict) else raw
    out: Dict[str, Dict[str, Any]] = {}
    for job in jobs or []:
        if isinstance(job, dict) and job.get("id"):
            out[str(job["id"])] = job
    return out


def _compile_time_trigger(ctx, rows, lock, auto: Dict[str, Any], port: int) -> None:
    """(Re)build the cron job of one time automation and record the outcome on the row."""
    auto.pop("cron_error", None)
    old = str(auto.get("cron_job") or "")
    if old:
        _cron_remove(ctx, old)
    job_id, cmd, err = _cron_create(ctx, auto, port)
    auto["cron_job"] = job_id
    auto["cron_cmd"] = cmd
    if job_id:
        auto["note"] = ""
        ctx["notify"]("automation %s → cron job %s" % (auto.get("id"), job_id))
    else:
        auto["cron_error"] = err
        auto["note"] = "کرون ساخته نشد: %s" % err
        ctx["notify"]("automation %s: cron create failed: %s" % (auto.get("id"), err))
    if not auto.get("enabled", True) and job_id:
        _cron_pause(ctx, job_id, True)
    _save(ctx, lock, rows)


# ---------------------------------------------------------------- the device-control lane --
def _try_cmd(cmd: List[str], timeout: float) -> bool:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return proc.returncode == 0
    except Exception:  # noqa: BLE001
        return False


def _probe_lane() -> Tuple[str, str]:
    """("adb"|"rish"|"", sample) — cached for LANE_TTL so polling stays cheap."""
    with _LANE_LOCK:
        if _now() - float(_LANE["at"] or 0) < LANE_TTL:
            return str(_LANE["kind"]), ""
    kind = ""
    sample = ""
    if shutil.which("adb") and _try_cmd(["adb", "-s", "127.0.0.1:5555", "shell", "true"], 8):
        kind, sample = "adb", "adb 127.0.0.1:5555"
    elif shutil.which("rish") and _try_cmd(["rish", "-c", "echo ok"], 25):
        kind, sample = "rish", "rish (Shizuku)"
    with _LANE_LOCK:
        _LANE["kind"], _LANE["at"] = kind, _now()
    return kind, sample


def _foreground_package(ctx) -> Optional[str]:
    """The package in front, via the lane that answers. None when no lane does."""
    kind, _sample = _probe_lane()
    if kind == "adb":
        cmd = ["adb", "-s", "127.0.0.1:5555", "shell",
               "dumpsys activity activities | grep -m1 mResumedActivity"]
    elif kind == "rish":
        cmd = ["rish", "-c", "dumpsys activity activities | grep -m1 mResumedActivity"]
    else:
        return None
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=20,
                              env=_env(ctx))
        match = FOCUS_RE.search(proc.stdout or "")
        if match:
            return match.group(1)
        if kind == "adb":
            proc = subprocess.run(["adb", "-s", "127.0.0.1:5555", "shell",
                                   "dumpsys window | grep -m1 mCurrentFocus"],
                                  capture_output=True, text=True, timeout=20, env=_env(ctx))
        else:
            proc = subprocess.run(["rish", "-c", "dumpsys window | grep -m1 mCurrentFocus"],
                                  capture_output=True, text=True, timeout=25, env=_env(ctx))
        match = FOCUS_RE.search(proc.stdout or "")
        return match.group(1) if match else None
    except Exception:  # noqa: BLE001
        return None


# ---------------------------------------------------------------------------- running --
def _fire(ctx, rows, lock, auto: Dict[str, Any], how: str) -> Dict[str, Any]:
    """Run one automation's action, record it, and return the action result."""
    action = auto.get("action") or {}
    started = _now()
    try:
        result = _run_action(ctx, action)
    except Exception as exc:  # noqa: BLE001
        result = {"ok": False, "error": "exception",
                  "detail": _redact("%s: %s" % (type(exc).__name__, exc), 200)}
    ms = int(result.get("ms") or (_now() - started) * 1000)
    ok = bool(result.get("ok"))
    entry = {"ts": round(started, 3), "at": _iso(started), "id": auto.get("id"),
             "name": auto.get("name"), "trigger": how,
             "trigger_label": _trigger_label(auto.get("trigger") or {}),
             "action": _action_label(action), "ok": ok, "ms": ms, "result": result}
    _append_log(ctx, entry)
    with lock:
        auto["last_run"] = entry["at"]
        auto["last_status"] = "ok" if ok else "fail"
        auto["runs"] = int(auto.get("runs") or 0) + 1
        auto["last_ms"] = ms
        _save(ctx, lock, rows)
    ctx["notify"]("automation %s (%s) → %s in %d ms" % (auto.get("id"), how,
                                                        "ok" if ok else "fail", ms))
    return {"ok": ok, "id": auto.get("id"), "trigger": how, "ms": ms, "result": result,
            "at": entry["at"]}


def _row(auto: Dict[str, Any], jobs: Dict[str, Dict[str, Any]]) -> Dict[str, Any]:
    """The list row the app shows — including a truthful `pending`.

    `pending` means "this trigger cannot fire right now". It is derived, never stored:
    a time trigger is pending only when its cron job could not be created (or Hermes' own job
    store says it is gone), an app_open trigger when no adb/Shizuku lane answers, and a
    notification trigger until the app ships its NotificationListenerService.
    """
    trigger = auto.get("trigger") or {}
    kind = trigger.get("kind")
    jobs_known = bool(jobs)
    job_id = str(auto.get("cron_job") or "")
    pending = False
    note = str(auto.get("cron_error") or auto.get("note") or "")
    job = jobs.get(job_id) or {}
    if kind == "time":
        if not job_id:
            pending = True
            note = note or "کار کرون ساخته نشد"
        elif jobs_known and job_id not in jobs:
            pending = True
            note = note or "کار کرون در فهرست Hermes نیست؛ خودکار بازساخته می‌شود"
    elif kind == "app_open":
        if not _LANE["kind"]:
            pending = True
            note = note or ("برای دیدن اپ بازشده به adb یا Shizuku نیاز است "
                            "(jarvis-doctor می‌گوید کدام)")
    elif kind == "notification":
        pending = True
        note = note or "منتظر NotificationListenerService در اپ"
    return {"id": auto.get("id"), "name": auto.get("name"), "trigger": trigger,
            "action": auto.get("action"), "enabled": bool(auto.get("enabled", True)),
            "pending": pending, "note": note,
            "last_run": auto.get("last_run") or "", "last_status": auto.get("last_status")
            or "never", "last_ms": int(auto.get("last_ms") or 0),
            "runs": int(auto.get("runs") or 0), "created": auto.get("created") or "",
            "cron_job": job_id,
            "next_run": str(job.get("next_run_at") or job.get("next_run") or "")}


def _patch_cron(ctx, rows, lock, auto: Dict[str, Any], port: int, old_kind: str,
                old_job: str, enabled_touched: bool) -> None:
    """Apply the cron side of a PATCH: drop, rebuild or pause the job. Slow (CLI) — run in a
    worker thread, never on the event loop."""
    new_kind = (auto.get("trigger") or {}).get("kind")
    if old_job and (old_kind != "time" or new_kind != "time"):
        _cron_remove(ctx, old_job)
        auto["cron_job"] = ""
    if new_kind == "time":
        if old_job and old_kind == "time":
            _cron_remove(ctx, old_job)   # the schedule or the name may have changed: rebuild
            auto["cron_job"] = ""
        _compile_time_trigger(ctx, rows, lock, auto, port)
    elif old_kind == "time":
        auto["note"] = ""
        _save(ctx, lock, rows)
    elif enabled_touched and auto.get("cron_job"):
        _cron_pause(ctx, str(auto["cron_job"]), not auto.get("enabled", True))


# ---------------------------------------------------------------------- background jobs --
def _watch_app_open(ctx, lock) -> None:
    """Poll the foreground package and fire `app_open` automations on a transition."""
    time.sleep(4.0)
    last_seen = None
    while True:
        try:
            rows = _load(ctx, lock)
            wanted = [r for r in rows if (r.get("trigger") or {}).get("kind") == "app_open"]
            if not wanted:
                time.sleep(WATCH_INTERVAL)
                continue
            _probe_lane()
            pkg = _foreground_package(ctx)
            if pkg and pkg != last_seen:
                for auto in wanted:
                    if not auto.get("enabled", True):
                        continue
                    if (auto.get("trigger") or {}).get("package") != pkg:
                        continue
                    # one fire per foreground change per automation: no cooldown needed
                    _fire(ctx, rows, lock, auto, "app_open")
                last_seen = pkg
            elif pkg:
                last_seen = pkg
        except Exception as exc:  # noqa: BLE001
            ctx["notify"]("automation watcher: %s" % exc)
        time.sleep(WATCH_INTERVAL)


def _reconcile(ctx, lock, port: int) -> None:
    """Re-create the cron job of every enabled time automation that lost it (restart, reboot,
    a Hermes upgrade that cleared its store). Runs once at start and then every 5 minutes."""
    time.sleep(6.0)
    while True:
        try:
            rows = _load(ctx, lock)
            jobs = _cron_jobs(ctx)
            jobs_known = bool(jobs)
            for auto in rows:
                trigger = auto.get("trigger") or {}
                if trigger.get("kind") != "time" or not auto.get("enabled", True):
                    continue
                job = str(auto.get("cron_job") or "")
                # Rebuild only when we know the job is gone. An unreadable job store must not
                # trigger a create-storm: every rebuild would add a duplicate cron job.
                if job and (not jobs_known or job in jobs):
                    continue
                ctx["notify"]("automation %s: cron job missing, rebuilding" % auto.get("id"))
                _compile_time_trigger(ctx, rows, lock, auto, port)
        except Exception as exc:  # noqa: BLE001
            ctx["notify"]("automation reconcile: %s" % exc)
        time.sleep(RECONCILE_INTERVAL)


# ------------------------------------------------------------------------------- routes --
def register(app, ctx) -> None:  # noqa: C901  (one flat block of routes is easier to audit)
    lock = threading.RLock()
    try:
        port = int(os.environ.get("JARVIS_PORT", "8780"))
    except ValueError:
        port = 8780

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req: Request) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw.decode("utf-8", "replace") or "{}")
            return data if isinstance(data, dict) else {}
        except (ValueError, UnicodeDecodeError):
            return {}

    @app.get("/automations")
    def automations_list():
        try:
            rows = _load(ctx, lock)
            jobs = _cron_jobs(ctx)
            return [_row(r, jobs) for r in rows]
        except Exception as exc:  # noqa: BLE001
            return err("internal", _redact(str(exc), 200), 500)

    @app.post("/automations")
    async def automations_create(req: Request):
        data = await body(req)
        try:
            trigger = _norm_trigger(data.get("trigger"))
            action = _norm_action(data.get("action"))
        except ValueError as exc:
            return err(str(exc))
        name = str(data.get("name") or "").strip()[:80] or _trigger_label(trigger)
        rows = _load(ctx, lock)
        if len(rows) >= MAX_AUTOMATIONS:
            return err("too_many", "حداکثر %d اتوماسیون" % MAX_AUTOMATIONS, 409)
        auto = {"id": uuid.uuid4().hex[:8], "name": name, "trigger": trigger,
                "action": action, "enabled": bool(data.get("enabled", True)),
                "created": _iso(), "last_run": "", "last_status": "never", "runs": 0,
                "last_ms": 0, "cron_job": "", "note": ""}
        rows.append(auto)
        _save(ctx, lock, rows)
        if trigger["kind"] == "time":
            # `hermes cron create` is a CLI call lasting seconds: keep it off the event loop
            # but finish it before answering, so the app can trust the "pending" it gets back.
            await run_in_threadpool(_compile_time_trigger, ctx, rows, lock, auto, port)
        ctx["notify"]("automation %s created: %s" % (auto["id"], _trigger_label(trigger)))
        row = _row(auto, _cron_jobs(ctx))
        return {"ok": True, "id": auto["id"], "pending": row["pending"],
                "note": row["note"]}

    @app.patch("/automations/{aid}")
    async def automations_patch(aid: str, req: Request):
        if not NAME_RE.match(aid):
            return err("bad_id", aid)
        data = await body(req)
        rows = _load(ctx, lock)
        auto = _find(rows, aid)
        if not auto:
            return err("not_found", aid, 404)
        old_kind = (auto.get("trigger") or {}).get("kind")
        old_job = str(auto.get("cron_job") or "")
        try:
            if "trigger" in data:
                auto["trigger"] = _norm_trigger(data.get("trigger"))
            if "action" in data:
                auto["action"] = _norm_action(data.get("action"))
        except ValueError as exc:
            return err(str(exc))
        if "name" in data:
            auto["name"] = str(data.get("name") or "").strip()[:80] or auto.get("name")
        if "enabled" in data:
            auto["enabled"] = bool(data.get("enabled"))
        _save(ctx, lock, rows)
        await run_in_threadpool(_patch_cron, ctx, rows, lock, auto, port, old_kind, old_job,
                                "enabled" in data)
        row = _row(auto, _cron_jobs(ctx))
        ctx["notify"]("automation %s patched" % aid)
        return {"ok": True, "pending": row["pending"], "note": row["note"]}

    @app.delete("/automations/{aid}")
    def automations_delete(aid: str):
        if not NAME_RE.match(aid):
            return err("bad_id", aid)
        rows = _load(ctx, lock)
        auto = _find(rows, aid)
        if not auto:
            return err("not_found", aid, 404)
        job = str(auto.get("cron_job") or "")
        if job:
            _cron_remove(ctx, job)
        try:
            os.remove(_wrapper_path(ctx, aid))
        except OSError:
            pass
        rows = [r for r in rows if r.get("id") != aid]
        _save(ctx, lock, rows)
        ctx["notify"]("automation %s deleted" % aid)
        return {"ok": True}

    @app.post("/automations/{aid}/run")
    def automations_run(aid: str):
        if not NAME_RE.match(aid):
            return err("bad_id", aid)
        rows = _load(ctx, lock)
        auto = _find(rows, aid)
        if not auto:
            return err("not_found", aid, 404)
        return _fire(ctx, rows, lock, auto, "manual")

    @app.get("/automations/{aid}/log")
    def automations_log(aid: str, n: int = 20):
        if not NAME_RE.match(aid):
            return err("bad_id", aid)
        rows = _load(ctx, lock)
        if not _find(rows, aid):
            return err("not_found", aid, 404)
        return {"ok": True, "id": aid, "entries": _read_log(ctx, aid, n)}

    # Both threads are daemons and swallow their own errors: a stuck watcher must never stop
    # the console, and a fresh phone with neither adb nor Shizuku must still list automations.
    if not _STARTED[0]:
        _STARTED[0] = True
        for target, args, name in ((_watch_app_open, (ctx, lock), "jarvisd-automation-watch"),
                                   (_reconcile, (ctx, lock, port), "jarvisd-automation-cron")):
            try:
                threading.Thread(target=target, args=args, name=name, daemon=True).start()
            except Exception as exc:  # noqa: BLE001
                ctx["notify"]("automation: %s not started: %s" % (name, exc))
    ctx["notify"]("automation: %d defined, port %d" % (len(_load(ctx, lock)), port))
