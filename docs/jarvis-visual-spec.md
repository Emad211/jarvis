# Jarvis — visual specification («Basalt»)

Status: **proposal, 2026-10-26.** Supersedes `design-system.md` for the *Jarvis console only*
(Hermes Control keeps the old contract until it gets the same pass). Every number here is
implementable with `LinearLayout` + `TextView` + `View` + `GradientDrawable`/`RippleDrawable`
and a bundled TTF. No Gradle, no Material, no Compose, no new dependency, no image assets.

Read with `jarvis-architecture.md` (what the app does) and `jarvis-personas-and-fastpath.md`
(what the daemon will publish). This file is only about how it looks and how it is built.

---

## 1. Direction, in five lines

1. **Type and measure carry the design, not containers.** The assistant answer stops being a
   bordered bubble and becomes full-width, 16sp/24dp Persian prose under a one-line author
   header; the user's own turn is the only filled card on screen. Hierarchy comes from a real
   size jump (11 → 13 → 15 → 16 → 20 → 24sp), never from another box.
2. **Basalt strata, not flat grey.** Five surfaces, one hue (248° violet), each step a measured
   +3.5…4.3 % luminance — window, panel, card, pressed, and one *sunken* well for machine text.
   Elevation is a lighter (or darker) surface plus a 1px hairline; there are zero shadows.
3. **One accent, used as ink.** `#A78BFA` appears as text, as a 2–3dp rule, as a dot, and as
   exactly one filled control per screen (send). It is never a wash, a gradient, or a glow.
4. **Every surface is a list, one row language.** Menu, model, providers, services and sessions
   are the same hand-rolled bottom sheet with the same 56dp rows — so the app has one grammar
   and a developer can maintain it with two helper methods.
5. **Android's own chrome is gone.** No `AlertDialog`, no `Toast`, no default Roboto, no purple
   Material buttons: the eleven `AlertDialog.Builder` sites are the single loudest "ugly" tell in
   the current build.

What this replaces, concretely: today the screen is a grey wall of bordered rounded boxes
(bubble → tool card → pill row, three nested containers) with Android's 2015 dialogs on top and
a green send button competing with a blue accent. Colour alone would not fix that; the structure
and the type scale do.

---

## 2. Device math (so the wireframes mean something)

| | |
|---|---|
| Phone | Samsung A53, 1080 × 2400 px, 6.5″ |
| Density | 2.625 (reported 405–420 dpi) → **411dp × 914dp**; Samsung sometimes reports 2.8125 → 384dp × 853dp |
| Design width | **360dp** (worst case; also covers 384/411dp) |
| Content column | 360 − 2×16 = **328dp**; at 411dp it becomes 379dp, so prose is capped at **336dp** (see §4) |
| Status bar inset | ~24–28dp, read from insets, never hard-coded |
| Nav bar inset | 24dp gesture / 48dp 3-button, read from insets |
| Font scale | must survive **1.3** (see §4.4) |

Wireframe convention: **1 column = 8dp, 1 row = 16dp**. A 45-column frame is 360dp wide. The
frame is the *360dp* case; on the real 411dp screen the transcript column grows and every gutter,
radius and vertical step below stays identical.

---

## 3. Colour

### 3.1 Tokens

One hue for all neutrals: **248° (violet-blue), saturation 21 ± 1 %**. That is what makes it not
flat grey — the hue is stable while only luminance moves.

| Token | Hex | Replaces | Role |
|---|---|---|---|
| `INK_0` | `#0A0910` | `BG` | window / transcript background |
| `INK_1` | `#12111A` | `SURFACE` | header, composer strip, sheet fill, tool card on window |
| `INK_2` | `#1A1926` | `SURFACE_2` | cards, input field, row groups, chips |
| `INK_3` | `#232134` | — (new) | pressed / selected row, chip pressed, segmented track |
| `WELL` | `#0D0C13` | — (new) | **sunken** surface for code, tool args, tool result |
| `LINE` | `#2E2B42` | `STROKE` | 1px border, hairline, dividers |
| `TEXT` | `#F4F2FA` | `TEXT` | primary text |
| `TEXT_2` | `#B3ADC7` | `DIM` | secondary text, row subtitle |
| `TEXT_3` | `#837CA0` | `FAINT` | metadata only (never a sentence) |
| `ACCENT` | `#A78BFA` | `ACCENT` | the one interactive colour |
| `ACCENT_HI` | `#C9BAFD` | — (new) | pressed state of accent *text* only (links); never a fill |
| `USER` | `#2B2442` | `USER` | the user's own turn (accent-family, L 20 % vs INK_3 16.7 %) |
| `ON_ACCENT` | `#0D0A18` | `ON_ACCENT` | glyphs/labels on an accent fill |
| `OK` | `#4ADE80` | `GREEN` | state: healthy / done |
| `WARN` | `#FBBF24` | `AMBER` | state: working / waiting / needs attention |
| `BAD` | `#F87171` | `RED` | state: failed / stopped / destructive |

**16 stored colours, 3 of them status.** That is the ceiling; the next colour is a bug.

### 3.2 Measured contrast (WCAG 2.1, computed on the exact hexes above)

| Pair | Ratio | Floor |
|---|---|---|
| `TEXT` on `INK_0` / `INK_1` / `INK_2` / `USER` | 17.9 / 16.9 / 15.6 / 13.2 | 4.5 |
| `TEXT_2` on `INK_1` / `INK_2` / `WELL` | 8.7 / 8.0 / 9.0 | 4.5 |
| `TEXT_3` on `INK_1` / `INK_0` / `INK_3` | 4.8 / 5.1 / 4.0 | 3.0 |
| `ACCENT` on `INK_0` / `INK_1` / `INK_2` | 7.3 / 6.9 / 6.4 | 4.5 |
| `ACCENT_HI` on `INK_1` | 10.6 | 4.5 |
| `ON_ACCENT` on `ACCENT` | 7.2 | 4.5 |
| `OK` / `WARN` / `BAD` on `INK_1` | 10.8 / 11.2 / 6.8 | 4.5 (text) |
| `LINE` on `INK_1` / `INK_0` | 1.37 / 1.45 | 1.2 (visible hairline) |

Elevation is measurable: L = 4.9 → 8.4 → 12.4 → 16.7 → 21.4 → 20.0(`USER`). Steps of 3.5–4.3 L are
perceptible; steps under 2 L (what the current `#07090E` / `#11151D` / `#1A2029` ladder has in two
of three gaps) read as "the same grey", which is why the app looks flat.

### 3.3 Alpha recipes (the only legal use of `Ui.alpha`)

| Name | Recipe | Where |
|---|---|---|
| `press` | `alpha(colour, 56)` ripple over `INK_3` base | every tappable row / icon button |
| `chip` | fill `alpha(colour, 16)` + stroke `alpha(colour, 45)` | pills, chips (colour = ACCENT or a status) |
| `rule` | `alpha(ACCENT, 255)` 2–3dp wide | the selected/current marker (one per screen) |
| `divider` | `alpha(LINE, 150)` 1dp | inside a row group |
| `scrim` | `alpha(0xFF000000, 200)` | behind a sheet |
| `disabled` | `alpha(TEXT_3, 120)` | disabled glyphs |

### 3.4 Where each colour may appear

- `OK/WARN/BAD`: **a dot, an icon, a 12sp word that names the state, or a chip that names the
  state.** Never a surface wash, never a border on a large block, never "just because".
- `ACCENT`: author name, links, selected control, the focused input border, one filled button per
  screen, the 2dp current-session rule, the streaming caret.
- `TEXT_3`: timestamps, durations, provider ids, versions, endpoints. Never a full sentence.
- Status is never carried by colour alone: every status also has a word next to it.

### 3.5 Forbidden (the owner already rejected these — this is the contract)

