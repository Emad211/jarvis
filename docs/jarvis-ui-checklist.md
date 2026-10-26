# Jarvis UI — implementation checklist (ordered, 25 items)

Companion to `docs/jarvis-visual-spec.md`. Every item names the file and the widget it touches,
and every item is independently shippable. Order = **ugliness removed ÷ effort**: the first five
items change no layout at all and already make the app stop looking like Android 2015.

Rules for whoever executes this:
- One item per commit. Build with `pwsh -File jarvis\build.ps1 -Install` before ticking it.
- Never import a colour hex outside `Ui.java` (grep the diff: `#` should appear 16 times, once per token).
- After phase B and again after phase D, run the acceptance list in the spec (§12).
- This app is 360dp wide at font scale 1.3 for a one-handed Persian speaker; if a change only looks
  right at 411dp, it is not done.

---

## Phase A — tokens and type (no layout change, biggest perceived jump)

- [ ] **1. `Ui.java`: replace the palette + radii constants.**
  `BG/SURFACE/SURFACE_2/STROKE/TEXT/DIM/FAINT/ACCENT/USER/ON_ACCENT` → `INK_0/INK_1/INK_2/INK_3/
  WELL/LINE/TEXT/TEXT_2/TEXT_3/USER/ACCENT/ACCENT_HI/ON_ACCENT` (16 total, spec §3.1). `R_*` →
  `R_WELL 8 / R_CHIP 12 / R_FIELD 14 / R_GROUP 16 / R_BUBBLE 18 / R_SHEET 24 / R_ROUND 999`.
  Keep temporary aliases for the old names so nothing else has to change yet.
  *Done when:* the app builds, screenshots show a violet-cast ladder instead of three greys.

- [ ] **2. `Ui.java`: fix line height and add role factories.**
  Delete `setLineSpacing(dp(3), 1f)` from `text()`. Add `LH_TIGHT .78 / LH_SNUG .86 / LH_TEXT .96 /
  LH_LOOSE 1.0` and `lh(TextView, float)` calling `setLineSpacing(0, mult)` (Vazirmatn's natural
  line box is 1.5625 em — measured, spec §4.1). Add `title/prose/row/label/meta/author` factories
  that already carry size+weight+LH+colour. Add `fa(long)` for Persian digits.
  *Done when:* a 16sp Persian paragraph is 25dp per line and no call site passes a raw `dp(3)`.

- [ ] **3. `Ui.java`: retune the shared widgets.**
  `pill` → padding 10/6, `minHeight 32`, chip alphas 16/45, 12sp Bold. `iconButton` → 44dp,
  `R_ROUND` for send/mic and `R_CHIP` elsewhere, ripple `alpha(colour,56)`. `mono()` → keep
  `Gravity.LEFT` (absolute is correct for LTR machine text; `START` right-aligns it in RTL).
  Add `Ui.rule(ctx, colour, widthDp)` (a plain `View`) for the 2–3dp accent marker.
  *Done when:* every pill on screen is ≥ 32dp tall and the ripple is a tint, not a wash.

- [ ] **4. `Ui.java`: `themeDialog(AlertDialog)` stopgap.**
  One helper applied at all 11 `new AlertDialog.Builder(...)` call sites in `MainActivity`: window
  background `outline(INK_1, LINE, R_SHEET)`, title/message/buttons to Vazirmatn, button text
  ACCENT / BAD, `setLayout(MATCH_PARENT, WRAP_CONTENT)`, list divider `a(LINE,150)`.
  This is a **stopgap**: item 18 + 19–25 delete these dialogs entirely.
  *Done when:* no dialog on screen renders Roboto or a purple Material button.

- [ ] **5. `Ui.java` + `MainActivity.java`: in-app snack instead of `Toast`.**
  `Ui.snack(Activity, CharSequence)` = `INK_3` bar, radius 12, 13sp, above the composer, 2200ms
  (spec §7.12). Replace all `toast(...)` calls; keep `Toast` only for the permission-request path.
  *Done when:* `grep -c "Toast.makeText" MainActivity.java` is 1 (permissions only).

