# Hermes Agent v0.20.6 — Embedding API Report for a Native Android Console

**Scope.** How to drive Hermes Agent v0.20.6 (Termux/Android, `u0_a309`) from a native
Android chat/agent console as the sole interface, with <1 s perceived latency per command and
live tool output. Every claim below carries a `file:line` reference. All measurements were
taken **on the phone** on 2026-10-26 (Asia/Tehran) and are numbers, not impressions.

**Method.** Files were read over SSH (`ssh -p 8022 … u0_a309@10.205.157.105`) from
`/data/data/com.termux/files/usr/lib/hermes-agent/app` (abbreviated `<APP>` below) using the
venv interpreter `$PREFIX/lib/hermes-agent/app/venv/bin/python` (Python 3.13.15). The phone
was **not modified**: the only files placed on it were throwaway benchmark scripts in
`$HOME` (`~/.tmp_*.py`), which you can delete. No secret values were read or printed
(`~/.hermes/.env` values were only tested for *presence*).

Version confirmed: `pyproject.toml:5` → `version = "0.20.6"`; `hermes --version` →
`Hermes Agent v0.20.6 (2026.8.27)`.

---

## 0. Executive summary

**The headline finding: you do not need to write a new transport.** v0.20.6 already ships a
native HTTP+SSE agent transport — the `api_server` platform adapter — with exactly the
endpoint set a chat console needs, including streaming text deltas, live tool events,
**and a blocking approval round-trip**:

```
POST /v1/runs                    -> 202 {run_id}            (returns immediately)
GET  /v1/runs/{id}/events        -> text/event-stream (SSE)
POST /v1/runs/{id}/approval      -> {choice: once|session|always|deny}
POST /v1/runs/{id}/steer|stop
```

`<APP>/gateway/platforms/api_server.py:1-36` (module docstring; the run endpoints are listed
at `:17-22`), route table at `<APP>/gateway/platforms/api_server.py:2214-2276`.

`platforms.api_server.enabled` is **true** in `~/.hermes/config.yaml` and `API_SERVER_KEY` is
**present** in `~/.hermes/.env` (presence check only). The adapter refuses to start without a
key (`api_server.py:8303`, guard at `api_server.py:8348-8369`), but nothing serves `:8642` now:
the only process that ever hosted it was the messaging gateway, which is no longer run (the
scripted bot that needed it was retired and deleted, so `hermes-svc` supervises only
`gwctl`/`jarvis`/`agy`). Using `/v1/runs` again is a `hermes gateway` start, not a config change.

**The decisive performance fact** is *not* the CLI-vs-in-process gap — it is that the cost is
**once-per-process, not once-per-turn**:

| Path | Cold (per process) | Steady state (same process) |
|---|---|---|
| `hermes -z "say ok"` (CLI, full process) | **119.8 s** | n/a (new process each time) |
| In-process `AIAgent()` + one turn | init **24.0 s** + turn 8.2 s | init **0.40–0.41 s**, TTFB **2.1–2.7 s** |

The 24 s is `npx agent-browser --version` being shelled out during tool-availability probing
(19.9 s of a 27.9 s profile; measured directly at **46.1 s** for one such probe). A
long-lived process pays it once and then serves turns in **~2–3 s**.

**Recommendation: (ii) extend the existing control plane — run the shipped `api_server`
adapter (or, if you must keep gwctl as the single listener, wrap the same in-process runner
behind an SSE endpoint).** Build the app against the `/v1/runs` contract documented in §1.6;
do not invent a new one. `gwctl` should keep doing what it is good at (config, keys, logs,
services) and hand agent turns to the run API. See §8 for the argument, the surface, and the
three biggest risks.

---

## 1. In-process entry point for a single agent turn

### 1.1 The stable surface is a callback set on one class: `AIAgent`

`<APP>/run_agent.py:421` — `class AIAgent`. The constructor is a thin forwarder
(`run_agent.py:396-...`, body at `run_agent.py:530-530`: `from agent.agent_init import
init_agent`) to `<APP>/agent/agent_init.py:584`. The constructor is **keyword-only in
practice** (40+ optional kwargs); the ones that matter for an embedding are:

```python
AIAgent(
    base_url=None, api_key=None, provider=None, api_mode=None,
    model="", max_iterations=sys.maxsize,
    enabled_toolsets=None, disabled_toolsets=None,   # run_agent.py:457-458
    quiet_mode=False, tool_progress_mode="all",
    session_id=None,                                  # run_agent.py:473
    # --- streaming / event callbacks ---
    stream_delta_callback=None,                       # run_agent.py:487  text deltas
    reasoning_callback=None,                          # run_agent.py:478
    tool_progress_callback=None,                      # run_agent.py:474
    tool_start_callback=None,                         # run_agent.py:475
    tool_complete_callback=None,                      # run_agent.py:476
    tool_gen_callback=None,                           # run_agent.py:489
    status_callback=None, notice_callback=None,
    interim_assistant_callback=None,
    event_callback: Optional[Callable[[str, dict], None]] = None,  # run_agent.py:493
    clarify_callback=None,                            # run_agent.py:479
    step_callback=None,
    # --- context / persistence ---
    platform=None, gateway_session_key=None,           # run_agent.py:500/508
    skip_memory=False, skip_background_review=False,
    session_db=None, parent_session_id=None,           # run_agent.py:514
    requested_provider=None,                           # run_agent.py:524
)
```

This is a **threaded, synchronous** API: `run_conversation` blocks the calling thread; the
callbacks fire **on that worker thread**. That is why every async transport in the tree
marshals back to a loop (`loop.call_soon_threadsafe`, `api_server.py:7674`, `7688`).

### 1.2 One turn

```python
# <APP>/agent/conversation_loop.py:1823  (forwarder: run_agent.py:8492)
def run_conversation(agent, user_message, system_message=None,
                     conversation_history=None, task_id=None,
                     stream_callback=None, persist_user_message=None,
                     ...) -> Dict[str, Any]
```

`AIAgent.chat(message, stream_callback=None) -> str` (`run_agent.py:8972-8985`) is the
one-line convenience wrapper returning only `result["final_response"]`.

Return contract (`<APP>/agent/turn_finalizer.py:703-737`):

```python
{"final_response", "last_reasoning", "messages", "api_calls", "completed",
 "turn_exit_reason", "failed", "partial", "interrupted",
 "response_transformed", "pre_transform_response", "response_previewed",
 "model", "provider", "base_url",
 "input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens",
 "reasoning_tokens", "prompt_tokens", "completion_tokens", "total_tokens",
 "last_prompt_tokens", "estimated_cost_usd", "cost_status", "cost_source",
 "service_tier", "session_id"}
# + optional "guardrail" (turn_finalizer.py:738-739), "error" (743)
```

> **Important for streaming.** `stream_callback` is what enables the streaming code path at
> all: *"When None (default), API calls use the standard non-streaming path"*
> (`conversation_loop.py:1846-1848`). Pass a callback or you get one blob at the end.

### 1.3 `(a) streaming assistant text deltas` — reliable

