#!/usr/bin/env bash
# Copy patch2 into a clean Eden checkout.
#
# Deliberately not tools/apply_patch.sh. That script carries the whole old
# Symbiosis tree - 13 screens, 376 KB of C++, the retro launcher whose early
# return caused the settings crash. This one copies four files and nothing
# else, which is the point of starting from clean upstream.
#
# Every file here is a MODIFIED COPY of an upstream file, so it is overwritten
# in place rather than merged: a three-way merge would silently half-apply if
# upstream moved a line, and the result would compile while missing the change.
# The check at the end proves each edit actually arrived.
#
# Usage: bash tools/apply_patch2.sh --patch <dir> --eden <dir>
set -euo pipefail

PATCH_DIR=""
EDEN_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --patch) PATCH_DIR="$2"; shift 2 ;;
    --eden)  EDEN_DIR="$2";  shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -n "$PATCH_DIR" ] || PATCH_DIR="$SELF_DIR/../patch2"
[ -d "$PATCH_DIR" ] || { echo "patch directory not found: $PATCH_DIR" >&2; exit 1; }
[ -d "$EDEN_DIR" ]  || { echo "eden checkout not found: $EDEN_DIR" >&2; exit 1; }

PATCH_DIR="$(cd "$PATCH_DIR" && pwd)"
EDEN_DIR="$(cd "$EDEN_DIR" && pwd)"
J="$EDEN_DIR/src/android/app/src/main/java/org/yuzu/yuzu_emu"

echo "patch2: $PATCH_DIR"
echo "eden  : $EDEN_DIR"
[ -d "$J" ] || { echo "not an Eden checkout: $J missing" >&2; exit 1; }

copied=0
copy_one() {
  local src="$1" dst="$2"
  [ -f "$src" ] || return 0
  [ -f "$dst" ] || { echo "  ::warning:: upstream file missing, skipping: $dst"; return 0; }
  cp "$src" "$dst"
  copied=$((copied + 1))
  echo "  copied $(basename "$dst")"
}

copy_one "$PATCH_DIR/android/model/Game.kt"            "$J/model/Game.kt"
copy_one "$PATCH_DIR/android/utils/GameHelper.kt"      "$J/utils/GameHelper.kt"
copy_one "$PATCH_DIR/android/ui/GamesFragment.kt"      "$J/ui/GamesFragment.kt"

# SharedDataDirectory is a new file, not a modified one, so it has no upstream
# counterpart to check against.
if [ -f "$PATCH_DIR/android/utils/SharedDataDirectory.kt" ]; then
  cp "$PATCH_DIR/android/utils/SharedDataDirectory.kt" "$J/utils/SharedDataDirectory.kt"
  copied=$((copied + 1))
  echo "  copied SharedDataDirectory.kt (new file)"
fi

# Prove the edits are actually present. A green build says nothing about
# whether a file was copied - that mistake has cost a full 30-minute build in
# this project more than once.
echo "verifying:"
fail=0
check() {
  local file="$1" needle="$2" what="$3"
  if grep -q "$needle" "$file"; then
    echo "  ok   $what"
  else
    echo "  MISSING $what in $(basename "$file")"
    fail=1
  fi
}
check "$J/model/Game.kt"       "val folder: String"  "Game.folder field"
check "$J/model/Game.kt"       "folderName"          "Game.folderName"
check "$J/utils/GameHelper.kt" "childFolder"         "folder carried through the scan"
check "$J/utils/GameHelper.kt" "deepScan) 24"        "scan depth raised to 24"
check "$J/ui/GamesFragment.kt" "groupByFolder"       "list grouped by folder"

echo "PATCH2_APPLIED files=$copied"
exit $fail
