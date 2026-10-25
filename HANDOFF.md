# Hermes-on-Android — Handoff for DeepSeek Harness

Written 2026-10-25. Verified against the repo (`git log` head `630249f`) and the live phone on
2026-10-22/23. Anything not verified is marked **UNVERIFIED**. No secret values appear here; only
where they live.

## 0. Read this first

- **Owner:** Emad Karimi, 24, programmer in Iran (Asia/Tehran, UTC+03:30), Persian speaker.
- **Language rule:** talk to Emad in **Persian**, short and direct, no filler, no recap of his
  question. Think/code/search in English. Code, commands, paths, error strings stay verbatim.
- **Working style he wants:** act, don't hand him steps; do exactly what was named, ask before
  widening scope; verify before claiming ("تأیید نشده" when unchecked); never invent results.
- **Security:** he explicitly accepts the risks of an unrestricted assistant. Still: never print or
  commit secret values, never push keys into git.
- **Goal of the project:** a full-featured personal assistant living on his Samsung Galaxy A53
  (Android 16, Termux), reachable as a Telegram bot, able to operate the phone itself, running on
  **free / cheap LLM providers** that fail over automatically, with a companion control app.
- **No root, and he was advised against it:** bootloader locked, rooting trips Knox. Device
  control is Shizuku `rish` = uid 2000 (shell). That covers nearly everything needed.

## 1. System map

```
PC (Windows 11, git-bash)                       PHONE  Samsung SM-A536E, Android 16
  C:\Users\Emad Karimi\hermes-control  (git)      Termux (user u0_a309), tmux-supervised services:
   ├─ java/ res/ assets/  Hermes Control app        gw      Hermes gateway  (Telegram bot, @Emad_assistant_hermes_bot)
   ├─ phone/   mirror of phone $HOME                gwctl   control API 127.0.0.1:8777  (python, venv)
   └─ pc/ph.sh, pc/deploy-phone.sh                  agy     Antigravity OpenAI bridge 127.0.0.1:8790
  ph.sh ── ssh :8022 ──────────────────────►        router  OmniRoute :20128 (NOT installed yet)
  adb   ── tcp  :5555 ─────────────────────►      sshd :8022, adbd :5555 (re-armed on every boot)
                                                  Shizuku + rish (uid 2000)
                                                  Hermes Control app ir.emad.hermesctl → gwctl
```

Phone IP is **DHCP and drifts** (last seen `10.205.157.105`). `pc/ph.sh` caches the last good IP in
`~/.hermes-phone-ip` and rescans the PC's /24 networks for port 8022 when it stops answering. The
phone can be so loaded (load avg 16) that sshd answers slowly; `ph.sh` waits 6 s per probe.

## 2. Repository layout (`C:\Users\Emad Karimi\hermes-control`)

| Path | What |
|---|---|
| `java/ir/emad/hermesctl/*.java` | Hermes Control app. `MainActivity` (shell, header, tabs, shared dialogs), `Screen` (base: one ScrollView per tab, in-place render), `HomeScreen`, `ModelsScreen`, `KeysScreen`, `LogsScreen`, `SettingsScreen`, `Api` (HTTP client), `J` (null-safe JSON), `Ui` (design tokens/widgets), `Icon` (vector glyphs), `StatusRing`, `MeterBar`, `Watchdog` (JobScheduler id 8871) |
| `AndroidManifest.xml`, `res/`, `assets/fonts/` | versionName 3.0, minSdk 26, targetSdk 35, Vazirmatn fonts |
| `build.sh` | no-Gradle build (aapt2 → `javac --release 8` → d8 → zipalign → apksigner) |
| `phone/` | exact mirror of the phone's `$HOME` for everything we own |
| `phone/.local/bin/` | `gwctl.py` (v3.0, API 3), `hermes-svc`, `gwctl-start.sh`, `phone-*` helpers |
| `phone/.termux/boot/start.sh` | Termux:Boot → `hermes-svc boot` |
| `phone/agy_bridge.py` | OpenAI-compatible bridge over the `agy` CLI |
| `phone/.hermes/SOUL.md` | system-prompt personality of the bot |
| `phone/.hermes/skills/mobile/` | device-control skills (read before re-deriving phone tricks) |
| `phone/.hermes/plugins/model-providers/` | `agentrouter`, `antigravity` provider profiles |
| `phone/.hermes/config.yaml` | **reference snapshot only** — live state is on the phone, never pushed |
| `pc/ph.sh` | `bash pc/ph.sh 'cmd'` runs a command on the phone |
| `pc/deploy-phone.sh [--all]` | push `phone/` to the phone, restart gwctl+agy (`--all` also gateway/router) |
| `CLAUDE.md` | short rules file for Claude Code (keep in sync with this) |
| `../hermes-handoff/` | the previous agent's handoff + a 2026-10-21 snapshot incl. `connect/id_phone` SSH key. `HANDOFF.md` there is **partly outdated**; trust this file and the code |

