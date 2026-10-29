#!/bin/sh
# Build only. Device installation/execution is always an explicit owner action.
set -eu
cd "$(dirname "$0")/.."
if [ "${1:-}" = --help ]; then
    cat tests/device/README.md
    exit 0
fi
if [ "$#" -ne 0 ]; then
    echo 'Usage: sh tools/device-test.sh [--help]' >&2
    exit 2
fi
STILL_JAVA="${STILL_JAVA_HOME:-$PWD/.toolchain/jdk}"
STILL_TOOLS="${STILL_BUILD_TOOLS:-$PWD/.toolchain/build-tools}"
STILL_PLATFORM="${STILL_ANDROID_JAR:-$PWD/.toolchain/platform/android.jar}"
export JAVA_HOME="$STILL_JAVA"
export PATH="$STILL_JAVA/bin:$PATH"
if [ ! -s build/local-signing.jks ] || [ ! -s build/signing-password ]; then
    echo 'Existing build/local-signing.jks and build/signing-password are required. No key is generated.' >&2
    exit 1
fi
STILL_PROBE="$PWD/build/device-tests"
mkdir -p "$STILL_PROBE/classes" "$STILL_PROBE/dex"
find "$STILL_PROBE/classes" "$STILL_PROBE/dex" -type f \( -name '*.class' -o -name '*.dex' \) -delete
"$STILL_TOOLS/aapt2" link -o "$STILL_PROBE/base.apk" -I "$STILL_PLATFORM" --manifest tests/device/AndroidManifest.xml
"$STILL_JAVA/bin/javac" --release 8 -classpath "$STILL_PLATFORM" -d "$STILL_PROBE/classes" tests/device/*.java
find "$STILL_PROBE/classes" -name '*.class' > "$STILL_PROBE/classes.txt"
"$STILL_TOOLS/d8" --lib "$STILL_PLATFORM" --min-api 26 --output "$STILL_PROBE/dex" @"$STILL_PROBE/classes.txt"
cp "$STILL_PROBE/base.apk" "$STILL_PROBE/unsigned.apk"
(cd "$STILL_PROBE/dex" && zip -q -u ../unsigned.apk classes.dex)
"$STILL_TOOLS/zipalign" -f 4 "$STILL_PROBE/unsigned.apk" "$STILL_PROBE/aligned.apk"
STILL_SIGNING_PASSWORD="$(cat build/signing-password)"
export STILL_SIGNING_PASSWORD
"$STILL_TOOLS/apksigner" sign --ks build/local-signing.jks --ks-key-alias still-tv --ks-pass env:STILL_SIGNING_PASSWORD --key-pass env:STILL_SIGNING_PASSWORD --out "$STILL_PROBE/still-tv-tests.apk" "$STILL_PROBE/aligned.apk"
"$STILL_TOOLS/apksigner" verify "$STILL_PROBE/still-tv-tests.apk"
unset STILL_SIGNING_PASSWORD
echo 'Built and signature-verified: build/device-tests/still-tv-tests.apk'
echo 'Build only: no device was contacted. See tests/device/README.md for owner-run commands.'
