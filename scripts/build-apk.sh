#!/usr/bin/env bash
#
# Build NobiTraderBot.apk with the raw Android toolchain (no Gradle).
#
# Local (sandbox) defaults can be overridden through env vars:
#   AAPT2, JAVAC, JAVA8 (java for SelfTest), JAVA_D8 (java for r8), R8JAR, PLATFORM, D8
# On a GitHub Actions runner (or any machine with a normal SDK) set:
#   BT=/path/to/build-tools/34.0.0 PLATFORM=/path/to/platforms/android-34/android.jar
# and javac/java must be on PATH; apksigner from BT is used for signing.
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OUT_NAME="${OUT_NAME:-NobiTrader-v2.1.apk}"
BUILD=build
OUT=release

# ----------------------------------------------------------- toolchain
AAPT2="${AAPT2:-/tmp/npmbin/aaptjs3/package/bin/x64/linux/aapt2}"
JAVAC="${JAVAC:-/tmp/jdk8/linux-x86/bin/javac}"
JAVA8="${JAVA8:-/tmp/jdk8/linux-x86/bin/java}"
JAVA_D8="${JAVA_D8:-/tmp/venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java}"
R8JAR="${R8JAR:-/tmp/r8repo/r8.jar}"
PLATFORM="${PLATFORM:-/tmp/sdkjar/33/public/android.jar}"

if [ -n "${BT:-}" ]; then
  # real Android SDK build-tools available (CI runner)
  AAPT2="$BT/aapt2"
  D8="$BT/d8"
  APKSIGNER="$BT/apksigner"
  JAVAC="${JAVAC_OVERRIDE:-javac}"
  JAVA8="${JAVA8_OVERRIDE:-java}"
fi

# we bundle org/json (JSON-java) in src/org/json; the platform jar copy is
# stripped after the build dirs are created (see below)

echo "== toolchain =="
echo "aapt2:   $AAPT2"
echo "javac:   $JAVAC"
echo "d8:      ${D8:-$JAVA_D8 -cp $R8JAR com.android.tools.r8.D8}"
echo "platform: $PLATFORM"
"$AAPT2" version

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD" "$OUT" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

# strip org/json from the platform jar so javac never mixes our bundled
# JSON-java sources with the binary copies inside the platform
PLATFORM_JAR="$ROOT/$BUILD/platform-android.jar"
cp "$PLATFORM" "$PLATFORM_JAR"
zip -q -d "$PLATFORM_JAR" 'org/json/*' || true
PLATFORM="$PLATFORM_JAR"

echo "== [1/6] compile resources (aapt2) =="
"$AAPT2" compile --dir res -o "$BUILD/res.zip"

echo "== [2/6] link resources (aapt2) =="
"$AAPT2" link -o "$BUILD/app-base.apk" -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 24 --target-sdk-version 33 \
  --version-code 15 --version-name 2.1 \
  --java "$BUILD/gen" \
  "$BUILD/res.zip"

echo "== [3/6] compile java (javac) =="
find src -name '*.java' > "$BUILD/sources.txt"
if [ -d "$BUILD/gen" ] && [ -n "$(find "$BUILD/gen" -name '*.java' 2>/dev/null)" ]; then
  find "$BUILD/gen" -name '*.java' >> "$BUILD/sources.txt"
fi
"$JAVAC" -encoding UTF-8 -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$PLATFORM" \
  -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "== [4/6] self-test (pure JVM) =="
"$JAVA8" -cp "$BUILD/classes" ir.nobitrader.bot.SelfTest

echo "== [5/6] dex (d8) =="
if [ -n "${D8:-}" ]; then
  (cd "$BUILD/classes" && "$D8" --release --lib "$PLATFORM" --min-api 24 \
    --output "$ROOT/$BUILD/dex" $(find . -name '*.class'))
else
  (cd "$BUILD/classes" && "$JAVA_D8" -cp "$R8JAR" com.android.tools.r8.D8 \
    --release --lib "$PLATFORM" --min-api 24 \
    --output "$ROOT/$BUILD/dex" $(find . -name '*.class'))
fi

cp "$BUILD/app-base.apk" "$BUILD/app-unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$ROOT/$BUILD/app-unsigned.apk" classes.dex)

echo "== [6/6] sign =="
if [ -n "${APKSIGNER:-}" ]; then
  "$APKSIGNER" sign --ks keystore/nobitrader.p12 \
    --ks-pass pass:nobitrader-bot --ks-key-alias nobitrader \
    --out "$OUT/$OUT_NAME" "$BUILD/app-unsigned.apk"
  "$APKSIGNER" verify --print-certs "$OUT/$OUT_NAME" || true
else
  SIGN_LIB="${SIGN_LIB:-/tmp/npmbin/signroot}" \
    node scripts/sign-apk.mjs "$BUILD/app-unsigned.apk" "$OUT/$OUT_NAME" \
    keystore/key.pem keystore/cert.pem
fi

echo "== verify =="
"$AAPT2" dump badging "$OUT/$OUT_NAME" | head -n 6
ls -la "$OUT/$OUT_NAME"
echo "DONE: $OUT/$OUT_NAME"
