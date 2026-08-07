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
      module/eq_devices.sh
      module/common/log.sh module/common/patch_xml.sh module/common/setup.sh"

EQ=module/eq_devices.sh
APP_EQ=app/src/main/java/io/github/mame1839/codecanchor/core/EqDevices.kt
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

# ---- <postprocess> は退路で、既定はオフ --------------------------------------
# <postprocess> の <stream type="music"> に載せると stream type 単位で挿さるので、
# スピーカーと spatializer の出力にも載る (実測 io 69 / 12ch)。
# イヤホン用に測った補正曲線をスピーカーに当てるのは間違いで、ユーザから見ると
# 「スピーカーの音がおかしくなった」になる。製品の経路は <deviceEffects> のほう。

# 13c. 既定はオフ。札を置いたときだけ有効。
pp_off=$( MODDIR="$TMP/mod"; mkdir -p "$MODDIR"; . module/common/log.sh; . module/common/setup.sh; ca_want_pp )
pp_on=$(  MODDIR="$TMP/mod"; mkdir -p "$MODDIR"; : > "$MODDIR/use_postprocess"
          . module/common/log.sh; . module/common/setup.sh; ca_want_pp )
[ "$pp_off" = 0 ] && [ "$pp_on" = 1 ] \
  && ok "postprocess は既定でオフ、札で有効" || ng "postprocess は既定でオフ、札で有効 (無 $pp_off / 有 $pp_on)"

# 13d. 登録 0 台で自分を無効化しない。
#      <postprocess> が既定でオフなので、エフェクトのインスタンスは「登録済みのイヤホンが
#      接続されている」ときにしか立たない。service.sh は起動の 10 秒後に走るので、
#      初回インストールのユーザは全員「map しているプロセスが 1 つも無い」状態を通る。
#      ここで disable を置くと「入れたのに消えた」になり、しかもモジュールが自分で
#      置くので原因に辿り着けない。無効化して直るものも 1 つも無い
#      (bind mount は生きていてベンダーの soundfx も無傷)。
mapblk=$(awk '/^mapper=""$/ { f = 1 } f { print } f && /^fi$/ { n++; if (n == 2) exit }' module/service.sh)
if [ -z "$mapblk" ]; then
    ng "登録 0 台で自分を無効化しない (service.sh の map 検査の枝を読めない)"
elif printf '%s\n' "$mapblk" | grep -qE 'fail=|touch'; then
    ng "登録 0 台で自分を無効化しない (map 検査の枝が自分を無効化する)"
    printf '%s\n' "$mapblk" | grep -nE 'fail=|touch'
elif ! grep -q 'fail="\$fail' module/service.sh; then
    # bind mount の検査まで無効化を外してしまうと、この検査は空振りになる。
    ng "登録 0 台で自分を無効化しない (service.sh から fail= が全部消えている)"
else
    ok "登録 0 台で自分を無効化しない"
fi

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
ro.codecanchor.module_dir=/data/adb/modules/codecanchor_eq
EOF
[ $? -eq 0 ] && ok "system.prop の書式" || { ng "system.prop の書式"; cat "$TMP/prop.diff"; }

# 19b. module_dir の値は module.prop の id から作る。
#      ここに別の literal を置くと、id を変えた瞬間にアプリがスクリプトを呼べなくなる
#      (しかも「モジュールが入っていない」という別の症状に化ける)。
printf 'id=zz_other_id\nname=x\nversion=0.0.0\nversionCode=1\n' > "$TMP/other.prop"
if [ "$(CA_MODULE_PROP=$TMP/other.prop ca_system_prop 0.2.1 201 | sed -n 's/^ro.codecanchor.module_dir=//p')" \
     = /data/adb/modules/zz_other_id ]; then
    ok "module_dir は module.prop の id から作る"
else
    ng "module_dir は module.prop の id から作る"
fi

# 19c. id を読めなければ system.prop を作らない (空の値を配ると、アプリは
#      「モジュールが入っていない」に倒れる。黙って壊れるより落ちるほうがよい)。
printf 'name=x\n' > "$TMP/noid.prop"
if CA_MODULE_PROP=$TMP/noid.prop ca_system_prop 0.2.1 201 >/dev/null 2>&1; then
    ng "id が読めなければ落ちる"
else
    ok "id が読めなければ落ちる"
fi

