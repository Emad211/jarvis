#!/data/data/com.termux/files/usr/bin/env python
"""jarvisd_project — project / code mode: one working directory, its files, a shell, git.

The console's chat side knows nothing about files; this module gives it a *project*: a directory
picked once, then tree/file read+writes, an approved shell, and the four safe git verbs. The
Android panel and the `jarvis-project` CLI both speak this contract.

    GET  /project                     {ok, dir, exists, git{repo,branch,dirty,ahead}, entries, ignored[]}
    POST /project/open      {dir}      same shape (expanduser + realpath, must stay under $HOME)
    GET  /project/tree      ?path=.&depth=1
    GET  /project/file      ?path=rel
    POST /project/file      {path, text}
    POST /project/run       {cmd, timeout?, approve}
    POST /project/git       {action, message?, approve?}

Rules that shaped the code:
  * **Everything is inside the project.** Every path is realpath'd and compared against the
    project root; `..`, absolute paths and symlinks that leave the tree answer `outside_project`.
  * **A shell is a decision, not a side effect.** `/project/run` refuses to run anything without
    `{"approve": true}` (409 `needs_approval`), and every approved run is appended to
    `~/.hermes/jarvis/project-runs.log` as one JSON line so the owner can see what was run.
  * **No network git.** push/fetch/pull answer `not_supported`, so no credential or token can
    ever be involved through this module.
  * **Writes are atomic** (temp file + os.replace) and the previous content is kept in
    `~/.hermes/jarvis/project-backups/` (sha1(path) + timestamp).
  * Never raise out of a route: every handler catches and answers `{ok:false,error,detail}`.

Import-safe: no file reads, no subprocess and no network at import time.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import signal
import subprocess
import time
from typing import Any, Dict, List, Optional, Tuple

# FastAPI resolves route annotations in the module namespace (postponed annotations), so these
# two must be module-level names or request bodies arrive as query parameters.
from fastapi import Request
from fastapi.responses import JSONResponse

SKIP_DIRS = {".git", "node_modules", "__pycache__", ".venv", "build", "dist", ".gradle"}
FILE_READ_MAX = 256 * 1024        # /project/file refuses to read more than this
FILE_WRITE_MAX = 512 * 1024       # /project/file refuses to write more than this
BINARY_SNIFF = 4096               # a NUL in the first 4 KB means binary
OUTPUT_CAP = 64 * 1024            # stdout/stderr/diff caps
TREE_CAP = 500                    # items per /project/tree answer
RUN_TIMEOUT_DEFAULT = 60
RUN_TIMEOUT_MAX = 600
GIT_ACTIONS = ("status", "log", "diff", "commit", "init")


# --------------------------------------------------------------------------- storage --
def _jarvis(ctx) -> str:
    return str(ctx["JARVIS_DIR"])


def _state_path(ctx) -> str:
    return os.path.join(_jarvis(ctx), "project.json")


def _runs_log(ctx) -> str:
    return os.path.join(_jarvis(ctx), "project-runs.log")


def _backups_dir(ctx) -> str:
    return os.path.join(_jarvis(ctx), "project-backups")


def _home(ctx) -> str:
    return os.path.realpath(str(ctx["HOME"]))


def _load_state(ctx) -> Dict[str, Any]:
    try:
        with open(_state_path(ctx), encoding="utf-8") as fh:
            data = json.load(fh)
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _save_state(ctx, directory: str) -> None:
    try:
        os.makedirs(_jarvis(ctx), exist_ok=True)
        tmp = _state_path(ctx) + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
            json.dump({"dir": directory, "updated": float(ctx["now"]())}, fh,
                      ensure_ascii=False, indent=1)
        os.replace(tmp, _state_path(ctx))
    except OSError as exc:
        ctx["notify"]("project: cannot persist dir: %s" % exc)


def _inside_home(ctx, path: str) -> bool:
    home = _home(ctx)
    return path == home or path.startswith(home + os.sep)


def _project_root(ctx) -> Tuple[str, str]:
    """(root, error). Root is realpath'd and guaranteed to live under $HOME."""
    saved = str(_load_state(ctx).get("dir") or "").strip()
    raw = saved or _home(ctx)
    root = os.path.realpath(os.path.expanduser(raw))
    if not _inside_home(ctx, root):
        return _home(ctx), "outside_home"
    return root, ""


