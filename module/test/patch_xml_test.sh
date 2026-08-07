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

# ---- postprocess あり (第 3 引数 1) ------------------------------------------
# <postprocess> への登録は退路で、既定はオフ (setup.sh の ca_want_pp)。
# 退路が要る端末のためにここで動きを固定しておく。

# 1. 正常系 — 3 行だけ増える
ca_patch_xml "$SHAPE" "$TMP/a.xml" 1
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
ca_patch_xml "$TMP/a.xml" "$TMP/b.xml" 1
cmp -s "$TMP/a.xml" "$TMP/b.xml" && ok "冪等" || ng "冪等"

# 7. 元に戻せる — 自分の行を消すと元とバイト単位で一致する
ca_strip_ours "$TMP/a.xml" > "$TMP/c.xml"
cmp -s "$TMP/c.xml" "$SHAPE" && ok "可逆" || ng "可逆"

# ---- postprocess なし (第 3 引数 0。製品の既定はこちら) ----------------------

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
ca_patch_xml module/test/fixtures/minified.xml "$TMP/d.xml" 1 2>/dev/null
[ $? -ne 0 ] && ok "minify を拒否" || ng "minify を拒否"

# 11. CRLF は拒否する
ca_patch_xml module/test/fixtures/crlf.xml "$TMP/e.xml" 1 2>/dev/null
[ $? -eq 3 ] && ok "CRLF を拒否" || ng "CRLF を拒否"

# 12. アンカーが無い XML は拒否する
printf '<?xml version="1.0"?>\n<audio_effects_conf/>\n' > "$TMP/none.xml"
ca_patch_xml "$TMP/none.xml" "$TMP/f.xml" 1 2>/dev/null
[ $? -ne 0 ] && ok "アンカー無しを拒否" || ng "アンカー無しを拒否"

# 13. <postprocess> が無い XML は、要求されたら拒否する (新設しない)
sed '/<postprocess>/,/<\/postprocess>/d' "$SHAPE" > "$TMP/nopp.xml"
ca_patch_xml "$TMP/nopp.xml" "$TMP/g.xml" 1 2>/dev/null
[ $? -eq 11 ] && ok "postprocess 無しを拒否 (新設しない)" || ng "postprocess 無しを拒否 (新設しない)"

# 14. ただし postprocess を要求しなければ通る
ca_patch_xml "$TMP/nopp.xml" "$TMP/h.xml" 0 2>/dev/null
[ $? -eq 0 ] && ok "postprocess 無しでも 2 行なら通る" || ng "postprocess 無しでも 2 行なら通る"

# ---- <deviceEffects> — イヤホンの登録 ----------------------------------------
# 静的な <deviceEffects> は MAC ごとに 1 ブロック要る。ワイルドカードは無く、
# 同じ枠 (type + address) を 2 つ書くと AudioDeviceTypeAddr が map のキーなので
# 黙って 1 つに潰れる。

SHAPE_DE=module/test/fixtures/shape_de.xml
M1=38:D5:18:47:31:A4
M2=AA:BB:CC:DD:EE:FF
M3=00:11:22:33:44:55

# 15. 一覧を渡さなければ <deviceEffects> は作らない (今までと同じ結果になる)
printf '' > "$TMP/dev0"
ca_patch_xml "$SHAPE" "$TMP/d0.xml" 1 "$TMP/dev0"
[ $? -eq 0 ] && ! grep -q 'deviceEffects' "$TMP/d0.xml" && cmp -s "$TMP/d0.xml" "$TMP/a.xml" \
  && ok "0 件なら deviceEffects を作らない" || ng "0 件なら deviceEffects を作らない"

# 16. 1 件 — 実機で通った綴りと完全一致すること。ここが製品の当たり判定そのもの。
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

# 17. </audio_effects_conf> の直前に入る (ベンダーの節が無い場合)
grep -A1 '</deviceEffects>' "$TMP/d1.xml" | grep -q '</audio_effects_conf>' \
  && ok "deviceEffects は </audio_effects_conf> の直前" || ng "deviceEffects は </audio_effects_conf> の直前"

# 18. 複数件 — 全部書かれ、順序は入力どおり
printf '%s\n%s\n%s\n' "$M1" "$M2" "$M3" > "$TMP/dev3"
ca_patch_xml "$SHAPE" "$TMP/d3.xml" 1 "$TMP/dev3" || ng "3 件の生成が通る"
[ "$(grep -c '<devicePort' "$TMP/d3.xml")" = 3 ] \
  && [ "$(grep -c '<apply effect="ca_eq"/>' "$TMP/d3.xml")" = 4 ] \
  && ok "3 件で devicePort が 3 つ / apply が 4 つ" || ng "3 件で devicePort が 3 つ / apply が 4 つ"
