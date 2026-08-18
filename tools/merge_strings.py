#!/usr/bin/env python3
"""Merges the fork's strings into upstream's, instead of replacing the file.

The fork used to ship a whole strings.xml copied from master. Against a
different upstream that silently drops any string the fork's copy predates -
build #10 died on `resource string/off not found`, because v0.2.1's arrays.xml
references four strings my master-derived file did not contain.

Merging keeps every upstream string, adds the fork's, and lets the fork
override a wording deliberately. It also means switching upstream versions no
longer requires re-syncing the file by hand.
"""
import re
import sys
import xml.etree.ElementTree as ET


def entries(path):
    """name -> raw XML text, preserving markup and attributes verbatim."""
    raw = open(path, encoding="utf-8").read()
    found = {}
    for m in re.finditer(
        r'[ \t]*<(string|plurals|string-array|integer-array)\s+[^>]*?name="([^"]+)".*?'
        r'(?:/>|</\1>)\n?',
        raw,
        re.S,
    ):
        found[m.group(2)] = m.group(0)
    return found


def main():
    if len(sys.argv) != 4:
        print("usage: merge_strings.py <upstream.xml> <fork.xml> <output.xml>",
              file=sys.stderr)
        return 2

    upstream_path, fork_path, out_path = sys.argv[1:4]
    upstream = entries(upstream_path)
    fork = entries(fork_path)

    base = open(upstream_path, encoding="utf-8").read()
    added, overridden = [], []

    for name, text in fork.items():
        if name in upstream:
            if text.strip() != upstream[name].strip():
                base = base.replace(upstream[name], text, 1)
                overridden.append(name)
        else:
            added.append(text)

    if added:
        idx = base.rindex("</resources>")
        block = "\n    <!-- Eden Symbiosis -->\n" + "".join(added)
        base = base[:idx] + block + base[idx:]

    open(out_path, "w", encoding="utf-8").write(base)

    # Parsing the result is the real check: a bad splice must not reach aapt2.
    ET.parse(out_path)

    kept = len(upstream) - len(overridden)
    print(f"merged: {kept} upstream kept, {len(overridden)} overridden, "
          f"{len(added)} added -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