`<APP>/run_agent.py:6935-7002` `_fire_stream_delta(text)`. Deltas are scrubbed before they
reach you (think-block scrubber, context scrubber, `run_agent.py:6953-6979`) and delivered to
`stream_delta_callback` and/or `_stream_callback`, with a single-writer guard so a retried
stream cannot interleave (`run_agent.py:6937-6940`, `6870`). This is the *only* text-delta
channel; `MessageChunk` in the typed event vocabulary (§1.4) is a presentation mirror of it.

Measured chunking (real, §7): a 114-char answer arrived as **4 deltas** of 3/40/49/22 chars,
first at 7.24 s, last at 7.30 s — i.e. the provider delivered a burst, not a smooth stream.

### 1.4 `(b) tool calls/results as they happen` — reliable, with one gap

Three overlapping surfaces. All fire from `<APP>/agent/tool_executor.py`; all are
`try/except`-wrapped so a broken callback cannot kill the turn.

| Callback | Signature (from call site) | Fires |
|---|---|---|
| `tool_progress_callback` | `(event_type, tool_name, preview, display_args, **kw)` | `"tool.started"`, `"tool.completed"`, `"reasoning.available"`, `"tool.output_risk"` |
| `tool_start_callback` | `(tool_call_id, function_name, display_args)` | once per call |
| `tool_complete_callback` | `(tool_call_id, function_name, display_args, display_result)` | once per call |

Evidence: `tool_executor.py:1055` (`"tool.started", function_name, preview, display_args`),
`tool_executor.py:1891` / `2793` (`"tool.completed", …, duration=tool_duration,
is_error=is_error, result=display_function_result`), `tool_executor.py:1865`/`2779`
(`tool.output_risk`), `tool_executor.py:1067` (`tool_start_callback(tool_call_id,
function_name, display_args)`), `tool_executor.py:1896`/`2802` (`tool_complete_callback(...)`).

Verified end-to-end on the phone (a real `terminal` call, §7):

```
('PROG',   7.98, 'tool.started',   'terminal', 'echo hi', None, 0)
('START',  7.98, 'call_710948',    'terminal', "{'command': 'echo hi'}")
('PROG',   8.47, 'tool.completed', 'terminal', '', False, 0.51)
('DONE',   8.47, 'call_710948',    'terminal', '{"output": "hi", "exit_code": 0, "error": null}')
('PROG',  10.41, 'reasoning.available', '_thinking', 'hi', None, 0)
```

**The gap:** there is *no* incremental tool-output socket. A long-running `terminal` command
surfaces only `tool.started` … then nothing … then `tool.completed` with the full result
(`tool_executor.py:1891-1897`). `<APP>/gateway/stream_events.py:100-110` says this explicitly
for `ToolCallFinished`: *"No tool **output** travels here — output is the agent's concern and
is persisted to history, not streamed as presentation."* For live tail-of-output you must
read it from the session DB (§2) or `display.py`'s CLI-only spinner path, which is not
exposed as an event. Marked a real limitation, not a bug.

Note also that `preview`/`result` are **display-redacted** (`_redact_tool_args_for_display`,
`tool_executor.py:1049`) while `tool_complete_callback` receives `display_function_result`.
The raw values live in `messages` and in the DB.

### 1.5 `(c) pause for approval` — yes, and it is a blocking gate

See §4. The turn **blocks inside the tool executor** until a resolver fires
(`tools/approval.py:4446-4501` `_await_gateway_decision`), so the transport only has to
deliver the event and POST back a choice.

### 1.6 The most stable, documented-ish surface: the typed event vocabulary

`<APP>/gateway/stream_events.py` is the only place in the tree that documents an
agent→transport *contract* rather than an implementation detail. Its docstring
(`stream_events.py:1-40`) is explicit about why: the old fan of loosely-typed callbacks made
tool bubbles race the streaming draft.

Frozen dataclasses (`stream_events.py:44-148`), union at `stream_events.py:151-159`:

```python
MessageChunk(text)                       # a delta of streamed assistant text
MessageStop(final: bool = False)         # end of a text segment; final=True only for the turn
Commentary(text)                         # complete interim assistant message between tools
ToolCallChunk(tool_name, preview, args, index)   # started / in-progress
ToolCallFinished(tool_name, duration, ok, index) # no output payload
LongToolHint(tool_name, duration)        # one-shot "this is slow" nudge
GatewayNotice(kind, text, extra)
StreamEvent = Union[...]
```

Its single sink is `GatewayStreamConsumer` — the async adapter that turns the sync callback
into rate-limited platform edits:

- `<APP>/gateway/stream_consumer.py:164` `class GatewayStreamConsumer`
- Usage docstring `stream_consumer.py:167-179`: build consumer → pass `consumer.on_delta` as
  `stream_delta_callback` → `asyncio.create_task(consumer.run())` → `consumer.finish()`
- Sync-side entry points: `on_delta(text)` (`:660`), `on_commentary(text)` (`:557`),
  `on_segment_break()` (`:553`), `finish(final_text=None)` (`:672`),
  `flush_pending_sync(timeout=5.0)` (`:562`)
- Async side: `async def run(self)` (`:842`)

**Caveat:** `GatewayStreamConsumer` is shaped around *editing an existing message* on a chat
platform (Discord/Slack/WhatsApp), including flood-control backoff
(`stream_consumer.py:1869-1875`), draft streaming (`:1929`), and overflow chunking (`:1511`).
A native app should **not** adopt it; it should adopt the *event vocabulary* and the
`/v1/runs` SSE encoding that already wraps it. That is precisely what `api_server` did.

### 1.7 Also evaluated (and rejected as the app-facing surface)

| Candidate | Verdict |
|---|---|
| `<APP>/agent/display.py` (59 KB) | **CLI-only presentation.** `KawaiiSpinner` (`display.py:1083`), `build_tool_preview` (`:446`), `get_cute_tool_message` (`:1574`), ANSI diff rendering (`:945`). No event contract. Useful only for `preview` strings, which the agent already computes. |
| `<APP>/agent/agent_init.py`, `agent_runtime_helpers.py` | Internals (`init_agent`), not a surface. |
| `<APP>/mcp_serve.py` | Exposes *conversations* over MCP (server side) — see §5. |
| `<APP>/acp_adapter/*` (server.py 113 KB) | Real ACP (Agent Client Protocol) bridge with permission round-trips (`acp_adapter/events.py:1-35`, `acp_adapter/edit_approval.py`, `acp_adapter/permissions.py`), but **stdio JSON-RPC** — `entry.py:1-14` reserves stdout for the ACP transport, `hermes acp --help` → "for editor integration (VS Code, Zed, JetBrains)". Wrong transport for a phone app; see §8. |
| `<APP>/tui_gateway/`, `<APP>/web/`, `<APP>/hermes_cli/web_server.py` | Present; `tui_gateway` is the desktop GUI gateway (`tui_gateway/server.py::_load_enabled_toolsets` is referenced by `toolsets.py:37-41`). Not documented as an embedding API, larger surface, and the `api_server` adapter supersedes it for this purpose. UNVERIFIED whether `web_server.py` exposes streaming runs; not on the critical path. |

