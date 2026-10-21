#!/usr/bin/env python3
"""gwctl v2 — full control server for the Hermes stack on this phone.

Loopback only (127.0.0.1:8777). The hcontrol app is the only client.

Endpoints
    GET  /ping                     liveness
    GET  /version                  server + hermes versions
    GET  /status                   gateway health, telegram, model, uptime
    GET  /system                   battery, memory, storage, load, processes
    GET  /config                   redacted config + editable field schema
    POST /config/set               {key, value}  -> write config.yaml (backed up)
    GET  /models                   configured providers/models + live model list
    POST /model/switch             {provider, model}
    POST /model/test               {provider, model, prompt} -> latency + reply
    GET  /keys                     credential pool, masked, with cooldowns
    POST /keys/test                {provider, id?}  -> live check per key
    POST /keys/reset               {provider}  -> clear cooldown/error state
    POST /keys/add                 {provider, label, key}
    POST /keys/remove              {provider, id}
    GET  /logs?file=&lines=&filter=
    GET  /logs/files               available log files
    GET  /checks                   reachability of agentrouter/telegram/dns
    GET  /cron                     scheduled jobs
    POST /cron/action              {id, action: run|pause|resume}
    POST /agent/test               end-to-end `hermes -z` round trip
    POST /gateway/restart          start/restart the gateway
    POST /gateway/stop             stop it

Security: binds 127.0.0.1 only, so nothing off-device can reach it. There is
deliberately no auth token — the app cannot read files inside Termux, so a shared
secret would have to be pasted in by hand, and the only clients that can reach a
loopback port are apps already running on this phone.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import yaml
except Exception:  # noqa: BLE001
    yaml = None

HOME = os.path.expanduser("~")
HERMES = os.path.join(HOME, ".hermes")
CFG = os.path.join(HERMES, "config.yaml")
AUTH = os.path.join(HERMES, "auth.json")
STATE = os.path.join(HERMES, "gateway_state.json")
JOBS = os.path.join(HERMES, "cron", "jobs.json")
BACKUPS = os.path.join(HERMES, "hcontrol-backups")
GW_LOG = os.path.join(HERMES, "gw-live.log")
PORT = int(os.environ.get("GWCTL_PORT", "8777"))

TMUX_SESSION = "gw"
PREFIX = "/data/data/com.termux/files/usr"
HERMES_BIN = os.path.join(PREFIX, "bin", "hermes")
UA = "claude-cli/1.0.0 (external, cli)"
API_VERSION = 2

CFG_LOCK = threading.Lock()

_ENV = dict(os.environ)
_ENV["PATH"] = f"{HOME}/.local/bin:{PREFIX}/bin:{_ENV.get('PATH', '')}"


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def _run(cmd, timeout=25):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=_ENV)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as exc:  # noqa: BLE001
        return 1, str(exc)


def _read_json(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:  # noqa: BLE001
        return {}


def _read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except Exception:  # noqa: BLE001
        return ""


def _mask(secret):
    s = str(secret or "")
    if len(s) <= 10:
        return "•" * len(s) if s else ""
    return f"{s[:6]}…{s[-4:]}"


def _now():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def _pid_cmdline(pid):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as fh:
            return fh.read().decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return ""


def _pid_alive(pid):
    cmd = _pid_cmdline(pid)
    return "gateway" in cmd and "run" in cmd


def _pid_uptime(pid):
    """Seconds a process has been alive.

    Android blocks reading /proc/uptime and /proc/1/* from Termux, and
    /proc/<pid>/stat's starttime needs /proc/uptime to convert. `ps -o etimes`
    (procps-ng, shipped with Termux) answers it directly.
    """
    try:
        out = subprocess.run(["ps", "-A", "-o", "pid,etimes", "--no-headers"],
                             capture_output=True, text=True, timeout=8, env=_ENV).stdout
        for line in out.splitlines():
            parts = line.split()
            if len(parts) >= 2 and parts[0] == str(pid):
                return int(parts[1])
    except Exception:  # noqa: BLE001
        pass
    # Fallback: directory ctime (creation time) — good enough for a floor value.
    try:
        return max(0, int(time.time() - os.stat(f"/proc/{pid}").st_ctime))
    except Exception:  # noqa: BLE001
        return None


def _pid_rss(pid):
    for line in _read_text(f"/proc/{pid}/status").splitlines():
        if line.startswith("VmRSS:"):
            try:
                return int(line.split()[1])
            except Exception:  # noqa: BLE001
                return 0
    return 0


def _sys_uptime():
    """System uptime in seconds.

    Android blocks /proc/uptime and /proc/1 from Termux, and ro.boottime.init is
    empty on this build. The oldest process's elapsed time is a good floor: the
    first process spawned after boot has been alive essentially since then.
    """
    try:
        return int(float(_read_text("/proc/uptime").split()[0]))
    except Exception:  # noqa: BLE001
        pass
    try:
        out = subprocess.run(["ps", "-A", "-o", "etimes", "--no-headers"],
                             capture_output=True, text=True, timeout=8, env=_ENV).stdout
        values = [int(x) for x in out.split() if x.isdigit()]
        if values:
            return max(values)
    except Exception:  # noqa: BLE001
        pass
    return None


# ---------------------------------------------------------------------------
# gateway
# ---------------------------------------------------------------------------
def gateway_pids():
    recorded = str(_read_json(STATE).get("pid") or "")
    if recorded.isdigit() and _pid_alive(int(recorded)):
        return [recorded]

    found = []
    try:
        for entry in os.listdir("/proc"):
            if not entry.isdigit():
                continue
            cmd = _pid_cmdline(entry)
            # Reject tmux launchers and `bash -c … | tee` wrappers: they outlive the
            # session and would make a dead gateway look alive.
            if "gateway" in cmd and " run" in cmd and "tmux" not in cmd and "bash -c" not in cmd:
                found.append(entry)
    except Exception:  # noqa: BLE001
        pass
    return found


def gateway_status():
    pids = gateway_pids()
    state = _read_json(STATE)
    tg = (state.get("platforms") or {}).get("telegram") or {}
    tg_state = tg.get("state") or "unknown"
    attention = bool(tg.get("needs_attention"))

    state_pid = str(state.get("pid") or "")
    fresh = bool(state_pid) and state_pid in pids

    if pids and fresh:
        overall = "online"
    elif pids:
        overall = "starting"
    else:
        overall = "offline"

    return {
        "overall": overall,
        "health": "ok" if overall == "online" and tg_state == "connected" else overall,
        "pid": pids[0] if pids else None,
        "pids": pids,
        "telegram": tg_state,
        "needs_attention": attention,
        "state_fresh": fresh,
        "model": _current_model(),
        "version": state.get("code_version") or "",
        "uptime_seconds": _pid_uptime(int(pids[0])) if pids else None,
        "rss_kb": _pid_rss(int(pids[0])) if pids else 0,
        "active_agents": state.get("active_agents") or 0,
        "checked_at": _now(),
    }


def _current_model():
    if yaml:
        try:
            with open(CFG, encoding="utf-8") as fh:
                cfg = yaml.safe_load(fh) or {}
            m = cfg.get("model") or {}
            return f"{m.get('provider', '')}/{m.get('default', '')}"
        except Exception:  # noqa: BLE001
            pass
    provider = model = ""
    section = None
    for line in _read_text(CFG).splitlines():
        if line.startswith("model:"):
            section = "model"
            continue
        if section == "model":
            if line and not line[0].isspace():
                section = None
                continue
            s = line.strip()
            if s.startswith("provider:"):
                provider = s.split(":", 1)[1].strip()
            elif s.startswith("default:"):
                model = s.split(":", 1)[1].strip()
    return f"{provider}/{model}" if provider or model else ""


def log_tail(lines=8):
    return [ln.rstrip() for ln in _read_text(GW_LOG).splitlines()[-lines:]]


def restart_gateway(wait=30):
    before = gateway_pids()
    _run(["tmux", "kill-session", "-t", TMUX_SESSION], timeout=15)

    tmux = shutil.which("tmux")
    if tmux:
        inner = (f"PYTHONUNBUFFERED=1 {HERMES_BIN} gateway run --replace "
                 f"2>&1 | tee {GW_LOG}")
        code, out = _run([tmux, "new-session", "-d", "-s", TMUX_SESSION,
                          "-x", "200", "-y", "50", inner], timeout=30)
    else:
        code, out = _run([HERMES_BIN, "gateway", "run", "--replace"], timeout=30)

    deadline = time.time() + wait
    pids = []
    while time.time() < deadline:
        time.sleep(1.5)
        pids = gateway_pids()
        if pids:
            break
    return {"started": bool(pids), "killed": before, "pids": pids, "exit_code": code,
            "detail": out.strip()[:400], "status": gateway_status()}


def stop_gateway():
    before = gateway_pids()
    _run(["tmux", "kill-session", "-t", TMUX_SESSION], timeout=15)
    for pid in before:
        _run(["kill", "-TERM", pid], timeout=10)
    time.sleep(3)
    return {"killed": before, "remaining": gateway_pids(), "status": gateway_status()}


# ---------------------------------------------------------------------------
# config
# ---------------------------------------------------------------------------
FIELDS = [
    {"key": "model.provider", "label": "پرووایدر فعال", "group": "مدل",
     "type": "enum", "source": "providers"},
    {"key": "model.default", "label": "مدل پیش‌فرض", "group": "مدل",
     "type": "enum", "source": "models"},
    {"key": "model.api_mode", "label": "پروتکل API", "group": "مدل", "type": "enum",
     "options": ["anthropic_messages", "chat_completions", "codex_responses"]},
    {"key": "agent.reasoning_effort", "label": "سطح استدلال", "group": "مدل", "type": "enum",
     "options": ["none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra"]},
    {"key": "agent.max_turns", "label": "حداکثر نوبت عامل", "group": "مدل",
     "type": "int", "min": 10, "max": 1000, "step": 10},

    {"key": "streaming.enabled", "label": "استریم در چت", "group": "استریم", "type": "bool"},
    {"key": "streaming.transport", "label": "روش استریم", "group": "استریم", "type": "enum",
     "options": ["auto", "draft", "edit"]},
    {"key": "streaming.edit_interval", "label": "فاصله به‌روزرسانی (ثانیه)", "group": "استریم",
     "type": "float", "min": 0.3, "max": 5.0, "step": 0.1},
    {"key": "display.streaming", "label": "استریم در ترمینال", "group": "استریم", "type": "bool"},

    {"key": "voice.auto_tts", "label": "پاسخ صوتی خودکار", "group": "صدا", "type": "bool"},
    {"key": "tts.provider", "label": "سرویس گفتار (TTS)", "group": "صدا", "type": "enum",
     "options": ["elevenlabs", "edge", "openai", "none"]},
    {"key": "tts.elevenlabs.model_id", "label": "مدل صدا", "group": "صدا", "type": "enum",
     "options": ["eleven_v4", "eleven_v4_turbo", "eleven_v3", "eleven_multilingual_v2"]},
    {"key": "tts.elevenlabs.voice_id", "label": "شناسه صدا", "group": "صدا", "type": "str"},
    {"key": "stt.provider", "label": "تبدیل گفتار به متن", "group": "صدا", "type": "enum",
     "options": ["elevenlabs", "openai", "local", "none"]},
    {"key": "stt.language", "label": "زبان گفتار", "group": "صدا", "type": "enum",
     "options": ["fa", "en", "auto"]},

    {"key": "display.tool_progress", "label": "نمایش اجرای ابزارها", "group": "نمایش",
     "type": "enum", "options": ["all", "concise", "none"]},
    {"key": "display.show_reasoning", "label": "نمایش استدلال", "group": "نمایش", "type": "bool"},
    {"key": "gateway.message_timestamps.enabled", "label": "مهر زمانی پیام‌ها",
     "group": "نمایش", "type": "bool"},

    {"key": "compression.threshold", "label": "آستانه فشرده‌سازی", "group": "سیستم",
     "type": "float", "min": 0.2, "max": 0.95, "step": 0.05},
    {"key": "approvals.mode", "label": "تأیید دستورهای خطرناک", "group": "سیستم",
     "type": "enum", "options": ["off", "on", "destructive"]},
    {"key": "timezone", "label": "منطقه زمانی", "group": "سیستم", "type": "str"},
]

RESTART_PREFIXES = ("model.", "streaming.", "tts.", "stt.", "voice.", "display.",
                    "agent.", "compression.", "gateway.message_timestamps")


def load_config():
    if not yaml:
        raise RuntimeError("yaml unavailable — gwctl must run under the Hermes venv")
    with open(CFG, encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def get_path(cfg, path):
    cur = cfg
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def set_path(cfg, path, value):
    parts = path.split(".")
    cur = cfg
    for part in parts[:-1]:
        nxt = cur.get(part)
        if not isinstance(nxt, dict):
            nxt = {}
            cur[part] = nxt
        cur = nxt
    cur[parts[-1]] = value


def coerce(field, raw):
    t = field.get("type")
    if t == "bool":
        if isinstance(raw, bool):
            return raw
        return str(raw).strip().lower() in ("1", "true", "yes", "on")
    if t == "int":
        return int(float(raw))
    if t == "float":
        return round(float(raw), 2)
    return "" if raw is None else str(raw)


def config_snapshot():
    cfg = load_config()
    providers = sorted((cfg.get("providers") or {}).keys())
    models = []
    for pname, pdata in (cfg.get("providers") or {}).items():
        for mname, _meta in _model_items((pdata or {}).get("models")):
            models.append(f"{pname}/{mname}")
    current = _current_model()
    if current and current not in models:
        models.insert(0, current)

    fields = []
    for f in FIELDS:
        item = dict(f)
        item["value"] = get_path(cfg, f["key"])
        if f.get("source") == "providers":
            item["options"] = providers
        elif f.get("source") == "models":
            item["options"] = models
        fields.append(item)

    return {"model": current, "providers": providers, "models": models,
            "fields": fields, "raw": _redact(cfg), "path": CFG, "editable": True}


def _redact(obj):
    if isinstance(obj, dict):
        out = {}
        for k, v in obj.items():
            if re.search(r"(api_key|token|secret|password)", str(k), re.I) \
                    and isinstance(v, (str, int, float)) and str(v):
                out[k] = _mask(v)
            else:
                out[k] = _redact(v)
        return out
    if isinstance(obj, list):
        return [_redact(v) for v in obj]
    return obj


def write_config(cfg):
    os.makedirs(BACKUPS, exist_ok=True)
    backup = os.path.join(BACKUPS, time.strftime("config-%Y%m%d-%H%M%S-") + uuid.uuid4().hex[:4] + ".yaml")
    shutil.copy2(CFG, backup)
    tmp = CFG + ".hcontrol.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        yaml.safe_dump(cfg, fh, sort_keys=False, allow_unicode=True,
                       default_flow_style=False)
    os.replace(tmp, CFG)
    try:
        for old in sorted(os.listdir(BACKUPS))[:-20]:
            os.remove(os.path.join(BACKUPS, old))
    except Exception:  # noqa: BLE001
        pass
    return backup


# ---------------------------------------------------------------------------
# models + keys
# ---------------------------------------------------------------------------
def _provider_conf(cfg, provider):
    return ((cfg.get("providers") or {}).get(provider) or {})


def _key_for(provider):
    pool = _read_json(AUTH).get("credential_pool") or {}
    entries = pool.get(provider) or []
    fallback = None
    for e in entries:
        token = e.get("access_token")
        if not token:
            continue
        if e.get("last_status") in (None, "ok"):
            return token
        fallback = fallback or token
    if fallback:
        return fallback
    env_name = provider.upper().replace("-", "_") + "_API_KEY"
    for line in _read_text(os.path.join(HERMES, ".env")).splitlines():
        if line.startswith(env_name + "="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    try:
        return _provider_conf(load_config(), provider).get("api_key") or ""
    except Exception:  # noqa: BLE001
        return ""


def _http_json(url, headers=None, body=None, timeout=25):
    h = {"User-Agent": UA, "Accept": "application/json"}
    if headers:
        h.update(headers)
    data = json.dumps(body).encode() if body is not None else None
    if data:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h,
                                 method="POST" if data else "GET")
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace"), \
                int((time.time() - started) * 1000)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), int((time.time() - started) * 1000)
    except Exception as e:  # noqa: BLE001
        return 0, str(e), int((time.time() - started) * 1000)


def _model_items(models):
    """Providers may declare models as a dict (with metadata) OR a plain list.

    `providers.antigravity.models` on this phone is a list of model ids, so any code
    that blindly calls .items() blows up with
    "'list' object has no attribute 'items'".
    """
    if isinstance(models, dict):
        return [(str(k), v or {}) for k, v in models.items()]
    if isinstance(models, list):
        out = []
        for item in models:
            if isinstance(item, dict):
                mid = item.get("id") or item.get("name") or ""
                if mid:
                    out.append((str(mid), item))
            elif item:
                out.append((str(item), {}))
        return out
    return []


def models_payload():
    cfg = load_config()
    configured = []
    for pname, pdata in (cfg.get("providers") or {}).items():
        for mname, meta in _model_items((pdata or {}).get("models")):
            meta = meta or {}
            configured.append({
                "provider": pname,
                "model": mname,
                "display": meta.get("display_name") or meta.get("name") or mname,
                "context": meta.get("context_length"),
                "tools": meta.get("supports_tools"),
                "vision": meta.get("supports_vision"),
            })

    live, live_error = [], None
    conf = _provider_conf(cfg, "agentrouter")
    if conf:
        base = str(conf.get("base_url") or "").rstrip("/")
        status, raw, _ms = _http_json(base + "/v1/models",
                                      {"Authorization": "Bearer " + _key_for("agentrouter")})
        if status == 200:
            try:
                live = [m.get("id") for m in (json.loads(raw).get("data") or [])]
            except Exception:  # noqa: BLE001
                live_error = raw[:200]
        else:
            live_error = f"HTTP {status}: {raw[:160]}"
    return {"configured": configured, "live": live, "live_error": live_error,
            "current": _current_model()}


def keys_payload():
    pool = _read_json(AUTH).get("credential_pool") or {}
    now = time.time()
    out = {}
    for provider, entries in pool.items():
        rows = []
        for e in entries or []:
            reset = e.get("last_error_reset_at")
            cooldown = int(reset - now) if isinstance(reset, (int, float)) and reset > now else None
            rows.append({
                "id": e.get("id"),
                "label": e.get("label"),
                "priority": e.get("priority"),
                "source": e.get("source"),
                "status": e.get("last_status") or "ok",
                "error_code": e.get("last_error_code"),
                "error_reason": e.get("last_error_reason"),
                "error": (e.get("last_error_message") or "")[:180],
                "cooldown": cooldown,
                "requests": e.get("request_count") or 0,
                "base_url": e.get("base_url"),
                "key": _mask(e.get("access_token")),
                "is_current": e.get("last_status") in (None, "ok"),
            })
        rows.sort(key=lambda r: (r.get("priority") or 0))
        if rows:
            out[provider] = rows
    return {"providers": out, "checked_at": _now()}


def test_keys(provider, entry_id=None):
    cfg = load_config()
    base = str(_provider_conf(cfg, provider).get("base_url") or "").rstrip("/")
    url = base + "/v1/models"
    pool = _read_json(AUTH).get("credential_pool") or {}
    results = []
    for e in (pool.get(provider) or []):
        if entry_id and e.get("id") != entry_id:
            continue
        token = e.get("access_token")
        if not token:
            continue
        status, raw, ms = _http_json(url, {"Authorization": "Bearer " + token}, timeout=30)
        results.append({"id": e.get("id"), "label": e.get("label"), "status": status,
                        "ms": ms, "ok": status == 200,
                        "detail": "ok" if status == 200 else f"HTTP {status}: {raw[:140]}"})
    return {"provider": provider, "results": results}


def reset_keys(provider):
    store = _read_json(AUTH)
    pool = store.get("credential_pool") or {}
    cleared = 0
    for e in pool.get(provider) or []:
        for k in ("last_status", "last_status_at", "last_error_code", "last_error_reason",
                  "last_error_message", "last_error_reset_at"):
            if e.get(k) is not None:
                e[k] = None
                cleared += 1
    if cleared:
        _write_auth(store)
    return {"provider": provider, "cleared": cleared,
            "keys": keys_payload()["providers"].get(provider, [])}


def add_key(provider, label, key):
    store = _read_json(AUTH)
    pool = store.setdefault("credential_pool", {})
    entries = pool.setdefault(provider, [])
    base = _provider_conf(load_config(), provider).get("base_url") or ""
    entries.append({
        "id": uuid.uuid4().hex[:6],
        "label": label or f"key-{len(entries) + 1}",
        "auth_type": "api_key",
        "priority": len(entries),
        "source": "manual",
        "access_token": key,
        "last_status": None,
        "last_status_at": None,
        "last_error_code": None,
        "last_error_reason": None,
        "last_error_message": None,
        "last_error_reset_at": None,
        "base_url": base,
        "request_count": 0,
    })
    _write_auth(store)
    return {"ok": True, "provider": provider, "count": len(entries)}


def remove_key(provider, entry_id):
    store = _read_json(AUTH)
    pool = store.get("credential_pool") or {}
    entries = pool.get(provider) or []
    kept = [e for e in entries if e.get("id") != entry_id]
    pool[provider] = kept
    store["credential_pool"] = pool
    _write_auth(store)
    return {"ok": True, "provider": provider,
            "removed": len(entries) - len(kept), "count": len(kept)}


def _write_auth(store):
    tmp = AUTH + ".hcontrol.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(store, fh, indent=2, ensure_ascii=False)
    os.replace(tmp, AUTH)


def switch_model(provider, model):
    with CFG_LOCK:
        cfg = load_config()
        conf = _provider_conf(cfg, provider)
        if not conf:
            return {"ok": False, "error": f"provider '{provider}' not in config"}
        node = cfg.setdefault("model", {})
        node["provider"] = provider
        node["default"] = model
        if conf.get("api_mode"):
            node["api_mode"] = conf["api_mode"]
        backup = write_config(cfg)
    return {"ok": True, "model": f"{provider}/{model}",
            "backup": os.path.basename(backup), "restart_required": True}


def test_model(provider, model, prompt):
    cfg = load_config()
    conf = _provider_conf(cfg, provider)
    if not conf:
        return {"ok": False, "error": f"unknown provider '{provider}'"}
    base = str(conf.get("base_url") or "").rstrip("/")
    mode = conf.get("api_mode") or "chat_completions"
    token = _key_for(provider)
    if not token:
        return {"ok": False, "error": "no API key for provider"}
    prompt = (prompt or "Reply with exactly: OK").strip()
    text, usage = "", {}

    if mode == "anthropic_messages":
        status, raw, ms = _http_json(
            base + "/v1/messages",
            {"Authorization": "Bearer " + token, "anthropic-version": "2023-06-01"},
            {"model": model, "max_tokens": 96,
             "messages": [{"role": "user", "content": prompt}]}, timeout=90)
    elif mode == "codex_responses":
        status, raw, ms = _http_json(
            base + "/responses", {"Authorization": "Bearer " + token},
            {"model": model, "max_output_tokens": 96, "input": prompt}, timeout=90)
    else:
        status, raw, ms = _http_json(
            base + "/chat/completions", {"Authorization": "Bearer " + token},
            {"model": model, "max_tokens": 96,
             "messages": [{"role": "user", "content": prompt}]}, timeout=90)

    if status == 200:
        try:
            payload = json.loads(raw)
            if mode == "anthropic_messages":
                text = "".join(b.get("text", "") for b in payload.get("content", [])
                               if b.get("type") == "text")
            elif mode == "codex_responses":
                for item in payload.get("output", []):
                    for c in (item.get("content") or []):
                        if c.get("text"):
                            text += c["text"]
            else:
                choice = (payload.get("choices") or [{}])[0]
                text = (choice.get("message") or {}).get("content") or ""
            usage = payload.get("usage") or {}
        except Exception as exc:  # noqa: BLE001
            return {"ok": False, "status": status, "ms": ms, "mode": mode,
                    "error": f"bad JSON: {exc}"}

    if status != 200:
        return {"ok": False, "status": status, "ms": ms, "mode": mode,
                "error": (raw if isinstance(raw, str) else str(raw))[:300]}
    return {"ok": True, "status": status, "ms": ms, "mode": mode,
            "reply": text.strip()[:400], "usage": usage}


# ---------------------------------------------------------------------------
# system / logs / checks / cron
# ---------------------------------------------------------------------------
def system_payload():
    mem = {}
    for line in _read_text("/proc/meminfo").splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            mem[k.strip()] = v.strip()

    def kb(key):
        try:
            return int(mem.get(key, "0").split()[0])
        except Exception:  # noqa: BLE001
            return 0

    total, avail = kb("MemTotal"), kb("MemAvailable")
    swap_total, swap_free = kb("SwapTotal"), kb("SwapFree")

    storage = {}
    for label, path in (("داده", "/data"), ("حافظه", "/storage/emulated/0")):
        try:
            u = shutil.disk_usage(path)
            storage[label] = {"total": u.total, "used": u.used, "free": u.free,
                              "percent": round(u.used / u.total * 100, 1)}
        except Exception:  # noqa: BLE001
            pass

    battery = {}
    code, out = _run(["termux-battery-status"], timeout=12)
    if code == 0 and out.strip().startswith("{"):
        try:
            battery = json.loads(out)
        except Exception:  # noqa: BLE001
            battery = {}
    if not battery:
        try:
            battery = {"percentage": int(_read_text(
                "/sys/class/power_supply/battery/capacity").strip())}
        except Exception:  # noqa: BLE001
            battery = {}

    load = os.getloadavg() if hasattr(os, "getloadavg") else (0, 0, 0)
    procs = [{"pid": p, "uptime": _pid_uptime(int(p)), "rss_kb": _pid_rss(int(p))}
             for p in gateway_pids()]

    return {
        "battery": {"percent": battery.get("percentage"),
                    "status": battery.get("status"),
                    "plugged": battery.get("plugged"),
                    "temperature": battery.get("temperature")},
        "memory": {"total_kb": total, "available_kb": avail,
                   "used_kb": max(0, total - avail),
                   "percent": round((total - avail) / total * 100, 1) if total else 0,
                   "swap_total_kb": swap_total, "swap_free_kb": swap_free,
                   "swap_percent": round((swap_total - swap_free) / swap_total * 100, 1)
                   if swap_total else 0},
        "storage": storage,
        "load": {"1m": round(load[0], 2), "5m": round(load[1], 2), "15m": round(load[2], 2)},
        "uptime_seconds": _sys_uptime(),
        "gateway_procs": procs,
        "checked_at": _now(),
    }


LOG_FILES = ["gw-live.log", "gwctl.log", "gateway.log", "gw2.log", "gw3.log",
             "bridge.log", "gwctl-access.log"]


def logs_files():
    out = []
    for name in LOG_FILES:
        path = os.path.join(HERMES, name)
        if os.path.exists(path):
            st = os.stat(path)
            out.append({"name": name, "size": st.st_size,
                        "mtime": time.strftime("%Y-%m-%d %H:%M:%S",
                                               time.localtime(st.st_mtime))})
    out.sort(key=lambda r: r["mtime"], reverse=True)
    return out


def logs_tail(name, lines, needle):
    if name not in LOG_FILES:
        return {"error": "unknown log", "lines": []}
    path = os.path.join(HERMES, name)
    size = 0
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - 200_000))
            chunk = fh.read().decode("utf-8", "replace")
    except Exception as exc:  # noqa: BLE001
        return {"error": str(exc), "lines": []}
    rows = chunk.splitlines()
    if needle:
        n = needle.lower()
        rows = [r for r in rows if n in r.lower()]
    rows = rows[-max(1, min(int(lines or 120), 800)):]
    return {"file": name, "lines": rows, "size": size, "matched": len(rows)}


def checks_payload():
    results = []
    cfg = load_config()
    base = str(_provider_conf(cfg, "agentrouter").get("base_url") or "").rstrip("/")
    token = _key_for("agentrouter")

    def battery_opt():
        # /system/bin/dumpsys exists but DeviceIdleController needs the DUMP signature
        # permission, which a Termux uid does not have; `dumpsys` is not even on the
        # Termux PATH. Report "unknown" instead of a false FAIL — only `adb shell`
        # can read this.
        for path in ("/system/bin/dumpsys", "dumpsys"):
            code, out = _run([path, "deviceidle", "whitelist"], timeout=15)
            if "com.termux" in out:
                return True, "allowlisted"
            if "Permission Denial" in out or "not found" in out or code != 0:
                continue
            if out.strip():
                return False, "not allowlisted"
        return None, "از Termux قابل خواندن نیست (نیازمند adb)"

    def agentrouter():
        s, _raw, ms = _http_json(base + "/v1/models",
                                 {"Authorization": "Bearer " + token}, timeout=25)
        return s == 200, f"HTTP {s} · {ms} ms"

    def telegram():
        s, _raw, _ms = _http_json("https://api.telegram.org/", timeout=20)
        return s in (200, 302, 404), f"HTTP {s}"

    def dns():
        return True, socket.gethostbyname("agentrouter.org")

    def venv():
        p = os.path.join(PREFIX, "lib/hermes-agent/app/venv/bin/python")
        return os.path.exists(p), "venv OK" if os.path.exists(p) else "missing"

    def add_check(name, label, fn):
        started = time.time()
        try:
            result = fn()
        except Exception as exc:  # noqa: BLE001
            result = (False, str(exc)[:160])
        if isinstance(result, tuple) and len(result) == 2:
            ok, detail = result
        else:
            ok, detail = result, ""
        item = {"name": name, "label": label, "ms": int((time.time() - started) * 1000),
                "detail": str(detail)[:200]}
        if ok is None:
            item["ok"] = False
            item["skipped"] = True
        else:
            item["ok"] = bool(ok)
        results.append(item)

    add_check("agentrouter", "AgentRouter API", agentrouter)
    add_check("telegram", "Telegram API", telegram)
    add_check("dns", "DNS", dns)
    tg = gateway_status().get("telegram")
    results.append({"name": "telegram_bot", "label": "ربات تلگرام",
                    "ok": tg == "connected", "ms": 0, "detail": tg or "unknown"})
    add_check("venv", "محیط پایتون", venv)
    add_check("battery_opt", "معاف از بهینه‌سازی باتری", battery_opt)
    return {"checks": results, "checked_at": _now()}


def cron_payload():
    jobs = []
    for j in (_read_json(JOBS).get("jobs") or []):
        jobs.append({
            "id": j.get("id"),
            "name": j.get("name"),
            "schedule": (j.get("schedule") or {}).get("display") or j.get("schedule_display"),
            "state": j.get("state"),
            "last_status": j.get("last_status"),
            "last_run": j.get("last_run_at"),
            "next_run": j.get("next_run_at"),
            "error": (j.get("last_error") or "")[:200],
            "mode": "script" if j.get("no_agent") else "agent",
        })
    return {"jobs": jobs, "count": len(jobs)}


def cron_action(job_id, action):
    if action not in ("run", "pause", "resume"):
        return {"ok": False, "error": "bad action"}
    code, out = _run([HERMES_BIN, "cron", action, job_id], timeout=90)
    return {"ok": code == 0, "action": action, "code": code,
            "output": out.strip()[:400], "jobs": cron_payload()["jobs"]}


def agent_test(prompt):
    prompt = prompt or "reply with exactly the word: READY"
    started = time.time()
    code, out = _run([HERMES_BIN, "-z", prompt], timeout=180)
    return {"ok": code == 0, "code": code, "ms": int((time.time() - started) * 1000),
            "reply": out.strip()[-600:]}


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
class Handler(BaseHTTPRequestHandler):
    server_version = "gwctl/2.0"

    def _note(self, what):
        try:
            with open(os.path.join(HERMES, "gwctl-access.log"), "a", encoding="utf-8") as fh:
                fh.write(f"{_now()} {self.client_address[0]} {self.command} {what} "
                         f"ua={self.headers.get('User-Agent', '-')[:40]}\n")
        except Exception:  # noqa: BLE001
            pass

    def _send(self, payload, code=200):
        body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _body(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:  # noqa: BLE001
            return {}

    def _query(self):
        if "?" not in self.path:
            return {}
        return {k: v[0] for k, v in
                urllib.parse.parse_qs(self.path.split("?", 1)[1]).items()}

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0].rstrip("/") or "/"
        self._note(path)
        q = self._query()
        try:
            if path == "/ping":
                return self._send({"ok": True, "api": API_VERSION})
            if path == "/version":
                code, out = _run([HERMES_BIN, "--version"], timeout=40)
                first = out.strip().splitlines()[0] if out.strip() else ""
                return self._send({"api": API_VERSION, "gwctl": "2.0", "hermes": first})
            if path == "/status":
                st = gateway_status()
                st["log_tail"] = log_tail(6)
                st["api"] = API_VERSION
                return self._send(st)
            if path == "/system":
                return self._send(system_payload())
            if path == "/config":
                return self._send(config_snapshot())
            if path == "/models":
                return self._send(models_payload())
            if path == "/keys":
                return self._send(keys_payload())
            if path == "/logs/files":
                return self._send({"files": logs_files()})
            if path == "/logs":
                return self._send(logs_tail(q.get("file", "gw-live.log"),
                                            q.get("lines", 120), q.get("filter") or ""))
            if path == "/checks":
                return self._send(checks_payload())
            if path == "/cron":
                return self._send(cron_payload())
            return self._send({"error": "not found", "path": path}, 404)
        except Exception as exc:  # noqa: BLE001
            return self._send({"error": f"{type(exc).__name__}: {exc}"}, 500)

    def do_POST(self):  # noqa: N802
        path = self.path.split("?")[0].rstrip("/")
        self._note(path)
        body = self._body()
        try:
            if path == "/config/set":
                key = body.get("key") or ""
                field = next((f for f in FIELDS if f["key"] == key), None)
                if not field:
                    return self._send({"ok": False, "error": "field not editable"}, 400)
                value = coerce(field, body.get("value"))
                with CFG_LOCK:
                    cfg = load_config()
                    set_path(cfg, key, value)
                    backup = write_config(cfg)
                return self._send({"ok": True, "key": key, "value": value,
                                   "backup": os.path.basename(backup),
                                   "restart_required": key.startswith(RESTART_PREFIXES)})
            if path == "/model/switch":
                return self._send(switch_model(body.get("provider"), body.get("model")))
            if path == "/model/test":
                return self._send(test_model(body.get("provider"), body.get("model"),
                                             body.get("prompt")))
            if path == "/keys/test":
                return self._send(test_keys(body.get("provider"), body.get("id")))
            if path == "/keys/reset":
                return self._send(reset_keys(body.get("provider")))
            if path == "/keys/add":
                if not (body.get("provider") and body.get("key")):
                    return self._send({"ok": False, "error": "provider and key required"}, 400)
                return self._send(add_key(body["provider"], body.get("label"), body["key"]))
            if path == "/keys/remove":
                return self._send(remove_key(body.get("provider"), body.get("id")))
            if path == "/cron/action":
                return self._send(cron_action(body.get("id"), body.get("action")))
            if path == "/agent/test":
                return self._send(agent_test(body.get("prompt")))
            if path == "/gateway/restart":
                return self._send(restart_gateway())
            if path == "/gateway/stop":
                return self._send(stop_gateway())
            return self._send({"error": "not found", "path": path}, 404)
        except Exception as exc:  # noqa: BLE001
            return self._send({"error": f"{type(exc).__name__}: {exc}"}, 500)

    def log_message(self, fmt, *args):
        return


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    print(f"gwctl {API_VERSION} listening on http://127.0.0.1:{PORT}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
