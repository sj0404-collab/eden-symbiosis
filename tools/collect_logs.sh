#!/usr/bin/env bash
# Collects everything needed to diagnose an Eden Symbiosis failure from a
# physical device, over ADB, into one archive you can send back.
#
# Nothing here is guesswork: it captures the native crash reason, low-memory
# kills, the Symbiosis layer's own log and the state of the data root - the
# places a cause can actually hide.
#
# Usage (device connected, USB debugging on):
#   bash tools/collect_logs.sh          # capture until Ctrl-C
#   bash tools/collect_logs.sh 60       # capture for 60 seconds
set -uo pipefail

PKG="dev.legacy.eden_emulator.debug"
SECONDS_TO_RUN="${1:-0}"
OUT="eden-logs-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb not found. Install platform-tools, or use"
  echo "  \$ANDROID_HOME/platform-tools/adb"
  exit 1
fi

if [ "$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)" -eq 0 ]; then
  echo "No device. Enable USB debugging and accept the RSA prompt."
  echo "Wireless: adb pair <host:port>, then adb connect <host:port>"
  exit 1
fi

echo "== device =="
adb shell getprop ro.product.model 2>/dev/null | tee "$OUT/device.txt"
{
  echo "--- properties ---"
  for p in ro.product.model ro.product.manufacturer ro.build.version.release \
           ro.build.version.sdk ro.soc.model ro.hardware.vulkan dalvik.vm.heapsize; do
    printf '%s = %s\n' "$p" "$(adb shell getprop "$p" 2>/dev/null | tr -d '\r')"
  done
  echo "--- memory ---"
  adb shell cat /proc/meminfo 2>/dev/null | head -5
} >> "$OUT/device.txt" 2>&1

# A cleared buffer means the capture contains this run and nothing else.
adb logcat -c 2>/dev/null || true

echo "== capturing. Start the game now. Ctrl-C when it fails. =="
if [ "$SECONDS_TO_RUN" -gt 0 ]; then
  timeout "$SECONDS_TO_RUN" adb logcat -v threadtime > "$OUT/logcat-full.txt" 2>&1 || true
else
  adb logcat -v threadtime > "$OUT/logcat-full.txt" 2>&1 || true
fi

echo
echo "== extracting =="
grep -aiE "eden|yuzu|symbiosis|vulkan|mali" "$OUT/logcat-full.txt" \
  > "$OUT/logcat-eden.txt" 2>/dev/null || true

# The signal line names a native crash far more precisely than any in-app text.
grep -aE "FATAL|SIGSEGV|SIGABRT|signal |backtrace|tombstone|abort message" \
  "$OUT/logcat-full.txt" > "$OUT/crash.txt" 2>/dev/null || true

grep -aA 30 "FATAL EXCEPTION" "$OUT/logcat-full.txt" \
  > "$OUT/exception.txt" 2>/dev/null || true

# An 8 GB device with ~5 GB usable does hit these.
grep -aiE "lowmemorykiller|lmkd|Out of memory|oom_|Kill '$PKG'" \
  "$OUT/logcat-full.txt" > "$OUT/oom.txt" 2>/dev/null || true

adb shell "run-as $PKG cat files/symbiosis.log" > "$OUT/symbiosis.log" 2>/dev/null || true
adb shell "cat /storage/emulated/0/Eden/files/log/eden_log.txt" \
  > "$OUT/eden_log.txt" 2>/dev/null || true

# Which data root is in use, and does it actually hold anything?
{
  echo "--- shared folder ---"
  adb shell 'ls -la /storage/emulated/0/Eden/files 2>/dev/null | head -20'
  echo "--- keys ---"
  adb shell 'ls -la /storage/emulated/0/Eden/files/keys 2>/dev/null'
  echo "--- registered firmware (file count) ---"
  adb shell 'ls /storage/emulated/0/Eden/files/nand/system/Contents/registered 2>/dev/null | wc -l'
  echo "--- config.ini [Paths] ---"
  adb shell 'grep -A 12 "\[Paths\]" /storage/emulated/0/Eden/files/config/config.ini 2>/dev/null'
} > "$OUT/data-root.txt" 2>&1

for f in crash.txt exception.txt oom.txt; do
  [ -s "$OUT/$f" ] && echo "  !! $f has content - look here first"
done

tar czf "$OUT.tar.gz" "$OUT" 2>/dev/null
echo
echo "Written: $OUT.tar.gz"
echo "Send that. If it is too large, send $OUT/crash.txt and $OUT/logcat-eden.txt."