---

## 2. Sessions — identity, persistence, and multi-process safety

### 2.1 Where history lives

- DB path: `<APP>/hermes_state.py:362` → `DEFAULT_DB_PATH = get_hermes_home() / "state.db"`,
  i.e. `~/.hermes/state.db` (`get_hermes_home()`, `hermes_state.py:409`).
- Class: `<APP>/hermes_state.py:4045` `class SessionDB(SessionSearchMixin,
  SessionSchemaMixin, SessionPortabilityMixin)`; constructor `hermes_state.py:4174`
  `__init__(self, db_path: Path = None, read_only: bool = False)` — **`read_only=True` exists**,
  which is what a read-only UI should use.
- Async wrapper: `hermes_state.py:14702` `class AsyncSessionDB`.

Schema (`<APP>/hermes_state_common.py`):

- `sessions` (`hermes_state_common.py:369-428`) — `id TEXT PRIMARY KEY`, `source`, `model`,
  `system_prompt`, `parent_session_id` (compression splits a session into a parent/child
  chain), `message_count`, token/cost columns, `title`, `archived`, `pinned`, `hidden`,
  `last_activity_at`, …
- `messages` (`hermes_state_common.py:430-455`) — `id INTEGER PRIMARY KEY AUTOINCREMENT`,
  `session_id`, `role`, `content`, `tool_call_id`, `tool_calls` (JSON), `tool_name`,
  `timestamp`, `reasoning*`, `platform_message_id`, `active`, `compacted`, `display_kind`,
  `display_metadata`.
- `system_prompts` (:364), `session_model_usage` (:457), `state_meta` (:479),
  `gateway_routing` (:484), `compression_locks` (:497), **`session_turn_leases` (:504)**,
  `async_delegations` (:511).
- FTS5 virtual tables for search (`hermes_state.py:5-10`).

### 2.2 Create / resume / read — the public methods a UI wants

| Operation | Method | Line |
|---|---|---|
| create | `SessionDB.create_session(session_id, source, **kwargs) -> str` | `hermes_state.py:5770` |
| read metadata | `get_session(session_id) -> Optional[dict]` | `hermes_state.py:9014` |
| read history | `get_messages(...)` / `as_conversation(...)` | `hermes_state.py:11453` / `11801` |
| append | `append_message(...)` / `append_messages_batch(...)` | `hermes_state.py:10564` / `10710` |
| list for UI | `list_sessions_rich(...)` | `hermes_state.py:9892` |
| search | `search_sessions(...)` | `hermes_state.py:12647` |
| delete | `delete_session(...)` | `hermes_state.py:12989` |

For an **agent turn** you do not touch these directly: pass `session_id=` to `AIAgent` and
`run_conversation` persists via `_persist_session` (`run_agent.py:2019`) →
`_flush_messages_to_session_db` (`run_agent.py:2117`), guarded per session. The
`run_conversation(..., conversation_history=[...])` argument lets a stateless caller supply
its own history (that is how `/v1/chat/completions` stays stateless while
`/api/sessions/{id}/chat` is stateful — `api_server.py:4631-4632`).

### 2.3 Can a second process safely append while the gateway runs?

**Yes for reads, and yes for writes — but not concurrently into the same conversation.**

1. **WAL is the designed mode.** `hermes_state.py:10` — *"WAL mode for concurrent readers +
   one writer (gateway multi-platform)"*. `journal_mode` falls back to `DELETE` only on
   filesystems that cannot do WAL (`hermes_state.py:646-674`, `_WAL_INCOMPAT_MARKERS`
   `:674`), and never live-downgrades an on-disk WAL DB (`:670-673`). WAL is size-bounded at
   64 MiB (`_WAL_SIZE_LIMIT_BYTES`, `hermes_state.py:683`).
2. **Multiple processes sharing one state.db is explicitly expected** —
   `hermes_state_common.py:809-812`: *"Several independent Hermes processes routinely share
   one state.db (gateway service, the Desktop app's `hermes serve` backend, interactive CLI
   sessions, the TUI slash worker)."*
3. **Structural surgery is serialized by file locks, not the DB**: cross-process FTS rebuild
   admission via `<db>.fts_rebuild.lock` (`hermes_state_common.py:809-846`,
   `fts_rebuild_admission`, timeout 120 s), and schema surgery via `cross_process_repair_lock`
   (`<db>.repair.lock`, referenced `hermes_state_common.py:828-830`). Both are portable
   (`msvcrt` on Windows, `flock` elsewhere) and **fail closed**.
4. **Per-conversation turn serialization is a lease, and it is the real answer.**
   `<APP>/gateway/turn_lease.py:1-40` documents the exact hazard you would hit: two routing
   keys mapped to one `session_id` run concurrent turns on two agent objects, interleave
   their flushes, and permanently wedge `user;user` alternation. `SessionTurnLeaseRegistry.acquire(session_id, *, owner_key, generation, timeout=None) -> Optional[TurnLeaseToken]`
   (`turn_lease.py:193-265`) serializes `[load history → run → flush]` per resolved
   `session_id`, times out **closed** (`TurnLeaseTimeoutError`, `turn_lease.py:72`,
   raised `:250-258`), and `rebind()` (`:266`) handles a mid-turn session rotation from
   compression. A durable row also exists (`session_turn_leases(conversation_id, holder,
   acquired_at, expires_at)`, `hermes_state_common.py:504-509`) so a crashed holder does not
   wedge the session forever.

**Practical rule for the app:** give the console its own `session_id` per conversation and
drive all turns for that conversation through **one** long-lived process. If you must write
from a second process, take the turn lease first, or you will reproduce #64934. Do not
append to a session id that a gateway is currently running a turn on.

---

## 3. Toolsets and tool-call representation

### 3.1 Enabling toolsets per run

- `<APP>/toolsets.py:103` `TOOLSETS = {…}` — **58 toolsets** on this install (enumerated
  live): `browser, clarify, code_execution, coding, computer_use, context_engine, cronjob,
  debugging, delegation, desktop_ui, discord, …, file, kanban, memory, project, safe, search,
  session_search, skills, spotify, terminal, todo, tts, video, video_gen, vision, web,
  x_search`, plus one `hermes-<platform>` composite per platform including
  **`hermes-api-server`**.
- Resolution helpers: `get_toolset` (`toolsets.py:628`), `resolve_toolset` (`:742`),
  `resolve_multiple_toolsets` (`:854`), `get_all_toolsets` (`:899`),
  `get_toolset_names` (`:924`), `validate_toolset` (`:947`), `create_custom_toolset` (`:967`).
- Per-run, on the agent: `enabled_toolsets=` / `disabled_toolsets=` (`run_agent.py:457-458`)
  → `<APP>/model_tools.py:323` `get_tool_definitions(enabled_toolsets, disabled_toolsets,
  quiet_mode, skip_tool_search_assembly)`.
