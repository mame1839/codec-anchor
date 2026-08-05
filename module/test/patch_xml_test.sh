#!/usr/bin/env bash
# ホストで走る。実機も Android SDK も要らない。
#   bash module/test/patch_xml_test.sh
set -u
cd "$(dirname "$0")/../.."
. module/common/patch_xml.sh

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
ok()   { echo "  PASS  $1"; }
ng()   { echo "  FAIL  $1"; fail=1; }

# 1. 正常系 — 2 行だけ増える
ca_patch_xml module/test/fixtures/shape.xml "$TMP/a.xml"
[ $? -eq 0 ] && [ "$(grep -c 'name="ca_eq"' "$TMP/a.xml")" = 2 ] \
  && [ "$(( $(wc -l < "$TMP/a.xml") - $(wc -l < module/test/fixtures/shape.xml) ))" = 2 ] \
  && ok "2 行だけ増える" || ng "2 行だけ増える"

# 2. 挿入位置 — library は </libraries> の直前、effect は </effects> の直前
grep -B1 '</libraries>' "$TMP/a.xml" | grep -q '<library name="ca_eq"' \
  && ok "library が </libraries> の直前" || ng "library が </libraries> の直前"
grep -B1 '</effects>' "$TMP/a.xml" | grep -q '<effect name="ca_eq"' \
  && ok "effect が </effects> の直前" || ng "effect が </effects> の直前"

# 3. <postprocess> に触っていない
diff <(sed -n '/<postprocess>/,/<\/postprocess>/p' module/test/fixtures/shape.xml) \
     <(sed -n '/<postprocess>/,/<\/postprocess>/p' "$TMP/a.xml") >/dev/null \
  && ok "postprocess は無変更" || ng "postprocess は無変更"

# 4. 冪等 — patch 済みをもう一度通しても同じ
ca_patch_xml "$TMP/a.xml" "$TMP/b.xml"
cmp -s "$TMP/a.xml" "$TMP/b.xml" && ok "冪等" || ng "冪等"

# 5. 元に戻せる — 自分の行を消すと元とバイト単位で一致する
sed '/name="ca_eq"/d' "$TMP/a.xml" > "$TMP/c.xml"
cmp -s "$TMP/c.xml" module/test/fixtures/shape.xml && ok "可逆" || ng "可逆"

# 6. minify された XML は黙って通さない
ca_patch_xml module/test/fixtures/minified.xml "$TMP/d.xml" 2>/dev/null
[ $? -ne 0 ] && ok "minify を拒否" || ng "minify を拒否"

# 7. CRLF は拒否する
ca_patch_xml module/test/fixtures/crlf.xml "$TMP/e.xml" 2>/dev/null
[ $? -eq 3 ] && ok "CRLF を拒否" || ng "CRLF を拒否"

# 8. アンカーが無い XML は拒否する
printf '<?xml version="1.0"?>\n<audio_effects_conf/>\n' > "$TMP/none.xml"
ca_patch_xml "$TMP/none.xml" "$TMP/f.xml" 2>/dev/null
[ $? -ne 0 ] && ok "アンカー無しを拒否" || ng "アンカー無しを拒否"

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
