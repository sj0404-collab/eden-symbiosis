#!/usr/bin/env python3
"""Check that every Kotlin `external fun` has a matching C++ symbol.

WHY THIS EXISTS AS A TEST

    JNI binds by NAME, at the first call, at runtime. A mismatch compiles
    cleanly on both sides, links cleanly, ships cleanly - and then throws
    UnsatisfiedLinkError the moment the feature is used. In an emulator that
    lands mid-launch and looks like the core crashed.

    The mangling has two traps:
      * dots in the package become underscores
      * an underscore already in the package, class or method name becomes _1

    So `org.yuzu.yuzu_emu.utils.KenjiBridge.nativeLoad` is
    `Java_org_yuzu_yuzu_1emu_utils_KenjiBridge_nativeLoad`. Writing
    `yuzu_emu` instead of `yuzu_1emu` is the easiest possible typo and the
    hardest to spot by eye.

    This also catches the reverse: a C++ entry point nobody declares in
    Kotlin, which is dead weight that quietly rots.

Usage: python3 tools/check_jni_names.py [--patch <dir>]
Exit code 1 if anything is missing on either side.
"""

import argparse
import pathlib
import re
import sys


def mangle(part: str) -> str:
    """JNI name mangling for one identifier segment."""
    return part.replace("_", "_1")


def expected_symbol(package: str, cls: str, method: str) -> str:
    pieces = [mangle(p) for p in package.split(".")]
    return "Java_" + "_".join(pieces) + "_" + mangle(cls) + "_" + mangle(method)


def kotlin_natives(path: pathlib.Path):
    """(package, class, [methods]) for a Kotlin file declaring external funs."""
    text = path.read_text(encoding="utf-8")
    pkg = re.search(r"^package\s+([\w.]+)", text, re.M)
    if not pkg:
        return None
    methods = re.findall(r"^\s*(?:private\s+|internal\s+)?external\s+fun\s+(\w+)",
                         text, re.M)
    if not methods:
        return None
    # object/class name that holds them
    holder = re.search(r"^\s*(?:object|class)\s+(\w+)", text, re.M)
    if not holder:
        return None
    return pkg.group(1), holder.group(1), methods


def cpp_symbols(paths):
    found = set()
    for p in paths:
        text = p.read_text(encoding="utf-8", errors="replace")
        found |= set(re.findall(r"\b(Java_\w+)\s*\(", text))
    return found


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--patch", default=None)
    args = ap.parse_args()

    root = pathlib.Path(__file__).resolve().parent.parent
    patch = pathlib.Path(args.patch) if args.patch else root / "patch"

    kt_files = sorted(patch.rglob("*.kt"))
    cpp_files = sorted(patch.rglob("*.cpp"))
    if not cpp_files:
        print("no C++ sources found; nothing to check")
        return 0

    declared = cpp_symbols(cpp_files)

    problems = 0
    checked = 0
    expected_all = set()

    for kt in kt_files:
        info = kotlin_natives(kt)
        if not info:
            continue
        package, cls, methods = info
        for m in methods:
            sym = expected_symbol(package, cls, m)
            expected_all.add(sym)
            checked += 1
            if sym in declared:
                print(f"ok   {cls}.{m}")
            else:
                print(f"MISSING in C++: {sym}   (declared in {kt.name})")
                problems += 1

    # A C++ entry point with no Kotlin declaration. Not fatal on its own -
    # upstream Eden has plenty - so only report ones in files we own.
    ours = cpp_symbols([p for p in cpp_files if "kenji" in p.name])
    for sym in sorted(ours - expected_all):
        print(f"unused C++ entry point: {sym}")
        problems += 1

    print(f"\nchecked {checked} native declarations, {problems} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
