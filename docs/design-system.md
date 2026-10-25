# Design system — Hermes Control + Jarvis

The contract for both apps. Dark-only, RTL Persian, no imagery, no motion beyond ripples.
Values are the ones actually in `Ui.java` after this pass — if you change one, change both files.

## What this system is not

Rejected on purpose, and the reason:

| Rejected | Why |
|---|---|
| Gradients (any) | They fake depth and make a status screen look like a landing page. Hierarchy comes from border + text weight. |
| Glow / outer shadows | No elevation model exists here; a glow is a gradient by another name. |
| Decorative rings, gauges, arcs | A 76dp ring that encodes "0–100%" with no scale is decoration. A 8dp dot + one word is information. |
| Multi-colour chips | Five palettes in one card reads as a toy. Colour on this screen means *state*, nothing else. |
| Colour as decoration | If a colour is not GREEN/AMBER/RED status, it is ACCENT or it is grey. No exceptions. |
| Pure black `#000000` | Halation on OLED at large text; `#0B0F17` has the same darkness without the ringing. |

## Colour

Two neutral families (background, surface) and one accent. That is the whole palette.

| Token | Hex | Use |
|---|---|---|
| `BG` | `#0B0F17` | Window background, screen behind cards |
| `SURFACE` | `#131A26` | Card fill, nav strip, dialogs |
| `SURFACE_2` | `#1B2433` | Inputs, tool cards, anything nested inside a card |
| `STROKE` | `#253147` | The 1px border on every surface, and dividers |
| `TEXT` | `#EEF2F8` | Primary text (15.2:1 on SURFACE) |
| `DIM` | `#9AA7BC` | Secondary text, subtitles (6.4:1) — minimum for body copy |
| `FAINT` | `#64718A` | Metadata only: timestamps, byte counts, key names (3.1:1 — never for a sentence) |
| `ACCENT` | `#8AA4FF` | The one interactive colour: buttons, links, the primary model id |
| `GREEN` | `#4ADE80` | Status: healthy / connected / ok |
| `AMBER` | `#FBBF24` | Status: degraded / waiting / needs attention |
| `RED` | `#F87171` | Status: down / failed / destructive |
| `ON_ACCENT` | `#0B1022` | Text on an ACCENT-filled button |

Rules: GREEN/AMBER/RED may only appear as (a) a dot, (b) an icon, (c) text that names the
state. Never as a surface wash wider than a pill. Removing `TEAL` was part of this pass —
it was a fifth semantic colour with no meaning of its own.

Contrast floor: body text ≥ 4.5:1 (`DIM`), metadata ≥ 3:1 (`FAINT`). ACCENT on SURFACE is
7.0:1 — safe for a 12.5sp bold model id.

## Type

One family: Vazirmatn (bundled), fallback `Typeface.SANS_SERIF`. Mono is `Typeface.MONOSPACE`
for logs, ids and tool payloads only.

| Role | Size | Weight | Colour |
|---|---|---|---|
| Screen title (header) | 22sp | bold | TEXT |
| Section heading | 13sp | bold | DIM |
| Card headline (status) | 17–19sp | bold | TEXT |
| Row title | 14sp | bold | TEXT |
| Row subtitle | 11.5sp | regular | DIM |
| Body / chat | 15sp | regular | TEXT |
| Label / chip | 12–12.5sp | bold | ACCENT or DIM |
| Metadata (timestamp, ms) | 10.5–11sp | regular | FAINT |
| Mono (logs) | 11sp | regular | DIM |

`setIncludeFontPadding(false)` everywhere; `lineSpacing` 2dp. Anything that can overflow gets
`oneLine()` — an ellipsis beats a reflowed row.

## Spacing

4dp grid. Only these values are used:

`4` icon-to-label · `8` between siblings · `12` inside a compact row · `16` screen gutter and
card padding · `18` hero padding · `24` between major blocks.

Screen gutter is **16dp on both sides** (`Screen.java`, `MainActivity` header, Jarvis header).
A header padded 20/14 against a 16/16 card is the kind of 1dp mismatch that reads as sloppiness
without anyone being able to name it.

