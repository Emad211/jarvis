# Jarvis M2 — Personas & the zero-LLM fast path

Design document, **2026-10-26**. Every claim about Hermes, `jarvisd`, the app and the device
below was read or measured on the phone today (`SM-A536E`, Android 16 / SDK 36, One UI 8,
build `BP2A.250605.031.A3.A536EXXSMGZE1`). Anything I could not verify is marked
**UNVERIFIED**, and Appendix B lists them together.

Path conventions used in citations:

| Alias | Real path |
|---|---|
| `<APP>` | `/data/data/com.termux/files/usr/lib/hermes-agent/app` (Hermes Agent v0.20.6) |
| `<JD>` | `phone/.local/bin/jarvisd.py` (repo mirror). The live `~/.local/bin/jarvisd.py` on the phone is newer — it has `/fast` + `/media` + `chips`; those cites are written `jarvisd.py:NNN` |
| `<BIN>` | `~/.local/bin/` on the phone (the `phone-*` helpers + `rish`) |
| `<SKILLS>` | `~/.hermes/skills/` on the phone (18 categories, 89 `SKILL.md` files — counted today) |

`AIAgent(...)` is constructed at `<JD>:285-303`; its real body is `<APP>/agent/agent_init.py:584`.
The two features below are additive: no existing endpoint changes shape, and the M1 acceptance
test in `CLAUDE.md` (§"Accepting a change to the console") must still pass afterwards.

---

## 0. Four facts that decide both designs

