#!/system/bin/sh
# 契約 (呼び方・印・終了コード) は eq-route.md §2。
# ⚠️ 呼び出し側が出す CA_SU_OK を消さないこと。理由は eq-route.md §2/§8。
#
#   code | 意味
#   -----+---------------------------------------------------------------------
#      0 | 成功
#     10 | 入力が不正 (MAC の書式・CRLF・件数)
#     11 | モジュールの状態が無い (post-fs-data / late-load が最後まで走っていない)
#     12 | XML の再生成に失敗 (ca_patch_xml が非 0)
#     13 | 反映に失敗 (live への書き込み、または書いた後の検証が落ちた)
#     14 | audioserver が戻ってこない
#
# module/test/module_test.sh がこの表と実装の一致を見張っている。
echo CA_EQ_DEVICES_BEGIN

case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac

for f in log.sh patch_xml.sh setup.sh; do
    if [ ! -f "$MODDIR/common/$f" ]; then
        echo "モジュールの中身が欠けている: $MODDIR/common/$f"
        exit 11
    fi
done
. "$MODDIR/common/log.sh"
. "$MODDIR/common/patch_xml.sh"
. "$MODDIR/common/setup.sh"

fail() {
    echo "$2"
    ca_log "eq_devices: [$1] $2"
    exit "$1"
}

case "${1:-}" in
    apply|list) ;;
    *) fail 10 "使い方: eq_devices.sh apply|list" ;;
esac

if [ "$1" = list ]; then
    echo "--- 登録済み ($CA_DEVICES) ---"
    [ -f "$CA_DEVICES" ] && cat "$CA_DEVICES"
    echo "--- live の XML の devicePort ---"
    if [ -f "$CA_WORK/paths" ]; then
        . "$CA_WORK/paths"
        ca_pick_ns
        $CA_NS grep '<devicePort' "$CA_SRC_XML" 2>/dev/null
    else
        echo "(モジュールの状態が無い: $CA_WORK/paths)"
    fi
    exit 0
fi

[ -f "$CA_WORK/paths" ] || fail 11 "$CA_WORK/paths が無い (post-fs-data / late-load が最後まで走っていない)"
. "$CA_WORK/paths"
[ -n "${CA_SRC_XML:-}" ] || fail 11 "$CA_WORK/paths に CA_SRC_XML が無い"
[ -n "${CA_PRISTINE_XML:-}" ] || fail 11 "$CA_WORK/paths に CA_PRISTINE_XML が無い (古いモジュール)"
[ -f "$CA_PRISTINE_XML" ] || fail 11 "patch 前の原本が無い: $CA_PRISTINE_XML"
ca_pick_ns

CA_RAW="$CA_WORK/devices.in"
CA_NEW_DEVICES="$CA_WORK/devices.next"
cat > "$CA_RAW" || fail 11 "$CA_RAW を書けない"
ca_canon_devices "$CA_RAW" "$CA_NEW_DEVICES" 2>&1 || fail 10 "入力が不正"
echo "登録する MAC: $(awk 'END { print NR }' "$CA_NEW_DEVICES") 件"

CA_NEXT_XML="$CA_WORK/etc/audio_effects.xml.next"
ca_patch_xml "$CA_PRISTINE_XML" "$CA_NEXT_XML" "$(ca_want_pp)" "$CA_NEW_DEVICES" 2>&1 \
  || fail 12 "XML の再生成に失敗"

CA_LIVE_XML="$CA_WORK/etc/audio_effects.xml"
cat "$CA_NEXT_XML" > "$CA_LIVE_XML" || fail 13 "$CA_LIVE_XML に書けない"

$CA_NS cmp -s "$CA_NEXT_XML" "$CA_SRC_XML" \
  || fail 13 "書いた内容が $CA_SRC_XML に出ていない (bind mount の inode が分かれている)"

CA_TMP_DEVICES="$CA_DEVICES.tmp"
: > "$CA_TMP_DEVICES" || fail 13 "$CA_TMP_DEVICES を書けない"
chmod 600 "$CA_TMP_DEVICES"
chown 0:0 "$CA_TMP_DEVICES" 2>/dev/null
cat "$CA_NEW_DEVICES" > "$CA_TMP_DEVICES" || fail 13 "$CA_TMP_DEVICES を書けない"
mv "$CA_TMP_DEVICES" "$CA_DEVICES" || fail 13 "$CA_DEVICES を差し替えられない"

ca_restart_audioserver || fail 14 "audioserver が戻ってこない"

echo "完了"
exit 0
