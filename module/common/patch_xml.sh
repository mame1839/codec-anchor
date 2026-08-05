# ca_patch_xml <入力ファイル> <出力ファイル>
#   既存の XML を読んで <libraries> と <effects> に 1 行ずつ足す。
#   丸ごと差し替えない。<postprocess> には触らない。冪等 (先に自分の行を消してから足す)。
#   成功で 0、アンカーが見つからない / 挿入できなかったら非 0。
CA_LIB_LINE='        <library name="ca_eq" path="libcaeq.so"/>'
CA_FX_LINE='        <effect name="ca_eq" library="ca_eq" uuid="7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1"/>'

ca_patch_xml() {
    src="$1"; dst="$2"

    # 1. CRLF を拒否する。adb shell cat 経由で持ってきたファイルは CRLF になっている。
    #    grep で CR を探してはいけない — Windows (MSYS2) の grep は行末の CR を落としてから
    #    照合するので、ホストでこのテストを走らせると CRLF を素通しする。
    #    tr の 8 進エスケープなら toybox / GNU / MSYS2 のどれでも同じに効く
    #    (toybox の tr は '\r' を解釈せず 'r' の意味に取るので、'\015' と書くこと)。
    if [ "$(tr -dc '\015' < "$src" | wc -c)" -ne 0 ]; then
        echo "patch_xml: CRLF が含まれている: $src" >&2
        return 3
    fi

    # 2. 冪等化 — 先に自分の行を消す。name="ca_eq" を持つ行だけが対象。
    #    <apply effect="ca_eq"/> は我々が書かないので、この式では消えない。
    sed '/name="ca_eq"/d' "$src" > "$dst" || return 4

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

    # 4. 2 行足す。
    tmp="$dst.ins"
    awk -v lib="$CA_LIB_LINE" -v fx="$CA_FX_LINE" '
        /^[[:space:]]*<\/libraries>[[:space:]]*$/ && !ld { print lib; ld = 1 }
        /^[[:space:]]*<\/effects>[[:space:]]*$/   && !fd { print fx;  fd = 1 }
        { print }
    ' "$dst" > "$tmp" || return 7
    mv "$tmp" "$dst" || return 8

    # 5. 実際に入ったかを確かめる。sed / awk が黙って何もしない事故を潰す。
    [ "$(grep -c 'name="ca_eq"' "$dst")" = "2" ] || {
        echo "patch_xml: 挿入後の行数が 2 でない" >&2; return 9; }

    # 6. 差分が我々の 2 行だけであることを確かめる。ここが一番強い検証。
    #    自分の行を消したものが、元から自分の行を消したものと一致すること。
    sed '/name="ca_eq"/d' "$dst" > "$dst.rt"
    sed '/name="ca_eq"/d' "$src" > "$dst.orig"
    if ! cmp -s "$dst.rt" "$dst.orig"; then
        echo "patch_xml: 2 行以外の差分が出ている" >&2
        rm -f "$dst.rt" "$dst.orig"; return 10
    fi
    rm -f "$dst.rt" "$dst.orig"
    return 0
}
