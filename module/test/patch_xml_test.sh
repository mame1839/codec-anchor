#!/usr/bin/env bash
set -u
cd "$(dirname "$0")/../.."
. module/common/patch_xml.sh

SHAPE=module/test/fixtures/shape.xml
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
fail=0
ok()   { echo "  PASS  $1"; }
ng()   { echo "  FAIL  $1"; fail=1; }


ca_patch_xml "$SHAPE" "$TMP/a.xml" 1
[ $? -eq 0 ] && [ "$(grep -c 'ca_eq' "$TMP/a.xml")" = 3 ] \
  && [ "$(( $(wc -l < "$TMP/a.xml") - $(wc -l < "$SHAPE") ))" = 3 ] \
  && ok "3 行だけ増える" || ng "3 行だけ増える"

grep -B1 '</libraries>' "$TMP/a.xml" | grep -q '<library name="ca_eq"' \
  && ok "library が </libraries> の直前" || ng "library が </libraries> の直前"
grep -B1 '</effects>' "$TMP/a.xml" | grep -q '<effect name="ca_eq"' \
  && ok "effect が </effects> の直前" || ng "effect が </effects> の直前"

[ "$(grep -c '<apply effect="ca_eq"/>' "$TMP/a.xml")" = 1 ] \
  && ok "apply は 1 つだけ" || ng "apply は 1 つだけ"
sed -n '/<postprocess>/,/<\/postprocess>/p' "$TMP/a.xml" \
  | grep -A1 '<stream type="music">' | grep -q '<apply effect="ca_eq"/>' \
  && ok "apply が postprocess の music の直後" || ng "apply が postprocess の music の直後"

sed -n '/<preprocess>/,/<\/preprocess>/p' "$TMP/a.xml" | grep -q 'ca_eq' \
  && ng "preprocess には入れない" || ok "preprocess には入れない"

for s in vendor_music_listener vendor_ring_listener vendor_alarm_listener; do
    grep -q "$s" "$TMP/a.xml" || { ng "ベンダーの apply が残る ($s)"; break; }
done
grep -q vendor_alarm_listener "$TMP/a.xml" && ok "ベンダーの apply が 3 つとも残る"

ca_patch_xml "$TMP/a.xml" "$TMP/b.xml" 1
cmp -s "$TMP/a.xml" "$TMP/b.xml" && ok "冪等" || ng "冪等"

ca_strip_ours "$TMP/a.xml" > "$TMP/c.xml"
cmp -s "$TMP/c.xml" "$SHAPE" && ok "可逆" || ng "可逆"


ca_patch_xml "$SHAPE" "$TMP/np.xml" 0
[ $? -eq 0 ] && [ "$(grep -c 'ca_eq' "$TMP/np.xml")" = 2 ] \
  && ok "postprocess なしなら 2 行" || ng "postprocess なしなら 2 行"
diff <(sed -n '/<postprocess>/,/<\/postprocess>/p' "$SHAPE") \
     <(sed -n '/<postprocess>/,/<\/postprocess>/p' "$TMP/np.xml") >/dev/null \
  && ok "postprocess なしなら postprocess は無変更" || ng "postprocess なしなら postprocess は無変更"

ca_patch_xml "$TMP/a.xml" "$TMP/back.xml" 0
[ "$(grep -c 'ca_eq' "$TMP/back.xml")" = 2 ] \
  && ok "有り -> 無しで apply が消える" || ng "有り -> 無しで apply が消える"


ca_patch_xml module/test/fixtures/minified.xml "$TMP/d.xml" 1 2>/dev/null
[ $? -ne 0 ] && ok "minify を拒否" || ng "minify を拒否"

ca_patch_xml module/test/fixtures/crlf.xml "$TMP/e.xml" 1 2>/dev/null
[ $? -eq 3 ] && ok "CRLF を拒否" || ng "CRLF を拒否"

printf '<?xml version="1.0"?>\n<audio_effects_conf/>\n' > "$TMP/none.xml"
ca_patch_xml "$TMP/none.xml" "$TMP/f.xml" 1 2>/dev/null
[ $? -ne 0 ] && ok "アンカー無しを拒否" || ng "アンカー無しを拒否"

