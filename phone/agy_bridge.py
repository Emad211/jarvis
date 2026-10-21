#!/data/data/com.termux/files/usr/bin/python3
"""OpenAI-compatible bridge in front of the Antigravity CLI (`agy`).

    GET  /health                liveness + whether agy answers
    GET  /v1/models             model ids (from `agy models`, cached)
    POST /v1/chat/completions   text + image parts, optional SSE

Images are written to ~/.hermes/vision/ and referenced by path in the prompt, because
`agy -p` only takes text (it reads the file thanks to --dangerously-skip-permissions).
`agy` is single-session, so calls are serialised with a lock. It answers all at once,
so "streaming" replays the finished reply as SSE chunks (Hermes always asks for SSE).
"""
from __future__ import annotations

import base64
import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("AGY_BRIDGE_PORT", "8790"))
TOKEN = os.environ.get("ANTIGRAVITY_BRIDGE_KEY", "antigravity-local")
AGY = os.environ.get("AGY_CLI_PATH") or shutil.which("agy") or os.path.expanduser("~/.local/bin/agy")
IMG_DIR = os.path.expanduser("~/.hermes/vision")
EFFORTS = ("xhigh", "max", "high", "medium", "low")
IMAGE_EXT = {"png", "jpeg", "jpg", "webp", "gif"}
FALLBACK_MODELS = [
    "gemini-3.8-flash-high", "gemini-3.8-flash-medium", "gemini-3.8-flash-low",
    "gemini-3.1-pro-high", "gemini-3.1-pro-low",
    "claude-sonnet-4-6", "claude-opus-4-6-thinking", "gpt-oss-120b-medium",
]

_LOCK = threading.Lock()
_MODELS: dict = {"at": 0.0, "ids": []}


def list_models() -> list[str]:
    if time.time() - _MODELS["at"] < 600 and _MODELS["ids"]:
        return _MODELS["ids"]
    ids = []
    try:
        out = subprocess.run([AGY, "models"], capture_output=True, text=True, timeout=20).stdout
        ids = [ln.split()[0] for ln in out.splitlines()
               if ln.strip() and re.match(r"^[a-z0-9][\w.\-]+$", ln.split()[0])]
    except Exception:  # noqa: BLE001
        pass
    _MODELS.update(at=time.time(), ids=ids or FALLBACK_MODELS)
    return _MODELS["ids"]


def split_effort(model: str) -> tuple[str, str | None]:
    for eff in EFFORTS:
        if model.endswith("-" + eff):
            return model[: -len(eff) - 1], eff
    return model, None


def save_image(b64: str, media_type: str) -> str:
    ext = media_type.split("/")[-1].lower()
    path = os.path.join(IMG_DIR, f"bridge_{int(time.time() * 1000)}_{uuid.uuid4().hex[:4]}."
                        f"{ext if ext in IMAGE_EXT else 'png'}")
    try:
        with open(path, "wb") as fh:
            fh.write(base64.b64decode(b64))
        return f"[Image saved at: {path}]"
    except Exception as exc:  # noqa: BLE001
        return f"[image decode error: {exc}]"


def part_text(part: dict) -> str:
    kind = part.get("type")
    if kind == "text":
        return part.get("text", "")
    if kind == "image_url":  # OpenAI: {"image_url": {"url": "data:image/png;base64,..."}}
        url = part.get("image_url")
        url = url.get("url", "") if isinstance(url, dict) else str(url or "")
        if url.startswith("data:image"):
            header, _, data = url.partition(",")
            return save_image(data, header[5:].split(";")[0])
        return f"[Image URL: {url}]" if url else ""
    if kind == "image":  # Anthropic-style: {"source": {"type": "base64", ...}}
        src = part.get("source") or {}
        if src.get("type") == "base64":
            return save_image(src.get("data", ""), src.get("media_type", "image/png"))
    return ""


def build_prompt(messages: list) -> str:
    prefix = {"system": "[System Instructions]\n", "user": "User: ", "assistant": "Assistant: ",
              "tool": "Tool result: "}
    parts = []
    for m in messages:
        content = m.get("content") or ""
        if isinstance(content, list):
            content = " ".join(filter(None, (part_text(p) for p in content if isinstance(p, dict))))
        parts.append(prefix.get(m.get("role", "user"), "User: ") + str(content))
    return "\n\n".join(parts)


def run_agy(model: str, prompt: str) -> tuple[str, dict]:
    name, effort = split_effort(model)
    cmd = [AGY, "-p", prompt, "--model", name, "--output-format", "json",
           "--dangerously-skip-permissions"] + (["--effort", effort] if effort else [])
    with _LOCK:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if res.returncode != 0:
        raise RuntimeError((res.stderr or res.stdout or f"agy exit {res.returncode}").strip()[:500])
    data = json.loads(res.stdout)
    if data.get("status") != "SUCCESS":
        raise RuntimeError(data.get("error") or "agy failed")
    return data.get("response", "").strip(), data.get("usage") or {}


class Handler(BaseHTTPRequestHandler):
    def _json(self, status: int, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self._raw(status, "application/json", body)

    def _raw(self, status: int, ctype: str, body: bytes) -> None:
        try:
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _authorized(self) -> bool:
        auth = self.headers.get("Authorization", "")
        return not TOKEN or auth in ("Bearer " + TOKEN, TOKEN)

    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0].rstrip("/")
        if path == "/health":
            return self._json(200, {"ok": True, "agy": os.path.exists(AGY), "port": PORT})
        if path in ("/v1/models", "/models"):
            return self._json(200, {"object": "list", "data": [
                {"id": m, "object": "model", "owned_by": "antigravity"} for m in list_models()]})
        return self._json(404, {"error": "not found"})

    def do_POST(self):  # noqa: N802
        if self.path.split("?")[0].rstrip("/") not in ("/v1/chat/completions", "/chat/completions"):
            return self._json(404, {"error": "not found"})
        if not self._authorized():
            return self._json(401, {"error": {"message": "bad bridge key"}})
        try:
            body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
            model = body.get("model") or "gemini-3.8-flash-high"
            text, usage = run_agy(model, build_prompt(body.get("messages") or []))
        except Exception as exc:  # noqa: BLE001
            return self._json(502, {"error": {"message": str(exc)}})

        cid, now = f"chatcmpl-{uuid.uuid4().hex[:12]}", int(time.time())
        p_tok, c_tok = usage.get("input_tokens", 0), usage.get("output_tokens", 0)
        if not body.get("stream"):
            return self._json(200, {
                "id": cid, "object": "chat.completion", "created": now, "model": model,
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": text}}],
                "usage": {"prompt_tokens": p_tok, "completion_tokens": c_tok,
                          "total_tokens": p_tok + c_tok}})

        def chunk(delta: str, finish=None) -> str:
            return "data: " + json.dumps({
                "id": cid, "object": "chat.completion.chunk", "created": now, "model": model,
                "choices": [{"index": 0, "delta": {"content": delta}, "finish_reason": finish}]},
                ensure_ascii=False) + "\n\n"

        events = [chunk(p) for p in re.split(r"(\s+)", text) if p]
        events += [chunk("", "stop"), "data: [DONE]\n\n"]
        self._raw(200, "text/event-stream", "".join(events).encode("utf-8"))

    def log_message(self, fmt, *args):
        return


def main():
    os.makedirs(IMG_DIR, exist_ok=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.daemon_threads = True
    print(f"agy bridge on http://127.0.0.1:{PORT}  (agy={AGY})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