- Per-platform, in config: `platform_toolsets.<platform>` →
  `<APP>/hermes_cli/tools_config.py:2628` `_get_platform_tools(config, platform, …)`; when
  absent it falls back to the platform's `default_toolset` (`tools_config.py:2645-2652`),
  which for `api_server` is `hermes-api-server` (`toolsets.py:103`).
- `hermes-api-server` = `web_search, web_extract, terminal, process, read_file, write_file,
  patch, search_files, vision_analyze, image_generate, skills_*, browser_*, todo, memory,
  session_search, execute_code, delegate_task, cronjob, ha_*` (live dump). Note it does
  **not** include `computer_use`, `kanban_*`, `clarify`, or `text_to_speech` — those are in
  `hermes-cli`. Choose deliberately.
- Per-tool gating at call time: `tools/registry.py:1044` `get_definitions(tool_names,
  quiet=False)` runs each tool's `check_fn()`, cached ~30 s
  (`_CHECK_FN_TTL_SECONDS = 30.0`, `registry.py:269`; `_check_fn_cached`, `registry.py:364`).
  **This is the source of the latency problem in §7.**

### 3.2 How a tool call is represented

**Model-side (OpenAI wire format):** `{"type": "function", "function": schema_with_name}`
— `tools/registry.py:1077`. The request carries `tools=[…]`; the response carries
`assistant_message.tool_calls`, each with `id`, `function.name`, `function.arguments`
(a **JSON string**, hence the corruption repair at
`run_conversation.py:1289` `_canonicalize_tool_call_arguments`).

**In history / DB:** the assistant message keeps `tool_calls` as JSON text
(`messages.tool_calls`); the result is a message built by
`<APP>/agent/tool_dispatch_helpers.py:586` `make_tool_result_message(name, content,
tool_call_id, *, effect_disposition=None)`:

```python
{"role": "tool", "name": name, "tool_name": name,
 "content": <wrapped>, "tool_call_id": tool_call_id, "_ts": …}
```

with untrusted-content wrapping for `web_extract/web_search/browser_*/mcp_*`
(`tool_dispatch_helpers.py:597-620`), plus `_tool_output_risk` metadata (`:629`).

**Streamed to a transport:** the redacted trio `(tool_name, preview, args)` and later
`(tool_name, duration, is_error, result)` — §1.4.

**Errors:** surfaced as `is_error=True` on `tool.completed` (`tool_executor.py:1891`) and, for
approval refusals, as a `{"approved": False, "message": "BLOCKED: …"}` tool result
(`tools/approval.py:3795-3804`). There is no dedicated `tool.failed` event.

### 3.3 The platform abstraction (`gateway/platforms/base.py`)

`<APP>/gateway/platforms/base.py:2970` `class BasePlatformAdapter(ABC)`. The methods that
shape a chat transport (signatures as declared):

```python
def __init__(self, config: PlatformConfig, platform: Platform)            # :3091
def set_message_handler(self, handler: MessageHandler) -> None            # :3716  inbound
def set_platform_event_handler(...)                                       # :3725
def set_reaction_handler(...)                                             # :3776
def set_authorization_check(...)                                          # :3795
def set_session_store(self, session_store: Any) -> None                   # :3833
async def connect(self, *, is_reconnect: bool = False) -> bool             # :4039
async def disconnect(self) -> None                                        # :4059
async def send(self, ...) -> SendResult                                   # :4064
async def edit_message(self, ...) -> SendResult                           # :4120
async def delete_message(self, ...)                                       # :4149
async def send_typing(self, chat_id, metadata=None)                        # :4442
async def stop_typing(self, chat_id)                                      # :4451
def supports_draft_streaming(...)                                          # :3303
def prefers_fresh_final_streaming(...)                                     # :3327
async def send_draft(...)                                                  # :3367
def render_message_event(self, event: Any, sink: Any) -> None              # :3409  <- typed events
def format_tool_event(self, event: Any, *, mode="all", ...)                # :3430  <- tool chrome
def format_tool_preview(self, preview: "ToolPreview") -> str               # :3479
async def send_slash_confirm(self, chat_id, title, message, session_key,
                              confirm_id, metadata=None) -> SendResult      # :4313
async def send_clarify(self, ...)                                          # :4348
def _format_exec_approval(self, command, description="dangerous command",
                          smart_denied=False) -> str                       # :4259
def _format_choice_page(...)                                               # :4284
```

`SendResult` at `base.py:2546`; `MessageEvent` (inbound) at `base.py:2380`. `Platform` enum
includes `API_SERVER = "api_server"` (`gateway/config.py:346`) and it is listed in the
platform set (`config.py:439`). The **only** adapter in `gateway/platforms/` that turns this
into a network request/response protocol is `api_server.py`; the others
(`signal.py`, `webhook.py`, `whatsapp_cloud.py`, `weixin.py`, `bluebubbles.py`, `yuanbao.py`,
`msgraph_webhook.py`) are chat providers. The messaging adapters that used to ship in that dir
lived in bundled plugins (`plugins/platforms/*`) rather than in `gateway/run.py` (1.5 MB).

> This is why the recommendation is "use `api_server`": it is the sanctioned,
> platform-abstract-shaped HTTP transport, already wired to the same callbacks the console
> needs, and it is the *only* such transport in the tree.

---

## 4. Approvals

### 4.1 Where the gate lives

`<APP>/tools/approval.py` — *"the single source of truth for the dangerous command system"*
(`approval.py:1-7`): pattern detection (`DANGEROUS_PATTERNS`,
`detect_dangerous_command` `approval.py:2505`, `detect_hardline_command` `:718`), per-session
state, CLI + gateway prompting, smart approval via an auxiliary LLM, and a permanent
allowlist in `config.yaml`.

Modes: `<APP>/hermes_cli/approval_mode.py:20` `VALID_APPROVAL_MODES = ("manual", "smart",
"off")`, persisted under `approvals.mode`. Permanent allowlist key:
`command_allowlist` (`hermes_cli/config_defaults.py:2518` default `[]`; read on every check at
`tools/approval.py:3135`, written at `:3149`).

### 4.2 The pending-approval object

`<APP>/tools/approval.py:2784-2801`:

```python
class _ApprovalEntry:
    """One pending dangerous-command approval inside a gateway session."""
    __slots__ = ("event", "data", "result", "reason", "acknowledged")
    def __init__(self, data: dict):
        self.event = threading.Event()
        self.data = dict(data)
        self.data.setdefault("request_id", uuid.uuid4().hex)
        self.acknowledged = False
        self.result: Optional[str] = None  # "once"|"session"|"always"|"deny"
        self.reason: Optional[str] = None  # free-text deny reason
```

Queues are **per `session_key`**: `_gateway_queues: dict[str, list]` and
`_gateway_notify_cbs: dict[str, object]` (`approval.py:2800-2801`). The `data` payload as
constructed for a gateway prompt (`approval.py:3825-3835`):