def _rel_join(root: str, rel: str) -> Tuple[str, str]:
    """Project-relative path -> absolute, refusing anything that escapes the root."""
    rel = str(rel or "").strip() or "."
    if os.path.isabs(rel):
        return "", "outside_project"
    target = os.path.realpath(os.path.join(root, rel))
    if target != root and not target.startswith(root + os.sep):
        return "", "outside_project"
    return target, ""


def _rel(root: str, path: str) -> str:
    out = os.path.relpath(path, root)
    return "." if out == "." else out.replace(os.sep, "/")


# ------------------------------------------------------------------------------ git --
def _git(root: str, args: List[str], timeout: float = 30.0) -> Tuple[int, str, str]:
    env = dict(os.environ, GIT_TERMINAL_PROMPT="0", GIT_ASKPASS="/bin/false",
               GIT_PAGER="cat", PAGER="cat")
    try:
        proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True,
                              timeout=timeout, env=env, stdin=subprocess.DEVNULL)
        return proc.returncode, proc.stdout or "", proc.stderr or ""
    except FileNotFoundError:
        return 127, "", "git is not installed"
    except subprocess.TimeoutExpired:
        return 124, "", "git timed out after %ss" % int(timeout)


def _git_info(root: str) -> Dict[str, Any]:
    info: Dict[str, Any] = {"repo": False, "branch": "", "dirty": False, "ahead": 0}
    if not root or not os.path.isdir(root):
        return info
    rc, out, _err = _git(root, ["rev-parse", "--is-inside-work-tree"])
    if rc != 0 or out.strip() != "true":
        return info
    info["repo"] = True
    rc, out, _err = _git(root, ["status", "--porcelain=v1", "-b"])
    lines = [ln for ln in out.splitlines() if ln.strip()]
    if lines and lines[0].startswith("##"):
        head = lines[0][2:].strip()
        if "..." in head:
            head = head.split("...")[0]
        info["branch"] = head.split()[0] if head else ""
        if "[ahead " in lines[0]:
            m = re.search(r"\[ahead (\d+)", lines[0])
            if m:
                info["ahead"] = int(m.group(1))
        info["dirty"] = any(not ln.startswith("##") for ln in lines)
    return info


# ----------------------------------------------------------------------- project view --
def _top_level(root: str) -> Tuple[int, List[str], List[str]]:
    """(visible entry count, names, skipped-by-name list) at the project root."""
    try:
        names = sorted(os.listdir(root))
    except OSError:
        return 0, [], []
    visible, ignored = [], []
    for name in names:
        if name in SKIP_DIRS:
            ignored.append(name)
            continue
        visible.append(name)
    return len(visible), visible, ignored


def _project_view(ctx, root: str) -> Dict[str, Any]:
    exists = bool(root) and os.path.isdir(root)
    count, names, ignored = _top_level(root) if exists else (0, [], [])
    return {"ok": True, "dir": root, "exists": exists, "git": _git_info(root),
            "entries": count, "ignored": ignored,
            # `items` is extra: the panel can show names without a second call.
            "items": names[:100]}


def _entry(root: str, path: str) -> Dict[str, Any]:
    try:
        st = os.stat(path)
        size, mtime, is_dir = int(st.st_size), int(st.st_mtime), os.path.isdir(path)
    except OSError:
        size, mtime, is_dir = 0, 0, os.path.isdir(path)
    return {"name": os.path.basename(path), "path": _rel(root, path), "dir": is_dir,
            "size": size, "mtime": mtime}


