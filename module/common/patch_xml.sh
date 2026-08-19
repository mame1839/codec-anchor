CA_LIB_LINE='        <library name="ca_eq" path="libcaeq.so"/>'
CA_FX_LINE='        <effect name="ca_eq" library="ca_eq" uuid="7a1c9f60-4a2e-4f6b-9d21-0a5c1b3e77d1"/>'
CA_APPLY_LINE='            <apply effect="ca_eq"/>'

CA_MAX_DEVICES=64

ca_hex='[0123456789ABCDEF]'
CA_MAC_GLOB="$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex:$ca_hex$ca_hex"
unset ca_hex

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

    if [ "$(tr -dc '\015' < "$src" | wc -c)" -ne 0 ]; then
        echo "patch_xml: CRLF が含まれている: $src" >&2
        return 3
    fi

    ndev=0
    devlist=""
    if [ -n "$devfile" ] && [ -f "$devfile" ]; then
        devlist="$dst.dev"
        ca_canon_devices "$devfile" "$devlist" || return 17
        ndev=$(awk 'END { print NR }' "$devlist")
    fi

    ca_strip_ours "$src" > "$dst" || return 4

    if ! grep -q '^[[:space:]]*</libraries>[[:space:]]*$' "$dst"; then
        echo "patch_xml: </libraries> が単独行で見つからない" >&2
        return 5
    fi
    if ! grep -q '^[[:space:]]*</effects>[[:space:]]*$' "$dst"; then
        echo "patch_xml: </effects> が単独行で見つからない" >&2
        return 6
    fi
    if [ "$want_pp" = "1" ]; then
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

    want_n=$((2 + ndev))
    [ "$want_pp" = "1" ] && want_n=$((want_n + 1))
    [ "$(grep -c 'ca_eq' "$dst")" = "$want_n" ] || {
        echo "patch_xml: 挿入後の行数が $want_n でない" >&2; return 9; }

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

    ca_strip_ours "$dst" > "$dst.rt"
    ca_strip_ours "$src" > "$dst.orig"
    if ! cmp -s "$dst.rt" "$dst.orig"; then
        echo "patch_xml: 我々のもの以外に差分が出ている" >&2
        rm -f "$dst.rt" "$dst.orig" "$dst.dev"; return 10
    fi
    rm -f "$dst.rt" "$dst.orig" "$dst.dev"
    return 0
}
