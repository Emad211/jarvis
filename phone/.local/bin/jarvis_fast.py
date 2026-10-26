#!/data/data/com.termux/files/usr/bin/env python
"""jarvis_fast — deterministic device actions, no model in the loop.

Every function here maps one intent to one shell command through Shizuku's `rish`
(uid 2000), so an action that the user asked for in plain words can run in tens of
milliseconds instead of waiting for a language model. `jarvisd` exposes
``POST /fast {"intent": "...", "args": {...}}`` on top of :func:`run`.

Design rules
------------
* Only additive / reversible actions. Anything destructive stays in the agent path,
  where it goes through the approval gate.
* A command that "succeeds" but changes nothing is a lie: each intent checks the
  effect where the platform allows it (brightness read-back, package existence, file
  size) and reports what it actually saw in ``detail``.
* Never print or log secrets; the clipboard reader is the only thing that can return
  user text, and it is explicitly requested by the user's own tap.
"""

from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import time
import urllib.request
import uuid
from typing import Any, Dict, List, Optional, Tuple

RISH = "rish"
ADB = "adb"
ADB_ADDR = "127.0.0.1:5555"

# ---------------------------------------------------------------- the app's own hands --
# Jarvis can drive the phone itself through an AccessibilityService (see jarvis/Bridge.java).
# That lane is tried first because it has no prerequisites beyond one system toggle, while adb
# needs wireless debugging paired and Shizuku needs a second app started by hand. Everything
# still falls back to adb, then rish, so an older APK or a disabled service changes nothing but
# the latency.
BRIDGE = "http://127.0.0.1:8791"
_BRIDGE_STATE = [0.0, False]   # [checked_at, ready]
# intents the bridge can do; `screenshot` is excluded because its file lives in the app's
# private cache, which Termux cannot read — that one keeps using adb.
BRIDGE_INTENTS = {"open_app", "open_url", "dial", "tap", "swipe", "key", "type",
                  "clipboard", "volume", "info", "ui", "screenshot",
                  "battery"}
# `notifications` is deliberately NOT here: Bridge.java has no such action (measured —
# `{"intent":"notifications"}` answers `{"ok":false,"error":"unknown_intent"}`), so it only
# added a wasted probe before falling through to adb. If the app ever grows that action,
# put it back and drop the shell lane below.
BRIDGE_NAMES = {"ui": "screen", "battery": "info"}


def bridge_ready(refresh: bool = False) -> bool:
    """Cheap /ping against the app. A *positive* answer is cached for 20 s; a negative one only
    for 2 s, because the app may simply be starting and a long cache would strand every action
    on the slow lanes for a whole turn."""
    now = time.time()
    ttl = 20.0 if _BRIDGE_STATE[1] else 2.0
    if not refresh and now - _BRIDGE_STATE[0] < ttl:
        return _BRIDGE_STATE[1]
    ok = False
    try:
        with urllib.request.urlopen(BRIDGE + "/ping", timeout=1.5) as r:
            info = json.loads(r.read().decode("utf-8", "ignore"))
        # `ok` says the HTTP server answers; `a11y` says the accessibility service is bound and
        # can actually act. A bridge with a11y off only costs a probe and answers `a11y_off`, so
        # it counts as not ready — measured today: the service was off and every intent silently
        # fell through to adb, which looks like "the bridge is slow" instead of "a11y is off".
        # Negative answers are cached for 2 s only, so flipping the toggle recovers fast.
        ok = info.get("ok") is True and info.get("a11y", True) is not False
    except Exception:  # noqa: BLE001
        ok = False
    _BRIDGE_STATE[0], _BRIDGE_STATE[1] = now, ok
    return ok


