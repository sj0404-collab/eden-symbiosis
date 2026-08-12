#!/usr/bin/env python3
"""Catch unresolved references between the patch's own Kotlin files.

WHY

    A missing import is a one-line mistake that only surfaces after the whole
    native tree has compiled - twenty-three minutes into a build, at
    `compileLegacyDebugKotlin`. That happened exactly once with
    `KenjiProbeService`, and once is enough: this finds the same class of
    mistake in under a second.

WHAT IT CHECKS

    For every Kotlin file we add, each reference to another type WE define
    must either be imported, or live in the same package. Types from Android,
    Kotlin or upstream Eden are ignored - we cannot see their sources here and
    guessing about them would produce noise instead of signal.

Usage: python3 tools/check_kotlin_refs.py [--patch <dir>]
"""

import argparse
import pathlib
import re
import sys


def declared_types(files):
    """Map of type name -> package, for everything the patch defines."""
    out = {}
    for path in files:
        text = path.read_text(encoding="utf-8")
        pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg:
            continue
        for m in re.finditer(
            r"^(\s*)(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*"
            r"(?:object|class|interface|enum class)\s+(\w+)", text, re.M
        ):
            indent, name = m.group(1), m.group(2)
            # Only TOP-LEVEL declarations. A nested type - EngineLoader.Engine,
            # EngineLoader.State - is reached through its owner and is never
            # imported on its own, so treating it as importable produced six
            # false alarms on code that compiles perfectly well.
            if indent.strip() != "" or len(indent) > 0:
                continue
            out.setdefault(name, pkg.group(1))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--patch", default=None)
    args = ap.parse_args()

    root = pathlib.Path(__file__).resolve().parent.parent
    patch = pathlib.Path(args.patch) if args.patch else root / "patch"

    files = sorted(patch.rglob("*.kt"))
    if not files:
        print("no Kotlin sources found")
        return 0

    types = declared_types(files)
    problems = 0

    for path in files:
        text = path.read_text(encoding="utf-8")
        pkg_m = re.search(r"^package\s+([\w.]+)", text, re.M)
        if not pkg_m:
            continue
        pkg = pkg_m.group(1)
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.M))
        imported_names = {i.rsplit(".", 1)[-1] for i in imports}

        # Strip comments and strings so a type named in prose is not "used".
        body = re.sub(r"//[^\n]*", "", text)
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
        body = re.sub(r'"""(?:.|\n)*?"""', '""', body)
        body = re.sub(r'"(?:[^"\\]|\\.)*"', '""', body)
        # Drop the file's own declarations line so `object Foo` is not a use.
        body = re.sub(r"^\s*(?:object|class|interface|enum class)\s+\w+", "", body, flags=re.M)

        own = {m.group(1) for m in re.finditer(
            r"^\s*(?:@\w+\s+)*(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+)*"
            r"(?:object|class|interface|enum class)\s+(\w+)", text, re.M)}

        for name, home in types.items():
            if name in own:
                continue
            if home == pkg:
                continue          # same package, no import needed
            if name in imported_names:
                continue
            # A fully-qualified use needs no import: org.yuzu...SharedDataDirectory
            # is legal and common. Only bare references count.
            bare = re.search(rf"(?<![\w.]){name}\s*[.(<]", body)
            if bare:
                print(f"{path.name}: uses {name} ({home}) without importing it")
                problems += 1

    print(f"\nchecked {len(files)} Kotlin files, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
