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
if want folders || want scan || want sort || want depth3; then
  copy_one "$PATCH_DIR/android/utils/GameHelper.kt"      "$J/utils/GameHelper.kt"
  # The folder lives here now, not on Game. Game.kt is left exactly as
  # upstream wrote it: it is @Parcelize and @Serializable, and every build
  # that added a field to it crashed on the device.
  cp "$PATCH_DIR/android/utils/GameFolders.kt" "$J/utils/GameFolders.kt"
  copied=$((copied + 1))
  echo "  copied GameFolders.kt (new file)"

  # ── depth3: folders, at upstream's own scan depth ─────────────────
  #
  # The user reports the crash looks like memory or interface. Depth is
  # the memory side: every level multiplies the directories walked, and
  # each game found becomes an object AND a JSON string held in one
  # SharedPreferences set that is read synchronously at startup. Going
  # 3 -> 8 can multiply a large library several times over.
  #
  # This subset keeps every folder feature and puts the depth back to
  # what upstream shipped, so the two variables are separated: if this
  # runs and the depth-8 build does not, the depth is the fault and the
  # code is fine.
  if want depth3; then
    true  # depth is already upstream's 3
    echo "  scan depth put back to upstream's 3"
  fi
fi
if want folders || want sort || want depth3; then
  copy_one "$PATCH_DIR/android/ui/GamesFragment.kt"      "$J/ui/GamesFragment.kt"
fi
if want folders || want depth3; then
  copy_one "$PATCH_DIR/android/adapters/GameAdapter.kt"  "$J/adapters/GameAdapter.kt"
fi


# "minimal" is the safest thing the patch can offer: the floating button and
# nothing else. It touches no list, no scan, no model - only a view added on
# top of the emulation surface, and only once a game is already running.
# "ui" is the interface half: the floating button and the shared-folder
# button, and NOT ONE of the four folder files. G proved the button alone
# runs; this adds the only other thing that draws.
if want button || want minimal || want ui; then
  copy_one "$PATCH_DIR/android/fragments/EmulationFragment.kt" "$J/fragments/EmulationFragment.kt"
  copy_one "$PATCH_DIR/android/activities/EmulationActivity.kt" "$J/activities/EmulationActivity.kt"
fi



# Layout and strings. The shared-folder button lives beside "add game folder",
# and its labels have to exist in both languages or the build fails on a
# missing resource - which is how R.string.select was caught before it cost a
# twenty-minute compile.
RES="$(cd "$J/../../../../res" && pwd)"

# ── Панель состояния на экране игр ──────────────────────────────────
#
# То, что было в старом Symbiosis и чего не хватает пользователю: что
# установлено, где лежит и сколько весит - ключи, прошивка, драйвер, игры,
# сейвы, шейдеры, плюс карточки папок с числом игр и размером.
#
# Читается заново при каждом открытии экрана. Мастер первого запуска
# показывал запомненный ответ и говорил "Готово" там, где ничего не было
# установлено - поэтому здесь только настоящее состояние файлов.
if want folders || want ui || want status; then
  cp "$PATCH_DIR/android/utils/SetupStatus.kt"        "$J/utils/SetupStatus.kt"
  cp "$PATCH_DIR/android/utils/GameFolderScanner.kt"  "$J/utils/GameFolderScanner.kt"
  mkdir -p "$J/adapters"
  cp "$PATCH_DIR/android/adapters/GameFolderAdapter.kt" "$J/adapters/GameFolderAdapter.kt"
  cp "$PATCH_DIR/android/layout/card_game_folder.xml"   "$RES/layout/card_game_folder.xml"
  # Панель "Мои игры": что лежит в папке, а не что разобрал эмулятор.
  cp "$PATCH_DIR/android/adapters/MyGamesAdapter.kt"    "$J/adapters/MyGamesAdapter.kt"
  cp "$PATCH_DIR/android/layout/card_my_games_folder.xml" "$RES/layout/card_my_games_folder.xml"
  copy_one "$PATCH_DIR/android/layout/fragment_games.xml" "$RES/layout/fragment_games.xml"
  copy_one "$PATCH_DIR/android/ui/GamesFragment.kt"       "$J/ui/GamesFragment.kt"
  # Корень данных применяется при старте. Без этого кнопка "Сменить папку
  # данных" держалась только до перезапуска: DirectoryInitialization
  # безусловно ставит getExternalFilesDir(), и эмулятор возвращался в свою
  # приватную папку вместе с ключами, прошивкой и списком игр.
  copy_one "$PATCH_DIR/android/utils/DirectoryInitialization.kt" "$J/utils/DirectoryInitialization.kt"
  copied=$((copied + 4))
  echo "  copied SetupStatus.kt, GameFolderScanner.kt, GameFolderAdapter.kt, card_game_folder.xml"
