#!/data/data/com.termux/files/usr/bin/env python
"""Run every fast-path intent once and print a verdict, so the table can be trusted.

    fast_test.py            # safe intents only
    fast_test.py --all      # also the ones that change the device state

Uses the same module jarvisd loads, so a green line here means the intent works in
production, not just in a unit stub.
"""
import json
import sys
import time

import jarvis_fast as F

SAFE = [
    ("info", {"what": "time"}),
    ("info", {"what": "storage"}),
    ("info", {"what": "uptime"}),
    ("battery", {}),
    ("screenshot", {}),
    ("ui", {}),
    ("open_app", {"name": "settings"}),
    ("key", {"name": "home"}),
    ("key", {"name": "back"}),
    ("swipe", {"dir": "up"}),
    ("volume", {"action": "up"}),
    ("volume", {"action": "down"}),
    ("brightness", {"percent": 62}),
    ("wifi", {"action": "on"}),
    ("clipboard", {"text": "jarvis fast path"}),
    ("clipboard", {}),
    ("open_url", {"url": "https://example.com"}),
    ("key", {"name": "back"}),
]

EXTRA = [
    ("type", {"text": "ascii works"}),
    ("type", {"text": "سلام جارویس"}),
    ("tap", {"x": 540, "y": 1200}),
    ("notifications", {}),
    ("key", {"name": "back"}),
]


def main():
    todo = SAFE + (EXTRA if "--all" in sys.argv else [])
    bad = 0
    for intent, args in todo:
        t0 = time.time()
        res = F.run(intent, args)
        ms = int((time.time() - t0) * 1000)
        ok = "OK " if res.get("ok") else "NO "
        if not res.get("ok"):
            bad += 1
        detail = str(res.get("detail", "")).replace("\n", " ⏎ ")[:90]
        print("%s %-13s %4dms  %s" % (ok, intent, ms, detail))
    print(json.dumps({"intents": len(todo), "failed": bad}))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