sed '/<postprocess>/,/<\/postprocess>/d' "$SHAPE" > "$TMP/nopp.xml"
ca_patch_xml "$TMP/nopp.xml" "$TMP/g.xml" 1 2>/dev/null
[ $? -eq 11 ] && ok "postprocess 無しを拒否 (新設しない)" || ng "postprocess 無しを拒否 (新設しない)"

ca_patch_xml "$TMP/nopp.xml" "$TMP/h.xml" 0 2>/dev/null
[ $? -eq 0 ] && ok "postprocess 無しでも 2 行なら通る" || ng "postprocess 無しでも 2 行なら通る"


SHAPE_DE=module/test/fixtures/shape_de.xml
M1=38:D5:18:47:31:A4
M2=AA:BB:CC:DD:EE:FF
M3=00:11:22:33:44:55

printf '' > "$TMP/dev0"
ca_patch_xml "$SHAPE" "$TMP/d0.xml" 1 "$TMP/dev0"
[ $? -eq 0 ] && ! grep -q 'deviceEffects' "$TMP/d0.xml" && cmp -s "$TMP/d0.xml" "$TMP/a.xml" \
  && ok "0 件なら deviceEffects を作らない" || ng "0 件なら deviceEffects を作らない"

printf '%s\n' "$M1" > "$TMP/dev1"
ca_patch_xml "$SHAPE" "$TMP/d1.xml" 1 "$TMP/dev1" || ng "1 件の生成が通る"
sed -n '/<deviceEffects>/,/<\/deviceEffects>/p' "$TMP/d1.xml" > "$TMP/d1.sec"
diff -u - "$TMP/d1.sec" > "$TMP/d1.diff" <<EOF
    <deviceEffects>
        <devicePort type="AUDIO_DEVICE_OUT_BLUETOOTH_A2DP" address="$M1">
            <apply effect="ca_eq"/>
        </devicePort>
    </deviceEffects>
EOF
[ $? -eq 0 ] && ok "1 件の綴りが実機で通ったものと一致" \
  || { ng "1 件の綴りが実機で通ったものと一致"; cat "$TMP/d1.diff"; }

grep -A1 '</deviceEffects>' "$TMP/d1.xml" | grep -q '</audio_effects_conf>' \
  && ok "deviceEffects は </audio_effects_conf> の直前" || ng "deviceEffects は </audio_effects_conf> の直前"

printf '%s\n%s\n%s\n' "$M1" "$M2" "$M3" > "$TMP/dev3"
ca_patch_xml "$SHAPE" "$TMP/d3.xml" 1 "$TMP/dev3" || ng "3 件の生成が通る"
[ "$(grep -c '<devicePort' "$TMP/d3.xml")" = 3 ] \
  && [ "$(grep -c '<apply effect="ca_eq"/>' "$TMP/d3.xml")" = 4 ] \
  && ok "3 件で devicePort が 3 つ / apply が 4 つ" || ng "3 件で devicePort が 3 つ / apply が 4 つ"