## Phase B — the transcript (the largest visual mass on screen)

- [ ] **6. `ChatAdapter.java`: the assistant answer loses its bubble.**
  `bubble` for `Msg.ASSISTANT` → no background, no border, no padding, full column width; add the
  author row (dot 6dp ACCENT/TEXT_3/BAD + «جارویس» 13sp Bold ACCENT + « · » + model 11sp Mono
  TEXT_3 + spacer + `1.8s`), then 6dp, then prose 16sp Regular `LH_TEXT` with
  `setMaxWidth(336dp)` and `TEXT_DIRECTION_ANY_RTL`.
  *Done when:* a long answer reads as text, not as a box, and the user's turn is the only filled shape.

- [ ] **7. `ChatAdapter.java`: delete the footer pill row.**
  Remove the `در حال نوشتن… / ttft / model / clock` row; the model and first-token time now live in
  the author row (item 6). Nothing replaces it.
  *Done when:* an assistant turn contains exactly one row of metadata.

- [ ] **8. `ChatAdapter.java`: user bubble.**
  `USER` fill (no border), radius `R_BUBBLE 18`, padding 14/10/14/10, text 15sp `LH_TEXT`,
  holder gravity `END` **and** holder `paddingStart 56` as the 84 % width cap (spec §7.3 — a
  `maxWidth` on a wrapping child is ignored).
  *Done when:* a two-line user turn never touches the opposite gutter.

- [ ] **9. `ChatAdapter.java`: tool card, collapsed + expanded.**
  `INK_2` (no border), radius 12, padding 12/9/12/10; header row 36dp = state icon 16 + name 12sp
  Bold Mono LTR + spacer + state word/duration 11sp Mono TEXT_3; one-line args below
  (mono 11sp TEXT_2, singleLine END); tap toggles an expanded body of two `WELL` blocks (args
  `maxLines 12`, result `maxLines 20`, `selectable`, LTR) plus «کپی»/«بستن» text buttons.
  Add `Msg.ToolEv.endMs` and show `tool.elapsedMs()` frozen at end (today it keeps ticking).
  Needs `Icon.CHEVRON_DOWN` (spec §7.13) for the expander.
  *Done when:* a `terminal` turn shows WARN+«در حال اجرا» then OK+`3.9s`, and the card never
  exceeds 60 % of the screen height at font scale 1.3.

- [ ] **10. `Msg.java` + `ChatAdapter.java`: render a turn as ordered segments.**
  Add `List<Object> segments` to `Msg` (append `<StringBuilder>` text and `ToolEv` in arrival order
  in `MainActivity.onEvent`); the adapter walks segments so prose↔tool order matches the daemon's
  event order (today all cards are forced above the text).
  *Done when:* a turn that says "let me check… `terminal` … here it is" renders in that order.

- [ ] **11. `ChatAdapter.java`: vertical rhythm + fade-in guard.**
  Row spacing by the *previous* row's role: 16dp after a user turn, 24dp after an assistant turn
  (spec §5.2), replacing the flat `padding 3` on the holder. Fade-in (alpha 0→1, 4dp up, 160ms) only
  for rows whose `msg.ts` is newer than the last drawn one, so scrolling never replays it.
  *Done when:* fast scrolling shows no flicker and turns group correctly.

- [ ] **12. `ChatAdapter.java`: streaming caret.**
  Append one `|` (U+007C — verified present in Vazirmatn) as a `ForegroundColorSpan(ACCENT)` at the
  end of the last text segment and pulse its alpha 255↔48 every 530ms; remove on `turn.end`; cancel
  the animator in `onDetachedFromWindow`. Replace the literal `"…"` placeholder with
  «فکر میکند…» 12sp TEXT_2.
  *Done when:* the caret sits at the left end of the last RTL line and disappears exactly once.

