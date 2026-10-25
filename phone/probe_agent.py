"""Measure the in-process agent: time-to-first-token and total, no CLI spawn.

Run on the phone with the Hermes venv python:
  $PREFIX/lib/hermes-agent/app/venv/bin/python ~/probe_agent.py [model] [prompt]
Env knobs (all optional):
  PROBE_TOOLSETS="terminal,file"   limit the tool schemas sent to the model
  PROBE_REASONING=none|low|medium|high|xhigh
  PROBE_SKIP_CONTEXT=1             do not load SOUL.md / AGENTS.md
  PROBE_SKIP_MEMORY=1              do not load memories / background review
  PROBE_MINIMAL_PROMPT=1           replace the system prompt with one short line
Prints one JSON line. Never prints or logs a key.
"""
import json
import os
import sys
import time

APP = "/data/data/com.termux/files/usr/lib/hermes-agent/app"
sys.path.insert(0, APP)
os.chdir("/data/data/com.termux/files/home")

t0 = time.time()
from run_agent import AIAgent  # noqa: E402
t_import = time.time() - t0

model = sys.argv[1] if len(sys.argv) > 1 else "gemini-3.5-flash"
prompt = sys.argv[2] if len(sys.argv) > 2 else "reply with exactly: READY"

deltas = []
stamps = {}


def on_delta(d):
    if not deltas:
        stamps["first"] = time.time()
    deltas.append(d)


def on_tool_start(*a, **k):
    stamps.setdefault("tools", []).append(("start", str(a[0] if a else "")[:40]))


def on_tool_done(*a, **k):
    stamps.setdefault("tools", []).append(("end", str(a[0] if a else "")[:40]))


kw = dict(
    model=model,
    quiet_mode=True,
    verbose_logging=os.environ.get("PROBE_VERBOSE") == "1",
    stream_delta_callback=on_delta,
    tool_start_callback=on_tool_start,
    tool_complete_callback=on_tool_done,
)
T_START = [0.0]


def stamp(kind):
    def cb(*a, **k):
        print("[cb %6.2fs] %s %s" % (time.time() - T_START[0], kind,
                                     str(a)[:160] if a else str(k)[:160]), flush=True)
    return cb


if os.environ.get("PROBE_TRACE") == "1":
    kw["status_callback"] = stamp("status")
    kw["notice_callback"] = stamp("notice")
    kw["thinking_callback"] = stamp("thinking")
    kw["event_callback"] = lambda name, payload: stamp("event:" + str(name))(
        str(payload)[:160])
    kw["tool_progress_callback"] = stamp("progress")
toolsets = os.environ.get("PROBE_TOOLSETS", "").strip()
if toolsets:
    kw["enabled_toolsets"] = [t.strip() for t in toolsets.split(",") if t.strip()]
reasoning = os.environ.get("PROBE_REASONING", "").strip()
if reasoning:
    kw["reasoning_config"] = {"effort": reasoning}
if os.environ.get("PROBE_SKIP_CONTEXT") == "1":
    kw["skip_context_files"] = True
if os.environ.get("PROBE_SKIP_MEMORY") == "1":
    kw["skip_memory"] = True
    kw["skip_background_review"] = True
if os.environ.get("PROBE_MINIMAL_PROMPT") == "1":
    kw["ephemeral_system_prompt"] = "You are Jarvis, a concise assistant on a phone."

t1 = time.time()
agent = AIAgent(**kw)
t_init = time.time() - t1
sys_prompt_chars = 0
try:
    msgs = getattr(agent, "conversation_history", None) or []
    for m in msgs:
        if m.get("role") == "system":
            sys_prompt_chars += len(str(m.get("content") or ""))
    sys_prompt_chars = sys_prompt_chars or len(str(getattr(agent, "system_prompt", "") or ""))
except Exception:  # noqa: BLE001
    pass

t2 = time.time()
T_START[0] = t2
turns = int(os.environ.get("PROBE_TURNS", "1") or 1)
per_turn = []
result = {}
for i in range(turns):
    deltas.clear()
    stamps.pop("first", None)
    t2 = time.time()
    T_START[0] = t2
    result = agent.run_conversation(
        user_message=prompt if i == 0 else "and once more, " + prompt,
        task_id="probe-%d-%d" % (int(t2), i))
    per_turn.append({
        "ttft_s": round(stamps["first"] - t2, 2) if "first" in stamps else None,
        "turn_s": round(time.time() - t2, 2),
        "deltas": len(deltas),
    })
t_turn = sum(p["turn_s"] for p in per_turn)

out = {
    "model": model,
    "toolsets": toolsets or "all",
    "reasoning": reasoning or "config",
    "import_s": round(t_import, 2),
    "init_s": round(t_init, 2),
    "sys_prompt_chars": sys_prompt_chars,
    "ttft_s": per_turn[0]["ttft_s"] if per_turn else None,
    "turn_s": round(t_turn, 2),
    "turns": per_turn,
    "deltas": per_turn[0]["deltas"] if per_turn else 0,
    "reply": (result or {}).get("final_response", "")[:120],
    "tools": [p + ":" + n for p, n in stamps.get("tools", [])][:8],
    "error": (result or {}).get("error"),
}
print(json.dumps(out, ensure_ascii=False))
