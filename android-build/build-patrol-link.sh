#!/usr/bin/env bash
set -euo pipefail
ROOT="$(pwd)"
WORK="/tmp/ronin-patrol-android"
rm -rf "$WORK"
mkdir -p "$WORK/src" "$ROOT/update-site"

cat "$ROOT"/android-build/patrol131/part* | base64 -d > "$WORK/source.tar.gz"
echo "b37e951c9e65c5c8322bb0636ad552f86a0180d0ead634610d52423fc49230b9  $WORK/source.tar.gz" | sha256sum -c -
tar -xzf "$WORK/source.tar.gz" -C "$WORK/src"

JDK="$WORK/jdk"
curl -fsSL "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" -o "$WORK/jdk.tar.gz"
mkdir -p "$JDK"
tar -xzf "$WORK/jdk.tar.gz" -C "$JDK" --strip-components=1
export JAVA_HOME="$JDK"
export PATH="$JAVA_HOME/bin:$PATH"

curl -fsSL "https://services.gradle.org/distributions/gradle-8.13-bin.zip" -o "$WORK/gradle.zip"
unzip -q "$WORK/gradle.zip" -d "$WORK"
export PATH="$WORK/gradle-8.13/bin:$PATH"

SDK="$WORK/android-sdk"
mkdir -p "$SDK/cmdline-tools"
curl -fsSL "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" -o "$WORK/android-cli.zip"
unzip -q "$WORK/android-cli.zip" -d "$SDK/cmdline-tools"
mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

cd "$WORK/src"
gradle --no-daemon :app:assembleDebug

APK="$WORK/src/app/build/outputs/apk/debug/app-debug.apk"
cp "$APK" "$ROOT/update-site/Ronin-Patrol-Link-latest.apk"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
printf '{"versionCode":141,"versionName":"1.1.31","sha256":"%s","apkUrl":"https://ronin-patrol-link-channel.onrender.com/Ronin-Patrol-Link-latest.apk"}' "$SHA" > "$ROOT/update-site/update.json"
printf 'Patrol Link v1.1.31 update channel\nSHA-256: %s\n' "$SHA" > "$ROOT/update-site/index.txt"

echo "APK_B64_BEGIN"
base64 -w 12000 "$ROOT/update-site/Ronin-Patrol-Link-latest.apk" | awk '{printf("APKCHUNK%05d:%s\\n", NR-1, $0)}'
echo "APK_B64_END"
