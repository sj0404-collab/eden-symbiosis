#!/usr/bin/env python3
"""Fails when a layout, array or menu references a string that does not exist.

Build #10 died at resource linking with "resource string/off not found": the
fork replaced strings.xml wholesale with a copy taken from a different upstream,
dropping four strings that this tag's arrays.xml still referenced. aapt2 only
notices at link time, twenty minutes in.
"""
import re
import sys
from pathlib import Path


def main():
    if len(sys.argv) != 2:
        print("usage: check_string_refs.py <res-dir>", file=sys.stderr)
        return 2

    res = Path(sys.argv[1])

    # Only the default values/ directory counts. A translation in values-ru
    # does not satisfy a reference: aapt2 resolves against the default locale,
    # and a string present solely in a translation still fails to link. That
    # distinction is exactly what build #10 tripped over.
    defined = set()
    default_values = res / "values"
    for xml in default_values.glob("*.xml"):
        for m in re.finditer(r'<(?:string|plurals)\s+name="([^"]+)"', xml.read_text(errors="ignore")):
            defined.add(m.group(1))

    # Strings that come from the AndroidX/Material libraries rather than this
    # project. They resolve at link time but are not in our res/.
    library_strings = {
        "appbar_scrolling_view_behavior",
        "bottom_sheet_behavior",
        "character_counter_pattern",
        "path_password_eye",
    }

    missing = {}
    for xml in res.rglob("*.xml"):
        text = xml.read_text(errors="ignore")
        for m in re.finditer(r'@string/(\w+)', text):
            if m.group(1) not in defined and m.group(1) not in library_strings:
                missing.setdefault(m.group(1), set()).add(xml.name)

    if missing:
        print("references to strings that do not exist:")
        for name, files in sorted(missing.items()):
            print(f"  @string/{name}  <- {', '.join(sorted(files))}")
        return 1

    print(f"{len(defined)} strings defined, every @string reference resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
