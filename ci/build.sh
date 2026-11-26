#!/usr/bin/env bash
# Builds the Go engine and the Android 4.0 APK without Gradle.
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION_CODE="${GITHUB_RUN_NUMBER:-1}"
VERSION_NAME="1.0.${VERSION_CODE}"
OUT=out
rm -rf build "$OUT" app/assets
mkdir -p build/res build/gen build/classes build/dex "$OUT" app/assets

echo "::group::Engine (Go)"
go version
(
  cd engine
  go get go.mau.fi/whatsmeow@latest github.com/skip2/go-qrcode@latest modernc.org/sqlite@latest google.golang.org/protobuf@latest
  go mod tidy
  go vet ./...
  CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
    go build -trimpath -ldflags="-s -w" -o ../app/assets/engine .
  # quick host smoke test: the engine must start and answer /status
  CGO_ENABLED=0 go build -o ../build/engine-host .
)
D=$(mktemp -d)
LEGACY_TOKEN=t ./build/engine-host -data "$D" > build/smoke.log 2>&1 &
PID=$!
for i in $(seq 1 20); do
  PORT=$(grep -oP 'ENGINE_READY \K[0-9]+' build/smoke.log || true)
  [ -n "$PORT" ] && break
  sleep 0.5
done
curl -fsS -H 'X-Token: t' "http://127.0.0.1:${PORT}/status" && echo
sleep 3
curl -fsS -H 'X-Token: t' "http://127.0.0.1:${PORT}/status" && echo
kill $PID || true
cat build/smoke.log | head -40
cp engine/go.mod engine/go.sum "$OUT/" || true
ls -la app/assets/engine
echo "::endgroup::"

echo "::group::CA bundle"
curl -fsSL https://curl.se/ca/cacert.pem -o app/assets/cacert.pem
echo "::endgroup::"

echo "::group::APK"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
PLATFORM=$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar
echo "build-tools: $BT"
echo "platform:    $PLATFORM"

"$BT/aapt2" compile --dir app/res -o build/res/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
  --manifest app/AndroidManifest.xml \
  --java build/gen -A app/assets \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res/res.zip

javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
  -bootclasspath "$PLATFORM" -classpath "$PLATFORM" \
  -d build/classes $(find app/src build/gen -name '*.java')

"$BT/d8" --release --min-api 14 --lib "$PLATFORM" --output build/dex $(find build/classes -name '*.class')

cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks ci/release.jks --ks-pass pass:legacywa --key-pass pass:legacywa \
  --ks-key-alias legacywa --min-sdk-version 14 --out "$OUT/LegacyWA.apk" build/aligned.apk
"$BT/apksigner" verify --min-sdk-version 14 --verbose "$OUT/LegacyWA.apk" | head -5
ls -la "$OUT"
echo "::endgroup::"
