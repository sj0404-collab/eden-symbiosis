#!/usr/bin/env bash
# Restores the Symbiosis tree into a fresh Eden checkout.
#
# Written as a script because copying these files by hand was forgotten twice,
# and each miss cost a full 30-minute build that appeared to succeed while
# silently shipping the old code.
#
# Paths are arguments rather than constants so the same script serves the local
# sandbox and a CI runner; hard-coding /work/eden meant CI needed its own copy,
# and two copies drift.
#
# Usage:
#   bash tools/apply_patch.sh                          # sandbox defaults
#   bash tools/apply_patch.sh --patch <dir> --eden <dir>
set -euo pipefail

PATCH_DIR="${PATCH_DIR:-/home/user/symbiosis-patch}"
EDEN_DIR="${EDEN_DIR:-/work/eden}"

while [ $# -gt 0 ]; do
  case "$1" in
    --patch) PATCH_DIR="$2"; shift 2 ;;
    --eden)  EDEN_DIR="$2";  shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# When run from a checkout of this repository, default to its own patch/ dir.
if [ ! -d "$PATCH_DIR" ]; then
  self_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if [ -d "$self_dir/../patch" ]; then
    PATCH_DIR="$(cd "$self_dir/../patch" && pwd)"
  fi
fi

[ -d "$PATCH_DIR" ] || { echo "patch directory not found: $PATCH_DIR" >&2; exit 1; }
[ -d "$EDEN_DIR" ]  || { echo "eden checkout not found: $EDEN_DIR"    >&2; exit 1; }

# PINNED TAG: this patch is cut against v0.2.1, not master. Upstream master
# carries b870bd255 "[android] config: load configuration on game start", which
# calls reloadGlobalConfig() on every launch; ReadPathValues() begins with
# game_dirs.clear(), so the configured game folders are wiped each time a game
# starts. v0.2.1 predates that.

P="$PATCH_DIR"
E="$EDEN_DIR"
A="$E/src/android/app/src/main"

echo "patch: $P"
echo "eden : $E"

cd "$E"

# --3way needs history, hence the depth-50 clone; the icon is excluded because
# a binary delete cannot be applied without a full index line.
# Capture the outcome rather than swallowing it. A shallow clone makes --3way
# fall back to direct application, which drops files and still exits 0 - the
# build then succeeds against a half-applied patch, which is worse than failing.
apply_log=$(git apply --3way --exclude='*ic_launcher_foreground.png' \
  "$P/upstream_changes.patch" 2>&1 || true)
printf '%s\n' "$apply_log" | grep -v cleanly || true

if printf '%s' "$apply_log" | grep -q "lacks the necessary blob"; then
  echo "ERROR: shallow checkout. git apply --3way needs full history:" >&2
  echo "  git clone --branch <tag> https://git.eden-emu.dev/eden-emu/eden.git" >&2
  exit 1
fi
if printf '%s' "$apply_log" | grep -q "patch does not apply"; then
  echo "ERROR: the patch does not apply to this checkout." >&2
  exit 1
fi
if git status --porcelain | grep -qE '^(UU|AA)'; then
  echo "ERROR: unresolved conflicts:" >&2
  git status --porcelain | grep -E '^(UU|AA)' >&2
  exit 1
fi
rm -f "$A/res/drawable/ic_launcher_foreground.png"

# C++ layer
mkdir -p "$E/src/common/symbiosis"
cp "$P"/symbiosis/*.cpp "$P"/symbiosis/*.h "$E/src/common/symbiosis/"

# Shader
cp "$P"/shaders/present_retro.frag "$E/src/video_core/host_shaders/"

# JNI bridge
cp "$P"/native_symbiosis.cpp "$A/jni/"

# Kotlin
J="$A/java/org/yuzu/yuzu_emu"
cp "$P"/android/fragments/*.kt "$J/fragments/"
cp "$P"/android/adapters/*.kt  "$J/adapters/"
cp "$P"/android/utils/*.kt     "$J/utils/"
# ui/ holds upstream screens this fork modifies wholesale rather than by diff.
if [ -d "$P/android/ui" ]; then
  cp "$P"/android/ui/*.kt "$J/ui/"
fi

# Resources
cp "$P"/android/layout/*.xml   "$A/res/layout/"
# navigation/ is only present once a screen has been added to the graph.
if [ -d "$P/android/navigation" ]; then
  cp "$P"/android/navigation/*.xml "$A/res/navigation/"
fi
cp "$P"/android/drawable/*.xml "$A/res/drawable/"
cp "$P"/android/values/strings-en.xml "$A/res/values/strings.xml"
cp "$P"/android/values/strings-ru.xml "$A/res/values-ru/strings.xml"

# The Kotlin daemon is OOM-killed on a small machine; in-process is slower to
# start but survives.
GP="$E/src/android/gradle.properties"
grep -q 'kotlin.compiler.execution.strategy' "$GP" 2>/dev/null || \
  echo 'kotlin.compiler.execution.strategy=in-process' >> "$GP"

changed=$(git status --porcelain | wc -l)
echo "PATCH_APPLIED files=$changed"
if [ "$changed" -lt 80 ]; then
  echo "ERROR: only $changed files changed; a complete apply touches ~87." >&2
  exit 1
fi
