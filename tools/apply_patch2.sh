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
# Which half of the patch to apply. The control build proved the crash is
# ours, so the next question is WHICH part - and bisecting by hand costs a
# build each time. These subsets split patch2 along its natural seams:
#
#   folders  the game-folder work: Game, GameHelper, GamesFragment, GameAdapter
#   button   the floating button and the keep-in-memory switch
#   shared   the shared-folder utility and its button
#   all      everything (default)
SUBSET="all"
while [ $# -gt 0 ]; do
  case "$1" in
    --patch)  PATCH_DIR="$2"; shift 2 ;;
    --eden)   EDEN_DIR="$2";  shift 2 ;;
    --subset) SUBSET="$2";    shift 2 ;;
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

want() {
  case "$SUBSET" in
    all) return 0 ;;
    "$1") return 0 ;;
    *) return 1 ;;
  esac
}
echo "subset: $SUBSET"

# The crash logger goes in for EVERY subset, including none of them: it is
# the only way the user can tell me why a build died. It writes to
# Android/data/<pkg>/files/symbiosis-crash.txt, which needs no permission
# and is reachable from any file manager.
copy_one "$PATCH_DIR/android/root/YuzuApplication.kt" "$J/YuzuApplication.kt"

# "folders" is four files that do four separable things, and the crash is
# somewhere in them. These narrower subsets split it further so the next
# build changes one thing again:
#
#   scan   Game.kt + GameHelper.kt   - the field, and filling it in
#   sort   + GamesFragment.kt        - ordering the list by it
#   folders (= all four)             - and showing it on the card
if want folders || want scan || want sort; then
  copy_one "$PATCH_DIR/android/model/Game.kt"            "$J/model/Game.kt"
  copy_one "$PATCH_DIR/android/utils/GameHelper.kt"      "$J/utils/GameHelper.kt"
fi
if want folders || want sort; then
  copy_one "$PATCH_DIR/android/ui/GamesFragment.kt"      "$J/ui/GamesFragment.kt"
fi
if want folders; then
  copy_one "$PATCH_DIR/android/adapters/GameAdapter.kt"  "$J/adapters/GameAdapter.kt"
fi

if want button; then
  copy_one "$PATCH_DIR/android/fragments/EmulationFragment.kt" "$J/fragments/EmulationFragment.kt"
  copy_one "$PATCH_DIR/android/activities/EmulationActivity.kt" "$J/activities/EmulationActivity.kt"
fi

if want shared; then
  copy_one "$PATCH_DIR/android/fragments/GameFoldersFragment.kt" "$J/fragments/GameFoldersFragment.kt"
fi

# Layout and strings. The shared-folder button lives beside "add game folder",
# and its labels have to exist in both languages or the build fails on a
# missing resource - which is how R.string.select was caught before it cost a
# twenty-minute compile.
RES="$(cd "$J/../../../../res" && pwd)"
if want shared; then
  copy_one "$PATCH_DIR/android/layout/fragment_folders.xml" "$RES/layout/fragment_folders.xml"
fi
copy_one "$PATCH_DIR/android/values/values__strings.xml"    "$RES/values/strings.xml"
copy_one "$PATCH_DIR/android/values/values-ru__strings.xml" "$RES/values-ru/strings.xml"

# The floating button is a new file with no upstream counterpart.
if want button && [ -f "$PATCH_DIR/android/views/FloatingGameButton.kt" ]; then
  mkdir -p "$J/views"
  cp "$PATCH_DIR/android/views/FloatingGameButton.kt" "$J/views/FloatingGameButton.kt"
  copied=$((copied + 1))
  echo "  copied FloatingGameButton.kt (new file)"
fi

if want shared && [ -f "$PATCH_DIR/android/utils/SharedDataDirectory.kt" ]; then
  cp "$PATCH_DIR/android/utils/SharedDataDirectory.kt" "$J/utils/SharedDataDirectory.kt"
  copied=$((copied + 1))
  echo "  copied SharedDataDirectory.kt (new file)"
fi

# Prove the edits are actually present. A green build says nothing about
# whether a file was copied - that mistake has cost a full 30-minute build in
# this project more than once.
echo "verifying:"
fail=0
# Only what this subset copied is asserted; the rest is upstream by design.
check() {
  local file="$1" needle="$2" what="$3"
  if grep -q "$needle" "$file"; then
    echo "  ok   $what"
  else
    echo "  MISSING $what in $(basename "$file")"
    fail=1
  fi
}
{ want folders || want scan || want sort; } && check "$J/model/Game.kt"       "val folder: String"  "Game.folder field"
{ want folders || want scan || want sort; } && check "$J/model/Game.kt"       "folderName"          "Game.folderName"
{ want folders || want scan || want sort; } && check "$J/utils/GameHelper.kt" "childFolder"         "folder carried through the scan"
{ want folders || want scan || want sort; } && check "$J/utils/GameHelper.kt" "deepScan) 8"         "scan depth raised to 8"
{ want folders || want sort; } && check "$J/ui/GamesFragment.kt" "groupByFolder"       "list grouped by folder"
want folders && check "$J/adapters/GameAdapter.kt" "model.folderName" "folder shown on the card"
want button && check "$J/views/FloatingGameButton.kt" "class FloatingGameButton" "floating button present"
want shared && check "$RES/layout/fragment_folders.xml" "button_shared_folder" "shared-folder button in the layout"
want shared && check "$J/fragments/GameFoldersFragment.kt" "processSharedFolder" "shared-folder button wired in"
check "$J/YuzuApplication.kt" "installCrashLogger" "crash logger"
check "$RES/values/strings.xml" "shared_folder_choose" "English labels"
check "$RES/values-ru/strings.xml" "shared_folder_choose" "Russian labels"
want button && check "$J/fragments/EmulationFragment.kt" "attachFloatingButton" "floating button wired in"
want button && check "$J/activities/EmulationActivity.kt" "PREF_KEEP_IN_MEMORY" "keep-in-memory switch"
want button && check "$J/activities/EmulationActivity.kt" "editingOverlayOnly" "no memory hold in overlay-edit mode"
want button && check "$J/views/FloatingGameButton.kt" "keepInMemory" "switch reachable from the button menu"

# The button must never pause the game. Anything matching here is a real call,
# not a comment - the comments are stripped first.
# Strip BOTH comment styles before looking. Stripping only // left the KDoc
# block at the top of the file - which explains, in prose, that nothing here
# pauses - and the check failed on its own documentation.
if want button && python3 -c "
import re, sys
src = open(sys.argv[1], encoding='utf-8').read()
src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
src = re.sub(r'//[^\n]*', '', src)
sys.exit(0 if re.search(r'pauseEmulation|emulationState\.pause', src) else 1)
" "$J/views/FloatingGameButton.kt"; then
  echo "  FAIL the floating button calls pause"
  fail=1
else
  echo "  ok   the floating button never pauses"
fi

echo "PATCH2_APPLIED files=$copied"
exit $fail
