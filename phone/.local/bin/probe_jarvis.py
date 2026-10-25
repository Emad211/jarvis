#!/data/data/com.termux/files/usr/bin/env python
"""Reference client + latency probe for jarvisd (the console's backend).

    probe_jarvis.py "prompt" [--session=id] [--turns=1] [--model=...] [--effort=...]

Prints each SSE event with its arrival time in ms and a summary line:
time-to-first-token, tool calls, total. The same flow the Android app implements.
"""
import json
import os
import sys
import time

import httpx

BASE = os.environ.get("JARVIS_URL", "http://127.0.0.1:8780")


def main():
    prompt = sys.argv[1] if len(sys.argv) > 1 else "reply with exactly: READY"
    sid = None
    turns = 1
    model = None
    effort = None
    for a in sys.argv[2:]:
        if a.startswith("--session="):
            sid = a.split("=", 1)[1]
        elif a.startswith("--turns="):
            turns = int(a.split("=", 1)[1])
        elif a.startswith("--model="):
            model = a.split("=", 1)[1]
        elif a.startswith("--effort="):
            effort = a.split("=", 1)[1]

    with httpx.Client(timeout=300) as c:
        if not sid:
            sid = c.post(BASE + "/session/new", json={}).json()["session"]
        print("session", sid)
        if model:
            c.post(BASE + "/session/%s/model" % sid, json={"model": model})
        if effort:
            c.post(BASE + "/session/%s/reasoning" % sid, json={"effort": effort})
        for t in range(turns):
            text = prompt if t == 0 else prompt + " (دوباره)"
            t0 = time.time()
            r = c.post(BASE + "/session/%s/turn" % sid, json={"text": text})
            if r.status_code != 200:
                print("turn failed", r.status_code, r.text[:200])
                return 1
            first = None
            tools = 0
            reply = ""
            with c.stream("GET", BASE + "/session/%s/events" % sid,
                          params={"from": r.json()["seq"]}) as s:
                for line in s.iter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    ms = int((time.time() - t0) * 1000)
                    ev = json.loads(line[5:].strip())
                    kind = ev["kind"]
                    data = ev.get("data") or {}
                    if kind == "text.delta":
                        if first is None:
                            first = ms
                        reply += data.get("text", "")
                        continue
                    if kind == "tool.start":
                        tools += 1
                    if kind in ("ask",):
                        ans = {"approval": "deny", "clarify": ""}.get(data.get("kind"), "")
                        c.post(BASE + "/ask/" + data["id"], json={"value": ans})
                    if kind == "turn.end":
                        print("%6dms turn.end tools=%d" % (ms, tools))
                        break
                    print("%6dms %-16s %s" % (ms, kind, str(data)[:160]))
            print(json.dumps({"turn": t + 1, "ttft_ms": first,
                              "total_ms": int((time.time() - t0) * 1000),
                              "tools": tools, "reply": reply[:200]},
                             ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