## Radii

One scale, no ad-hoc numbers:

`12` chips, icon buttons · `14` inputs · `16` buttons · `20` cards, chat bubbles · `22` dialogs.

Nothing is a circle except a status dot and the count badge.

## Surfaces

A card is: `SURFACE` fill, `STROKE` 1px border, radius 20, padding 16/14. No shadow, no
gradient, no inner glow. If something needs to stand out, it gets **more whitespace or
bigger text**, not a colour.

A row inside a card: padding 10dp vertical, 4dp horizontal, divider `alpha(STROKE,200)` at
0.6dp between rows — never a border around each row.

## Status row (replaces the hero ring)

The dashboard headline is one quiet row, not a gauge:

```
●  ربات فعال است                       ← 8dp dot (state colour) + 17sp bold
   google_ai_studio/gemini-3.8-flash   ← 12.5sp ACCENT bold, LTR, oneLine
   تلگرام: وصل · 7 دقیقه               ← 11.5sp DIM, single line
```

The dot is the only coloured element. "How healthy" is expressed in words, not in an arc.

## Tool card (Jarvis)

Inline in the answer bubble, `SURFACE_2` fill, radius 12, padding 10/8, **no border** (it sits
inside a bordered bubble — two borders nest badly):

```
⚡ tool_name                    42 ms      ← 15dp icon (GREEN/AMBER/RED), 11.5sp bold mono name
{"path": "…"}                              ← 10.5sp mono DIM, clamped to 240 chars
result text…                               ← 10.5sp mono, FAINT (or RED on failure)
```

State is the icon colour and the icon only. No coloured card background.

## Chat bubble (Jarvis)

| Part | Fill | Border | Radius |
|---|---|---|---|
| User | `USER` `#1D2A44` | none | 20 |
| Jarvis | `SURFACE` | `STROKE` 1px | 20 |
| Note (system/error) | `SURFACE` | `STROKE` 1px | 20, RED **text** only — never a red fill |

Bubble padding 14/11/14/10. Bubbles are `WRAP_CONTENT` with 8dp side margins: the ragged edge
is what makes a long conversation scannable. A full-width bubble turns the transcript into a
wall. User turns sit on the end edge, Jarvis on the start edge.

Markdown links render in ACCENT at 13% tint underline — not a second hue.

## Chips / pills (both apps)

A pill is metadata, not a badge. One recipe:

```
outline(fill = alpha(colour, 18), border = alpha(colour, 70), radius 12)
text 11sp bold in the same colour, padding 9/4
```

Colour rules: ACCENT for "in progress / live", `FAINT` for neutral facts (model id, timing,
byte counts), GREEN/AMBER/RED only when the chip *names a state*. Never a filled pastel block —
four filled pills in a footer was the console's biggest colour problem.

## Input bar (Jarvis)

Row, padding 10/6/10/12, on `BG`:

| Element | Spec |
|---|---|
| Mic / stop | iconButton 42dp, ACCENT; RED when recording |
| Input | `SURFACE_2` fill, `STROKE` 1px, radius 14, padding 14/11, 15sp, hint `FAINT`, `maxLines` 4 |
| Send | iconButton 42dp, GREEN |

Gaps 8dp. The input is weight(1f); the two buttons keep their fixed 42dp.

## Header status row (both apps)

Same shape in both apps — this is the one element that must look identical:

```
●  عنوان                              ← 8–10dp dot (FAINT/GREEN/AMBER/RED) + 17–22sp bold TEXT
   خط وضعیت · شناسه مدل                ← 11.5–12sp DIM, oneLine, second half ellipsizes
```

Dot left of the title, in RTL that means the dot is on the right. The title hugs the start
edge; a spacer (weight 1f) takes the slack **after** the trailing icon button, so the action
sits next to the content instead of across the screen.

## What a change must not do

- Add a token. Eleven colours are already the limit.
- Add a background image, gradient or animation to a surface.
- Introduce a colour that means "pretty".
- Change layout/IA while changing style — one or the other per commit.