No gradient of any kind. No glow, no shadow, no `setShadowLayer`, no `elevation` API. No
translucent "glass" over content. No multi-hue chips. No colour that means "pretty". No pure
black `#000000` (OLED halation) and no pure white `#FFFFFF` text. No emoji anywhere in a UI
string — the system emoji font renders in colour and breaks the single-accent rule, and
Vazirmatn contains none (verified: its cmap has U+007C `|`, U+2022 `•`, U+00B7 `·`, U+2026 `…`,
U+2014 `—`, but **no** U+25CF `●`, U+2713 `✓`, U+2190-2193 arrows, U+258C/258D block chars,
U+25BE `▾`). Every symbol on screen is drawn by `Icon.java`.

---

## 4. Type

One family: **Vazirmatn** (`vazirmatn_regular.ttf`, `vazirmatn_bold.ttf`, bundled).
Monospace (`Typeface.MONOSPACE`) is allowed **only** for machine strings: tool names, tool args,
tool results, model ids, provider ids, env names, versions, endpoints, durations, fenced code.
Never for a Persian sentence. Fallback when the asset fails to load: `Typeface.SANS_SERIF` (never
`DEFAULT`/Roboto Light).

### 4.1 Measured fact that fixes line height

Vazirmatn's natural line height is **1.5625 em** (hhea asc 2100 / desc −1100 / upem 2048, and
`USE_TYPO_METRICS` is set). Android's `setLineSpacing(add, mult)` multiplies *that*, so:

- `setLineSpacing(dp(3), 1f)` — what the code does today — yields 1.56 em **+ 3dp**, i.e. a 16sp
  paragraph gets 28dp lines: loose, and the extra 3dp does not scale with font scale.
- To hit a real line height, keep `add = 0` and set `mult = target / 1.5625`.

Four multipliers cover the whole app, and all of them scale with font scale automatically:

| Constant | `mult` | Resulting line height | Used for |
|---|---|---|---|
| `LH_TIGHT` | `0.78` | 1.22 em | section labels, chips |
| `LH_SNUG` | `0.86` | 1.34 em | row titles, subtitles |
| `LH_TEXT` | `0.96` | 1.50 em | **body prose**, mono blocks |
| `LH_LOOSE` | `1.00` | 1.56 em | display (24sp) and header title |

### 4.2 Scale

| Role | sp | Weight | LH | Colour | Where |
|---|---|---|---|---|---|
| Display | 24 | Vazirmatn Bold | `LH_LOOSE` | `TEXT` | empty-state headline (2 lines max) |
| Title | 20 | Bold | `LH_LOOSE` | `TEXT` | header «جارویس» |
| Prose | 16 | Regular | `LH_TEXT` | `TEXT` | assistant answer |
| Prose-strong | 16 | Bold | `LH_TEXT` | `TEXT` | `**bold**` runs inside prose |
| User | 15 | Regular | `LH_TEXT` | `TEXT` | user turn |
| Row | 15 | Regular / Bold when it is the row's own name | `LH_SNUG` | `TEXT` | sheet rows, session titles |
| Sheet title | 15 | Bold | `LH_SNUG` | `TEXT` | sheet header |
| Sub | 12 | Regular | `LH_SNUG` | `TEXT_2` | row subtitle, header state line |
| Label | 11 | Bold | `LH_TIGHT` | `TEXT_3` | «شروع سریع», «مدل», «سرویسها» |
| Author | 13 | Bold | `LH_SNUG` | `ACCENT` | «جارویس» above an answer |
| Meta | 11 | Regular Mono | `LH_TIGHT` | `TEXT_3` | durations, ids, counts, time |
| Mono-strong | 12 | Bold Mono | `LH_SNUG` | `TEXT` | tool name |
| Mono-body | 11 | Regular Mono | `LH_TEXT` | `TEXT_2` / `TEXT_3` | args, result, code |
| Chip | 12 | Bold | `LH_TIGHT` | `ACCENT` or `TEXT_2` | chips, pills |
| Button | 14 | Bold | `LH_TIGHT` | `ON_ACCENT` / `ACCENT` | filled and tonal buttons |
| Snack | 13 | Regular | `LH_SNUG` | `TEXT` | in-app toast |

Rules:
- **Letter spacing is always 0.** Tracking breaks Persian letter joining; never set it, and never
  uppercase a Persian string (uppercase only touches Latin and looks wrong beside Persian).
