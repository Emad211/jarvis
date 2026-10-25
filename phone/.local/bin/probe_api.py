#!/data/data/com.termux/files/usr/bin/env python
"""Probe Hermes' /v1/runs contract with precise timings.

    probe_api.py "prompt" [--approve=deny|once|session|always] [--session=id] [--utf]

Prints every SSE event as:  <ms> <event> <payload…>
and a final summary line with time-to-first-token, tool count and total.
The bearer key is read from ~/.hermes/.env here and never printed.
"""
import json
import os
import sys
import time

import httpx

BASE = "http://127.0.0.1:8642"
HOME = os.path.expanduser("~")


def key():
    with open(os.path.join(HOME, ".hermes", ".env"), encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("API_SERVER_KEY="):
                return line.split("=", 1)[1].strip()
    return ""


def main():
    prompt = sys.argv[1] if len(sys.argv) > 1 else "reply with exactly: READY"
    approve = "deny"
    session = "probe-%d" % int(time.time())
    model = None
    provider = None
    effort = None
    for a in sys.argv[2:]:
        if a.startswith("--approve="):
            approve = a.split("=", 1)[1]
        elif a.startswith("--session="):
            session = a.split("=", 1)[1]
        elif a.startswith("--model="):
            model = a.split("=", 1)[1]
        elif a.startswith("--provider="):
            provider = a.split("=", 1)[1]
        elif a.startswith("--effort="):
            effort = a.split("=", 1)[1]

    h = {"Authorization": "Bearer " + key()}
    payload = {"input": prompt, "session_id": session}
    if model:
        payload["model"] = model
    if provider:
        payload["provider"] = provider
    if effort:
        payload["model_options"] = {"reasoning_effort": effort}
    t0 = time.time()
    with httpx.Client(timeout=300) as c:
        r = c.post(BASE + "/v1/runs", headers=h, json=payload)
        if r.status_code >= 300:
            print("start failed", r.status_code, r.text[:300])
            return 1
        rid = r.json()["run_id"]
        print("run_id", rid, "%.0fms" % ((time.time() - t0) * 1000))
        first = None
        tools = 0
        final = ""
        asks = 0
        usage = {}
        with c.stream("GET", BASE + "/v1/runs/%s/events" % rid, headers=h) as s:
            for line in s.iter_lines():
                if not line or not line.startswith("data:"):
                    continue
                ms = int((time.time() - t0) * 1000)
                try:
                    ev = json.loads(line[5:].strip())
                except Exception:
                    print(ms, "raw", line[:160])
                    continue
                kind = ev.get("event") or ev.get("type") or "?"
                if kind == "message.delta" and first is None:
                    first = ms
                if kind == "tool.started":
                    tools += 1
                if kind == "approval.request":
                    asks += 1
                    print(ms, kind, json.dumps(ev, ensure_ascii=False)[:300])
                    rr = c.post(BASE + "/v1/runs/%s/approval" % rid, headers=h,
                                json={"choice": approve})
                    print(ms, "approval->", approve, rr.status_code)
                    continue
                if kind == "run.completed":
                    final = ev.get("output") or ""
                    usage = ev.get("usage") or {}
                payload = ev.get("delta") or ev.get("tool") or ev.get("text") or ev.get("output") or ""
                print(ms, kind, str(payload)[:200].replace("\n", " "))
                if kind in ("run.completed", "run.failed", "run.cancelled"):
                    break
        print(json.dumps({"ttft_ms": first, "total_ms": int((time.time() - t0) * 1000),
                          "tools": tools, "approvals": asks, "usage": usage,
                          "reply": final[:200]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