[ "$(grep -o 'address="[^"]*"' "$TMP/d3.xml" | sed 's/address="//;s/"//')" = "$(printf '%s\n%s\n%s' "$M1" "$M2" "$M3")" ] \
  && ok "順序は入力どおり" || ng "順序は入力どおり"

ca_patch_xml "$TMP/d3.xml" "$TMP/d3b.xml" 1 "$TMP/dev3"
cmp -s "$TMP/d3.xml" "$TMP/d3b.xml" && ok "deviceEffects つきで冪等" || ng "deviceEffects つきで冪等"

ca_strip_ours "$TMP/d3.xml" > "$TMP/d3.rt"
cmp -s "$TMP/d3.rt" "$SHAPE" && ok "deviceEffects つきで可逆" || ng "deviceEffects つきで可逆"

# 21. 20 と重なるが、落ちたときにどれが残ったか分かるよう分けてある。
grep -qE '<(/?devicePort|/?deviceEffects)' "$TMP/d3.rt" \
  && { ng "strip が構造ごと消す"; grep -nE '<(/?devicePort|/?deviceEffects)' "$TMP/d3.rt"; } \
  || ok "strip が構造ごと消す"

ca_patch_xml "$SHAPE" "$TMP/d3z.xml" 1 "$TMP/dev0"
cmp -s "$TMP/d3z.xml" "$TMP/a.xml" && ok "全解除で元の形に戻る" || ng "全解除で元の形に戻る"


ca_patch_xml "$SHAPE_DE" "$TMP/v1.xml" 1 "$TMP/dev1" || ng "ベンダーの節がある場合の生成が通る"
[ "$(grep -c '<deviceEffects>' "$TMP/v1.xml")" = 1 ] \
  && ok "deviceEffects の節は 1 つのまま" || ng "deviceEffects の節は 1 つのまま"
grep -q 'vendor_speaker_fx' "$TMP/v1.xml" \
  && ok "ベンダーの devicePort が残る" || ng "ベンダーの devicePort が残る"
# 中身を丸ごと突き合わせる (末尾行だけの判定だと挿入順を区別できない)。
sed -n '/<deviceEffects>/,/<\/deviceEffects>/p' "$TMP/v1.xml" > "$TMP/v1.sec"
diff -u - "$TMP/v1.sec" > "$TMP/v1.diff" <<EOF
    <deviceEffects>
        <devicePort type="AUDIO_DEVICE_OUT_SPEAKER" address="">
            <apply effect="vendor_speaker_fx"/>
        </devicePort>
        <devicePort type="AUDIO_DEVICE_OUT_BLUETOOTH_A2DP" address="$M1">
            <apply effect="ca_eq"/>
        </devicePort>
    </deviceEffects>
EOF
[ $? -eq 0 ] && ok "ベンダーの節の末尾に足す (節の中身が完全一致)" \
  || { ng "ベンダーの節の末尾に足す (節の中身が完全一致)"; cat "$TMP/v1.diff"; }

ca_strip_ours "$TMP/v1.xml" > "$TMP/v1.rt"
cmp -s "$TMP/v1.rt" "$SHAPE_DE" && ok "ベンダーの節があっても可逆" || ng "ベンダーの節があっても可逆"

ca_patch_xml "$TMP/v1.xml" "$TMP/v1b.xml" 1 "$TMP/dev1"
cmp -s "$TMP/v1.xml" "$TMP/v1b.xml" && ok "ベンダーの節があっても冪等" || ng "ベンダーの節があっても冪等"

sed -e "s/AUDIO_DEVICE_OUT_SPEAKER/AUDIO_DEVICE_OUT_BLUETOOTH_A2DP/" \
    -e "s/address=\"\"/address=\"$M1\"/" "$SHAPE_DE" > "$TMP/vdup.xml"
ca_patch_xml "$TMP/vdup.xml" "$TMP/vdup.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 16 ] && ok "枠の重複を拒否" || ng "枠の重複を拒否"

sed -e "s/address=\"\"/address=\"$M1\"/" "$SHAPE_DE" > "$TMP/vsame.xml"
ca_patch_xml "$TMP/vsame.xml" "$TMP/vsame.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 0 ] && ok "種別が違えば同じ address でも通る" || ng "種別が違えば同じ address でも通る"


sed 's|</audio_effects_conf>|<!-- x --></audio_effects_conf>|' "$SHAPE" > "$TMP/noend.xml"
ca_patch_xml "$TMP/noend.xml" "$TMP/noend.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 13 ] && ok "</audio_effects_conf> 無しを拒否" || ng "</audio_effects_conf> 無しを拒否"

sed 's|</deviceEffects>|<!-- x --></deviceEffects>|' "$SHAPE_DE" > "$TMP/node.xml"
ca_patch_xml "$TMP/node.xml" "$TMP/node.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 14 ] && ok "</deviceEffects> が単独行で無いのを拒否" || ng "</deviceEffects> が単独行で無いのを拒否"

ca_patch_xml "$TMP/noend.xml" "$TMP/noend2.out" 1 2>/dev/null
[ $? -eq 0 ] && ok "0 件なら </audio_effects_conf> を要求しない" || ng "0 件なら </audio_effects_conf> を要求しない"


canon_rc() { ca_canon_devices "$1" "$TMP/canon.out" 2>/dev/null; echo $?; }

printf '%s\n\n%s\n' "$M1" "$M2" > "$TMP/c_ok"
[ "$(canon_rc "$TMP/c_ok")" = 0 ] && [ "$(wc -l < "$TMP/canon.out")" = 2 ] \
  && ok "空行を読み飛ばす" || ng "空行を読み飛ばす"

printf '' > "$TMP/c_empty"
[ "$(canon_rc "$TMP/c_empty")" = 0 ] && [ "$(wc -c < "$TMP/canon.out")" = 0 ] \
  && ok "空の入力は全解除として通る" || ng "空の入力は全解除として通る"

printf '%s\n%s\n%s\n' "$M2" "$M1" "$M2" > "$TMP/c_dup"
[ "$(canon_rc "$TMP/c_dup")" = 0 ] \
  && [ "$(cat "$TMP/canon.out")" = "$(printf '%s\n%s' "$M2" "$M1")" ] \
  && ok "重複は先勝ちで畳む" || ng "重複は先勝ちで畳む"

printf '%s' "$M1" > "$TMP/c_nonl"
[ "$(canon_rc "$TMP/c_nonl")" = 0 ] && [ "$(wc -l < "$TMP/canon.out")" = 1 ] \
  && ok "末尾の改行が無くても読める" || ng "末尾の改行が無くても読める"

e=0
for bad in '38:d5:18:47:31:a4' '38:D5:18:47:31' '38:D5:18:47:31:A4:B0' '38-D5-18-47-31-A4' \
           'GG:D5:18:47:31:A4' ' 38:D5:18:47:31:A4' '38:D5:18:47:31:A4 ' '38:D5:18:47:31:A' ; do
    printf '%s\n' "$bad" > "$TMP/c_bad"
    [ "$(canon_rc "$TMP/c_bad")" = 0 ] && { e=1; echo "    通ってしまった: [$bad]"; }
done
[ $e -eq 0 ] && ok "書式外の MAC を拒否" || ng "書式外の MAC を拒否"

printf '%s\r\n' "$M1" > "$TMP/c_crlf"
ca_canon_devices "$TMP/c_crlf" "$TMP/canon.out" > "$TMP/crlf.err" 2>&1
crlf_rc=$?
if [ "$crlf_rc" = 0 ]; then
    ng "一覧の CRLF を CR の検査で拒否 (通ってしまった)"
elif ! grep -q 'CR が混ざっている' "$TMP/crlf.err"; then
    ng "一覧の CRLF を CR の検査で拒否 (落ちたが CR の検査ではない)"; cat "$TMP/crlf.err"
else
    ok "一覧の CRLF を CR の検査で拒否"
fi

# 37. ⚠️ 64/65 は literal のまま (module-shell.md §11)。$CA_MAX_DEVICES に置き換えると定数と一緒に動いて落ちなくなる。
awk 'BEGIN { for (i = 0; i < 64; i++) printf "AA:BB:CC:DD:00:%02X\n", i }' > "$TMP/c_64"
awk 'BEGIN { for (i = 0; i < 65; i++) printf "AA:BB:CC:DD:00:%02X\n", i }' > "$TMP/c_65"
[ "$(canon_rc "$TMP/c_64")" = 0 ] && [ "$(canon_rc "$TMP/c_65")" != 0 ] \
  && ok "上限は 64 件" || ng "上限は 64 件"

printf 'not a mac\n' > "$TMP/c_bad2"
ca_patch_xml "$SHAPE" "$TMP/bad.out" 1 "$TMP/c_bad2" 2>/dev/null
[ $? -eq 17 ] && ok "壊れた一覧では XML を作らない" || ng "壊れた一覧では XML を作らない"

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
