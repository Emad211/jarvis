#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_tools — skills, curator, memory, MCP, plugins, tools and cron for the console.

Every route here is a thin, fail-soft wrapper over the `hermes` CLI (plus the files the
CLI itself writes). Two rules shape the code:

* **Never hang.** Several `hermes` subcommands ask questions on stdin (``mcp add`` is
  discovery-first, ``mcp remove`` confirms, ``plugins enable`` asks for capability
  grants). jarvisd's stdin is a tmux tty, so an inherited stdin would block the daemon
  forever. Every CLI call here runs with ``stdin=DEVNULL``: EOF makes the CLI take its
  documented default and return.
* **Never 500.** A missing file, an empty registry or a CLI that changed its table is an
  empty list or ``{"ok": false, "error": "unsupported"}`` — never an exception.

API (see docs/jarvis-api.md):
    GET  /skills  /skills/feed  /curator  /memory  /mcp  /tools  /plugins  /cron
    POST /skills/{id}/toggle  /curator/run  /mcp  /mcp/{name}/test  /plugins/{name}/toggle
         /cron  /cron/{id}/run  /cron/{id}/pause
    DELETE /mcp/{name}  /cron/{id}

Import-safe: no file reads, no subprocess, no network at import time.
"""

from __future__ import annotations

# Imported at module level on purpose: with postponed annotations (the line above) FastAPI
# resolves route annotations against *this* namespace, so a Request imported inside register()
# would silently become a required query parameter instead of the request body.
from fastapi import Request
from fastapi.responses import JSONResponse

import json
import os
import re
import subprocess
import threading
import time
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple

SKILL_DIR_NAMES = (".archive", ".curator_backups")
NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,39}$")
ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,40}$")
SECRET_RE = re.compile(
    r"(sk-[A-Za-z0-9]{8,}|AIza[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{10,}"
    r"|Bearer\s+[A-Za-z0-9._-]{8,}|eyJ[A-Za-z0-9._-]{20,})")

_CACHE: Dict[str, Tuple[float, Any]] = {}
_CACHE_LOCK = threading.Lock()
_CURATOR_RUN = {"busy": False}


# --------------------------------------------------------------------------- helpers --
def _now() -> float:
    return time.time()


def _cached(key: str, ttl: float, fn):
    """Tiny TTL cache: the CLI costs 2–4 s per call and the console polls."""
    stamp, val = _CACHE.get(key, (0.0, None))
    if val is not None and (_now() - stamp) < ttl:
        return val
    val = fn()
    with _CACHE_LOCK:
        _CACHE[key] = (_now(), val)
    return val


def _run(ctx, *args: str, timeout: int = 45) -> Tuple[int, str]:
    """Run the hermes CLI with a closed stdin. Falls back to ctx["hermes"]."""
    env = dict(os.environ)
    env["HOME"] = ctx["HOME"]
    env.setdefault("TERM", "dumb")
    prefix = os.environ.get("PREFIX") or "/data/data/com.termux/files/usr"
    env["PATH"] = os.pathsep.join([os.path.join(ctx["HOME"], ".local", "bin"),
                                   os.path.join(prefix, "bin"), env.get("PATH", "")])
    try:
        proc = subprocess.run(["hermes", *args], capture_output=True, text=True,
                              timeout=timeout, env=env, stdin=subprocess.DEVNULL)
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")
    except FileNotFoundError:
        try:
            return ctx["hermes"](*args)
        except Exception as exc:  # noqa: BLE001
            return 127, str(exc)
    except subprocess.TimeoutExpired:
        return 124, "hermes %s timed out after %ss" % (" ".join(args), timeout)
    except OSError as exc:
        return 126, str(exc)


def _redact(line: str) -> str:
    """No key has any business travelling to the console."""
    return SECRET_RE.sub("***", line)


def _iso_to_epoch(value: Any) -> float:
    if not value:
        return 0.0
    if isinstance(value, (int, float)):
        return float(value)
    text = str(value).strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        return datetime.fromisoformat(text).timestamp()
    except ValueError:
        return 0.0


def _load_yaml(ctx) -> Dict[str, Any]:
    """config.yaml, when PyYAML is importable (it is, in the daemon's venv)."""
    try:
        import yaml
        with open(os.path.join(ctx["HERMES"], "config.yaml"), encoding="utf-8") as fh:
            data = yaml.safe_load(fh) or {}
        return data if isinstance(data, dict) else {}
    except Exception:  # noqa: BLE001
        return {}


def _split_row(line: str) -> List[str]:
    return [c.strip() for c in re.split(r"\s{2,}", line.strip()) if c.strip()]


# ---------------------------------------------------------------------------- skills --
def _frontmatter(path: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.read(6000).splitlines()
    except OSError:
        return out
    if not lines or lines[0].strip() != "---":
        return out
    key = ""
    for raw in lines[1:40]:
        if raw.strip() == "---":
            break
        if not raw.strip():
            continue
        if raw[:1] in (" ", "\t") and key:
            out[key] = (out.get(key, "") + " " + raw.strip()).strip()
            continue
        if ":" in raw:
            key, val = raw.split(":", 1)
            key = key.strip().lower()
            val = val.strip()
            out[key] = "" if val in (">", "|", ">-", "|-") else val.strip("'\"")
    return out


def _skill_files(ctx) -> List[Dict[str, Any]]:
    """Every SKILL.md under ~/.hermes/skills, with frontmatter, category and mtime."""
    root = os.path.join(ctx["HERMES"], "skills")
    rows: List[Dict[str, Any]] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKILL_DIR_NAMES and not d.startswith(".")]
        if "SKILL.md" not in filenames:
            continue
        rel = os.path.relpath(dirpath, root)
        parts = [] if rel == "." else rel.split(os.sep)
        path = os.path.join(dirpath, "SKILL.md")
        meta = _frontmatter(path)
        name = (meta.get("name") or (parts[-1] if parts else rel)).strip()
        try:
            updated = int(os.path.getmtime(path))
        except OSError:
            updated = 0
        rows.append({"id": name, "name": name,
                     "description": _redact(meta.get("description", ""))[:300],
                     "scope": parts[0] if len(parts) > 1 else "",
                     "path": path, "updated": updated})
    rows.sort(key=lambda r: r["id"])
    return rows


def _skills_table(ctx) -> Dict[str, Dict[str, str]]:
    """`hermes skills list` (a Rich table) -> {name: {category, source, trust, status}}."""
    def build() -> Dict[str, Dict[str, str]]:
        code, out = _run(ctx, "skills", "list", timeout=60)
        rows: Dict[str, Dict[str, str]] = {}
        for line in out.splitlines():
            if "│" not in line:
                continue
            cells = [c.strip() for c in line.split("│") if c.strip()]
            if len(cells) < 5 or cells[0].lower() == "name":
                continue
            rows[cells[0]] = {"category": cells[1], "source": cells[2],
                              "trust": cells[3], "status": cells[4]}
        return rows
    return _cached("skills_table", 30.0, build)


def _disabled_skills(ctx) -> List[str]:
    cfg = _load_yaml(ctx).get("skills")
    if isinstance(cfg, dict):
        raw = cfg.get("disabled")
        if isinstance(raw, str):
            return [raw.strip()] if raw.strip() else []
        if isinstance(raw, list):
            return [str(x).strip() for x in raw if str(x).strip()]
        return []
    code, out = _run(ctx, "config", "get", "skills.disabled", timeout=30)
    return [ln.strip()[2:].strip() for ln in out.splitlines() if ln.strip().startswith("- ")]


def _usage(ctx) -> Dict[str, Dict[str, Any]]:
    """`hermes curator usage --json` -> {skill: record} (uses, state, pinned)."""
    def build() -> Dict[str, Dict[str, Any]]:
        code, out = _run(ctx, "curator", "usage", "--json", timeout=60)
        start = out.find("[")
        if start < 0:
            return {}
        try:
            data = json.loads(out[start:])
        except ValueError:
            return {}
        return {str(r.get("name")): r for r in data if isinstance(r, dict) and r.get("name")}
    return _cached("usage", 30.0, build)


def _journey(ctx) -> Dict[str, Any]:
    def build() -> Dict[str, Any]:
        code, out = _run(ctx, "journey", "--json", timeout=60)
        start = out.find("{")
        if start < 0:
            return {}
        try:
            data = json.loads(out[start:])
        except ValueError:
            return {}
        return data if isinstance(data, dict) else {}
    return _cached("journey", 60.0, build)


def _skills_rows(ctx, refresh: bool = False) -> List[Dict[str, Any]]:
    if refresh:
        with _CACHE_LOCK:
            _CACHE.pop("skills_table", None)
            _CACHE.pop("usage", None)
    files = _skill_files(ctx)
    table = _skills_table(ctx)
    usage = _usage(ctx)
    disabled = set(_disabled_skills(ctx))
    rows = []
    for row in files:
        meta = table.get(row["id"]) or table.get(os.path.basename(os.path.dirname(row["path"]))) or {}
        status = meta.get("status", "")
        enabled = (row["id"] not in disabled) and status != "disabled"
        rec = usage.get(row["id"]) or {}
        rows.append({"id": row["id"], "name": row["name"],
                     "description": row["description"],
                     "scope": row["scope"] or meta.get("category", ""),
                     "source": meta.get("source", "local"),
                     "enabled": bool(enabled),
                     "uses": int(rec.get("use_count") or 0),
                     "updated": row["updated"]})
    return rows


def _ledger_entries(ctx) -> List[Dict[str, Any]]:
    """~/.hermes/skills/.curator_ledger.jsonl — who changed which skill, and when."""
    path = os.path.join(ctx["HERMES"], "skills", ".curator_ledger.jsonl")
    out: List[Dict[str, Any]] = []
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except ValueError:
                    continue
                if isinstance(row, dict):
                    out.append(row)
    except OSError:
        pass
    return out


KIND_BY_ACTION = {
    "write_file": "learned", "create": "learned", "create_dir": "learned",
    "restore": "learned", "adopt": "learned",
    "patch": "edited", "edit": "edited", "rollback": "edited", "update": "edited",
    "archive": "archived", "prune": "archived",
}
KIND_TITLE = {"learned": "یادگیری مهارت %s", "edited": "ویرایش مهارت %s",
              "archived": "بایگانی مهارت %s"}


def _skills_feed(ctx, limit: int) -> List[Dict[str, Any]]:
    """The visible self-improvement timeline: ledger first, journey second, files last."""
    items: List[Dict[str, Any]] = []
    for row in _ledger_entries(ctx):
        action = str(row.get("action") or "")
        kind = KIND_BY_ACTION.get(action)
        skill = str(row.get("skill") or "")
        if not kind or not skill:
            continue
        items.append({"id": "led-%s" % (row.get("id") or ""),
                      "title": KIND_TITLE[kind] % skill,
                      "at": _iso_to_epoch(row.get("ts")),
                      "kind": kind,
                      "detail": _redact("%s %s %s" % (row.get("actor") or "?", action, skill)),
                      "skill": skill, "actor": str(row.get("actor") or "")})
    seen = {(i["skill"], i["kind"]) for i in items}
    for node in (_journey(ctx).get("nodes") or []):
        if not isinstance(node, dict) or node.get("kind") != "skill":
            continue
        if str(node.get("createdBy") or "") != "agent":
            continue
        skill = str(node.get("id") or node.get("label") or "")
        if not skill or (skill, "learned") in seen:
            continue
        items.append({"id": "jn-%s" % skill, "title": KIND_TITLE["learned"] % skill,
                      "at": float(node.get("timestamp") or 0), "kind": "learned",
                      "detail": _redact("agent created %s" % skill),
                      "skill": skill, "actor": "agent"})
    if not items:
        for row in _skill_files(ctx):
            items.append({"id": "fs-%s" % row["id"],
                          "title": KIND_TITLE["learned"] % row["id"],
                          "at": float(row["updated"]), "kind": "learned",
                          "detail": "file mtime", "skill": row["id"], "actor": ""})
    items.sort(key=lambda i: i["at"], reverse=True)
    return items[:limit]


def _curator_rows(ctx) -> Dict[str, Any]:
    state_path = os.path.join(ctx["HERMES"], "skills", ".curator_state")
    state: Dict[str, Any] = {}
    try:
        with open(state_path, encoding="utf-8") as fh:
            raw = json.load(fh)
        if isinstance(raw, dict):
            state = raw
    except (OSError, ValueError):
        state = {}
    usage = _usage(ctx)
    records = list(usage.values())
    managed = len(records)
    archived = sum(1 for r in records if str(r.get("state")) == "archived")
    pinned = sum(1 for r in records if r.get("pinned"))
    paused = bool(state.get("paused"))
    last_run = _iso_to_epoch(state.get("last_run_at"))
    if not state:
        code, out = _run(ctx, "curator", "status", timeout=60)
        text = out
        paused = "curator: ENABLED" not in text and "curator: PAUSED" in text
        m = re.search(r"curator-managed skills:\s*(\d+)", text)
        if m:
            managed = int(m.group(1))
        m = re.search(r"archived\s+(\d+)", text)
        if m:
            archived = int(m.group(1))
    return {"enabled": not paused, "last_run": last_run, "managed": managed,
            "archived": archived, "pinned": pinned,
            "runs": int(state.get("run_count") or 0),
            "last_summary": _redact(str(state.get("last_run_summary") or ""))}


# ---------------------------------------------------------------------------- memory --
def _memory_rows(ctx) -> Dict[str, Any]:
    base = os.path.join(ctx["HERMES"], "memories")
    facts: List[str] = []
    items: List[Dict[str, Any]] = []
    updated = 0.0
    for name, source in (("MEMORY.md", "memory"), ("USER.md", "user")):
        path = os.path.join(base, name)
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            updated = max(updated, os.path.getmtime(path))
        except OSError:
            continue
        for chunk in re.split(r"(?m)^\s*§\s*$", text):
            chunk = _redact(chunk.strip())
            if not chunk or chunk.startswith("#"):
                continue
            facts.append(chunk)
            items.append({"source": source, "text": chunk})
    nodes = _journey(ctx).get("nodes") or []
    return {"facts": facts, "graph_nodes": len(nodes) if isinstance(nodes, list) else 0,
            "updated": int(updated), "items": items}


# ------------------------------------------------------------------------------- MCP --
def _mcp_rows(ctx) -> List[Dict[str, Any]]:
    """Configured MCP servers. config.yaml keeps the full target; the CLI table
    truncates long commands, so it is only the fallback."""
    servers = _load_yaml(ctx).get("mcp_servers")
    rows: List[Dict[str, Any]] = []
    if isinstance(servers, dict) and servers:
        for name, cfg in servers.items():
            if not isinstance(cfg, dict):
                continue
            url = str(cfg.get("url") or "")
            command = str(cfg.get("command") or "")
            args = [str(a) for a in (cfg.get("args") or [])]
            target = url or " ".join([command, *args]).strip()
            if not target:
                continue
            tools = cfg.get("tools")
            rows.append({"name": str(name),
                         "transport": "http" if url else "stdio",
                         "target": _redact(target),
                         "status": "enabled" if cfg.get("enabled", True) else "disabled",
                         "tools": [str(t) for t in tools] if isinstance(tools, list) else [],
                         "enabled": bool(cfg.get("enabled", True))})
        return rows
    code, out = _run(ctx, "mcp", "list", timeout=45)
    for line in out.splitlines():
        if "─" in line or not line.strip():
            continue
        cells = _split_row(line)
        if len(cells) < 4 or cells[0].lower() == "name":
            continue
        target = cells[1]
        rows.append({"name": cells[0],
                     "transport": "http" if target.startswith("http") else "stdio",
                     "target": _redact(target),
                     "status": "enabled" if "enabled" in cells[-1] and "not" not in cells[-1]
                               else "disabled",
                     "tools": [] if cells[2].strip() in ("all", "") else
                              [t.strip() for t in cells[2].split(",")],
                     "enabled": "enabled" in cells[-1] and "not" not in cells[-1]})
    return rows


def _mcp_test(ctx, name: str) -> Dict[str, Any]:
    code, out = _run(ctx, "mcp", "test", name, timeout=90)
    ok = "✓ Connected" in out or "Connected (" in out
    m = re.search(r"Connected \(([0-9.]+)\s*ms\)", out)
    ms = int(float(m.group(1))) if m else 0
    tools: List[str] = []
    for line in out.splitlines():
        m2 = re.match(r"^ {2,}([A-Za-z0-9_.:-]{2,})\s{2,}\S", line)
        if m2 and m2.group(1) not in ("Transport", "Auth", "Tools"):
            tools.append(m2.group(1))
    detail = ""
    if not ok:
        detail = _redact(" ".join(out.split())[:300])
    return {"ok": bool(ok), "ms": ms, "tools": tools, "detail": detail}


# --------------------------------------------------------------------------- plugins --
def _plugin_rows(ctx, refresh: bool = False) -> List[Dict[str, Any]]:
    if refresh:
        with _CACHE_LOCK:
            _CACHE.pop("plugins", None)

    def build() -> List[Dict[str, Any]]:
        code, out = _run(ctx, "plugins", "list", "--json", timeout=60)
        start = out.find("[")
        if start < 0:
            return []
        try:
            data = json.loads(out[start:])
        except ValueError:
            return []
        rows = []
        for row in data:
            if not isinstance(row, dict) or not row.get("name"):
                continue
            rows.append({"name": str(row["name"]),
                         "version": str(row.get("version") or ""),
                         "enabled": str(row.get("status") or "") == "enabled",
                         "capabilities": [str(c) for c in (row.get("capabilities") or [])
                                          if isinstance(row.get("capabilities"), list)],
                         "status": str(row.get("status") or ""),
                         "source": str(row.get("source") or ""),
                         "description": _redact(str(row.get("description") or ""))[:300]})
        return rows
    return _cached("plugins", 20.0, build)


# ----------------------------------------------------------------------------- tools --
def _tool_rows(ctx) -> List[Dict[str, Any]]:
    rows: List[Dict[str, Any]] = []
    code, out = _run(ctx, "tools", "list", timeout=60)
    kind, toolset = "agent", "builtin"
    for line in out.splitlines():
        stripped = line.strip()
        if stripped.endswith(":") and not stripped.startswith(("✓", "✗")):
            low = stripped.lower()
            kind = "mcp" if "mcp" in low else "agent"
            toolset = "mcp" if "mcp" in low else ("plugin" if "plugin" in low else "builtin")
            continue
        m = re.match(r"^([✓✗])\s+(enabled|disabled)\s+(\S+)\s*(.*)$", stripped)
        if not m:
            continue
        label = re.sub(r"^[^\w]+", "", m.group(4)).strip() or m.group(3)
        rows.append({"id": m.group(3), "label": label[:80], "toolset": toolset,
                     "kind": kind, "enabled": m.group(2) == "enabled"})
    try:  # the deterministic device lane is a tool surface too, just not an LLM one
        import jarvis_fast as fast  # noqa: PLC0415
        intents = getattr(fast, "INTENTS", None) or {}
        labelled = {}
        for chip in getattr(fast, "CHIPS", None) or []:
            if isinstance(chip, dict) and chip.get("intent"):
                labelled[chip["intent"]] = chip.get("label") or chip["intent"]
        for name in (intents.keys() if isinstance(intents, dict) else intents):
            rows.append({"id": "fast.%s" % name,
                         "label": str(labelled.get(name, name))[:80],
                         "toolset": "fast", "kind": "fast", "enabled": True})
    except Exception:  # noqa: BLE001  (jarvis_fast is optional and never fatal)
        pass
    return rows


# ------------------------------------------------------------------------------ cron --
def _cron_path(ctx) -> str:
    return os.path.join(ctx["HERMES"], "cron", "jobs.json")


def _persona_sidecar(ctx) -> Dict[str, str]:
    path = os.path.join(ctx["JARVIS_DIR"], "cron_personas.json")
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
        return {str(k): str(v) for k, v in data.items()} if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_persona_sidecar(ctx, job_id: str, persona: str) -> None:
    data = _persona_sidecar(ctx)
    if persona:
        data[job_id] = persona
    else:
        data.pop(job_id, None)
    path = os.path.join(ctx["JARVIS_DIR"], "cron_personas.json")
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump(data, fh, ensure_ascii=False)
        os.replace(tmp, path)
    except OSError as exc:
        ctx["notify"]("cron: persona sidecar failed: %s" % exc)


def _cron_rows(ctx) -> List[Dict[str, Any]]:
    personas = _persona_sidecar(ctx)
    jobs: List[Dict[str, Any]] = []
    try:
        with open(_cron_path(ctx), encoding="utf-8") as fh:
            raw = json.load(fh)
        if isinstance(raw, dict):
            jobs = [j for j in (raw.get("jobs") or []) if isinstance(j, dict)]
        elif isinstance(raw, list):
            jobs = [j for j in raw if isinstance(j, dict)]
    except (OSError, ValueError):
        jobs = []
        code, out = _run(ctx, "cron", "list", "--all", timeout=45)
        cur: Optional[Dict[str, Any]] = None
        for line in out.splitlines():
            m = re.match(r"^\s+([0-9a-zA-Z]{4,})\s+\[(\w+)\]", line)
            if m:
                cur = {"id": m.group(1), "state": m.group(2)}
                jobs.append(cur)
                continue
            m = re.match(r"^\s+(Name|Schedule|Next run|Deliver):\s+(.+)$", line)
            if m and cur is not None:
                cur[m.group(1).lower().replace(" ", "_")] = m.group(2).strip()
    rows = []
    for job in jobs:
        sched = job.get("schedule")
        if isinstance(sched, dict):
            sched = sched.get("display") or sched.get("expr") or ""
        rows.append({"id": str(job.get("id") or ""),
                     "name": _redact(str(job.get("name") or job.get("id") or ""))[:120],
                     "schedule": str(sched or ""),
                     "prompt": _redact(str(job.get("prompt") or ""))[:2000],
                     "persona": personas.get(str(job.get("id") or ""), ""),
                     "enabled": bool(job.get("enabled", job.get("state") == "active")),
                     "next_run": str(job.get("next_run_at") or job.get("next_run") or ""),
                     "last_run": str(job.get("last_run_at") or job.get("last_run") or ""),
                     "last_status": str(job.get("last_status") or ""),
                     "next_run_ts": _iso_to_epoch(job.get("next_run_at")),
                     "last_run_ts": _iso_to_epoch(job.get("last_run_at")),
                     "deliver": str(job.get("deliver") or "")})
    return rows


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901
    from fastapi import Request
    from fastapi.responses import JSONResponse

    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001
            return {}
        return data if isinstance(data, dict) else {}

    # ---- skills ----------------------------------------------------------------
    @app.get("/skills")
    def skills(q: str = "", scope: str = "", refresh: int = 0):
        try:
            rows = _skills_rows(ctx, refresh=bool(refresh))
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)
        needle = (q or "").strip().lower()
        if needle:
            rows = [r for r in rows
                    if needle in r["id"].lower() or needle in r["description"].lower()]
        if scope and scope != "all":
            rows = [r for r in rows if r["scope"] == scope]
        return rows

    @app.get("/skills/feed")
    def skills_feed(limit: int = 50):
        try:
            return _skills_feed(ctx, max(1, min(int(limit or 50), 200)))
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/skills/{sid}/toggle")
    async def skills_toggle(sid: str, req: Request):
        if not ID_RE.match(sid):
            return err("bad_id", sid)
        data = await body(req)
        if "enabled" not in data:
            return err("bad_body", "enabled is required")
        want = bool(data.get("enabled"))
        known = {r["id"] for r in _skill_files(ctx)}
        if sid not in known:
            return err("not_found", sid, 404)
        disabled = set(_disabled_skills(ctx))
        if want:
            disabled.discard(sid)
        else:
            disabled.add(sid)
        if disabled:
            code, out = _run(ctx, "config", "set", "skills.disabled",
                             json.dumps(sorted(disabled)), timeout=45)
        else:
            code, out = _run(ctx, "config", "unset", "skills.disabled", timeout=45)
        if code != 0:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        now_disabled = set(_disabled_skills(ctx))
        with _CACHE_LOCK:
            _CACHE.pop("skills_table", None)
        ok = (sid in now_disabled) != want
        ctx["notify"]("tools: skill %s -> %s" % (sid, "on" if want else "off"))
        return {"ok": ok, "enabled": sid not in now_disabled, "disabled": len(now_disabled)}

    # ---- curator ---------------------------------------------------------------
    @app.get("/curator")
    def curator():
        try:
            return _curator_rows(ctx)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/curator/run")
    def curator_run():
        if _CURATOR_RUN["busy"]:
            return {"ok": True, "started": False, "detail": "already running"}
        _CURATOR_RUN["busy"] = True

        def work() -> None:
            try:
                code, out = _run(ctx, "curator", "run", timeout=900)
                ctx["notify"]("curator: run finished rc=%s %s"
                              % (code, _redact(" ".join(out.split()))[:180]))
                with _CACHE_LOCK:
                    _CACHE.pop("usage", None)
                    _CACHE.pop("journey", None)
            finally:
                _CURATOR_RUN["busy"] = False

        threading.Thread(target=work, name="curator-run", daemon=True).start()
        ctx["notify"]("curator: run started by console")
        return {"ok": True, "started": True}

    # ---- memory ----------------------------------------------------------------
    @app.get("/memory")
    def memory():
        try:
            return _memory_rows(ctx)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    # ---- MCP -------------------------------------------------------------------
    @app.get("/mcp")
    def mcp_list():
        try:
            return _mcp_rows(ctx)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/mcp")
    async def mcp_add(req: Request):
        data = await body(req)
        name = str(data.get("name") or "").strip()
        url = str(data.get("url") or "").strip()
        command = str(data.get("command") or "").strip()
        args = data.get("args") or []
        env_in = data.get("env") or {}
        if not NAME_RE.match(name):
            return err("bad_name", "name must match [a-z0-9][a-z0-9_-]{0,39}")
        if bool(url) == bool(command):
            return err("bad_body", "exactly one of url / command is required")
        if not isinstance(args, list) or any(not isinstance(a, str) for a in args):
            return err("bad_args", "args must be a list of strings")
        if not isinstance(env_in, dict):
            return err("bad_env", "env must be an object")
        cfg: Dict[str, Any] = {}
        if url:
            if not url.startswith(("http://", "https://")):
                return err("bad_url", "url must be http(s)")
            if env_in:
                return err("bad_env", "env is only supported for stdio servers")
            cfg["url"] = url
        else:
            cfg["command"] = command
            if args:
                cfg["args"] = args
            if env_in:
                cfg["env"] = {str(k): str(v) for k, v in env_in.items()}
        # `hermes mcp add` is discovery-first and asks questions on stdin, so it is not
        # usable from a daemon; `config set` writes the same mcp_servers.<name> entry.
        code, out = _run(ctx, "config", "set", "mcp_servers." + name,
                         json.dumps(cfg, ensure_ascii=False), timeout=45)
        if code != 0:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        servers = _load_yaml(ctx).get("mcp_servers") or {}
        if name not in servers:
            return err("write_failed", "entry did not land in config.yaml", 500)
        ctx["notify"]("tools: mcp server added: %s" % name)
        return {"ok": True, "name": name}

    @app.delete("/mcp/{name}")
    def mcp_remove(name: str):
        if not NAME_RE.match(name):
            return err("bad_name", name)
        code, out = _run(ctx, "mcp", "remove", name, timeout=60)
        text = " ".join(out.split())
        if "Removed" in text:
            ctx["notify"]("tools: mcp server removed: %s" % name)
            return {"ok": True}
        if "not found" in text.lower() or "no such" in text.lower() or "unknown" in text.lower():
            return err("not_found", name, 404)
        return err("cli_failed", _redact(text)[:300])

    @app.post("/mcp/{name}/test")
    def mcp_test(name: str):
        if not NAME_RE.match(name):
            return err("bad_name", name)
        try:
            res = _mcp_test(ctx, name)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)
        if not res["ok"] and not res["detail"]:
            res["detail"] = "no connection"
        return res

    # ---- plugins ---------------------------------------------------------------
    @app.get("/plugins")
    def plugins(refresh: int = 0):
        try:
            return _plugin_rows(ctx, refresh=bool(refresh))
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/plugins/{name}/toggle")
    async def plugins_toggle(name: str, req: Request):
        if not ID_RE.match(name):
            return err("bad_name", name)
        data = await body(req)
        if "enabled" not in data:
            return err("bad_body", "enabled is required")
        want = bool(data.get("enabled"))
        args = ["plugins", "enable", name] if want else ["plugins", "disable", name]
        if want:
            args.append("--no-allow-tool-override")
        code, out = _run(ctx, *args, timeout=90)
        if code != 0:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        rows = {r["name"]: r for r in _plugin_rows(ctx, refresh=True)}
        row = rows.get(name)
        ctx["notify"]("tools: plugin %s -> %s" % (name, "on" if want else "off"))
        return {"ok": bool(row and row["enabled"] == want) or row is None,
                "enabled": bool(row["enabled"]) if row else want}

    # ---- tools -----------------------------------------------------------------
    @app.get("/tools")
    def tools():
        try:
            return _tool_rows(ctx)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    # ---- cron ------------------------------------------------------------------
    @app.get("/cron")
    def cron_list():
        try:
            return _cron_rows(ctx)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/cron")
    async def cron_create(req: Request):
        data = await body(req)
        name = str(data.get("name") or "").strip()
        schedule = str(data.get("schedule") or "").strip()
        prompt = str(data.get("prompt") or "").strip()
        persona = str(data.get("persona") or "").strip()
        if not schedule:
            return err("bad_schedule", "schedule is required")
        if not prompt:
            return err("bad_prompt", "prompt is required")
        if persona:
            try:
                import jarvisd_personas as P  # noqa: PLC0415
                if P.persona_prompt(persona, ctx) is None:
                    return err("bad_persona", persona)
            except Exception:  # noqa: BLE001  (persona module optional at runtime)
                pass
        args = ["cron", "create", schedule, prompt, "--deliver", "local"]
        if name:
            args += ["--name", name[:80]]
        code, out = _run(ctx, *args, timeout=90)
        m = re.search(r"Created job:\s*([0-9a-zA-Z]+)", out)
        if code != 0 or not m:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        job_id = m.group(1)
        if persona:
            _save_persona_sidecar(ctx, job_id, persona)
        ctx["notify"]("tools: cron %s created (%s)" % (job_id, schedule))
        return {"ok": True, "id": job_id}

    @app.post("/cron/{job_id}/run")
    def cron_run(job_id: str):
        if not ID_RE.match(job_id):
            return err("bad_id", job_id)
        code, out = _run(ctx, "cron", "run", job_id, timeout=60)
        if code != 0:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        ctx["notify"]("tools: cron %s queued" % job_id)
        return {"ok": True}

    @app.post("/cron/{job_id}/pause")
    async def cron_pause(job_id: str, req: Request):
        if not ID_RE.match(job_id):
            return err("bad_id", job_id)
        data = await body(req)
        paused = bool(data.get("paused", True))
        code, out = _run(ctx, "cron", "resume" if not paused else "pause", job_id, timeout=60)
        if code != 0:
            return err("cli_failed", _redact(" ".join(out.split()))[:300])
        ctx["notify"]("tools: cron %s %s" % (job_id, "paused" if paused else "resumed"))
        return {"ok": True, "paused": paused}

    @app.delete("/cron/{job_id}")
    def cron_remove(job_id: str):
        if not ID_RE.match(job_id):
            return err("bad_id", job_id)
        code, out = _run(ctx, "cron", "remove", job_id, timeout=60)
        text = " ".join(out.split())
        if "Removed" in text:
            _save_persona_sidecar(ctx, job_id, "")
            ctx["notify"]("tools: cron %s removed" % job_id)
            return {"ok": True}
        if "not found" in text.lower() or "no job" in text.lower():
            return err("not_found", job_id, 404)
        return err("cli_failed", _redact(text)[:300])
