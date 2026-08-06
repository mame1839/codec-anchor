#!/usr/bin/env bash
# ホストで走る。実機も Android SDK も要らない。
#   bash module/test/module_test.sh
#
# ここで見るのは「他人の端末でだけ落ちる」種類の退行。実機は 1 台しかなく、しかも
# KernelSU の built-in モード / MediaTek / audio_effects.xml が /vendor という
# 1 通りの組み合わせしか踏めないので、そこから外れた経路は静的にしか守れない。
set -u
cd "$(dirname "$0")/../.."
. module/version.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
ok() { echo "  PASS  $1"; }
ng() { echo "  FAIL  $1"; fail=1; }

# 実機に配るシェル。build.sh が staging に入れるものと一致していること (下でも検査する)。
SHIP="module/post-fs-data.sh module/late-load.sh module/service.sh module/uninstall.sh
      module/common/log.sh module/common/patch_xml.sh module/common/setup.sh"
# コメントを落とした本文。逐語引用したドキュメントの文言を、コードとして誤検出しないため。
# shellcheck disable=SC2086
sed 's/#.*//' $SHIP > "$TMP/ship.code"
sed 's/#.*//' module/common/setup.sh > "$TMP/setup.code"

# ---- 構文 --------------------------------------------------------------------

# 1. 実機に配る sh。POSIX 相当のシェルで構文検査する (実機は busybox ash)。
syn=0
for f in $SHIP; do sh -n "$f" 2>>"$TMP/syn.log" || syn=1; done
[ $syn -eq 0 ] && ok "配るシェルの構文" || { ng "配るシェルの構文"; cat "$TMP/syn.log"; }

# 2. ホスト用の bash。
syn=0
for f in module/build.sh module/version.sh module/test/*.sh; do bash -n "$f" || syn=1; done
[ $syn -eq 0 ] && ok "ホスト用スクリプトの構文" || ng "ホスト用スクリプトの構文"

# ---- 入口が 2 つあること -----------------------------------------------------
# KernelSU の late-load モードでは post-fs-data 段が丸ごと走らず、代わりに late-load 段が
# 走る (公式ドキュメント: post-fs-data.sh / post-fs-data.d/ は "Replaced by `late-load`
# stage"、late-load.sh は "runs only in late-load mode, as a replacement for
# post-fs-data.sh")。実機は built-in モードなので、ここが抜けても実機では気づけない。

# 3. late-load.sh がある
[ -f module/late-load.sh ] && ok "late-load.sh がある" || ng "late-load.sh がある"

# 4. 2 つの入口は ca_setup の引数以外が完全に同じ (片方だけ直す事故を防ぐ)
norm() { sed 's/^ca_setup .*/ca_setup @STAGE@/' "$1"; }
diff <(norm module/post-fs-data.sh) <(norm module/late-load.sh) > "$TMP/entry.diff" \
  && ok "2 つの入口は段の名前以外が同じ" \
  || { ng "2 つの入口は段の名前以外が同じ"; cat "$TMP/entry.diff"; }

# 5. どちらも本体を持たず common/setup.sh を呼ぶだけ
e=0
for f in module/post-fs-data.sh module/late-load.sh; do
    grep -q 'common/setup.sh' "$f" || e=1
    grep -q '^ca_setup ' "$f" || e=1
    [ "$(sed 's/#.*//' "$f" | grep -c '[^[:space:]]')" -le 8 ] || e=1
done
[ $e -eq 0 ] && ok "入口は setup.sh を呼ぶだけ" || ng "入口は setup.sh を呼ぶだけ"

# 6. 段の名前は 2 つとも正しい (綴りを間違えると KernelSU は黙って何もしない)
grep -qx 'ca_setup post-fs-data' module/post-fs-data.sh \
  && grep -qx 'ca_setup late-load' module/late-load.sh \
  && ok "段の名前が正しい" || ng "段の名前が正しい"

# ---- 起動を止めないこと ------------------------------------------------------
# post-fs-data は blocking な段で、予算は KernelSU が 10 秒 / Magisk が 40 秒。
# property_service を経由する書き込みは起動をデッドロックさせる。

