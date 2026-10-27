# The device bridge (`ir.emad.jarvis`, `127.0.0.1:8791`)

The Jarvis app runs a tiny HTTP server (`Bridge.java`) so the phone's shell can drive the device
without Shizuku and without pairing wireless adb. It is loopback-only and has no auth, the same
trust model as `jarvisd` and gwctl: anything that can reach `127.0.0.1:8791` is already the user.

```
POST /act   {"intent": "...", "args": {...}}   ->  {"ok": bool, "intent": ..., ...fields}
GET  /ping                                     ->  {"ok": true, "version": 1, "a11y": bool}
```

`a11y` says whether the accessibility service is bound. A few intents (`tap`, `swipe`, `key`,
`type`, `clipboard_set`, `volume`, `screenshot`) need it and answer `{"ok": false,
"error": "a11y_off"}` without it; the rest work from the app's own process.

`jarvis_fast.py` calls this bridge first for every intent that has one, and only falls back to the
adb/rish lanes when the bridge cannot answer — measured on this phone, that is the difference
between ~100 ms and ~3 s, and between "flip one toggle" and "install Shizuku and pair adb".

## Intents

| intent | args | answer | needs a11y |
|---|---|---|---|
| `info` | — | `{model, sdk, battery}` | no |
| `screen` | — | `{text}` — the focused window's package + visible text | no |
| `screenshot` | `{name?, inline?}` | `{path}` or `{b64}` | yes |
| `open_app` | `{name}` | `{ok}` — resolved against installed packages | no |
| `open_url` | `{url}` | `{ok}` | no |
| `dial` | `{number}` | `{ok}` | no |
| `tap` | `{x, y}` or `{text}` | `{ok}` | yes |
| `swipe` | `{x1, y1, x2, y2, ms?}` | `{ok}` | yes |
| `key` | `{name}` — `home`/`back`/`recents`/`notifications`/`quicksettings`/`lock` | `{ok}` | yes |
| `type` | `{text}` | `{ok}` | yes |
| `volume` | `{direction}` — `up`/`down`/`mute` | `{ok}` | yes |
| `clipboard_get` | — | `{text}` — **always empty**, see below | yes |
| `clipboard_set` | `{text}` | `{ok}` | yes |
| `clipboard_focus` | `{seed?}` | `{text, diag}` — the real clipboard read | no |
| `notif_list` | — | `{items[]}` from the notification listener | no |
| `notif_clear` | `{id}` | `{ok}` | no |
| `calendar_today` | — | `{items[]}` | no |
| `calendar_range` | `{days}` | `{items[]}` | no |
| `contacts_search` | `{query}` | `{items[]}` | no |
| `sms_recent` | `{limit?}` | `{items[]}` | no |
| `apps_list` | — | `{items[]}` | no |

## Reading the clipboard: why it needs an activity

Since Android 10 the clipboard is readable **only** by the app that currently has window focus or
by the default input method. A background service is neither, so both `clipboard_get` (an
accessibility-service read) and `termux-clipboard-get` return an empty string — that is the
platform's rule, not a bug in either one.

`clipboard_focus` is the way in: `Clip.java` is a translucent activity with no animation and no
recents entry that takes window focus for one frame, reads the clipboard, parks the text in the
app's private `files/clipboard.txt` and finishes. `Bridge` polls for the token file and returns
the text, so the whole round trip is one HTTP call.

Measured on this phone (2026-10-27), with text put on the clipboard by **Termux** — a different
UID, i.e. a genuine cross-app copy:

| step | ms |
|---|---|
| `clipboard_focus` read (Termux wrote the clip) | 54 |
| `jarvis-fast clipboard` read, whole CLI | 167 |
| Persian write `{"text":"…"}` through `clipboard_set` | 88 |
| read back what was written | 158 |

`diag` is what the focused read actually saw, and it is the reason an empty answer can be trusted:

```
tries=1 | ms=56 | focused=false | has=true | items=1 | len=0 | seeded=false | mime=text
```

`has=false` or `len=0` means the clipboard really is empty; `diag=timeout` means the activity never
answered (the console app is not running) and the caller must not claim the clipboard is empty.
`focused=false` is expected: the read usually answers on the first attempt, before the
`onWindowFocusChanged` callback lands — the *window* is already focused at that point, which is
what the platform checks.

Two earlier failures this design exists to avoid:
- a read queued from `onCreate` runs **before** the window is focused, and a one-shot guard then
  made that early miss final — hence the retrying probe (40 ms × 50, ends as soon as the clipboard
  answers);
- `setPrimaryClip` through the accessibility service returns `true` for a reason nobody can see,
  so a write is only believed after a read gives it back.

`seed` is diagnostic only: it lets the activity put text on the clipboard itself, which tests the
read path with no accessibility service and no second app.