fi

# ── Strings are APPENDED, never overwritten ─────────────────────────
#
# These two files were the one thing present in every crashing build and
# absent from the only working one: they were copied for EVERY subset,
# including the narrowest, because the shared-folder labels live in them.
#
# Copying them wholesale replaces upstream's 1125 strings with my snapshot
# of them. If upstream changes one line - or if my copy was taken from a
# different revision - every screen that reads a moved string is wrong, and
# a missing one is a crash at inflate time with no hint of where it came
# from.
#
# So the six labels are inserted into upstream's own file instead. Nothing
# upstream wrote is touched, and there is no snapshot to drift.
add_strings() {
  local target="$1" src="$2"
  [ -f "$target" ] || return 0
  [ -f "$src" ] || return 0
  python3 - "$target" "$src" <<'ADDSTR'
import re, sys
target, src = sys.argv[1], sys.argv[2]
t = open(target, encoding='utf-8').read()
s = open(src, encoding='utf-8').read()

have = set(re.findall(r'<string name="([^"]+)"', t))
# Только наши строки, по именам. Целиком файл не копируется: снимок
# апстримовского strings.xml разошёлся бы с собираемой версией.
WANTED = ('shared_folder', 'status_', 'folders_open_list', 'folder_unreadable',
          'folder_game_count', 'my_games')
add = [m.group(0) for m in re.finditer(r'<string name="([^"]+)"[^>]*>.*?</string>', s, re.S)
       if m.group(1).startswith(WANTED) and m.group(1) not in have]
# plurals тоже: карточка папки показывает "2 игры" через folder_game_count
havep = set(re.findall(r'<plurals name="([^"]+)"', t))
add += [m.group(0) for m in re.finditer(r'<plurals name="([^"]+)".*?</plurals>', s, re.S)
        if m.group(1).startswith(WANTED) and m.group(1) not in havep]
if not add:
    print("    strings already present")
    sys.exit(0)

# Before the closing tag, so the file stays valid whatever else is in it.
i = t.rindex('</resources>')
t = t[:i] + '\n'.join('    ' + a for a in add) + '\n' + t[i:]
open(target, 'w', encoding='utf-8').write(t)
print(f"    added {len(add)} string(s)")
ADDSTR
  echo "  merged strings into $(basename "$(dirname "$target")")/strings.xml"
  copied=$((copied + 1))
}

add_strings "$RES/values/strings.xml"    "$PATCH_DIR/android/values/values__strings.xml"
add_strings "$RES/values-ru/strings.xml" "$PATCH_DIR/android/values/values-ru__strings.xml"

# The floating button is a new file with no upstream counterpart.
if { want button || want minimal || want ui; } && [ -f "$PATCH_DIR/android/views/FloatingGameButton.kt" ]; then
  mkdir -p "$J/views"
  cp "$PATCH_DIR/android/views/FloatingGameButton.kt" "$J/views/FloatingGameButton.kt"
  copied=$((copied + 1))
  echo "  copied FloatingGameButton.kt (new file)"
fi

if { want shared || want ui; } && [ -f "$PATCH_DIR/android/utils/SharedDataDirectory.kt" ]; then
  cp "$PATCH_DIR/android/utils/SharedDataDirectory.kt" "$J/utils/SharedDataDirectory.kt"
  copied=$((copied + 1))
  echo "  copied SharedDataDirectory.kt (new file)"