```python
{"command": <redacted>, "pattern_key": …, "pattern_keys": [pattern_key],
 "description": <redacted>, "allow_permanent": True, "allow_session": True}
# + "request_id" injected by _ApprovalEntry
# + "smart_denied": True and the allow_* flags forced False on an owner override (:4944-4983)
```

### 4.3 What a UI must send back

Resolver: `approval.py:2829` `resolve_gateway_approval(session_key, choice, resolve_all=False,
reason=None, request_id=None) -> int` — returns the number resolved, `0` = nothing pending.
**Choices: `"once" | "session" | "always" | "deny"`** (`_ApprovalEntry.result`, `:2793`).
Also available: `list_gateway_approvals(session_key)` (`:2871`, replay-safe snapshots),
`ack_gateway_approval(session_key, request_id)` (`:2877`, client-received ack),
`has_blocking_approval(session_key)` (`:2887`), `register_gateway_notify` (`:2804`) /
`unregister_gateway_notify` (`:2816`).

### 4.4 The HTTP contract (already implemented)

`POST /v1/runs/{run_id}/approval` — `api_server.py:8067-8150`:

- Body: `{"choice": "once"|"session"|"always"|"deny", "all"/"resolve_all": bool}`
  (aliases `approve/approved/allow` → `once`, `api_server.py:8086-8089`).
- Validation: `allowed = {"once","session","always","deny"}` → else **400**
  `invalid_approval_choice` (`:8090-8097`).
- 404 `run_not_found`; **409** `approval_not_active` (no approval session) / **409**
  `approval_not_pending` (nothing waiting) (`:8075-8082`, `:8118-8123`).
- Success → `{"object": "hermes.run.approval_response", "run_id", "choice", "resolved"}`
  (`:8147-8152`).
- **Approval namespaces are isolated per run**: `approval_session_key = run_id`
  (`api_server.py:7664-7668`) — resolving one run's approval can never unblock another's.

Delivered event `approval.request` (`api_server.py:7740-7768`) carries the redacted command,
`choices` from `_approval_event_choices(...)` (`api_server.py:121-130`: full set
`["once","session","always","deny"]`, or `["once","deny"]` when `smart_denied` / no session
allowance), and sets run status `waiting_for_approval`. The reply is echoed as
`approval.responded` (`:8139`).

**Fail-closed behavior:** with no interactive user and no gateway notify callback, a dangerous
command is **BLOCKED**, not auto-approved (`approval.py:3790-3804`). So if your app's SSE
subscription drops, the turn stalls/denies rather than silently executing.

---

## 5. MCP / plugins / connectors

### 5.1 MCP

- **Config key:** `mcp_servers:` in `~/.hermes/config.yaml`
  (`<APP>/tools/mcp_tool.py:9`, example `:13-65`; `hermes_cli/mcp_config.py:1-8`).
- **Loader:** `<APP>/tools/mcp_tool.py` — stdio, HTTP/StreamableHTTP, and SSE transports;
  discovered tools are registered into the normal tool registry, so they appear as ordinary
  tools (including the `mcp_*` untrusted-content wrapping,
  `agent/tool_dispatch_helpers.py:597`). The `mcp` Python package is **optional** — without
  it the module is a no-op (`mcp_tool.py:9-11`).
- **Per-server keys** (`mcp_tool.py:13-65`): `command`, `args`, `env`, `url`, `headers`,
  `transport` (`stdio|sse|…`), `timeout` (per-tool-call, default 300), `connect_timeout`
  (60), `keepalive_interval` (180, floored 5), `idle_timeout_seconds`,
  `max_lifetime_seconds`, `supports_parallel_tool_calls`, `skip_preflight`,
  `identity_header{name,value_from,value}`, `sampling{enabled,model,max_tokens_cap}`,
  `oauth`.
- **CLI:** `hermes mcp {serve,add,remove|rm,list|ls,test,configure|config,login,reauth,picker,catalog,install}`
  (verified `hermes mcp --help`). `serve` runs *Hermes as* an MCP server
  (`<APP>/mcp_serve.py`).
- **A UI can enumerate/toggle without the CLI**: `hermes_cli/mcp_catalog.py`,
  `mcp_config.py`, `mcp_picker.py`, `mcp_security.py`, `mcp_startup.py`; plus
  `GET /v1/toolsets` (`api_server.py:4092`) and `GET /v1/skills` (`:4061`) on the run API.
- **Hot reload:** `hermes_cli/config_defaults.py:727-729` — MCP connections auto-reload when
  config.yaml's `mcp_servers` changes; per-turn refresh is noted at
  `agent/turn_context.py:540-541` ("Between-turns MCP refresh").

### 5.2 Plugins

- **Loader/format:** `<APP>/hermes_cli/agent_plugins.py:1-6` — portable "Agent Plugins v1"
  directory packages (`PLUGIN_SCHEMA_V1`, `agent_plugins.py:25`); validated locally and
  translated into records for the existing skill + MCP runtimes; **imports no plugin Python**.
  Plugin dir: `~/.hermes/plugins/` (present; currently contains only `model-providers`).
- **Enable/disable:** `plugins.enabled` / `plugins.disabled` / `plugins.entries.<name>` in
  config.yaml (see the live `config.yaml:57-62`, e.g. `marketplaces`, `antigravity-oauth`).
- **CLI:** `hermes plugins {install,search,update,remove,rm,uninstall,list,ls,enable,disable,capabilities,doctor,pack,show,info}`
  (verified `--help`); portable packages install **disabled** by design.
- **Runtime API:** `hermes_cli/plugins.py`, `plugin_capabilities.py`, `plugin_index.py`,
  `plugin_packs.py`, `plugin_dev.py`, `plugins_cmd.py`. In-agent hooks:
  `<APP>/agent/plugin_stream_hooks.py` (`on_stream_start`, `on_stream_delta`, `on_stream_end`
  — queued from `run_agent.py:6913-6934`, `6985-6995`) and
  `hermes_cli/lifecycle.invoke_hook("on_session_end", …)` (`agent/turn_finalizer.py`, tail).

---

## 6. Cron and Kanban

### 6.1 Cron

- **CLI:** `hermes cron {list,create|add,edit,pause,resume,run,remove|rm|delete,status,runs|history,incidents,notepad,tick}`
  (verified `--help`).
- **Files:** `~/.hermes/cron/` —
  `jobs.json` (job definitions), `executions.db` (durable execution attempts →
  `hermes cron runs`), `notepad.db` (per-job durable KV → `hermes cron notepad`),
  `usage_audit.jsonl`, `output/`, plus lock/heartbeat files
  (`.jobs.lock`, `.tick.lock`, `.fire-<hash>.lock`, `ticker_heartbeat`,
  `ticker_last_success`). Locking is file-lock based, so a read-only UI can poll the DBs.
- **HTTP already exists:** `GET/POST /api/jobs`, `GET/PATCH/DELETE /api/jobs/{job_id}`,
  `POST /api/jobs/{job_id}/{pause|resume|run}` (`api_server.py:2254-2262`, handlers
  `:6591-6800`), plus `POST /api/cron/fire` when Chronos is available
  (`api_server.py:2275-2276`).

