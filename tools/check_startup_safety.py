#!/usr/bin/env python3
"""Nothing on the pre-core startup path may call into the core.

THE TRAP
    Eden's own logger is native:

        object Log {
            external fun error(message: String)
        }

    Those functions live inside libyuzu-android.so. Calling one before the
    library is loaded throws UnsatisfiedLinkError - and if the call sits in
    the error handler that reports a failed load, the real reason is replaced
    by a misleading one. The user sees the same black screen and the log,
    if it appears at all, blames the logger.

    I wrote exactly that bug into the fix for the previous black screen:
    Log.error() inside the branch that runs when the core did not load.

WHY A SCRIPT AND NOT A UNIT TEST
    A unit test cannot catch this. On the JVM every one of these functions is
    missing anyway, so a test proves nothing about the ordering. The property
    being checked is structural - "this call does not appear in this region" -
    so it is checked by reading the source.

WHAT IS CHECKED
    Inside NativeLibrary's init block - everything that runs before the core
    is up - there must be no call to Eden's Log object and no call to any
    `external fun`. android.util.Log is framework code and always allowed.
"""

import re
import sys


NATIVE_LOG = re.compile(r"(?<!android\.util\.)\bLog\.(error|info|debug|warning|critical)\s*\(")


def init_block(src):
    """The body of `init { ... }`, found by matching braces."""
    m = re.search(r"^\s*init\s*\{", src, re.M)
    if not m:
        return None
    i = m.end() - 1
    depth = 0
    for j in range(i, len(src)):
        if src[j] == "{":
            depth += 1
        elif src[j] == "}":
            depth -= 1
            if depth == 0:
                return src[i + 1 : j]
    return None


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else \
        "patch2/android/root/NativeLibrary.kt"
    src = open(path, encoding="utf-8").read()

    body = init_block(src)
    if body is None:
        print(f"  MISSING an init block in {path}")
        return 1

    code = strip_comments(body)
    bad = 0

    hits = NATIVE_LOG.findall(code)
    if hits:
        print(f"  FAIL init calls Eden's native Log: {sorted(set(hits))}")
        print("       Log.* is `external fun` - it needs the core that has")
        print("       not loaded yet. Use android.util.Log here.")
        bad = 1
    else:
        print("  ok   init does not call Eden's native Log")

    # Any other native entry point declared in this same file.
    externals = set(re.findall(r"external\s+fun\s+(\w+)", src))
    called = {n for n in externals if re.search(rf"\b{n}\s*\(", code)}
    if called:
        print(f"  FAIL init calls native functions before the core loads: {sorted(called)}")
        bad = 1
    else:
        print(f"  ok   init calls none of the {len(externals)} native functions")

    # The load must actually happen on both paths - the bug that shipped
    # twice was a branch that reported success without loading anything.
    if "System.loadLibrary" not in code and "CoreFromFolder.load" not in code:
        print("  FAIL init never loads the core at all")
        bad = 1
    else:
        print("  ok   init does load the core")

    if bad:
        print("ПРОВАЛ")
    else:
        print("ВСЁ ПРОШЛО")
    return bad


if __name__ == "__main__":
    sys.exit(main())
