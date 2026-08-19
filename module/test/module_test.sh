#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.."
. module/version.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
ok() { echo "  PASS  $1"; }
ng() { echo "  FAIL  $1"; fail=1; }

SHIP="module/post-fs-data.sh module/late-load.sh module/service.sh module/uninstall.sh
      module/eq_devices.sh
      module/common/log.sh module/common/patch_xml.sh module/common/setup.sh"

EQ=module/eq_devices.sh
APP_EQ=app/src/main/java/io/github/mame1839/codecanchor/core/EqDevices.kt
# shellcheck disable=SC2086
sed 's/#.*//' $SHIP > "$TMP/ship.code"
sed 's/#.*//' module/common/setup.sh > "$TMP/setup.code"

syn=0
for f in $SHIP; do sh -n "$f" 2>>"$TMP/syn.log" || syn=1; done
[ $syn -eq 0 ] && ok "配るシェルの構文" || { ng "配るシェルの構文"; cat "$TMP/syn.log"; }

syn=0
for f in module/build.sh module/version.sh module/test/*.sh; do bash -n "$f" || syn=1; done
[ $syn -eq 0 ] && ok "ホスト用スクリプトの構文" || ng "ホスト用スクリプトの構文"

[ -f module/late-load.sh ] && ok "late-load.sh がある" || ng "late-load.sh がある"

norm() { sed 's/^ca_setup .*/ca_setup @STAGE@/' "$1"; }
diff <(norm module/post-fs-data.sh) <(norm module/late-load.sh) > "$TMP/entry.diff" \
  && ok "2 つの入口は段の名前以外が同じ" \
  || { ng "2 つの入口は段の名前以外が同じ"; cat "$TMP/entry.diff"; }

e=0
for f in module/post-fs-data.sh module/late-load.sh; do
    grep -q 'common/setup.sh' "$f" || e=1
    grep -q '^ca_setup ' "$f" || e=1
    [ "$(sed 's/#.*//' "$f" | grep -c '[^[:space:]]')" -le 8 ] || e=1
done
[ $e -eq 0 ] && ok "入口は setup.sh を呼ぶだけ" || ng "入口は setup.sh を呼ぶだけ"

grep -qx 'ca_setup post-fs-data' module/post-fs-data.sh \
  && grep -qx 'ca_setup late-load' module/late-load.sh \
  && ok "段の名前が正しい" || ng "段の名前が正しい"

grep -qE '(^|[^a-z])setprop' "$TMP/ship.code" \
  && ng "素の setprop を使っていない" || ok "素の setprop を使っていない"

grep -q 'resetprop' "$TMP/ship.code" || ng "resetprop の呼び出しが 1 つも無い (検査が空振り)"
grep 'resetprop' "$TMP/ship.code" | grep -qv 'resetprop -n' \
  && ng "resetprop は必ず -n 付き" || ok "resetprop は必ず -n 付き"

lineno() { grep -n "$1" "$2" | head -1 | cut -d: -f1; }
l_ksu=$(lineno '\${KSU:-}' "$TMP/setup.code")
l_ap=$(lineno '\${APATCH:-}' "$TMP/setup.code")
l_mg=$(lineno 'magiskpolicy' "$TMP/setup.code")
l_mv=$(lineno 'MAGISK_VER' "$TMP/setup.code")

if [ -n "$l_ksu" ] && [ -n "$l_ap" ] && [ -n "$l_mg" ] \
   && [ "$l_ksu" -lt "$l_ap" ] && [ "$l_ap" -lt "$l_mg" ]; then
    ok "判定順が \$KSU -> \$APATCH -> Magisk"
else
    ng "判定順が \$KSU -> \$APATCH -> Magisk (ksu=$l_ksu apatch=$l_ap magisk=$l_mg)"
fi

if [ -z "$l_mv" ] || { [ -n "$l_mg" ] && [ "$l_mv" -ge "$l_mg" ]; }; then
    ok "MAGISK_VER を判定に使っていない"
else
    ng "MAGISK_VER を判定に使っていない"
fi

