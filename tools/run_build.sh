#!/bin/bash
export JAVA_HOME=/home/user/.toolchain/jdk-17.0.2
export ANDROID_HOME=/home/user/.toolchain/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/28.2.13676358
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:/usr/local/bin:/usr/bin:/bin
mkdir -p /work/tmp; export TMPDIR=/work/tmp
export GRADLE_USER_HOME=/work/gradle-home
cd /work/eden/src/android
exec ./gradlew -Djava.io.tmpdir=/work/tmp --no-daemon --max-workers=1 assembleLegacyDebug