## Phase C — the shell

- [ ] **13. `MainActivity.buildShell` — header.**
  Height `wrap_content` (≥ 64dp + status inset), padding 16/12+inset/8/12, `INK_1`, then a 1dp
  `LINE` view. Title «جارویس» 20sp Bold (not "JARVIS"), 8dp dot, state line 12sp = **connection
  state only**; model pill shows the short model id (fallback «مدل پیشفرض») — never the word «مدل»;
  menu `IconButton` 44dp TEXT_2 at the end.
  *Done when:* `setState()` is only called with connection/permission strings and the header stops
  changing on every `tool.start`/`text.delta` (move those to the transcript).

- [ ] **14. `MainActivity.buildShell` — composer.**
  Order send → input → mic (RTL ⇒ send rightmost, one-hand reach), 44dp buttons, gap 8, padding
  12/8/12/12+inset, `INK_1` with a top hairline; input `minHeight 44`, `INK_2` + `LINE` + radius 14,
  15sp, `maxLines 4`, `TEXT_DIRECTION_LOCALE`; send = ACCENT fill + `ON_ACCENT` glyph, greyed when
  empty, becomes STOP (`INK_3` + TEXT glyph) while streaming and posts `/session/{id}/cancel`;
  mic ghost, `BAD` fill while recording.
  *Done when:* the send button is the only filled control on screen and it is on the thumb side.

- [ ] **15. `MainActivity.fillChips` — chips strip.**
  Strip 44dp (chip 32 + 6/6), padding 16, gap 8; chip = `INK_2` + 1dp `LINE`, 12sp Bold TEXT_2,
  radius 12; first chip accent-tinted (16/45); chips hidden while the transcript is empty (the
  empty-state rows replace them, spec §6a).
  *Done when:* the strip never wraps, never clips a label, and disappears on a fresh session.

- [ ] **16. `MainActivity` — empty state.**
  New `emptyState()` view inserted in the list area when `messages.isEmpty()`: headline 24sp Bold,
  sub 14sp TEXT_2, «شروع سریع» label, one `INK_2` group of 3 rows (60dp each) fed by
  `/catalog.chips`, plus a disconnected line (11sp Mono BAD) when `/health` failed.
  *Done when:* first open looks intentional at 360dp × 1.3 with no chips strip.

- [ ] **17. `MainActivity.onCreate` — window chrome.**
  `getWindow().setBackgroundDrawable(new ColorDrawable(INK_0))` before `setContentView` (kills the
  white flash), nav bar `INK_0`, status bar transparent on API 35+, keep
  `fitsSystemWindows(true)` for now and leave the §9.12 note in a comment.
  *Done when:* cold start shows no white frame.

## Phase D — sheets (delete every dialog)

- [ ] **18. New `Sheet.java` — container only.**
  `Sheet.show(Activity, View content, boolean dragDismiss)`: root `FrameLayout`, scrim
  `alpha(#000000,200)` fading 150ms, panel `INK_1` with top radius 24 + 1dp `LINE`, handle 36×4,
  title row 52dp with a 44dp close, `ScrollView` body padded 20, max height 86 %, slide 220ms /
  close 180ms, back-press closes (spec §7.10). No shadow, no Material class.
  *Done when:* an empty test sheet opens/closes with a drag handle and correct RTL padding.

- [ ] **19. `MainActivity.pickModel` → model + reasoning sheet.**
  Replace both `pickModel` dialogs: radio row group from `/catalog.models` (title + mono
  `provider/model` sub), reasoning pills (بدون/کم/متوسط/زیاد) from `/catalog.reasoning`, apply
  immediately via `/session/{id}/model` and `/session/{id}/reasoning`, footer state line. No apply
  button.
  *Done when:* switching model never opens a dialog and the header pill updates in place.