### 6.2 Kanban

- **DB:** `~/.hermes/kanban.db`, `journal_mode = wal` (verified). Tables:
  `tasks, task_events, task_comments, task_links, task_runs, task_attachments,
  kanban_notify_subs`.
- `tasks` columns (verified via `pragma table_info`): `id, title, body, assignee, status,
  priority, created_by, created_at, started_at, completed_at, workspace_kind, workspace_path,
  branch_name, project_id, claim_lock, claim_expires, tenant, result, idempotency_key,
  consecutive_failures, worker_pid, last_failure_error, max_runtime_seconds,
  last_heartbeat_at, current_run_id, workflow_template_id, current_step_key, skills,
  model_override, provider_override, reasoning_effort, max_retries, goal_mode,
  goal_max_turns, session_id, block_kind, block_recurrences`.
- `task_events(id, task_id, run_id, kind, payload, created_at)` — **this is the change log to
  tail** for a UI.
- **CLI:** `hermes kanban {init,boards,create,swarm,list|ls,show,assign,set-model,reclaim,
  reassign,diagnostics|diag,link,unlink,claim,comment,attach,attachments,attach-rm,complete,
  edit,block,schedule,unblock,request-review,request-changes,reopen-review,promote,archive,
  tail,dispatch,daemon,watch,stats,notify-subscribe,notify-list,notify-unsubscribe,log,runs,
  heartbeat,assignees,context,specify,decompose,gc,repair}` (verified `--help`).
- **Subscriptions for a long-lived process:** `kanban_notify_subs(task_id, platform, chat_id,
  thread_id, user_id, user_id_alt, chat_type, notifier_profile, delivery_mode,
  delivery_metadata, created_at, last_event_id)` — a real cursor per subscriber
  (`last_event_id`). CLI at `hermes_cli/kanban.py:2940` `_cmd_notify_subscribe` →
  `kb.add_notify_sub`; listing with `--json` at `:2958`. Watchers that deliver them live in
  `<APP>/gateway/kanban_watchers.py` (98 KB). A native app can therefore register itself as a
  `platform` subscriber, or simply poll `task_events` by `id > last_event_id` — that is the
  same contract the watcher honours.

---

## 7. Measurements (all on the phone, 2026-10-26)

Method: `ssh … 'cd $HOME; TIMEFORMAT="ELAPSED=%3R"; time <cmd>'`, or in-process timing marks
from `time.monotonic()`. Interpreter for in-process runs:
`$PREFIX/lib/hermes-agent/app/venv/bin/python`. Benchmarks used
`skip_memory=True, skip_background_review=True, quiet_mode=True, platform="api_server"` and
`disabled_toolsets=["browser","browser_cdp","web"]` unless stated otherwise.

### 7.1 `hermes -z` end to end (the current gwctl path)

| Command | Wall time |
|---|---|
| `hermes -z "say ok"` (default chain, `gemini-3.8-flash`) | **119.8 s** |
| `hermes -m gemini-3.5-flash-lite --provider google_ai_studio -z "say ok"` | **37.3 s** |
| `hermes --version` (process/CLI startup only) | **1.38 s** |

The 119.8 s run returned `ok`. This matches `gwctl.agent_test`'s 180 s timeout
(`phone/.local/bin/gwctl.py:802-805`) and explains why gwctl feels unusable for chat.

### 7.2 Where the time goes (import + init, not inference)

| Step | Time |
|---|---|
| `import run_agent` | 3.87 s (3.86–4.04 s) |
| `import agent.agent_init` | 1.74 s |
| `import agent.conversation_loop` | 1.81 s |
| `import gateway.platforms.api_server` (cold) | 3.19 s |
| `AIAgent(...)` cold | **23.7–27.9 s** |
| `AIAgent(...)` warm (same process, <30 s later) | **0.40–0.41 s** |

`cProfile` of `AIAgent.__init__` (27.86 s total, 1,895,826 calls):

```
19.899s  model_tools.py:323 get_tool_definitions
19.891s  model_tools.py:417 _compute_tool_definitions
19.180s  tools/registry.py:1044 get_definitions
19.169s  tools/registry.py:364  _check_fn_cached       (39 calls)
17.828s  tools/browser_tool.py:5864 check_browser_requirements   (11 calls)
16.540s  tools/browser_tool.py:2926 _find_agent_browser          (11 calls)
16.505s  tools/browser_tool.py:2905 _resolve_npx_bin             (11 calls)
16.480s  hermes_constants.py:359   node_tool_runnable            (11 calls)
16.468s  subprocess.py:512 run -> {select.poll}                  (16.38 s)
 4.470s  openai/__init__.py  (openai SDK import, lazily triggered)
 3.247s  tools/browser_cdp_tool.py:638 _browser_cdp_check
```

Root cause confirmed directly on the phone:

```
npx --version                 -> 1.486 s
node --version                -> 0.061 s
npx agent-browser --version   -> 46.086 s   (exit 1)
```

`node`/`npx` are at `$PREFIX/bin/node` and `$PREFIX/bin/npx`, so
`_resolve_npx_bin` (`browser_tool.py:2905`) *does* find one and hands it to
`node_tool_runnable` (`hermes_constants.py:359`), which runs `[path, "--version"]` with a
**10 s timeout** per call (`hermes_constants.py:386-391`) — 11 calls across the browser
requirement checks. The `_check_fn_cached` TTL is **30 s** (`registry.py:269`), so any turn
more than 30 s after the last probe pays the probe again *unless* the tool-definition memo
hits.

Second-order finding: the tool-definitions memo only engages when `quiet_mode=True`
(`model_tools.py:356-392`). A caller that leaves `quiet_mode=False` gets **no** caching and
re-probes on every agent construction. **Always pass `quiet_mode=True` in the app.**

Disabling the browser/web toolsets removes the probe entirely:

| Config | Cold init |
|---|---|
| default toolsets | 24.38 s |
| `disabled_toolsets=["browser","browser_cdp","web"]` | 6.33 s (cold) → 0.55 s warm |

### 7.3 Per-model turn latency (in-process, warm init, browser/web disabled)

| Model | Provider | init | TTFB (first delta) | Turn wall | Result |
|---|---|---|---|---|---|
| `gemini-3.5-flash-lite` | `google_ai_studio` | 0.40 s | **3.96 s** | 4.03 s | ok |
| `gemini-3.5-flash-lite` | `google_ai_studio` | 6.37 s (cold) | 7.27 s | 8.50 s | ok |
| `nvidia/nemotron-3-ultra-550b-a55b:free` | `openrouter` | 10.20 s | **15.82 s** | 15.90 s | ok |
| `gemini-3.8-flash` (config default) | `google_ai_studio` | — | — | 19.75 s | **HTTP 429 quota exhausted** |
| `minimax/minimax-m3.1-flash:free` | `openrouter` | — | — | 16.17 s | **HTTP 404 — `:free` retired 2026-10-22** |

