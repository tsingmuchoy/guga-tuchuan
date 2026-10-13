#!/usr/bin/env bash
# 清木图传 手动打包管线（仿咕嘎音乐 build-manual/build.sh 改路径与产物名）
set -e
VER_CODE="${1:?need version code}"
VER_NAME="${2:?need version name}"
export PATH="$HOME/tools/jdk17/bin:$PATH"
BT="$HOME/tools/android-sdk/build-tools/34.0.0"
AJ="$HOME/tools/android-sdk/platforms/android-34/android.jar"
SRC="$HOME/workspace/nikon-transfer/app/src/main"
OUT="$HOME/workspace/nikon-transfer/build-manual"
KS="$OUT/debug.keystore"
mkdir -p "$OUT"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled.zip"
"$BT/aapt2" link -o "$OUT/app-unsigned.apk" -I "$AJ" --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" --min-sdk-version 26 --target-sdk-version 34 \
  --version-code "$VER_CODE" --version-name "$VER_NAME" "$OUT/compiled.zip"
find "$SRC" "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
rm -rf "$OUT/classes" "$OUT/dex"; mkdir -p "$OUT/classes" "$OUT/dex"
javac -source 8 -target 8 -g:none -encoding UTF-8 -cp "$AJ" -d "$OUT/classes" @"$OUT/sources.txt" 2> "$OUT/javac.log" || { echo "JAVAC FAILED"; cat "$OUT/javac.log"; exit 1; }
"$BT/d8" --lib "$AJ" --min-api 26 --output "$OUT/dex" $(find "$OUT/classes" -name "*.class")
cp "$OUT/app-unsigned.apk" "$OUT/app-with-dex.apk"
(cd "$OUT" && zip -9 -j app-with-dex.apk dex/classes.dex > /dev/null)
"$BT/zipalign" -p -f 4 "$OUT/app-with-dex.apk" "$OUT/app-aligned.apk"
APK="$HOME/workspace/nikon-transfer/qingmu-tuchuan-v$VER_NAME.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --out "$APK" "$OUT/app-aligned.apk"
echo "BUILD_DONE v$VER_NAME (code $VER_CODE)"
"$BT/apksigner" verify --print-certs "$APK" | grep "SHA-256"
"$BT/aapt2" dump badging "$APK" | head -1
ls -la "$APK"