grep -q 'pidof android\.hardware' module/service.sh \
  && ng "HAL をプロセス名で引かない" || ok "HAL をプロセス名で引かない"

grep -q '/dev/caeq/paths' module/service.sh \
  && grep -q '"\$WORK/paths"' module/common/setup.sh \
  && ok "パスは解決した側から渡す" || ng "パスは解決した側から渡す"

[ "$(grep -c 'nsenter' "$TMP/setup.code")" = 1 ] \
  && ok "nsenter の判断は 1 箇所" || ng "nsenter の判断は 1 箇所"

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

pp_off=$( MODDIR="$TMP/mod"; mkdir -p "$MODDIR"; . module/common/log.sh; . module/common/setup.sh; ca_want_pp )
pp_on=$(  MODDIR="$TMP/mod"; mkdir -p "$MODDIR"; : > "$MODDIR/use_postprocess"
          . module/common/log.sh; . module/common/setup.sh; ca_want_pp )
[ "$pp_off" = 0 ] && [ "$pp_on" = 1 ] \
  && ok "postprocess は既定でオフ、札で有効" || ng "postprocess は既定でオフ、札で有効 (無 $pp_off / 有 $pp_on)"

mapblk=$(awk '/^mapper=""$/ { f = 1 } f { print } f && /^fi$/ { n++; if (n == 2) exit }' module/service.sh)
if [ -z "$mapblk" ]; then
    ng "登録 0 台で自分を無効化しない (service.sh の map 検査の枝を読めない)"
elif printf '%s\n' "$mapblk" | grep -qE 'fail=|touch'; then
    ng "登録 0 台で自分を無効化しない (map 検査の枝が自分を無効化する)"
    printf '%s\n' "$mapblk" | grep -nE 'fail=|touch'
elif ! grep -q 'fail="\$fail' module/service.sh; then
    ng "登録 0 台で自分を無効化しない (service.sh から fail= が全部消えている)"
else
    ok "登録 0 台で自分を無効化しない"
fi

e=0
for f in $SHIP; do grep -q "$f" module/build.sh || { e=1; echo "    $f が build.sh に無い"; }; done
[ $e -eq 0 ] && ok "配るファイルが build.sh に揃っている" || ng "配るファイルが build.sh に揃っている"

grep -qE '^for f in .*system\.prop' module/build.sh \
  && ok "system.prop も CRLF 検査に入る" || ng "system.prop も CRLF 検査に入る"

e=0
for f in $SHIP module/build.sh module/version.sh; do
    [ "$(tr -dc '\015' < "$f" | wc -c)" -eq 0 ] || { e=1; echo "    CRLF: $f"; }
done
[ $e -eq 0 ] && ok "CRLF が混ざっていない" || ng "CRLF が混ざっていない"

SHM_H=app/src/main/cpp/ca_eq_shm.h
is_num() { case "${1:-}" in ''|*[!0-9]*) return 1 ;; *) return 0 ;; esac; }
defnum() { sed -n "s/^[[:space:]]*#define[[:space:]]\\+$1[[:space:]]\\+\\([0-9]\\+\\).*\$/\\1/p" "$SHM_H"; }
defstr() { sed -n "s/^[[:space:]]*#define[[:space:]]\\+$1[[:space:]]\\+\"\\([^\"]\\+\\)\".*\$/\\1/p" "$SHM_H"; }
setup_code=$(sed 's/#.*//' module/common/setup.sh)

[ -f "$SHM_H" ] || ng "$SHM_H がある"

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

[ "$(ca_version_code 0.2.1)" = 201 ]   && ok "versionCode 0.2.1 -> 201"   || ng "versionCode 0.2.1 -> 201"
[ "$(ca_version_code 1.2.3)" = 10203 ] && ok "versionCode 1.2.3 -> 10203" || ng "versionCode 1.2.3 -> 10203"
[ "$(ca_version_code 0.0.0)" = 1 ]     && ok "versionCode の下限は 1"     || ng "versionCode の下限は 1"

[ "$(ca_version_code 1.0.0)" = 10000 ] && ok "versionCode 1.0.0 -> 10000" || ng "versionCode 1.0.0 -> 10000"