- [ ] **20. `MainActivity.setupWizard` + `askKey` → providers sheet.**
  Row group from `/setup/state.providers`: label 15sp Bold, `key_env` mono 11sp, state pill
  («کلید دارد» OK / «بی کلید» WARN); tapping a row expands an inline field (LTR, password, mono
  13sp) + «ذخیره و تست» tonal button + result line (OK/BAD + mono ms) + «صفحهٔ کلید» underlined
  ACCENT. Never display an existing key; remove the `"  ✓"` string (the glyph is not in the font).
  *Done when:* a wrong key produces an inline red line under its row, not a toast.

- [ ] **21. `MainActivity.sessionsDialog` → session list sheet.**
  Row group from `/sessions`: title 15sp Bold oneLine, sub 12sp «۱۲ نوبت · ۵ دقیقه پیش» (Persian
  digits, relative time computed once), trailing model mono, 8dp ACCENT dot when `busy`, current
  session = `INK_3` + 3dp ACCENT start rule, PLUS icon button in the title row for a new session.
  *Done when:* the current session is identifiable without reading ids.

- [ ] **22. `MainActivity.menu` → control sheet (services).**
  Replace the `menu` dialog with sheet (e): «اتصال» (jarvisd `/health`: warm/version/sessions),
  «گیتوی» (gwctl `/status`: overall/health, stopped units, model, uptime in Persian words), «سرویسهای hermes-svc»
  (gwctl `/services`: dot + state word + 32dp restart/stop buttons; **stop asks with a confirm row**),
  «تازهسازی» text button, footer with the endpoint. Add `Api.gwctlBase` (default
  `http://127.0.0.1:8777`, pref `gwctl_base`) — loopback, no new listener. Needs `Icon.RELOAD`
  and `Icon.CLOSE` (spec §7.13).
  *Done when:* a stopped `agy` service is visible and restartable without opening Termux.

- [ ] **23. `MainActivity.settings` → rows inside the control sheet.**
  Base URL field (LTR, mono, radius 14) + TTS toggle row (trailing 8dp OK dot + «روشن/خاموش») +
  «گفتگوی تازه»، «گفتگوهای قبلی»، «پرووایدرها» rows that open items 21/20/19. Delete the dialog and
  keep the same writes to `prefs`/`/session`.
  *Done when:* no settings value is reachable only through a system dialog.

- [ ] **24. `MainActivity.showAsk` → approval / clarify sheet.**
  Same container with `dragDismiss = false`; the gated command in a `WELL` block (mono 11sp,
  selectable, max 8 lines) + three stacked tonal buttons (یکبار ACCENT tint / همیشه INK_2 /
  نه INK_2 + BAD text); clarify variant = question 15sp + one input + «بفرست». Dismiss = deny.
  *Done when:* a real gated command can be approved and denied from the sheet, and back-press denies.

- [ ] **25. `MainActivity.messageMenu` → message sheet, then the full verification pass.**
  Long-press sheet: «کپی» / «بازخوانی صوتی» (+ «تلاش دوباره» if cheap). Then run spec §12 end to
  end on the phone: tool turn, streaming caret, font scale 1.3, 360dp, RTL greps
  (`Gravity.LEFT` only in `Ui.mono`, no `setPadding(` with 4 args on an asymmetric view),
  `AlertDialog` count = 0, `Toast` count ≤ 1, hex literals outside `Ui.java` = 0.
  *Done when:* all eight acceptance points pass on the A53 and `jarvisd` still reports connected.

---

### Do not start with

- A new theme/`styles.xml`, a navigation drawer, a sidebar, tabs, or a "redesign" of the IA. The IA
  is fine; the surfaces are not.
- Animations (they are items 11/12/18, not step 1).
- The services sheet (item 22) before items 1–9: it is the most work for the least screen area.

### If there is only time for five items

1, 2, 6, 13, 18 — tokens, line height, no bubble on the answer, a real header, and one sheet
container. That is the difference between "Android dialog demo" and "console", and it touches
three files.
