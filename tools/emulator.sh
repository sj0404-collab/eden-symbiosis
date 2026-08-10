#!/usr/bin/env bash
# Creates and boots an Android emulator for testing Eden Symbiosis.
#
# READ THIS FIRST - it decides whether the emulator is any use to you.
#
#   The APK is arm64-v8a only; build.gradle.kts pins abiFilters("arm64-v8a")
#   and the APK contains zero x86_64 libraries. An x86_64 system image
#   therefore cannot install it.
#
#   That forces an arm64 image. On an x86_64 host with no /dev/kvm, every guest
#   instruction is translated by QEMU TCG. Boot takes tens of minutes, and
#   Vulkan inside the guest is SwiftShader - a software rasteriser - not Mali.
#
#   So this emulator CAN verify: the app installs, starts, the setup pages
#   render, which buttons exist and whether they are enabled.
#   It CANNOT reproduce: Mali driver behaviour, real frame rates, ASTC/BC
#   support, thermal throttling, or a crash that depends on any of those.
#
#   For those, a physical device plus tools/collect_logs.sh is the only answer.
#
# Usage:
#   bash tools/emulator.sh create
#   bash tools/emulator.sh start
#   bash tools/emulator.sh ui-test <apk>    # install, launch, dump the screen
#   bash tools/emulator.sh stop
set -uo pipefail

: "${ANDROID_HOME:=$HOME/.toolchain/android-sdk}"
: "${JAVA_HOME:=$HOME/.toolchain/jdk-17.0.2}"
export ANDROID_HOME JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

AVD="eden_arm64"
IMAGE="system-images;android-30;google_apis;arm64-v8a"
PKG="dev.legacy.eden_emulator.debug"

case "${1:-}" in
create)
  echo "Downloading $IMAGE - large, and slow to run. See the notes above."
  yes | sdkmanager --licenses >/dev/null 2>&1
  sdkmanager --install "$IMAGE" "emulator" || exit 1
  echo no | avdmanager create avd -n "$AVD" -k "$IMAGE" --device pixel_5 --force
  cfg="$HOME/.android/avd/$AVD.avd/config.ini"
  {
    echo "hw.ramSize=1536"
    echo "vm.heapSize=256"
    echo "hw.gpu.enabled=yes"
    echo "hw.gpu.mode=swiftshader_indirect"
    echo "disk.dataPartition.size=4G"
  } >> "$cfg"
  echo "AVD created: $AVD"
  ;;

start)
  command -v emulator >/dev/null 2>&1 || { echo "run: bash tools/emulator.sh create"; exit 1; }
  nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim \
    -gpu swiftshader_indirect -memory 1536 -no-snapshot > /tmp/emulator.log 2>&1 &
  echo "booting (arm64 under TCG - this takes a long time)"
  adb wait-for-device
  for i in $(seq 1 240); do
    if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
      echo "booted after ~$((i*15))s"; adb shell input keyevent 82; exit 0
    fi
    sleep 15
  done
  echo "did not finish booting; see /tmp/emulator.log"; exit 1
  ;;

install)
  adb install -r -g "${2:?usage: emulator.sh install <apk>}"
  ;;

ui-test)
  APK="${2:?usage: emulator.sh ui-test <apk>}"
  adb install -r -g "$APK" || exit 1
  adb logcat -c
  adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 25
  mkdir -p ui-test
  # The view hierarchy is the objective check: it proves whether a button
  # exists and whether it is enabled. A screenshot cannot show "disabled".
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml ui-test/ >/dev/null 2>&1
  adb exec-out screencap -p > ui-test/screen.png 2>/dev/null
  adb logcat -d > ui-test/logcat.txt 2>&1
  echo "--- text on screen ---"
  grep -oP 'text="\K[^"]+' ui-test/ui.xml 2>/dev/null | grep -v '^$' || echo "(no dump)"
  echo "--- disabled controls ---"
  python3 - <<'PY' 2>/dev/null || true
import re, sys
try: x = open('ui-test/ui.xml').read()
except Exception: sys.exit()
for m in re.finditer(r'<node[^>]*>', x):
    t = re.search(r'text="([^"]*)"', m.group(0))
    e = re.search(r'enabled="([^"]*)"', m.group(0))
    if t and t.group(1) and e and e.group(1) == 'false':
        print('  disabled:', t.group(1))
PY
  echo "artefacts in ui-test/"
  ;;

stop)
  adb emu kill 2>/dev/null || pkill -f "emulator.*$AVD" || true
  echo stopped
  ;;

*)
  sed -n '2,20p' "$0"
  ;;
esac