# 20. キーと値が property の制約に収まる。
#     PROP_NAME_MAX (32) は API 26 で撤廃されている (bionic の system_properties.h に
#     "Deprecated: there's no limit on the length of a property name since API level 26")
#     が、余計な端末差を踏まないので 32 未満に収めておく。
#     PROP_VALUE_MAX (92) は現役。ro. だけは超えられるが、そこにも寄りかからない。
#     module_dir だけは、アプリが su に渡す前に ^/[0-9A-Za-z._/-]+$ で検査してから使う。
#     通らない値を配ると su を呼ばずに「モジュールが入っていない」に倒れるので、
#     空白や引用符が混ざらないことをここでも見る。
e=0
while IFS='=' read -r k v; do
    case "$k" in ro.codecanchor.*) ;; *) e=1; echo "    prefix が違う: $k" ;; esac
    [ "${#k}" -lt 32 ] || { e=1; echo "    キーが 32 バイト以上: $k (${#k})"; }
    [ "${#v}" -lt 92 ] || { e=1; echo "    値が 92 バイト以上: $k"; }
    case "$v" in *[[:space:]]*|"") e=1; echo "    値が空か空白を含む: $k" ;; esac
    if [ "$k" = ro.codecanchor.module_dir ]; then
        printf '%s\n' "$v" | grep -qE '^/[0-9A-Za-z._/-]+$' \
          || { e=1; echo "    module_dir がアプリの検査を通らない: $v"; }
    fi
done < "$TMP/system.prop"
[ $e -eq 0 ] && ok "キーと値が property の制約に収まる" || ng "キーと値が property の制約に収まる"

# ---- eq_devices.sh — アプリから root で呼ばれる入口 ---------------------------
# アプリは su の拒否とスクリプトの失敗を、印の行が出たかどうかだけで区別する。
# 終了コードは画面の文言と 1 対 1 で、増やすと「予期しない失敗」になる。

eq_code=$(sed 's/#.*//' "$EQ")

# 23. 印が最初の出力であること。引数の検査より後ろに落ちると、使い方を間違えたときに
#     アプリは「root を拒否された」と表示する (最も紛らわしい誤診)。
[ "$(grep -vE '^[[:space:]]*(#|$)' "$EQ" | head -1)" = 'echo CA_EQ_DEVICES_BEGIN' ] \
  && ok "印が最初の出力" || ng "印が最初の出力"

# 24. 終了コードの表と実装が一致する。
#     ⚠️ 取り出しに失敗したときは「一致した」ではなく失敗にすること。
tbl=$(sed -n 's/^#[[:space:]]*\([0-9]\{1,\}\)[[:space:]]*|.*/\1/p' "$EQ" | sort -un | tr '\n' ' ')
impl=$(printf '%s\n' "$eq_code" | grep -oE '\b(exit|fail) +[0-9]+' | awk '{print $2}' | sort -un | tr '\n' ' ')
if [ -z "$tbl" ]; then
    ng "終了コードの表と実装が一致する (先頭のコメントから表を読めない)"
elif [ -z "$impl" ]; then
    ng "終了コードの表と実装が一致する (実装から exit / fail を読めない)"
elif [ "$tbl" != "$impl" ]; then
    ng "終了コードの表と実装が一致する (表 [$tbl] / 実装 [$impl])"
else
    ok "終了コードの表と実装が一致する ($tbl)"
fi

# 25. 生成は作業領域で行い、live には cat でだけ書く。
#     ⚠️ bind mount は inode 単位。live に mv / sed -i を向けた瞬間に新しい inode が
#     できて、bind mount は古い内容を見続ける。変更が反映されないうえ原因を見失う。
e=0
printf '%s\n' "$eq_code" | grep -q 'sed -i' && { e=1; echo "    sed -i を使っている"; }
printf '%s\n' "$eq_code" | grep 'mv ' | grep -qE 'CA_SRC_XML|CA_LIVE_XML|CA_LIBDIR|/vendor|/odm' \
  && { e=1; echo "    mv が live のパスに向いている"; }
n_w=$(printf '%s\n' "$eq_code" | grep -c '>[[:space:]]*"\$CA_LIVE_XML"')
if [ "$n_w" != 1 ]; then
    e=1; echo "    \$CA_LIVE_XML へ書く行が $n_w 箇所 (1 箇所であること)"
elif ! printf '%s\n' "$eq_code" | grep '>[[:space:]]*"\$CA_LIVE_XML"' | grep -q '^[[:space:]]*cat '; then
    e=1; echo "    \$CA_LIVE_XML へ書いているのが cat ではない"
fi
printf '%s\n' "$eq_code" | grep -q '>>[[:space:]]*"\$CA_LIVE_XML"' \
  && { e=1; echo "    \$CA_LIVE_XML に追記している"; }