# 7. 素の setprop を使っていない
grep -qE '(^|[^a-z])setprop' "$TMP/ship.code" \
  && ng "素の setprop を使っていない" || ok "素の setprop を使っていない"

# 8. resetprop は必ず -n 付き
grep -q 'resetprop' "$TMP/ship.code" || ng "resetprop の呼び出しが 1 つも無い (検査が空振り)"
grep 'resetprop' "$TMP/ship.code" | grep -qv 'resetprop -n' \
  && ng "resetprop は必ず -n 付き" || ok "resetprop は必ず -n 付き"

# ---- root マネージャの判定 ---------------------------------------------------
# KernelSU は互換のため MAGISK_VER / MAGISK_VER_CODE を名乗り、magiskpolicy も同梱する。
# Magisk を先に見ると KernelSU の端末で必ず誤判定する。

lineno() { grep -n "$1" "$2" | head -1 | cut -d: -f1; }
l_ksu=$(lineno '\${KSU:-}' "$TMP/setup.code")
l_ap=$(lineno '\${APATCH:-}' "$TMP/setup.code")
l_mg=$(lineno 'magiskpolicy' "$TMP/setup.code")
l_mv=$(lineno 'MAGISK_VER' "$TMP/setup.code")

# 9. 判定順が $KSU -> $APATCH -> Magisk
if [ -n "$l_ksu" ] && [ -n "$l_ap" ] && [ -n "$l_mg" ] \
   && [ "$l_ksu" -lt "$l_ap" ] && [ "$l_ap" -lt "$l_mg" ]; then
    ok "判定順が \$KSU -> \$APATCH -> Magisk"
else
    ng "判定順が \$KSU -> \$APATCH -> Magisk (ksu=$l_ksu apatch=$l_ap magisk=$l_mg)"
fi

# 10. MAGISK_VER を判定に使っていない (出てくるとしても Magisk の枝に入った後)
if [ -z "$l_mv" ] || { [ -n "$l_mg" ] && [ "$l_mv" -ge "$l_mg" ]; }; then
    ok "MAGISK_VER を判定に使っていない"
else
    ng "MAGISK_VER を判定に使っていない"
fi

# ---- 端末依存の決め打ちを持ち込まないこと ------------------------------------
# どれも「落ちる」のではなく service.sh の自己検証が失敗して自分を無効化する形で出るので、
# ユーザからは「入れたのに効かない」としか見えない。

# 11. HAL のプロセス名で引かない (この実機は MediaTek。SoC とベンダーで変わる)
grep -q 'pidof android\.hardware' module/service.sh \
  && ng "HAL をプロセス名で引かない" || ok "HAL をプロセス名で引かない"

# 12. service.sh は post-fs-data/late-load が解決したパスを受け取る
grep -q '/dev/caeq/paths' module/service.sh \
  && grep -q '"\$WORK/paths"' module/common/setup.sh \
  && ok "パスは解決した側から渡す" || ng "パスは解決した側から渡す"

# 13. init の名前空間に入る手段を 1 箇所で決める
#     (mount は nsenter 失敗時に素の mount へ落ちるのに検証だけ nsenter 決め打ち、が元の姿)
[ "$(grep -c 'nsenter' "$TMP/setup.code")" = 1 ] \
  && ok "nsenter の判断は 1 箇所" || ng "nsenter の判断は 1 箇所"

# 13b. /dev/caeq を消すより前に bind mount を外す。
#      mount が生きたまま消すと bind mount 越しにベンダーの soundfx が空になる
#      (= 端末のエフェクトが全滅する)。起動後に走る経路でしか踏めないので実機では出ない。
e=0
for f in module/common/setup.sh module/uninstall.sh; do
    sed 's/#.*//' "$f" > "$TMP/rm.code"
    l_rm=$(grep -n 'rm -rf' "$TMP/rm.code" | head -1 | cut -d: -f1)
    l_um=$(grep -n 'ca_unmount_ours[^(]' "$TMP/rm.code" | head -1 | cut -d: -f1)
    if [ -z "$l_rm" ]; then e=1; echo "    $f に rm -rf が無い"
    elif [ -z "$l_um" ]; then e=1; echo "    $f が ca_unmount_ours を呼んでいない"
    elif [ "$l_um" -ge "$l_rm" ]; then e=1; echo "    $f: rm -rf が先 ($l_rm 行) で ca_unmount_ours が後 ($l_um 行)"
    fi
