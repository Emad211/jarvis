#!/data/data/com.termux/files/usr/bin/python3
from __future__ import annotations

import json
import os
import shutil
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(os.environ.get("AGY_BRIDGE_PORT", "8790"))
TOKEN = os.environ.get("ANTIGRAVITY_BRIDGE_KEY", "antigravity-local")
AGY = os.environ.get("AGY_CLI_PATH") or shutil.which("agy") or "/data/data/com.termux/files/home/.local/bin/agy"
EFFORTS = ("xhigh", "max", "high", "medium", "low")

_LOCK = threading.Lock()

def list_models() -> list[str]:
    try:
        out = subprocess.run([AGY, "models"], capture_output=True, text=True, timeout=15)
        models = []
        for line in out.stdout.splitlines():
            parts = line.strip().split()
            if parts:
                models.append(parts[0])
        if models:
            return models
    except Exception:
        pass
    return [
        "gemini-3.8-flash-high", "gemini-3.8-flash-medium", "gemini-3.8-flash-low",
        "gemini-3.7-flash-high", "gemini-3.7-flash-medium", "gemini-3.7-flash-low",
        "gemini-3.6-flash-high", "gemini-3.6-flash-medium", "gemini-3.6-flash-low",
        "gemini-3.1-pro-high", "gemini-3.1-pro-low",
        "claude-sonnet-4-6", "claude-opus-4-6-thinking", "gpt-oss-120b-medium",
    ]

def parse_model_effort(raw_model: str) -> tuple[str, str | None]:
    model = raw_model.strip()
    for eff in EFFORTS:
        if model.endswith(f"-{eff}"):
            return model[:-len(eff)-1], eff
    return model, None

