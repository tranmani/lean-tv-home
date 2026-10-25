#!/bin/bash
# Builds Lean TV Home without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
#   ./build.sh <versionCode> [versionName]
# Needs JDK 11+ and the Android SDK (build-tools 35.0.0, platform android-34) in $ANDROID_HOME
# (default ~/android-sdk). Signs with the debug keystore unless KEYSTORE and KEYSTORE_PASS_FILE
# point at a release keystore (alias "release").
set -euo pipefail
cd "$(dirname "$0")"
VC=${1:-1}; VN=${2:-1.0.$VC}
SDK=${ANDROID_HOME:-$HOME/android-sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/unsigned.apk build/res.zip \
  --java build/gen --min-sdk-version 26 --target-sdk-version 34 --version-code "$VC" --version-name "$VN"
javac -nowarn -Xlint:-options -source 11 -target 11 -encoding UTF-8 -classpath "$JAR" -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8" --release --lib "$JAR" --min-api 26 --output build/dex $(find build/classes -name '*.class')
cp build/unsigned.apk build/app.apk
(cd build/dex && zip -q ../app.apk classes.dex)
"$BT/zipalign" -f -p 4 build/app.apk build/aligned.apk
if [ -n "${KEYSTORE:-}" ]; then
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias release --ks-pass "file:$KEYSTORE_PASS_FILE" \
    --out build/lean-tv-home.apk build/aligned.apk
else
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out build/lean-tv-home.apk build/aligned.apk
fi
ls -l build/lean-tv-home.apk