# ca_patch_xml は最後に mv するので、出力先を live に向けてはいけない。
printf '%s\n' "$eq_code" | grep -q 'ca_patch_xml "\$CA_PRISTINE_XML" "\$CA_NEXT_XML"' \
  || { e=1; echo "    ca_patch_xml の入出力が 原本 -> 作業領域 になっていない"; }
printf '%s\n' "$eq_code" | grep -qE '^[[:space:]]*CA_NEXT_XML="\$CA_WORK/' \
  || { e=1; echo "    CA_NEXT_XML が作業領域を指していない"; }
[ $e -eq 0 ] && ok "live には cat でだけ書く" || ng "live には cat でだけ書く"

# 26. 作り直しは patch 前の原本から。patch 済みを patch し直す形にすると消し漏れが積み上がる。
grep -q 'CA_PRISTINE_XML=' module/common/setup.sh \
  && grep -q 'CA_PRISTINE_XML=\$CA_PRISTINE_XML' module/common/setup.sh \
  && printf '%s\n' "$eq_code" | grep -q 'CA_PRISTINE_XML' \
  && ok "原本を post-fs-data が取っておき eq_devices.sh が使う" \
  || ng "原本を post-fs-data が取っておき eq_devices.sh が使う"

# 27. audioserver の作り直しは 1 箇所。kill が非同期で「pid が変わったこと」を
#     戻ってきた証拠にする、という罠を 2 箇所に書かないため (13 番と同じ形)。
#     late-load 段と eq_devices.sh の両方が要求するので、複製しやすい。
fnbody=$(sed 's/#.*//' module/common/setup.sh \
         | awk '/^ca_restart_audioserver\(\) \{/ { f = 1 } f { print } f && /^\}/ { exit }')
n_all=$(grep -c 'pidof audioserver' "$TMP/ship.code")
n_in=$(printf '%s\n' "$fnbody" | grep -c 'pidof audioserver')
n_def=$(grep -c '^ca_restart_audioserver() {' module/common/setup.sh)
if [ "$n_def" != 1 ] || [ "$n_in" -lt 1 ]; then
    ng "audioserver の作り直しは 1 箇所 (ca_restart_audioserver の定義が $n_def)"
elif [ "$n_all" != "$n_in" ]; then
    ng "audioserver の作り直しは 1 箇所 (pidof audioserver が関数の外にもある: 全 $n_all / 関数内 $n_in)"
elif [ "$(grep -c 'ca_restart_audioserver' module/common/setup.sh)" -ge 2 ] \
     && printf '%s\n' "$eq_code" | grep -q 'ca_restart_audioserver'; then
    ok "audioserver の作り直しは 1 箇所"
else
    ng "audioserver の作り直しは 1 箇所 (late-load と eq_devices.sh の両方から呼んでいない)"
fi

# 28. 一覧の置き場は 1 箇所で決め、uninstall で消す。
n_d=$(grep -c '/data/adb/codecanchor_eq_devices' "$TMP/ship.code")
if [ "$n_d" != 1 ]; then
    ng "一覧の置き場は 1 箇所 (literal が $n_d 箇所)"
elif grep -q 'rm -f "\$CA_DEVICES"' module/uninstall.sh; then
    ok "一覧の置き場は 1 箇所で、uninstall が消す"
else
    ng "一覧の置き場は 1 箇所で、uninstall が消す (uninstall.sh が消していない)"
fi

# ---- 実際に走らせる ----------------------------------------------------------
# 静的検査では、read のループや検査の順番の間違いは捕まらない。
# CA_LOG / CA_WORK / CA_DEVICES を差し替えて、ホストで通しで走らせる。
# audioserver はホストに居ないので、最後まで通ると 14 で終わる。

FAKE="$TMP/work"
mkdir -p "$FAKE/etc"
mkfake() {
    rm -rf "$FAKE"; mkdir -p "$FAKE/etc"
    cp module/test/fixtures/shape.xml "$FAKE/etc/audio_effects.xml.pristine"
    cp module/test/fixtures/shape.xml "$FAKE/etc/audio_effects.xml"
    {
        echo "CA_STAGE=post-fs-data"
        echo "CA_SRC_XML=$FAKE/etc/audio_effects.xml"
        echo "CA_LIBDIR=$FAKE/soundfx"
        echo "CA_PRISTINE_XML=$FAKE/etc/audio_effects.xml.pristine"
    } > "$FAKE/paths"
    rm -f "$TMP/devices"
}
# runeq <stdin の中身> <引数...> — 終了コードを返し、stdout+stderr を $TMP/eq.out に置く
runeq() {
    _in="$1"; shift
    printf '%s' "$_in" | CA_LOG="$TMP/eq.log" CA_WORK="$FAKE" CA_DEVICES="$TMP/devices" \
        sh "$EQ" "$@" > "$TMP/eq.out" 2>&1
}