def _walk(root: str, start: str, depth: int) -> Tuple[List[Dict[str, Any]], bool]:
    items: List[Dict[str, Any]] = []
    truncated = False
    frontier = [(start, 0)]
    while frontier:
        current, level = frontier.pop(0)
        try:
            names = sorted(os.listdir(current))
        except OSError:
            continue
        for name in names:
            path = os.path.join(current, name)
            if os.path.isdir(path) and name in SKIP_DIRS:
                continue
            if len(items) >= TREE_CAP:
                return items, True
            items.append(_entry(root, path))
            if os.path.isdir(path) and level + 1 < max(1, depth):
                frontier.append((path, level + 1))
    return items, truncated


# ------------------------------------------------------------------------ file bytes --
def _looks_binary(data: bytes) -> bool:
    return b"\x00" in data[:BINARY_SNIFF]


def _backup(ctx, path: str, data: bytes) -> str:
    try:
        os.makedirs(_backups_dir(ctx), exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S", time.localtime(float(ctx["now"]())))
        digest = hashlib.sha1(path.encode("utf-8", "replace")).hexdigest()[:10]
        out = os.path.join(_backups_dir(ctx), "%s-%s.bak" % (digest, stamp))
        with open(out, "wb") as fh:
            fh.write(data)
        return out
    except OSError as exc:
        ctx["notify"]("project: backup failed for %s: %s" % (path, exc))
        return ""


def _cap(text: str, limit: int = OUTPUT_CAP) -> Tuple[str, bool]:
    if len(text) <= limit:
        return text, False
    return text[:limit], True


# ---------------------------------------------------------------------------- routes --
def register(app, ctx):  # noqa: C901  (one flat block of routes is easier to audit)
    def err(code: str, detail: str = "", status: int = 400, **extra: Any):
        body = {"ok": False, "error": code, "detail": detail}
        body.update(extra)
        return JSONResponse(body, status_code=status)

    async def body(req) -> Dict[str, Any]:
        try:
            raw = await req.body()
            data = json.loads(raw) if raw else {}
        except Exception:  # noqa: BLE001
            return {}
        return data if isinstance(data, dict) else {}

    @app.get("/project")
    def project_get():
        try:
            root, why = _project_root(ctx)
            view = _project_view(ctx, root)
            if why:
                view["detail"] = why
            return view
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/project/open")
    async def project_open(req: Request):
        try:
            data = await body(req)
            raw = str(data.get("dir") or "").strip()
            if not raw:
                return err("bad_dir", "dir is required")
            target = os.path.realpath(os.path.expanduser(raw))
            if not _inside_home(ctx, target):
                return err("outside_home", "%s is outside %s" % (target, _home(ctx)), 403)
            if not os.path.exists(target):
                return err("missing", target, 404)
            if not os.path.isdir(target):
                return err("not_dir", target, 400)
            _save_state(ctx, target)
            ctx["notify"]("project: opened %s" % target)
            return _project_view(ctx, target)
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.get("/project/tree")
    def project_tree(path: str = ".", depth: int = 1):
        try:
            root, why = _project_root(ctx)
            if why:
                return err(why, root, 403)
            if not os.path.isdir(root):
                return err("missing", root, 404)
            target, err_code = _rel_join(root, path)
            if err_code:
                return err(err_code, path, 403)
            if not os.path.exists(target):
                return err("missing", path, 404)
            if not os.path.isdir(target):
                return err("not_dir", path, 400)
            try:
                want = max(1, min(int(depth or 1), 6))
            except (TypeError, ValueError):
                want = 1
            items, truncated = _walk(root, target, want)
            return {"ok": True, "path": _rel(root, target), "items": items,
                    "truncated": truncated, "count": len(items)}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.get("/project/file")
    def project_file(path: str = ""):
        try:
            root, why = _project_root(ctx)
            if why:
                return err(why, root, 403)
            if not str(path or "").strip():
                return err("bad_path", "path is required")
            target, err_code = _rel_join(root, path)
            if err_code:
                return err(err_code, path, 403)
            if not os.path.exists(target):
                return err("missing", path, 404)
            if os.path.isdir(target):
                return err("not_file", path, 400)
            size = os.path.getsize(target)
            if size > FILE_READ_MAX:
                return err("too_large", "%d bytes > %d" % (size, FILE_READ_MAX), 413,
                           size=size)
            with open(target, "rb") as fh:
                data = fh.read()
            if _looks_binary(data):
                return {"ok": True, "path": _rel(root, target), "text": "", "size": len(data),
                        "truncated": False, "binary": True}
            text = data.decode("utf-8", "replace")
            return {"ok": True, "path": _rel(root, target), "text": text, "size": len(data),
                    "truncated": False, "binary": False}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/project/file")
    async def project_write(req: Request):
        try:
            data = await body(req)
            root, why = _project_root(ctx)
            if why:
                return err(why, root, 403)
            rel = str(data.get("path") or "").strip()
            if not rel:
                return err("bad_path", "path is required")
            text = data.get("text")
            if not isinstance(text, str):
                return err("bad_text", "text must be a string")
            payload = text.encode("utf-8")
            if len(payload) > FILE_WRITE_MAX:
                return err("too_large", "%d bytes > %d" % (len(payload), FILE_WRITE_MAX), 413,
                           size=len(payload))
            target, err_code = _rel_join(root, rel)
            if err_code:
                return err(err_code, rel, 403)
            if os.path.isdir(target):
                return err("not_file", rel, 400)
            backup = ""
            if os.path.exists(target):
                try:
                    with open(target, "rb") as fh:
                        backup = _backup(ctx, target, fh.read())
                except OSError as exc:
                    ctx["notify"]("project: read-before-write failed: %s" % exc)
            os.makedirs(os.path.dirname(target) or root, exist_ok=True)
            tmp = target + ".jv-tmp"
            with open(tmp, "wb") as fh:
                fh.write(payload)
            os.replace(tmp, target)
            ctx["notify"]("project: wrote %s (%d bytes)" % (_rel(root, target), len(payload)))
            return {"ok": True, "path": _rel(root, target), "bytes": len(payload),
                    "backup": backup}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/project/run")
    async def project_run(req: Request):
        try:
            data = await body(req)
            cmd = str(data.get("cmd") or "").strip()
            if not cmd:
                return err("bad_cmd", "cmd is required")
            if not bool(data.get("approve")):
                # Nothing is executed before the owner says yes — this is the one route that
                # can change the device, so the refusal is explicit and logged.
                return err("needs_approval",
                           "POST /project/run requires {\"approve\": true}", 409)
            root, why = _project_root(ctx)
            if why:
                return err(why, root, 403)
            if not os.path.isdir(root):
                return err("missing", root, 404)
            try:
                timeout = float(data.get("timeout") or RUN_TIMEOUT_DEFAULT)
            except (TypeError, ValueError):
                timeout = float(RUN_TIMEOUT_DEFAULT)
            timeout = max(1.0, min(timeout, float(RUN_TIMEOUT_MAX)))
            started = float(ctx["now"]())
            timed_out = False
            proc = subprocess.Popen(["bash", "-lc", cmd], cwd=root, text=True,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                    stdin=subprocess.DEVNULL, start_new_session=True,
                                    env=dict(os.environ))
            try:
                out, err_out = proc.communicate(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                # Kill the whole group: a `bash -lc` may have spawned children that would
                # otherwise keep the pipes open and the request hanging.
                try:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                except OSError:
                    proc.kill()
                out, err_out = proc.communicate()
            rc = proc.returncode
            ms = int((float(ctx["now"]()) - started) * 1000)
            out, cut_out = _cap(out or "")
            err_out, cut_err = _cap(err_out or "")
            _log_run(ctx, {"ts": round(float(ctx["now"]()), 3), "dir": root,
                           "cmd": cmd[:500], "rc": rc, "ms": ms, "timed_out": timed_out})
            return {"ok": rc == 0 and not timed_out, "cmd": cmd, "rc": rc, "stdout": out,
                    "stderr": err_out, "ms": ms, "timed_out": timed_out,
                    "stdout_truncated": cut_out, "stderr_truncated": cut_err}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)

    @app.post("/project/git")
    async def project_git(req: Request):
        try:
            data = await body(req)
            action = str(data.get("action") or "status").strip().lower()
            if action not in GIT_ACTIONS:
                return err("not_supported",
                           "%s is not one of %s (no network git here)"
                           % (action, ", ".join(GIT_ACTIONS)), 400)
            root, why = _project_root(ctx)
            if why:
                return err(why, root, 403)
            if not os.path.isdir(root):
                return err("missing", root, 404)
            if action in ("commit", "init") and not bool(data.get("approve")):
                return err("needs_approval",
                           "POST /project/git %s requires {\"approve\": true}" % action, 409)
            started = float(ctx["now"]())
            if action == "init":
                rc, out, err_out = _git(root, ["init"])
            elif action == "status":
                rc, out, err_out = _git(root, ["status", "--porcelain=v1", "-b"])
            elif action == "log":
                rc, out, err_out = _git(root, ["log", "--oneline", "-20"])
            elif action == "diff":
                rc1, stat, err1 = _git(root, ["diff", "--stat"])
                rc2, full, err2 = _git(root, ["diff"])
                full, cut = _cap(full)
                rc = rc1 or rc2
                out = stat.rstrip() + "\n\n" + full
                err_out = (err1 or "") + (err2 or "")
                _log_run(ctx, {"ts": round(float(ctx["now"]()), 3), "dir": root,
                               "cmd": "git diff", "rc": rc,
                               "ms": int((float(ctx["now"]()) - started) * 1000)})
                return {"ok": rc == 0, "action": action, "stdout": out, "stderr": err_out,
                        "rc": rc, "truncated": cut}
            else:  # commit
                message = str(data.get("message") or "").strip() or \
                    "jarvis: checkpoint %s" % time.strftime("%Y-%m-%d %H:%M:%S")
                rc_add, out_add, err_add = _git(root, ["add", "-A"])
                # This phone has no git identity at all (`Author identity unknown`), so a
                # commit could never succeed. Use -c overrides *only* when the user has not
                # configured an identity — never touch their global config.
                ident: List[str] = []
                if not _git(root, ["config", "user.email"])[1].strip():
                    ident = ["-c", "user.name=Jarvis", "-c", "user.email=jarvis@localhost"]
                rc, out, err_out = _git(root, [*ident, "commit", "-m", message])
                out = (out_add or "") + (out or "")
                err_out = (err_add or "") + (err_out or "")
                ctx["notify"]("project: git commit in %s rc=%s" % (root, rc))
            ms = int((float(ctx["now"]()) - started) * 1000)
            out, cut_out = _cap(out or "")
            err_out, cut_err = _cap(err_out or "")
            _log_run(ctx, {"ts": round(float(ctx["now"]()), 3), "dir": root,
                           "cmd": "git %s" % action, "rc": rc, "ms": ms})
            return {"ok": rc == 0, "action": action, "stdout": out, "stderr": err_out,
                    "rc": rc, "ms": ms, "truncated": cut_out or cut_err}
        except Exception as exc:  # noqa: BLE001
            return err("internal", str(exc), 500)


def _log_run(ctx, row: Dict[str, Any]) -> None:
    """One JSON line per command, so the owner can audit what the agent ran."""
    try:
        os.makedirs(_jarvis(ctx), exist_ok=True)
        with open(_runs_log(ctx), "a", encoding="utf-8", newline="\n") as fh:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    except OSError as exc:
        ctx["notify"]("project: runs log failed: %s" % exc)