def bridge(intent: str, args: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Run one intent through the app. None means "not handled here, use the shell lanes"."""
    if not bridge_ready():
        return None
    name = BRIDGE_NAMES.get(intent, intent)
    payload = {"intent": name, "args": dict(args)}
    if intent == "open_app":
        query = str(args.get("query") or args.get("package") or args.get("app") or "")
        pkg = args.get("package") or (resolve_package(query) if query else "")
        if not pkg:
            return None
        payload["args"] = {"package": pkg}
    if intent == "notifications":
        # reuse the global-action lane that already works instead of waiting for an APK update
        payload["intent"] = "key"
        payload["args"] = {"name": "notifications"}
    if intent == "clipboard":
        # the bridge has two separate actions; the intent here is one with optional text
        payload["intent"] = "clipboard_set" if str(args.get("text") or "") else "clipboard_get"
    if intent == "screenshot":
        # the PNG lands in the app's private cache, so ask for the bytes inline and write them
        # where the rest of the pipeline expects them (~/shots/jv_*.png)
        payload["args"] = {"name": "jv_%s.png" % uuid.uuid4().hex[:10], "inline": True}
    elif intent in ("ui", "info", "battery"):
        payload["args"] = {}
    if intent == "key" and "name" not in args:
        keymap = {"home": "home", "back": "back", "recents": "recents", "recent": "recents",
                  "notifications": "notifications", "settings": "quicksettings",
                  "lock": "lock", "power": "lock"}
        payload["args"] = {"name": keymap.get(str(args.get("key", "")).lower(), "home")}
    try:
        req = urllib.request.Request(BRIDGE + "/act",
                                     data=json.dumps(payload).encode("utf-8"),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=12) as r:
            res = json.loads(r.read().decode("utf-8", "ignore"))
    except Exception:  # noqa: BLE001
        return None
    if not res.get("ok"):
        # a11y off or the action failed: let the shell lanes try, they may still work
        return None
    if intent == "clipboard" and "text" not in args:
        if not str(res.get("text") or "").strip():
            # The app answered `ok` with an empty clipboard. That is Android's background
            # read rule, not an empty clipboard (measured: the same is true of
            # termux-clipboard-get). Do NOT report success — let the shell lane try, and if
            # it is empty too the intent tells the truth (`clipboard_unreadable`).
            return None
    out = {"ok": True, "intent": intent, "source": "bridge"}
    if intent == "ui":
        out["detail"] = res.get("text", "")
        out["text"] = res.get("text", "")
    elif intent == "screenshot":
        blob = res.get("b64") or ""
        if not blob:
            return None  # no bytes came back: let the adb lane try instead
        name = payload["args"]["name"]
        shots = os.path.expanduser("~/shots")
        try:
            os.makedirs(shots, exist_ok=True)
            path = os.path.join(shots, name)
            with open(path, "wb") as fh:
                fh.write(base64.b64decode(blob))
        except (OSError, ValueError):
            return None
        out["path"] = path
        out["media"] = "/media/" + name
        out["detail"] = name
    elif intent == "battery":
        out["detail"] = "%s%%" % res.get("battery", "?")
    elif intent == "info":
        for k in ("model", "sdk", "battery"):
            if k in res:
                out[k] = res[k]
        out["detail"] = "%s · API %s" % (res.get("model", ""), res.get("sdk", ""))
    elif intent == "clipboard" and "text" in res:
        out["text"] = res.get("text", "")
        out["detail"] = res.get("text", "")
    else:
        out["detail"] = "ok"
    return out
SHOT_DIR = os.path.expanduser("~/shots")
TMP = "/data/local/tmp"
# Which shell lane the last sh() call really used. Several intents have no bridge action,
# so their result has to name the lane honestly instead of returning `source: null`.
_SH_LANE = ["adb"]
ENV = dict(os.environ, RISH_APPLICATION_ID="com.termux", PATH=os.path.expanduser("~/.local/bin")
           + ":" + os.environ.get("PATH", ""))

# Loopback adb answers in ~90 ms; `rish` costs ~3 s just to reach Shizuku. The fast path
# therefore prefers adb and only falls back to rish when adbd is not reachable.
_state: Dict[str, Any] = {"adb": None, "checked": 0.0}

# Friendly names people actually say, mapped to packages. Anything not here falls back to
# a fuzzy match against the installed package list.
ALIASES = {
    "telegram": "org.telegram.messenger",
    "تلگرام": "org.telegram.messenger",
    "chrome": "com.android.chrome",
    "کروم": "com.android.chrome",
    "camera": "com.sec.android.app.camera",
    "دوربین": "com.sec.android.app.camera",
    "gallery": "com.sec.android.gallery3d",
    "گالری": "com.sec.android.gallery3d",
    "settings": "com.android.settings",
    "تنظیمات": "com.android.settings",
    "phone": "com.samsung.android.dialer",
    "dialer": "com.samsung.android.dialer",
    "تلفن": "com.samsung.android.dialer",
    "messages": "com.samsung.android.messaging",
    "پیامک": "com.samsung.android.messaging",
    "youtube": "com.google.android.youtube",
    "یوتیوب": "com.google.android.youtube",
    "maps": "com.google.android.apps.maps",
    "نقشه": "com.google.android.apps.maps",
    "instagram": "com.instagram.android",
    "اینستاگرام": "com.instagram.android",
    "whatsapp": "com.whatsapp",
    "واتساپ": "com.whatsapp",
    "termux": "com.termux",
    "ترموکس": "com.termux",
    "jarvis": "ir.emad.jarvis",
    "جارویس": "ir.emad.jarvis",
    "hermes": "ir.emad.hermesctl",
    "هرمس": "ir.emad.hermesctl",
}

_pkg_cache: Dict[str, Any] = {"at": 0.0, "list": []}


def _adb_ready(refresh: bool = False) -> bool:
    """Is the loopback adb bridge usable? Cached for 30 s; reconnects on demand."""
    now = time.time()
    if not refresh and _state["adb"] is not None and now - _state["checked"] < 30:
        return bool(_state["adb"])
    ok = False
    try:
        p = subprocess.run([ADB, "-s", ADB_ADDR, "shell", "true"], capture_output=True, text=True,
                           timeout=6, env=ENV)
        ok = p.returncode == 0
    except (FileNotFoundError, subprocess.TimeoutExpired):
        ok = False
    if not ok:
        try:
            subprocess.run([ADB, "connect", ADB_ADDR], capture_output=True, text=True,
                           timeout=8, env=ENV)
            p = subprocess.run([ADB, "-s", ADB_ADDR, "shell", "true"], capture_output=True, text=True,
                               timeout=6, env=ENV)
            ok = p.returncode == 0
        except (FileNotFoundError, subprocess.TimeoutExpired):
            ok = False
    _state.update(adb=ok, checked=now)
    return ok


def sh(command: str, timeout: float = 12.0) -> Tuple[int, str]:
    """Run a shell-uid command as fast as the device allows. Returns (exit code, output)."""
    if _adb_ready():
        try:
            p = subprocess.run([ADB, "-s", ADB_ADDR, "shell", command], capture_output=True, text=True,
                               timeout=timeout, env=ENV)
            out = (p.stdout or "") + (p.stderr or "")
            if p.returncode == 0:
                _SH_LANE[0] = "adb"
                return 0, out.strip()
            # adb's own failure (device gone) is worth one rish retry; a remote exit
            # code is a real answer, so pass it back as-is.
            if out.strip() and "device" not in out.lower():
                _SH_LANE[0] = "adb"
                return p.returncode, out.strip()
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass
    try:
        p = subprocess.run([RISH, "-c", command], capture_output=True, text=True,
                           timeout=timeout, env=ENV)
        out = (p.stdout or "") + (p.stderr or "")
        _SH_LANE[0] = "rish"
        return p.returncode, out.strip()
    except subprocess.TimeoutExpired:
        return 124, "timeout"
    except FileNotFoundError:
        return 127, "neither adb nor rish is usable"


def grab(remote_path: str) -> Optional[bytes]:
    """Read a file produced by the shell uid (world-readable under /data/local/tmp)."""
    if _adb_ready():
        try:
            p = subprocess.run([ADB, "-s", ADB_ADDR, "exec-out", "cat", remote_path], capture_output=True,
                               timeout=20, env=ENV)
            if p.returncode == 0 and p.stdout:
                return p.stdout
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass
    try:
        with open(remote_path, "rb") as fh:
            return fh.read()
    except OSError:
        return None


def packages(refresh: bool = False) -> List[str]:
    if not refresh and _pkg_cache["list"] and time.time() - _pkg_cache["at"] < 600:
        return _pkg_cache["list"]
    # `--user 0` matters: a bare `pm list packages` fails intermittently on this phone with
    # "Shell does not have permission to access user 150" — and it exits 0 with no output,
    # so the failure is silent. Pin the user and treat an empty answer as a failure.
    names: List[str] = []
    for cmd in ("pm list packages --user 0", "pm list packages"):
        code, out = sh(cmd, timeout=25)
        names = sorted({line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line})
        if names:
            break
    if names:
        _pkg_cache.update(at=time.time(), list=names)
    return names


def resolve_package(query: str) -> Optional[str]:
    """Best guess for what the user means: alias, exact package, then substring."""
    q = (query or "").strip()
    if not q:
        return None
    low = q.lower()
    if low in ALIASES:
        return ALIASES[low]
    names = packages()
    if q in names:
        return q
    for name in names:
        if name.lower() == low:
            return name
    # prefer a package whose last segment matches the query ("youtube", "chrome")
    for name in names:
        tail = name.split(".")[-1].lower()
        if tail == low:
            return name
    hits = [n for n in names if low in n.lower()]
    if hits:
        return sorted(hits, key=len)[0]
    return None


def _ok(intent: str, started: float, detail: str, **extra: Any) -> Dict[str, Any]:
    extra.setdefault("source", _SH_LANE[0])
    return dict(ok=True, intent=intent, ms=int((time.time() - started) * 1000),
                detail=detail, **extra)


def _err(intent: str, started: float, detail: str, **extra: Any) -> Dict[str, Any]:
    extra.setdefault("source", _SH_LANE[0])
    return dict(ok=False, intent=intent, ms=int((time.time() - started) * 1000),
                detail=detail, **extra)


def _focus(want: Any = (), tries: int = 1, delay: float = 0.15) -> str:
    """The window that currently has input focus, e.g. `com.android.settings/…Settings`.

    With ``want`` it polls: `cmd statusbar expand-notifications` returns *before* the shade
    takes focus (measured: expand 166 ms, NotificationShade focused ~200 ms later), so a
    single read right after the command says "not opened" when it in fact opened.
    """
    def read() -> str:
        code, out = sh("dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        return out.split("mCurrentFocus=")[-1].strip() if code == 0 else ""

    text = read()
    for _ in range(max(0, tries - 1)):
        if want and any(w in text.lower() for w in want):
            break
        time.sleep(delay)
        text = read()
    return text


def _volume_index() -> str:
    """Music-stream volume index, straight from the settings provider (~50 ms)."""
    code, out = sh("settings get system volume_music_speaker")
    return out.strip() if code == 0 else ""


# What we can read back to prove an intent worked. `focus` compares the focused window,
# `volume` compares the music-stream index. Measured: focus 180–195 ms, volume ~50 ms.
VERIFY = {"open_app": "focus", "dial": "focus", "notifications": "focus", "volume": "volume"}


def _readback(kind: str) -> str:
    return _volume_index() if kind == "volume" else _focus()


def _verified(intent: str, args: Dict[str, Any], before: str) -> Optional[bool]:
    """Did the device state actually change the way the intent promised?"""
    kind = VERIFY.get(intent)
    if not kind:
        return None
    if kind == "volume":
        after = _volume_index()
        if not after:
            return None                    # could not read: honest "unknown", not "yes"
        return bool(before) and after != before
    want: Any = {"dial": ("dialer", "dialtacts", "contacts", "incall"),
                 "notifications": ("notification", "shade")}.get(intent)
    if want is None:
        pkg = str(args.get("package") or "")
        if not pkg:
            query = str(args.get("name") or args.get("app") or args.get("query") or "")
            pkg = resolve_package(query) or ""
        want = (pkg.lower(),) if pkg else ()
    if not want:
        return None
    after = _focus(want, tries=3)
    return any(word in after.lower() for word in want)


# --------------------------------------------------------------------------- intents --
def open_app(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    name = str(args.get("name") or args.get("app") or args.get("query") or "")
    pkg = resolve_package(name)
    if not pkg:
        return _err("open_app", started, "برنامه‌ای با این نام پیدا نشد: " + name)
    code, out = sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1", timeout=15)
    # monkey prints "No activities found to run" when the package has no launcher entry
    if "No activities found" in out or code != 0:
        return _err("open_app", started, f"{pkg} آیکون اجرا ندارد")
    return _ok("open_app", started, f"{pkg} باز شد", package=pkg)


def screenshot(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    os.makedirs(SHOT_DIR, exist_ok=True)
    name = "jv_%d.png" % int(time.time() * 1000)
    path = os.path.join(SHOT_DIR, name)
    remote = TMP + "/jv_shot.png"
    code, out = sh(f"screencap -p {remote}", timeout=20)
    data = grab(remote) if code == 0 else None
    if not data:
        return _err("screenshot", started, "گرفتن تصویر نشد: " + out)
    with open(path, "wb") as fh:
        fh.write(data)
    sh(f"rm -f {remote}")
    return _ok("screenshot", started, f"{len(data) // 1024} KB",
               media="/media/" + name, path=path)


def tap(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    try:
        x, y = int(args.get("x", -1)), int(args.get("y", -1))
    except (TypeError, ValueError):
        return _err("tap", started, "مختصات نامعتبر")
    if x < 0 or y < 0:
        return _err("tap", started, "x و y لازم است")
    hold = int(args.get("hold") or 0)
    sh(f"input tap {x} {y}" if not hold else f"input swipe {x} {y} {x} {y} {hold}")
    return _ok("tap", started, f"({x}, {y})")


def swipe(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    direction = str(args.get("dir") or "").lower()
    geom = sh("wm size")[1]
    m = re.search(r"(\d+)x(\d+)", geom)
    w, h = (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)
    cx, cy = w // 2, h // 2
    moves = {
        "up": (cx, int(h * 0.75), cx, int(h * 0.25)),
        "down": (cx, int(h * 0.25), cx, int(h * 0.75)),
        "left": (int(w * 0.8), cy, int(w * 0.2), cy),
        "right": (int(w * 0.2), cy, int(w * 0.8), cy),
    }
    if direction in moves:
        x1, y1, x2, y2 = moves[direction]
    else:
        try:
            x1, y1, x2, y2 = (int(args[k]) for k in ("x1", "y1", "x2", "y2"))
        except (KeyError, TypeError, ValueError):
            return _err("swipe", started, "جهت یا مختصات نامعتبر")
    sh(f"input swipe {x1} {y1} {x2} {y2} {int(args.get('ms') or 250)}")
    return _ok("swipe", started, f"{direction or 'custom'}")


KEYS = {"back": 4, "home": 3, "recents": 187, "enter": 66, "delete": 67, "tab": 61,
        "menu": 82, "notifications": 83, "power": 26, "sleep": 223, "volume_up": 24,
        "volume_down": 25, "mute": 164, "play": 85, "next": 87, "previous": 88,
        "search": 84, "camera": 27, "escape": 111}
PERSIAN_LABELS = {"back": "برگشت", "home": "خانه", "recents": "برنامه‌های اخیر",
                  "enter": "اینتر", "delete": "پاک کردن", "notifications": "نوار اعلان",
                  "sleep": "خاموش کردن صفحه", "volume_up": "صدا بیشتر",
                  "volume_down": "صدا کمتر", "mute": "بی‌صدا", "play": "پخش/توقف",
                  "next": "بعدی", "previous": "قبلی", "search": "جستجو",
                  "power": "دکمهٔ پاور", "tab": "تب", "menu": "منو"}


def key(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    name = str(args.get("name") or args.get("key") or "").lower()
    code = KEYS.get(name)
    if code is None:
        try:
            code = int(name)
        except ValueError:
            return _err("key", started, "کلید ناشناخته: " + name)
    rc, out = sh(f"input keyevent {code}")
    if rc != 0:
        return _err("key", started, out)
    return _ok("key", started, PERSIAN_LABELS.get(name, str(code)))


def type_text(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    text = str(args.get("text") or "")
    if not text:
        return _err("type", started, "متنی نبود")
    if text.isascii():
        safe = text.replace(" ", "%s").replace("'", "")
        sh(f"input text '{safe}'")
        return _ok("type", started, text[:60])
    # Persian/Arabic or symbols: `input text` is ASCII-only, so paste through the clipboard
    clipboard_set(text)
    time.sleep(0.25)
    sh("input keyevent 279")  # KEYCODE_PASTE
    return _ok("type", started, "چسباند از کلیپ‌بورد: " + text[:60])


def clipboard_set(text: str) -> Tuple[int, str]:
    try:
        p = subprocess.run(["termux-clipboard-set", text], capture_output=True, text=True,
                           timeout=8, env=ENV)
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return 1, str(exc)


def clipboard(args: Dict[str, Any]) -> Dict[str, Any]:
    """Set, or read, the clipboard — and never answer a silent empty string.

    Android 10+ lets only the *focused* app (or the default IME) read the clipboard, and the
    Termux process is never that app. Measured on this phone: `setPrimaryClip` through the
    accessibility service works, but every read — the bridge's `clipboard_get` **and**
    `termux-clipboard-get` — comes back empty, even with the shade as the focused window.
    So a read that yields nothing is reported as `clipboard_unreadable`, not as «(خالی)».
    """
    started = time.time()
    if "text" in args:
        rc, out = clipboard_set(str(args["text"]))
        return (_ok("clipboard_set", started, "در کلیپ‌بورد گذاشته شد", source="termux-api")
                if rc == 0 else _err("clipboard_set", started, out, source="termux-api"))
    try:
        p = subprocess.run(["termux-clipboard-get"], capture_output=True, text=True,
                           timeout=8, env=ENV)
        text = (p.stdout or "").strip()
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return _err("clipboard_get", started, str(exc), source="termux-api",
                    error="clipboard_unreadable")
    if not text:
        return _err("clipboard_get", started,
                    "کلیپ‌بورد در پس‌زمینه خوانده نمی‌شود: اندروید ۱۰+ خواندن را فقط به اپ "
                    "فعال یا کیبورد پیش‌فرض می‌دهد (هر دو مسیر برگشتند خالی). متن را از "
                    "اپ فعال بخوان یا با «type» بنویس.",
                    source="termux-api", error="clipboard_unreadable",
                    paths_tried=["bridge:clipboard_get", "termux-clipboard-get"])
    return _ok("clipboard_get", started, text[:300], text=text, source="termux-api",
               verified=True)


def volume(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    what = str(args.get("action") or args.get("dir") or "up").lower()
    keycode = {"up": 24, "down": 25, "mute": 164}.get(what)
    if keycode is None:
        return _err("volume", started, "up/down/mute لازم است")
    before = _volume_index()
    steps = int(args.get("steps") or 1)
    for _ in range(steps):
        sh(f"input keyevent {keycode}")
    after = _volume_index()
    lane = "keyevent"
    if before and after and before == after and what in ("up", "down"):
        # A foreground app can swallow hardware volume keys (measured: Settings did). The audio
        # service does not care what has focus, so use it before giving up.
        code, out = sh("cmd media_session volume --stream 3 --adj %s"
                       % ("raise" if what == "up" else "lower"))
        if code == 0:
            lane = "media_session"
            after = _volume_index()
    # `164` toggles mute, and "up" at maximum changes nothing: read the stream index back so
    # the card can say «انجام شد» only when it really moved.
    return _ok("volume", started, what, before=before, after=after, via=lane,
               verified=bool(before) and bool(after) and before != after)


def brightness(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    try:
        pct = max(1, min(100, int(args.get("percent", -1))))
    except (TypeError, ValueError):
        return _err("brightness", started, "درصد لازم است")
    value = int(round(pct * 255 / 100))
    sh(f"settings put system screen_brightness {value}")
    code, out = sh("settings get system screen_brightness")
    read = out.strip()
    if read != str(value):
        return _err("brightness", started,
                    f"تنظیم شد ولی مقدار خوانده‌شده {read} است (ممکن است حالت خودکار روشن باشد)")
    return _ok("brightness", started, f"{pct}%")


def wifi(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    on = str(args.get("action") or "on").lower() in ("on", "1", "true", "روشن")
    # Turning Wi-Fi off drops this Termux session's own network; only 'on' is safe inline.
    if not on and not args.get("allow_offline"):
        return _err("wifi", started, "خاموش کردن وای‌فای اتصال را قطع می‌کند؛ صریحاً تأیید کن")
    sh("svc wifi enable" if on else "svc wifi disable")
    code, out = sh("settings get global wifi_on")
    return _ok("wifi", started, f"wifi_on={out.strip()}")


def battery(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    code, out = sh("dumpsys battery")
    level = re.search(r"level:\s*(\d+)", out)
    status = re.search(r"status:\s*(\d+)", out)
    temp = re.search(r"temperature:\s*(\d+)", out)
    ac = re.search(r"AC powered:\s*(\w+)", out)
    parts = []
    if level:
        parts.append(level.group(1) + "٪")
    if status:
        parts.append({"1": "نامعلوم", "2": "در حال شارژ", "3": "روی باتری",
                      "4": "شارژ نشده", "5": "پر"}.get(status.group(1), status.group(1)))
    if ac and ac.group(1) == "true":
        parts.append("به برق")
    if temp:
        parts.append("%.1f°C" % (int(temp.group(1)) / 10))
    return _ok("battery", started, " · ".join(parts) or "خوانده نشد")


def info(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    what = str(args.get("what") or "time").lower()
    if what in ("time", "date", "ساعت"):
        return _ok("time", started, sh("date '+%H:%M:%S · %Y-%m-%d'")[1])
    if what in ("storage", "disk", "فضا"):
        out = sh("df -h /data")[1].splitlines()
        return _ok("storage", started, out[-1] if out else "?")
    if what in ("uptime", "بوت"):
        return _ok("uptime", started, sh("uptime")[1])
    if what in ("wifi_name", "ssid"):
        return _ok("ssid", started, sh("cmd wifi status")[1][:300])
    return _err("info", started, "what نامعتبر")


def open_url(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    url = str(args.get("url") or "")
    if not re.match(r"^https?://", url):
        return _err("open_url", started, "فقط http/https")
    code, out = sh(f"am start -a android.intent.action.VIEW -d '{url}'")
    return _ok("open_url", started, url) if code == 0 else _err("open_url", started, out)


def dial(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    number = re.sub(r"[^0-9+*#]", "", str(args.get("number") or ""))
    if not number:
        return _err("dial", started, "شماره‌ای نبود")
    code, out = sh(f"am start -a android.intent.action.DIAL -d 'tel:{number}'")
    return _ok("dial", started, number) if code == 0 else _err("dial", started, out)


def notifications(args: Dict[str, Any]) -> Dict[str, Any]:
    """Open the notification shade, then *check* that it opened.

    Measured on this build: `cmd statusbar expand-notifications` exits 0 in ~166 ms and the
    read-back `mCurrentFocus=…NotificationShade` shows up ~190 ms later. The old version
    returned «نوار اعلان باز شد» unconditionally, even when the command failed — a false
    success is worse than an error, so the claim is now conditional on the read-back.
    """
    started = time.time()
    code, out = sh("cmd statusbar expand-notifications")
    how = "cmd statusbar expand-notifications"
    if code != 0:
        code, out = sh("input keyevent 83")          # KEYCODE_NOTIFICATION
        how = "input keyevent 83"
    if code != 0:
        return _err("notifications", started, "باز کردن نوار اعلان ناموفق بود: " + out[:160],
                    verified=False, via=how)
    focus = _focus(("notification", "shade"), tries=4)
    opened = "notification" in focus.lower() or "shade" in focus.lower()
    return _ok("notifications", started, "نوار اعلان باز شد" if opened
               else "فرستاده شد ولی باز بودن نوار تأیید نشد",
               verified=opened, focus=focus[:80], via=how)


def ui_dump(args: Dict[str, Any]) -> Dict[str, Any]:
    """The UI tree with tap centres — the cheapest way for the agent to 'see' a screen."""
    started = time.time()
    remote = TMP + "/jv_ui.xml"
    sh(f"uiautomator dump {remote}", timeout=25)
    data = grab(remote)
    if not data:
        return _err("ui", started, "UI tree خوانده نشد")
    xml = data.decode("utf-8", "replace")
    nodes = re.findall(r'text="([^"]+)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
    lines = []
    for text, x1, y1, x2, y2 in nodes[:60]:
        cx, cy = (int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2
        lines.append(f"{text[:40]}  @ {cx},{cy}")
    return _ok("ui", started, "\n".join(lines[:40]) or "(متنی روی صفحه نیست)")


INTENTS = {
    "open_app": open_app,
    "screenshot": screenshot,
    "tap": tap,
    "swipe": swipe,
    "key": key,
    "type": type_text,
    "clipboard": clipboard,
    "volume": volume,
    "brightness": brightness,
    "wifi": wifi,
    "battery": battery,
    "info": info,
    "open_url": open_url,
    "dial": dial,
    "notifications": notifications,
    "ui": ui_dump,
}

# Intents the app may show as one-tap chips, with their Persian labels.
CHIPS = [
    {"intent": "info", "args": {"what": "time"}, "label": "ساعت"},
    {"intent": "battery", "args": {}, "label": "باتری"},
    {"intent": "screenshot", "args": {}, "label": "اسکرین‌شات"},
    {"intent": "key", "args": {"name": "home"}, "label": "خانه"},
    {"intent": "key", "args": {"name": "back"}, "label": "برگشت"},
    {"intent": "ui", "args": {}, "label": "چه روی صفحه است"},
]

# --------------------------------------------------------------------- natural routing --
# A tiny deterministic router for the commands people actually say. It only fires on
# unambiguous imperative phrasing; anything else falls through to the agent, which is
# slower but can think. This is what makes "تلگرام رو باز کن" feel instant.
_APP_PATTERNS = [
    re.compile(r"^(?:برنامه‌?ی?\s+)?(?P<app>.+?)\s+(?:رو|را)\s+باز\s*کن", re.I),
    re.compile(r"^(?P<app>.+?)\s+رو\s+باز\s*کن", re.I),
    re.compile(r"^open\s+(?P<app>.+)$", re.I),
    re.compile(r"^launch\s+(?P<app>.+)$", re.I),
]
_ROUTE_SIMPLE = [
    (re.compile(r"^(?:اسکرین\s*شات|اسکرین‌شات|عکس\s+(?:از\s+)?صفحه|screenshot)$", re.I),
     "screenshot", {}, "اسکرین‌شات"),
    (re.compile(r"^(?:چه\s+)?ساعت\s+چنده?$|^چه\s+ساعتیه$|^time$", re.I),
     "info", {"what": "time"}, "ساعت"),
    (re.compile(r"^(?:چقدر|چند\s+درصد|وضعیت)\s*باتری$|^باتری$|^battery$", re.I),
     "battery", {}, "باتری"),
    (re.compile(r"^(?:برو\s+)?خانه$|^home$", re.I), "key", {"name": "home"}, "خانه"),
    (re.compile(r"^(?:برگرد|بازگشت|back)$", re.I), "key", {"name": "back"}, "برگشت"),
    (re.compile(r"^(?:برنامه‌های\s+)?اخیر$|^recents$", re.I),
     "key", {"name": "recents"}, "برنامه‌های اخیر"),
    (re.compile(r"^(?:نوار\s+)?اعلان‌?ها?$|^notifications?$", re.I),
     "notifications", {}, "اعلان‌ها"),
    (re.compile(r"^(?:صدا|صدای?)\s*(?:رو)?\s*(?:بیشتر|بالا|زیاد)$|^volume\s*up$", re.I),
     "volume", {"action": "up"}, "صدای بیشتر"),
    (re.compile(r"^(?:صدا|صدای?)\s*(?:رو)?\s*(?:کمتر|پایین)$|^volume\s*down$", re.I),
     "volume", {"action": "down"}, "صدای کمتر"),
    (re.compile(r"^(?:بی\s*صدا|mute)$", re.I), "volume", {"action": "mute"}, "بی‌صدا"),
    (re.compile(r"^(?:وای\s*فای|وایفای|wifi)\s*(?:رو)?\s*(?:روشن|باز\s*کن)$", re.I),
     "wifi", {"action": "on"}, "وای‌فای روشن"),
    (re.compile(r"^چه\s+روی\s+صفحه\s+است$|^چی\s+روی\s+صفحه\s+است$|^screen$", re.I),
     "ui", {}, "چه روی صفحه است"),
    (re.compile(r"^(?:فضای|حافظه)\s*(?:ذخیره|خالی)?$|^storage$", re.I),
     "info", {"what": "storage"}, "فضای ذخیره"),
]
_BRIGHTNESS = re.compile(r"^(?:روشنایی|نور\s+صفحه)\s*(?:رو)?\s*(\d{1,3})\s*(?:درصد|٪)?", re.I)


def route(text: str) -> Optional[Dict[str, Any]]:
    """Map a short command to a fast intent, or None to let the agent handle it."""
    t = (text or "").strip().replace("ي", "ی").replace("ك", "ک")
    if not t or len(t) > 60:
        return None
    for pattern, intent, args, label in _ROUTE_SIMPLE:
        if pattern.match(t):
            return {"intent": intent, "args": args, "label": label}
    m = _BRIGHTNESS.match(t)
    if m:
        return {"intent": "brightness", "args": {"percent": int(m.group(1))},
                "label": "روشنایی " + m.group(1) + "٪"}
    for pattern in _APP_PATTERNS:
        m = pattern.match(t)
        if m:
            app = m.group("app").strip(" .!،")
            if 0 < len(app) <= 30:
                return {"intent": "open_app", "args": {"name": app},
                        "label": "باز کردن " + app}
    return None


def run(intent: str, args: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    fn = INTENTS.get(intent)
    if fn is None:
        return {"ok": False, "intent": intent, "ms": 0,
                "detail": "این کار سریع پشتیبانی نمی‌شود: " + str(intent),
                "known": sorted(INTENTS)}
    started = time.time()
    args = args or {}
    # Read the "before" state only when the caller asked to prove the action, so the default
    # path keeps its bridge latency (open_app 96 ms with verify:false, ~280 ms with it).
    kind = VERIFY.get(intent)
    want_verify = bool(args.get("verify")) and kind is not None
    before = _readback(kind) if want_verify else ""
    # The app's own hands first: it needs no adb pairing and no Shizuku, which is the whole
    # difference between "install three apps and pair wireless debugging" and "flip one toggle".
    if intent in BRIDGE_INTENTS:
        via = bridge(intent, args)
        if via is not None:
            via.setdefault("ms", int((time.time() - started) * 1000))
            if want_verify:
                verdict = _verified(intent, args, before)
                if verdict is False:
                    # The app said ok but the screen disagrees (measured: `dial` through the
                    # bridge reported ok while Settings stayed focused). Try the shell lane
                    # once, then report whichever proof actually exists.
                    retry = fn(args)
                    if retry.get("ok"):
                        retry["bridge_missed"] = True
                        retry["verified"] = _verified(intent, args, before)
                        retry.setdefault("ms", int((time.time() - started) * 1000))
                        return retry
                via["verified"] = verdict
            return via
    try:
        result = fn(args)
        if want_verify and result.get("ok") and "verified" not in result:
            result["verified"] = _verified(intent, args, before)
        return result
    except Exception as exc:  # noqa: BLE001
        return {"ok": False, "intent": intent,
                "ms": int((time.time() - started) * 1000),
                "detail": "%s: %s" % (type(exc).__name__, exc)}


if __name__ == "__main__":
    import json
    import sys
    print(json.dumps(run(sys.argv[1] if len(sys.argv) > 1 else "info",
                         json.loads(sys.argv[2]) if len(sys.argv) > 2 else {}),
                     ensure_ascii=False))
