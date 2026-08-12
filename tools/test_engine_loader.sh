#!/usr/bin/env bash
# Type-check and exercise EngineLoader/EngineDownloader without an Android
# device or the SDK.
#
# These two files decide whether the second engine can be fetched and loaded at
# all, and every failure mode they guard against is silent: a truncated
# download that dlopen() maps and then dies inside, a file left writable that
# Android 14+ refuses, a hash that was never checked. A green build says
# nothing about any of that, so the logic is run here against the real core.
#
# Usage: bash tools/test_engine_loader.sh [--core /path/to/libkenji.so]
set -euo pipefail

KOTLINC="${KOTLINC:-/tmp/kotlinc/bin/kotlinc}"
CORE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --core) CORE="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -x "$KOTLINC" ] || { echo "kotlinc not found at $KOTLINC" >&2; exit 1; }

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/src"

cp "$ROOT/patch/android/utils/EngineLoader.kt" "$WORK/src/"
cp "$ROOT/patch/android/utils/EngineDownloader.kt" "$WORK/src/"
cp "$ROOT/tools/engine-test/"*.kt "$WORK/src/"

# The real core, if one was handed over: without it the happy path is never
# exercised and only the failure cases are.
if [ -n "$CORE" ] && [ -f "$CORE" ]; then
  [ "$(readlink -f "$CORE")" = "/tmp/dl.so" ] || cp "$CORE" /tmp/dl.so
  echo "using the real core: $CORE"
else
  rm -f /tmp/dl.so
  echo "no core given - the success path will be skipped"
fi

rm -rf /tmp/engines
"$KOTLINC" "$WORK/src"/*.kt -include-runtime -d "$WORK/run.jar" 2>&1 |
  grep -vE "^warning: (classpath|language version)" || true
java -Dfile.encoding=UTF-8 -jar "$WORK/run.jar"
