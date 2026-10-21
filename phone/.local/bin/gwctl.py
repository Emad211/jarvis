#!/usr/bin/env python3
"""gwctl v3 — control server for the Hermes stack on this phone.

Loopback only (127.0.0.1:8777); the Hermes Control app is the client. There is no
auth token: the app cannot read Termux-private files to learn a secret, and only apps
already on this phone can reach a loopback port.

Most endpoints accept ?profile=<name> (default profile when omitted).

    GET  /ping                     liveness
    GET  /version                  gwctl + hermes versions
    GET  /status                   gateway health, telegram, model, uptime, log tail
    GET  /overview                 status of every profile + every service, in one call
    GET  /system                   battery, memory, storage, load
    GET  /services                 supervised services (hermes-svc) and their state
    POST /services/action          {name, action: start|stop|restart}
    GET  /profiles                 Hermes profiles with model + bot state
    GET  /config                   redacted config + editable field schema
    POST /config/set               {key, value}         (via `hermes config set`)
    GET  /models                   configured + fallback chain + live model list
    POST /model/switch             {provider, model}
    POST /model/test               {provider, model, prompt}
    GET  /fallback                 current fallback chain
    POST /fallback/set             {chain: [{provider, model}, ...]}
    GET  /keys                     credential pools (masked) + provider API-key slots
    POST /keys/add                 {provider, label, key}   (pool, via `hermes auth add`)
    POST /keys/remove              {provider, id}
    POST /keys/reset               {provider}
    POST /keys/test                {provider, id?}
    POST /env/set                  {name, value}   only *_API_KEY / *_TOKEN names
    GET  /logs?file=&lines=&filter=
    GET  /logs/files
    GET  /checks                   reachability: providers, telegram, VPN, bridge, router
    GET  /cron                     scheduled jobs
    POST /cron/action              {id, action: run|pause|resume}
    POST /agent/test               {prompt?}  end-to-end `hermes -z` round trip
    POST /send                     {text, target?}  message via `hermes send` (no LLM)
    POST /gateway/restart          restart this profile's gateway
    POST /gateway/stop             stop it
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
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import yaml  # gwctl runs under the Hermes venv, which ships PyYAML

HOME = os.path.expanduser("~")
ROOT = os.path.join(HOME, ".hermes")
PREFIX = "/data/data/com.termux/files/usr"
HERMES_BIN = os.path.join(PREFIX, "bin", "hermes")
SVC = os.path.join(HOME, ".local", "bin", "hermes-svc")
PORT = int(os.environ.get("GWCTL_PORT", "8777"))
API_VERSION = 3
VERSION = "3.0"

ENV = dict(os.environ, RISH_APPLICATION_ID="com.termux",
           PATH=f"{HOME}/.local/bin:{PREFIX}/bin:{os.environ.get('PATH', '')}")
WRITE_LOCK = threading.Lock()

# Base URLs for providers that Hermes ships as built-ins (not listed under `providers:`).
BUILTIN_BASES = {
    "openrouter": "https://openrouter.ai/api/v1",
    "gemini": "https://generativelanguage.googleapis.com/v1beta/openai",
    "nvidia": "https://integrate.api.nvidia.com/v1",
    "huggingface": "https://router.huggingface.co/v1",
    "deepseek": "https://api.deepseek.com/v1",
    "deepinfra": "https://api.deepinfra.com/v1/openai",
}
# Env var that holds each provider's key (shown as "slots" in the app's Keys tab).
KEY_SLOTS = [
    ("gemini", "GEMINI_API_KEY", "https://aistudio.google.com/apikey"),
    ("openrouter", "OPENROUTER_API_KEY", "https://openrouter.ai/keys"),
    ("groq", "GROQ_API_KEY", "https://console.groq.com/keys"),
    ("cerebras", "CEREBRAS_API_KEY", "https://cloud.cerebras.ai"),
    ("nvidia", "NVIDIA_API_KEY", "https://build.nvidia.com"),
    ("mistral", "MISTRAL_API_KEY", "https://console.mistral.ai/api-keys"),
    ("github", "GITHUB_TOKEN", "https://github.com/settings/tokens"),
    ("agentrouter", "AGENTROUTER_API_KEY", "https://agentrouter.org/console"),
    ("elevenlabs", "ELEVENLABS_API_KEY", "https://elevenlabs.io/app/settings/api-keys"),
]


# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
def run(cmd, timeout=30):
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, env=ENV)
        return p.returncode, ((p.stdout or "") + (p.stderr or "")).strip()
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except Exception as exc:  # noqa: BLE001
        return 1, str(exc)


def read_json(path):
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:  # noqa: BLE001
        return {}


def read_text(path):
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except Exception:  # noqa: BLE001
        return ""


def mask(secret):
    s = str(secret or "")
    if len(s) <= 10:
        return "•" * len(s)
    return f"{s[:6]}…{s[-4:]}"


def now():
    return time.strftime("%Y-%m-%d %H:%M:%S")


def home_of(profile):
    if not profile or profile == "default":
        return ROOT
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", profile):
        raise ValueError("bad profile name")
    path = os.path.join(ROOT, "profiles", profile)
    if not os.path.isdir(path):
        raise ValueError(f"no profile '{profile}'")
    return path


def hermes(profile, *args, timeout=60):
    cmd = [HERMES_BIN] + (["-p", profile] if profile and profile != "default" else []) + list(args)
    return run(cmd, timeout=timeout)


def load_config(profile=None):
    with open(os.path.join(home_of(profile), "config.yaml"), encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def get_path(cfg, path):
    cur = cfg
    for part in path.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def read_env(profile=None):
    out = {}
    for line in read_text(os.path.join(home_of(profile), ".env")).splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip().strip("\"'")
    return out


def write_env(profile, name, value):
    path = os.path.join(home_of(profile), ".env")
    lines = read_text(path).splitlines()
    lines = [ln for ln in lines if not ln.startswith(name + "=")]
    if value:
        lines.append(f"{name}={value}")
    tmp = path + ".gwctl.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")
    os.chmod(tmp, 0o600)
    os.replace(tmp, path)


def http_json(url, headers=None, body=None, timeout=25):
    h = {"Accept": "application/json", "User-Agent": "gwctl/3"}
    h.update(headers or {})
    data = json.dumps(body).encode() if body is not None else None
    if data:
        h["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=h, method="POST" if data else "GET")
    started = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace"), int((time.time() - started) * 1000)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace"), int((time.time() - started) * 1000)
    except Exception as e:  # noqa: BLE001
        return 0, str(e), int((time.time() - started) * 1000)


# ---------------------------------------------------------------------------
# processes / services
# ---------------------------------------------------------------------------
def _argv(pid):
    try:
        with open(f"/proc/{pid}/cmdline", "rb") as fh:
            return [a.decode("utf-8", "replace") for a in fh.read().split(b"\0") if a]
    except Exception:  # noqa: BLE001
        return []


def _is_gateway(argv, profile):
    """A real gateway process, not the tmux/shell wrapper whose -c string mentions it."""
    if not argv or os.path.basename(argv[0]) in ("sh", "bash", "tmux", "dash"):
        return False
    if "gateway" not in argv or "run" not in argv:
        return False
    named = argv[argv.index("-p") + 1] if "-p" in argv[:-1] else "default"
    return named == (profile or "default")


def gateway_pids(profile=None):
    state = read_json(os.path.join(home_of(profile), "gateway_state.json"))
    recorded = str(state.get("pid") or "")
    if recorded.isdigit() and _is_gateway(_argv(recorded), profile):
        return [recorded]
    return [e for e in os.listdir("/proc") if e.isdigit() and _is_gateway(_argv(e), profile)]


def pid_uptime(pid):
    # /proc/uptime is blocked for Termux; procps `ps -o etimes` answers directly.
    code, out = run(["ps", "-o", "etimes=", "-p", str(pid)], timeout=8)
    return int(out) if code == 0 and out.strip().isdigit() else None


def pid_rss(pid):
    for line in read_text(f"/proc/{pid}/status").splitlines():
        if line.startswith("VmRSS:"):
            return int(line.split()[1])
    return 0


def services():
    code, out = run(["sh", SVC, "list"], timeout=20)
    rows = []
    for line in out.splitlines():
        parts = line.split()
        if len(parts) == 2:
            rows.append({"name": parts[0], "running": parts[1] == "running"})
    return rows


def service_action(name, action):
    if action not in ("start", "stop", "restart"):
        return {"ok": False, "error": "bad action"}
    if not re.fullmatch(r"[a-z0-9_-]+", name or ""):
        return {"ok": False, "error": "bad service"}
    code, out = run(["sh", SVC, action, name], timeout=30)
    return {"ok": code == 0, "output": out[-400:], "services": services()}


# ---------------------------------------------------------------------------
# gateway status
# ---------------------------------------------------------------------------
def current_model(cfg):
    m = cfg.get("model") or {}
    return f"{m.get('provider', '')}/{m.get('default', '')}"


def gateway_status(profile=None):
    home = home_of(profile)
    pids = gateway_pids(profile)
    state = read_json(os.path.join(home, "gateway_state.json"))
    tg = (state.get("platforms") or {}).get("telegram") or {}
    tg_state = tg.get("state") or "unknown"
    fresh = str(state.get("pid") or "") in pids
    overall = "online" if pids and fresh else ("starting" if pids else "offline")
    try:
        model = current_model(load_config(profile))
    except Exception:  # noqa: BLE001
        model = ""
    pid = pids[0] if pids else None
    return {
        "profile": profile or "default",
        "overall": overall,
        "health": "ok" if overall == "online" and tg_state == "connected" else overall,
        "pid": pid,
        "pids": pids,
        "telegram": tg_state,
        "telegram_error": tg.get("error_message") or "",
        "needs_attention": bool(tg.get("needs_attention")),
        "state_fresh": fresh,
        "model": model,
        "version": state.get("code_version") or "",
        "uptime_seconds": pid_uptime(pid) if pid else None,
        "rss_kb": pid_rss(pid) if pid else 0,
        "active_agents": state.get("active_agents") or 0,
        "checked_at": now(),
    }


def log_tail(profile=None, lines=8):
    text = read_text(os.path.join(home_of(profile), "gw-live.log"))
    return [ln.rstrip() for ln in text.splitlines()[-lines:]]


def profile_names():
    names = ["default"]
    pdir = os.path.join(ROOT, "profiles")
    if os.path.isdir(pdir):
        names += sorted(d for d in os.listdir(pdir) if os.path.isdir(os.path.join(pdir, d)))
    return names


def profiles_payload():
    out = []
    for name in profile_names():
        st = gateway_status(name)
        env = read_env(name)
        soul = read_text(os.path.join(home_of(name), "SOUL.md")).strip().splitlines()
        out.append({"name": name, "model": st["model"], "overall": st["overall"],
                    "telegram": st["telegram"], "has_bot": bool(env.get("TELEGRAM_BOT_TOKEN")),
                    "soul": soul[0][:120] if soul else "", "service": "gw" if name == "default" else f"gw-{name}"})
    return {"profiles": out}


def svc_name(profile):
    return "gw" if not profile or profile == "default" else f"gw-{profile}"


def restart_gateway(profile=None, wait=40):
    before = gateway_pids(profile)
    code, out = run(["sh", SVC, "restart", svc_name(profile)], timeout=40)
    deadline, pids = time.time() + wait, []
    while time.time() < deadline:
        time.sleep(1.5)
        pids = [p for p in gateway_pids(profile) if p not in before] or []
        if pids:
            break
    return {"started": bool(pids), "killed": before, "pids": pids, "exit_code": code,
            "detail": out[-400:], "status": gateway_status(profile)}


def stop_gateway(profile=None):
    before = gateway_pids(profile)
    run(["sh", SVC, "stop", svc_name(profile)], timeout=20)
    for pid in gateway_pids(profile):
        run(["kill", "-TERM", pid], timeout=5)
    time.sleep(2)
    return {"killed": before, "remaining": gateway_pids(profile), "status": gateway_status(profile)}


# ---------------------------------------------------------------------------
# config
# ---------------------------------------------------------------------------
FIELDS = [
    {"key": "model.provider", "label": "پرووایدر فعال", "group": "مدل", "type": "enum", "source": "providers"},
    {"key": "model.default", "label": "مدل پیش‌فرض", "group": "مدل", "type": "str"},
    {"key": "agent.reasoning_effort", "label": "سطح استدلال", "group": "مدل", "type": "enum",
     "options": ["none", "minimal", "low", "medium", "high", "xhigh"]},
    {"key": "agent.max_turns", "label": "حداکثر نوبت عامل", "group": "مدل",
     "type": "int", "min": 10, "max": 1000, "step": 10},
    {"key": "delegation.max_concurrent_children", "label": "ساب‌ایجنت هم‌زمان", "group": "مدل",
     "type": "int", "min": 1, "max": 10, "step": 1},

    {"key": "approvals.mode", "label": "تأیید دستورهای خطرناک", "group": "دسترسی", "type": "enum",
     "options": ["off", "smart", "manual"]},
    {"key": "telegram.require_mention", "label": "در گروه فقط با منشن جواب بده", "group": "دسترسی",
     "type": "bool"},

    {"key": "streaming.enabled", "label": "استریم در چت", "group": "استریم", "type": "bool"},
    {"key": "streaming.edit_interval", "label": "فاصله به‌روزرسانی (ثانیه)", "group": "استریم",
     "type": "float", "min": 0.3, "max": 5.0, "step": 0.1},

    {"key": "voice.auto_tts", "label": "پاسخ صوتی خودکار", "group": "صدا", "type": "bool"},
    {"key": "tts.provider", "label": "سرویس گفتار (TTS)", "group": "صدا", "type": "enum",
     "options": ["elevenlabs", "edge", "openai", "none"]},
    {"key": "tts.elevenlabs.model_id", "label": "مدل صدا", "group": "صدا", "type": "enum",
     "options": ["eleven_v4_turbo", "eleven_v4", "eleven_v3"]},
    {"key": "tts.elevenlabs.voice_id", "label": "شناسه صدا", "group": "صدا", "type": "str"},
    {"key": "stt.language", "label": "زبان گفتار", "group": "صدا", "type": "enum", "options": ["fa", "en", "auto"]},

    {"key": "display.tool_progress", "label": "نمایش اجرای ابزارها", "group": "نمایش",
     "type": "enum", "options": ["all", "concise", "none"]},
    {"key": "display.show_reasoning", "label": "نمایش استدلال", "group": "نمایش", "type": "bool"},

    {"key": "compression.threshold_tokens", "label": "آستانه فشرده‌سازی (توکن)", "group": "سیستم",
     "type": "int", "min": 20000, "max": 900000, "step": 20000},
    {"key": "timezone", "label": "منطقه زمانی", "group": "سیستم", "type": "str"},
]
FIELD_BY_KEY = {f["key"]: f for f in FIELDS}
NO_RESTART = ("timezone",)


def coerce(field, raw):
    t = field.get("type")
    if t == "bool":
        return raw if isinstance(raw, bool) else str(raw).strip().lower() in ("1", "true", "yes", "on")
    if t == "int":
        v = int(float(raw))
        return max(field.get("min", v), min(field.get("max", v), v))
    if t == "float":
        v = round(float(raw), 2)
        return max(field.get("min", v), min(field.get("max", v), v))
    return "" if raw is None else str(raw)


def redact(obj):
    if isinstance(obj, dict):
        return {k: (mask(v) if re.search(r"api_key|token|secret|password", str(k), re.I)
                    and isinstance(v, (str, int)) and str(v) else redact(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [redact(v) for v in obj]
    return obj


def provider_names(cfg):
    names = set((cfg.get("providers") or {}).keys()) | set(BUILTIN_BASES)
    names.add((cfg.get("model") or {}).get("provider") or "")
    return sorted(n for n in names if n)


def config_snapshot(profile=None):
    cfg = load_config(profile)
    fields = []
    for f in FIELDS:
        item = dict(f, value=get_path(cfg, f["key"]))
        if f.get("source") == "providers":
            item["options"] = provider_names(cfg)
        fields.append(item)
    return {"profile": profile or "default", "model": current_model(cfg), "fields": fields,
            "raw": yaml.safe_dump(redact(cfg), allow_unicode=True, sort_keys=False)}


def set_config(profile, key, value):
    field = FIELD_BY_KEY.get(key)
    if not field:
        return {"ok": False, "error": "field not editable"}
    value = coerce(field, value)
    text = json.dumps(value) if isinstance(value, bool) else str(value)
    with WRITE_LOCK:
        code, out = hermes(profile, "config", "set", key, text.lower() if isinstance(value, bool) else text)
    return {"ok": code == 0, "key": key, "value": value, "detail": out[-300:],
            "restart_required": code == 0 and key not in NO_RESTART}


# ---------------------------------------------------------------------------
# models / fallback / keys
# ---------------------------------------------------------------------------
def model_items(models):
    """`models:` may be a dict (with metadata) or a plain list of ids."""
    if isinstance(models, dict):
        return [(str(k), v or {}) for k, v in models.items()]
    if isinstance(models, list):
        return [(str(m.get("id") or m.get("name")), m) if isinstance(m, dict) else (str(m), {})
                for m in models if m]
    return []


def provider_base(cfg, provider):
    conf = (cfg.get("providers") or {}).get(provider) or {}
    base = conf.get("api") or conf.get("base_url") or conf.get("url") or BUILTIN_BASES.get(provider, "")
    return str(base).rstrip("/"), conf


def key_for(profile, provider, cfg):
    pool = (read_json(os.path.join(home_of(profile), "auth.json")).get("credential_pool") or {})
    entries = [e for e in pool.get(provider) or [] if e.get("access_token")]
    for e in entries:
        if e.get("last_status") in (None, "ok"):
            return e["access_token"]
    if entries:
        return entries[0]["access_token"]
    env = read_env(profile)
    _base, conf = provider_base(cfg, provider)
    names = [conf.get("key_env")] + [s[1] for s in KEY_SLOTS if s[0] == provider] + \
        [provider.upper().replace("-", "_") + "_API_KEY"]
    for n in names:
        if n and env.get(n):
            return env[n]
    return conf.get("api_key") or ""


def provider_headers(cfg, provider):
    _base, conf = provider_base(cfg, provider)
    headers = dict(conf.get("extra_headers") or {})
    if provider == (cfg.get("model") or {}).get("provider"):
        headers.update((cfg.get("model") or {}).get("default_headers") or {})
    return headers


def models_payload(profile=None):
    cfg = load_config(profile)
    configured = []
    for pname, pdata in (cfg.get("providers") or {}).items():
        for mname, meta in model_items((pdata or {}).get("models")):
            configured.append({"provider": pname, "model": mname,
                               "display": meta.get("display_name") or mname,
                               "context": meta.get("context_length"),
                               "tools": meta.get("supports_tools"),
                               "vision": meta.get("supports_vision")})
    return {"current": current_model(cfg), "configured": configured,
            "fallback": cfg.get("fallback_providers") or []}


def set_fallback(profile, chain):
    clean = []
    for e in chain or []:
        if isinstance(e, dict) and e.get("provider") and e.get("model"):
            clean.append({k: v for k, v in e.items() if k in ("provider", "model", "base_url", "key_env")})
    path = os.path.join(home_of(profile), "config.yaml")
    with WRITE_LOCK:
        cfg = load_config(profile)
        cfg["fallback_providers"] = clean
        backup_dir = os.path.join(home_of(profile), "hcontrol-backups")
        os.makedirs(backup_dir, exist_ok=True)
        shutil.copy2(path, os.path.join(backup_dir, time.strftime("config-%Y%m%d-%H%M%S.yaml")))
        tmp = path + ".gwctl.tmp"
        with open(tmp, "w", encoding="utf-8") as fh:
            yaml.safe_dump(cfg, fh, sort_keys=False, allow_unicode=True)
        os.replace(tmp, path)
    return {"ok": True, "fallback": clean, "restart_required": True}


def switch_model(profile, provider, model):
    if not provider or not model:
        return {"ok": False, "error": "provider and model required"}
    with WRITE_LOCK:
        c1, o1 = hermes(profile, "config", "set", "model.provider", provider)
        c2, o2 = hermes(profile, "config", "set", "model.default", model)
    return {"ok": c1 == 0 and c2 == 0, "model": f"{provider}/{model}",
            "detail": (o1 + "\n" + o2)[-300:], "restart_required": True}


def test_model(profile, provider, model, prompt):
    cfg = load_config(profile)
    base, conf = provider_base(cfg, provider)
    if not base:
        return {"ok": False, "error": f"unknown provider '{provider}'"}
    token = key_for(profile, provider, cfg)
    mode = conf.get("transport") or conf.get("api_mode") or (
        (cfg.get("model") or {}).get("api_mode") if provider == (cfg.get("model") or {}).get("provider")
        else None) or "chat_completions"
    headers = dict(provider_headers(cfg, provider), Authorization="Bearer " + token)
    prompt = (prompt or "Reply with exactly: OK").strip()
    if mode == "anthropic_messages":
        url = base + ("/messages" if base.endswith("/v1") else "/v1/messages")
        body = {"model": model, "max_tokens": 128, "messages": [{"role": "user", "content": prompt}]}
        headers["anthropic-version"] = "2023-06-01"
    else:
        url = base + "/chat/completions"
        body = {"model": model, "max_tokens": 128, "messages": [{"role": "user", "content": prompt}]}
    status, raw, ms = http_json(url, headers, body, timeout=90)
    if status != 200:
        return {"ok": False, "status": status, "ms": ms, "mode": mode, "error": raw[:300]}
    try:
        payload = json.loads(raw)
        if mode == "anthropic_messages":
            text = "".join(b.get("text", "") for b in payload.get("content", []) if b.get("type") == "text")
        else:
            text = ((payload.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "status": status, "ms": ms, "mode": mode, "error": f"bad JSON: {exc}"}
    return {"ok": True, "status": status, "ms": ms, "mode": mode, "reply": text.strip()[:400],
            "usage": payload.get("usage") or {}}


def keys_payload(profile=None):
    pool = read_json(os.path.join(home_of(profile), "auth.json")).get("credential_pool") or {}
    t = time.time()
    providers = {}
    for provider, entries in pool.items():
        rows = []
        for e in entries or []:
            reset = e.get("last_error_reset_at")
            rows.append({
                "id": e.get("id"), "label": e.get("label"), "priority": e.get("priority"),
                "source": e.get("source"), "status": e.get("last_status") or "ok",
                "error": (e.get("last_error_message") or "")[:180],
                "cooldown": int(reset - t) if isinstance(reset, (int, float)) and reset > t else None,
                "requests": e.get("request_count") or 0, "key": mask(e.get("access_token")),
            })
        if rows:
            providers[provider] = sorted(rows, key=lambda r: r.get("priority") or 0)
    env = read_env(profile)
    slots = [{"provider": p, "env": n, "url": u, "set": bool(env.get(n)), "key": mask(env.get(n))}
             for p, n, u in KEY_SLOTS]
    return {"providers": providers, "slots": slots, "checked_at": now()}


def add_key(profile, provider, label, key):
    args = ["auth", "add", provider, "--type", "api-key", "--api-key", key]
    if label:
        args += ["--label", label]
    with WRITE_LOCK:
        code, out = hermes(profile, *args, timeout=60)
    return {"ok": code == 0, "detail": out[-300:]}


def remove_key(profile, provider, entry_id):
    with WRITE_LOCK:
        code, out = hermes(profile, "auth", "remove", provider, entry_id, timeout=60)
    return {"ok": code == 0, "detail": out[-300:]}


def reset_keys(profile, provider):
    with WRITE_LOCK:
        code, out = hermes(profile, "auth", "reset", provider, timeout=60)
    return {"ok": code == 0, "detail": out[-300:]}


def test_keys(profile, provider, entry_id=None):
    cfg = load_config(profile)
    base, _conf = provider_base(cfg, provider)
    pool = read_json(os.path.join(home_of(profile), "auth.json")).get("credential_pool") or {}
    headers = provider_headers(cfg, provider)
    results = []
    for e in pool.get(provider) or []:
        if (entry_id and e.get("id") != entry_id) or not e.get("access_token"):
            continue
        status, raw, ms = http_json(base + ("/models" if base.endswith("/v1") else "/v1/models"),
                                    dict(headers, Authorization="Bearer " + e["access_token"]), timeout=30)
        results.append({"id": e.get("id"), "label": e.get("label"), "status": status, "ms": ms,
                        "ok": status == 200, "detail": "ok" if status == 200 else f"HTTP {status}: {raw[:140]}"})
    return {"provider": provider, "results": results}


def set_env(profile, name, value):
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*_(API_KEY|TOKEN)", name or ""):
        return {"ok": False, "error": "only *_API_KEY / *_TOKEN names"}
    with WRITE_LOCK:
        write_env(profile, name, (value or "").strip())
    return {"ok": True, "name": name, "set": bool(value), "restart_required": True}


# ---------------------------------------------------------------------------
# system / logs / checks / cron
# ---------------------------------------------------------------------------
def system_payload():
    mem = {}
    for line in read_text("/proc/meminfo").splitlines():
        k, _, v = line.partition(":")
        mem[k.strip()] = int((v.split() or ["0"])[0])
    total, avail = mem.get("MemTotal", 0), mem.get("MemAvailable", 0)
    swap_total, swap_free = mem.get("SwapTotal", 0), mem.get("SwapFree", 0)

    storage = {}
    for label, path in (("داده", "/data"), ("حافظه", "/storage/emulated/0")):
        try:
            u = shutil.disk_usage(path)
            storage[label] = {"total": u.total, "used": u.used, "free": u.free,
                              "percent": round(u.used / u.total * 100, 1)}
        except Exception:  # noqa: BLE001
            pass

    battery = {}
    code, out = run(["termux-battery-status"], timeout=12)
    if code == 0 and out.startswith("{"):
        battery = json.loads(out)
    load = os.getloadavg()
    return {
        "battery": {"percent": battery.get("percentage"), "status": battery.get("status"),
                    "plugged": battery.get("plugged"), "temperature": battery.get("temperature")},
        "memory": {"total_kb": total, "available_kb": avail, "used_kb": max(0, total - avail),
                   "percent": round((total - avail) / total * 100, 1) if total else 0,
                   "swap_total_kb": swap_total, "swap_free_kb": swap_free,
                   "swap_percent": round((swap_total - swap_free) / swap_total * 100, 1) if swap_total else 0},
        "storage": storage,
        "load": {"1m": round(load[0], 2), "5m": round(load[1], 2), "15m": round(load[2], 2)},
        "checked_at": now(),
    }


LOG_FILES = ["gw-live.log", "gwctl.log", "bridge.log", "router.log", "boot.log"]


def logs_files(profile=None):
    out = []
    for name in LOG_FILES:
        path = os.path.join(home_of(profile) if name == "gw-live.log" else ROOT, name)
        if os.path.exists(path):
            st = os.stat(path)
            out.append({"name": name, "size": st.st_size,
                        "mtime": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(st.st_mtime))})
    return sorted(out, key=lambda r: r["mtime"], reverse=True)


def logs_tail(profile, name, lines, needle):
    if name not in LOG_FILES:
        return {"error": "unknown log", "lines": []}
    path = os.path.join(home_of(profile) if name == "gw-live.log" else ROOT, name)
    try:
        with open(path, "rb") as fh:
            fh.seek(0, os.SEEK_END)
            size = fh.tell()
            fh.seek(max(0, size - 300_000))
            rows = fh.read().decode("utf-8", "replace").splitlines()
    except Exception as exc:  # noqa: BLE001
        return {"error": str(exc), "lines": []}
    if needle:
        rows = [r for r in rows if needle.lower() in r.lower()]
    rows = rows[-max(1, min(int(lines or 150), 1000)):]
    return {"file": name, "lines": rows, "size": size, "matched": len(rows)}


def checks_payload(profile=None):
    cfg = load_config(profile)
    results = []

    def add(name, label, fn):
        started = time.time()
        try:
            ok, detail = fn()
        except Exception as exc:  # noqa: BLE001
            ok, detail = False, str(exc)[:160]
        item = {"name": name, "label": label, "ok": bool(ok), "detail": str(detail)[:200],
                "ms": int((time.time() - started) * 1000)}
        if ok is None:
            item.update(ok=False, skipped=True)
        results.append(item)

    def reach(url, headers=None, good=(200,)):
        def fn():
            s, _raw, ms = http_json(url, headers, timeout=15)
            return s in good, f"HTTP {s} · {ms} ms"
        return fn

    def provider_check(provider):
        base, _conf = provider_base(cfg, provider)
        token = key_for(profile, provider, cfg)
        if not token:
            return lambda: (None, "کلید ندارد")
        url = base + ("/models" if base.endswith(("/v1", "/openai", "/api/v1")) else "/v1/models")
        return reach(url, dict(provider_headers(cfg, provider), Authorization="Bearer " + token))

    def vpn():
        # /sys/class/net is unreadable for the Termux uid; the shell uid (rish) may list it.
        # tunl0 is the kernel's always-present IPIP device, not a VPN.
        code, out = run(["rish", "-c", "ls /sys/class/net"], timeout=10)
        if code != 0:
            return None, "rish در دسترس نیست"
        tun = [n for n in out.split() if re.fullmatch(r"tun\d+|wg\w*|ppp\d+", n)]
        return (True, ", ".join(tun)) if tun else (False, "VPN روشن نیست")

    def dns():
        return True, "api.telegram.org → " + socket.gethostbyname("api.telegram.org")

    def battery_opt():
        code, out = run(["rish", "-c", "dumpsys deviceidle whitelist"], timeout=15)
        if code != 0:
            return None, "rish در دسترس نیست"
        missing = [p for p in ("com.termux", "ir.emad.hermesctl") if p not in out]
        return not missing, "همه معاف" if not missing else "معاف نیست: " + ", ".join(missing)

    add("vpn", "VPN", vpn)
    add("telegram", "Telegram API", reach("https://api.telegram.org/", good=(200, 302, 404)))
    add("dns", "DNS تلگرام", dns)
    st = gateway_status(profile)
    results.append({"name": "telegram_bot", "label": "ربات تلگرام", "ok": st["telegram"] == "connected",
                    "ms": 0, "detail": st["telegram_error"] or st["telegram"]})
    seen = {(cfg.get("model") or {}).get("provider")} | \
        {e.get("provider") for e in cfg.get("fallback_providers") or [] if isinstance(e, dict)}
    for p in sorted(x for x in seen if x):
        add("provider:" + p, "مدل: " + p, provider_check(p))
    add("bridge", "پل Antigravity", reach("http://127.0.0.1:8790/health"))
    add("router", "OmniRoute", reach("http://127.0.0.1:20128/v1/models", good=(200, 401)))
    add("battery_opt", "معافیت باتری", battery_opt)
    return {"checks": results, "checked_at": now()}


def cron_payload(profile=None):
    jobs = []
    for j in read_json(os.path.join(home_of(profile), "cron", "jobs.json")).get("jobs") or []:
        jobs.append({"id": j.get("id"), "name": j.get("name"),
                     "schedule": (j.get("schedule") or {}).get("display") or j.get("schedule_display"),
                     "state": j.get("state"), "last_status": j.get("last_status"),
                     "last_run": j.get("last_run_at"), "next_run": j.get("next_run_at"),
                     "error": (j.get("last_error") or "")[:200],
                     "mode": "script" if j.get("no_agent") else "agent"})
    return {"jobs": jobs, "count": len(jobs)}


def cron_action(profile, job_id, action):
    if action not in ("run", "pause", "resume"):
        return {"ok": False, "error": "bad action"}
    code, out = hermes(profile, "cron", action, job_id, timeout=90)
    return {"ok": code == 0, "action": action, "output": out[-400:], "jobs": cron_payload(profile)["jobs"]}


def agent_test(profile, prompt):
    started = time.time()
    code, out = hermes(profile, "-z", prompt or "reply with exactly the word: READY", timeout=180)
    return {"ok": code == 0, "code": code, "ms": int((time.time() - started) * 1000), "reply": out[-600:]}


def send_message(profile, text, target):
    if not text:
        return {"ok": False, "error": "empty"}
    code, out = hermes(profile, "send", "-t", target or "telegram", "--json", text, timeout=60)
    return {"ok": code == 0, "detail": out[-300:]}


def overview():
    return {"profiles": [gateway_status(p) for p in profile_names()], "services": services(),
            "checked_at": now()}


# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------
GET_ROUTES = {
    "/ping": lambda q, p: {"ok": True, "api": API_VERSION},
    "/version": lambda q, p: {"api": API_VERSION, "gwctl": VERSION,
                              "hermes": (hermes(None, "--version", timeout=40)[1].splitlines() or [""])[0]},
    "/status": lambda q, p: dict(gateway_status(p), log_tail=log_tail(p, 6), api=API_VERSION),
    "/overview": lambda q, p: overview(),
    "/system": lambda q, p: system_payload(),
    "/services": lambda q, p: {"services": services()},
    "/profiles": lambda q, p: profiles_payload(),
    "/config": lambda q, p: config_snapshot(p),
    "/models": lambda q, p: models_payload(p),
    "/fallback": lambda q, p: {"fallback": load_config(p).get("fallback_providers") or []},
    "/keys": lambda q, p: keys_payload(p),
    "/logs/files": lambda q, p: {"files": logs_files(p)},
    "/logs": lambda q, p: logs_tail(p, q.get("file", "gw-live.log"), q.get("lines", 150), q.get("filter", "")),
    "/checks": lambda q, p: checks_payload(p),
    "/cron": lambda q, p: cron_payload(p),
}
POST_ROUTES = {
    "/services/action": lambda b, p: service_action(b.get("name"), b.get("action")),
    "/config/set": lambda b, p: set_config(p, b.get("key"), b.get("value")),
    "/model/switch": lambda b, p: switch_model(p, b.get("provider"), b.get("model")),
    "/model/test": lambda b, p: test_model(p, b.get("provider"), b.get("model"), b.get("prompt")),
    "/fallback/set": lambda b, p: set_fallback(p, b.get("chain")),
    "/keys/add": lambda b, p: add_key(p, b.get("provider"), b.get("label"), b.get("key"))
    if b.get("provider") and b.get("key") else {"ok": False, "error": "provider and key required"},
    "/keys/remove": lambda b, p: remove_key(p, b.get("provider"), b.get("id")),
    "/keys/reset": lambda b, p: reset_keys(p, b.get("provider")),
    "/keys/test": lambda b, p: test_keys(p, b.get("provider"), b.get("id")),
    "/env/set": lambda b, p: set_env(p, b.get("name"), b.get("value")),
    "/cron/action": lambda b, p: cron_action(p, b.get("id"), b.get("action")),
    "/agent/test": lambda b, p: agent_test(p, b.get("prompt")),
    "/send": lambda b, p: send_message(p, b.get("text"), b.get("target")),
    "/gateway/restart": lambda b, p: restart_gateway(p),
    "/gateway/stop": lambda b, p: stop_gateway(p),
}


class Handler(BaseHTTPRequestHandler):
    server_version = "gwctl/" + VERSION

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

    def _dispatch(self, routes, arg):
        path, _, query = self.path.partition("?")
        path = path.rstrip("/") or "/"
        q = {k: v[0] for k, v in urllib.parse.parse_qs(query).items()}
        handler = routes.get(path)
        if not handler:
            return self._send({"error": "not found", "path": path}, 404)
        try:
            profile = q.get("profile") or (arg or {}).get("profile") or None
            home_of(profile)  # validate early
            return self._send(handler(q if arg is None else arg, profile))
        except ValueError as exc:
            return self._send({"ok": False, "error": str(exc)}, 400)
        except Exception as exc:  # noqa: BLE001
            return self._send({"ok": False, "error": f"{type(exc).__name__}: {exc}"}, 500)

    def do_GET(self):  # noqa: N802
        self._dispatch(GET_ROUTES, None)

    def do_POST(self):  # noqa: N802
        try:
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}") if length else {}
        except Exception:  # noqa: BLE001
            body = {}
        self._dispatch(POST_ROUTES, body if isinstance(body, dict) else {})

    def log_message(self, fmt, *args):
        return


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    print(f"gwctl {VERSION} listening on http://127.0.0.1:{PORT}", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
