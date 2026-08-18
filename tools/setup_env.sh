#!/bin/bash
mkdir -p /home/user/.toolchain
curl -sL "https://download.java.net/java/GA/jdk17.0.2/dfd4a8d0985749f896bed50d7138ee7f/8/GPL/openjdk-17.0.2_linux-x64_bin.tar.gz" | tar xz -C /home/user/.toolchain
export JAVA_HOME=/home/user/.toolchain/jdk-17.0.2
export ANDROID_HOME=/home/user/.toolchain/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
mkdir -p $ANDROID_HOME/cmdline-tools
curl -sL -o /work/ct.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q /work/ct.zip -d /work/ct && mv /work/ct/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
rm -rf /work/ct.zip /work/ct
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --install "platform-tools" "platforms;android-36" "build-tools;35.0.0" "ndk;28.2.13676358" "cmake;3.31.6" >/dev/null 2>&1
sudo fallocate -l 5G /work/swap && sudo chmod 600 /work/swap && sudo mkswap /work/swap >/dev/null && sudo swapon /work/swap
echo "ENV_READY"
