#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-/c/Users/mame/scoop/apps/android-clt/current}"
AJ="$SDK/platforms/android-36/android.jar"
[ -f "$AJ" ] || { echo "android.jar が無い: $AJ" >&2; exit 1; }

D8="$SDK/build-tools/36.0.0/d8.bat"
[ -f "$D8" ] || D8="$SDK/build-tools/36.0.0/d8"

rm -rf build; mkdir -p build/classes
javac -source 17 -target 17 -Xlint:-options -cp "$AJ" \
      -d build/classes $(find src -name '*.java')
"$D8" --min-api 31 --lib "$AJ" --output build $(find build/classes -name '*.class')
mv build/classes.dex build/spike.dex
echo "できた: helper/build/spike.dex"