[ "$(ca_version_name "$CA_MODULE_TAG_PREFIX" 1.2.3)" = 1.2.3 ] \
  && ok "渡された版を優先する" || ng "渡された版を優先する"
[ "$(ca_version_name "$CA_MODULE_TAG_PREFIX" m1.2.3)" = 1.2.3 ] \
  && ok "モジュールの接頭辞を落とす" || ng "モジュールの接頭辞を落とす"
[ "$(ca_version_name "$CA_APP_TAG_PREFIX" v1.2.3)" = 1.2.3 ] \
  && ok "アプリの接頭辞を落とす" || ng "アプリの接頭辞を落とす"

if out=$(ca_version_name "$CA_MODULE_TAG_PREFIX" 0.2.1-rc1 2>/dev/null); then
    ng "版が読めなければ 0.0.0 を返さず落ちる (成功で返った: $out)"
elif [ -n "$out" ]; then
    ng "版が読めなければ 0.0.0 を返さず落ちる (標準出力に $out が出た)"
else
    ok "版が読めなければ 0.0.0 を返さず落ちる"
fi

grep -q '0\.0\.0' module/build.sh \
  && ng "build.sh に 0.0.0 の逃げ道が無い" || ok "build.sh に 0.0.0 の逃げ道が無い"

GT="$TMP/tags"
mkdir -p "$GT"
(
    cd "$GT" || exit 1
    git init -q -b main
    git -c user.email=t@example.invalid -c user.name=t commit -q --allow-empty -m a
    git tag v1.2.3
    git -c user.email=t@example.invalid -c user.name=t commit -q --allow-empty -m b
    git tag m9.9.9
) > /dev/null 2>&1

gm=$(cd "$GT" && ca_version_name "$CA_MODULE_TAG_PREFIX" 2>&1)
gv=$(cd "$GT" && ca_version_name "$CA_APP_TAG_PREFIX" 2>&1)
if [ "$gm" = 9.9.9 ] && [ "$gv" = 1.2.3 ]; then
    ok "2 系統のタグが並んでも取り違えない"
else
    ng "2 系統のタグが並んでも取り違えない (m -> $gm / v -> $gv)"
fi

GRADLE=app/build.gradle.kts
gcmd=$(sed -n 's/^[[:space:]]*commandLine(\(.*\))[[:space:]]*$/\1/p' "$GRADLE")
if [ "$(printf '%s\n' "$gcmd" | grep -c .)" != 1 ]; then
    ng "Gradle も 2 系統のタグを取り違えない ($GRADLE の commandLine( を 1 つだけ読めない)"
else
    set -f
    # shellcheck disable=SC2086
    set -- $(printf '%s\n' "$gcmd" | tr -d '"' | tr ',' ' ')
    set +f
    gtag=$(cd "$GT" && "$@" 2>/dev/null)
    [ "$gtag" = v1.2.3 ] && ok "Gradle も 2 系統のタグを取り違えない" \
      || ng "Gradle も 2 系統のタグを取り違えない ($* -> $gtag、期待 v1.2.3)"
fi

n_ret=$(grep -c '^[[:space:]]*return ' "$GRADLE")
gexpr=$(sed -n 's/^[[:space:]]*return \(.*\)$/\1/p' "$GRADLE" | tr -d ' _')
if [ "$n_ret" != 1 ]; then
    ng "Gradle の versionCode の式が期待どおり ($GRADLE の return が $n_ret 箇所)"
elif [ "$gexpr" != 'parts[0]*10000+parts[1]*100+parts[2]' ]; then
    ng "Gradle の versionCode の式が期待どおり (Gradle 側が $gexpr)"
elif ! grep -q 'coerceAtLeast(1)' "$GRADLE"; then
    ng "Gradle の versionCode の式が期待どおり (Gradle 側に下限 1 が無い)"
else
    ok "Gradle の versionCode の式が期待どおり"
fi