**(0.1) Hermes' own prompt is built once per session and frozen; the daemon's string is appended
at every API call.** The system prompt is persisted per `session_id` in `state.db` and restored
verbatim on later turns — `<APP>/agent/conversation_loop.py:994-996` (*"Continuing session —
reuse the exact system prompt from the previous turn so the Anthropic cache prefix matches"*) —
and rebuilt only when the runtime identity goes **stale**, i.e. a different model/provider
(`<APP>/agent/conversation_loop.py:1019`, `stored_state = "stale_runtime"`). The
`ephemeral_system_prompt` string is deliberately **kept out of** that prompt
(`<APP>/agent/system_prompt.py:778-779`) and concatenated onto it at call time:
`effective = (effective + "\n\n" + agent.ephemeral_system_prompt).strip()`
(`<APP>/agent/conversation_loop.py:2390-2391`; the same two lines exist at
`<APP>/agent/chat_completion_helpers.py:2985-2986` and in the failover re-sync at
`<APP>/agent/conversation_loop.py:1567-1568`).

⇒ **Persona text rides in `ephemeral_system_prompt`. It ADDS to Hermes' prompt; it never
replaces it.** Everything in Part A follows from that one line.

**(0.2) The current daemon loses conversation history between turns.** `run_turn` calls
`agent.run_conversation(user_message=text, task_id=…)` with no `conversation_history`
(`<JD>:397`; identical in the live file on the phone, `~/.local/bin/jarvisd.py:413`), and the turn
loop starts from what the caller passed —
`messages = list(conversation_history) if conversation_history else []`
(`<APP>/agent/turn_context.py:655`). Nothing reloads the DB for this path (the only automatic
reload is the turn-lease/compaction path, `<APP>/run_agent.py:8740-8752`). The gateway — which
also caches agents across turns — always passes history, which is why it works there
(`<APP>/agent/conversation_loop.py:1878` comment). The console therefore answers each message
with only that message in context (plus Hermes' memory files). Part A's "keep history when
switching persona" is really "start passing history", and the sanctioned API already exists:
`SessionDB.get_messages_as_conversation(session_id, repair_alternation=True,
include_row_ids=True)` (`<APP>/hermes_state.py:11801-11807`, call pattern at
`<APP>/run_agent.py:8748-8752`).

**(0.3) Device-action lanes differ by ~30×.** Measured on the phone today (see Appendix A):

| Lane | What it is | p50 per call | Samples |
|---|---|---|---|
| **adb** | `adb -s 127.0.0.1:5555 shell …` (termux-adb 1.0.41/37.0.0p1, uid 2000 `shell`) | **96–166 ms** | 96, 109, 129, 131, 134, 136, 143, 156, 158, 162, 164 ms |
| **in-process** | `os.statvfs`, `/proc/uptime`, `time.time()`, `ZoneInfo` | **<5 ms** | — |
| **rish** | Shizuku `rish -c …` (uid 2000, same privileges) | **2.72–2.99 s** | 2.723, 2.941, 2.949, 2.957, 2.993 s |
| **termux-api** | `termux-*` (Termux:API app round-trip) | **2.70–2.94 s** | 2.703, 2.740, 2.747, 2.748, 2.762, 2.841, 2.937 s |

The last two are *fixed* overhead, not cold-start: three consecutive `termux-battery-status`
calls, 1 s apart, all cost ≈2.7 s. So the <300 ms fast path is **an adb path or nothing**;
`rish` and `termux-api` are the slow lane and must be labelled as such in the API.

**(0.4) Part B is already half-built on the phone — this document specifies the contract, not a
greenfield build.** At 03:03 today `~/.local/bin/jarvis_fast.py` (24 212 B) landed with 16 intents,
the same adb→rish ladder I measured (`_adb_ready`/`sh`, 30 s health cache, 600 s package cache), a
`CHIPS` list and the deterministic Persian router `route()`; `jarvisd.py` already serves
`POST /fast` (`:537-546`), `GET /media/<name>` (`:548-556`), `chips` inside `/catalog` (`:533`) and
routes chat turns through `route()` into `run_fast_turn()` (`:784-821`), which emits
`turn.start → tool.start → tool.end → text.delta → turn.end` with `mode:"fast"`. So §B is written as
*the contract that code must satisfy plus the exact gaps* (§B3.1, and the three wire defects in
§B2.2). `CLAUDE.md` now records ~92 ms per intent and 613 ms for «تلگرام رو باز کن» end to end; my
own adb samples in (0.3) agree.

---

# PART A — Personas

## A1. What a persona is

A persona is a **prompt + defaults bundle**, not a Hermes profile. It has no state of its own;
sessions have state, personas only decide how the agent thinks and what it can touch.

| Field | Type | Required | Meaning |
|---|---|---|---|
| `id` | `[a-z0-9_-]{1,24}` | yes | directory name, URL segment, session binding key |
| `label` | str ≤24 | yes | Persian name for the chip and the transcript divider |
| `emoji` | 1 grapheme | yes | chip glyph |
| `color` | `#RRGGBB` from the persona-only set | yes | chip tint (see A2 note) |
| `soul_file` | relative name, default `SOUL.md` | no | the persona's voice: 6–12 lines, Persian |
| `model` | str, default `""` → config default | yes | passed as `model=` (`<APP>/agent/agent_init.py:545`) |
| `reasoning` | one of `none,minimal,low,medium,high,xhigh,max,ultra` | yes | → `reasoning_config={"effort": …}` (`<APP>/agent/agent_init.py:587,648,960`; valid set at `<APP>/agent/reasoning_effort.py:51`) |
| `toolsets` | list of names from `<APP>/toolsets.py` `TOOLSETS` (58 keys, enumerated today) | yes | → `enabled_toolsets=` (`<APP>/agent/agent_init.py:549`) |
| `skills` | list of skill names | no | **must-call** directives appended to the prompt (A3) |
| `skills_preload` | list of skill names | no | full `SKILL.md` text inlined — hard budget: ≤2 items, ≤4 KB each |
| `suppress_soul` | bool, default `false` | no | drop the owner's global `~/.hermes/SOUL.md` for this persona (A4.3) |
| `banner` | str ≤60 | no | one line the app shows when a session opens on this persona |
| `created`, `updated` | epoch float, written by the daemon | no | bookkeeping |

Everything maps to a real `AIAgent` kwarg; `AIAgent` forwards unknown kwargs to
`init_agent(...)`, which is fully explicit, so an unknown field would raise `TypeError` — a
persona file that names a nonexistent option must therefore be rejected at load time, not at
turn time.

## A2. Where personas live

```
~/.hermes/jarvis/
  personas/
    _index.json                 {"default":"jarvis","order":["jarvis","kargah","pazhuhesh","daftar"]}
    jarvis/
      SOUL.md                   6–12 lines, Persian
      persona.json              the table in A1
    kargah/{SOUL.md,persona.json}
    pazhuhesh/{SOUL.md,persona.json}
    daftar/{SOUL.md,persona.json}
  cache/
    packages.json               open_app index (B3)
    fast_log.jsonl              one line per /fast call, no secrets
  shots/f_<10 hex>.png          screenshots served at /media/<name>
  sessions/<sid>.json           unchanged (<JD>:167-182), + "persona" field
```

Rules:

- **The persona dir is the source of truth.** `persona.json` may be edited by hand or by the
  agent; the daemon never writes persona files except `/persona/<id>` field updates.
- `SOUL.md` is read as UTF-8, capped at 4 KB (≈700 Persian words); longer is truncated with a
  warning, because it competes for tokens with Hermes' own prompt.
- `color` must come from the persona set `{#7FA6FF, #5EC8C8, #4ADE80, #B4A0FF}` — the app reserves
  `AMBER #FBBF24` and `RED #F87171` for status (`Ui.java:31-34`, `docs/design-system.md:22`).
- `_index.json` is optional: missing → alphabetical order, default = `jarvis` if present else
  the first persona. `order` may list only some ids; the rest sort after them.

## A3. How the daemon loads them

1. **Scan** once at startup and on `POST /personas/reload`: `glob(personas/*/persona.json)`.
2. **Validate** per file, fail-soft: id matches its directory, required fields present,
   `reasoning` in the valid set, `toolsets ⊆ TOOLSETS` (unknown names are dropped and reported,
   never fatal), `skills` resolved by scanning `<SKILLS>*/ * /SKILL.md` frontmatter `name:`
   (89 today), `color` in the palette. A rejected persona is **excluded** from the picker and
   reported in `GET /personas` as `{"error": "…"}`, so a typo can never take the console down.
3. **Compose** the prompt string once per persona and cache it with
   `sha1(soul_text + toolsets + skills + brief)`:

   ```
   ephemeral_system_prompt =
       JARVIS_BRIEF                                   # <JD>:72-78, the console invariants
     + "\n\n" + <SOUL.md>                             # the persona's voice and defaults
     + "\n\n" + <must-call skills directive>          # one line per entry in skills[]
     + "\n\n" + <preloaded SKILL.md text>             # only from skills_preload[]
   ```

   `JARVIS_BRIEF` stays first and unchanged: "never invent results", "never print API keys" are
   invariants, not personality.
4. **Warm** the default persona at startup (`warm_up`, `<JD>:713-725`) and any persona the app
   binds within the first few seconds. A rebuilt agent costs **0.40–0.41 s warm** and a fresh
   process pays **24.0 s cold** once (`docs/hermes-embedding-api.md:693-697`), so the daemon must
   still hand every persona's agent to a *single* long-lived process — never one process per persona.
5. **Reload on drift**: before each turn, if the persona's `mtime` or hash changed, recompose and
   reassign `agent.ephemeral_system_prompt` before calling `run_conversation` (see A4.4 for why
   this reassignment is not optional).

## A4. How the persona prompt composes with Hermes' own prompt

### A4.1 What Hermes puts in the stable prompt

With no kwargs set (what `<JD>:285-303` does today), `skip_context_files=False` and
`load_soul_identity=False` (`<APP>/agent/agent_init.py:600-601`), so the gate
`if agent.load_soul_identity or not agent.skip_context_files:` (`<APP>/agent/system_prompt.py:382`)
passes and `load_soul_md()` injects **`~/.hermes/SOUL.md` as the primary identity**
(`<APP>/agent/system_prompt.py:383-390`), falling back to `DEFAULT_AGENT_IDENTITY` only if it is
missing. On this phone that file is 42 lines / 2784 bytes, and it opens with
*"You are Hermes (in Persian: «هرمس»), the personal assistant of Emad Karimi"* — i.e. **the
global identity is present in every Jarvis turn today**, followed by the skills index
(`<APP>/agent/system_prompt.py:524-546`), context files (`:783+`) and tool guidance.

### A4.2 The answer: it ADDS

`ephemeral_system_prompt` is **appended after** that stable prompt, per API call, and is not
persisted into the stored prompt (`<APP>/agent/system_prompt.py:778-779`,
`<APP>/agent/conversation_loop.py:2390-2391`). So a persona is a *suffix that must win by
instruction*, not a replacement. Two consequences the design depends on:

- Every persona `SOUL.md` **must name the global rules it overrides**. The global SOUL says
  *"Short, direct, practical… no lists of possibilities"*; the research persona wants 15 lines and
  3–5 bullets, so its SOUL says so explicitly. Do not rely on position alone.
- Persona identity is therefore **switchable at any turn boundary** — which is exactly the
  behaviour A5 needs.

### A4.3 When a persona needs a clean identity

`suppress_soul: true` makes the daemon pass `skip_context_files=True` + `load_soul_identity=False`
(`<APP>/agent/agent_init.py:600-601`): the gate at `<APP>/agent/system_prompt.py:382` then fails
and Hermes uses `DEFAULT_AGENT_IDENTITY`. Trade-off to state in the persona file:
`skip_context_files=True` **also drops `AGENTS.md`/`CLAUDE.md`/cwd context files**
(`<APP>/agent/system_prompt.py:783`), so it is right for the phone/research/office personas and
wrong for the coding persona (which wants project instructions). Default `false`.

A third route exists and is deliberately **not** used in M2: pointing `HERMES_HOME` at
`~/.hermes/profiles/<id>` so Hermes reads that profile's own `SOUL.md`, skills and memory. The
mechanism is real — `set_hermes_home_override()`/`reset_hermes_home_override()` are a ContextVar
API (`<APP>/hermes_constants.py:17-18,30-46`), profile mode is `<root>/profiles/<name>`
(`<APP>/hermes_constants.py:193`), the CLI exists (`hermes profile {list,use,create,…}`, verified
today) — but `~/.hermes/profiles/` **does not exist on this phone**, resolution prefers the
`state.db`-derived home on threads that lost the ContextVar
(`<APP>/agent/system_prompt.py:276-296`, and *"ContextVars don't propagate into
`threading.Thread`"* — which is how `<JD>:560-566` runs every turn), and a profile home that does
not exist raises `FileNotFoundError` (`<APP>/hermes_cli/config.py:963-971`). That is a separate
feature (per-persona memory and skills), not a prerequisite for personas. Out of scope here;
named as M2.5 if the owner wants personas with independent memories.

### A4.4 Two traps, both verified in source

1. **Never use `run_conversation(system_message=…)` for personas.** It is accepted
   (`<APP>/agent/conversation_loop.py:1826`, doc at `:1841` *"overrides ephemeral_system_prompt if
   provided"*) but it lands in the **context tier of the cached, persisted prompt**
   (`<APP>/agent/system_prompt.py:781-782`), which is built once per session
   (`<APP>/agent/conversation_loop.py:2385-2389`) — so it would be frozen for the life of the
   session and could not be switched. Reserve it for one-shot injections.
2. **Re-assign the persona string every turn.** If any provider error text contains `ascii`
   (an `'ascii' codec` encoding failure), Hermes sanitizes the prompt in place and **permanently
   overwrites** `agent.ephemeral_system_prompt` with the non-ASCII-stripped version
   (`<APP>/agent/conversation_loop.py:4549-4553`, trigger at `:4471-4472`, i.e. an
   `'ascii' codec can't encode` error). Persian persona text would be wiped for the rest of that
   agent's life. The daemon already touches the agent before each turn
   (`<JD>:392-396`), so setting `agent.ephemeral_system_prompt = persona.prompt` there costs
   nothing and closes the hole. **Do this.**

## A5. Session binding and switching

`Session` gains one field, persisted next to `model`/`reasoning` (`<JD>:173-176`):

```json
{"id":"9f2c…","title":"…","persona":"kargah","model":"gemini-3.5-flash",
 "reasoning":"low","toolsets":["terminal","file"],"turns":3,"messages":[…]}
```

- `POST /session/new {persona:"kargah"}` binds at creation. Unknown persona → `400`.
- A session with no `persona` (every session that exists today) binds to the default persona on
  load, and writes it back on the next save. No migration step.
- The agent cache key becomes `(persona_id, prompt_hash, model, reasoning, tuple(toolsets))`
  instead of today's `(model, reasoning)` (`<JD>:383-387`), so an edited `SOUL.md` also forces a
  rebuild on the next turn.

### A5.1 Switching mid-session — the semantics

| What the switch changes | Effect | Cost |
|---|---|---|
| voice / tone / overrides (in the SOUL suffix) | applies to the **next** turn, same session, same history | 0 |
| `reasoning` | `agent = None` → rebuild; history kept | 0.4 s warm |
| `model` (differs) | rebuild **and** Hermes rebuilds the stored system prompt | 0.4 s + one prefix-cache miss |
| `toolsets` (differs) | rebuild (tool definitions are frozen per agent) | 0.4 s + tool-definition cost |
| `suppress_soul` (differs) | rebuild; the stable prompt is rebuilt only if the model also differs — otherwise the stale identity survives until a new session | — |
| `skills_preload` (differs) | rebuild + recompose | 0.4 s |

Two modes, chosen by the client and echoed in the response:

- **`mode:"swap"` (default)** — same `session_id`, history preserved, transcript gets a divider
  card. Allowed when the session is idle; returns `409 busy` while a turn is running
  (same guard as `<JD>:555-556`).
- **`mode:"new"`** — create a fresh session bound to the new persona and return its id. This is
  the honest choice when `suppress_soul` or the model changes, because the **stable** prompt is
  frozen per `session_id` (`<APP>/agent/conversation_loop.py:994-996`).

**Does the prompt get replaced?** No — see A4.2. Only the appended suffix changes; Hermes' stable
prompt (global SOUL + skills index + context files) is whatever that `session_id` stored on its
first turn. Document this in the UI: a divider reading *«شخصیت: کارگاه»* plus the persona's
`banner`, not a claim that the agent was "reset".

**History across a swap** requires (0.2) to be fixed in the same change: load
`get_messages_as_conversation(sid, repair_alternation=True, include_row_ids=True)` and pass it to
`run_conversation(conversation_history=…)` on **every** turn — the gateway pattern
(`<APP>/run_agent.py:8748-8752`). A rebuilt agent then continues the conversation exactly where
the previous one stopped. Accept the risk that this may duplicate rows in `messages`; assert it in
the acceptance test (Appendix C, C5) — **UNVERIFIED** whether `_persist_session` appends deltas
only (it is the pattern the gateway uses, so it should).

## A6. Endpoints

Same style as the existing surface (`<JD>:520-611`): plain JSON dicts, `{"ok": true, …}` on
success, `JSONResponse({"error": "no such session"}, 404)` on a miss.

```
GET  /personas                       -> {personas:[…], default:"jarvis", errors:[…]}
GET  /persona/<id>                   -> {id,label,emoji,color,model,reasoning,toolsets,
                                         skills,skills_preload,suppress_soul,banner,
                                         soul:"<text>", soul_sha1, updated, prompt_chars}
POST /personas/reload                -> {ok:true, count:N, errors:[…]}
POST /persona/new    {…fields…}      -> {ok:true, id}
POST /persona/<id>   {…any fields…}  -> {ok:true, persona:{…}}      # partial update
POST /persona/<id>/delete            -> {ok:true}                   # 409 if a live session uses it
POST /session/new    {persona?,title?}       -> {session, persona}
POST /session/<sid>/persona {id, mode?}      -> {ok, session, persona, mode, new_session?}
GET  /session/<sid>                  -> … + {persona:"kargah", toolsets:[…]}
GET  /catalog                        -> … + personas:[{id,label,emoji,color,model,reasoning}]
```

`GET /catalog` already ships a placeholder (`<JD>:517` in the mirror, `jarvisd.py:534` live) and `fast.CHIPS` at `:533`; the `personas` key becomes the real list so the app's picker has one round trip at startup. Example:

```json
GET /personas
{"personas":[
  {"id":"jarvis","label":"جارویس","emoji":"⚡","color":"#7FA6FF",
   "model":"gemini-3.5-flash-lite","reasoning":"none",
   "toolsets":["terminal","vision","clarify","skills"],"skills":["android-device-control"]},
  {"id":"kargah","label":"کارگاه","emoji":"🛠","color":"#4ADE80", … }],
 "default":"jarvis","errors":[]}
```

```json
POST /session/9f2c1a8b3d4e/persona   {"id":"daftar","mode":"swap"}
{"ok":true,"session":"9f2c1a8b3d4e","persona":"daftar","mode":"swap","rebuilt":true}
```

Emitting `status {kind:"persona", text:"<label>"}` into the session stream on any successful bind
or swap gives the app a divider for free — it already renders `status` (`MainActivity.java:364-378`).

## A7. Four starter personas

Genuinely different in defaults, not cosmetics: different models (speed vs thinking), different
reasoning efforts, disjoint toolsets, and disjoint tones. Model choices follow the measured facts:
`gemini-3.5-flash-lite` + `reasoning:none` ≈ 1.8 s to first token, `gemini-3.5-flash` ≈ +0.6 s,
and `gemini-3.8-flash` is 429 on the free tier (`<JD>:88-92`, `:697-704`;
`docs/hermes-embedding-api.md:671-686`).

### ⚡ `jarvis` — «جارویس» — phone operator (default)

- `model: gemini-3.5-flash-lite`, `reasoning: none` — this persona exists for latency.
- `toolsets: [terminal, vision, clarify, skills]` — no `web`, no `browser`: those toolsets pay a
  ~20 s `npx agent-browser` probe at agent build (`docs/hermes-embedding-api.md:628-642`).
- `skills: [android-device-control]` (must-call), `suppress_soul: false`.
- Tone: act, report one line, never claim success it did not verify.

```text
تو «جارویس» هستی؛ دستیارِ گوشیِ عماد روی اندروید ۱۶.
جواب حداکثر دو خط؛ کار را انجام بده، دستور نده.
برای کارهای تکراری گوشی از مسیر سریع استفاده کن، نه از مدل.
بعد از هر اقدام یک جمله بگو چه شد؛ اگر خطا داد، همان خطا را نقل کن.
اگر ابزار خروجی خالی داد، «انجام شد» نگو؛ بگو «تأیید نشد».
قبل از پاک کردن، ارسال، خرید یا هر کار برگشت‌ناپذیر اجازه بگیر و دلیلش را بگو.
فارسی را با کلیپ‌بورد تایپ کن نه با input text؛ بعد با dump صفحه بررسی کن.
شماره، رمز و کلید API را هیچ‌وقت در چت چاپ نکن.
```

### 🛠 `kargah` — «کارگاه» — coding

- `model: gemini-3.5-flash`, `reasoning: low` — correctness over the last 0.6 s.
- `toolsets: [terminal, file, code_execution, todo, memory, clarify, skills, delegation]`,
  `suppress_soul: false` (it wants project `CLAUDE.md`/`AGENTS.md` context).
- `skills: [plan, systematic-debugging, test-driven-development]` (must-call, pointing at
  `<SKILLS>/software-development/*`, 8.9/14.1/10.3 KB — directives only, not preloaded).
- Tone: minimal diffs, evidence, no praise, English for code.

```text
تو «کارگاه» هستی: جفت‌برنامه‌نویس عماد روی همین گوشی.
فارسی و کوتاه جواب بده؛ کد، مسیر فایل و دستور را انگلیسی و بدون ترجمه بنویس.
اول کد را بخوان، بعد تغییر بده؛ هرگز حدس نزن فایل چه شکلی است.
کمترین تغییر لازم را بده؛ بازنویسی بی‌دلیل ممنوع.
هر ادعا با خروجی واقعی ابزار پشتیبانی شود؛ اگر تست نگرفتی، بگو نگرفتی.
خطا را کامل بخوان و علت ریشه‌ای را پیدا کن، نه علامت را.
برای کارهای چندمرحله‌ای یک todo بساز و همان‌جا به‌روزش کن.
تحسین و مقدمه ننویس؛ مستقیم برو سر تفاوت کد.
اگر تصمیم معماری لازم است، دو گزینه با هزینه و فایده بده و یکی را توصیه کن.
```

### 🔎 `pazhuhesh` — «پژوهش» — research

- `model: gemini-3.5-flash`, `reasoning: medium` (this is the one persona where thinking is worth
  2–4 s).
- `toolsets: [web, session_search, memory, file, todo, clarify, skills]` — `browser` deliberately
  excluded; add it only if a task needs clicking, and expect the first turn after that to pay the
  probe.
- `skills: []`, `suppress_soul: true` — the global "keep it short, no lists" rule fights research
  output, so this persona runs without it and states the override itself.
- Tone: sourced, explicitly separates "the source says" from "I conclude", 15-line ceiling.

```text
تو «پژوهش» هستی: پژوهشگر عماد؛ فارسی جواب می‌دهی و منابع انگلیسی می‌مانند.
هر ادعا یا لینک دارد یا برچسب «تأیید نشده» می‌خورد.
عدد، تاریخ و نقل‌قول فقط از منبع اصلی، همراه با لینک همان منبع.
پاسخ با ۳ تا ۵ نکته شروع می‌شود و بعد جزئیات؛ تا ۱۵ خط مجاز است.
تفاوت «منبع می‌گوید» و «من نتیجه می‌گیرم» را صریح جدا کن.
اگر شبکه یا VPN اجازه نداد، مانع را گزارش کن، نه نتیجه‌ی ساختگی.
در موضوع‌های حقوقی، پزشکی و مالی ریسک و عدم‌قطعیت را ذکر کن.
جست‌وجو را انگلیسی انجام بده مگر موضوع ایران‌محور باشد.
```

### 📋 `daftar` — «دفتر» — business & admin

- `model: gemini-3.5-flash-lite`, `reasoning: low` — fast drafts, it lives in short bursts.
- `toolsets: [file, todo, memory, cronjob, web, clarify, skills]` — `cronjob` is the differentiator:
  reminders and recurring admin chores.
- `skills: [weekly-review-planning, document-to-action-items, scheduling-reminders]`;
  `skills_preload: [scheduling-reminders]` (1.4 KB, the only one small enough to inline).
- Tone: structured (وضعیت / عدد / اقدام بعدی / مهلت), Jalali dates, Toman, drafts rather than advice.

```text
تو «دفتر» هستی: مسئول کارهای اداری و مالی عماد.
پاسخ ساخت‌یافته و کوتاه: وضعیت، عدد، اقدام بعدی، مهلت.
تاریخ‌ها شمسی و مبلغ‌ها به تومان؛ اگر تبدیل لازم است نرخ و منبعش را بنویس.
هر سند (فاکتور، پیش‌فاکتور، قرارداد، ایمیل) را آماده کن، نه فقط توضیح بده.
قبل از ارسال یا ثبت نهایی یک بار متن را برای تأیید نشان بده.
هیچ عددی را حدس نزن؛ اگر داده نداری بگو کدام فیلد کم است.
کارهای باز را در todo نگه دار و در پایان هر گفتگو یک جمع‌بندی یک‌خطی بده.
لحن رسمی و بدون تعارف؛ فقط کاری که لازم است.
```

## A8. Persona failure modes

| Failure | Behaviour | Why |
|---|---|---|
| `persona.json` invalid JSON | persona skipped, listed in `/personas.errors` | loader must never crash the daemon |
| unknown toolset name | dropped + warning; rest of the list applies | `enabled_toolsets` unknown key → no tools silently, worse than a warning |
| unknown skill name | dropped + warning | 89 skills, names drift |
| `SOUL.md` missing | persona loads with `JARVIS_BRIEF` only | still usable |
| `SOUL.md` >4 KB | truncated, warning | competes with Hermes' prompt |
| persona deleted while a session uses it | session falls back to default, emits `status {kind:"persona_missing"}` | never 500 a chat turn |
| swap while a turn is running | `409 {"ok":false,"error":"busy"}` | matches `<JD>:555-556` |
| swap to a model that 429s | turn fails as it does today (`<JD>:402-403`); persona unchanged | quota is not a persona bug |

---

# PART B — Fast path (zero-LLM device actions)

Goal: a fixed intent vocabulary that executes in **<300 ms p50 without touching the model**, and
reports back so the app can render the outcome as an ordinary tool card.

## B1. Lane policy — the single most important rule

Measured (0.3): adb ≈ 96–166 ms per round trip; rish ≈ 2.9 s; termux-api ≈ 2.7 s.

```
every intent = { lanes: [primary, fallback…], budget_ms }
primary is adb unless adb provably cannot do it
rish and termux-api are fallback lanes and are declared in the response ("lane" + "slow":true)
an intent whose only lane is slow is NOT a fast-path intent (B3 marks these)
```

adb specifics verified today:

- `adb` is termux-adb 1.0.41 at `$PREFIX/bin/adb`; the device is attached as
  `127.0.0.1:5555` (wireless debugging; `hermes-svc:148-151` re-arms `service.adb.tcp.port 5555`,
  restarts `adbd` and reconnects at boot).
- **Two devices are attached right now** — `127.0.0.1:5555` and a stale `emulator-5554`. Every
  call must pass `-s 127.0.0.1:5555`; without it adb refuses with "more than one device". A wrong
  serial fails in **63 ms** with exit 1 and `adb: device '…' not found` on stderr — cheap probe.
- `adb shell` runs as **uid 2000(shell)**, groups include `1004(input)`, `1015(sdcard_rw)`,
  `3003(inet)`. `/system/bin/{input,screencap,dumpsys,svc,cmd,settings,am,monkey,content}` are on
  its PATH — but **not** on Termux's own PATH (`command -v input` → missing), which is why the
  Termux wrappers are useless and rish exists.
- The phone is **not rooted**; `su` is not used anywhere in this design.

Process rules for the daemon:

1. `subprocess.run(["adb","-s",SERIAL,"shell",cmd], timeout=…)` — argument list, no shell; the
   remote side is a single `/system/bin/sh -c` string built by us.
2. **One round trip per intent.** Batch reads inside one shell invocation: two `getprop`s in one
   call cost 136 ms vs 2×96 ms.
3. Timeouts: adb 2.5 s, rish 8 s, termux-api 10 s. Never let an intent outlive the HTTP request;
   run intents in `asyncio.to_thread`/executor so uvicorn's loop stays free (the turn worker
   already blocks threads, `<JD>:560-566`).
4. Serialize per lane with a `threading.Lock` (timeout 0.5 s) → `409 busy`. Concurrent taps on one
   device interleave unpredictably.
5. Health probe cached 10 s: `adb -s SERIAL shell true` (96 ms). `/health` gains
   `fast:{adb:true, adb_ms:96, rish:true, termux_api:true, serial:"127.0.0.1:5555"}` so the app can
   grey out chips instead of failing them.

## B2. Wire shape

### B2.1 `POST /fast`

Live today: `jarvisd.py:537-546` takes `{intent, args}`, runs `fast.run(intent, args)` in an
executor and returns its dict verbatim — so the *shape below is a superset*: `ok`, `intent`, `ms`,
`detail`, `media` already exist; `verified`, `lane`, `slow`, `error`, `seq`, `card` are the additions
(delta 1 in §B3.1). Note the live `detail` is a Persian sentence, not an object; that is the thing
to change first, because `verified` cannot live in a string.

```json
{"intent":"tap","args":{"x":540,"y":1200},"session":"9f2c1a8b3d4e","emit":true,"verify":false}
```

Response (200 — a *dispatched* intent always returns 200; only programmer errors are 4xx):

```json
{"ok":true,"verified":false,"intent":"tap","ms":168,"lane":"adb","slow":false,
 "detail":{"x":540,"y":1200},"media":null,"error":null,
 "seq":118,"card":"fast_7c1ab2d4"}
```

Failure:

```json
{"ok":false,"verified":false,"intent":"open_app","ms":247,"lane":"adb","slow":false,
 "detail":{"query":"فلان‌برنامه","stdout":"No activity found"},
 "error":{"code":"no_activity","message":"No activity found",
          "hint":"هیچ اپلیکیشنی با این نام پیدا نشد."},
 "seq":119,"card":"fast_7c1ab2d5"}
```

- `ok` = the command was dispatched and its own output looks like success.
- `verified` = we *read back* device state proving the effect (B3 column "verify").
  **A card must never show "انجام شد" when `verified:false`** — it shows "فرستاده شد".
- `ms` = wall time inside the daemon (monotonic, includes the adb call, excludes HTTP).
- `lane` = `"adb" | "inprocess" | "rish" | "termux-api"`; `slow` = `lane != "adb" and lane !=
  "inprocess"`.
- `card`/`seq` present only when `session` was supplied and `emit` is true.
- `media`: `{"kind":"image","url":"/media/f_ab12cd34ef.png","w":1080,"h":2340,"bytes":526666,"shot_ms":487}`
  — served by a `/media/<name>` route guarded like `/audio/<name>` (`<JD>:674-681`).

Status codes: `400` unknown intent / argument validation (`error.code` = `unknown_intent`,
`bad_args`, with `error.field`), `409` `needs_confirm` or `busy`, `503` `adb_unavailable`,
`200` for everything else including device-level failures.

### B2.2 Rendering as a tool card — reuse the existing event path

When `session` is present the daemon emits the **same** events an LLM tool call would
(`<JD>:256-264`), so history, replay and reconnect all work unchanged:

```json
{"seq":118,"kind":"tool.start","ts":…,
 "data":{"id":"fast_7c1ab2d4","name":"fast.open_app","args":"{\"query\": \"تلگرام\"}"}}
{"seq":119,"kind":"tool.end","ts":…,
 "data":{"id":"fast_7c1ab2d4","name":"fast.open_app",
         "args":"{\"query\": \"تلگرام\"}",
         "result":"{\"ok\": true, \"verified\": true, \"ms\": 291, \"lane\": \"adb\"}"}}
```

Three wire details that matter, all verified in the app:

1. The app reads `ev["data"]` and injects `_seq` from `ev["seq"]` (`Api.java:214-222`) — the daemon's
   envelope already provides both (`<JD>:148-159`, `:707-710`). Nothing new to invent.
2. The app decides a card failed by string-matching the result:
   `result.contains("\"ok\": false")` (`MainActivity.java:356-358`). Build the `result` string with
   `json.dumps` defaults (what `jdump` does, `<JD>:121-122`) so the space after the colon is
   present. FastAPI's compact `{"ok":false}` would **not** match and a failure would render green.
3. **Live defect, same class.** The current `run_fast_turn()` emits
   `"result": _short(res.get("detail"))` — the *detail string*, not the verdict object
   (`jarvisd.py:813-814`). A failed fast action therefore arrives as e.g.
   `"برنامه‌ای با این نام پیدا نشد: فلان"`, which contains no `"ok": false`, and the transcript
   paints it as a **successful** card. Fix: put `jdump({ok, verified, ms, detail})` into `result`
   and keep the human sentence in `text.delta` / the reply only. Until then, a chip action that
   fails is indistinguishable from one that worked — the worst possible outcome for a feature whose
   whole promise is determinism.

## B3. Intent catalogue

Columns: **args** · **lane/command** (exact) · **p50** (measured or derived) · **verify** (how
success is proven) · **silent failure** (what the exit code does *not* tell you) · **live** (present
in `~/.local/bin/jarvis_fast.py` today, or `new`).

| intent | args | lane · command | p50 | verify | silent failure to detect | live |
|---|---|---|---|---|---|---|
| `open_app` | `{name\|query:str, verify?:bool}` | adb · `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>` → `am start -n <cmp>` (live code uses `monkey -p <pkg> -c …LAUNCHER 1`) | 109 + ~120 ms | `foreground` (164 ms) | `No activity found` **with exit 0**; `monkey` prints `No activities found` | ✅ `open_app:191` |
| `screenshot` | `{vision?:bool, question?:str}` | adb · `adb exec-out screencap -p` | **487 ms** (526 666 B) | PNG magic + size | screen off → valid but blank PNG, exit 0 | ✅ `screenshot()` |
| `tap` | `{x,y, hold?:ms}` | adb · `input tap X Y`; `hold` → `input swipe X Y X Y MS` | 156 ms | `verify:true` → `ui`/screenshot (slow) | exit 0 even for off-screen coordinates | ✅ `tap:221` |
| long-press | — folded into `tap{hold:800}` | adb · `input swipe X Y X Y MS` | 160 ms | as tap | same | ✅ `tap:229` |
| `swipe` | `{x1,y1,x2,y2,ms?:300}` or direction | adb · `input swipe X1 Y1 X2 Y2 MS` | 160 ms | as tap | same | ✅ `swipe()` |
| `key` | `{name:back\|home\|recents\|enter\|delete\|paste\|menu\|power\|notifications\|…, code?:int}` | adb · `input keyevent N` | 156 ms | `foreground` for `home` | **exit 0 for bogus codes** (verified: 9999) | ✅ `key:270` |
| `type` | `{text:str, verify?:bool}` | adb · `input text '<ascii, spaces→%s>'` | 160 ms | `verify` → `ui` (2–4 s) | non-ASCII dropped silently; `'` stripped | ✅ `type_text:285` |
| `type` (Persian) | `{text}` with any non-ASCII char | **termux-api** · `termux-clipboard-set` + `input keyevent 279` | **~2.95 s** | `ui` | denied clipboard → paste is a no-op | ✅ `type_text:294` |
| `open_url` | `{url:http(s)\|tg\|market}` | adb · `am start -a android.intent.action.VIEW -d '<url>'` | ~130 ms | stdout `Starting:` | `Error:` on stdout, exit 0 (UNVERIFIED) | ✅ `open_url()` |
| `dial` | `{number:str}` | adb · `am start -a android.intent.action.DIAL -d tel:<num>` | ~130 ms | stdout | as above | ✅ `dial()` |
| `volume` | `{action:up\|down\|mute, steps?:1..10}` | adb · `input keyevent 24`×n / `25`×n / `164` | 156 ms + 60 ms/key | read-back (slow) | **164 toggles mute**, it does not set it | ✅ `volume:325` |
| `brightness` | `{percent?:1..100, level?:0..255}` | adb · `settings put system screen_brightness_mode 0` + `settings put system screen_brightness N` | 143 ms + write | read-back `settings get system screen_brightness` | adaptive brightness resets `_mode` to 1 | ✅ `brightness:336` |
| `wifi` | `{action:on\|off, allow_offline?:bool}` | adb · `svc wifi enable\|disable` (live) or `cmd wifi set-wifi-enabled enabled\|disabled` | 129 ms + write | `settings get global wifi_on` / `cmd wifi status` | **can kill the adb lane and the VPN** | ✅ `wifi:352` |
| `battery` | `{}` | adb · `dumpsys battery` | 134 ms | n/a (it is the read) | — | ✅ `battery:363` |
| `info` | `{what:time\|storage\|uptime\|ssid}` | **in-process** preferred; live code uses adb `date`/`df`/`uptime` | live ~100–160 ms, could be **<5 ms** | n/a | — | ✅ `info:383` |
| `notifications` | `{}` (expand) | adb · `cmd statusbar expand-notifications` → fallback `input keyevent 83` | 131 ms | `dumpsys window` shade string (UNVERIFIED) | exit 0 when nothing happens | ✅ `notifications:416` |
| `clipboard` | `{text?}` (set) / read | **termux-api** · `termux-clipboard-set` / `-get` | ~2.7 s | set: read back | `cmd clipboard` → *"No shell command implementation."* | ✅ `clipboard:310` |
| `ui` | `{query?:str, tap?:bool}` | adb · `uiautomator dump` + parse | **2–4 s (slow lane)** | the dump itself | not a fast intent | ✅ `ui_dump:424` |
| `foreground` | `{}` | adb · `dumpsys window \| grep -m1 mCurrentFocus` | 164 ms | — | `Broken pipe` noise on stderr | **new** |
| `media` | `{action:play\|pause\|play-pause\|next\|previous\|stop}` | adb · `cmd media_session dispatch <action>` (pre-check `list-sessions`) | 129 + ~130 ms | list-sessions non-empty | **no session → exit 0, no output** | **new** |
| `screen_lock` | `{verify?:bool}` | adb · read `dumpsys power` → `input keyevent 223` → re-read | 90+156+90 ms | `mWakefulness=Asleep` | sleep ≠ locked (auto-lock delay, Smart Lock) | **new** |
| `flashlight` | `{on:bool}` | **termux-api** · `termux-torch on\|off` | ~2.7 s | none reliable | slow lane only | **new (slow)** |

### B3.1 Deltas the live implementation still needs

Ordered by risk, not by effort. Each line is a concrete change in `jarvis_fast.py` /
`jarvisd.py`; nothing else in this document is a code change.

1. **The verdict must be a structured object, not a sentence.** Today every function returns
   `{ok, intent, ms, detail:<persian string>}`; `_ok()` asserts success even when nothing was
   checked. Add `verified:bool`, `lane:"adb"|"rish"|"termux-api"|"inprocess"` and `slow:bool`, and
   make `detail` a dict (`{"stdout":…,"read_back":…}`). The app needs `verified` to pick between
   «انجام شد» and «فرستاده شد — تأیید نشد».
2. **`run_fast_turn` must not put the sentence in `result`** (B2.2 item 3) — a failed action
   currently renders green.
3. **Packages: pass `--user 0` and stop trusting exit codes.** `packages()` calls bare
   `pm list packages` and splits every line containing `:`. On this phone that command **failed
   once today** (`SecurityException: Shell does not have permission to access user 150`, Secure
   Folder running, exit 0, empty list) and **succeeded 25 minutes later** (553 lines) — it is
   state-dependent. `pm list packages --user 0` gave 553 / 112 (third-party) deterministically in
   both tests, 162 ms. Also filter with `line.startswith("package:")` so a stack trace can never be
   ingested as a package name.
4. **`wifi off` needs the confirm token, not an in-band flag.** `allow_offline:true` is a good
   internal guard (`wifi:356-357`) but the app must not be able to reach it by accident; use the
   two-step `409 needs_confirm` + `confirm:"<token>"` of §B5. Also verify `svc wifi` actually works
   on One UI 8 — `cmd wifi set-wifi-enabled` is the help-documented alternative.
5. **Add the four missing intents** `foreground`, `media`, `screen_lock`, `flashlight` (rows above).
   Without `foreground` no chip can honestly verify `home`/`open_app`.
6. **`info` should stop paying for adb.** `time` → `time.time()` + `ZoneInfo("Asia/Tehran")`;
   `storage` → `os.statvfs`; `uptime` → `/proc/uptime`. That turns three ~150 ms calls into <5 ms
   and removes three failure modes.
7. **Emit cards for direct `/fast` calls.** `POST /fast` (`jarvisd.py:537-546`) returns the verdict
   but emits nothing, so a chip tap has no line in the transcript and no place in replay. Accept
   `session` + `emit` and reuse `run_fast_turn`'s event sequence.
8. **`/health` must publish the lanes** (`fast:{adb, adb_ms, rish, termux_api, serial}`) so the app
   can grey out chips instead of failing them, and so a dead `adb connect` is diagnosable without
   SSH.


### B3.2 Notes that changed a decision

- **`open_app` fuzzy matching.** Cache `pm list packages --user 0` (553 packages, 162 ms) plus the
  resolved components, in `~/.hermes/jarvis/cache/packages.json`, rebuilt at startup, on a miss, or
  after 24 h. Normalize both sides (lowercase, drop ZWNJ/spaces, `ي→ی`, `ك→ک`), then rank:
  exact alias → prefix → substring. Persian names come from a curated
  `aliases: {"واتساپ":"com.whatsapp", …}` block in the same cache (auto-grown when an
  agent opens an app successfully — cheap learning loop, no labels API needed). Ambiguous → `409`
  with `error.candidates` so the app can show a chooser.
  **`--user 0` is mandatory, and exit codes lie.** Observed today, twice, 25 minutes apart, on the
  same device: `pm list packages -3` → `SecurityException: Shell does not have permission to access
  user 150`, **exit 0**, empty stdout (Secure Folder is running: `pm list users` → `0:Owner`,
  `150:Secure Folder`); then the identical command → 112 lines. The failure is state-dependent, not
  a fixed fact — which is worse, because it is intermittent. `pm list packages --user 0` returned
  553 / 112 deterministically in both runs (162 ms). Same class of trap elsewhere:
  `cmd package resolve-activity` for a nonexistent package prints `No activity found` and exits 0
  (verified twice); `input keyevent 9999` exits 0 (verified); `cmd notification` help has no
  read command; `cmd clipboard` answers *"No shell command implementation."*
  **Parse stdout, filter it strictly (`line.startswith("package:")`), and never trust exit codes for
  `cmd`/`pm`.**
- **`screenshot`** uses `exec-out` (binary clean; `adb shell screencap -p` can mangle bytes).
  Guard with a 5 s timeout: a hung surface returns nothing and the daemon must report
  `error.code:"timeout"` rather than block. Read `dumpsys power | grep mWakefulness` first (90 ms)
  — a sleeping screen yields a valid, useless black PNG. `vision:true` is **not** fast: the vision
  route on this phone is 15–90 s (`<SKILLS>/mobile/android-vision-control`, `phone-see` chain ending
  at the local bridge `127.0.0.1:8790`), so the response returns `media` immediately plus
  `vision:{pending:true, job:"v_…"}`, and a later `tool.end` carries the text.
- **`type`, Persian.** `input text` handles ASCII only; the existing helper sends anything matching
  `[^ -~]` or containing quotes through `termux-clipboard-set` + `input keyevent 279`
  (`<BIN>/phone-type:8-11`). That is the correct trick and it is **3 s**, not 300 ms — so the app
  must render a "paste" card, and the daemon must report `lane:"termux-api", slow:true`.
  Also escape: `input text` needs spaces as `%s` (`phone-type:10`), and single quotes must be
  rejected or escaped.
- **`volume`.** Relative only. `media volume …` does not exist in adb's shell PATH (`/system/bin/sh:
  media: inaccessible or not found`, verified), `cmd audio` exposes no stream-volume subcommand
  (its help lists only surround-format), and `cmd media_session volume --help` documents options
  without syntax (**UNVERIFIED**). Absolute set → `termux-volume` (2.7 s, slow lane) or accept
  keyevent steps. `input keyevent 164` **toggles** mute: two calls = unmuted, which is the classic
  "it did nothing" report.
- **`media`.** `cmd media_session dispatch play-pause` with no active session prints nothing and
  exits 0. Always `cmd media_session list-sessions` first (129 ms; verified output begins
  `Sessions:`), else report `no_media_session`.
- **`screen_lock`.** `cmd power` has no lock/screen-off verb (help verified: only power-saver
  modes). Use `input keyevent 223` (KEYCODE_SLEEP) — **not** `26`, which toggles and would wake a
  sleeping phone. Sleep ≠ locked when the owner has an auto-lock delay or Smart Lock trust; report
  `verified:true, detail:{keyguard:"unknown"}` and let the card say «صفحه خاموش شد».
- **`wifi`.** `cmd wifi set-wifi-enabled enabled|disabled` is documented in the device's own help
  output (verified) and `cmd wifi status` reads back (verified: *"Wifi is enabled / connected to
  «Galaxy S10 Lite f0f9»"*). Execution is **UNVERIFIED** (mutating; not tested). Two hazards:
  the owner's internet and VPN ride on that link, and wireless debugging can drop with the radio —
  i.e. **turning Wi-Fi off can destroy the fast lane itself**. Confirmation required (B5).
- **`info`.** The cheap reads are in-process and need no device call at all:
  `/proc/uptime`, `os.statvfs("/data/data/com.termux/files")`, `time.time()` +
  `ZoneInfo("Asia/Tehran")`. Battery and screen state still need `dumpsys battery` (134 ms) /
  `dumpsys power` (90 ms). `df -h /data` via adb is available for exact storage numbers.
- **`notifications_shade`.** `cmd statusbar` help on this build lists exactly
  `expand-notifications`, `expand-settings`, `collapse` (verified). Verification string for the
  shade on One UI 8 is **UNVERIFIED**; fall back to `verified:false`.

## B4. What the response must never claim

`verified:true` is only allowed when the daemon read device state *after* the action, in the same
call, and the read agrees. Everything else is `verified:false`, and the card footer renders
«فرستاده شد — تأیید نشد». This is the same rule the global SOUL states for the agent ("if you could
not check something, say تأیید نشده"), applied mechanically.

## B5. Confirmation protocol (`caution` intents)

Risk classes: `safe` (default), `caution`, `forbidden` (never in `/fast`, B7).

`caution` intents: `wifi` (either direction), `screen_lock`, `clipboard.get`,
`notifications_shade` (annoying but harmless → actually `safe`), `volume.mute` when already muted,
`open_url` with a non-http scheme, `dial`.

Two-step, no hidden state:

```json
POST /fast {"intent":"wifi","args":{"on":false}}
409 {"ok":false,"error":{"code":"needs_confirm"},
     "confirm":{"token":"c_9f3a…","risk":"caution","ttl_s":60,
                "question":"وای‌فای خاموش شود؟ اتصال adb، VPN و اینترنت قطع می‌شود."}}

POST /fast {"intent":"wifi","args":{"on":false},"confirm":"c_9f3a…"}
200 {"ok":true,…}
```

Tokens are single-use, 60 s TTL, bound to `(intent, args-hash, session)`. The question text comes
from the intent catalogue so the app does not invent safety copy.

## B6. App chips → intents

`jarvis_fast.py:463-471` already declares a `CHIPS` list (ساعت، باتری، اسکرین‌شات، خانه، برگشت،
چه روی صفحه است) and `jarvisd.py:533` serves it inside `/catalog` — so the app should render
**whatever the daemon publishes**, not a hard-coded row: the daemon then owns the vocabulary, and a
new intent becomes a chip without an app release. Treat the table below as the recommended default
set (superset of the live one), with `label` taken from the daemon.

The bar above the input (`MainActivity.java:161-184`) gains a horizontally scrollable chip row of
`Ui.pill` buttons (`MainActivity.java:141-143`).

| chip | `{intent, args}` | note |
|---|---|---|
| 📸 | `screenshot` | long-press → «با سؤال» (`vision:true`, slow) |
| ← | `key{name:back}` | |
| ⌂ | `key{name:home}` | verify with `foreground` |
| ▢ | `key{name:recents}` | |
| 🔉 | `volume{action:up}` | long-press → `down` |
| 🔈 | `volume{action:mute}` | toggles — show the read-back state |
| 🔒 | `screen_lock` | confirm dialog (caution) |
| ⚡ | `open_app` | long-press → recent-3 chooser from the package cache |
| 🕒 | `info{what:time}` | in-process once delta 6 lands |
| 🔋 | `battery` | |
| 🎤 | (existing mic) | unchanged |
| 🔗 | `open_url` | opens a one-field sheet |

Rules: chips are disabled when `/health.fast.adb` is false (grey + tooltip «adb وصل نیست»); a tap
paints an optimistic card immediately, then fills `result` from the 200 body — the same code path
as `tool.start`/`tool.end` (`MainActivity.java:324-363`), so nothing new is needed in the adapter
once the verdict string is fixed (B2.2 item 3).
`Ui.java` currently has no `TEAL`, although `MainActivity.java:334` references `Ui.TEAL` — whoever
touches the app next should fix that first (the working tree as committed will not compile).

## B7. Risks — what must never be in the fast path

1. **Anything destructive or irreversible**: `pm uninstall`, `pm clear`, `pm disable-user`,
   `rm`/file deletion, `settings delete`, `cmd lock_settings` writes, `reboot`/`svc power reboot`,
   `am force-stop` of a foreground app, factory reset, permission revocation.
2. **Anything that spends money, sends a message, or places a call**: `termux-sms-send`,
   `termux-telephony-call`, `am start` with `SEND`/`CALL` actions, purchase deep links. `dial`
   (opens the dialer pre-filled, human presses call) is the maximum allowed.
3. **Anything that changes access control**: `pm grant`/`revoke`, `appops set`,
   `cmd notification allow_listener`, `settings put secure …`, and above all **toggling adb or
   wireless debugging** — that is a self-lockout: it kills the PC's SSH lane and this fast lane in
   one move.
4. **`wifi` off without confirmation** — see B3.2: it can take down the lane that ran it.
5. **Blind input into an unverified foreground app.** Before `type`, `tap` on a sensitive screen,
   or `key`, when the action is not obviously idempotent, the caller must have a recent
   `foreground` (164 ms) or `ui_dump`. The device-control skill records this as the number-one
   pitfall: coordinates come from the last dump, and if another app came forward the tap lands in
   the wrong app.
6. **Secrets**: `/fast` must never accept or echo credentials, OTPs or keys; `clipboard.get` is
   `caution` and its result must not be persisted into the transcript unless the caller explicitly
   asks (`emit:true`).
7. **Anything slow pretending to be fast**: `ui_dump` (2–4 s), vision (15–90 s),
   `termux-notification-list` (hung >120 s in a test today with no listener permission),
   `rish`/`termux-api` intents. They may exist, but they are labelled `slow:true`.
8. **Any LLM call, and any touch of Hermes' approval gate.** `/fast` is not a way around
   `tools/approval.py`; dangerous *terminal* commands keep going through the agent and its
   approval round-trip (`<JD>:278-283`).

## B8. Cost of getting this wrong

Every silent failure above was observed, not imagined: `pm list packages -3` exited **0** with no
output because of the Secure Folder user; `cmd package resolve-activity` exited **0** with
`No activity found`; `input keyevent 9999` exited **0** doing nothing; `cmd clipboard` answers
*"No shell command implementation."* An implementation that maps exit code → green card will
report successes it never had. That is worse than a slow path, because it teaches the owner to
trust a number.

---

## Appendix A — measurement log (phone, 2026-10-26)

All commands were read-only; the only device interactions were idempotent reads. The phone was
under heavy load (`uptime` → load average 15.0/15.0/14.9, up 11 days), so these numbers are
pessimistic for the adb lane and unrealistically *good* for nothing.

| # | Command | Result | ms |
|---|---|---|---|
| 1 | `adb -s 127.0.0.1:5555 shell true` | exit 0 | 96 |
| 2 | `... shell "getprop ro.product.model; getprop ro.build.version.sdk"` | SM-A536E / 36 | 136 |
| 3 | `... shell "dumpsys battery \| head -4"` | AC/USB/Wireless powered | 134 |
| 4 | `... exec-out screencap -p > /dev/null` | 526 666 bytes | 487 |
| 5 | `... shell "input keyevent 0"` | exit 0 | 156 |
| 6 | `... shell "cmd statusbar help"` | 3 subcommands | 131 |
| 7 | `... shell "cmd media_session list-sessions"` | "Sessions:" (empty) | 129 |
| 8 | `... shell "settings get system screen_brightness"` | 158 (`_mode` = 1) | 143 |
| 9 | `... shell "cmd package resolve-activity --brief -c …LAUNCHER com.whatsapp"` | `com.whatsapp/.Main` | 109 |
| 10 | `... shell "cmd package resolve-activity … com.nope.nope"` | `No activity found`, **exit 0** | — |
| 11 | `... shell "pm list packages --user 0"` / `-3 --user 0` | 553 / 112 lines | 162 |
| 12 | `... shell "pm list packages -3"` | **exit 0**, empty stdout, `SecurityException … user 150` | — |
| 13 | `adb -s 127.0.0.9:5555 shell true` | exit 1, `device '…' not found` | 63 |
| 14 | `... shell "dumpsys window \| grep -m1 mCurrentFocus"` | launcher focus | 164 |
| 15 | `... shell "dumpsys power \| grep mWakefulness"` | `Awake` | 90 |
| 16 | `... shell "dumpsys notification --noredact \| head -3"` | state dump | 266 |
| 17 | `... shell "input keyevent 9999"` | **exit 0**, no output | — |
| 18 | `... shell "cmd clipboard help"` | `No shell command implementation.` | — |
| 19 | `... shell "cmd audio"` | help, no volume subcommand | — |
| 20 | `... shell "media volume --stream 3 --get"` | `/system/bin/sh: media: inaccessible or not found` | — |
| 21 | `rish -c true` ×4 (with `RISH_APPLICATION_ID=com.termux`) | — | 2941, 2949, 2957, 2993 |
| 22 | `rish -c true` (no env var) | `RISH_APPLICATION_ID is not set` | 2723 |
| 23 | `termux-battery-status` ×5 | valid JSON | 2740, 2748, 2703, 2841 |
| 24 | `termux-volume` / `termux-clipboard-get` / `termux-toast` | ok / empty / ok | 2747 / 2762 / 2937 |
| 25 | `termux-notification-list` | **did not return in 120 s** (killed) | — |
| 26 | `date +%s` / `echo hi` / `python3 -c pass` | — | 29 / 30 / 148 |
| 27 | `pm list packages -3` re-run 25 min after #12 | **112 lines, success** (same command) | — |
| 28 | `... shell "pm list packages"` (bare, stdout only / with stderr) | 553 / 555 lines | — |
| 29 | `... shell "pm list users"` | `0:Owner running`, `150:Secure Folder running` | — |
| 30 | `... shell "am get-current-user"` | `0` | — |

#12 vs #27 is the point worth keeping: the same `pm` invocation failed and then succeeded without
any change on our side, so an intent that treats "exit 0" as success will be right most of the time
and silently wrong exactly when the device is in the state that matters.

Source reads (all on `<APP>`): `agent/agent_init.py:549-601,633,648,677,694-695,960`;
`agent/system_prompt.py:276-296,344-350,382-390,524-546,778-783`;
`agent/conversation_loop.py:866-940,994-996,1019,1567-1568,1826,1841,1878,2385-2391,4471-4472,4549-4553`;
`agent/chat_completion_helpers.py:2985-2986`; `agent/turn_context.py:196-245,455-500,640-700,1472`;
`run_agent.py:421,509-514,8740-8752,8972`; `hermes_state.py:11801-11807`;
`hermes_constants.py:17-18,30-46,114,193`; `hermes_cli/config.py:789,955-971`;
`toolsets.py:103,176`; `agent/reasoning_effort.py:51`; `tools/skills_tool.py:53-54,818,1086`.
Live fast-path reads: `~/.local/bin/jarvis_fast.py:76-135,191-231,270-333,352-424,441-471,508-543`;
`~/.local/bin/jarvisd.py:65,533,537-556,784-821`.

## Appendix B — UNVERIFIED registry

1. **`cmd wifi set-wifi-enabled` actually toggling** on this One UI 8 build (help text verified,
   execution not — mutating).
2. **`cmd statusbar expand-notifications` effect** and the string to detect the shade
   (`NotificationShade` on One UI 8).
3. **`am start` exit codes**: the `Error:` / `Starting:` output lines are documented behaviour, the
   exit status is not verified (analogous `cmd` paths exit 0 regardless).
4. **Keyguard state after `keyevent 223`** (`dumpsys window` field names on One UI 8).
5. **`screencap` with the screen off** returning a blank frame rather than failing.
6. **`cmd media_session volume` syntax** for absolute volume (`--help` lists options only).
7. **`uiautomator dump` latency** on this build (2–4 s is the inherited estimate from the
   device-control skill, not measured today to keep the session short).
8. **Duplicate rows in `state.db.messages`** if `conversation_history` is passed every turn
   (gateway pattern; must be asserted by acceptance test C5).
9. **`hermes` CLI profile mode end-to-end** with `set_hermes_home_override` in a worker thread —
   designed out of M2, so untested here.
10. **Vision latency floor** (15–90 s comes from `<SKILLS>/mobile/android-vision-control`, not from
    a measurement today).
11. **`svc wifi enable|disable`** (what `jarvis_fast.wifi` runs) actually toggling on One UI 8, and
    whether the loopback adb connection survives it (`cmd wifi set-wifi-enabled` is the documented
    alternative).
12. **`monkey -p <pkg> -c …LAUNCHER 1` exit codes** — the implementation keys off the string
    `No activities found`, which is the right instinct; the numeric status is unverified.
13. **Why `pm list packages -3` failed then succeeded** (#12 vs #27). Hypothesis: the user-150
    handle is only reachable while Secure Folder is resident, but the trigger was not isolated.

## Appendix C — acceptance tests (numbers, not vibes)

- **C1** `POST /fast {intent:"home"}` ×10 on a warm daemon: every `ok:true`, `ms` p50 < 300,
  p90 < 400, `lane:"adb"`.
- **C2** `POST /fast {intent:"screenshot"}`: `media.url` fetchable, `bytes > 100 000`,
  `ms < 700`, PNG magic `\x89PNG`.
- **C3** `POST /fast {intent:"open_app",args:{query:"qqqq"}}`: HTTP 200, `ok:false`,
  `error.code` in `{no_activity, ambiguous_app}`, and the card renders **red**
  (proves the `"ok": false` string reaches `MainActivity.java:356-358`).
- **C4** With the stale `emulator-5554` attached, every response carries
  `detail.serial:"127.0.0.1:5555"` — no intent ever lands on the emulator entry.
- **C5** Two turns in session A with persona `jarvis`, `POST /session/A/persona {id:"kargah",
  mode:"swap"}`, third turn: history intact (assistant references turn 1) and `SELECT COUNT(*)`
  on `messages` for A is not doubled (`<APP>/hermes_state.py:11801` load path).
- **C6** Daemon restart: `GET /session/A` still reports `persona:"kargah"`, and the next turn uses
  `kargah`'s model + prompt (check `result["model"]` from the turn and `agent.ephemeral_system_prompt`).
- **C7** `GET /personas` with a deliberately broken `persona.json`: daemon still serves, the broken
  entry appears under `errors`, chat still answers.
- **C8** M1 regression per `CLAUDE.md`: a tool-needing message still streams a tool card and the
  answer in one turn, `/status` stays "connected".
- **C9** Fast-path failure renders as a failure: send «فلان‌برنامه رو باز کن» in the app; the
  `tool.end` card must be **red** (`result` contains `"ok": false`). Today this fails — see B2.2
  item 3.
- **C10** `POST /fast {intent:"wifi",args:{action:"off"}}` without a token → `409 needs_confirm`,
  and with the token → `200` + `verified:true` only if `wifi_on=0` was read back.
- **C11** 20 consecutive `open_app` calls for installed packages: `verified:true` ≥ 19/20, and the
  package cache never contains a line without a `.` in it (junk-filter assertion for delta 3).

## Appendix D — build order (what to do first)

1. `run_fast_turn` verdict fix + `verified`/`lane`/`slow`/structured `detail` (B3.1 #1-2) — smallest
   change, removes the "green lie".
2. `packages()` `--user 0` + strict filter (B3.1 #3).
3. `foreground` intent (B3.1 #5) — everything else that claims verification depends on it.
4. Personas: loader + `GET /personas` + `Session.persona` (A3, A6) — no Hermes change needed.
5. History per turn (`conversation_history` from `state.db`) — required by A5 and fixes the
   pre-existing single-turn behaviour (0.2).
6. `/fast` card emission for chip taps + `/health.fast` (B3.1 #7-8).
7. Confirm tokens (B5), then the remaining intents (B3.1 #5) and `info` in-process (B3.1 #6).
