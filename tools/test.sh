#!/usr/bin/env bash
# Runs the JVM unit tests (protocol logic against a simulated ELM327 + car) without Gradle.
# Uses the same tool locations as build.sh; JUNIT_CP must point at junit-4.13.2 + hamcrest-core-1.3.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/test"
ANDROID_JAR="${ANDROID_JAR:-${ANDROID_HOME:-/opt/android-sdk}/platforms/android-34/android.jar}"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/buildtools/kotlinc}"
JUNIT_CP="${JUNIT_CP:-/opt/buildtools/testlibs/junit-4.13.2.jar:/opt/buildtools/testlibs/hamcrest-core-1.3.jar}"

rm -rf "$OUT"
mkdir -p "$OUT"
# Only the protocol layers: they don't depend on Android at runtime.
"$KOTLIN_HOME/bin/kotlinc" -nowarn -jvm-target 1.8 \
  -cp "$ANDROID_JAR:$JUNIT_CP" -d "$OUT" \
  $(find "$ROOT/app/src/main/java/com/mpsdiag"/{diag,elm,transport} -name '*.kt') \
  $(find "$ROOT/app/src/test/java" -name '*.kt') 2>&1 | grep -v JAVA_TOOL_OPTIONS || true

# Test classes are the *Test.kt files under app/src/test/java.
TESTS=$(cd "$ROOT/app/src/test/java" && find . -name '*Test.kt' | sed 's#^\./##; s#\.kt$##; s#/#.#g')
java -cp "$OUT:$JUNIT_CP:$KOTLIN_HOME/lib/kotlin-stdlib.jar" org.junit.runner.JUnitCore $TESTS 2>&1 | grep -v JAVA_TOOL_OPTIONS