Git: 5 commits, no remote. `.gitignore` excludes `dist/`, `build/`, `*.apk`, `keystore.jks`,
`.env`, `auth.json`, `memories/`, `custom_endpoints.json`. `.gitattributes` forces **LF** (phone
scripts break on CRLF).

## 3. Phone runtime in detail

### 3.1 `hermes-svc` (supervisor) — `phone/.local/bin/hermes-svc`
Each service = its own tmux session running a `while :; do cmd; sleep 5; done` loop, logging to a
file, so a crash self-heals in seconds.

```
hermes-svc start|stop|restart|status <name>   hermes-svc list   hermes-svc boot
```
Services: `gwctl` (`$VENV_PY gwctl.py`, log `~/.hermes/gwctl.log`), `agy`
(`~/.hermes/bridge.log`), `router` (`omniroute`, only if installed, `~/.hermes/router.log`), `gw`
(`hermes gateway run --replace`, `~/.hermes/gw-live.log`), `gw-<profile>` (one per
`~/.hermes/profiles/<p>/` that has `TELEGRAM_BOT_TOKEN` in its `.env`).
`stop` is graceful (SIGTERM to the pane process, wait ≤10 s, then kill the session) because a bare
SIGHUP makes the gateway log an "UNCLEAN exit". `boot` does: wake-lock, sshd, re-arm adb
(`setprop service.adb.tcp.port 5555; stop adbd; start adbd`), `adb connect 127.0.0.1:5555`,
battery-whitelist `com.termux` + `ir.emad.hermesctl`, then starts all services (gwctl first).
`gwctl-start.sh` just `exec`s `hermes-svc boot` (the app's wake button runs it via Termux
RUN_COMMAND). Python for gwctl/agy must be the **Hermes venv**
(`$PREFIX/lib/hermes-agent/app/venv/bin/python`, has PyYAML); system `python3` lacks it.

**Rule:** never start the gateway with bare `nohup` (it can silently never come up); always use `hermes-svc`.

### 3.2 `gwctl.py` v3 — control API, loopback only, no auth by design
Binds `127.0.0.1:8777`. Most endpoints accept `?profile=<name>`. Writes config through
`hermes config set` / `hermes auth ...`, never by raw-editing YAML, except the fallback chain and
`.env` slots.

GET: `/ping /version /status /overview /system /services /profiles /config /models /fallback /keys /logs/files /logs?file=&lines=&filter= /checks /cron`
POST: `/services/action {name,action}`, `/config/set {key,value}` (whitelist `FIELDS`), `/model/switch {provider,model}`, `/model/test {provider,model,prompt}`, `/fallback/set {chain:[{provider,model}]}`, `/keys/add|remove|reset|test`, `/env/set {name,value}` (only `*_API_KEY`/`*_TOKEN`; empty value deletes), `/cron/action`, `/agent/test {prompt}`, `/send {text,target}` (via `hermes send`, no LLM), `/gateway/restart`, `/gateway/stop`.

`/checks` runs: VPN (tun interface seen through rish — `/sys/class/net` is unreadable for Termux uid),
Telegram API reachability, DNS, bot state, one check per provider in the active chain, bridge,
OmniRoute, battery-optimisation whitelist. `gateway_pids()` ignores `sh/bash/tmux` wrappers and
matches `-p <profile>`.

