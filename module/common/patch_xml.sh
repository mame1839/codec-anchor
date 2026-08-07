# ca_patch_xml <入力ファイル> <出力ファイル> [postprocess に足すか (既定 0)] [登録するイヤホンの一覧]
#   既存の XML を読んで <libraries> と <effects> に 1 行ずつ足す。
#   第 3 引数が 1 なら <postprocess> の <stream type="music"> にも <apply> を 1 行足す。
#   既定を 0 にしてあるのは、言われなければ何もしない側に倒すため。
#   実際にどちらにするかは setup.sh の ca_want_pp が 1 箇所で決める (既定はオフ)。
#   第 4 引数にファイルを渡すと、その中の MAC ごとに <deviceEffects> の <devicePort> を足す。
#   丸ごと差し替えない。既存の <apply> (実機では Dolby の listener が music/ring/alarm に居る)
#   には触らない。冪等 (先に自分のものを消してから足す)。
#   成功で 0、アンカーが見つからない / 挿入できなかったら非 0。
#
#   ⚠️ 作業に <出力ファイル> の隣の .ins / .rt / .orig / .dev を使う。
#      その名前のファイルを出力先の隣に置かないこと (消される)。
#   ⚠️ 出力先を bind mount が生きているファイルに向けないこと。最後に mv しているので
#      新しい inode ができ、bind mount は古い内容を見続ける。生成は作業領域で行い、
#      live には cat 新 > 対象 でだけ書く。
CA_LIB_LINE='        <library name="ca_eq" path="libcaeq.so"/>'
CA_FX_LINE='        <effect name="ca_eq" library="ca_eq" uuid="7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1"/>'
CA_APPLY_LINE='            <apply effect="ca_eq"/>'

# 登録できるイヤホンの上限。XML が肥大すると audioserver の起動が遅くなるので頭を打っておく。
CA_MAX_DEVICES=64

# MAC の書式。大文字の 16 進 2 桁をコロンで 6 つ。
# [0-9A-F] と書かないのは、範囲の解釈が LC_COLLATE 依存だから (端末差を踏まない)。
ca_hex='[0123456789ABCDEF]'
CA_MAC_GLOB="$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex"
unset ca_hex

# ca_canon_devices <入力> <出力>
#   1 行 1 MAC の入力を検査して正規形にする。空の入力は正当 (= 全解除)。
#   - CR が 1 つでも混ざっていたら拒否。grep で CR を探さないこと —
#     Windows (MSYS2) の grep は行末の CR を落としてから照合するので素通しする。
#     toybox の tr は '\r' を 'r' の意味に取るので '\015' と書く。
#   - 空行は読み飛ばす / 重複は先勝ちで畳む / 上限を超えたら拒否
#   成功で 0。拒否は非 0 で、理由を stderr に出す。
ca_canon_devices() {
    ca_cd_in="$1"; ca_cd_out="$2"
    if [ ! -f "$ca_cd_in" ]; then
        echo "canon_devices: 入力が無い: $ca_cd_in" >&2
        return 1
    fi
    if [ "$(tr -dc '\015' < "$ca_cd_in" | wc -c)" -ne 0 ]; then
        echo "canon_devices: CR が混ざっている (CRLF で書かれている)" >&2
        return 1
    fi
    : > "$ca_cd_out" || return 1
    ca_cd_n=0
    # 最後の行に改行が無くても読めるようにする ([ -n "$line" ] の側)。
    while IFS= read -r ca_cd_line || [ -n "$ca_cd_line" ]; do
        [ -n "$ca_cd_line" ] || continue
        case "$ca_cd_line" in
            $CA_MAC_GLOB) ;;
            *) echo "canon_devices: MAC の書式が違う: $ca_cd_line" >&2; return 1 ;;
        esac
        grep -qxF "$ca_cd_line" "$ca_cd_out" && continue
        ca_cd_n=$((ca_cd_n+1))
        if [ "$ca_cd_n" -gt "$CA_MAX_DEVICES" ]; then
            echo "canon_devices: 登録は $CA_MAX_DEVICES 件まで" >&2
            return 1
        fi
        printf '%s\n' "$ca_cd_line" >> "$ca_cd_out"
    done < "$ca_cd_in"
    return 0
}