# 29. 引数が無ければ 10。印はそれでも出る。
runeq '' ; rc=$?
[ "$rc" = 10 ] && [ "$(head -1 "$TMP/eq.out")" = CA_EQ_DEVICES_BEGIN ] \
  && ok "引数無しは 10 で、印は出る" || { ng "引数無しは 10 で、印は出る (rc=$rc)"; cat "$TMP/eq.out"; }

# 30. モジュールの状態が無ければ 11
rm -rf "$FAKE"; mkdir -p "$FAKE"
runeq '' apply ; rc=$?
[ "$rc" = 11 ] && ok "状態が無ければ 11" || { ng "状態が無ければ 11 (rc=$rc)"; cat "$TMP/eq.out"; }

# 31. 入力が不正なら 10 で、XML にも一覧にも触らない
mkfake
runeq '38:d5:18:47:31:a4
' apply ; rc=$?
if [ "$rc" != 10 ]; then
    ng "不正な入力は 10 (rc=$rc)"; cat "$TMP/eq.out"
elif [ -f "$TMP/devices" ]; then
    ng "不正な入力は 10 (一覧を書いてしまっている)"
elif ! cmp -s "$FAKE/etc/audio_effects.xml" module/test/fixtures/shape.xml; then
    ng "不正な入力は 10 (XML を書き換えてしまっている)"
else
    ok "不正な入力は 10 で、XML にも一覧にも触らない"
fi

# 32. 通しで走らせる。audioserver がホストに居ないので 14 まで行くのが正しい。
#     そこまでに XML と一覧が書けていること。
mkfake
runeq '38:D5:18:47:31:A4
AA:BB:CC:DD:EE:FF
' apply ; rc=$?
if [ "$rc" != 14 ]; then
    ng "通しで走ると 14 まで進む (rc=$rc)"; cat "$TMP/eq.out"
elif [ "$(cat "$TMP/devices")" != "$(printf '38:D5:18:47:31:A4\nAA:BB:CC:DD:EE:FF')" ]; then
    ng "通しで走ると 14 まで進む (一覧の中身が違う)"; cat "$TMP/devices"
elif [ "$(grep -c '<devicePort' "$FAKE/etc/audio_effects.xml")" != 2 ]; then
    ng "通しで走ると 14 まで進む (live の XML に devicePort が 2 つ無い)"
else
    ok "通しで走ると XML と一覧を書いてから 14"
fi

# 33. 空の入力 = 全解除。原本と同じ形に戻る (patch 済みを patch し直していない証拠)。
runeq '' apply ; rc=$?
if [ "$rc" != 14 ]; then
    ng "空の入力は全解除 (rc=$rc)"; cat "$TMP/eq.out"
elif grep -q 'deviceEffects' "$FAKE/etc/audio_effects.xml"; then
    ng "空の入力は全解除 (deviceEffects が残っている)"
    grep -n 'deviceEffects\|devicePort' "$FAKE/etc/audio_effects.xml"
elif [ "$(wc -c < "$TMP/devices")" != 0 ]; then
    ng "空の入力は全解除 (一覧が空になっていない)"
else
    ok "空の入力は全解除"
fi

# 34. 2 回続けて同じものを登録しても devicePort は増えない
mkfake
runeq '38:D5:18:47:31:A4
' apply
cp "$FAKE/etc/audio_effects.xml" "$TMP/once.xml"
runeq '38:D5:18:47:31:A4
' apply
cmp -s "$TMP/once.xml" "$FAKE/etc/audio_effects.xml" \
  && ok "2 回登録しても増えない" || { ng "2 回登録しても増えない"; diff "$TMP/once.xml" "$FAKE/etc/audio_effects.xml"; }

# 34b. 「書けたのに効かない」を捕まえる。
#      bind mount は inode 単位なので、live に書けても $CA_SRC_XML が別 inode を
#      指していれば audioserver には何も届かない。これを捕まえるのは
#      live に書いた後の cmp 1 つだけで、そこが死んでいても他のテストは全部通る。
#      実機の bind mount は要らない — paths の $CA_SRC_XML を別ファイルに向ければ、
#      「書けたのに効かない」状態はホストで作れる。
#      nsenter はホストで使えないので、これは CA_NS が空の経路も通っている。
rm -rf "$FAKE"; mkdir -p "$FAKE/etc"
cp module/test/fixtures/shape.xml "$FAKE/etc/audio_effects.xml.pristine"
cp module/test/fixtures/shape.xml "$FAKE/etc/audio_effects.xml"
cp module/test/fixtures/shape.xml "$FAKE/etc/audio_effects.xml.notbound"
{
    echo "CA_STAGE=post-fs-data"
    echo "CA_SRC_XML=$FAKE/etc/audio_effects.xml.notbound"
    echo "CA_LIBDIR=$FAKE/soundfx"
    echo "CA_PRISTINE_XML=$FAKE/etc/audio_effects.xml.pristine"
} > "$FAKE/paths"
rm -f "$TMP/devices"
runeq '38:D5:18:47:31:A4
' apply ; rc=$?
if [ "$rc" != 13 ]; then
    ng "live と \$CA_SRC_XML が食い違ったら 13 (rc=$rc)"; cat "$TMP/eq.out"
