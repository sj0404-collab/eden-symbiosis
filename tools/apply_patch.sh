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

# Resolved before any cd, otherwise BASH_SOURCE is relative to the new cwd.
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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
  if [ -d "$SELF_DIR/../patch" ]; then
    PATCH_DIR="$(cd "$SELF_DIR/../patch" && pwd)"
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
# Merge, never replace. A wholesale copy silently drops any upstream string the
# fork's file predates: build #10 failed on "resource string/off not found"
# because v0.2.1's arrays.xml references strings my master-derived copy lacked.
python3 "$SELF_DIR/merge_strings.py" \
  "$A/res/values/strings.xml"    "$P/android/values/strings-en.xml" "$A/res/values/strings.xml"
python3 "$SELF_DIR/merge_strings.py" \
  "$A/res/values-ru/strings.xml" "$P/android/values/strings-ru.xml" "$A/res/values-ru/strings.xml"

# The Kotlin daemon is OOM-killed on a small machine; in-process is slower to
# start but survives.
GP="$E/src/android/gradle.properties"
# Gradle tuning depends on the machine, so decide it here rather than shipping
# one setting that is wrong everywhere.
#
# Upstream ships deliberately tiny limits (1.2 GB heap, one worker, no
# parallelism) so the build survives a small laptop. On a CI runner with four
# cores and 16 GB that is the difference between ~10 and ~25 minutes, and the
# native compile is entirely CPU-bound.
total_kb=$(awk '/MemTotal/{print $2}' /proc/meminfo 2>/dev/null || echo 0)
cores=$(nproc 2>/dev/null || echo 2)

python3 - "$GP" "$total_kb" "$cores" <<'PYTUNE'
import sys

gp, total_kb, cores = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
text = open(gp).read() if __import__("os").path.exists(gp) else ""
big = total_kb >= 7_000_000  # ~7 GB and up counts as a real build machine

if big:
    # Leave headroom for the Kotlin daemon and the C++ compilers, which live
    # outside the Gradle JVM.
    heap = min(6144, max(2048, total_kb // 1024 // 3))
    settings = {
        "org.gradle.jvmargs": f"-Xms512m -Xmx{heap}m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
        "org.gradle.workers.max": str(cores),
        "org.gradle.parallel": "true",
        "org.gradle.caching": "true",
        "kotlin.parallel.tasks.in.project": "true",
        # A daemon can hold the Kotlin compiler in memory; only safe when there
        # is enough RAM that the OOM killer will not take it out mid-build.
        "kotlin.compiler.execution.strategy": "daemon",
        "kotlin.daemon.jvmargs": "-Xmx2g",
    }
else:
    settings = {
        "org.gradle.workers.max": "1",
        "org.gradle.parallel": "false",
        # In-process, because a separate Kotlin daemon is the first thing the
        # OOM killer takes on a 2 GB machine.
        "kotlin.compiler.execution.strategy": "in-process",
    }

lines, seen = [], set()
for line in text.splitlines():
    key = line.split("=", 1)[0].strip()
    if key in settings:
        if key in seen:
            continue
        seen.add(key)
        lines.append(f"{key}={settings[key]}")
    else:
        lines.append(line)

for key, value in settings.items():
    if key not in seen:
        lines.append(f"{key}={value}")

open(gp, "w").write("\n".join(lines) + "\n")
print(f"gradle tuned for {'a large' if big else 'a small'} machine: "
      f"{total_kb // 1024} MB RAM, {cores} cores")
PYTUNE

changed=$(git status --porcelain | wc -l)
echo "PATCH_APPLIED files=$changed"
if [ "$changed" -lt 80 ]; then
  echo "ERROR: only $changed files changed; a complete apply touches ~87." >&2
  exit 1
fi
