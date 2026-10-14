#!/usr/bin/env bash
# Generic Android APK builder: aapt2 + javac + d8 + zipalign + apksigner
# Usage: build_apk.sh <project_dir> <out_apk_path>
set -e
. /scratch/work/sdk/env.sh
ulimit -v unlimited 2>/dev/null || true

P="$(cd "$1" && pwd)"
OUT="$2"
BT=/scratch/work/sdk/build-tools/34.0.0
AJ=/scratch/work/sdk/platforms/android-36/android.jar
B="$P/build"

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "[1/6] aapt2 compile resources"
"$BT/aapt2" compile --dir "$P/res" -o "$B/res.zip"

echo "[2/6] aapt2 link"
"$BT/aapt2" link -o "$B/app.unsigned.apk" \
  -I "$AJ" \
  --manifest "$P/AndroidManifest.xml" \
  -R "$B/res.zip" \
  -A "$P/assets" \
  --java "$B/gen" \
  --auto-add-overlay

echo "[3/6] javac"
# capture to a log so the exit status is checked: a compile error must never be
# swallowed by the output filter (that once shipped an APK with a missing class)
javac -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$AJ" -classpath "$AJ" \
  -d "$B/classes" \
  $(find "$B/gen" -name '*.java') $(find "$P/src" -name '*.java') > "$B/javac.log" 2>&1
JRC=$?
grep -v -e '^Note:' -e 'Picked up' -e 'bootstrap class path' "$B/javac.log" || true
if [ "$JRC" -ne 0 ]; then
  echo "!!! JAVAC FAILED (exit $JRC) - refusing to package a broken APK"
  exit 1
fi
# and confirm a class file was produced for every source file
MISSING=0
for f in $(find "$P/src" -name '*.java'); do
  cls="$B/classes/$(echo "${f#$P/src/}" | sed 's/\.java$/.class/')"
  if [ ! -f "$cls" ]; then echo "!!! missing class: ${f#$P/src/}"; MISSING=1; fi
done
if [ "$MISSING" -ne 0 ]; then
  echo "!!! some sources did not compile - refusing to package"
  exit 1
fi

echo "[4/6] d8 dex"
"$BT/d8" -JXmx1024m --release --lib "$AJ" --min-api 24 --output "$B/dex" $(find "$B/classes" -name '*.class')

echo "[5/6] add dex to apk + zipalign"
(cd "$B/dex" && zip -q "$B/app.unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$B/app.unsigned.apk" "$B/app.aligned.apk"

echo "[6/6] sign"
KS="$P/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=QuickShare Debug,O=QuickShare,C=IN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android \
  --out "$OUT" "$B/app.aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT" | head -3
echo "BUILD OK -> $OUT"
ls -la "$OUT"
