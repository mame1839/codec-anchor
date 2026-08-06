#!/usr/bin/env bash
# ホストで走る。実機も Android SDK も要らない。
#   bash module/test/patch_xml_test.sh
set -u
cd "$(dirname "$0")/../.."
. module/common/patch_xml.sh

SHAPE=module/test/fixtures/shape.xml
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
ok()   { echo "  PASS  $1"; }
ng()   { echo "  FAIL  $1"; fail=1; }

# ---- postprocess あり (既定) ------------------------------------------------

# 1. 正常系 — 3 行だけ増える
ca_patch_xml "$SHAPE" "$TMP/a.xml"
[ $? -eq 0 ] && [ "$(grep -c 'ca_eq' "$TMP/a.xml")" = 3 ] \
  && [ "$(( $(wc -l < "$TMP/a.xml") - $(wc -l < "$SHAPE") ))" = 3 ] \
  && ok "3 行だけ増える" || ng "3 行だけ増える"

# 2. 挿入位置 — library は </libraries> の直前、effect は </effects> の直前
grep -B1 '</libraries>' "$TMP/a.xml" | grep -q '<library name="ca_eq"' \
  && ok "library が </libraries> の直前" || ng "library が </libraries> の直前"
grep -B1 '</effects>' "$TMP/a.xml" | grep -q '<effect name="ca_eq"' \
  && ok "effect が </effects> の直前" || ng "effect が </effects> の直前"

# 3. <apply> は postprocess の music の直後に 1 つだけ
[ "$(grep -c '<apply effect="ca_eq"/>' "$TMP/a.xml")" = 1 ] \
  && ok "apply は 1 つだけ" || ng "apply は 1 つだけ"
sed -n '/<postprocess>/,/<\/postprocess>/p' "$TMP/a.xml" \
  | grep -A1 '<stream type="music">' | grep -q '<apply effect="ca_eq"/>' \
  && ok "apply が postprocess の music の直後" || ng "apply が postprocess の music の直後"

# 4. <preprocess> の music には入れない (囲いを見ずに挿すとここに入ってしまう)
sed -n '/<preprocess>/,/<\/preprocess>/p' "$TMP/a.xml" | grep -q 'ca_eq' \
  && ng "preprocess には入れない" || ok "preprocess には入れない"

# 5. ベンダーの <apply> を壊していない (music/ring/alarm の 3 つとも残る)
for s in vendor_music_listener vendor_ring_listener vendor_alarm_listener; do
    grep -q "$s" "$TMP/a.xml" || { ng "ベンダーの apply が残る ($s)"; break; }
done
grep -q vendor_alarm_listener "$TMP/a.xml" && ok "ベンダーの apply が 3 つとも残る"

# 6. 冪等 — patch 済みをもう一度通しても同じ
ca_patch_xml "$TMP/a.xml" "$TMP/b.xml"
cmp -s "$TMP/a.xml" "$TMP/b.xml" && ok "冪等" || ng "冪等"

# 7. 元に戻せる — 自分の行を消すと元とバイト単位で一致する
ca_strip_ours "$TMP/a.xml" > "$TMP/c.xml"
cmp -s "$TMP/c.xml" "$SHAPE" && ok "可逆" || ng "可逆"

# ---- postprocess なし (第 3 引数 0) ------------------------------------------

# 8. 2 行だけ増え、postprocess は完全に無変更
ca_patch_xml "$SHAPE" "$TMP/np.xml" 0
[ $? -eq 0 ] && [ "$(grep -c 'ca_eq' "$TMP/np.xml")" = 2 ] \
  && ok "postprocess なしなら 2 行" || ng "postprocess なしなら 2 行"
diff <(sed -n '/<postprocess>/,/<\/postprocess>/p' "$SHAPE") \
     <(sed -n '/<postprocess>/,/<\/postprocess>/p' "$TMP/np.xml") >/dev/null \
  && ok "postprocess なしなら postprocess は無変更" || ng "postprocess なしなら postprocess は無変更"

# 9. 有り <-> 無しを行き来しても元に戻せる
ca_patch_xml "$TMP/a.xml" "$TMP/back.xml" 0
[ "$(grep -c 'ca_eq' "$TMP/back.xml")" = 2 ] \
  && ok "有り -> 無しで apply が消える" || ng "有り -> 無しで apply が消える"

# ---- 拒否すべき入力 ----------------------------------------------------------

# 10. minify された XML は黙って通さない
ca_patch_xml module/test/fixtures/minified.xml "$TMP/d.xml" 2>/dev/null
[ $? -ne 0 ] && ok "minify を拒否" || ng "minify を拒否"

# 11. CRLF は拒否する
ca_patch_xml module/test/fixtures/crlf.xml "$TMP/e.xml" 2>/dev/null
[ $? -eq 3 ] && ok "CRLF を拒否" || ng "CRLF を拒否"

# 12. アンカーが無い XML は拒否する
printf '<?xml version="1.0"?>\n<audio_effects_conf/>\n' > "$TMP/none.xml"
ca_patch_xml "$TMP/none.xml" "$TMP/f.xml" 2>/dev/null
[ $? -ne 0 ] && ok "アンカー無しを拒否" || ng "アンカー無しを拒否"

# 13. <postprocess> が無い XML は、要求されたら拒否する (新設しない)
sed '/<postprocess>/,/<\/postprocess>/d' "$SHAPE" > "$TMP/nopp.xml"
ca_patch_xml "$TMP/nopp.xml" "$TMP/g.xml" 2>/dev/null
[ $? -eq 11 ] && ok "postprocess 無しを拒否 (新設しない)" || ng "postprocess 無しを拒否 (新設しない)"

# 14. ただし postprocess を要求しなければ通る
ca_patch_xml "$TMP/nopp.xml" "$TMP/h.xml" 0 2>/dev/null
[ $? -eq 0 ] && ok "postprocess 無しでも 2 行なら通る" || ng "postprocess 無しでも 2 行なら通る"

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
