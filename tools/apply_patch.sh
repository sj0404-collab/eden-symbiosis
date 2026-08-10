#!/bin/bash
# Restores the Symbiosis tree into a fresh Eden checkout.
# Written as a script because copying these by hand was forgotten twice and
# each miss cost a full 30-minute build.
set -e
P=/home/user/symbiosis-patch
E=/work/eden
A=$E/src/android/app/src/main

cd $E
git apply --3way --exclude='*ic_launcher_foreground.png' $P/upstream_changes.patch 2>&1 | grep -v cleanly || true
rm -f $A/res/drawable/ic_launcher_foreground.png

# C++ layer
mkdir -p $E/src/common/symbiosis
cp $P/symbiosis/*.cpp $P/symbiosis/*.h $E/src/common/symbiosis/

# Shader
cp $P/shaders/present_retro.frag $E/src/video_core/host_shaders/

# JNI bridge
cp $P/native_symbiosis.cpp $A/jni/

# Kotlin
J=$A/java/org/yuzu/yuzu_emu
cp $P/android/fragments/*.kt $J/fragments/
cp $P/android/adapters/*.kt $J/adapters/
cp $P/android/utils/*.kt    $J/utils/

# Resources
cp $P/android/layout/*.xml   $A/res/layout/
cp $P/android/drawable/*.xml $A/res/drawable/
cp $P/android/values/strings-en.xml $A/res/values/strings.xml
cp $P/android/values/strings-ru.xml $A/res/values-ru/strings.xml

echo "PATCH_APPLIED files=$(git status --porcelain | wc -l)"