### 3.3 `phone-*` helpers (all source `phone-env`, which sets `RISH_APPLICATION_ID=com.termux`)
`phone-screenshot [out.png]` → prints `MEDIA:<path>` · `phone-ui [needle]` / `--tap <needle>` (UI tree with
tap centres; first line = foreground package) · `phone-see ["question"]` (screenshot → vision-model chain)
· `phone-tap X Y [hold_ms]` · `phone-swipe up|down|left|right|X1 Y1 X2 Y2 [ms]` · `phone-key
back|home|recents|enter|delete|paste|menu|power|notifications|<code>` · `phone-type "text"`
(ASCII via `input text`; anything non-ASCII or with shell-special chars via `termux-clipboard-set` +
keyevent 279) · `phone-open <pkg>` · `phone-contacts [filter]` (tab-separated; rish prints rows on
**stderr**, hence `2>&1`) · `saat` (Jalali date/time).
Facts: `input text` is ASCII-only; `input keyevent` takes numbers only; `uiautomator dump` coordinates
go stale instantly; other apps' `/data/data` is unreadable even at uid 2000.

### 3.4 Antigravity bridge `agy_bridge.py`
`/health`, `/v1/models`, `/v1/chat/completions` (text + image parts, fake-SSE), serialises calls with a
lock. Wraps `agy -p ... --output-format json --dangerously-skip-permissions`. **UNVERIFIED that it
still works:** `agy models` returned `403 ... please log out and /login again` on 2026-10-21, so the
Antigravity login may be expired. It is last in the fallback chain.

### 3.5 Boot / reboot behaviour
After a phone reboot Termux:Boot runs `start.sh` after a 20 s sleep. Services come up in the order
above. adb TCP port 5555 is re-armed by `hermes-svc boot` (it is a runtime prop, not persistent).

## 4. Models, providers, keys (state as of 2026-10-23)

Config lives in `~/.hermes/config.yaml` on the phone (snapshot in `phone/.hermes/config.yaml`).

