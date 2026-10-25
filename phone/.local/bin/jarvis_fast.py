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

import os
import re
import subprocess
import time
from typing import Any, Dict, List, Optional, Tuple

RISH = "rish"
ADB = "adb"
ADB_ADDR = "127.0.0.1:5555"
SHOT_DIR = os.path.expanduser("~/shots")
TMP = "/data/local/tmp"
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
                return 0, out.strip()
            # adb's own failure (device gone) is worth one rish retry; a remote exit
            # code is a real answer, so pass it back as-is.
            if out.strip() and "device" not in out.lower():
                return p.returncode, out.strip()
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass
    try:
        p = subprocess.run([RISH, "-c", command], capture_output=True, text=True,
                           timeout=timeout, env=ENV)
        out = (p.stdout or "") + (p.stderr or "")
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
    code, out = sh("pm list packages", timeout=25)
    names = sorted({line.split(":", 1)[1].strip() for line in out.splitlines() if ":" in line})
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
    return dict(ok=True, intent=intent, ms=int((time.time() - started) * 1000),
                detail=detail, **extra)


def _err(intent: str, started: float, detail: str) -> Dict[str, Any]:
    return dict(ok=False, intent=intent, ms=int((time.time() - started) * 1000),
                detail=detail)


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
    started = time.time()
    if "text" in args:
        rc, out = clipboard_set(str(args["text"]))
        return _ok("clipboard_set", started, "در کلیپ‌بورد گذاشته شد") if rc == 0 \
            else _err("clipboard_set", started, out)
    try:
        p = subprocess.run(["termux-clipboard-get"], capture_output=True, text=True,
                           timeout=8, env=ENV)
        text = (p.stdout or "").strip()
        return _ok("clipboard_get", started, text[:300] or "(خالی)", text=text)
    except (FileNotFoundError, subprocess.TimeoutExpired) as exc:
        return _err("clipboard_get", started, str(exc))


def volume(args: Dict[str, Any]) -> Dict[str, Any]:
    started = time.time()
    what = str(args.get("action") or args.get("dir") or "up").lower()
    keycode = {"up": 24, "down": 25, "mute": 164}.get(what)
    if keycode is None:
        return _err("volume", started, "up/down/mute لازم است")
    for _ in range(int(args.get("steps") or 1)):
        sh(f"input keyevent {keycode}")
    return _ok("volume", started, what)


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
    started = time.time()
    rc, out = sh("cmd statusbar expand-notifications")
    if rc != 0:
        sh("input keyevent 83")
    return _ok("notifications", started, "نوار اعلان باز شد")


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


def run(intent: str, args: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    fn = INTENTS.get(intent)
    if fn is None:
        return {"ok": False, "intent": intent, "ms": 0,
                "detail": "این کار سریع پشتیبانی نمی‌شود: " + str(intent),
                "known": sorted(INTENTS)}
    started = time.time()
    try:
        return fn(args or {})
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
