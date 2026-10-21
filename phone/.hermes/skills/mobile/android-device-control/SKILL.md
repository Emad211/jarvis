---
name: android-device-control
description: Use when driving Android apps via Shizuku.
---

# Android device control via Shizuku + rish (from Termux)

## When to use
The user wants you to operate the phone itself: open an app, navigate inside it, read what's on screen, tap, or take a screenshot. Prefer doing it directly over giving the user manual steps.

## Prerequisites (setup state, check first)
- Shizuku app installed and started (Wireless debugging on, started from inside Shizuku).
- `rish` present at `/data/data/com.termux/files/home/.local/bin/rish`.
- Every rish call needs `export RISH_APPLICATION_ID=com.termux` (otherwise it errors "RISH_APPLICATION_ID is not set").
- Verify with `./rish -c id`; it should show uid=2000(shell).

## Core commands
- Launch app: `./rish -c "am start -n <pkg>/<activity>"`. Activity names are guessed; if one fails, use `-a android.intent.action.VIEW -d <url>` or check the foreground app with uiautomator. Instagram works with `com.instagram.android/.activity.MainTabActivity`.
- Read the screen: `./rish -c "uiautomator dump /sdcard/ui.xml"`, then `cp /sdcard/ui.xml ~/ui.xml`. Parse `text`/`content-desc` plus `bounds="[x1,y1][x2,y2]"` with a regex. Print only the first N matches to keep output small.
- Tap: `./rish -c "input tap X Y"`, using the center of the bounds box.
- Type text into the focused field: `./rish -c "input text <string>"` (e.g. an Instagram search box). Tap the field first, wait ~2s, then type. **ASCII only**: `input text` silently drops Persian/Arabic characters.
- Persian/non-ASCII text (verified on Instagram DM composer): `printf 'سلام' | termux-clipboard-set`, tap the field, then `./rish -c "input keyevent 279"` (KEYCODE_PASTE). Confirm with `uiautomator dump` that the field's `text=` now holds the Persian string before pressing Send.
- Instagram DM flow (verified): profile → "Message" button opens the thread; composer is `resource-id=com.instagram.android:id/row_thread_composer_edittext` (its `bounds` were around [170,2101][581,2242] on 1080×2400, not the coordinate guessed from the Message button).
- Swipe/scroll: `./rish -c "input swipe X1 Y1 X2 Y2 DURATION_MS"`. Swiping up on a video/reel area (e.g. 540,1700 → 540,700) advances between full-screen reels; swiping down (540,1000 → 540,1750) collapses comments back to the reel. A long caption needs a direct tap on it to expand.
- Screenshot: `./rish -c "screencap -p /sdcard/name.png"`, then `cp` to `~/name.png` and send it with `MEDIA:/absolute/path`.

## Procedure
1. Confirm the foreground package with `uiautomator dump` before any tap. `package=` in the XML shows what's on screen.
2. Launch the target app, sleep 3–5s, dump again, and verify the package.
3. Tap by bounds, then dump again to verify the result before the next tap.
4. Screenshot only after the target screen is confirmed.

## Reading rendered/image content with a VLM (not just the UI tree)
`uiautomator dump` only exposes text nodes; captions, photos, "what is on this reel", and Persian text inside images are invisible to it. For those, send the screenshot to the local Antigravity vision bridge:
- Bridge: OpenAI-compatible HTTP on `http://127.0.0.1:8790/v1` (the `agy_bridge.py` server, key `antigravity-local`). No new key needed.
- POST `chat/completions` with `content = [{type:text}, {type:image_url, image_url:{url:"data:image/png;base64,..."}}]`, model `gemini-3.8-flash-low` (fast) or `-high` (better).
- The bridge saves the image to `~/.hermes/vision/` and calls `agy -p <prompt with [Image saved at: path]> --dangerously-skip-permissions` (the flag is required or agy auto-denies reading the file). Returns a structured description in English or Persian.
- VLM calls take 15–90 s. Use `gemini-3.8-flash-low` + a generous timeout.