- Only the two real weights ship. No `setFakeBoldText`, no italic, no synthesized bold on Mono
  (use `MONOSPACE` + `setFakeBoldText(false)`; if a bold mono is needed, use `Typeface.MONOSPACE`
  with `BOLD` style and accept the platform's substitute, or just colour/texture for emphasis).
- Line height is always set with `Ui.lh(view, LH_*)`; the old `dp(3)` additive is deleted.
- `setIncludeFontPadding(false)` everywhere (Vazirmatn's ascent is generous; padding double-counts).
- Anything that can overflow gets `setSingleLine(true)` + `TruncateAt.END`. An ellipsis beats a
  reflowed row.

### 4.3 Digits and mixed direction

- Counts, turn numbers, durations inside Persian sentences: **Persian digits** (۰۱۲۳۴۵۶۷۸۹).
  Helper `Ui.fa(long)` → `String.format(Locale("fa","IR"), "%d", n)`. Verify on device that Java's
  `fa` locale emits U+06F0–06F9 and not Arabic-Indic U+0660–0669; if it does, hard-code the ten
  glyphs (`Ui.PERSIAN_DIGITS`).
- Measurements that a developer compares to a log line — `1.8s`, `42 ms`, model ids, versions,
  byte counts — stay **Latin, in mono**. Mixed rule: *a number that the user reads as a sentence
  is Persian; a number a developer compares is Latin mono.*

### 4.4 Font scale 1.3 (hard requirement)

At 1.3, prose 16sp renders as 20.8sp and the header title 20sp as 26sp. Therefore:
- **No fixed heights on any text container.** Buttons, pills, chips and rows use `minHeight` +
  padding, never `height=`.
- The header's height is `wrap_content` and grows to ~84dp at 1.3 — acceptable; the transcript
  loses 16dp.
- The pill uses padding `10/6` with `minHeight 32dp` (so it becomes ~40dp at 1.3) instead of the
  current fixed-height look.
- Mono blocks are clamped: `maxLines(12)` + `ellipsize(END)` for args, `maxLines(20)` for result,
  and the tool card body is collapsible (see §7.5), so one 400-character result cannot swallow the
  screen at 1.3.
- Every string in the spec was checked at 360dp × 1.3; the ones that must ellipsize are marked
  with `…` in the widget tables.

---

## 5. Spacing and radii

### 5.1 Spine

4dp grid; **only these values exist**: `2 · 4 · 6 · 8 · 10 · 12 · 14 · 16 · 20 · 24 · 28 · 32`.
Nothing else — `13`, `18`, `22` and the current `9`/`11` are removed.

| Use | Value |
|---|---|
| Screen gutter (both sides) | **16** (composer and header included; the composer's icon buttons carry their own 4dp inset so the glyph still lines up) |
| Header: top / bottom padding | 12 / 12 (+ status-bar inset on top) |
| Header: gap title↔pill, pill↔menu | 8 / 4 |
| Transcript list padding | start/end 16, top 12, bottom 8 |
| **Gap after a user turn** | **16** |
| **Gap after an assistant turn** | **24** |
| Inside an assistant turn: author line → prose | 6 |
| Inside an assistant turn: prose → tool card / tool card → prose | 10 |
| Inside a user bubble | 14 / 10 / 14 / 10 |
| Tool card padding | 12 / 9 / 12 / 10; header → body 6; body blocks 8 apart |
| Sheet: handle → title | 12; title → first label 16; label → group 8; group → label 20 |
| Sheet bottom padding | 16 (+ nav inset) |
| Chips strip: top / bottom | 6 / 6 (chip 32 ⇒ 44dp strip) |
| Composer: top / bottom | 8 / 12 (+ IME/nav inset) |
| Composer gaps | 8 |

### 5.2 Vertical rhythm of a conversation

```
24 ── gap after assistant turn
 6 ── author line → prose
24 ── prose line (16sp × 1.5)
10 ── prose → tool card
67 ── tool card (collapsed)
10 ── tool card → prose
16 ── gap after user turn
42 ── user bubble (one line, 15sp)
```
Between two user turns or two assistant turns the 16/24 rule still applies by role of the
*previous* row — that is what makes the transcript group visually.

### 5.3 Radii

| Token | dp | Use |
|---|---|---|
| `R_WELL` | 8 | sunken code/args block |
| `R_CHIP` | 12 | chips, pills, tool card, selected row, snack |
| `R_FIELD` | 14 | input field, tonal buttons |
| `R_GROUP` | 16 | row group, empty-state group, card |
| `R_BUBBLE` | 18 | user bubble |
| `R_SHEET` | 24 | sheet top corners |
| `R_ROUND` | 999 | only: status dots, send/mic buttons, radio ring |

The old `20` and `22` are gone: two radii 2dp apart read as a mistake nobody can name.

---

## 6. Screen-by-screen wireframes

Legend: `●` = drawn dot, `▾ › ⟳ ✕` = `Icon.java` glyphs (§7.13), `┄` = 1px `LINE` hairline,
`▓` = 3dp accent rule, `▁` = streaming caret. Numbers on the left are dp from the top of the window.

**Reading the frames:** the **right edge is the RTL start edge.** Anything that hugs the start
(dot + title, author line, leading icon, section labels, sheet titles) is drawn against the right
border; anything trailing (model pill, menu, close/plus buttons, metadata, and the user's own
bubble) sits on the left. Persian text inside a proportional viewer will not line up to the
character — the frames encode *sides and order*, the numbers encode size. **No emoji in the real
app**: the symbols here stand for glyphs drawn on the 24×24 grid.

### (a) First open — empty state

```
   0 ┌───────────────────────────────────────────────┐
     │ status bar · transparent · icons light         │  inset, ~24dp
  24 ├───────────────────────────────────────────────┤
     │  ☰   ┌──────────────┐          ● جارویس        │  header INK_1, 68dp
     │      │ flash-3.5  ▾ │     آماده · 127.0.0.1…   │  dot 8 + 20sp Bold / 12sp
     │      └──────────────┘                          │  menu 44dp, pill 32dp at END
  92 ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤  1dp LINE
     │                                               │  transcript INK_0
     │         چه کاری برایت انجام بدهم؟             │  24sp Bold, 2 lines max
     │      یک دستور بنویس، یا از اینها شروع کن       │  14sp TEXT_2, +8
     │                                               │  +28
     │                                   شروع سریع    │  11sp Bold TEXT_3
     │  ┌─────────────────────────────────────────┐  │  group INK_2 r16
     │  │ اسکرین‌شات بگیر                   ▾   ›  │  │  60dp row, icon at START
     │  │ تصویر صفحه را می‌گیرد و نشان می‌دهد        │  │  12sp TEXT_2
     │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤  │  divider 1dp a(LINE,150)
     │  │ ساعت چنده؟                       ▾   ›  │  │
     │  │ بدون مدل، فوری                           │  │
     │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤  │
     │  │ باتری                            ▾   ›  │  │
     │  │ درصد و وضعیت شارژ                        │  │
     │  └─────────────────────────────────────────┘  │
     │                                               │
     │  [only when disconnected, 11sp Mono BAD]      │
     │  به 127.0.0.1:8780 وصل نیست — Termux روشن؟    │
     ├───────────────────────────────────────────────┤
     │                     … (empty: no chips row)   │  16dp spacer
     ├───────────────────────────────────────────────┤
     │         ┌───────────────────────┐  ▾ (send)  │  composer INK_1, 64dp
     │  (○)    │ به جارویس بگو چه…      │            │  input INK_2 r14, 44dp
     │         └───────────────────────┘            │  send 44 / mic 44, send at START
 890 └───────────────────────────────────────────────┘  + nav inset
```

Geometry: status 24 · header 68 · hairline 1 · transcript `weight 1` · composer 64 · nav 24.
The empty block is a vertical `LinearLayout` centered with a 1 : 1.2 spacer pair (biased slightly
above optical center), never a `ScrollView` of decoration. The quick-start list **replaces the
chips strip** while the transcript is empty — one affordance, not two.

Content of the three rows is whatever the daemon publishes in `/catalog.chips` (first three);
the copy in the sub-line comes from a local map, and a chip with no copy simply hides the
sub-line (row height drops to 52dp).

### (b) Active conversation — user turn, tool card, streaming answer

```
     ├───────────────────────────────────────────────┤
     │         1.8s         ● جارویس · flash-3.5      │  author row 13sp ACCENT;
     │                                                │  latency 11sp Mono at END
     │   وضعیت سرویسها را چک کردم. سه سرویس بالاست    │  16sp TEXT, 24dp lines
     │   و بکاپ دیشب هم گرفته شده.                     │
     │                                                │  +10
     │  ┌──────────────────────────────────────────┐  │  tool card INK_2 r12
     │  │ terminal                         3.9s  ▾ │  │  36dp header, name at START
     │  │ hermes status                            │  │  11sp Mono TEXT_2, 1 line …
     │  └──────────────────────────────────────────┘  │  collapsed; tap = expand
     │                                                │  +10
     │   اگر بخواهی همین حالا گزارش کامل را…▁         │  caret (ACCENT) at text end
     │                                                │
     │  ┌───────────────────────┐                     │  user bubble USER r18
     │  │  سرویسها را چک کن      │                     │  15sp TEXT, ≤ 84 % width
     │  └───────────────────────┘                     │  at END (screen left)
     ├───────────────────────────────────────────────┤
     │   (▾ خانه) (▾ باتری) (▾ ساعت) (▾ اسکرین‌شات)   │  chips 44dp; first chip at START (right)
     ├───────────────────────────────────────────────┤
     │         ┌───────────────────────┐  ▾ (send)  │  send ⇄ STOP while streaming
     │  (○)    │                       │            │  mic 44dp at END (left)
     │         └───────────────────────┘            │  input grows to 4 lines
     └───────────────────────────────────────────────┘
```

Note the order: the **user's turn is above**, the answer below it. In RTL the user bubble sits at
the *end* edge (screen left) and the assistant block is flush to the *start* edge (right) at full
column width. The author line carries model + time-to-first-token, so the old footer pill row is
deleted — that row was the transcript's biggest source of visual noise.

### (c) Bottom sheet — model + reasoning

```
     │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ scrim a(#000,200) ▓▓▓▓▓▓▓▓▓│  tap = dismiss
     │  ╭─────────────────────────────────────────╮  │  sheet INK_1, r24 top
     │  │                  ▁▁▁▁                   │  │  handle 36×4 LINE, 12/12
     │  │  ▾                 مدل و استدلال        │  │  title 15sp Bold at START
     │  │                                         │  │  close 44dp at END
     │  │                              مدل        │  │  label 11sp Bold TEXT_3
     │  │  ┌─────────────────────────────────────┐│  │  group INK_2 r16
     │  │  │ Gemini 3.5 Flash              ◉     ││  │  radio 18dp at START
     │  │  │ google_ai_studio/gemini-3.5-fl…     ││  │  11sp Mono TEXT_3, oneLine …
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │  divider, inset 14
     │  │  │ Gemini 3.5 Flash Lite         ○     ││  │  ring TEXT_3 when off
     │  │  │ google_ai_studio/gemini-3.5-fl…     ││  │
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ Nemotron 3 Ultra (free)       ○     ││  │
     │  │  │ openrouter/nemotron-3-ultra…        ││  │
     │  │  └─────────────────────────────────────┘│  │
     │  │                                         │  │  +20
     │  │                         سطح استدلال     │  │
     │  │      ( بدون )( کم )( متوسط )( زیاد )    │  │  4 pills 34dp, gap 8
     │  │                            ▔▔▔▔▔        │  │  selected = ACCENT fill
     │  │                                         │  │
     │  │   این گفتگو · flash-3.5 · استدلال: بدون │  │  11sp Mono TEXT_3
     │  ╰─────────────────────────────────────────╯  │
     │                    (nav inset)                │
```

Selection applies immediately (POST `/session/{id}/model` / `/reasoning`); there is **no apply
button** — a non-destructive setting should not need a confirmation step. The sheet's own state
line at the bottom is the acknowledgement.

### (d) Bottom sheet — providers and keys

```
     │  ╭─────────────────────────────────────────╮  │
     │  │                  ▁▁▁▁                   │  │
     │  │  ▾            پرووایدرها و کلیدها       │  │
     │  │                                         │  │
     │  │  ┌─────────────────────────────────────┐│  │
     │  │  │ (کلید دارد)       Google AI Studio  ││  │  pill OK, 12sp Bold at END
     │  │  │ GEMINI_API_KEY                      ││  │  11sp Mono TEXT_3 at START
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ (بی کلید)         OpenRouter        ││  │  pill WARN
     │  │  │ OPENROUTER_API_KEY                  ││  │
     │  │  │ ┌─────────────────────────────────┐ ││  │  expanded in place:
     │  │  │ │ sk-or-v1-…                      │ ││  │  field WELL r14, LTR, 44dp
     │  │  │ └─────────────────────────────────┘ ││  │
     │  │  │ ( ذخیره و تست )        صفحهٔ کلید    ││  │  tonal 44dp + ACCENT link
     │  │  │ ذخیره شد، تست رد شد (۴۰۱) · 920 ms  ││  │  12sp BAD, ms in 11sp Mono
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ (کلید دارد)       AgentRouter       ││  │
     │  │  │ AGENTROUTER_API_KEY                 ││  │
     │  │  └─────────────────────────────────────┘│  │
     │  │     ۳ کلید · ۲ پرووایدر فعال             │  │  11sp Mono TEXT_3
     │  ╰─────────────────────────────────────────╯  │
```

Rules: an existing key is **never** displayed — only `(کلید دارد)` and, if useful, its length
(«۳۹ کاراکتر», Persian digits). The field is `TEXT_DIRECTION_LTR`, mono, password input, hint =
env name. «ذخیره و تست» is disabled (TEXT_3 on INK_2) until ≥ 8 characters. The result line under
the row replaces the old toast; a toast for a failed API test is how a key silently doesn't work.

### (e) Bottom sheet — control (services: gwctl / jarvis / gw)

```
     │  ╭─────────────────────────────────────────╮  │
     │  │                  ▁▁▁▁                   │  │
     │  │  تازه‌سازی ▾             سرویس‌ها        │  │  32dp text button at END
     │  │                                         │  │
     │  │                              اتصال      │  │
     │  │  ┌─────────────────────────────────────┐│  │
     │  │  │ (آماده)           ● جارویس (8780)   ││  │  OK dot + pill
     │  │  │ 0.1 · ۳ گفتگو · warm                ││  │  11sp Mono TEXT_3
     │  │  └─────────────────────────────────────┘│  │
     │  │  ┌─────────────────────────────────────┐│  │
     │  │  │ (وصل)            ● گیتوی (Telegram) ││  │  OK / WARN «نیاز به توجه»
     │  │  │ google_ai_studio/gemini-3.5-flash   ││  │  11sp Mono, oneLine …
     │  │  │ ۲ ساعت ۱۴ دقیقه بالا                ││  │  Persian digits
     │  │  └─────────────────────────────────────┘│  │
     │  │                                         │  │
     │  │                    سرویس‌های hermes-svc  │  │
     │  │  ┌─────────────────────────────────────┐│  │
     │  │  │ ⟳ ✕         در حال اجرا   ● gwctl   ││  │  56dp row; 32dp ghost
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ ⟳ ✕         در حال اجرا   ● jarvis  ││  │
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ ⟳ ✕         متوقف          ○ gw      ││  │  BAD dot
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ ⟳ ✕         در حال اجرا   ○ tgwatch ││  │
     │  │  └─────────────────────────────────────┘│  │
     │  │   آخرین بررسی: همین حالا · gwctl 8777   │  │  11sp Mono TEXT_3
     │  ╰─────────────────────────────────────────╯  │
```

Data sources (no daemon change needed): jarvisd `GET /health` → `{ok, version, warm, sessions}`;
gwctl `GET http://127.0.0.1:8777/services` → `{services:[{name,running}]}`; gwctl
`GET /status?profile=` → `{overall, telegram, model, uptime_seconds, needs_attention}`. The app
needs one extra preference, `gwctl_base` (default `http://127.0.0.1:8777`) — additive, no new
listener, loopback only. `⟳` = restart (`POST /services/action {name,action:"restart"}`), `✕` =
stop, and **stop asks first** through the same sheet with a confirm row (destructive ⇒ a second
tap, never a long-press surprise).

### (f) Bottom sheet — session list

```
     │  ╭─────────────────────────────────────────╮  │
     │  │                  ▁▁▁▁                   │  │
     │  │  +                  گفتگوها             │  │  44dp PLUS at END
     │  │  ┌─────────────────────────────────────┐│  │
     │  │▓ │ گزارش سرویس‌ها                      ││  │  current: INK_3 + 3dp
     │  │▓ │ ۱۲ نوبت · ۵ دقیقه پیش · flash-3.5    ││  │  ACCENT rule at START
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │ ●          تمیز کردن کش             ││  │  ● busy dot ACCENT at END
     │  │  │ ۷ نوبت · دیروز · flash-3.5-lite     ││  │  sub 12sp TEXT_2
     │  │  ├┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┤│  │
     │  │  │            بدون عنوان               ││  │
     │  │  │ ۱ نوبت · ۳ روز پیش · gpt-6.1-sol    ││  │
     │  │  └─────────────────────────────────────┘│  │
     │  │      هیچ گفتگوی دیگری نیست              │  │  empty 13sp TEXT_2
     │  ╰─────────────────────────────────────────╯  │
```

Relative time is computed once at open (not a ticking timer). `title` comes from the daemon;
`بدون عنوان` when empty. Selecting a row = `prefs.session = id; lastSeq = 0; openSession(id)`.

---

## 7. Widget specs

All values dp. `start/end` = RTL-relative; never `left/right`.

### 7.1 Header

```
LinearLayout horizontal, wrap_content height (≥ 64 + status inset), bg INK_1
padding: start 16, top 12+insetTop, end 8, bottom 12
├─ titles: LinearLayout vertical, weight 1
│   ├─ row: [dot 8×8, marginEnd 8] + TextView «جارویس» 20sp Bold TEXT, LH_LOOSE
│   └─ state: TextView 12sp Regular LH_SNUG, colour = state, paddingTop 4,
│             singleLine, ellipsize END, text = «آماده · 127.0.0.1:8780»
├─ model pill (see 7.2), marginStart 8
└─ menu: IconButton MENU 44×44, glyph 20, TEXT_2, marginStart 4
below: View 1dp, LINE   (a real view, not a divider attribute)
```

- The state line shows **connection state only** (آماده / در حال اتصال… / قطع — تلاش دوباره /
  منتظر اجازه). It must never show «مینویسد…», «ابزار: terminal» or a latency: turn activity
  lives in the transcript, otherwise the header churns on every event and looks broken.
- The dot's colour is the same state; the dot is 8dp, not 9 (a 9dp dot in a 24-line grid lands off-pixel).
- The model pill always shows a **model id or «مدل پیشفرض»** — never the literal word «مدل».

### 7.2 Status dot / pill

| Element | Spec |
|---|---|
| Dot | `View` 8×8, `round(colour, 999)`; marginEnd 8 before a label; never alone without a word within 8dp |
| Pill (text only) | `TextView` 12sp Bold, colour = token, padding 10/6, `minHeight 32`, bg `outline(chip-alpha fill, chip-alpha stroke, R_CHIP)`; singleLine |
| Pill (with dot) | dot 6×6 + 6 gap inside the same pill, padding start 8 |
| State pill colour | `OK` naming healthy/done · `WARN` working/waiting · `BAD` failed/stopped · `TEXT_3` neutral fact · `ACCENT` in-progress/live |
| Forbidden | two pills side by side both coloured; a pill that only repeats the adjacent title |

### 7.3 User bubble

```
holder: LinearLayout vertical, gravity END (RTL ⇒ left edge), paddingStart 56 (84 % cap)
bubble: LinearLayout vertical, bg USER, no border, radius R_BUBBLE
        padding 14/10/14/10, wrap_content
text:   15sp Regular LH_TEXT TEXT, TEXT_DIRECTION_ANY_RTL, setLineSpacing via Ui.lh
```
- 56dp start padding on the holder is the width cap (328 − 56 = 272dp = 83 % at 360dp; 323/379
  = 85 % at 411dp). Do not use `setMaxWidth` on a WRAP_CONTENT child of a horizontal parent — it
  is ignored under `AT_MOST` measurement.
- No author line, no timestamp, no pill inside the bubble. The bubble *is* the user's mark.
- Long-press opens the message menu (sheet), not an AlertDialog.

### 7.4 Assistant block

```
LinearLayout vertical, bg = none (INK_0 shows through), full column width, no radius, no border
├─ author row: [dot 6, colour: ACCENT streaming / TEXT_3 done / BAD failed, marginEnd 6]
│              TextView «جارویس» 13sp Bold ACCENT
│              TextView « · » 11sp TEXT_3
│              TextView modelShort 11sp Mono TEXT_3, singleLine, ellipsize END
│              spacer weight 1
│              TextView «1.8s» 11sp Mono TEXT_3 (only after turn.end)
├─ +6 → prose TextView 16sp Regular LH_TEXT TEXT, maxWidth 336dp, selectable,
│        TEXT_DIRECTION_ANY_RTL, padding 0
│        · while waiting for the first token: «فکر میکند…» 12sp TEXT_2 + caret
│        · while streaming: Md.render(text) + caret span (7.7)
├─ +10 → tool cards in arrival order (7.5)
└─ +10 → (prose continues after tools, same TextView is not reused: use one TextView per segment)
```

- **Segmented prose.** A turn that interleaves text and tools is a vertical list of segments
  (text, tool, text), not one TextView with cards inserted above it (which is what the code does
  today and it produces the wrong reading order). `Msg` keeps its `tools` list; the adapter walks
  `text/tool` in arrival order — implement with a single `List<Object> segments` on the message.
- Bold runs use 16sp Bold; inline code uses Mono 15sp on `WELL` with padding 3/1 and radius 8 (a
  `BackgroundColorSpan` cannot round corners — accept square inline code, or draw no background
  and use `TEXT_2` on ACCENT-free mono; prefer **no fill, colour `ACCENT`** for inline code).
- Fenced blocks are a separate child: `WELL`, radius 8, padding 12/10, mono 11sp `TEXT_2`,
  `maxLines(20)`, horizontal `HorizontalScrollView` only if the block has lines > 60 chars.
- The old footer (`در حال نوشتن…` / ttft pill / model pill / clock) is **deleted**.

### 7.5 Tool card — collapsed and expanded

Collapsed (default):
```
LinearLayout vertical, bg INK_2, radius R_CHIP, no border
padding 12/9/12/10   ← whole header row is the toggle target (min 36dp tall)
header row (36dp):
  [icon 16, colour OK|WARN|BAD]  mono-bold name 12sp TEXT (LTR)
  spacer weight 1
  mono 11sp TEXT_3: «3.9s» when done · «در حال اجرا» WARN when pending · «خطا» BAD when failed
  [chevron 16 TEXT_3, rotates 180° when expanded — optional, may be a static glyph]
body, +6, ONE line: mono 11sp TEXT_2, singleLine, ellipsize END
  = args (pretty-printed, whitespace-collapsed) · if failed, the first line of result in BAD
```

Expanded (tap):
```
body:
├─ args block:   WELL r8, padding 12/10, mono 11sp TEXT_2, maxLines 12, END, selectable, LTR
├─ +8 result:    WELL r8, padding 12/10, mono 11sp (TEXT_3 | BAD), maxLines 20, END, selectable
└─ +8 action row: text buttons «کپی» / «بستن» — 12sp Bold ACCENT, 32dp hit, no fill
```
- State is the **icon colour and the word**, never the card fill. No red card, no amber card.
- `tool.start` ⇒ card appears immediately with WARN + «در حال اجرا»; `tool.end` flips icon to
  OK/BAD and fills the duration. Elapsed time is computed at `tool.end`, not on every redraw
  (`elapsedMs()` today keeps growing in the collapsed label — fix by storing `endMs`).
- Nested inside the assistant block there is no second border: `INK_2` on `INK_0` is the contrast.

### 7.6 Empty state

| Element | Spec |
|---|---|
| Container | vertical, `weight 1` area, two spacers 1 : 1.2, side padding 20 |
| Headline | 24sp Bold LH_LOOSE TEXT, max 2 lines, centered |
| Sub | 14sp Regular LH_TEXT TEXT_2, +8, centered, max 2 lines |
| Label «شروع سریع» | 11sp Bold TEXT_3 LH_TIGHT, +28, aligned start |
| Group | `INK_2`, radius R_GROUP, 3 rows of **60dp** with `divider` between, +8 |
| Row | padding 14/10/14/10: [icon 20 ACCENT, marginEnd 12] + col(title 15sp Bold TEXT, sub 12sp TEXT_2 LH_SNUG) + chevron 16 TEXT_3 |
| Pressed | whole row ripple `press` (RippleDrawable over `INK_2`, radius R_GROUP on first/last row only — acceptable to use one shared rounded ripple) |
| Disconnected line | 11sp Mono BAD, +16, aligned start, only when `/health` failed |

### 7.7 Streaming caret

Append **one** `|` character (U+007C — verified present in Vazirmatn) to the `Spannable` at the
end of the last text segment, with a `ForegroundColorSpan(ACCENT)`; animate that span's colour
between `ACCENT` (alpha 255) and `alpha(ACCENT, 48)` every 530ms with
`ValueAnimator.setRepeatMode(REVERSE)`. Because the paragraph direction is RTL, the glyph lands at
the visual **left** end of the last line — which is the end of RTL text. Remove the span on
`turn.end`, not on the last delta.

Why not a `▍` block or a separate `View`: the block characters are absent from Vazirmatn (verified
cmap), and an inline span is the only thing that follows the text while it wraps without a
relayout per frame.

### 7.8 Quick-action chips

```
strip: HorizontalScrollView, height 44, padding start/end 16, top/bottom 6, no scrollbar
chip:  TextView 12sp Bold LH_TIGHT, padding 12/0 (minHeight 32), radius R_CHIP, singleLine
        default: fill INK_2, stroke 1 LINE, colour TEXT_2
        first chip (or the daemon's `primary`): fill a(ACCENT,16), stroke a(ACCENT,45), colour ACCENT
        pressed: fill INK_3
        disabled (adb down): colour a(TEXT_3,120), stroke a(LINE,120)
gap:   8 (marginEnd on each but the last)
```
Labels come from `/catalog.chips` verbatim. No emoji, no icon unless the daemon sends one; the
chip is text because the daemon owns the vocabulary.

### 7.9 Composer (input bar)

```
LinearLayout horizontal, bg INK_1, top hairline 1dp LINE
padding: start 12, top 8, end 12, bottom 12+insetBottom
children in order (RTL ⇒ first is rightmost):
 1. send:  IconButton 44×44, radius R_ROUND
           enabled  → fill ACCENT, glyph SEND 20dp ON_ACCENT
           empty    → fill INK_2,  glyph SEND 20dp a(TEXT_3,120)
           streaming→ fill INK_3, glyph STOP 20dp TEXT   (POST /session/{id}/cancel)
 2. input: EditText weight 1, marginStart 8, marginEnd 8
           minHeight 44, bg outline(INK_2, LINE, R_FIELD), padding 14/11
           15sp Regular TEXT, hint TEXT_3 «به جارویس بگو چه کار کن…», maxLines 4
           TEXT_DIRECTION_LOCALE, imeOptions ACTION_SEND, rawInputType TEXT|CAP_SENTENCES
 3. mic:   IconButton 44×44, ghost (no fill; ripple only), glyph MIC 20dp TEXT_2
           recording → fill INK_3, glyph STOP 20dp BAD, plus the header state line turns BAD
```
- Send sits at the **start** edge (right in RTL) on purpose: it is the most-tapped control and the
  owner holds the phone in one hand — the thumb reaches bottom-right, not bottom-left.
- Green send is deleted. Accent is the only filled control on the screen.
- Enter sends (already wired). While streaming the input stays editable; the daemon answers 409
  `busy`, so the send button is STOP until `turn.end`.

### 7.10 Sheet container (`Sheet.java`, new file)

```java
final class Sheet {
    static void show(Activity a, View content, boolean dragDismiss)
}
```
```
struct: root FrameLayout (added over the activity's content via a FrameLayout in MainActivity)
├─ scrim: View MATCH×MATCH, bg alpha(#000000,200), alpha 0→1 in 150ms, click = dismiss
└─ panel: LinearLayout vertical, bg INK_1, corners topLeft=topRight=R_SHEET
          stroke 1 LINE (all sides; only the top edge is visibly useful)
          layout_gravity BOTTOM, MATCH width
          ├─ handle zone: 12 top + View 36×4 round(LINE,999) centered + 12 bottom
          ├─ title row: 52dp, padding start/end 20
          │    title 15sp Bold TEXT (start) · icon button CLOSE 44dp (end)
          ├─ ScrollView (weight 1, content): padding start/end 20
          └─ bottom padding 16 (+ nav inset)
```
Behaviour: max panel height `min(content, 0.86 × screenH)`; slide `translationY` from height→0 in
220ms `DecelerateInterpolator(1.8)`; close 180ms `AccelerateInterpolator(1.5)`; back press closes;
`dragDismiss` moves the panel with the finger and settles at 35 % height or 900dp/s velocity (drop
it for the approval sheet — see below). No shadow: the scrim does the separating, and a 1px `LINE`
top edge gives the sheet an outline against `INK_0`.

**Approval / clarify:** same container, `dragDismiss = false`, and its two/three actions are
**tonal buttons** (44dp, radius R_FIELD) stacked full-width at the bottom: primary «یکبار» =
ACCENT tint `a(ACCENT,26)` fill + ACCENT 14sp Bold; «همیشه» = INK_2 + TEXT_2; «نه» = INK_2 +
BAD text. The command to approve is a `WELL` block, mono 11sp, `selectable`, max 8 lines. If drag
dismiss is ever enabled here, **dismiss must mean deny** — never approve by gesture.

### 7.11 Row group and rows (the one list language)

```
group: LinearLayout vertical, bg INK_2, radius R_GROUP, clipToOutline via GradientDrawable
row:   LinearLayout horizontal, minHeight 56, padding 14/10/14/10, gravity CENTER_VERTICAL
       ├─ optional: 8dp dot / 18dp radio / 20dp icon, marginEnd 12
       ├─ col weight 1: title 15sp (Bold if the row's own name) TEXT, singleLine END
       │                sub 12sp Regular TEXT_2 LH_SNUG, +2, singleLine END
       └─ trailing: mono 11sp TEXT_3 · state pill · 32dp ghost button (44dp hit via padding)
divider: View 1dp a(LINE,150), inset start 14 (full-bleed inside the group when the row has no
         leading icon)
selected/current row: bg INK_3 + View 3dp wide ACCENT at the start edge, full row height
pressed: RippleDrawable(press) over the row's current fill
```
No per-row borders (that is the current design's core ugliness: every row is its own box).

### 7.12 In-app snack (replaces `Toast`)

```
View MATCH×40, bg INK_3, radius R_CHIP, side margins 16, bottom margin 8 above the composer,
13sp Regular TEXT, padding 14/0, translateY 8→0 + alpha 0→1 in 160ms, auto-hide 2200ms.
One at a time; a new message replaces the text instead of stacking.
```
`Toast` is system-drawn in Roboto with an unsupported typeface — the last surviving Android
default surface. Basic `Toast` is kept only for permission-request fallout where no window exists.

### 7.13 Glyph additions to `Icon.java` (3 new ids, nothing else changes)

The spec uses three symbols the current set does not have. Same 24×24 grid, 2-unit stroke, round
caps, drawn with the existing `line()`/`arc()` helpers; ids continue from `MIC_OFF = 14`.

| id | Name | Geometry | Used by |
|---|---|---|---|
| 15 | `CHEVRON` | `line(15,5 → 9,12 → 15,19)` — points to the **end** side, i.e. left in RTL | row trailing affordance, empty-state rows |
| 16 | `CHEVRON_DOWN` | `line(7,9.5 → 12,15 → 17,9.5)` | tool card expand/collapse |
| 17 | `RELOAD` | `arc(12,12,6.5,-60,290)` + `line(18.5,7 → 18.5,3.5)` + `line(18.5,7 → 15,7)` | services sheet «restart» |

Two more shapes are **Views, not glyphs** (they are filled, and `Icon` only strokes):
`Ui.radio(ctx, boolean)` = 18×18 `FrameLayout`: ring `outline(transparent, selected?ACCENT:TEXT_3, 2dp)` + inner 8dp `ACCENT` dot when selected; and the status dot (§7.2).
Do **not** add a `CHEVRON_UP`: rotate the same glyph with `setRotation(180f)`.

---

## 8. Motion

Five animations exist. Everything else is instant. No animation may delay information.

| # | What | Property | Duration | Easing | Trigger / where |
|---|---|---|---|---|---|
| 1 | Streaming caret pulse | span colour alpha 255↔48 | 530ms, `REVERSE`, infinite | `LinearInterpolator` | `text.delta` until `turn.end` — `ChatAdapter` |
| 2 | New turn fade-in | alpha 0→1, `translationY` 4→0 | 160ms | `DecelerateInterpolator(1.8)` | row appears at the end of the list; guard with `msg.ts` so recycled rows don't re-fade on scroll — `ChatAdapter.getView` |
| 3 | Sheet open | panel `translationY` h→0, scrim alpha 0→1 | 220ms | `DecelerateInterpolator(1.8)` | `Sheet.show` |
| 4 | Sheet close | panel `translationY` 0→h, scrim 1→0 | 180ms | `AccelerateInterpolator(1.5)` | scrim tap, back press, `Sheet.hide` |
| 5 | Snack in/out | alpha + 8dp translate | 160ms / 140ms | Decelerate / Accelerate | `Ui.snack` |

Explicitly **not** animated: tool card expand/collapse (an instant height change — animating it
fights `ListView` measurement), list scroll, status dot colour change, model change confirmation,
thinking→streaming transition (the caret just appears), typing dots, skeletons, shimmer, page or
activity transitions, haptics. No `ObjectAnimator` loops other than #1; stop it in
`onPause`/`onDetachedFromWindow` or it will keep a view invalidating in the background.

---

## 9. RTL — the rules that actually bite in this codebase

1. **`LinearLayout` gravity has no horizontal meaning on the in-line axis.** In a *vertical*
   LinearLayout `setGravity(Gravity.START/END)` positions children horizontally and mirrors
   correctly (this is how the user bubble is aligned). In a *horizontal* LinearLayout the inline
   axis is consumed by child order + `layout_weight`; `gravity` only aligns the group if there is
   slack, and `layout_gravity`'s horizontal component is ignored. To push an element to the far
   edge, insert `View` with `weight 1` — never rely on gravity.
2. **`Gravity.LEFT` / `RIGHT` / `setPadding(l,t,r,b)` are absolute** and do not mirror. Use
   START/END and `setPaddingRelative`. One deliberate exception: a mono/LTR machine string is
   *content-left*, so `Ui.mono` setting `Gravity.LEFT` (absolute) is correct — `Gravity.START`
   resolves against the RTL layout and right-aligns a left-to-right string, which is the bug you
   see on multi-line tool output today.
3. **`setTextDirection` is not the layout direction.** `View.setLayoutDirection(RTL)` mirrors
   start/end, padding, drawables and scrollbars; it does **not** decide a TextView's paragraph
   base direction (that is `setTextDirection`, default `FIRSTSTRONG_LTR`). A reply that begins
   with a Latin word (`git status`, `api_server`) becomes an LTR paragraph with Persian inside —
   the punctuation and the caret jump to the wrong side. Prose and user bubbles must set
   `TEXT_DIRECTION_ANY_RTL`; machine fields (base URL, key, mono blocks) set
   `TEXT_DIRECTION_LTR`; the chat input uses `TEXT_DIRECTION_LOCALE`.
4. **Latin runs inside a Persian sentence need isolation.** `gemini-3.5-flash`, `1.8s`, `42 ms`,
   `127.0.0.1:8780` sitting inside an RTL paragraph reorder their trailing punctuation
   (`۱.۸s · flash-3.5-gemini` is the visible failure). Two fixes, both cheap: put machine strings
   in their **own** TextView with `TEXT_DIRECTION_LTR` (preferred, and what the author line does),
   or wrap them in `\u2066…\u2069` (LRI/PDI) inside a mixed span. In `Md.render`, wrap every
   inline-code span that contains no Persian letter in LRI/PDI.
5. **Ellipsis side.** `TruncateAt.END` truncates at the visual end of the paragraph — the left in
   RTL, the right in LTR. Correct for both Persian text and Latin ids. `TruncateAt.START` on a
   Persian string cuts the *beginning of the sentence*: forbidden. `TruncateAt.MARQUEE`: forbidden.
6. **Width caps.** A `WRAP_CONTENT` child of a horizontal LinearLayout measures against
   `AT_MOST(parent)`; `setMaxWidth` on the child is honoured by TextView but *not* by a wrapping
   container, so a bubble with a nested TextView can still overflow. Cap the bubble by padding the
   holder (56dp start), cap prose with `TextView.setMaxWidth(336dp)` where it is the direct child.
7. **`HorizontalScrollView` starts at the right in RTL** — good for chips. Do not call
   `scrollTo(0,0)` to "reset" it (that jumps to the wrong end); if a reset is ever needed, use
   `fullScroll(View.FOCUS_RIGHT)`.
8. **Dialogs and toasts ignore all of this.** `AlertDialog` with `Theme_Material_Dialog_Alert` draws
   Roboto, Material purple buttons, its own insets and its own bidi handling. Eleven
   `AlertDialog.Builder` call sites in ten `MainActivity` methods (`menu`, `sessionsDialog`,
   `pickModel` ×2, `setupWizard`, `askKey`, `settings`, `messageMenu`, `showAsk` ×2, `progress`)
   plus every `toast(...)` are replaced by §7.10/§7.12. Until that lands,
   at minimum theme them: `Ui.themeDialog(d)` sets the window background to `outline(INK_1, LINE,
   R_SHEET)`, the title/button typefaces to Vazirmatn, button colours to ACCENT/BAD, and
   `d.getWindow().setLayout(MATCH_PARENT, WRAP_CONTENT)`.
9. **`EditText` direction.** With `FIRSTSTRONG_LTR` the caret flips sides as soon as you type a
   Latin word ("open telegram"), which reads as a glitch. Use `TEXT_DIRECTION_LOCALE` for the chat
   input, `TEXT_DIRECTION_LTR` for key/URL fields, and never `TEXT_DIRECTION_RTL` on a field that
   accepts machine strings.
10. **Persian digits in an LTR context** still render as Persian shapes but take Latin advance
    widths inside a mono run — so never mix Persian digits into a mono block; keep mono purely
    Latin (see §4.3).
11. **Font scale is a layout event, not a style change.** `configChanges` in the manifest does not
    include `fontScale`, so the activity is recreated: the transcript survives via SSE replay and
    `prefs.session`, which is acceptable. Do not "fix" it by adding `fontScale` to `configChanges`
    — the views would keep stale paddings.
12. **Insets.** `fitsSystemWindows(true)` on the root is what works on Android 14 (the owner's
    device). On Android 15+ with `targetSdk 35` the system enforces edge-to-edge:
    `setStatusBarColor`/`setNavigationBarColor` become no-ops and `adjustResize` no longer lifts
    the composer. If the phone updates, switch to an `OnApplyWindowInsetsListener` on the root that
    pads the header by `insets.getInsets(systemBars()|ime()).top` and the composer by `.bottom`,
    and drop `fitsSystemWindows`. (Unverified on this device — see the report.)

---

## 10. Do not

1. Do not add a colour, a gradient, a shadow, a glow, or an emoji glyph. The palette is closed at
   16 tokens; symbols come from `Icon.java`.
2. Do not put the assistant answer in a bubble. The bubble belongs to the user only.
3. Do not nest a bordered container inside another bordered container (that is why the current
   tool card sits borderless inside a bordered bubble; with the bubble gone, the card is `INK_2`
   on `INK_0` and needs no border either).
4. Do not add a per-message icon row (copy/retry/speak). Long-press → sheet. Permanent per-message
   actions read as clutter after the third exchange.
5. Do not show the same fact twice (model in header pill *and* in every footer; state in header
   *and* in a note). One home per fact.
6. Do not use `AlertDialog`, `Toast`, `setDivider`, `setShadowLayer`, `View.setElevation`,
   `AppCompat`, or any new dependency. `GradientDrawable`, `RippleDrawable`, `Icon`, `Sheet` only.
7. Do not use `dp` for text or `sp` for spacing. Text is sp, everything else dp.
8. Do not set letter spacing, capitalise Persian, use italic, or fake bold.
9. Do not animate anything that isn't in §8, and do not animate a `ListView` row height.
10. Do not let a status colour fill an area bigger than a pill, and do not use two coloured pills
    side by side.
11. Do not add fixed heights to text containers; the app must survive font scale 1.3.
12. Do not use `Gravity.LEFT/RIGHT` except for LTR machine text (see §9.2).
13. Do not let the header state line report turn progress; that is the transcript's job.
14. Do not ship a screen with two primary buttons. One accent-filled control per screen.
15. Do not "improve" the layout while porting the style. Two commits: structure, then pixels.

---

## 11. Port map (mechanical, for whoever edits `Ui.java`)

| Old | New |
|---|---|
| `BG` | `INK_0` |
| `SURFACE` | `INK_1` |
| `SURFACE_2` | `INK_2` |
| `STROKE` | `LINE` |
| `TEXT` / `DIM` / `FAINT` | `TEXT` / `TEXT_2` / `TEXT_3` |
| `ACCENT` `#7FA6FF` | `ACCENT` `#A78BFA` (+ `ACCENT_HI`) |
| `USER` `#1D2A44` | `USER` `#2B2442` |
| `GREEN` / `AMBER` / `RED` | `OK` / `WARN` / `BAD` (same hexes) |
| `ON_ACCENT` `#06101F` | `ON_ACCENT` `#0D0A18` |
| `R_CHIP 12 / R_INPUT 14 / R_BUTTON 16 / R_BUBBLE 20` | `R_WELL 8 / R_CHIP 12 / R_FIELD 14 / R_GROUP 16 / R_BUBBLE 18 / R_SHEET 24 / R_ROUND 999` |
| `setLineSpacing(dp(3), 1f)` | `setLineSpacing(0, LH_SNUG\|LH_TEXT\|LH_LOOSE)` |
| `Ui.text(ctx, s, sp, colour, bold)` | keep, plus `Ui.title / Ui.prose / Ui.row / Ui.label / Ui.meta` wrappers so roles are named at call sites |
| `Ui.pill` | same recipe, new alphas (16 / 45) and `minHeight 32` |
| `Ui.iconButton` (`42dp`) | `44dp`, `R_ROUND` for send/mic, `R_CHIP` elsewhere |
| `Ui.ripple(alpha(tint,70))` | `press` = `alpha(colour,56)` |
| `true`/`false` `bg` division `USER` | unchanged (`USER` stays the user's bubble) |

## 12. Acceptance (what "done" looks like)

1. Assistant turns have no visible container; the transcript reads as prose with one author line
   per turn, and the user's turns are the only filled shapes.
2. Scrolling the transcript never shows a "flash" of a half-drawn border or a replayed fade-in.
3. No `AlertDialog`, no `Toast` and no Roboto glyph appears anywhere in the app.
4. A tool turn shows: WARN card + «در حال اجرا» → OK card + `3.9s`, in the same turn as the answer,
   with the prose continuing under the card (§7.4 segmented prose).
5. At font scale 1.3 and 360dp nothing overlaps, no text is clipped without an ellipsis, and the
   composer still shows send + input + mic at ≥ 44dp height each.
6. Send is the only accent-filled control; the microphone is a ghost; nothing is green except a
   state that is literally healthy.
7. Every sheet opens in ≤ 220ms, closes on scrim/back, and the same row language appears in all
   five sheets.
8. Nothing on screen uses a colour outside §3.1 (grep the diff for hex literals — there should be
   exactly the 16 in `Ui.java` and none elsewhere).

---

## 13. Delta against the pass already in the working tree (measured 2026-10-26 03:23)

`jarvis/…/Ui.java`, `ChatAdapter.java` and `MainActivity.java` are **already modified** in the
working tree by a parallel pass that cites this file. It follows the same direction (surface
ladder, no bubble on the answer, empty state, accent send) but it diverges from the contract below.
This section exists so the two can be reconciled instead of merged blindly. Unless stated, **this
spec wins**; where it says *either*, pick one and delete the other.

| # | Where | In the tree | Contract | Why it matters |
|---|---|---|---|---|
| 1 | `Ui.java` | `BG #0A0C11 / SURFACE #12151C / SURFACE_2 #191D26 / STROKE #232833 / STROKE_HI #2E353F`, `ACCENT #7C93FF` — hue 222°, no `INK_3`, no `WELL` | §3.1 (hue 248°, 16 tokens) | The ladder is correct in *shape* (ΔL 3.7 / 3.3 / 4.5 / 4.5) and passes WCAG (measured: `FAINT` on `SURFACE` 4.10, `ACCENT` on `SURFACE` 6.50, `USER` 13.6). Keeping the blue hue is a taste call; keeping *no* sunken `WELL` is not — code and tool output have nowhere to sit, which is why the tool card still needs a border. |
| 2 | `Ui.java` | `R_INPUT = 22`, `R_CARD = 18`, `R_BUBBLE = 20` | §5.3: 8 / 12 / 14 / 16 / 18 / 24 / 999 | Three radii 2dp apart (18/20/22) is exactly the "nobody can name it" problem this pass kills. A 22dp input with a 46dp send button is a *pill* composer; that is fine, but then the field, the button and the bubbles must all be pills, not 22/46/20. |
| 3 | `ChatAdapter.answer()` | 2dp accent rule at the start edge + `JARVIS` 10.5sp FAINT label, prose under it | §7.4: author line at the top (dot + «جارویس» 13sp ACCENT + model + `1.8s`), no full-height rule | Either is defensible; **both at once is not** — a rule per answer fences the transcript after ~15 turns. If the rule stays, the label must move into the same row as model/latency and the streaming state must be a caret, not a rule colour change. |
| 4 | `ChatAdapter.answer()` | streaming indicator `"● ● ●"` 9sp + `setLetterSpacing(0.3f)` | §7.7: inline `\|` span, alpha pulse | U+25CF is **not in Vazirmatn** (verified cmap), so those dots render from a fallback font at a different optical weight; and letter spacing is banned for Persian (harmless on punctuation, but the rule exists so nobody copies it onto text). |
| 5 | `ChatAdapter.getView` | `holder.setPadding(16, 4, 16, 4)` | §5.2: 16 after a user turn, 24 after an assistant turn | Uniform 4dp is why the transcript still reads as a list of equal rows rather than as turn groups. |
| 6 | `ChatAdapter.toolCard` | always shows args + result, `tool.elapsedMs()` still live | §7.5: collapsed (1 line) / expanded (`WELL` blocks), frozen `endMs` | The timer keeps ticking in a finished card, and a long result permanently occupies the screen — worse at font scale 1.3. |
| 7 | `ChatAdapter.answer()` footer | `model · ttft · total · clock` in 10.5sp FAINT | deleted; author line carries model + ttft | Four metadata items per turn is the noise the owner is reacting to. |
| 8 | `ChatAdapter` | `15.5f` text sizes (`Ui.text(..., 15.5f, …)`) | scale is 15 / 16 only | Off-grid sizes are how a type scale decays. |
| 9 | `MainActivity.emptyState()` | `android.R.drawable.ic_menu_compass` tinted ACCENT, 4 full-width cards, trailing 10.5sp caption | §7.6: `Icon.java` glyph, one `INK_2` group of 3 rows | A Holo system drawable has a different stroke weight from the 2px/24 grid glyphs — it reads as a sticker; and four separate bordered cards reintroduce the "wall of boxes". |
| 10 | `MainActivity.providersDialog()` | `"➕ افزودن پرووایدر دلخواه"` | §3.5: no emoji, any symbol comes from `Icon.PLUS` | U+2795 is not in Vazirmatn; the system emoji font renders it in colour, which breaks the one-accent rule on the very screen that is supposed to prove the palette. (Same bug exists as `"➕ گفتگوی تازه"` in `sessionsDialog`.) |
| 11 | `MainActivity.buildShell` | `modelChip = Ui.pill(this, "مدل", …)` | §7.1: the pill shows a model id or «مدل پیشفرض» | A pill that literally says "Model" is the kind of placeholder that ships. |
| 12 | `MainActivity` | `setState("مینویسد…")`, `setState("ابزار: " + name)`, `setState("میفرستم…")`, `setState(brief(text))` | §7.1: header state = connection/permission only | Nine call sites still repaint the header on every event; the header visibly churns during a tool turn. Move them to the transcript (the caret, the tool card, the author line). |
| 13 | `MainActivity.buildShell` | send 46dp filled ACCENT, mic/menu via `Ui.iconButton` (42dp inside) | §7.9: 44 / 44 / 44, ghost mic, ACCENT send, STOP while streaming | Three button sizes in one bar; and there is still no stop affordance while a turn streams. |
| 14 | `MainActivity.buildShell` | `list.setVisibility(GONE)` toggled by `updateEmptyState()` (called from `notifyList`) | keep — this part is correct | Noted so it is not "fixed" into something else. |

Not addressed by the in-flight pass at all (still to do): the eleven `AlertDialog` sites, every
`Toast` call, the five sheets (§7.10/§6c–f), the segmented prose order (§7.4/checklist 10), the
`Gravity.LEFT` fix in `Ui.mono` (§9.2), the LRI/PDI isolation in `Md.render` (§9.4), and the
Persian-digit helper (§4.3).