# 自分が書いたものだけを消す。行だけを消すと </devicePort> と <deviceEffects> が残り、
# 作り直すたびに残骸が増える。だから消す単位を構造にしてある:
#   - 中身が <apply effect="ca_eq"/> だけの <devicePort> ブロックは丸ごと消す
#     (ベンダーの <apply> が混ざっているブロックは、我々の行だけ抜いて残す)
#   - 中身が 1 行も残らなかった <deviceEffects> 節は節ごと消す。ベンダーが空の節を
#     持っていた場合も同じに消えるので、ca_patch_xml の検査 6 (可逆性) が成立する
#   - <library name="ca_eq"> / <effect name="ca_eq"> / <postprocess> の <apply> は行で消す
# XML のコメントで印を付ける方式は採らない (実機で通った綴りから離れるため)。
ca_strip_ours() {
    awk '
        function emit(l) { if (inde) sec[++sn] = l; else print l }

        /^[[:space:]]*<deviceEffects>[[:space:]]*$/ && !inde && !inblk {
            inde = 1; sn = 0; sec_open = $0; next
        }
        inde && !inblk && /^[[:space:]]*<\/deviceEffects>[[:space:]]*$/ {
            inde = 0
            if (sn > 0) {
                print sec_open
                for (i = 1; i <= sn; i++) print sec[i]
                print $0
            }
            next
        }
        !inblk && /<devicePort/ && $0 !~ /\/>[[:space:]]*$/ {
            inblk = 1; bn = 0; blk[++bn] = $0; ours = 0; other = 0; next
        }
        inblk {
            blk[++bn] = $0
            if ($0 ~ /^[[:space:]]*<\/devicePort>[[:space:]]*$/) {
                inblk = 0
                if (ours > 0 && other == 0) next
                for (i = 1; i <= bn; i++) if (blk[i] !~ /effect="ca_eq"/) emit(blk[i])
                next
            }
            if ($0 ~ /effect="ca_eq"/) ours++
            else if ($0 ~ /[^[:space:]]/) other++
            next
        }
        $0 !~ /name="ca_eq"/ && $0 !~ /effect="ca_eq"/ { emit($0) }

        END {
            if (inde) {
                print sec_open
                for (i = 1; i <= sn; i++) print sec[i]
            }
            if (inblk) for (i = 1; i <= bn; i++) print blk[i]
        }
    ' "$1"
}