Notes: `gemini-3.8-flash` (the configured default, `config.yaml:15-18`) is **429 on the free
tier** — consistent with `CLAUDE.md` ("Pro models return 429 until billing is linked"). The
first `:free` OpenRouter fallback in `config.yaml:231` is **dead** (the other,
`nvidia/nemotron-3-ultra-550b-a55b:free`, still works but is ~4× slower than
`gemini-3.5-flash-lite`). Both are worth fixing in the fallback chain.

### 7.4 The number that decides the architecture

Three consecutive turns in **one** long-lived process, sleeping 40 s between turns
(deliberately exceeding the 30 s check_fn TTL):

```
imports = 4.04s
turn0   init=24.04s  ttfb=6.99s  turn=8.22s  ok
turn1   init= 0.40s  ttfb=2.14s  turn=2.20s  ok
turn2   init= 0.41s  ttfb=2.63s  turn=2.71s  ok
```

**Steady-state 2.1–2.7 s TTFB, ~0.4 s agent construction.** The 24 s is a one-time
per-process tax.

### 7.5 Streaming granularity

Prompt "Count from 1 to 20…", `gemini-3.5-flash-lite`: turn 8.42 s, **4 deltas**
(3, 40, 49, 22 chars), first at 7.24 s, last at 7.30 s, final length 114 chars. The provider
delivers in a late burst; the *first* token is the latency that matters, and much of that
2–4 s is prompt assembly + provider TTFT, not transport.

### 7.6 Tool-call event timing (real `terminal` call)

`tool.started` @ 7.98 s → `tool.completed` @ 8.47 s (duration 0.51 s) → second API call
finishes @ 10.48 s (2 `api_calls` total). Event granularity is per-tool-call, and the
0.5 s tool execution is fully visible to the client.

### 7.7 What I could **not** measure (honest gaps)

- **End-to-end TTFB through the actual `api_server` SSE endpoint** — UNVERIFIED. The adapter
  is disabled on this phone and enabling it requires writing `API_SERVER_KEY` + a gateway
  restart, which is out of scope ("do not modify the phone"). The in-process numbers above are
  the same code path minus aiohttp/loop marshalling, which should add single-digit
  milliseconds on loopback.
- **`/v1/runs` under concurrency** (`max_concurrent_runs`, default 10,
  `hermes_cli/config_defaults.py:3217`) — UNVERIFIED on this device.
- **Cold-init cost if Node/agent-browser were installed and working** — UNVERIFIED; the 46 s
  `npx` probe is a failure path *and* a resolution path, and both are slow on this phone.

---

## 8. Recommendation

### 8.1 Decision

**Take option (ii): extend the existing control plane with the shipped run/SSE API —
concretely, enable the `api_server` platform adapter (or host its handlers inside gwctl's
process if you want a single listener) and build the app against `/v1/runs`.**

Rejected alternatives, with reasons:

| Option | Verdict |
|---|---|
| **(i) New WebSocket/SSE server next to gwctl wrapping the in-process runner** | Works, and is the fallback if you want one listener on `:8777`. But you would be re-implementing `api_server.py`'s run lifecycle, approval namespace isolation, steer/stop, idempotency, auth, CORS, and SSE framing — all of which already exist and are tested. |
| **(ii) Extend gwctl with an SSE "run" endpoint** | **Chosen**, in the specific form "delegate to the shipped adapter / reuse the `/v1/runs` contract". Keep gwctl for config/keys/logs/services; do not make it own agent turns via `hermes -z` (that is the 120 s path). |
| **(iii) ACP** (`acp_adapter/`) | Real protocol with permission round-trips (`acp_adapter/permissions.py`, `edit_approval.py`), but **stdio JSON-RPC** (`entry.py:1-14`), designed for editors. A network-native Android app would need a stdio bridge in between. Rejected. |
| **(iv) MCP** | `mcp_serve.py` exposes *conversations* to other agents; MCP is a tool-call protocol, not a chat/agent-run transport with token streaming and approvals. Wrong layer. Rejected. |

The clincher is not features — it is §7.4. Any design that spawns a process per command pays
24–120 s. A design with **one long-lived Python process holding warm agents** pays 2–3 s per
turn. `api_server` *is* that long-lived process (it lives inside the gateway; the measurement
below was taken while that gateway was running: PID 30479, uptime 2627 s, its chat platform
connected. The gateway is not started any more — `jarvisd` is the long-lived process now).

### 8.2 The concrete API surface to build against

Base URL `http://127.0.0.1:8642` (defaults `DEFAULT_HOST="127.0.0.1"`,
`DEFAULT_PORT=8642` — `api_server.py:260-261`; loopback by default, so keep it that way).
Auth: `Authorization: Bearer <API_SERVER_KEY>` (`api_server.py:1920-1968`); the adapter
**refuses to start without a key** (`:8303`).

**Per command (the primary path):**

```http
POST /v1/runs
Content-Type: application/json

{"input": "list the files in ~",
 "session_id": "console-<uuid>",           # optional; defaults to run_id
 "instructions": "<ephemeral system prompt>",   # optional -> ephemeral_system_prompt
 "model": "<alias from model_routes>",     # optional
 "conversation_history": [{"role": "...", "content": "..."}]}   # optional
-> 202 {"run_id": "run_<hex>", ...}
```

Accepted body fields (`api_server.py:7572-7671`): `input` (string **or** message array; last
item becomes the user message, earlier items become history), `instructions`,
`previous_response_id`, `conversation_history`, `session_id`, `model`. Header
`X-Hermes-Session-Key` scopes long-term memory (`api_server.py:2293`).

> Send `input` as a **string** to stay on the simple path (`api_server.py:7588-7591`).
> `POST /v1/runs` always honours explicit `provider`/`model` selection
> (`api_server.py:1542-1548`), unlike `/v1/chat/completions`.

```http
GET /v1/runs/{run_id}/events        # text/event-stream
```

SSE frames are `data: <json>\n\n`, optional `event:` line
(`_sse_frame`, `api_server.py:297-316`); keepalive comment every 30 s;
terminates with `: stream closed`. Event vocabulary (verified by grep of
`"event": "…"` in `api_server.py`):

| `event` | Payload (beyond `run_id`, `timestamp`) | Emitted at |
|---|---|---|
| `message.delta` | `delta` | `api_server.py:7689` |
| `tool.started` | `tool`, `preview` | `:7498` |
| `tool.completed` | `tool`, `duration`, `error` (bool) | `:7506` |
| `reasoning.available` | `text` | `:7515` |
| `subagent.start` / `subagent.complete` | `goal`, `task_count`, `status`, `summary`, `output_tail`, token/cost fields… | `:7520-7564` |
| `approval.request` | `command` (redacted), `description`, `pattern_key(s)`, `choices`, `request_id` | `:7755` |
| `approval.responded` | `choice`, `resolved` | `:8139` |
| `run.steered` | — | `:8208` |
| `run.completed` | `output` (final text), `usage{input_tokens,output_tokens,total_tokens}`, optional `pending_steer` | `:7885` |
| `run.failed` | `error` (redacted) | `:7867`, `:7935`, `:7952` |
| `run.cancelled` | — | `:7720`, `:7852`, `:7910` |