e=0
for w in .github/workflows/release.yml .github/workflows/release-module.yml; do
    grep -q '\. module/version\.sh' "$w" || { e=1; echo "    $w が version.sh を読んでいない"; }
    grep -q '10000' "$w" && { e=1; echo "    $w が versionCode の式を自分で持っている"; }
done
grep -q 'ca_version_code' .github/workflows/release.yml \
  || { e=1; echo "    release.yml が ca_version_code を使っていない"; }
[ $e -eq 0 ] && ok "ワークフローは版の計算を version.sh に任せる" \
             || ng "ワークフローは版の計算を version.sh に任せる"

e=0
[ "$CA_MODULE_TAG_PREFIX" = m ] || { e=1; echo "    CA_MODULE_TAG_PREFIX が m でない"; }
[ "$CA_APP_TAG_PREFIX" = v ] || { e=1; echo "    CA_APP_TAG_PREFIX が v でない"; }
grep -qF "tags: ['m*']" .github/workflows/release-module.yml \
  || { e=1; echo "    release-module.yml が m* で走らない"; }
grep -qF "tags: ['v*']" .github/workflows/release.yml \
  || { e=1; echo "    release.yml が v* で走らない"; }
[ $e -eq 0 ] && ok "タグの接頭辞が version.sh とワークフローで一致する" \
             || ng "タグの接頭辞が version.sh とワークフローで一致する"

[ "$(ca_zip_name 1.2.3)" = codecanchor_eq-m1.2.3.zip ] \
  && ok "zip の名前に版が入る" || ng "zip の名前に版が入る ($(ca_zip_name 1.2.3))"

if REPO=$(ca_update_repo); then
    ok "updateJson= が version.sh の置き場と噛み合う ($REPO)"
else
    ng "updateJson= が version.sh の置き場と噛み合う"
    REPO=
fi

