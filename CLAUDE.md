# CLAUDE.md — Hermes-on-Android

One repo for both halves of the system. Background history: `../hermes-handoff/HANDOFF.md`
(written 2026-10-21; parts of its "known issues" are fixed here — trust this file and the code first).

## Layout
- `jarvis/` — **Jarvis** app (`ir.emad.jarvis`), the only app: chat console with streaming
  answers, live tool cards, approvals, mic/TTS, personas, skills/MCP, cron, services and voice
  settings. Talks only to `jarvisd` on `127.0.0.1:8780`.
  Build with `pwsh -File jarvis/build.ps1` (add `-Install` to push it to the phone).
  The old **Hermes Control** app (`ir.emad.hermesctl`, `java/` + `res/` + `build.sh`) was
  deleted on 2026-10-26: everything worth keeping lives in the panels above.
- `phone/` — mirror of the phone's Termux `$HOME` (scripts, gwctl, bridge, SOUL, skills).
  `phone/.hermes/config.yaml` is a **reference snapshot only**: it is live state on the phone,
  edited via `hermes config set` / gwctl, and `pc/deploy-phone.sh` never pushes it.
- `pc/ph.sh` — run a command on the phone over SSH (finds the phone's drifting DHCP IP itself).
- `pc/deploy-phone.sh [--all]` — push `phone/` to the phone and restart services.
- `docs/` — `jarvis-architecture.md`, `google-features.md`, `hermes-embedding-api.md`
  (measured facts about Hermes' internals; read before touching the agent runtime).

## Phone runtime
- `hermes-svc` supervises everything in tmux + restart loops: `gwctl` (:8777), `jarvis`
  (`jarvisd`, the console's agent on :8780), `agy` (:8790), `cron`, and `router` (OmniRoute
  :20128, if installed). The Hermes **messaging gateway is gone**: the Telegram bot was deleted
  on 2026-10-26 together with its token, its `platforms.telegram` / `telegram:` config blocks,
  the `tgwatch` + `telegram-heal` restart machinery and the per-profile `gw-<profile>` gateways.
  The console is the only interface; boot runs `hermes-svc boot`.
- `cron` exists because cron used to be ticked by the gateway: the unit runs
  `hermes cron tick` every 55 s. `hermes cron status` still claims "gateway is not running, cron
  jobs will NOT fire" — that is its own heuristic and it is now wrong; trust `hermes-svc list`
  plus `hermes cron runs` (a real job has fired with `last_status=ok`).
- `jarvisd` keeps a **warm in-process `AIAgent`** per session. Measured on this phone
  (2026-10-26): warm turn ≈ 1.8–4 s to first token, `hermes -z` ≈ 13–120 s, cold
  `AIAgent()` ≈ 24 s. Never route chat through `/agent/test` or a fresh `hermes -z`.
- Feature modules sit beside `jarvisd.py` and are loaded through a guarded `register(app, ctx)`
  loop — a broken module is logged and skipped, never fatal: `jarvisd_personas.py` (personas +
  per-session binding), `jarvisd_tools.py` (skills, curator, memory, MCP, plugins, cron),
  `jarvisd_ops.py` (services, restart whitelist, logs), `jarvisd_voice.py` (all of `/voice/*`).
  They must import `Request`/`JSONResponse` at **module** level: with `from __future__ import
  annotations`, a runtime-local import makes FastAPI treat request bodies as query params.
- Voice, measured 2026-10-26 over the phone's own network: STT `gemini-3.5-flash-lite` answers a
  3 s Persian clip in 1.19–1.79 s with CER 0 (`gemini-3.5-transcribe` is more accurate but hits
  the free-tier per-model daily 429), TTS takes 3.3–9.4 s depending on which model still has
  quota — hence the fallback chain plus last-good-model stickiness. ElevenLabs is blocked from
  this phone at the host level (403 before auth, on the Berlin exit), not a key problem.
- Device actions do **not** go through the model: `jarvis_fast.py` runs 16 intents over
  loopback adb (~92 ms/call vs `rish`'s 3 s) and `jarvis_fast.route()` maps plain Persian
  commands onto them ("تنظیمات رو باز کن" → 613 ms end to end). The agent gets the same hands
  through the `jarvis-fast` CLI + `~/.hermes/skills/mobile/jarvis-fast/SKILL.md`.
- Hermes also ships an OpenAI-compatible API server (`gateway/platforms/api_server.py`, key
  `API_SERVER_KEY`, port 8642). It is **unset and inert** here: it only ever ran inside the
  gateway, which no longer starts. `docs/hermes-embedding-api.md` has the measured numbers and
  the cold-start caveat if it is ever brought back with `hermes gateway`.
- gwctl v3 API is documented in its docstring; most endpoints take `?profile=`.
- Device control goes through Shizuku `rish` (uid 2000, not root). The `phone-*` helpers source
  `phone-env`, which sets `RISH_APPLICATION_ID=com.termux`.
- Models: primary **River AI** `deepseek-ai/DeepSeek-V4.1-Flash-1017` (measured 1.1 s for a tiny
  chat through a working VPN exit; it is also the model the builder of this project runs on).
  `POST /providers/autoselect` probes every provider with a real request and makes the first
  one that answers the default, so the console follows the network instead of a fixed choice.
  **AvalAI** (`api.avalai.ir`, `deepseek-v4.1-flash`) is the only provider measured to work
  from an Iranian mobile network with **no VPN** — keep it as the fallback for that case. Then
  Gemini and OpenRouter. Providers without a key are skipped, and the fallback chain drops any
  provider the last probe could not reach. AgentRouter dropped `deepseek-v4-flash`; it now
  serves only `gpt-6.1-sol` / `gpt-6-astra` (`/v1/messages`, needs a `claude-cli/…` User-Agent).

## Build / install the app
```powershell
pwsh -File jarvis\build.ps1 -Install      # aapt2 -> javac --release 8 -> d8 -> zipalign -> sign -> adb install
```
aapt2/d8/apksigner/zipalign are native Windows binaries and MSYS does not translate their path
arguments, so the script passes `C:/…` paths itself. Build from PowerShell, not git-bash.

## Rules
- Reply to the user in Persian, short and direct; act instead of instructing; verify before claiming.
- Never bind gwctl beyond 127.0.0.1. Watchdog stays a JobScheduler job (no foreground service).
- Never launch a supervised service with bare `nohup`; use `hermes-svc`.
- Keep `allow-external-apps=true` in `~/.termux/termux.properties` (the app's wake button needs it).
- TTS: **ElevenLabs** `eleven_v3` with a Persian voice (`ndcUYGFbbd96WXiZVVaQ` = Roya) is the
  default — measured 2.4 s for a sentence, and it is the only engine with real Persian voices
  (Hooman, Connery, Aran, Kaveh, Setareh, Niloofar). Gemini TTS stays as the quota-free
  fallback. **Both providers are unreachable from an Iranian network**: `api.elevenlabs.io`
  answers 403 from Google's frontend and `api.river.ai` (a Google Cloud IP in Finland) times
  out. They work through a VPN whose exit is not blocked — ExpressVPN did, a Berlin/Eweka exit
  did not.
- Phone scripts must keep LF line endings (`.gitattributes` enforces it).
- `jarvisd` and gwctl are loopback-only, no auth by design (same trust model). Do not add a
  second listener on a non-loopback address without a token.

## Accepting a change to the console
```powershell
pwsh -File jarvis\build.ps1 -Install
# then on the phone: send a message that needs a tool, confirm the tool card + answer
# appear in the same turn, and that `/status` stays "connected".
```