fi

# Prove the edits are actually present. A green build says nothing about
# whether a file was copied - that mistake has cost a full 30-minute build in
# this project more than once.
echo "verifying:"
# Game.kt must be untouched. Every build that changed it crashed.
if [ -f "$J/model/Game.kt" ] && grep -q "val folder: String" "$J/model/Game.kt"; then
  echo "  FAIL Game.kt was modified - it must stay upstream"
  fail=1
else
  echo "  ok   Game.kt left as upstream"
fi
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
{ want folders || want scan || want sort || want depth3; } && check "$J/utils/GameFolders.kt" "object GameFolders" "folder side table"
{ want folders || want scan || want sort || want depth3; } && check "$J/utils/GameHelper.kt" "GameFolders.remember" "scan records the folder"
{ want folders || want scan || want sort || want depth3; } && check "$J/utils/GameHelper.kt" "childFolder"         "folder carried through the scan"
{ want folders || want scan || want sort; } && check "$J/utils/GameHelper.kt" "deepScan) 3"         "scan depth is upstream's 3"

{ want folders || want sort || want depth3; } && check "$J/ui/GamesFragment.kt" "GameFolders.folderOf" "list grouped by folder"
{ want folders || want depth3; } && check "$J/adapters/GameAdapter.kt" "GameFolders.folderNameOf" "folder shown on the card"
{ want folders || want ui || want status; } && check "$J/utils/SetupStatus.kt" "object SetupStatus" "setup status panel"
{ want folders || want ui || want status; } && check "$RES/layout/fragment_games.xml" "status_strip" "status panel in the layout"
{ want folders || want ui || want status; } && check "$J/ui/GamesFragment.kt" "refreshStatusStrip" "status panel wired in"
{ want folders || want ui || want status; } && check "$J/ui/GamesFragment.kt" "SettingsSubscreen.GAME_FOLDERS" "folders button uses Eden's own route"
{ want folders || want ui || want status; } && check "$J/utils/DirectoryInitialization.kt" "SharedDataDirectory.configuredPath" "chosen data folder survives a restart"
{ want folders || want ui || want status; } && check "$J/utils/SharedDataDirectory.kt" "configuredPath = path" "chosen data folder is saved"
{ want folders || want ui || want status; } && check "$J/adapters/MyGamesAdapter.kt" "listFilesFlat" "my-games panel lists real files"
{ want folders || want ui || want status; } && check "$RES/layout/fragment_games.xml" "my_games_list" "my-games panel in the layout"
{ want folders || want ui || want status; } && check "$J/utils/SetupStatus.kt" "архив не распакован" "firmware tells you to unpack it"
{ want button || want minimal || want ui; } && check "$J/views/FloatingGameButton.kt" "class FloatingGameButton" "floating button present"
check "$J/YuzuApplication.kt" "installCrashLogger" "crash logger"
check "$RES/values/strings.xml" "shared_folder_choose" "English labels"
check "$RES/values-ru/strings.xml" "shared_folder_choose" "Russian labels"
{ want button || want minimal || want ui; } && check "$J/fragments/EmulationFragment.kt" "attachFloatingButton" "floating button wired in"
{ want button || want minimal || want ui; } && check "$J/activities/EmulationActivity.kt" "PREF_KEEP_IN_MEMORY" "keep-in-memory switch"
{ want button || want minimal || want ui; } && check "$J/activities/EmulationActivity.kt" "editingOverlayOnly" "no memory hold in overlay-edit mode"
{ want button || want minimal || want ui; } && check "$J/views/FloatingGameButton.kt" "keepInMemory" "switch reachable from the button menu"

# The button must never pause the game. Anything matching here is a real call,
# not a comment - the comments are stripped first.
# Strip BOTH comment styles before looking. Stripping only // left the KDoc
# block at the top of the file - which explains, in prose, that nothing here
# pauses - and the check failed on its own documentation.
if { want button || want minimal || want ui; } && python3 -c "
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