done
[ $e -eq 0 ] && ok "消す前に bind mount を外す" || ng "消す前に bind mount を外す"

# ---- 配布物 ------------------------------------------------------------------

# 14. build.sh が配るファイルを全部 staging に入れる
e=0
for f in $SHIP; do grep -q "$f" module/build.sh || { e=1; echo "    $f が build.sh に無い"; }; done
[ $e -eq 0 ] && ok "配るファイルが build.sh に揃っている" || ng "配るファイルが build.sh に揃っている"

# 15. 生成する system.prop も CRLF 検査に掛ける
grep -qE '^for f in .*system\.prop' module/build.sh \
  && ok "system.prop も CRLF 検査に入る" || ng "system.prop も CRLF 検査に入る"

# 16. リポジトリのシェルに CR が混ざっていない
#     (grep で CR を探さない — Windows の grep は行末の CR を落としてから照合する)
e=0
for f in $SHIP module/build.sh module/version.sh; do
    [ "$(tr -dc '\015' < "$f" | wc -c)" -eq 0 ] || { e=1; echo "    CRLF: $f"; }
done
[ $e -eq 0 ] && ok "CRLF が混ざっていない" || ng "CRLF が混ざっていない"

# ---- .so とモジュールの取り決め ----------------------------------------------
# 共有メモリの形は、ヘッダ (.so 側) とシェル (モジュール側) の両方に書いてある。
# 片方だけ直しても両方ビルドは通り、実機でしか出ない形で壊れる。
# ヘッダ側は static_assert が構造体との一致を見ているので、ここではヘッダとシェルを突き合わせる。
#
# ⚠️ 取り出しに失敗したときは「一致した」ではなく失敗にすること。
#    定義の綴りや書き方が変わったときに黙って素通しするテストは、無いのと同じ。

SHM_H=app/src/main/cpp/ca_eq_shm.h
is_num() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
# #define <名前> <数値> / #define <名前> "<文字列>" を 1 つだけ取り出す。
defnum() { sed -n "s/^[[:space:]]*#define[[:space:]]\\+$1[[:space:]]\\+\\([0-9]\\+\\).*\$/\\1/p" "$SHM_H"; }
defstr() { sed -n "s/^[[:space:]]*#define[[:space:]]\\+$1[[:space:]]\\+\"\\([^\"]\\+\\)\".*\$/\\1/p" "$SHM_H"; }
setup_code=$(sed 's/#.*//' module/common/setup.sh)

[ -f "$SHM_H" ] || ng "$SHM_H がある"

# 21. 共有メモリの大きさ。食い違うと .so がマップの外を触り、SIGBUS で vendor の
#     audio HAL ごと落ちる (= 端末が無音になる)。
h_bytes=$(defnum CA_SHM_BYTES)
s_bytes=$(printf '%s\n' "$setup_code" | sed -n 's/.*[[:space:]]bs=\([0-9]*\).*/\1/p')
n_bs=$(printf '%s\n' "$setup_code" | grep -c 'bs=')
if ! is_num "$h_bytes"; then
    ng "共有メモリの大きさが一致する ($SHM_H の CA_SHM_BYTES を 1 つの数値として読めない)"
elif [ "$n_bs" != 1 ]; then
    ng "共有メモリの大きさが一致する (setup.sh の bs= が $n_bs 箇所)"
elif ! is_num "$s_bytes"; then
    ng "共有メモリの大きさが一致する (setup.sh の bs= を数値として読めない)"
elif [ "$h_bytes" != "$s_bytes" ]; then
    ng "共有メモリの大きさが一致する (ヘッダ $h_bytes / setup.sh $s_bytes)"
