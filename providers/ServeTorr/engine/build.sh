#!/usr/bin/env bash
set -euo pipefail

TAG=1
UPSTREAM_REPO=https://github.com/YouROK/TorrServer.git
UPSTREAM_COMMIT=3aa5905206575ee854b869f9a74af9b575bcc113
GO_TOOLCHAIN=go1.27.4
MOBILE_VERSION=v0.0.0-20260602190626-68735029466e
ANDROID_API=23
BUILD_TOOLS=36.1.0
PLATFORM=android-36
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
OUT=$(mkdir -p "${1:-$HERE/build}" && cd "${1:-$HERE/build}" && pwd)
: "${ANDROID_HOME:?set ANDROID_HOME}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
ANDROID_JAR="$ANDROID_HOME/platforms/$PLATFORM/android.jar"

export GOTOOLCHAIN=$GO_TOOLCHAIN
export GOFLAGS=-mod=readonly
export CGO_ENABLED=1
export TZ=UTC
export LC_ALL=C
umask 022

sha256() {
  if command -v sha256sum > /dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi
}

WORK=/tmp/servetorr-engine
SRC="$WORK/src"
MODULE="$SRC/server/mobile/servetorr"
STAGE="$OUT/stage"
rm -rf "$WORK" "$STAGE" "$OUT/torrserver.aar" "$OUT/sha256.txt"
mkdir -p "$WORK/bin" "$STAGE"
export PATH="$WORK/bin:$PATH"

grep -q "const tag = \"$TAG\"" "$HERE/bindings/torrserver.go" || { echo "bindings/torrserver.go tag is not $TAG" >&2; exit 1; }

GOBIN="$WORK/bin" go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION" "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
gomobile init

git clone --quiet "$UPSTREAM_REPO" "$SRC"
git -C "$SRC" checkout --quiet "$UPSTREAM_COMMIT"
git -C "$SRC" apply "$HERE"/patches/upstream-*.patch
mkdir -p "$MODULE"
cp "$HERE"/bindings/torrserver.go "$HERE"/bindings/go.mod "$HERE"/bindings/go.sum "$MODULE/"

(cd "$MODULE" && gomobile bind -target=android -androidapi "$ANDROID_API" -tags servetorr -trimpath \
  -ldflags "-s -w -checklinkname=0 -buildid=" -o "$STAGE/gomobile.aar" .)

mkdir -p "$STAGE/aar" "$STAGE/java" "$STAGE/classes"
unzip -q "$STAGE/gomobile.aar" -d "$STAGE/aar"
unzip -q "$STAGE/gomobile-sources.jar" -d "$STAGE/java"
patch -s -p1 -d "$STAGE/java" < "$HERE/patches/gobind-java-lazy-load.patch"
javac -nowarn --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$STAGE/classes" \
  $(cd "$STAGE/java" && find go torrServer -name '*.java' | sort | sed "s#^#$STAGE/java/#")
chmod -R u=rwX,go=rX "$STAGE/classes"
find "$STAGE/classes" -exec touch -t 198001010000 {} +
rm -f "$STAGE/aar/classes.jar"
(cd "$STAGE/classes" && find go torrServer -name '*.class' | sort | zip -q -X -D "$STAGE/aar/classes.jar" -@)

mkdir -p "$STAGE/dex"
"$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8" --release --min-api "$ANDROID_API" \
  --lib "$ANDROID_JAR" --output "$STAGE/dex" "$STAGE/aar/classes.jar"
cp "$STAGE/dex/classes.dex" "$STAGE/aar/classes.dex"

chmod -R u=rwX,go=rX "$STAGE/aar"
find "$STAGE/aar" -exec touch -t 198001010000 {} +
(cd "$STAGE/aar" && zip -q -X -D -9 -fz- "$OUT/torrserver.aar" AndroidManifest.xml classes.jar classes.dex \
  $(for abi in "${ABIS[@]}"; do echo "jni/$abi/libgojni.so"; done) proguard.txt R.txt)

READELF=$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-readelf | head -n 1)
echo "go: $(go version)"
echo "gomobile: golang.org/x/mobile@$MOBILE_VERSION"
echo "upstream: $UPSTREAM_COMMIT"
echo "javac: $(javac -version 2>&1)"
echo "d8: $("$ANDROID_HOME/build-tools/$BUILD_TOOLS/d8" --version 2>&1 | head -n 1)"
echo "version: ServeTorr torrserver $TAG"
for abi in "${ABIS[@]}"; do
  so="$STAGE/aar/jni/$abi/libgojni.so"
  align=$("$READELF" -lW "$so" | awk '$1 == "LOAD" { print $NF }' | sort -u | tr '\n' ' ')
  echo "$abi libgojni.so $(wc -c < "$so" | tr -d ' ') bytes, LOAD align: $align"
done
echo "sha256:"
(cd "$STAGE/aar" && for abi in "${ABIS[@]}"; do sha256 "jni/$abi/libgojni.so"; done; sha256 classes.dex) > "$OUT/sha256.txt"
cat "$OUT/sha256.txt"
(cd "$STAGE/aar" && sha256 classes.jar)
(cd "$OUT" && sha256 torrserver.aar && wc -c torrserver.aar)
