#!/usr/bin/env bash
# Builds and signs the APK without Gradle / Android Studio, using only command-line tools.
#
# Requirements (paths can be overridden with the environment variables below):
#   ANDROID_JAR  android.jar of platform 34 (from an SDK: platforms/android-34/android.jar)
#   KOTLIN_HOME  Kotlin compiler distribution (contains bin/kotlinc and lib/kotlin-stdlib.jar)
#   R8_JAR       r8.jar (provides the D8 dexer)
#   aapt2, zipalign, apksigner, javac on PATH (or AAPT2/ZIPALIGN/APKSIGNER variables)
#
# Output: build/mps-scan-tool.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="$ROOT/build"

# Platform 34 is used for linking because older aapt2 builds cannot read the android-35 resource table.
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar}"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/buildtools/kotlinc}"
R8_JAR="${R8_JAR:-/opt/buildtools/r8.jar}"
AAPT2="${AAPT2:-aapt2}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
KEYSTORE="${KEYSTORE:-$ROOT/signing/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"

MIN_SDK=23
TARGET_SDK=34
VERSION_CODE="$(grep -E '^\s*versionCode\s*=' "$ROOT/app/build.gradle.kts" | grep -oE '[0-9]+')"
VERSION_NAME="$(grep -E '^\s*versionName\s*=' "$ROOT/app/build.gradle.kts" | grep -oE '"[^"]+"' | tr -d '"')"

rm -rf "$OUT"
mkdir -p "$OUT/res" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> Compiling resources"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/res/compiled.zip"

echo "==> Linking resources"
# Gradle supplies the package name from build.gradle.kts (namespace); add it for aapt2.
NAMESPACE="$(grep -E '^\s*namespace\s*=' "$ROOT/app/build.gradle.kts" | grep -oE '"[^"]+"' | tr -d '"')"
sed "s#<manifest #<manifest package=\"$NAMESPACE\" #" "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT2" link -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  -o "$OUT/base.apk" "$OUT/res/compiled.zip"

echo "==> Compiling R.java"
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -cp "$ANDROID_JAR" \
  -d "$OUT/classes" $(find "$OUT/gen" -name '*.java') 2>&1 | grep -v -E 'JAVA_TOOL_OPTIONS|bootstrap classpath|obsolete|warning' || true

echo "==> Compiling Kotlin"
"$KOTLIN_HOME/bin/kotlinc" -nowarn -jvm-target 1.8 \
  -cp "$ANDROID_JAR:$OUT/classes" \
  -d "$OUT/classes" \
  $(find "$APP/java" -name '*.kt') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
test -n "$(find "$OUT/classes" -path '*mpsdiag/ui/*.class' -print -quit)" || { echo "Kotlin compilation failed"; exit 1; }

echo "==> Dexing"
(cd "$OUT/classes" && find . -name '*.class' > "$OUT/classlist.txt")
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api "$MIN_SDK" \
  --lib "$ANDROID_JAR" --output "$OUT/dex" \
  $(sed "s#^\./#$OUT/classes/#" "$OUT/classlist.txt") \
  "$KOTLIN_HOME/lib/kotlin-stdlib.jar" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
test -f "$OUT/dex/classes.dex" || { echo "Dexing failed"; exit 1; }

echo "==> Packaging"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -j "$OUT/unsigned.apk" classes*.dex)
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> Signing"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --ks-key-alias "$KEY_ALIAS" \
  --min-sdk-version "$MIN_SDK" --out "$OUT/mps-scan-tool.apk" "$OUT/aligned.apk" 2>&1 | grep -v JAVA_TOOL_OPTIONS || true
"$APKSIGNER" verify -v "$OUT/mps-scan-tool.apk" 2>&1 | grep -E "^Verifie[ds]" || { echo "Signature verification failed"; exit 1; }
echo "Built $OUT/mps-scan-tool.apk ($VERSION_NAME / $VERSION_CODE)"
