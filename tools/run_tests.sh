#!/usr/bin/env bash
# Builds and runs the host test suite against a patched Eden checkout.
#
# Two things here are easy to get wrong and both make the suite worthless:
#
#  1. Do NOT put <eden>/src/common on the include path. Eden ships its own
#     common/assert.h, which shadows the standard <cassert>; every assert()
#     then expands to nothing and the tests pass without checking anything.
#     Only -I<eden>/src is correct.
#
#  2. Eden's logging is a real dependency of the layer. Rather than linking
#     half the emulator, a tiny stub supplies FmtLogMessageImpl.
#
# Usage: bash tools/run_tests.sh [--eden <dir>]
set -uo pipefail

EDEN_DIR="${EDEN_DIR:-/tmp/citest}"
# A test that will not link proves nothing. Allow a known few (they need most
# of video_core), but fail if the number grows - that means the environment is
# broken, not the code.
MAX_SKIP="${MAX_SKIP:-99}"
while [ $# -gt 0 ]; do
  case "$1" in
    --max-skip) MAX_SKIP="$2"; shift 2 ;;
    --eden) EDEN_DIR="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

E="$EDEN_DIR"
S="$E/src/common/symbiosis"
[ -d "$S" ] || { echo "patched Eden not found at $E (run tools/apply_patch.sh first)" >&2; exit 1; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT

# Minimal stand-in for Eden's logging backend.
cat > "$BUILD/log_stub.cpp" <<'STUB'
#include <cstdarg>
#include <string_view>
#include "common/logging.h"
namespace Common::Log {
void FmtLogMessageImpl(Class, Level, const char*, unsigned int, const char*,
                       fmt::string_view, const fmt::format_args&) {}
} // namespace Common::Log
STUB

CORE="$S/symbiosis_log.cpp $S/symbiosis_types.cpp $BUILD/log_stub.cpp"
# Settings::values lives here; several tests read or write real settings.
SETTINGS="$E/src/common/settings.cpp"

deps_for() {
  case "$1" in
    t_mali|t_parse) echo "$S/mali_tuning.cpp $CORE" ;;
    t_fw)           echo "$S/firmware_vault.cpp $CORE" ;;
    t_rom)          echo "$S/rom_tools.cpp $CORE" ;;
    t_save)         echo "$S/save_guard.cpp $S/thermal_monitor.cpp $CORE" ;;
    t_guard)        echo "$CORE" ;;
    t_modes)        echo "$S/auto_modes.cpp $S/device_profiles.cpp $SETTINGS $CORE" ;;
    t_uaf)          echo "$S/memory_governor.cpp $CORE" ;;
    t_v12)          echo "$S/device_profiles.cpp $S/auto_modes.cpp $SETTINGS $CORE" ;;
    t_fps)          echo "$S/device_profiles.cpp $S/auto_modes.cpp $SETTINGS $CORE" ;;
    *)              echo "" ;;
  esac
}

pass=0; fail=0; skip=0; failed=""
for f in "$HERE"/tests/t_*.cpp; do
  [ -e "$f" ] || continue
  n=$(basename "$f" .cpp)
  # shellcheck disable=SC2046
  if g++ -std=c++20 -O1 -I"$E/src" -o "$BUILD/$n" "$f" $(deps_for "$n") -lfmt \
       > "$BUILD/$n.err" 2>&1; then
    if out=$("$BUILD/$n" 2>&1); then
      echo "  PASS  $n — $(printf '%s' "$out" | tail -1)"; pass=$((pass+1))
    else
      echo "  FAIL  $n"; printf '%s\n' "$out" | tail -5 | sed 's/^/        /'
      fail=$((fail+1)); failed="$failed $n"
    fi
  else
    # A test whose dependencies are not wired up is reported, never hidden.
    # A test that will not build is reported with the real reason, never a
    # blank. An empty parenthesis told me nothing on CI and cost a whole run.
    reason=$(grep -m1 -E "undefined reference|error:|fatal error:" "$BUILD/$n.err" | head -1)
    echo "  SKIP  $n"
    echo "        reason: ${reason:-unknown}"
    sed 's/^/        | /' "$BUILD/$n.err" | head -6
    skip=$((skip+1))
  fi
done

# Kotlin tests, when a compiler is available.
if command -v kotlinc >/dev/null 2>&1; then
  for f in "$HERE"/tests/*.kt; do
    [ -e "$f" ] || continue
    n=$(basename "$f" .kt)
    kotlinc "$f" -include-runtime -d "$BUILD/$n.jar" 2>/dev/null
    if out=$(java -jar "$BUILD/$n.jar" 2>&1); then
      echo "  PASS  $n — $(printf '%s' "$out" | tail -1)"; pass=$((pass+1))
    else
      echo "  FAIL  $n"; printf '%s\n' "$out" | tail -8 | sed 's/^/        /'
      fail=$((fail+1)); failed="$failed $n"
    fi
  done
else
  echo "  note: kotlinc not on PATH, Kotlin tests not run"
fi

echo
echo "passed=$pass failed=$fail skipped=$skip"
[ "$fail" -eq 0 ] || { echo "failing:$failed"; exit 1; }
if [ "$skip" -gt "$MAX_SKIP" ]; then
  echo "too many tests could not be built ($skip > $MAX_SKIP): the toolchain is"
  echo "missing something - libfmt-dev, or the patched Eden tree."
  exit 1
fi
