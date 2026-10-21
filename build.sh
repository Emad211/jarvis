#!/usr/bin/env bash
# Build the Hermes Control APK with the Android SDK tools only (no Gradle).
# NOTE: aapt2/d8/apksigner/zipalign are native Windows binaries. MSYS does NOT
# translate paths for them, so every path handed to them must be C:/ style.
set -euo pipefail

SDK="C:/Users/Public/Android/sdk"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
PROJ_MSYS="$(cd "$(dirname "$0")" && pwd)"
PROJ="$(cygpath -m "$PROJ_MSYS")"          # -> C:/Users/... (native, forward slashes)
OUT="$PROJ/build"
DIST="$PROJ/dist"

rm -rf "$PROJ_MSYS/build"
mkdir -p "$PROJ_MSYS/build/res" "$PROJ_MSYS/build/classes" "$PROJ_MSYS/build/dex" "$PROJ_MSYS/dist"

echo "==> 1/6 aapt2 compile"
"$BT/aapt2.exe" compile --dir "$PROJ/res" -o "$OUT/res/resources.zip"

echo "==> 2/6 aapt2 link (manifest + resources + assets)"
"$BT/aapt2.exe" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  --min-sdk-version 24 \
  --target-sdk-version 35 \
  -A "$PROJ/assets" \
  --java "$OUT/gen" \
  "$OUT/res/resources.zip"

echo "==> 3/6 javac"
mkdir -p "$PROJ_MSYS/build/gen"
GEN_ARGS=()
while IFS= read -r -d '' f; do GEN_ARGS+=("$(cygpath -m "$f")"); done < <(find "$PROJ_MSYS/build/gen" -name '*.java' -print0 2>/dev/null)
SRC_ARGS=()
while IFS= read -r -d '' f; do SRC_ARGS+=("$(cygpath -m "$f")"); done < <(find "$PROJ_MSYS/java" -name '*.java' -print0)

"$JAVA_HOME/bin/javac.exe" -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$OUT/classes" \
  "${GEN_ARGS[@]}" \
  "${SRC_ARGS[@]}"

echo "==> 4/6 d8 (dex)"
CLASS_ARGS=()
while IFS= read -r -d '' f; do CLASS_ARGS+=("$(cygpath -m "$f")"); done < <(find "$PROJ_MSYS/build/classes" -name '*.class' -print0)
"$BT/d8.bat" --release --min-api 24 \
  --lib "$ANDROID_JAR" \
  --output "$OUT/dex" \
  "${CLASS_ARGS[@]}"

echo "==> 5/6 package dex into apk + zipalign"
cp "$PROJ_MSYS/build/base.apk" "$PROJ_MSYS/build/unaligned.apk"
python - "$PROJ/build/unaligned.apk" "$PROJ/build/dex/classes.dex" <<'PYEOF'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    if "classes.dex" in z.namelist():
        z.remove("classes.dex") if hasattr(z, "remove") else None
    z.write(dex, "classes.dex")
print("    dex packaged")
PYEOF
"$BT/zipalign.exe" -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

echo "==> 6/6 sign"
KEYSTORE="$PROJ/keystore.jks"
if [ ! -f "$PROJ_MSYS/keystore.jks" ]; then
  "$JAVA_HOME/bin/keytool.exe" -genkeypair -v \
    -keystore "$KEYSTORE" -alias hermesctl -keyalg RSA -keysize 2048 \
    -validity 10950 -storepass hermesctl -keypass hermesctl \
    -dname "CN=Hermes Control, OU=Emad, O=Emad, L=Tehran, C=IR" >/dev/null 2>&1
  echo "    created keystore"
fi
"$BT/apksigner.bat" sign \
  --ks "$KEYSTORE" --ks-key-alias hermesctl \
  --ks-pass pass:hermesctl --key-pass pass:hermesctl \
  --out "$DIST/hermes-control.apk" "$OUT/aligned.apk"

"$BT/apksigner.bat" verify --print-certs "$DIST/hermes-control.apk" | head -4

echo
echo "BUILT: $DIST/hermes-control.apk"
ls -la "$PROJ_MSYS/dist/hermes-control.apk"
