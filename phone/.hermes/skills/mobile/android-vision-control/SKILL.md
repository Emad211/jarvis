---
name: android-vision-control
description: Control the phone via Shizuku rish and VLM vision.
---

# Android Vision Control (Termux + Shizuku + VLM)

Drive an Android phone end-to-end from Termux: capture the screen, "read" it with a
vision model via the local Antigravity bridge, then tap/type/swipe by pixel coordinates.

## Environment
- rish wrapper: `/data/data/com.termux/files/home/.local/bin/rish`
- Must export before every call: `export RISH_APPLICATION_ID=com.termux`
- Shell is Android's `shell` user: uid=2000, groups include input, adb, sdcard_rw,
  inet, readproc, uhid. SELinux context `u:r:shell:s0`.
- Vision gateway: OpenAI-compatible HTTP on `http://127.0.0.1:8790/v1`
  (the `agy_bridge.py` server). API key `antigravity-local`. No new key needed.

## Core commands (rish -c "...")
```
rish -c "screencap -p /sdcard/name.png"     # full-screen shot (1080x2400)
rish -c "uiautomator dump /sdcard/ui.xml"   # accessibility tree -> text + bounds
rish -c "input tap X Y"                      # tap at pixel
rish -c "input swipe X1 Y1 X2 Y2 [ms]"       # swipe
rish -c "input text 'string'"                # type into focused field
rish -c "input keyevent 83"                  # open notifications shade
rish -c "am start -n <pkg>/.<Activity>"      # launch app/activity
rish -c "pm list" ; rish -c "settings put secure <k> <v>"
```
After screencap/dump, `cp /sdcard/...  ~/...` to pull into Termux (sandbox is opaque).

## Vision loop (read any on-screen content)
1. Screenshot: `rish -c "screencap -p /sdcard/s.png" && cp /sdcard/s.png ~/s.png`
2. Base64-encode + POST to `http://127.0.0.1:8790/v1/chat/completions`
   - model: `gemini-3.8-flash-low` (fast) or `-high` (better)
   - content = [{type:text}, {type:image_url, image_url:{url: "data:image/png;base64,..."}}]
   - auth: `Authorization: Bearer antigravity-local`
3. The bridge extracts the image, saves it to `~/.hermes/vision/`, and runs
   `agy -p <prompt-with-[Image saved at: path]> --dangerously-skip-permissions`.
   Model returns structured description (English or Persian as asked).

## OCR / UI-reading precedence
- **Prefer uiautomator dump** for tapping targets: it gives exact `bounds="[x1,y1][x2,y2]"`
  per element; tap centre `((x1+x2)/2, (y1+y2)/2)`. Read labels from `text=` OR
  `content-desc=` (Instagram puts visible text in content-desc).
- **Use VLM** when content is image/video/rendered text not in the dump (captions,
  photos, "what is on this reel", Persian text in images).

## Pitfalls
- `/data/data/<other app>` is **blocked** by Android 11+ sandbox even at uid 2000.
  Cannot read another app's DB/private files. Drive the app UI instead.
- Not root; no documented escalation on non-rooted device.
- Check the foreground app first via `uiautomator dump` root node `package=`
  before tapping, so you don't tap a stale app.
- `input keyevent` takes **numeric codes only** (BACK=4, enter=66, open-notifications=83).
- Long-press: `input swipe X Y X Y 2000`.
- VLM calls take 15-90s; use `gemini-3.8-flash-low` for speed, generous timeouts.
- `agy` headless needs `--dangerously-skip-permissions` or it auto-denies reading files.