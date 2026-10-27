# OmniRoute on the phone — what it is, what it is not

Measured on the handset 2026-10-27. This file exists so nobody re-runs the experiment.

## What it is
An open-source AI gateway, published on npm (`omniroute`), that speaks the OpenAI API on
`127.0.0.1:20128/v1` and fronts ~371 provider connections and 463 catalogue models with routing,
fallback and compression. The official install for Android is its own:
`pkg install nodejs && npx -y omniroute` — "runs on your phone, 24/7, no root".

## What it is NOT
**It grants no tokens.** Its own README: "Bring your own provider accounts. This is an estimated
aggregate across separately eligible third-party free tiers, **not a token grant from OmniRoute**."
It is a hub, not a supply.

Measured on a fresh install here, with a valid OmniRoute key and **zero** provider connections:

```
GET  /v1/models                 -> 200, 463 models
POST /v1/chat/completions auto  -> 502 in 6.7 s
     cfp/zai-org/glm-5.2: provider — Cloudflare Playground browser session failed (HTTP 502)
     unc/turboderp/Qwen3.8-27B-exl3: auth — the public beta is closed, needs an API key
```

So the keyless pools it tries first are either broken or closed. The first real answer requires
connecting **one** provider account. That is the honest state of "free models on install": there
is no such thing yet, from any gateway.

## The install on this phone (measured)
| step | result |
|---|---|
| `npm i -g omniroute` | **rc=0 with no binary** — npm 11 blocks install scripts by default |
| `npm i -g omniroute --allow-scripts=omniroute,@parcel/watcher,@swc/core,esbuild` | binary appears |
| disk | **2.0 GB** in `lib/node_modules/omniroute` (the 440 MB figure is the tarball only) |
| first boot | 20.6 s, then `Dashboard: http://localhost:20128` |
| `hermes-svc start router` | works — the unit already existed and waits for this binary |
| `/v1/models` without a key | 401 (so a key is real auth, not decorative) |

### Getting a key without a browser
`api_keys` starts empty and every `/api/*` route is 401. What works:

1. `omniroute setup --password <pw> --non-interactive` → "Admin password configured", login enabled.
2. `POST /api/auth/login {"password": <pw>}` → 200 + `auth_token` cookie.
3. `POST /api/keys {"name": "jarvis"}` → 201 `{"key": "sk-…"}`.
4. `POST /api/keys/<id>/rotate` if the key ever leaks (it did once during this work, and was
   rotated immediately — the leaked value was never written to any file).

The password lives in `~/.omniroute/.admin-pass` (600) and the gateway key in `~/.hermes/.env` as
`OMNIROUTE_API_KEY` (600). The CLI's own `keys`/`providers` local commands **do not work here**:
`better-sqlite3` has no Termux/arm64 build, so the CLI falls back and reports
`[DB] Sync driver 'better-sqlite3' failed to open`. The HTTP API is the only reliable path.

## What the bundle is therefore for
Not "free tokens out of the box" — that claim would be false. Its real value, once **one** provider
key is connected (AvalAI is the one measured to work from an Iranian network with no VPN):

- one endpoint and one key for Jarvis, instead of N provider entries;
- 463 models behind `model: auto` (and `auto/best-fast`, `auto/best-coding`, …) with fallback when
  a provider fails or runs out of quota — which is exactly what makes a *demo* survive a bad day;
- the dashboard as the place to paste the next free key, with no Jarvis update needed.

So the wizard must say: **"OmniRoute needs one connected provider; the shortest path is AvalAI"** —
and Jarvis can register that provider into OmniRoute itself (`POST /api/provider-connections`)
so the user never has to open the dashboard.

## Cost to keep in mind
2 GB of a phone's storage for the hub, before any provider. `jarvis-omniroute ensure` cleans the
npm cache after install (~1.3 GB) and is idempotent, so a second run costs a status call.
