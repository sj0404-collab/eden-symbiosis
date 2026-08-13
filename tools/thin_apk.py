#!/usr/bin/env python3
"""Strip the core out of a built APK without disturbing anything else.

WHY THIS FILE EXISTS INSTEAD OF A `zip` COMMAND
    The first version of the thin APK rebuilt the archive with `zip`, which
    recompresses everything with its own defaults. That produced an APK the
    installer rejected:

        Failed parse during installPackageLI: Targeting R+ (version 30 and
        above) requires the resources.arsc of installed APKs to be stored
        uncompressed and aligned on a 4-byte boundary

    Since Android 11 the framework maps resources.arsc straight out of the
    APK instead of reading it, so it must be STORED, not DEFLATED, and its
    data must start on a 4-byte boundary. Gradle emits it that way; `zip`
    happily compressed it and undid that.

    The same class of mistake applies to every other entry: the packaging
    upstream chose is part of a working APK, not an incidental detail. So
    this copies entries across VERBATIM - same compression method for each
    one - and only leaves out the files asked for. Nothing is recompressed
    and nothing is re-flagged.

    Alignment is then restored by zipalign and the result signed by
    apksigner, which is the ordinary Android toolchain order.

Usage:
    thin_apk.py --in app.apk --out thin.apk --drop libyuzu-android.so --drop 'libVkLayer_*.so'
"""

import argparse
import fnmatch
import os
import shutil
import struct
import sys
import zipfile


def data_offset(f, info):
    """Where an entry's bytes actually begin.

    The central directory records the local header offset, not the data
    offset: the local header carries its own name and extra field, whose
    lengths differ from the central copy. Alignment is a property of the
    data, so the local header has to be read to find it.
    """
    f.seek(info.header_offset)
    head = f.read(30)
    if len(head) < 30 or head[:4] != b"PK\x03\x04":
        return None
    name_len, extra_len = struct.unpack("<HH", head[26:30])
    return info.header_offset + 30 + name_len + extra_len


def describe(path):
    """Report what matters for installability."""
    out = []
    with zipfile.ZipFile(path) as z, open(path, "rb") as f:
        for info in z.infolist():
            if info.filename == "resources.arsc" or info.filename.endswith(".so"):
                off = data_offset(f, info)
                out.append(
                    {
                        "name": info.filename,
                        "stored": info.compress_type == zipfile.ZIP_STORED,
                        "offset": off,
                        "align4": None if off is None else off % 4,
                    }
                )
    return out


def check(path, expect_stored_arsc=True):
    """True when the APK will not be rejected for packaging reasons.

    Only the rules the installer actually enforces are checked, so a pass
    here means something. resources.arsc must be stored and 4-byte aligned
    on Android 11 and later.
    """
    ok = True
    with zipfile.ZipFile(path) as z, open(path, "rb") as f:
        names = z.namelist()
        if "resources.arsc" not in names:
            print("  MISSING resources.arsc")
            return False
        info = z.getinfo("resources.arsc")
        stored = info.compress_type == zipfile.ZIP_STORED
        off = data_offset(f, info)
        aligned = off is not None and off % 4 == 0

        if expect_stored_arsc:
            print(f"  {'ok  ' if stored else 'FAIL'} resources.arsc uncompressed")
            print(f"  {'ok  ' if aligned else 'FAIL'} resources.arsc 4-byte aligned"
                  f" (offset {off})")
            ok = stored and aligned
    return ok


def thin(src, dst, drops):
    """Copy every entry except the dropped ones, preserving compression."""
    dropped, kept = [], 0
    with zipfile.ZipFile(src) as zin:
        infos = zin.infolist()
        with zipfile.ZipFile(dst, "w") as zout:
            for info in infos:
                base = os.path.basename(info.filename)
                # The old signature covers a file list that is about to
                # change; apksigner writes a new one.
                if info.filename.startswith("META-INF/") and base.upper().endswith(
                    (".SF", ".RSA", ".DSA", ".EC")
                ):
                    dropped.append(info.filename)
                    continue
                if any(fnmatch.fnmatch(base, pat) for pat in drops):
                    dropped.append(info.filename)
                    continue

                data = zin.read(info.filename)
                # A fresh ZipInfo carrying the ORIGINAL compress_type. Reusing
                # the source ZipInfo would drag its stale header offset along.
                out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                out.compress_type = info.compress_type
                out.external_attr = info.external_attr
                out.internal_attr = info.internal_attr
                out.create_system = info.create_system
                zout.writestr(out, data)
                kept += 1
    return kept, dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", required=True)
    # Not required with --check-only: checking an existing APK produces no
    # output file, and demanding one there is just a trap.
    ap.add_argument("--out", dest="dst")
    ap.add_argument("--drop", action="append", default=[])
    ap.add_argument("--check-only", action="store_true")
    args = ap.parse_args()

    if args.check_only:
        print(f"checking {args.src}")
        sys.exit(0 if check(args.src) else 1)

    if not args.dst:
        ap.error("--out is required unless --check-only is given")

    before = os.path.getsize(args.src)
    kept, dropped = thin(args.src, args.dst, args.drop)
    after = os.path.getsize(args.dst)

    print(f"kept {kept} entries, dropped {len(dropped)}:")
    for d in dropped:
        print(f"    {d}")
    print(f"{before / 1048576:.1f} MB -> {after / 1048576:.1f} MB")

    # Not aligned yet - zipalign does that next - but the compression method
    # must already be right, because nothing later fixes it.
    with zipfile.ZipFile(args.dst) as z:
        info = z.getinfo("resources.arsc")
        if info.compress_type != zipfile.ZIP_STORED:
            print("  FAIL resources.arsc came out compressed")
            sys.exit(1)
        print("  ok   resources.arsc still uncompressed")


if __name__ == "__main__":
    main()
