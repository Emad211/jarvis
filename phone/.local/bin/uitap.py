#!/data/data/com.termux/files/usr/bin/env python
"""uitap — tap the on-screen element whose visible text contains a string.

Built for driving the console from a terminal/laptop while iterating on the UI: blind
coordinate taps break every time a row moves, text does not.

    uitap 'شخصیت'        tap the first clickable node whose text contains this
    uitap -l             list every clickable node as `text  x,y`
"""
import re
import sys
import xml.etree.ElementTree as ET

import jarvis_fast as fast


def dump() -> str:
    fast.sh("uiautomator dump /sdcard/ui.xml")
    return fast.sh("cat /sdcard/ui.xml")[1]


def rows(xml_text: str):
    start = xml_text.find("<hierarchy")
    if start < 0:
        return []
    try:
        root = ET.fromstring(xml_text[start:])
    except ET.ParseError:
        return []
    out = []
    for node in root.iter("node"):
        if node.get("clickable") != "true":
            continue
        m = re.findall(r"\d+", node.get("bounds") or "")
        if len(m) < 4:
            continue
        x1, y1, x2, y2 = (int(v) for v in m[:4])
        label = node.get("text") or ""
        if not label:
            label = node.get("content-desc") or ""
        if not label:
            label = " / ".join(n.get("text") for n in node.iter("node") if n.get("text"))
        if label:
            out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def main() -> int:
    args = [a for a in sys.argv[1:]]
    listing = any(a in ("-l", "--list") for a in args)
    args = [a for a in args if a not in ("-l", "--list")]
    found = rows(dump())
    if listing or not args:
        for label, x, y in found:
            print("%-46s %d,%d" % (label[:46], x, y))
        return 0
    needle = args[0]
    for label, x, y in found:
        if needle in label:
            fast.sh("input tap %d %d" % (x, y))
            print("tapped %r at %d,%d" % (label[:40], x, y))
            return 0
    print("no clickable node containing %r" % needle)
    return 2


if __name__ == "__main__":
    sys.exit(main())
