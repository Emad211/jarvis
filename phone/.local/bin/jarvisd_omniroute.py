"""jarvisd_omniroute — the bundled free-model gateway, as a first-class provider.

OmniRoute is an OpenAI-compatible gateway that runs *on this phone* (npm `omniroute`,
127.0.0.1:20128) and routes to other providers' free tiers. Bundling it is what lets a stranger
install Jarvis and talk to a real model in the first five minutes without buying anything.

What this module does NOT do: invent free tokens. OmniRoute grants nothing itself — it aggregates
third-party free tiers, and most of those need *your* account. So the status it reports has four
separate truths (`installed`, `running`, `key_set`, `models_connected`) and the app shows them
one by one instead of a single green light that lies.

Measured 2026-10-27 on this handset: the npm package is ~1 GB unpacked, npm 11 needs
`--allow-scripts` or the binary never appears, and the gateway answers `/v1/models` only after it
has finished booting (a bare TCP check lied during that window).
"""

from __future__ import annotations

from typing import Any, Dict

from fastapi import Request
from fastapi.responses import JSONResponse

import json
import os
import subprocess
import time

CLI = os.path.expanduser("~/.local/bin/jarvis-omniroute")
PORT = 20128


def _run(args: list, timeout: float = 60.0) -> Dict[str, Any]:
    """Run the CLI and return its JSON, or an honest failure."""
    if not os.path.isfile(CLI):
        return {"ok": False, "error": "cli_missing", "path": CLI}
    try:
        p = subprocess.run([CLI] + args, capture_output=True, text=True, timeout=timeout,
                           env=dict(os.environ, HOME=os.path.expanduser("~")))
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout", "args": args}
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "error": type(exc).__name__, "detail": str(exc)}
    out = (p.stdout or "").strip().splitlines()
    for line in reversed(out):
        line = line.strip()
        if line.startswith("{"):
            try:
                data = json.loads(line)
                data.setdefault("ok", p.returncode == 0)
                return data
            except Exception:  # noqa: BLE001
                continue
    return {"ok": False, "error": "no_json", "rc": p.returncode,
            "detail": ((p.stderr or "") + (p.stdout or ""))[:300]}


def register(app, ctx) -> None:
    def err(code: str, detail: str = "", status: int = 400):
        return JSONResponse({"ok": False, "error": code, "detail": detail}, status_code=status)

    @app.get("/omniroute")
    def omniroute_status():
        """Four honest facts, not one light. The app renders each of them."""
        data = _run(["status"], timeout=40)
        data["provider_id"] = "omniroute"
        data["dashboard"] = "http://127.0.0.1:%d/dashboard" % PORT
        return data

    @app.post("/omniroute/ensure")
    async def omniroute_ensure(req: Request):
        """Install + start + key, then report. Long on purpose: it may download ~1 GB.

        Called from the app's first-run flow and from `jarvis-install`; idempotent, so a phone
        that already has it pays only the status call.
        """
        data = _run(["ensure"], timeout=2760)  # the CLI's own install budget, plus slack
        return data

    @app.post("/omniroute/test")
    async def omniroute_test(req: Request):
        """One real chat through the gateway with model `auto`.

        This is the only thing that proves the promise ("free models work right now"): a key can
        exist and every provider pool can still be empty.
        """
        try:
            body = await req.json()
        except Exception:  # noqa: BLE001
            body = {}
        text = str((body or {}).get("text") or "سلام")
        data = _run(["test", text], timeout=120)
        # Remember the verdict so the providers panel can show "last measured" instead of hope.
        try:
            path = os.path.join(ctx["JARVIS_DIR"], "omniroute_last_test.json")
            with open(path, "w", encoding="utf-8") as fh:
                json.dump(dict(data, at=time.time()), fh, ensure_ascii=False)
        except Exception:  # noqa: BLE001
            pass
        return data
