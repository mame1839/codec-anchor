# ca_patch_xml <入力ファイル> <出力ファイル> [postprocess に足すか (既定 1)]
#   既存の XML を読んで <libraries> と <effects> に 1 行ずつ足す。
#   第 3 引数が 1 なら <postprocess> の <stream type="music"> にも <apply> を 1 行足す。
#   丸ごと差し替えない。既存の <apply> (実機では Dolby の listener が music/ring/alarm に居る)
#   には触らない。冪等 (先に自分の行を消してから足す)。
#   成功で 0、アンカーが見つからない / 挿入できなかったら非 0。
CA_LIB_LINE='        <library name="ca_eq" path="libcaeq.so"/>'
CA_FX_LINE='        <effect name="ca_eq" library="ca_eq" uuid="7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1"/>'
CA_APPLY_LINE='            <apply effect="ca_eq"/>'

# 自分が書いた行だけを消す式。<apply> は name= ではなく effect= なので 2 つ要る。
ca_strip_ours() { sed -e '/name="ca_eq"/d' -e '/effect="ca_eq"/d' "$1"; }

ca_patch_xml() {
    src="$1"; dst="$2"; want_pp="${3:-1}"

    # 1. CRLF を拒否する。adb shell cat 経由で持ってきたファイルは CRLF になっている。
    #    grep で CR を探してはいけない — Windows (MSYS2) の grep は行末の CR を落としてから
    #    照合するので、ホストでこのテストを走らせると CRLF を素通しする。
    #    tr の 8 進エスケープなら toybox / GNU / MSYS2 のどれでも同じに効く
    #    (toybox の tr は '\r' を解釈せず 'r' の意味に取るので、'\015' と書くこと)。
    if [ "$(tr -dc '\015' < "$src" | wc -c)" -ne 0 ]; then
        echo "patch_xml: CRLF が含まれている: $src" >&2
        return 3
    fi

    # 2. 冪等化 — 先に自分の行を消す。
    ca_strip_ours "$src" > "$dst" || return 4

    # 3. アンカーの存在を確かめる。1 要素 1 行に整形されている前提に依存するので、
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

    # 4. 足す。<apply> は <postprocess> の中の music の直後にだけ入れる
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

    # 5. 実際に入ったかを確かめる。sed / awk が黙って何もしない事故を潰す。
    want_n=2
    [ "$want_pp" = "1" ] && want_n=3
    [ "$(grep -c 'ca_eq' "$dst")" = "$want_n" ] || {
        echo "patch_xml: 挿入後の行数が $want_n でない" >&2; return 9; }

    # 6. 差分が我々の行だけであることを確かめる。ここが一番強い検証。
    #    自分の行を消したものが、元から自分の行を消したものと一致すること。
    #    Dolby の <apply> が消えていればここで落ちる。
    ca_strip_ours "$dst" > "$dst.rt"
    ca_strip_ours "$src" > "$dst.orig"
    if ! cmp -s "$dst.rt" "$dst.orig"; then
        echo "patch_xml: 我々の行以外に差分が出ている" >&2
        rm -f "$dst.rt" "$dst.orig"; return 10
    fi
    rm -f "$dst.rt" "$dst.orig"
    return 0
}