def run_agy(model: str, effort: str | None, prompt: str) -> tuple[str, int, int]:
    cmd = [AGY, "-p", prompt, "--model", model, "--output-format", "json", "--dangerously-skip-permissions"]
    if effort:
        cmd.extend(["--effort", effort])
    with _LOCK:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if res.returncode != 0:
        err = res.stderr or res.stdout or f"agy exit {res.returncode}"
        raise RuntimeError(err)
    data = json.loads(res.stdout)
    if data.get("status") != "SUCCESS":
        raise RuntimeError(data.get("error") or "agy failed")
    resp_text = data.get("response", "")
    usage = data.get("usage", {})
    p_tok = usage.get("input_tokens", 0)
    c_tok = usage.get("output_tokens", 0)
    return resp_text, p_tok, c_tok

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/v1/models", "/models"):
            mods = list_models()
            data = {
                "object": "list",
                "data": [{"id": m, "object": "model", "owned_by": "antigravity"} for m in mods],
            }
            self._send_json(200, data)
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path in ("/v1/chat/completions", "/chat/completions"):
            try:
                length = int(self.headers.get("content-length", 0))
                body = json.loads(self.rfile.read(length).decode("utf-8"))
                model = body.get("model", "gemini-3.8-flash-high")
                messages = body.get("messages", [])
                
                # Build conversation text (supports text + base64/image parts)
                prompt_parts = []
                img_dir = os.path.expanduser("~/.hermes/vision")
                try:
                    os.makedirs(img_dir, exist_ok=True)
                except Exception:
                    img_dir = "/tmp"
                import base64 as _b64
                for m in messages:
                    role = m.get("role", "user")
                    content = m.get("content", "")
                    if isinstance(content, list):
                        txts = []
                        for c in content:
                            if not isinstance(c, dict):
                                continue
                            ctype = c.get("type")
                            if ctype == "text":
                                txts.append(c.get("text", ""))
                            elif ctype == "image_url":
                                url = c.get("image_url", {})
                                u = url.get("url", "") if isinstance(url, dict) else (url if isinstance(url, str) else "")
                                if isinstance(u, str) and u.startswith("data:image"):
                                    hdr, _, b64data = u.partition(",")
                                    ext = "png"
                                    if ";base64" in u:
                                        mt = hdr.split(";")[0].split("/")[-1]
                                        ext = mt if mt in ("png", "jpeg", "jpg", "webp", "gif") else "png"
                                    ipath = os.path.join(img_dir, f"bridge_{int(time.time()*1000)}_{len(txts)}.{ext}")
                                    try:
                                        with open(ipath, "wb") as f:
                                            f.write(_b64.b64decode(b64data))
                                        txts.append(f"[Image saved at: {ipath}]")
                                    except Exception as e:
                                        txts.append(f"[image decode error: {e}]")
                            elif ctype == "image":
                                src = c.get("source", {})
                                if isinstance(src, dict) and src.get("type") == "base64":
                                    mt = src.get("media_type", "image/png")
                                    ext = "png"
                                    ext = (mt.split("/")[-1] if "/" in mt and mt.split("/")[-1] in ("png","jpeg","jpg","webp","gif") else "png")
                                    ipath = os.path.join(img_dir, f"bridge_{int(time.time()*1000)}_{len(txts)}.{ext}")
                                    try:
                                        with open(ipath, "wb") as f:
                                            f.write(_b64.b64decode(src.get("data", "")))
                                        txts.append(f"[Image saved at: {ipath}]")
                                    except Exception as e:
                                        txts.append(f"[image decode error: {e}]")
                        content = " ".join(txts)
                    if role == "system":
                        prompt_parts.append(f"[System Instructions]\n{content}\n")
                    elif role == "user":
                        prompt_parts.append(f"User: {content}")
                    elif role == "assistant":
                        prompt_parts.append(f"Assistant: {content}")
                prompt = "\n\n".join(prompt_parts)
                
                m_name, effort = parse_model_effort(model)
                resp_text, p_tok, c_tok = run_agy(m_name, effort, prompt)

                want_stream = bool(body.get("stream", False))
                if not want_stream:
                    out = {
                        "id": f"chatcmpl-{uuid.uuid4().hex[:12]}",
                        "object": "chat.completion",
                        "created": int(time.time()),
                        "model": model,
                        "choices": [{
                            "index": 0,
                            "message": {"role": "assistant", "content": resp_text.strip()},
                            "finish_reason": "stop",
                        }],
                        "usage": {
                            "prompt_tokens": p_tok,
                            "completion_tokens": c_tok,
                            "total_tokens": p_tok + c_tok,
                        },
                    }
                    self._send_json(200, out)
                else:
                    # Emit OpenAI-style SSE chunks: stream the whole answer as
                    # word-ish deltas then a final [DONE]. Deltas are large but
                    # valid; clients accumulate them into the full response.
                    import re as _re
                    content = resp_text.strip()
                    nid = f"chatcmpl-{uuid.uuid4().hex[:12]}"
                    gen = int(time.time())
                    def chunk(delta: str, finish: str | None):
                        c = {"id": nid, "object": "chat.completion.chunk",
                             "created": gen, "model": model,
                             "choices": [{"index": 0, "delta": {"content": delta},
                                          "finish_reason": finish}]}
                        return c
                    # split on whitespace so it streams; include a space after words
                    pieces = _re.split(r'(\s+)', content) or [""]
                    payload_chunks = []
                    for piece in pieces:
                        if piece == "":
                            continue
                        payload_chunks.append("data: " + json.dumps(chunk(piece, None)) + "\n\n")
                    payload_chunks.append("data: " + json.dumps(chunk("", "stop")) + "\n\n")
                    payload_chunks.append("data: [DONE]\n\n")
                    # send SSE with known full length (blocking, but keeps CL correct)
                    body_b = "".join(payload_chunks).encode("utf-8")
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.send_header("Content-Length", str(len(body_b)))
                    self.end_headers()
                    self.wfile.write(body_b)
            except Exception as e:
                self._send_json(500, {"error": {"message": str(e)}})
        else:
            self._send_json(404, {"error": "not found"})

    def _send_json(self, status: int, data: dict):
        body = json.dumps(data).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass

def main():
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Antigravity bridge listening on 127.0.0.1:{PORT}")
    server.serve_forever()

if __name__ == "__main__":
    main()
