#!/system/bin/sh
# イヤホンを audio_effects.xml の <deviceEffects> に登録する。
#
# アプリからの呼ばれ方:
#   su -c "sh '/data/adb/modules/codecanchor_eq/eq_devices.sh' apply"
#   登録する MAC を 1 行 1 つで stdin に流す。空の入力 = 全解除 (正当な入力)。
#
# 最初の 1 行に CA_EQ_DEVICES_BEGIN を出す。これが「スクリプトが実際に走った」印で、
# アプリはこれが無いことをもって「root を拒否された」と判定する。su の拒否と
# スクリプトの失敗を区別する唯一の手段なので、引数の検査より前に出すこと。
#
# stdout は契約ではない (アプリは診断表示のために全文を取るだけ)。見るのは終了コード。
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
# この 6 つ以外を返さないこと。未知の値はアプリ側で「予期しない失敗」になる。
# module/test/module_test.sh がこの表と実装の一致を見張っている。
echo CA_EQ_DEVICES_BEGIN

case "$0" in */*) MODDIR=${0%/*} ;; *) MODDIR=. ;; esac

# 中身が欠けた状態を 11 に寄せる。素の . の失敗は sh 依存の終了コードになるので、
# 上の表から外れる (アプリでは「予期しない失敗」になってしまう)。
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

# ---- list — 診断用に現在の一覧を出す ----------------------------------------
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

# ---- apply ------------------------------------------------------------------

# 1. モジュールの状態を受け取る。post-fs-data / late-load が最後まで走っていれば居る。
[ -f "$CA_WORK/paths" ] || fail 11 "$CA_WORK/paths が無い (post-fs-data / late-load が最後まで走っていない)"
. "$CA_WORK/paths"
[ -n "${CA_SRC_XML:-}" ] || fail 11 "$CA_WORK/paths に CA_SRC_XML が無い"
[ -n "${CA_PRISTINE_XML:-}" ] || fail 11 "$CA_WORK/paths に CA_PRISTINE_XML が無い (古いモジュール)"
[ -f "$CA_PRISTINE_XML" ] || fail 11 "patch 前の原本が無い: $CA_PRISTINE_XML"
ca_pick_ns

# 2. stdin を検査して保存する。
#    先に丸ごと受けるのは、CR の検査がバイト単位だから (行に割ってからでは見えない)。
CA_RAW="$CA_WORK/devices.in"
CA_NEW_DEVICES="$CA_WORK/devices.next"
cat > "$CA_RAW" || fail 11 "$CA_RAW を書けない"
ca_canon_devices "$CA_RAW" "$CA_NEW_DEVICES" 2>&1 || fail 10 "入力が不正"
echo "登録する MAC: $(awk 'END { print NR }' "$CA_NEW_DEVICES") 件"

# 3. patch 前の原本から XML を作り直す。patch 済みを patch し直す形にしない。
CA_NEXT_XML="$CA_WORK/etc/audio_effects.xml.next"
ca_patch_xml "$CA_PRISTINE_XML" "$CA_NEXT_XML" "$(ca_want_pp)" "$CA_NEW_DEVICES" 2>&1 \
  || fail 12 "XML の再生成に失敗"

# 4. live に書く。
#    ⚠️ bind mount は inode 単位。$CA_LIVE_XML は $CA_SRC_XML と同じ inode なので、
#    ここを truncate して書けば audioserver が読むものが変わる。/dev は tmpfs で
#    どの名前空間からも同じ inode に届くので、bind mount がどちらの名前空間に
#    張られていても効く。
#    mv と sed -i を live に向けないこと — 新しい inode ができ、bind mount は
#    古い内容を見続ける。変更が反映されないうえ原因を見失う。
CA_LIVE_XML="$CA_WORK/etc/audio_effects.xml"
cat "$CA_NEXT_XML" > "$CA_LIVE_XML" || fail 13 "$CA_LIVE_XML に書けない"

# 5. init の名前空間から見て、audioserver が読むファイルが本当に変わったか。
#    inode が分かれていたらここで落ちる (書けたのに効かない、を検出できる唯一の点)。
$CA_NS cmp -s "$CA_NEXT_XML" "$CA_SRC_XML" \
  || fail 13 "書いた内容が $CA_SRC_XML に出ていない (bind mount の inode が分かれている)"

# 6. ここで初めて一覧を差し替える。live に載ったものだけを残すため —
#    先に書くと、XML の再生成に失敗したのに次回起動では登録されている、というずれが出る。
#    置き換えの途中を post-fs-data に読ませないよう rename で原子的に行う。
#    0600 root:root。root でしか走らないので chown は念のため。
CA_TMP_DEVICES="$CA_DEVICES.tmp"
: > "$CA_TMP_DEVICES" || fail 13 "$CA_TMP_DEVICES を書けない"
chmod 600 "$CA_TMP_DEVICES"
chown 0:0 "$CA_TMP_DEVICES" 2>/dev/null
cat "$CA_NEW_DEVICES" > "$CA_TMP_DEVICES" || fail 13 "$CA_TMP_DEVICES を書けない"
mv "$CA_TMP_DEVICES" "$CA_DEVICES" || fail 13 "$CA_DEVICES を差し替えられない"

# 7. audioserver を作り直す。XML は起動時に 1 回しか読まれない。
ca_restart_audioserver || fail 14 "audioserver が戻ってこない"

echo "完了"
exit 0
