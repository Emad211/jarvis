---
name: jarvis-fast
description: Fast, deterministic phone control (open apps, tap, screenshot, UI tree, volume, brightness, wifi) through the loopback adb bridge. Use this instead of rish/input/uiautomator whenever the task is a normal device action.
---

# jarvis-fast — hands for the agent

`rish -c "…"` costs about **3 seconds per call** on this phone; `jarvis-fast` runs the same
kind of action over loopback adb in **~90–300 ms** and prints a JSON verdict. If an action
is in the table below, use `jarvis-fast`; reach for `rish` only for something it cannot do.

Always run it from the terminal tool as:

```sh
jarvis-fast <intent> '<json args>'
```

Output is one JSON line: `{"ok": true, "intent": "...", "ms": 137, "detail": "...", "media": "..."}`.

## Intents

| intent | args | does |
|---|---|---|
| `open_app` | `{"name": "telegram"}` | launches a package; accepts Persian names and fuzzy ids |
| `screenshot` | `{}` | saves a PNG, returns `media: /media/<file>.png` |
| `ui` | `{}` | dumps the accessibility tree as `text @ x,y` lines (best way to "see" a screen) |
| `tap` | `{"x":540,"y":1200}` or `{"x":..,"y":..,"hold":600}` | taps/long-presses |
| `swipe` | `{"dir":"up"}` or `{"x1":..,"y1":..,"x2":..,"y2":..}` | scrolls |
| `key` | `{"name":"back"}` | back/home/recents/enter/delete/notifications/sleep/volume_up/volume_down/mute/play/next/previous |
| `type` | `{"text":"سلام"}` | types into the focused field (Persian goes through the clipboard) |
| `clipboard` | `{}` or `{"text":"..."}` | reads or sets the clipboard |
| `volume` | `{"action":"up","steps":2}` | volume up/down/mute |
| `brightness` | `{"percent":40}` | sets screen brightness and reads it back |
| `wifi` | `{"action":"on"}` | Wi-Fi on (turning it off needs `allow_offline`) |
| `battery` | `{}` | level, charging state, temperature |
| `info` | `{"what":"time"｜"storage"｜"uptime"}` | clock/disk/uptime |
| `open_url` | `{"url":"https://…"}` | opens a link |
| `dial` | `{"number":"+98…"}` | opens the dialer with the number |
| `notifications` | `{}` | opens the notification shade (the *act*; reading them is below) |
| `notif_list` | `{}` | the phone's current notifications: app, title, text |
| `notif_clear` | `{}` | dismisses all notifications |
| `calendar_today` | `{}` | today's events (title, time, location) |
| `calendar_range` | `{"days":7}` | events from today up to N days ahead |
| `contacts_search` | `{"query":"علی"}` | contacts by name/number fragment |
| `sms_recent` | `{"limit":10}` | the last N inbox messages |
| `apps_list` | `{}` | launchable apps, matched by name or package |
| `list` | — | prints every supported intent |

## Reading the phone's own data

`notif_list`, `calendar_today`, `calendar_range`, `contacts_search`, `sms_recent` and
`apps_list` answer from the app itself: fast (~60–180 ms) and no accessibility service needed.
A refusal looks like `ok:false` with `needs: android.permission.READ_CALENDAR` or
`notification_listener` — that is the **user's** toggle, one time: منو → کنترل گوشی →
دسترسی‌ها. Do not try to route around it with adb, and never claim data you did not receive.

## How to drive the phone well

1. `jarvis-fast ui` first — it tells you which text is on screen and where to tap.
   Coordinates from a dump go stale after any layout change, so tap immediately.
2. Prefer `open_app` over tapping a launcher icon.
3. After a state-changing action, verify with `ui` or `battery`/`info` instead of assuming.
4. `screenshot` returns a path under `/media/` that the app can display; use it when the
   question is visual ("what does this chart say") and pair it with the vision toolset.
5. Anything destructive (deleting files, uninstalling, payments) is **not** in this tool —
   use the terminal tool so the approval gate applies.

## Failures

`{"ok": false, "detail": "…"}` means it did not happen. Common reasons: the package name
matched nothing (`open_app`), the screen is off (`tap`/`type` do nothing), or adb lost its
loopback connection — re-run `adb connect 127.0.0.1:5555` through the terminal tool.