else
    ok "共有メモリの大きさが一致する ($h_bytes バイト)"
fi

# 22. 共有メモリの置き場。ずれると .so は何も見つけられず、観測点だけが黙って消える。
#     uninstall.sh も同じ場所を消しているので 3 箇所を突き合わせる。
h_path=$(defstr CA_SHM_PATH)
s_path=$(printf '%s\n' "$setup_code" | sed -n 's/^[[:space:]]*SHM=\(.*[^[:space:]]\)[[:space:]]*$/\1/p')
if [ -z "$h_path" ] || [ "$(printf '%s\n' "$h_path" | wc -l)" != 1 ]; then
    ng "共有メモリの置き場が一致する ($SHM_H の CA_SHM_PATH を 1 つの文字列として読めない)"
elif [ "$s_path" != "$h_path" ]; then
    ng "共有メモリの置き場が一致する (ヘッダ $h_path / setup.sh $s_path)"
elif ! grep -qF "$h_path" module/uninstall.sh; then
    ng "共有メモリの置き場が一致する (uninstall.sh が $h_path を消していない)"
else
    ok "共有メモリの置き場が一致する ($h_path)"
fi

# ---- 版 ----------------------------------------------------------------------
# タグが唯一の出どころ。モジュールの中身は APK から取り出すので、版はアプリと必ず一致する。

# 17. versionCode の式が build.gradle.kts / release.yml と同じ
[ "$(ca_version_code 0.2.1)" = 201 ]   && ok "versionCode 0.2.1 -> 201"   || ng "versionCode 0.2.1 -> 201"
[ "$(ca_version_code 1.2.3)" = 10203 ] && ok "versionCode 1.2.3 -> 10203" || ng "versionCode 1.2.3 -> 10203"
[ "$(ca_version_code 0.0.0)" = 1 ]     && ok "versionCode の下限は 1"     || ng "versionCode の下限は 1"

# 18. versionName の決め方
[ "$(CA_VERSION_NAME=1.2.3 ca_version_name)" = 1.2.3 ] \
  && ok "CA_VERSION_NAME を優先する" || ng "CA_VERSION_NAME を優先する"
[ "$(CA_VERSION_NAME=v1.2.3 ca_version_name)" = 1.2.3 ] \
  && ok "先頭の v を落とす" || ng "先頭の v を落とす"
[ "$(CA_VERSION_NAME=0.2.1-rc1 ca_version_name)" = 0.0.0 ] \
  && ok "semver でない値は 0.0.0" || ng "semver でない値は 0.0.0"

# 19. system.prop の書式。アプリが読む契約なので、キーの綴りまで固定する。
ca_system_prop 0.2.1 201 > "$TMP/system.prop"
diff -u - "$TMP/system.prop" <<'EOF' > "$TMP/prop.diff"
ro.codecanchor.module_version=201
ro.codecanchor.module_semver=0.2.1
EOF
[ $? -eq 0 ] && ok "system.prop の書式" || { ng "system.prop の書式"; cat "$TMP/prop.diff"; }

# 20. キーと値が property の制約に収まる。
#     PROP_NAME_MAX (32) は API 26 で撤廃されている (bionic の system_properties.h に
#     "Deprecated: there's no limit on the length of a property name since API level 26")
#     が、余計な端末差を踏まないので 32 未満に収めておく。
#     PROP_VALUE_MAX (92) は現役。ro. だけは超えられるが、そこにも寄りかからない。
e=0
while IFS='=' read -r k v; do
    case "$k" in ro.codecanchor.*) ;; *) e=1; echo "    prefix が違う: $k" ;; esac
    [ "${#k}" -lt 32 ] || { e=1; echo "    キーが 32 バイト以上: $k (${#k})"; }
    [ "${#v}" -lt 92 ] || { e=1; echo "    値が 92 バイト以上: $k"; }
    case "$v" in *[[:space:]]*|"") e=1; echo "    値が空か空白を含む: $k" ;; esac
done < "$TMP/system.prop"
[ $e -eq 0 ] && ok "キーと値が property の制約に収まる" || ng "キーと値が property の制約に収まる"

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