elif [ -f "$TMP/devices" ]; then
    # 反映が確かめられていないのに一覧を残すと、次回起動で「登録されている」ことになる。
    ng "live と \$CA_SRC_XML が食い違ったら 13 (一覧を残している)"
elif ! grep -q '使わない' "$TMP/eq.log"; then
    ng "live と \$CA_SRC_XML が食い違ったら 13 (CA_NS が空の経路を通っていない)"
else
    ok "live と \$CA_SRC_XML が食い違ったら 13"
fi

# 35. list は状態が無くても 0 で終わる (診断用なので落ちないこと)
rm -rf "$FAKE"; mkdir -p "$FAKE"
runeq '' list ; rc=$?
[ "$rc" = 0 ] && [ "$(head -1 "$TMP/eq.out")" = CA_EQ_DEVICES_BEGIN ] \
  && ok "list は状態が無くても 0" || { ng "list は状態が無くても 0 (rc=$rc)"; cat "$TMP/eq.out"; }

# ---- アプリ側との突き合わせ --------------------------------------------------
# ⚠️ このテストは別のファイル (app/.../EqDevices.kt) を読む。
#    モジュール側だけのブランチでは EqDevices.kt がまだ無いので、必ず落ちる。
#    **落ちているからといって消さないこと。**両方が載れば通る。
#    同じ値が 2 箇所にある状態は、統合のときに黙って壊れる — このプロジェクトで
#    実際に踏んでいて、共有メモリの大きさが 1152 と 5760 に分かれたまま
#    git が衝突として報告せず、消えかけた。突き合わせだけがそれを捕まえる。

# 36. アプリ側と終了コード・綴りが一致する
if [ ! -f "$APP_EQ" ]; then
    ng "アプリ側と終了コード・綴りが一致する ($APP_EQ が無い。アプリ側の合流待ち)"
else
    e=0
    app_codes=$(grep -oE 'EXIT_[A-Z_]+ = [0-9]+' "$APP_EQ" | awk '{print $3}' | sort -un | tr '\n' ' ')
    [ -n "$app_codes" ] || { e=1; echo "    $APP_EQ から EXIT_… = 数値 を読めない"; }
    [ "$app_codes" = "$tbl" ] || { e=1; echo "    終了コード: モジュール [$tbl] / アプリ [$app_codes]"; }
    appstr() { grep -oE "$1 = \"[^\"]*\"" "$APP_EQ" | sed 's/.*= "//;s/"$//'; }
    [ "$(appstr PROPERTY_DIR)" = ro.codecanchor.module_dir ] \
      || { e=1; echo "    PROPERTY_DIR: [$(appstr PROPERTY_DIR)]"; }
    [ "$(appstr SCRIPT_NAME)" = eq_devices.sh ] \
      || { e=1; echo "    SCRIPT_NAME: [$(appstr SCRIPT_NAME)]"; }
    [ "$(appstr ARG_APPLY)" = apply ] \
      || { e=1; echo "    ARG_APPLY: [$(appstr ARG_APPLY)]"; }
    [ "$(appstr BEGIN_MARKER)" = CA_EQ_DEVICES_BEGIN ] \
      || { e=1; echo "    BEGIN_MARKER: [$(appstr BEGIN_MARKER)]"; }
    # 綴りがモジュール側の実物と合っていること (アプリ側の literal だけ直っても意味が無い)
    [ -f "module/$(appstr SCRIPT_NAME)" ] \
      || { e=1; echo "    SCRIPT_NAME がモジュールに無い: $(appstr SCRIPT_NAME)"; }
    grep -q "^ro.codecanchor.module_dir=" "$TMP/system.prop" \
      || { e=1; echo "    system.prop に module_dir が無い"; }
    [ $e -eq 0 ] && ok "アプリ側と終了コード・綴りが一致する" \
                 || ng "アプリ側と終了コード・綴りが一致する"
fi

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