[ "$(grep -o 'address="[^"]*"' "$TMP/d3.xml" | sed 's/address="//;s/"//')" = "$(printf '%s\n%s\n%s' "$M1" "$M2" "$M3")" ] \
  && ok "順序は入力どおり" || ng "順序は入力どおり"

# 19. 冪等 — patch 済みをもう一度通しても devicePort が増えない。
#     ca_strip_ours が行ではなく構造を消していないと、ここで残骸が積み上がる。
ca_patch_xml "$TMP/d3.xml" "$TMP/d3b.xml" 1 "$TMP/dev3"
cmp -s "$TMP/d3.xml" "$TMP/d3b.xml" && ok "deviceEffects つきで冪等" || ng "deviceEffects つきで冪等"

# 20. 可逆 — 自分のものを消すと原本とバイト単位で一致する
ca_strip_ours "$TMP/d3.xml" > "$TMP/d3.rt"
cmp -s "$TMP/d3.rt" "$SHAPE" && ok "deviceEffects つきで可逆" || ng "deviceEffects つきで可逆"

# 21. 消し残りが無い — <devicePort> / </devicePort> / <deviceEffects> の行が残らない。
#     20 と重なるが、落ちたときにどれが残ったかが分かるので分けてある。
grep -qE '<(/?devicePort|/?deviceEffects)' "$TMP/d3.rt" \
  && { ng "strip が構造ごと消す"; grep -nE '<(/?devicePort|/?deviceEffects)' "$TMP/d3.rt"; } \
  || ok "strip が構造ごと消す"

# 22. 一覧を減らすと devicePort も減る (全解除まで含めて)
ca_patch_xml "$SHAPE" "$TMP/d3z.xml" 1 "$TMP/dev0"
cmp -s "$TMP/d3z.xml" "$TMP/a.xml" && ok "全解除で元の形に戻る" || ng "全解除で元の形に戻る"

# ---- ベンダーの <deviceEffects> がある端末 -----------------------------------

# 23. ベンダーの節がある場合は </deviceEffects> の直前に足す (節を 2 つ作らない)
ca_patch_xml "$SHAPE_DE" "$TMP/v1.xml" 1 "$TMP/dev1" || ng "ベンダーの節がある場合の生成が通る"
[ "$(grep -c '<deviceEffects>' "$TMP/v1.xml")" = 1 ] \
  && ok "deviceEffects の節は 1 つのまま" || ng "deviceEffects の節は 1 つのまま"
grep -q 'vendor_speaker_fx' "$TMP/v1.xml" \
  && ok "ベンダーの devicePort が残る" || ng "ベンダーの devicePort が残る"
#      中身を丸ごと突き合わせる。「末尾の行が </devicePort> か」では、先頭に足しても
#      末尾に足しても通ってしまう (どちらの並びでも最後は </devicePort> で終わる)。
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

# 24. ベンダーの節があっても可逆。ベンダーの節ごと消してしまうとここで落ちる。
ca_strip_ours "$TMP/v1.xml" > "$TMP/v1.rt"
cmp -s "$TMP/v1.rt" "$SHAPE_DE" && ok "ベンダーの節があっても可逆" || ng "ベンダーの節があっても可逆"

# 25. ベンダーの節があっても冪等
ca_patch_xml "$TMP/v1.xml" "$TMP/v1b.xml" 1 "$TMP/dev1"
cmp -s "$TMP/v1.xml" "$TMP/v1b.xml" && ok "ベンダーの節があっても冪等" || ng "ベンダーの節があっても冪等"

# 26. ベンダーが同じ枠 (type + address) を持っている場合は拒否する。
#     黙って 1 つに潰れるので、生成した側は「入れたのに効かない」としか見えない。
sed -e "s/AUDIO_DEVICE_OUT_SPEAKER/AUDIO_DEVICE_OUT_BLUETOOTH_A2DP/" \
    -e "s/address=\"\"/address=\"$M1\"/" "$SHAPE_DE" > "$TMP/vdup.xml"
ca_patch_xml "$TMP/vdup.xml" "$TMP/vdup.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 16 ] && ok "枠の重複を拒否" || ng "枠の重複を拒否"

# 27. 種別が違えば同じ address でも通る (AudioDeviceTypeAddr のキーは type + address)
sed -e "s/address=\"\"/address=\"$M1\"/" "$SHAPE_DE" > "$TMP/vsame.xml"
ca_patch_xml "$TMP/vsame.xml" "$TMP/vsame.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 0 ] && ok "種別が違えば同じ address でも通る" || ng "種別が違えば同じ address でも通る"

# ---- deviceEffects を足せない XML ---------------------------------------------

# 28. </audio_effects_conf> が単独行で無ければ拒否する (minify を素通ししない)
sed 's|</audio_effects_conf>|<!-- x --></audio_effects_conf>|' "$SHAPE" > "$TMP/noend.xml"
ca_patch_xml "$TMP/noend.xml" "$TMP/noend.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 13 ] && ok "</audio_effects_conf> 無しを拒否" || ng "</audio_effects_conf> 無しを拒否"

# 29. <deviceEffects> はあるのに </deviceEffects> が単独行で無い場合も拒否する
sed 's|</deviceEffects>|<!-- x --></deviceEffects>|' "$SHAPE_DE" > "$TMP/node.xml"
ca_patch_xml "$TMP/node.xml" "$TMP/node.out" 1 "$TMP/dev1" 2>/dev/null
[ $? -eq 14 ] && ok "</deviceEffects> が単独行で無いのを拒否" || ng "</deviceEffects> が単独行で無いのを拒否"

# 30. 一覧が要らないときは 28/29 の形でも通る (アンカーの検査を無条件にしない)
ca_patch_xml "$TMP/noend.xml" "$TMP/noend2.out" 1 2>/dev/null
[ $? -eq 0 ] && ok "0 件なら </audio_effects_conf> を要求しない" || ng "0 件なら </audio_effects_conf> を要求しない"

# ---- MAC の検査 (ca_canon_devices) -------------------------------------------
# アプリが送ってくる値をそのまま XML に流し込む場所なので、ここが最後の関門。

canon_rc() { ca_canon_devices "$1" "$TMP/canon.out" 2>/dev/null; echo $?; }

# 31. 正しい入力は通り、空行は読み飛ばす
printf '%s\n\n%s\n' "$M1" "$M2" > "$TMP/c_ok"
[ "$(canon_rc "$TMP/c_ok")" = 0 ] && [ "$(wc -l < "$TMP/canon.out")" = 2 ] \
  && ok "空行を読み飛ばす" || ng "空行を読み飛ばす"

# 32. 空の入力は正当 (= 全解除)
printf '' > "$TMP/c_empty"
[ "$(canon_rc "$TMP/c_empty")" = 0 ] && [ "$(wc -c < "$TMP/canon.out")" = 0 ] \
  && ok "空の入力は全解除として通る" || ng "空の入力は全解除として通る"

# 33. 重複は先勝ちで畳む
printf '%s\n%s\n%s\n' "$M2" "$M1" "$M2" > "$TMP/c_dup"
[ "$(canon_rc "$TMP/c_dup")" = 0 ] \
  && [ "$(cat "$TMP/canon.out")" = "$(printf '%s\n%s' "$M2" "$M1")" ] \
  && ok "重複は先勝ちで畳む" || ng "重複は先勝ちで畳む"

# 34. 最後の行に改行が無くても読める
printf '%s' "$M1" > "$TMP/c_nonl"
[ "$(canon_rc "$TMP/c_nonl")" = 0 ] && [ "$(wc -l < "$TMP/canon.out")" = 1 ] \
  && ok "末尾の改行が無くても読める" || ng "末尾の改行が無くても読める"

# 35. 書式外は拒否する。小文字も拒否 ([0-9A-F] の範囲は LC_COLLATE 次第で
#     小文字を拾いうるので、範囲ではなく列挙で書いてある)。
e=0
for bad in '38:d5:18:47:31:a4' '38:D5:18:47:31' '38:D5:18:47:31:A4:B0' '38-D5-18-47-31-A4' \
           'GG:D5:18:47:31:A4' ' 38:D5:18:47:31:A4' '38:D5:18:47:31:A4 ' '38:D5:18:47:31:A' ; do
    printf '%s\n' "$bad" > "$TMP/c_bad"
    [ "$(canon_rc "$TMP/c_bad")" = 0 ] && { e=1; echo "    通ってしまった: [$bad]"; }
done
[ $e -eq 0 ] && ok "書式外の MAC を拒否" || ng "書式外の MAC を拒否"

# 36. CRLF は CR の検査で弾く (grep で CR を探さない。tr の 8 進で見る)。
#     ⚠️ 「非 0 で落ちること」だけを見てはいけない — CR 付きの行は MAC の書式検査でも
#     どのみち落ちるので、CR の検査を丸ごと外しても通ってしまう (実際に変異で確認した)。
#     理由まで固定する。CR を見ないと、画面には行末の見えない文字について
#     「MAC の書式が違う」とだけ出て、原因に辿り着けない。
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

# 37. 件数の上限。64 は通り、65 は落ちる
awk 'BEGIN { for (i = 0; i < 64; i++) printf "AA:BB:CC:DD:00:%02X\n", i }' > "$TMP/c_64"
awk 'BEGIN { for (i = 0; i < 65; i++) printf "AA:BB:CC:DD:00:%02X\n", i }' > "$TMP/c_65"
[ "$(canon_rc "$TMP/c_64")" = 0 ] && [ "$(canon_rc "$TMP/c_65")" != 0 ] \
  && ok "上限は 64 件" || ng "上限は 64 件"

# 38. 壊れた一覧を渡されたら XML を作らずに落ちる (ca_patch_xml 側の関門)
printf 'not a mac\n' > "$TMP/c_bad2"
ca_patch_xml "$SHAPE" "$TMP/bad.out" 1 "$TMP/c_bad2" 2>/dev/null
[ $? -eq 17 ] && ok "壊れた一覧では XML を作らない" || ng "壊れた一覧では XML を作らない"

[ $fail -eq 0 ] && echo "すべて成功" || echo "失敗あり"
exit $fail
