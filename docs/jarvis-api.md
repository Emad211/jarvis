# Jarvis daemon API (`jarvisd`, 127.0.0.1:8780)

The app talks only to this daemon. Loopback only, no auth (same trust model as gwctl).
All bodies are JSON. Errors are `{"ok": false, "error": "<code>", "detail": "..."}` with a 4xx/5xx.

This file is the **contract** between the daemon (`phone/.local/bin/jarvisd*.py`) and the
Android app (`jarvis/java/ir/emad/jarvis/*.java`). Change it before changing either side.

## Chat (existing)

| method | path | body / query | answer |
|---|---|---|---|
| GET | `/health` | — | `{ok, version, warm, sessions}` |
| GET | `/catalog` | — | models the user can pick |
| POST | `/session/new` | `{title?}` | `{id}` |
| GET | `/sessions` | — | `[{id, title, updated, turns}]` |
| GET | `/session/{sid}` | — | `{id, title, messages[], model, reasoning, persona}` |
| POST | `/session/{sid}/turn` | `{text}` | `{ok, queued}` — output arrives on `/events` |
| GET | `/session/{sid}/events` | `?since=<seq>` | SSE, envelope `{seq, kind, ts, data}` |
| POST | `/session/{sid}/cancel` | — | `{ok}` |
| POST | `/session/{sid}/model` | `{model}` | `{ok, model}` |
| POST | `/session/{sid}/reasoning` | `{effort}` | `{ok, effort}` |
| POST | `/ask/{ask_id}` | `{value}` | `{ok}` |
| POST | `/fast` | `{text}` | fast-path result, no model call |

SSE kinds: `turn.start`, `text.delta`, `thinking.delta`, `tool.start`, `tool.progress`,
`tool.end` (carries `ok`), `status`, `ask`, `error`, `turn.end`.

## Providers

| method | path | body | answer |
|---|---|---|---|
| GET | `/providers` | — | `{providers: [...], kinds: [...]}` |
| POST | `/providers` | `{label, base_url, kind, key, models[], headers?}` | `{ok, id, key_env}` |
| DELETE | `/providers/{id}` | — | `{ok}` |
| POST | `/providers/test` | `{id, model?}` | `{ok, status, ms, reply}` — one tiny chat |
| POST | `/providers/probe` | `{}` | `{results: [{id, ok, ms, status, error}]}` — reachability of every provider, measured |
| POST | `/providers/default` | `{id, model}` | `{ok, provider, model, previous}` |

Provider row: `{id, label, builtin, kind, base_url, models[], key_env, has_key, reachable, is_default, note}`.
`reachable` is `true|false|null` (null = not probed yet). The app shows it as a dot and never
offers a provider whose last probe failed as the default.

`kind` is one of `openai` (`/chat/completions`), `anthropic` (`/v1/messages`), `gemini`
(Google's OpenAI-compatible surface).

## Personas

| method | path | body | answer |
|---|---|---|---|
| GET | `/personas` | — | `[{id, label, emoji, description, soul, builtin, active}]` |
| POST | `/personas` | `{label, description?, emoji?, prompt}` | `{ok, id}` |
| PATCH | `/personas/{id}` | `{label?, description?, emoji?, prompt?}` | `{ok}` |
| DELETE | `/personas/{id}` | — | `{ok}` — builtin personas cannot be deleted |
| POST | `/session/{sid}/persona` | `{id}` | `{ok, persona}` — rebuilds the warm agent |

A persona is a `SOUL.md`-style prompt file under `~/.hermes/jarvis/personas/<id>.md`, injected
as the session's `ephemeral_system_prompt` on top of the base Jarvis brief.

## Skills and self-improvement

| method | path | body | answer |
|---|---|---|---|
| GET | `/skills` | `?q=&scope=` | `[{id, name, description, scope, source, enabled, uses, updated}]` |
| POST | `/skills/{id}/toggle` | `{enabled}` | `{ok}` |
| GET | `/skills/feed` | `?limit=50` | `[{id, title, at, kind: learned\|edited\|archived, detail}]` |
| GET | `/curator` | — | `{enabled, last_run, managed, archived, pinned}` |
| POST | `/curator/run` | — | `{ok, started}` |
| GET | `/memory` | — | `{facts: [...], graph_nodes, updated}` |

`/skills/feed` is the visible self-improvement log: every skill the agent wrote or rewrote,
newest first. The app renders it as a timeline; it is the answer to "what did Jarvis learn".

## Tools: MCP and plugins

| method | path | body | answer |
|---|---|---|---|
| GET | `/mcp` | — | `[{name, transport, target, status, tools[], enabled}]` |
| POST | `/mcp` | `{name, url? | command?, args[]?, env?{}}` | `{ok, name}` |
| DELETE | `/mcp/{name}` | — | `{ok}` |
| POST | `/mcp/{name}/test` | — | `{ok, ms, tools[]}` |
| GET | `/plugins` | — | `[{name, version, enabled, capabilities[]}]` |
| POST | `/plugins/{name}/toggle` | `{enabled}` | `{ok}` |
| GET | `/tools` | — | `[{id, label, toolset, kind: fast\|agent\|mcp, enabled}]` |

## Cron

| method | path | body | answer |
|---|---|---|---|
| GET | `/cron` | — | `[{id, name, schedule, prompt, persona, enabled, next_run, last_run, last_status}]` |
| POST | `/cron` | `{name, schedule, prompt, persona?}` | `{ok, id}` |
| POST | `/cron/{id}/run` | — | `{ok}` |
| POST | `/cron/{id}/pause` | `{paused}` | `{ok}` |
| DELETE | `/cron/{id}` | — | `{ok}` |

`schedule` is a 5-field cron expression in the phone's local time.

## Services and logs (folded in from Hermes Control)

| method | path | body | answer |
|---|---|---|---|
| GET | `/services` | — | `[{name, state, pid, uptime_s, port, health}]` |
| POST | `/services/{name}/restart` | — | `{ok}` — whitelisted names only |
| GET | `/logs` | `?name=jarvis&n=200&grep=` | `{name, lines[]}` |

## Voice

| method | path | body | answer |
|---|---|---|---|
| POST | `/voice/in` | raw WAV body (16 kHz mono) | `{text, ms, model}` |
| GET | `/voice/status` | — | `{stt_model, stt_ms, tts_model, tts_ms, ready}` |
| POST | `/voice/out` | `{text}` | `{url, ms, model}` |
| GET | `/audio/{name}` | — | audio bytes |
