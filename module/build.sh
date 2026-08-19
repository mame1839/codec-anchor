#!/usr/bin/env bash
set -eu
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "先に .\\gradlew.bat :app:assembleDebug を実行する" >&2; exit 1; }

. module/version.sh
CA_VER=$(ca_version_name)
CA_VER_CODE=$(ca_version_code "$CA_VER")
[ "$CA_VER" != "0.0.0" ] || echo "警告: タグが取れないので版が 0.0.0 になる" >&2
echo "版: $CA_VER ($CA_VER_CODE)"

OUT=module/build/staging
rm -rf module/build; mkdir -p "$OUT/common"

cp module/post-fs-data.sh module/late-load.sh module/service.sh module/uninstall.sh \
   module/eq_devices.sh module/sepolicy.rule "$OUT/"
cp module/common/log.sh module/common/patch_xml.sh module/common/setup.sh "$OUT/common/"

sed -e "s/^version=.*/version=$CA_VER/" -e "s/^versionCode=.*/versionCode=$CA_VER_CODE/" \
    module/module.prop > "$OUT/module.prop"
grep -qx "version=$CA_VER" "$OUT/module.prop" \
  || { echo "module.prop の version を置き換えられなかった" >&2; exit 1; }
grep -qx "versionCode=$CA_VER_CODE" "$OUT/module.prop" \
  || { echo "module.prop の versionCode を置き換えられなかった" >&2; exit 1; }

ca_system_prop "$CA_VER" "$CA_VER_CODE" > "$OUT/system.prop"

unzip -p "$APK" lib/arm64-v8a/libcaeq.so        > "$OUT/libcaeq.so"
unzip -p "$APK" lib/arm64-v8a/libcaeqstat.so    > "$OUT/caeqstat"
unzip -p "$APK" lib/arm64-v8a/libdlopen_check.so > "$OUT/dlopen_check"
chmod 755 "$OUT/caeqstat" "$OUT/dlopen_check"

for f in eq_devices.sh module.prop system.prop libcaeq.so caeqstat dlopen_check; do
    [ -s "$OUT/$f" ] || { echo "staging に $f が無い" >&2; exit 1; }
done

for f in $(find "$OUT" -name '*.sh') "$OUT/module.prop" "$OUT/system.prop" "$OUT/sepolicy.rule"; do
    if [ "$(tr -dc '\015' < "$f" | wc -c)" -ne 0 ]; then echo "CRLF が混ざっている: $f" >&2; exit 1; fi
done

(cd "$OUT" && zip -r ../codecanchor_eq.zip . -x '.*') > /dev/null
echo "できた: module/build/codecanchor_eq.zip"
