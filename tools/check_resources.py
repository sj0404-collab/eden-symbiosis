#!/usr/bin/env python3
"""Fails on duplicate string resources before aapt2 has to.

A duplicate name is fatal to the resource merger, but only ~20 minutes into a
build, after the whole emulator core has compiled. This catches it in a second.

The trap it exists for: adding a string that upstream Eden already defines.
Nothing in the patch tooling warns about that, and the error message
("Found item String/x more than one time") does not say which file is at fault.
"""
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


def check(path: Path) -> list[str]:
    root = ET.parse(path).getroot()
    problems = []
    for tag in ("string", "plurals", "string-array", "integer-array"):
        names = [e.get("name") for e in root.iter(tag) if e.get("name")]
        for name, count in Counter(names).items():
            if count > 1:
                problems.append(f"{path.name}: <{tag} name=\"{name}\"> defined {count} times")
    return problems


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: check_resources.py <res-dir> [...]", file=sys.stderr)
        return 2

    problems, checked = [], 0
    for arg in sys.argv[1:]:
        for xml in sorted(Path(arg).rglob("strings*.xml")):
            checked += 1
            try:
                problems += check(xml)
            except ET.ParseError as exc:
                problems.append(f"{xml}: not valid XML: {exc}")

    if problems:
        print("duplicate or malformed resources:")
        for p in problems:
            print("  " + p)
        return 1

    print(f"{checked} resource file(s) checked, no duplicates")
    return 0


if __name__ == "__main__":
    sys.exit(main())
