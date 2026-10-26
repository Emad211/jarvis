# CLAUDE.md — Hermes-on-Android

One repo for both halves of the system. Background history: `../hermes-handoff/HANDOFF.md`
(written 2026-10-21; parts of its "known issues" are fixed here — trust this file and the code first).

## Layout
- `java/`, `res/`, `assets/`, `AndroidManifest.xml`, `build.sh` — **Hermes Control** app
  (`ir.emad.hermesctl`), a pure supervisor/health client of gwctl. No Gradle; do not add it.
- `jarvis/` — **Jarvis** app (`ir.emad.jarvis`), the chat console: streaming answers, live
  tool cards, approvals, mic/TTS. Talks only to `jarvisd` on `127.0.0.1:8780`.
  Build with `pwsh -File jarvis/build.ps1` (add `-Install` to push it to the phone).
- `phone/` — mirror of the phone's Termux `$HOME` (scripts, gwctl, bridge, SOUL, skills).
  `phone/.hermes/config.yaml` is a **reference snapshot only**: it is live state on the phone,
  edited via `hermes config set` / gwctl, and `pc/deploy-phone.sh` never pushes it.
- `pc/ph.sh` — run a command on the phone over SSH (finds the phone's drifting DHCP IP itself).
- `pc/deploy-phone.sh [--all]` — push `phone/` to the phone and restart services.
- `docs/` — `jarvis-architecture.md`, `google-features.md`, `hermes-embedding-api.md`
  (measured facts about Hermes' internals; read before touching the agent runtime).

## Phone runtime
- `hermes-svc` supervises everything in tmux + restart loops: `gwctl` (:8777), `jarvis`
  (`jarvisd`, the console's agent on :8780), `agy` (:8790), `router` (OmniRoute :20128, if
  installed). The Hermes **messaging gateway is gone**: the Telegram bot was deleted on
  2026-10-26 together with its token, its `platforms.telegram` / `telegram:` config blocks, the
  `tgwatch` + `telegram-heal` restart machinery and the per-profile `gw-<profile>` gateways.
  The console is the only interface; boot runs `hermes-svc boot`.
- `jarvisd` keeps a **warm in-process `AIAgent`** per session. Measured on this phone
  (2026-10-26): warm turn ≈ 1.8–4 s to first token, `hermes -z` ≈ 13–120 s, cold
  `AIAgent()` ≈ 24 s. Never route chat through `/agent/test` or a fresh `hermes -z`.
- Device actions do **not** go through the model: `jarvis_fast.py` runs 16 intents over
  loopback adb (~92 ms/call vs `rish`'s 3 s) and `jarvis_fast.route()` maps plain Persian
  commands onto them ("تنظیمات رو باز کن" → 613 ms end to end). The agent gets the same hands
  through the `jarvis-fast` CLI + `~/.hermes/skills/mobile/jarvis-fast/SKILL.md`.
- Hermes also ships an OpenAI-compatible API server (`gateway/platforms/api_server.py`,
  `platforms.api_server.enabled`, key `API_SERVER_KEY`, port 8642). `platforms.api_server` is
  still enabled in config, but **nothing serves :8642**: it only ever ran inside the gateway,
  which is no longer started. `docs/hermes-embedding-api.md` has the measured numbers and the
  cold-start caveat if it is ever brought back with `hermes gateway`.
- gwctl v3 API is documented in its docstring; most endpoints take `?profile=`.
- Device control goes through Shizuku `rish` (uid 2000, not root). The `phone-*` helpers source
  `phone-env`, which sets `RISH_APPLICATION_ID=com.termux`.
- Models: primary `google_ai_studio/gemini-3.5-flash`, then `fallback_providers` (Gemini 3.8 /
  3.5-lite, free OpenRouter models, AgentRouter `gpt-6.1-sol`, Antigravity). Providers without a
  key are skipped. The Google key is on the **free API tier** (Pro models return 429 until billing
  is linked in AI Studio). AgentRouter dropped `deepseek-v4-flash`; it now serves only
  `gpt-6.1-sol` / `gpt-6-astra` (`/v1/messages` and `/v1/responses`, not chat/completions).
  Google's OpenAI-compatible base is `.../v1beta/openai`. OpenRouter/Gemini/Antigravity need the
  phone's VPN on; AgentRouter does not (it needs the `User-Agent: claude-cli/…` header).

## Build / install the app
```bash
./build.sh                       # aapt2 -> javac --release 8 -> d8 -> zipalign -> apksigner
adb -s <phone-ip>:5555 install -r dist/hermes-control.apk
```
aapt2/d8/apksigner/zipalign are native Windows binaries: MSYS does not translate their path
arguments, so `build.sh` passes `C:/…` paths (`cygpath -m`). With adb from git-bash, set
`MSYS_NO_PATHCONV=1` for device paths like `/sdcard/...`.

## Rules
- Reply to the user in Persian, short and direct; act instead of instructing; verify before claiming.
- Never bind gwctl beyond 127.0.0.1. Watchdog stays a JobScheduler job (no foreground service).
- Never launch a supervised service with bare `nohup`; use `hermes-svc`.
- Keep `allow-external-apps=true` in `~/.termux/termux.properties` (the app's wake button needs it).
- TTS: the console uses Gemini TTS (`tts.provider: gemini`) because the ElevenLabs key
  answers 403 from this phone (`eleven_v4_turbo` stays the model if ElevenLabs is ever used).
- Phone scripts must keep LF line endings (`.gitattributes` enforces it).
- `jarvisd` and gwctl are loopback-only, no auth by design (same trust model). Do not add a
  second listener on a non-loopback address without a token.

## Accepting a change to the console
```powershell
pwsh -File jarvis\build.ps1 -Install
# then on the phone: send a message that needs a tool, confirm the tool card + answer
# appear in the same turn, and that `/status` stays "connected".
```