ORIGIN=$(git remote get-url origin 2>/dev/null || true)
ORIGIN=${ORIGIN#https://github.com/}
ORIGIN=${ORIGIN%.git}
if [ -z "$ORIGIN" ]; then
    ng "updateJson= の指す先が origin と一致する (origin が読めないので照合できない)"
elif [ "$ORIGIN" != "$REPO" ]; then
    ng "updateJson= の指す先が origin と一致する (module.prop $REPO / origin $ORIGIN)"
else
    ok "updateJson= の指す先が origin と一致する"
fi

if [ -n "$REPO" ]; then
    ca_update_json 1.2.3 10203 > "$TMP/update.json"
    diff -u - "$TMP/update.json" <<EOF > "$TMP/update.diff"
{
  "version": "1.2.3",
  "versionCode": 10203,
  "zipUrl": "https://github.com/$REPO/releases/download/m1.2.3/codecanchor_eq-m1.2.3.zip",
  "changelog": "https://raw.githubusercontent.com/$REPO/module-update/changelog.md"
}
EOF
    [ $? -eq 0 ] && ok "update.json の書式" || { ng "update.json の書式"; cat "$TMP/update.diff"; }
fi

ca_system_prop 0.2.1 201 > "$TMP/system.prop"
diff -u - "$TMP/system.prop" <<'EOF' > "$TMP/prop.diff"
ro.codecanchor.module_version=201
ro.codecanchor.module_semver=0.2.1
ro.codecanchor.module_dir=/data/adb/modules/codecanchor_eq
EOF
[ $? -eq 0 ] && ok "system.prop の書式" || { ng "system.prop の書式"; cat "$TMP/prop.diff"; }

printf 'id=zz_other_id\nname=x\nversion=0.0.0\nversionCode=1\n' > "$TMP/other.prop"
if [ "$(CA_MODULE_PROP=$TMP/other.prop ca_system_prop 0.2.1 201 | sed -n 's/^ro.codecanchor.module_dir=//p')" \
     = /data/adb/modules/zz_other_id ]; then
    ok "module_dir は module.prop の id から作る"
else
    ng "module_dir は module.prop の id から作る"
fi

printf 'name=x\n' > "$TMP/noid.prop"
if CA_MODULE_PROP=$TMP/noid.prop ca_system_prop 0.2.1 201 >/dev/null 2>&1; then
    ng "id が読めなければ落ちる"
else
    ok "id が読めなければ落ちる"
fi

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

eq_code=$(sed 's/#.*//' "$EQ")

[ "$(grep -vE '^[[:space:]]*(#|$)' "$EQ" | head -1)" = 'echo CA_EQ_DEVICES_BEGIN' ] \
  && ok "印が最初の出力" || ng "印が最初の出力"

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
printf '%s\n' "$eq_code" | grep -q 'ca_patch_xml "\$CA_PRISTINE_XML" "\$CA_NEXT_XML"' \
  || { e=1; echo "    ca_patch_xml の入出力が 原本 -> 作業領域 になっていない"; }
printf '%s\n' "$eq_code" | grep -qE '^[[:space:]]*CA_NEXT_XML="\$CA_WORK/' \
  || { e=1; echo "    CA_NEXT_XML が作業領域を指していない"; }
[ $e -eq 0 ] && ok "live には cat でだけ書く" || ng "live には cat でだけ書く"

grep -q 'CA_PRISTINE_XML=' module/common/setup.sh \
  && grep -q 'CA_PRISTINE_XML=\$CA_PRISTINE_XML' module/common/setup.sh \
  && printf '%s\n' "$eq_code" | grep -q 'CA_PRISTINE_XML' \
  && ok "原本を post-fs-data が取っておき eq_devices.sh が使う" \
  || ng "原本を post-fs-data が取っておき eq_devices.sh が使う"

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

n_pid=$(printf '%s\n' "$fnbody" | grep -c 'pidof audioserver')
if ! printf '%s\n' "$fnbody" | grep -q 'while'; then
    ng "作り直しは pid が変わるまで戻らない (待つループが無い)"
elif [ "$n_pid" -lt 2 ]; then
    ng "作り直しは pid が変わるまで戻らない (pidof audioserver が $n_pid 回。旧と新で 2 回要る)"
elif ! printf '%s\n' "$fnbody" | grep -q '!='; then
    ng "作り直しは pid が変わるまで戻らない (新旧の pid を比べていない)"
elif [ "$(printf '%s\n' "$fnbody" | awk '/while/ { exit } /return 0/ { n++ } END { print n+0 }')" != 0 ]; then
    ng "作り直しは pid が変わるまで戻らない (待つループより前に成功で返している)"
else
    ok "作り直しは pid が変わるまで戻らない"
fi

n_d=$(grep -c '/data/adb/codecanchor_eq_devices' "$TMP/ship.code")
if [ "$n_d" != 1 ]; then
    ng "一覧の置き場は 1 箇所 (literal が $n_d 箇所)"
elif grep -q 'rm -f "\$CA_DEVICES"' module/uninstall.sh; then
    ok "一覧の置き場は 1 箇所で、uninstall が消す"
else
    ng "一覧の置き場は 1 箇所で、uninstall が消す (uninstall.sh が消していない)"
fi

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
runeq() {
    _in="$1"; shift
    printf '%s' "$_in" | CA_LOG="$TMP/eq.log" CA_WORK="$FAKE" CA_DEVICES="$TMP/devices" \
        sh "$EQ" "$@" > "$TMP/eq.out" 2>&1
}

runeq '' ; rc=$?
[ "$rc" = 10 ] && [ "$(head -1 "$TMP/eq.out")" = CA_EQ_DEVICES_BEGIN ] \
  && ok "引数無しは 10 で、印は出る" || { ng "引数無しは 10 で、印は出る (rc=$rc)"; cat "$TMP/eq.out"; }

rm -rf "$FAKE"; mkdir -p "$FAKE"
runeq '' apply ; rc=$?
[ "$rc" = 11 ] && ok "状態が無ければ 11" || { ng "状態が無ければ 11 (rc=$rc)"; cat "$TMP/eq.out"; }

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

mkfake
runeq '38:D5:18:47:31:A4
' apply
cp "$FAKE/etc/audio_effects.xml" "$TMP/once.xml"
runeq '38:D5:18:47:31:A4
' apply
cmp -s "$TMP/once.xml" "$FAKE/etc/audio_effects.xml" \
  && ok "2 回登録しても増えない" || { ng "2 回登録しても増えない"; diff "$TMP/once.xml" "$FAKE/etc/audio_effects.xml"; }

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
    ng "live と \$CA_SRC_XML が食い違ったら 13 (一覧を残している)"
elif ! grep -q '使わない' "$TMP/eq.log"; then
    ng "live と \$CA_SRC_XML が食い違ったら 13 (CA_NS が空の経路を通っていない)"
else
    ok "live と \$CA_SRC_XML が食い違ったら 13"
fi

rm -rf "$FAKE"; mkdir -p "$FAKE"
runeq '' list ; rc=$?
[ "$rc" = 0 ] && [ "$(head -1 "$TMP/eq.out")" = CA_EQ_DEVICES_BEGIN ] \
  && ok "list は状態が無くても 0" || { ng "list は状態が無くても 0 (rc=$rc)"; cat "$TMP/eq.out"; }

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
    [ -f "module/$(appstr SCRIPT_NAME)" ] \
      || { e=1; echo "    SCRIPT_NAME がモジュールに無い: $(appstr SCRIPT_NAME)"; }
    grep -q "^ro.codecanchor.module_dir=" "$TMP/system.prop" \
      || { e=1; echo "    system.prop に module_dir が無い"; }
    [ $e -eq 0 ] && ok "アプリ側と終了コード・綴りが一致する" \
                 || ng "アプリ側と終了コード・綴りが一致する"
fi

CAEQSET=app/src/main/cpp/caeqset.cpp
APP_PARAMS=app/src/main/java/io/github/mame1839/codecanchor/core/EqParams.kt
if [ ! -f "$CAEQSET" ] || [ ! -f "$APP_PARAMS" ]; then
    ng "caeqset の終了コードがアプリ側と一致する ($CAEQSET か $APP_PARAMS が無い)"
else
    e=0
    set_tbl=$(sed -n 's@^//[[:space:]]*|[[:space:]]*\([0-9]\{1,\}\)[[:space:]]*|.*@\1@p' \
              "$CAEQSET" | sort -un | tr '\n' ' ')
    set_impl=$(grep -oE '\bkExit[A-Za-z]+ +=[[:space:]]*[0-9]+' "$CAEQSET" \
               | grep -oE '[0-9]+$' | sort -un | tr '\n' ' ')
    app_set=$(sed -n '/^object EqParamsExit {/,/^}/p' "$APP_PARAMS" \
              | grep -oE 'const val [A-Z_]+ = [0-9]+' | awk '{print $5}' | sort -un | tr '\n' ' ')
    [ -n "$set_tbl" ]  || { e=1; echo "    $CAEQSET の先頭コメントから表を読めない"; }
    [ -n "$set_impl" ] || { e=1; echo "    $CAEQSET の enum から kExit… を読めない"; }
    [ -n "$app_set" ]  || { e=1; echo "    $APP_PARAMS の EqParamsExit を読めない"; }
    [ "$set_tbl" = "$set_impl" ] \
      || { e=1; echo "    caeqset 内で不一致: 表 [$set_tbl] / enum [$set_impl]"; }
    [ "$set_tbl" = "$app_set" ] \
      || { e=1; echo "    終了コード: caeqset [$set_tbl] / アプリ [$app_set]"; }
    set_begin=$(grep -oE '#define CA_EQ_SET_BEGIN "[^"]*"' "$CAEQSET" | sed 's/.*"\(.*\)"/\1/')
    app_begin=$(grep -oE 'BEGIN_MARKER = "[^"]*"' "$APP_PARAMS" | sed 's/.*"\(.*\)"/\1/')
    [ -n "$set_begin" ] && [ "$set_begin" = "$app_begin" ] \
      || { e=1; echo "    BEGIN_MARKER: caeqset [$set_begin] / アプリ [$app_begin]"; }
    [ $e -eq 0 ] && ok "caeqset の終了コードがアプリ側と一致する ($set_tbl)" \
                 || ng "caeqset の終了コードがアプリ側と一致する"
fi

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
