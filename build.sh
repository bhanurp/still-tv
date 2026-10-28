#!/bin/sh
set -eu
cd "$(dirname "$0")"
STILL_JAVA="${STILL_JAVA_HOME:-$PWD/.toolchain/jdk}"
STILL_TOOLS="${STILL_BUILD_TOOLS:-$PWD/.toolchain/build-tools}"
STILL_PLATFORM="${STILL_ANDROID_JAR:-$PWD/.toolchain/platform/android.jar}"
export JAVA_HOME="$STILL_JAVA"
export PATH="$STILL_JAVA/bin:$PATH"
mkdir -p build/compiled build/generated build/classes build/dex
find build/classes build/generated build/dex -type f \( -name '*.class' -o -name '*.java' -o -name '*.dex' \) -delete
"$STILL_TOOLS/aapt2" compile --dir app/res -o build/resources.zip
"$STILL_TOOLS/aapt2" link -o build/base.apk -I "$STILL_PLATFORM" -A app/assets --manifest app/AndroidManifest.xml --java build/generated build/resources.zip
find app/src build/generated -name '*.java' > build/sources.txt
javac --release 8 -classpath "$STILL_PLATFORM" -d build/classes @build/sources.txt
find build/classes -name '*.class' > build/classes.txt
"$STILL_TOOLS/d8" --lib "$STILL_PLATFORM" --min-api 26 --output build/dex @build/classes.txt
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$STILL_TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f build/signing-password ]; then
    (umask 077; "$STILL_JAVA/bin/java" -version >/dev/null 2>&1; /usr/bin/python3 -c 'import secrets; print(secrets.token_hex(24))' > build/signing-password)
fi
STILL_SIGNING_PASSWORD="$(cat build/signing-password)"
export STILL_SIGNING_PASSWORD
if [ ! -f build/local-signing.jks ]; then
    keytool -genkeypair -keystore build/local-signing.jks -alias still-tv -keyalg RSA -keysize 2048 -validity 10000 -storepass:env STILL_SIGNING_PASSWORD -keypass:env STILL_SIGNING_PASSWORD -dname 'CN=Still TV local build' >/dev/null 2>&1
fi
"$STILL_TOOLS/apksigner" sign --ks build/local-signing.jks --ks-key-alias still-tv --ks-pass env:STILL_SIGNING_PASSWORD --key-pass env:STILL_SIGNING_PASSWORD --out build/still-tv.apk build/aligned.apk
"$STILL_TOOLS/apksigner" verify build/still-tv.apk
echo 'Built and signature-verified: build/still-tv.apk'