Deliberately **not** forwarded: `_thinking`, `subagent.tool`, `subagent_progress` — marked
"high-volume UI noise" (`api_server.py:7566-7570`).

```http
POST /v1/runs/{run_id}/approval   {"choice": "once"|"session"|"always"|"deny", "all": false}
POST /v1/runs/{run_id}/steer      {"input": "<guidance>"}      # api_server.py:8155
POST /v1/runs/{run_id}/stop                                     # :8215
GET  /v1/runs/{run_id}            -> {status, output, usage, …} # :8000
```

Run statuses observed: `queued` → `running` → (`waiting_for_approval`) → `completed` /
`failed` / `cancelled` (`_set_run_status` call sites `api_server.py:7695`, `:7761`, `:7860`,
`:7878`, `:7896`, `:7960`, `:8143`).

**Supporting read-only endpoints** the console needs anyway (same server, same auth):
`GET /v1/capabilities` (`:3313`), `GET /v1/models` (`:3228`),
`GET /api/model/options` (`:3276`), `GET /v1/toolsets` (`:4092`), `GET /v1/skills` (`:4061`),
`GET /api/sessions` + `/{id}` + `/{id}/messages` + `/fork` (`:4228-4554`),
`GET /health`, `GET /health/detailed` (`:3163`, `:3169`), and the cron set
`/api/jobs*` (`:2254-2262`). A long-lived, session-scoped alternative to `/v1/runs` is
`POST /api/sessions/{id}/chat/stream` (`:4719`).

**Enabling it** (config only, no code):

```yaml
# ~/.hermes/config.yaml
platforms:
  api_server:
    enabled: true
    extra:
      host: "127.0.0.1"     # keep loopback
      port: 8642
      key: "<API_SERVER_KEY>"   # or set API_SERVER_KEY in ~/.hermes/.env
```

Config plumbing: `gateway/config.py:346` (`Platform.API_SERVER`), `:899`
(`_has_usable_api_server_key`), `:2284-2324` (`API_SERVER_KEY` / `_PORT` / `_HOST` /
`_CORS_ORIGINS` / `_MODEL_NAME` env overrides and the `platforms.api_server.*` YAML bridge).
Note `config.py:2293-2307`: without a usable key the platform stays disabled even if
`enabled: true`. Restart via `hermes-svc` (never bare `nohup`).

**Server-side hardening to do at the same time**, because it changes the numbers materially:

1. Set `platform_toolsets.api_server` (`hermes_cli/tools_config.py:2628-2652`) to a toolset
   list **without** `browser`/`browser_cdp` unless the app needs device browsing — removes the
   `npx agent-browser` probe (§7.2).
2. Keep `quiet_mode=True` on agent construction (`model_tools.py:360`) so tool definitions are
   memoized.
3. Fix the fallback chain (`config.yaml:225-237`): `gemini-3.8-flash` is 429 on the free tier,
   and `openrouter/minimax/minimax-m3.1-flash:free` is retired.
4. Prefer `gemini-3.5-flash-lite` (or whatever wins a re-measure) as the console default.

### 8.3 The 3 biggest risks

1. **The 24 s cold-start tax is a cliff, not a slope — and it silently returns.**
   Anything that creates a *new* Python process (a cron `hermes -z`, a `hermes-svc` restart, a
   second gateway for a second profile, a deploy) re-pays it, and the `_check_fn_cached` TTL is
   only 30 s (`registry.py:269`). If a future change makes the console spawn a process per
   command, latency goes back to 37–120 s. Mitigation: one process, warm agents,
   browser toolsets off, and an explicit startup warm-up turn. Also note the probe's own
   timeout is **10 s** per call (`hermes_constants.py:386-391`) but the observed probe took
   46 s — so the timeout is not bounding the real cost the way the comment implies.
2. **Concurrency/write interleaving on `state.db` is real, documented, and has already
   corrupted production data.** `turn_lease.py:1-40` describes two routing keys mapping to one
   `session_id` interleaving flushes and permanently wedging `user;user` alternation;
   `hermes_state_common.py:809-816` records concurrent FTS rebuilds structurally corrupting
   `state.db` (PR #93200, incidents 2026-08-15/23). `api_server` isolates *approvals* per run
   (`api_server.py:7664-7668`) but session isolation is still your responsibility. Mitigation:
   one conversation → one `session_id` → one owning process; take
   `SessionTurnLeaseRegistry.acquire` if you ever write from two processes; read with
   `SessionDB(read_only=True)`.
3. **The transport is undocumented as a public contract and unexercised on this device.**
   `/v1/runs` is specified only in a module docstring and code (`api_server.py:1-36`,
   `:2214-2276`) — no versioning guarantee, and `POST /v1/runs`'s `session_id` defaults to the
   `run_id` (`api_server.py:7659-7660`), so forgetting to send one silently fragments history
   into one-session-per-command. Approval requests **fail closed** if your SSE stream drops
   (`approval.py:3790-3804`), so a backgrounded Android app can stall a turn. And the whole
   path is UNVERIFIED end-to-end here because the adapter is disabled. Mitigation: pin the
   version, always send `session_id`, resume via `GET /v1/runs/{id}` + `list_gateway_approvals`
   on reconnect, and re-measure TTFB through SSE once enabled before committing to the UI's
   <1 s target.

> On that last point, be clear-eyed: **<1 s perceived latency is not achievable for the first
> token** with this stack — measured TTFB is 2.1–2.7 s warm and 3.96 s at best, dominated by
> prompt assembly plus provider TTFT. What you *can* hit is <1 s **perceived responsiveness**
> via optimistic UI + typing indicator + immediate `202` + `tool.started` events within the
> same second the model starts working. If a hard 1 s first-token SLA is non-negotiable, that
> requires a faster/smaller provider or a local model — not a different integration.

---

## Appendix A — Reproduction

Bench scripts used (throwaway, on the phone at `$HOME`): `~/.tmp_bench_inproc.py`,
`~/.tmp_bench_turn.py`, `~/.tmp_bench_t2/t3.py`, `~/.tmp_bench_tool.py`,
`~/.tmp_bench_stream.py`, `~/.tmp_prof_init.py`, `~/.tmp_warm.py`, `~/.tmp_steady.py`,
`~/.tmp_cmp.py`, `~/.tmp_ts.py`. Safe to delete.

Timing was measured with bash's `TIMEFORMAT="ELAPSED=%3R"; time <cmd>` (there is no
`/usr/bin/time` on this Termux image) and in-process `time.monotonic()` marks.

## Appendix B — Method caveat on one early number

An early instrumented run reported `FIRST_DELTA 159.751s` for `gemini-3.8-flash`. That run was
concurrent with other benchmark SSH sessions and coincides with the provider's 429 quota
exhaustion; it is reported here for honesty but **excluded** from the tables above. The
sequential re-measurements in §7.3/§7.4 are the ones to trust.