## Antigravity as a Hermes model provider
The bridge doubles as a real provider, not just a vision helper.
- Switch Hermes to it: `hermes chat -q "<query>" -m gemini-3.8-flash-low --provider antigravity`
- Model IDs are FULL (`gemini-3.8-flash-low|medium|high`). `--provider custom` misroutes to OpenRouter and 400s "not a valid model ID". Do not use the `/gemini` quick command (it uses `custom`).
- Bridge internals, the two bugs it had (image stripping + missing SSE), and restart steps: see `references/antigravity-bridge-internals.md`.

## Pitfalls
- Coordinates come from the last dump only. If another app (e.g. Telegram) is in front, taps land in the wrong app. Always check `package=` first.
- Instagram bottom tabs on a 1080×2400 screen: Profile about (972, 2198). The "following" count on the profile page is about (893, 470). Search tab center ≈ (756, 2197), search edit field ≈ (490, 140).
- Instagram post/reel captions frequently do NOT appear in `text=`. Read them from the `content-desc="..."` attribute instead; a caption node often reads `<content-desc="<caption> …">` where `…` means it's truncated. Tap directly on the caption area to expand it (truncation becomes full text, sometimes as `text=` and revealing hashtags), which also opens the comment thread. Hashtags appear as `text="#tag"` nodes near the bottom of the reel. Like/comment/repost counts come through as `text=` ("The like number is N", "Reposted N times").
- Kill-card lists/queries can be left in the wrong view (e.g. a comment thread open). Dump first, and use `input key` sparingly — `input key BACK` is NOT supported (returns "Unknown command"); navigate with taps/swipes instead.
- Termux python can read the PNG header to confirm validity (`\x89PNG`, width/height in bytes 16–24).
- Don't rely on plain Termux `dumpsys`, `pm list packages`, or `settings get`; they fail with permission errors. Use rish.

## Opening Gemini and asking it questions (tested on SM-A536E)
- Gemini is a separate app: `com.google.android.apps.bard`. If it is missing, open the Play Store page with `am start -a android.intent.action.VIEW -d 'market://details?id=com.google.android.apps.bard'`, pick "Google Play Store" in the chooser, tap Install, and wait about 90s.
- Launch it with `monkey -p com.google.android.apps.bard -c android.intent.category.LAUNCHER 1`. It opens in `googlequicksearchbox/...voice.robin.main.MainActivity`.
- The model picker shows the newest model, here "Argon High". Tap "Let's go" to reach the chat. The input is at the bottom; the Microphone and Send buttons are identified by content-desc.
- Type a question with `input text`, then press Send by tapping its `content-desc="Send"` bounds. `input keyevent 66` (Enter) did not send it.
- Plain spaces work in `input text "..."` (keep the question in one string, no `+` or `%3F` URL encoding; those get typed literally). To clear a prefilled or wrong draft, send `input keyevent 67` in a loop (about 300 presses).
- Gemini shows suggestion chips (e.g. "Automate top-mic voice notes", "Examining …") just above the input box, at a similar y-coordinate. Before tapping the input, re-dump and take the bounds of the `Ask Gemini` editable node. Tapping a chip starts an unrelated run.
- A pull-down notification shade (`mCurrentFocus=...NotificationShade`) hides the chat. Check `dumpsys window | grep -m1 mCurrentFocus` and close the shade (back-tap on the screen or press Home) before reading an answer.
- Answers can arrive as several consecutive runs ("Redirected current run", "typing"). Keep dumping until the final text is stable (about 2–5 min for deep research), and read the answer from the `text=` nodes.
- Gemini can show a "Not now" feedback prompt. Dismiss it with the bounds of its `text="Not now"` node.
- Deep research answers take several minutes. Poll with `uiautomator dump` every 60s and read `text=` nodes. Long answers can be read in full from the XML file.
- Coordinates from a dump go stale quickly. Dump again just before each tap.

## Tool-call hygiene
- A long one-line `rish` command with nested quotes and `$()` was blocked as malformed. Split it into a dump step, a parse step (a short Python script with `re` on the XML), and a tap step.

## User preference (Emad)
- Act on the phone directly; don't hand him steps he could be doing himself.
- Treat Gemini's answers as leads, not facts. Verify key claims with web_search/web_extract before reporting them, and say which parts were unconfirmed.
- Keep status updates short and in Persian; don't pad the reply with long lists of possibilities.
- Send results as images via MEDIA:, not text descriptions.
- Don't send voice messages unless he sends voice first or explicitly asks.