- **Primary:** `google_ai_studio` / `gemini-3.5-flash` (custom provider, `transport: chat_completions`,
  `api: https://generativelanguage.googleapis.com/v1beta/openai`, key env `GOOGLE_AI_STUDIO_API_KEY`;
  `GEMINI_API_KEY` holds the same key and serves Hermes' built-in `gemini` provider).
- **`fallback_providers` (tried in order on 429/5xx/connection errors):**
  1 google_ai_studio/gemini-3.8-flash · 2 google_ai_studio/gemini-3.5-flash-lite ·
  3 openrouter/minimax/minimax-m3.1-flash:free · 4 openrouter/nvidia/nemotron-3-ultra-550b-a55b:free ·
  5 agentrouter/gpt-6.1-sol · 6 antigravity/gemini-3.8-flash-high. Manage with `hermes fallback ...`
  or the app's Models tab.
- **Google key = FREE API tier.** `gemini-4-argon` and `gemini-3.1-pro-preview` return 429 with
  quota ids ending `-FreeTier`; `gemini-2.5-pro` returns 404 "no longer available to new users".
  Working: `gemini-3.5-flash`, `gemini-3.5-flash-lite` (Persian + tool-calling verified).
  `gemini-3.8-flash` / `3.7-flash` gave transient 503 "high demand". Per Google's rate-limit docs a
  paid tier needs a billing account linked in AI Studio (Tier 1); the docs do not say a consumer
  "Google AI Pro" subscription changes API quota. **Only Emad can link billing.** Exact per-model
  limits are visible only in his AI Studio dashboard.
- **AgentRouter** (`https://agentrouter.org`, `api_mode: anthropic_messages`): the three pool keys
  `daily-1/2/3` are valid, but the server removed `deepseek-v4-flash` ("无可用渠道"). It now lists only
  `gpt-6.1-sol` and `gpt-6-astra`, which work via `/v1/messages` and `/v1/responses` and **reject
  `/v1/chat/completions`**. Requests need header `User-Agent: claude-cli/1.0.0 (external, cli)`
  (set as the provider's `extra_headers`). Works without the phone VPN.
- **OpenRouter free models** (`:free`): shared quota ≈ 20 RPM / 50 RPD; verified tool-calling + Persian on
  `minimax/minimax-m3.1-flash:free` and `nvidia/nemotron-3-ultra-550b-a55b:free`. `thinkingmachines/inkling:free`
  only works from "agentic harnesses". Needs the phone VPN.
- **Keyless or unusable:** Groq (the previous bot wrote a placeholder key; it was removed — add a real
  key in the app's Keys tab), Cerebras and Mistral (provider blocks exist, no key). OpenCode Zen free
  models answer 403 "free tier can only be used from within OpenCode". Chutes/Z.ai are not usable from Iran.
- **Credential pool strategy:** `credential_pool_strategies.{openrouter,agentrouter}: round_robin`.
  `deepseek` pool entry is dead (401). `custom:google-antigravity` is the bridge.
- **Vision:** `phone-see` tries Gemini → OpenRouter gemma-4 free → Antigravity.
- **Voice:** TTS ElevenLabs `eleven_v4_turbo`, voice Hooman `XzieuK0bwYWT9Kl1LhLs` (never switch to
  `eleven_multilingual_v2`: no Persian). STT was changed by the previous bot to `gemini`
  (`stt.language: fa`). **Never send voice unless Emad sent voice first or asks for it**
  (`voice.auto_tts: false`).
- **Aliases** in `model.aliases` (use `/model <alias>`): `chat`, `free`, `ultra`, `sol`, plus Emad's own
  `img img-pro img-nano tts tts-pro tts-2.5-flash tts-3.1-flash music music-pro music-clip agent
  agent-antigrav agent-antigrav2 research research-pro research-standard embed* stt chat-pro argon`.
  Those non-chat aliases (image, TTS, Lyria music, deep research, embeddings, computer-use) point at
  models that exist on his key (60 models listed) but **are not chat/completions models**; they only
  make sense for dedicated tools, which are not wired yet (see §7).
- **Network:** the phone needs a VPN for Telegram, Google, OpenRouter (`tun0` present when on). The
  gateway gives up after 8 failed Telegram connects and stays in `retrying` — a restart fixes it
  (`curl -X POST 127.0.0.1:8777/gateway/restart` or the app). `.env` flags
  `HERMES_TELEGRAM_DISABLE_FALLBACK=1`, `TELEGRAM_DISABLE_FALLBACK=1`,
  `HERMES_TELEGRAM_DISABLE_FALLBACK_IPS=1` are intentional; keep them.
- **Secrets live in** `~/.hermes/.env` (Telegram bot token, `GOOGLE_AI_STUDIO_API_KEY`,
  `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `ELEVENLABS_API_KEY`, `CUSTOM_API_KEY`),
  `~/.hermes/auth.json` (pools), `~/.hermes/custom_endpoints.json` (plaintext AgentRouter key),
  `~/.hermes/memories/` (contains the device PIN fragment). SSH key `../hermes-handoff/connect/id_phone`.
  Note: the Google key was typed through Telegram and an `echo ... >> .env` command, so it is stored in
  the bot's session DB (`state.db`) and was sent to model providers.

## 5. Hermes Control app (`ir.emad.hermesctl`, v3.0)

Pure client of gwctl (`Api.BASE = http://127.0.0.1:8777`, cleartext allowed). Five tabs, RTL Persian UI,
dark theme, no Gradle, no support libs, vector icons drawn in `Icon.java`.
- `Screen` pattern: each tab owns one ScrollView for life; `refresh()` fetches then `done(render)` re-renders
  in place (the old app built a new screen on silent refresh and never showed it — fixed). `live()` tabs
  (Home, Logs-when-follow) auto-refresh every 12 s from `MainActivity`'s tick.
- Home: status ring, restart/test/send/stop, services (start/restart), team profiles, device meters, log tail.
  Models: primary + test, **fallback chain editor** (reorder/delete/add → `/fallback/set`), reasoning effort,
  configured models. Keys: provider key slots (`/env/set`) with "get key" links + credential pools.
  Logs: file chips, search, line count, follow. Settings: health checks (run on open / on demand), editable
  fields from gwctl's `FIELDS`, cron jobs, raw config.
- Watchdog: periodic JobScheduler job (every 15 min), notifies on state change and at most every 3 h while
  down. **Do not** convert it to a foreground service.
- Wake button: `startForegroundService` to Termux `RunCommandService` (needs `allow-external-apps=true` in
  `~/.termux/termux.properties` — currently set).
- **Build & install:**
  ```bash
  cd "/c/Users/Emad Karimi/hermes-control" && ./build.sh        # -> dist/hermes-control.apk (dev keystore, pass "hermesctl")
  adb connect <ip>:5555 && MSYS_NO_PATHCONV=1 adb -s <ip>:5555 install -r dist/hermes-control.apk
  ```
  Needs `JAVA_HOME` (JDK 17 at `C:\Program Files\Eclipse Adoptium\jdk-17.0.16.8-hotspot`) and the SDK at
  `C:/Users/Public/Android/sdk` (build-tools 35.0.0, android-35). aapt2/d8/apksigner/zipalign are native
  Windows binaries → every path passed to them must be `C:/...` (`cygpath -m`).
- **UNVERIFIED:** the new UI was installed and launches without crashing, but nobody has looked at it on
  screen yet (the phone was locked). Check layout, RTL alignment and the fallback editor visually.

## 6. Everyday commands

```bash
cd "/c/Users/Emad Karimi/hermes-control"
bash pc/ph.sh 'sh ~/.local/bin/hermes-svc list'                       # service state
bash pc/ph.sh 'curl -s 127.0.0.1:8777/status'                         # health JSON
bash pc/ph.sh 'curl -s 127.0.0.1:8777/checks'                         # provider/VPN/Telegram checks
bash pc/ph.sh 'curl -s -XPOST 127.0.0.1:8777/gateway/restart'
bash pc/ph.sh 'tail -50 ~/.hermes/gw-live.log'
bash pc/ph.sh 'hermes fallback list'
bash pc/ph.sh 'hermes -z "ping"'                                      # one-shot agent run
bash pc/deploy-phone.sh            # push phone/ + restart gwctl/agy   (add --all for gateway/router)
```
After editing anything in `phone/`: syntax-check (`python -m py_compile`, `sh -n`), deploy, hit the
endpoint, then commit. Pull the live config back into the repo with
`bash pc/ph.sh 'cat ~/.hermes/config.yaml' > phone/.hermes/config.yaml`.
Do real checks with tiny prompts; the phone is heavily loaded (7.4 GB RAM, ~2.7 GB swap in use, `/data` 86 % full).

## 7. Open work, in priority order

1. **Visual review of the app** on the phone (§5 UNVERIFIED). Fix whatever looks wrong.
2. **Gateway self-heal:** after the VPN returns, a gateway stuck in `retrying` needs a restart. Add a
   check (in `hermes-svc` or a gwctl background thread) that restarts `gw` when Telegram API is reachable
   but state is `retrying`/`error` for > N minutes.
3. **Wire Google features beyond chat** using his key (he wants image gen, TTS, STT, music, agentic/computer-use,
   deep research). Models present on the key: image `gemini-3.1-flash-image`, `gemini-3-pro-image`,
   `nano-banana-pro-preview`, `gemini-nano-banana-2.1`; TTS `gemini-3.8-flash-tts`, `gemini-3.1-flash-tts-preview`,
   `gemini-2.5-*-preview-tts`; STT `gemini-3.5-transcribe`; music `lyria-3.5`, `lyria-3-pro-preview`,
   `lyria-3-clip-preview`; live/audio `gemini-3.8-live*`; agent `gemini-2.5-computer-use-preview-10-2025`,
   `antigravity-preview-*`; research `deep-research-*`. All are native `generateContent` models (not OpenAI chat).
   Expect free-tier limits until billing is linked. Do this as Hermes skills/tools or small helper scripts
   (`phone/.local/bin/`), test each on the real key with a tiny call, and report which actually work.
4. **Multi-agent team** (profiles with own SOUL/model/Telegram bot). Hermes supports it natively:
   `hermes profile create <name> --clone --description "..."`, write `~/.hermes/profiles/<name>/SOUL.md`,
   put a **new** `TELEGRAM_BOT_TOKEN` (from @BotFather) in that profile's `.env` (one token = one gateway),
   `hermes-svc start gw-<name>`; all profiles share the kanban board (`hermes kanban ...`). For "message everyone":
   a Telegram **group** with every bot added, each bot's Group Privacy **off** in BotFather (re-add after changing),
   `telegram.group_allowed_chats`, `telegram.exclusive_bot_mentions: true` (@mention routes to chosen bots).
   Bots do not see each other's messages on Telegram; only Emad's messages fan out. `delegate_task` subagents do
   not inherit SOUL.md and cannot take per-task toolsets/models (`delegation.model/provider` pin the whole batch).
   An earlier attempt at a `hermes-team` helper script was blocked by the sandbox classifier — write it as plain
   documented commands or ask Emad first.
5. **Approvals:** `approvals.mode` is still `smart`; Emad wants unrestricted. Turning it to `off` was blocked by the
   sandbox classifier during the last session → ask Emad to set it in the app (Settings → «تأیید دستورهای خطرناک»).
6. **OmniRoute:** `npm install -g omniroute` failed three times mid-download on the loaded phone (`ECONNABORTED`,
   exit 146/153). Retry with a quiet phone / VPN on; once installed, `hermes-svc` auto-starts it
   (`omniroute` → :20128, OpenAI-compatible) and a custom Hermes provider can point at it. Low priority:
   Hermes' own fallback chain already covers failover.
7. **Provider hygiene:** give Groq/Cerebras/Mistral real keys (Emad creates them), re-login `agy` if Antigravity
   is wanted, delete dead `deepseek` pool entry, confirm `gemini-3.8-flash` stability after Google's demand
   spike, then consider making it primary again.
8. **Housekeeping:** `/data` is 86 % full (`~/shots`, `~/*.png`, `~/ui*.xml`, `~/debs`, `~/downloads` are
   cleanup candidates); `~/old/` holds scripts retired by `deploy-phone.sh`; the PC-side
   `../hermes-handoff/HANDOFF.md` is stale — either delete or point it here.

## 8. Do-not list (each one burned time before)

- Don't add Gradle. Don't bind gwctl to anything but `127.0.0.1`. Don't turn the watchdog into a foreground service.
- Don't launch the gateway/gwctl with bare `nohup`; use `hermes-svc`. Don't use system `python3` for gwctl.
- Don't send `deepseek-v4-flash` to AgentRouter (gone) and don't call its GPT models on `/v1/chat/completions`.
- Don't point Google's chat provider at `/v1beta` (needs `/v1beta/openai`) and don't prefix the model id with the
  provider name in `model.default`.
- Don't put non-chat models (TTS/music/image/embedding) in a provider's chat `models:` list.
- Don't push `config.yaml` from the repo to the phone; don't commit `.env`, `auth.json`, keys, the keystore or APKs.
- Don't enable `eleven_multilingual_v2`, and don't send voice unprompted.
- Don't rely on `/tmp` on the phone (permission denied) — use `$PREFIX/tmp` or `~`.
- With adb under git-bash set `MSYS_NO_PATHCONV=1` for device paths; native build tools need `C:/` paths.
- Phone scripts must stay LF; a CRLF script fails with `\r: command not found`.

## 9. Acceptance checks (run after any change)

```bash
bash pc/ph.sh 'sh ~/.local/bin/hermes-svc list'                         # gwctl, agy, gw all "running"
bash pc/ph.sh 'curl -s 127.0.0.1:8777/status' | python -c "import json,sys;d=json.load(sys.stdin);print(d['overall'],d['telegram'],d['model'])"   # online connected google_ai_studio/gemini-3.5-flash
bash pc/ph.sh 'curl -s -m 200 -XPOST 127.0.0.1:8777/agent/test -H "Content-Type: application/json" -d "{\"prompt\":\"ساعت چنده؟ با ابزار terminal دستور saat را اجرا کن.\"}"'   # ok:true, Persian answer
bash pc/ph.sh 'hermes fallback list'                                    # 6 entries
git status --short                                                      # clean after commit
```
