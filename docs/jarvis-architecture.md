# Jarvis — native console for Hermes (architecture)

Status: **M1 working on the phone** (2026-10-26). Everything below is measured or verified
on the device unless it says UNTESTED.

## M1 as built

| Part | Where | Verified |
|---|---|---|
| App | `jarvis/` → `ir.emad.jarvis`, built by `jarvis/build.ps1` | installed, launches, renders |
| Backend | `phone/.local/bin/jarvisd.py`, service `jarvis` in `hermes-svc`, `127.0.0.1:8780` | `/health` warm, SSE, tool events |
| Chat | streaming answer + live tool cards inside one bubble | tool turn: `terminal` card 3.9 s + answer in same turn |
| Approvals | `ask` event → dialog (یک‌بار / همیشه / نه) | wiring verified via `probe_api.py --approve`; UI dialog untested with a real gated command |
| Mic | `AudioRecord` 16 kHz → WAV → `/voice/in` → Gemini STT → sends the turn | silent-room run reached STT and reported "متنی تشخیص داده نشد" as designed |
| TTS | `/voice/out` → Gemini TTS → `MediaPlayer` from `http://127.0.0.1:8780/audio/…` | new `jv_*.mp3` produced for a real reply while the app was open |
| Sessions | JSON per session in `~/.hermes/jarvis/sessions/`, restored on start | restart survived |
| Markdown | bold / inline code / fenced blocks rendered in bubbles | visible in screenshots |

Measured latency (free tier, quota available):

| Path | First token |
|---|---|
| `jarvisd` warm agent, simple turn | **1.8 s** |
| `jarvisd`, real Persian turn through the app backend | **3.7–4.0 s** |
| `jarvisd`, turn with a `terminal` tool call | 8.5 s to tool, 11.6 s to answer |
| Hermes `api_server` `/v1/runs` (warm) | 5.8–7.5 s |
| `hermes -z` (what gwctl used) | 13 s – 120 s |

Under a 429 storm (free tier exhausted by probing) any path degrades to tens of seconds;
that is quota, not code. Billing in AI Studio is the only fix, and only Emad can do it.

## Why a new app, not a Telegram bot

Telegram costs a network round-trip per message, cannot stream tool output, cannot show
approval buttons with context, and dies whenever the VPN drops. The phone is the agent
host; the interface should be a local client with direct access to the same process that
runs the tools. Hermes Control (`ir.emad.hermesctl`) stays as the **health/supervisor**
app; Jarvis (`ir.emad.jarvis`) is the **console**.

## Pieces

```
ir.emad.jarvis (Android, no Gradle)          jarvisd (Python, FastAPI+uvicorn)
  chat list, tool cards, approvals  ──SSE──►  127.0.0.1:8780
  mic (AudioRecord 16k)             ──POST─►  /voice/in  → Gemini STT
  speaker (MediaPlayer)             ◄─HTTP──  /audio/<id> ← Gemini TTS
  sessions, settings, quick actions
                                                  │ in-process
                                                  ▼
                                     run_agent.AIAgent (warm, one per persona)
                                                  │
                                     tools · MCP · plugins · cron · skills
```

Loopback only, exactly like gwctl: `127.0.0.1:8780`. Nothing is exposed to the LAN.
M1 has no auth token (same trust model as gwctl, which the app already relies on);
a token handshake is listed in the roadmap.

## Integration surface (verified in the installed source, 2026-10-26)

`run_agent.AIAgent` is the whole agent runtime and takes **callbacks**, so no process
spawning and no terminal scraping is needed:

| Callback | What we do with it |
|---|---|
| `stream_delta_callback(delta)` | `text.delta` SSE event — the live answer |
| `tool_start_callback(name, args)` | `tool.start` — a card appears before the tool runs |
| `tool_complete_callback(name, result)` | `tool.end` — the card gets its result/summary |
| `tool_progress_callback(...)` | `tool.progress` — long tools tick |
| `status_callback(kind, msg)` | `status` — compression, retries, lifecycle |
| `clarify_callback(question, choices) -> str` | `ask` + block until the app answers |
| `set_approval_callback` (`tools/terminal_tool.py`) | `ask` for dangerous shell commands |

Evidence: `run_agent.py:487-500` (kwargs), `:8972` `chat(message, stream_callback)`,
`:8492` `run_conversation(...)`, `agent/conversation_loop.py:1829,1906` (stream callback
plumbing), `tools/clarify_gateway.py:107,164` (ask/answer lifecycle),
`agent/tool_executor.py:131-165` (approval wait ceiling). The gateway itself drives the
agent exactly this way (`gateway/run.py:23074`).

Sessions persist in `~/.hermes/state.db` through `session_db`; the daemon passes its own
id so a session is stable across app restarts.

## Speed design (the part that matters)

1. **No process spawn per turn.** `hermes -z "…"` costs ~13 s before the model is even
   called (measured). A warm `AIAgent` skips interpreter start, imports, config parse and
   MCP handshake.
2. **Warm HTTP client.** The provider client is built once per agent and reused, so TLS
   setup is paid once.
3. **Two-tier model choice.** A tiny router model (`gemini-3.5-flash-lite`, reasoning none)
   handles "what does the user want" for short commands; the working model
   (`gemini-3.5-flash`) does the real turn. Configurable per session.
4. **Local fast path** (phase 2): a fixed set of device intents (open app, screenshot, tap,
   type, wifi toggle, volume, screenshot→OCR) executes through `rish`/`adb` **without any
   LLM call**, emitted as a normal tool event so the UI looks identical. Target: <300 ms.
5. **Streaming everywhere.** First token reaches the UI as soon as the provider produces
   it; tool cards appear at call time, not at turn end.
6. **Event replay.** SSE keeps the last 500 events per session with sequence numbers, so a
   reconnect (or an app restart) resumes mid-turn instead of losing the answer.

## Roadmap

- **M1 (now)** — chat with streaming, tool timeline, approve/deny for gated commands,
  mic→STT, TTS playback, session list, model + toolset pickers, latency badge.
- **M2** — fast path for device intents; personas (multiple SOUL profiles, each with its own
  prompt and model); quick-action tiles; notifications for finished background turns.
- **M3** — MCP servers and plugins: list, enable, install, show their tools; connectors.
- **M4** — cron jobs and kanban board in the app; scheduled runs report into the chat.
- **M5** — multi-user (accounts with separate sessions/limits on the same daemon), audit
  log, remote access over a private tunnel with a real token.

## Non-goals for M1

Telegram parity, group chat, file manager, code editor. Those come after the console is
solid; the daemon API is designed so they are additive.
