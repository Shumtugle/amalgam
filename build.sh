#!/bin/sh
# Builds and signs the APK without an IDE.
#   ANDROID_JAR  a platform jar, API 34 or newer
#   KEYSTORE, KEY_ALIAS, KS_PASS  the signing key
set -e
AJ=${ANDROID_JAR:?set ANDROID_JAR to a platform jar, API 34 or newer}
KS=${KEYSTORE:?set KEYSTORE}; KA=${KEY_ALIAS:?set KEY_ALIAS}; : ${KS_PASS:?set KS_PASS}
rm -rf out && mkdir -p out/gen out/classes out/apk
aapt package -f -M AndroidManifest.xml -S res -A assets -I "$AJ" -J out/gen -F out/base.apk
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d out/classes $(find src out/gen -name '*.java')
# whichever dexer the build tools provide
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 26 --lib "$AJ" --output out/apk $(find out/classes -name '*.class')
elif command -v dx >/dev/null 2>&1; then
    dx --dex --min-sdk-version=26 --output=out/apk/classes.dex out/classes
else
    dalvik-exchange --dex --min-sdk-version=26 --output=out/apk/classes.dex out/classes
fi
cp out/base.apk out/unsigned.apk
( cd out/apk && zip -q ../unsigned.apk classes.dex )
zipalign -f -p 4 out/unsigned.apk out/aligned.apk
apksigner sign --ks "$KS" --ks-key-alias "$KA" --ks-pass env:KS_PASS --out out/amalgam.apk out/aligned.apk
apksigner verify out/amalgam.apk
echo "out/amalgam.apk"
