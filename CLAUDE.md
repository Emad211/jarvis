# CLAUDE.md — Hermes-on-Android

One repo for both halves of the system. Background history: `../hermes-handoff/HANDOFF.md`
(written 2026-10-21; parts of its "known issues" are fixed here — trust this file and the code first).

## Layout
- `java/`, `res/`, `assets/`, `AndroidManifest.xml`, `build.sh` — **Hermes Control** app
  (`ir.emad.hermesctl`), a pure client of gwctl. No Gradle; do not add it.
- `phone/` — mirror of the phone's Termux `$HOME` (scripts, gwctl, bridge, SOUL, skills).
  `phone/.hermes/config.yaml` is a **reference snapshot only**: it is live state on the phone,
  edited via `hermes config set` / gwctl, and `pc/deploy-phone.sh` never pushes it.
- `pc/ph.sh` — run a command on the phone over SSH (finds the phone's drifting DHCP IP itself).
- `pc/deploy-phone.sh [--all]` — push `phone/` to the phone and restart services.

## Phone runtime
- `hermes-svc` supervises everything in tmux + restart loops: `gwctl` (:8777), `agy`
  (Antigravity bridge :8790), `router` (OmniRoute :20128, if installed), `gw` (gateway),
  `gw-<profile>` (one per Hermes profile that has its own bot token). Boot runs `hermes-svc boot`.
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
- Never launch the gateway with bare `nohup`; use `hermes-svc`.
- Keep `allow-external-apps=true` in `~/.termux/termux.properties` (the app's wake button needs it).
- TTS stays `eleven_v4_turbo` (multilingual_v2 has no Persian).
- Phone scripts must keep LF line endings (`.gitattributes` enforces it).
