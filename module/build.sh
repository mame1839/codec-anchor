#!/usr/bin/env bash
# APK からネイティブ生成物を取り出してモジュールの zip を作る。
#   bash module/build.sh
set -eu
cd "$(dirname "$0")/.."
APK=app/build/outputs/apk/debug/app-debug.apk
[ -f "$APK" ] || { echo "先に .\\gradlew.bat :app:assembleDebug を実行する" >&2; exit 1; }

OUT=module/build/staging
rm -rf module/build; mkdir -p "$OUT/common"

cp module/module.prop module/post-fs-data.sh module/service.sh module/uninstall.sh \
   module/sepolicy.rule "$OUT/"
cp module/common/log.sh module/common/patch_xml.sh "$OUT/common/"

# lib*.so を名乗っているが実体は実行ファイル。モジュール内では素の名前に戻す。
unzip -p "$APK" lib/arm64-v8a/libcaeq.so        > "$OUT/libcaeq.so"
unzip -p "$APK" lib/arm64-v8a/libcaeqstat.so    > "$OUT/caeqstat"
unzip -p "$APK" lib/arm64-v8a/libdlopen_check.so > "$OUT/dlopen_check"
# Windows の info-zip は FAT 属性しか記録しないので、この chmod は zip に残らない。
# 実行ビットは post-fs-data.sh が実機側で立て直すので、ここは Linux で作ったときのため。
chmod 755 "$OUT/caeqstat" "$OUT/dlopen_check"

# 改行は LF。CRLF が混ざると /system/bin/sh が読めない。
# grep で CR を探さないこと (Windows の grep は行末の CR を落としてから照合する)。
for f in $(find "$OUT" -name '*.sh') "$OUT/module.prop" "$OUT/sepolicy.rule"; do
    if [ "$(tr -dc '\015' < "$f" | wc -c)" -ne 0 ]; then echo "CRLF が混ざっている: $f" >&2; exit 1; fi
done

(cd "$OUT" && zip -r ../codecanchor_eq.zip . -x '.*') > /dev/null
echo "できた: module/build/codecanchor_eq.zip"