ca_patch_xml() {
    src="$1"; dst="$2"; want_pp="${3:-0}"; devfile="${4:-}"

    # 1. CRLF を拒否する。adb shell cat 経由で持ってきたファイルは CRLF になっている。
    #    grep で CR を探してはいけない — Windows (MSYS2) の grep は行末の CR を落としてから
    #    照合するので、ホストでこのテストを走らせると CRLF を素通しする。
    #    tr の 8 進エスケープなら toybox / GNU / MSYS2 のどれでも同じに効く
    #    (toybox の tr は '\r' を解釈せず 'r' の意味に取るので、'\015' と書くこと)。
    if [ "$(tr -dc '\015' < "$src" | wc -c)" -ne 0 ]; then
        echo "patch_xml: CRLF が含まれている: $src" >&2
        return 3
    fi

    # 2. 登録するイヤホンの一覧を正規形にする。渡されなければ 0 件 = <deviceEffects> を書かない。
    ndev=0
    devlist=""
    if [ -n "$devfile" ] && [ -f "$devfile" ]; then
        devlist="$dst.dev"
        ca_canon_devices "$devfile" "$devlist" || return 17
        ndev=$(awk 'END { print NR }' "$devlist")
    fi

    # 3. 冪等化 — 先に自分のものを消す。
    ca_strip_ours "$src" > "$dst" || return 4

    # 4. アンカーの存在を確かめる。1 要素 1 行に整形されている前提に依存するので、
    #    minify された XML では黙って何もしないことになる。ここで検出する。
    if ! grep -q '^[[:space:]]*</libraries>[[:space:]]*$' "$dst"; then
        echo "patch_xml: </libraries> が単独行で見つからない" >&2
        return 5
    fi
    if ! grep -q '^[[:space:]]*</effects>[[:space:]]*$' "$dst"; then
        echo "patch_xml: </effects> が単独行で見つからない" >&2
        return 6
    fi
    if [ "$want_pp" = "1" ]; then
        # <postprocess> が無い端末に新設はしない。ベンダーの構造を作り変えないため。
        if ! grep -q '^[[:space:]]*<postprocess>[[:space:]]*$' "$dst"; then
            echo "patch_xml: <postprocess> が単独行で見つからない" >&2
            return 11
        fi
        if ! grep -q '<stream[^>]*type="music"' "$dst"; then
            echo "patch_xml: <stream type=\"music\"> が見つからない" >&2
            return 12
        fi
    fi
    if [ "$ndev" -gt 0 ]; then
        # strip の後に残っている <deviceEffects> はベンダーのもの。その中に足す。
        # 残っていなければ </audio_effects_conf> の直前に節ごと作る。
        if grep -q '<deviceEffects' "$dst"; then
            if ! grep -q '^[[:space:]]*</deviceEffects>[[:space:]]*$' "$dst"; then
                echo "patch_xml: <deviceEffects> はあるが </deviceEffects> が単独行で見つからない" >&2
                return 14
            fi
        elif ! grep -q '^[[:space:]]*</audio_effects_conf>[[:space:]]*$' "$dst"; then
            echo "patch_xml: </audio_effects_conf> が単独行で見つからない" >&2
            return 13
        fi
    fi

    # 5. 足す。<apply> は <postprocess> の中の music の直後にだけ入れる
    #    (<preprocess> にも <stream> があるので、囲いを見ないと入れる場所を間違える)。
    tmp="$dst.ins"
    awk -v lib="$CA_LIB_LINE" -v fx="$CA_FX_LINE" -v ap="$CA_APPLY_LINE" -v want="$want_pp" '
        /^[[:space:]]*<postprocess>[[:space:]]*$/    { inpp = 1 }
        /^[[:space:]]*<\/postprocess>[[:space:]]*$/  { inpp = 0 }
        /^[[:space:]]*<\/libraries>[[:space:]]*$/ && !ld { print lib; ld = 1 }
        /^[[:space:]]*<\/effects>[[:space:]]*$/   && !fd { print fx;  fd = 1 }
        { print }
        want == 1 && inpp && /<stream[^>]*type="music"/ && !ad { print ap; ad = 1 }
    ' "$dst" > "$tmp" || return 7
    mv "$tmp" "$dst" || return 8

    # 6. <deviceEffects> を足す。実機で通った綴りをそのまま書く。
    if [ "$ndev" -gt 0 ]; then
        awk -v devfile="$devlist" '
            function ca_ports(   l) {
                while ((getline l < devfile) > 0) {
                    if (l == "") continue
                    print "        <devicePort type=\"AUDIO_DEVICE_OUT_BLUETOOTH_A2DP\" address=\"" l "\">"
                    print "            <apply effect=\"ca_eq\"/>"
                    print "        </devicePort>"
                }
                close(devfile)
            }
            /^[[:space:]]*<\/deviceEffects>[[:space:]]*$/ && !done { ca_ports(); done = 1 }
            /^[[:space:]]*<\/audio_effects_conf>[[:space:]]*$/ && !done {
                print "    <deviceEffects>"; ca_ports(); print "    </deviceEffects>"; done = 1
            }
            { print }
            END { if (!done) exit 1 }
        ' "$dst" > "$tmp" || return 15
        mv "$tmp" "$dst" || return 8
    fi

    # 7. 実際に入ったかを確かめる。sed / awk が黙って何もしない事故を潰す。
    want_n=$((2 + ndev))
    [ "$want_pp" = "1" ] && want_n=$((want_n + 1))
    [ "$(grep -c 'ca_eq' "$dst")" = "$want_n" ] || {
        echo "patch_xml: 挿入後の行数が $want_n でない" >&2; return 9; }

    # 8. address が重複していないこと。同じ枠 (type + address) の <devicePort> が 2 つあると
    #    AudioDeviceTypeAddr が map のキーなので黙って 1 つに潰れる。
    dup=$(awk '
        /<devicePort/ {
            if (!match($0, /address="[^"]*"/)) next
            a = substr($0, RSTART + 9, RLENGTH - 10)
            t = "?"
            if (match($0, /type="[^"]*"/)) t = substr($0, RSTART + 6, RLENGTH - 7)
            k = t " " a
            if (k in seen) print k
            seen[k] = 1
        }' "$dst")
    if [ -n "$dup" ]; then
        echo "patch_xml: devicePort の枠が重複している: $dup" >&2
        return 16
    fi

    # 9. 差分が我々のものだけであることを確かめる。ここが一番強い検証。
    #    自分のものを消したものが、元から自分のものを消したものと一致すること。
    #    Dolby の <apply> が消えていればここで落ちる。
    ca_strip_ours "$dst" > "$dst.rt"
    ca_strip_ours "$src" > "$dst.orig"
    if ! cmp -s "$dst.rt" "$dst.orig"; then
        echo "patch_xml: 我々のもの以外に差分が出ている" >&2
        rm -f "$dst.rt" "$dst.orig" "$dst.dev"; return 10
    fi
    rm -f "$dst.rt" "$dst.orig" "$dst.dev"
    return 0
}
