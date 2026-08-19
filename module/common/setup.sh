CA_WORK=${CA_WORK:-/dev/caeq}
CA_DEVICES=${CA_DEVICES:-/data/adb/codecanchor_eq_devices}

CA_NS=""

ca_want_pp() {
    [ -f "$MODDIR/use_postprocess" ] && echo 1 || echo 0
}

ca_restart_audioserver() {
    ca_as_old=$(pidof audioserver)
    if [ -z "$ca_as_old" ]; then
        ca_log "警告: audioserver が居ない"
        return 2
    fi
    ca_log "audioserver を作り直す (音が一瞬切れる)。旧 pid $ca_as_old"
    kill $ca_as_old 2>>"$CA_LOG"
    ca_as_i=0
    while [ $ca_as_i -lt 10 ]; do
        sleep 1
        ca_as_new=$(pidof audioserver)
        if [ -n "$ca_as_new" ] && [ "$ca_as_new" != "$ca_as_old" ]; then
            ca_log "audioserver を作り直した (新 pid $ca_as_new)"
            return 0
        fi
        ca_as_i=$((ca_as_i+1))
    done
    ca_log "警告: audioserver を落としたが戻ってこない"
    return 1
}

ca_pick_ns() {
    CA_NS=""
    if nsenter -t 1 -m -- true 2>/dev/null; then CA_NS="nsenter -t 1 -m --"; fi
    ca_log "init の名前空間: ${CA_NS:-使わない (自分の名前空間で操作)}"
}

ca_mount() {
    $CA_NS mount -o bind "$1" "$2" 2>>"$CA_LOG" && return 0
    [ -n "$CA_NS" ] || return 1
    mount -o bind "$1" "$2" 2>>"$CA_LOG" || return 1
    ca_log "警告: init の名前空間にマウントできないので、以後は自分の名前空間で見る"
    CA_NS=""
}

ca_ours_mounted() {
    [ -n "${CA_LIBDIR:-}" ] || return 1
    $CA_NS test -f "$CA_LIBDIR/libcaeq.so" && return 0
    $CA_NS grep -q 'name="ca_eq"' "$CA_SRC_XML" 2>/dev/null
}

ca_unmount_ours() {
    ca_i=0
    while [ $ca_i -lt 8 ] && ca_ours_mounted; do
        $CA_NS umount "$CA_SRC_XML" 2>>"$CA_LOG"
        $CA_NS umount "$CA_LIBDIR"  2>>"$CA_LOG"
        ca_i=$((ca_i+1))
    done
    ca_ours_mounted && return 1
    [ $ca_i -eq 0 ] || ca_log "前の bind mount を $ca_i 回で外した"
    return 0
}

ca_setup() {
    CA_STAGE="$1"
    ca_log "===== $CA_STAGE 開始 (runtime=${KSU_RUNTIME_MODE:-?} late_load=${KSU_LATE_LOAD:-0}) ====="

    WORK="$CA_WORK"

    ca_pick_ns

    CA_SRC_XML=""
    for d in /odm/etc /vendor/etc /system/etc; do
        if [ -f "$d/audio_effects.xml" ]; then CA_SRC_XML="$d/audio_effects.xml"; break; fi
    done
    [ -n "$CA_SRC_XML" ] || ca_die "設定 XML が 1 本も無い。この端末は非対応 (新規作成するとベンダーエフェクトが全滅する)"
    ca_log "実効の設定 XML: $CA_SRC_XML"

    case "$CA_SRC_XML" in
        /odm/*)    CA_LIBDIR=/odm/lib64/soundfx ;;
        /vendor/*) CA_LIBDIR=/vendor/lib64/soundfx ;;
        *)         ca_die "設定 XML が $CA_SRC_XML にある。この経路の lib64/soundfx は未対応" ;;
    esac
    [ -d "$CA_LIBDIR" ] || ca_die "$CA_LIBDIR が無い"

    ca_unmount_ours \
      || ca_die "前の bind mount を外せない。$WORK を消すとベンダーの soundfx ごと消えるので中止する"

    rm -rf "$WORK"
    mkdir -p "$WORK/soundfx" "$WORK/etc" || ca_die "作業領域を作れない"

    cp -a "$CA_LIBDIR"/. "$WORK/soundfx/" || ca_die "soundfx の複製に失敗"
    cp "$MODDIR/libcaeq.so" "$WORK/soundfx/libcaeq.so" || ca_die "libcaeq.so の配置に失敗"
    chmod 644 "$WORK/soundfx/libcaeq.so"
    chcon -R --reference="$CA_LIBDIR" "$WORK/soundfx" || ca_die "soundfx のラベル継承に失敗"

    chmod 755 "$MODDIR/dlopen_check" "$MODDIR/caeqstat" 2>/dev/null

    if ! "$MODDIR/dlopen_check" "$WORK/soundfx/libcaeq.so" >>"$CA_LOG" 2>&1; then
        ca_die ".so の dlopen セルフテストに失敗。overlay しない (ABI/シンボルの検査。SELinux は別)"
    fi

    CA_PRISTINE_XML="$WORK/etc/audio_effects.xml.pristine"
    cp "$CA_SRC_XML" "$CA_PRISTINE_XML" || ca_die "patch 前の原本を取っておけない"

    CA_DEVLIST="$WORK/devices"
    : > "$CA_DEVLIST"
    if [ -f "$CA_DEVICES" ]; then
        if ca_canon_devices "$CA_DEVICES" "$CA_DEVLIST" 2>>"$CA_LOG"; then
            ca_log "登録済みのイヤホン: $(awk 'END { print NR }' "$CA_DEVLIST") 件"
        else
            : > "$CA_DEVLIST"
            ca_log "警告: $CA_DEVICES が壊れている。<deviceEffects> は書かない"
        fi
    fi

    CA_PP=$(ca_want_pp)
    ca_log "postprocess への登録: $CA_PP"
    if ! ca_patch_xml "$CA_PRISTINE_XML" "$WORK/etc/audio_effects.xml" "$CA_PP" "$CA_DEVLIST" 2>>"$CA_LOG"; then
        ca_die "XML の patch に失敗"
    fi
    chmod 644 "$WORK/etc/audio_effects.xml"
    chcon --reference="$CA_SRC_XML" "$WORK/etc/audio_effects.xml" || ca_die "XML のラベル継承に失敗"
    ca_log "XML patch 完了 ($(wc -c < "$CA_SRC_XML") -> $(wc -c < "$WORK/etc/audio_effects.xml") バイト)"

    SHM=/data/vendor/audio/ca_eq_stats.bin
    if [ -d /data/vendor/audio ]; then
        rm -f "$SHM"
        dd if=/dev/zero of="$SHM" bs=19584 count=1 2>/dev/null
        chmod 664 "$SHM"
        chown audioserver:audio "$SHM"
        chcon --reference=/data/vendor/audio "$SHM"
        ca_log "共有メモリを用意: $(ls -Z "$SHM")"
    else
        ca_log "警告: /data/vendor/audio が無い。カウンタは logcat だけになる"
    fi

    if   [ "${KSU:-}" = "true" ] && command -v ksud >/dev/null 2>&1; then
        ca_log "root マネージャ: KernelSU (${KSU_VER:-?})"
        ksud sepolicy apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
    elif [ "${APATCH:-}" = "true" ] && command -v apd >/dev/null 2>&1; then
        ca_log "root マネージャ: APatch (${APATCH_VER:-?})"
        apd sepolicy --live --apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
    elif command -v magiskpolicy >/dev/null 2>&1; then
        ca_log "root マネージャ: Magisk (${MAGISK_VER:-?})"
        while read -r r; do [ -n "$r" ] && magiskpolicy --live "$r" >>"$CA_LOG" 2>&1; done < "$MODDIR/sepolicy.rule"
    else
        ca_log "警告: sepolicy の適用手段が見つからない"
    fi

    resetprop -n ro.audio.ignore_effects false 2>>"$CA_LOG" || ca_log "警告: resetprop に失敗"

    ca_mount "$WORK/soundfx" "$CA_LIBDIR"  || ca_die "soundfx の bind mount に失敗"
    ca_mount "$WORK/etc/audio_effects.xml" "$CA_SRC_XML" || ca_die "XML の bind mount に失敗"

    $CA_NS test -f "$CA_LIBDIR/libcaeq.so" || ca_die "init の名前空間に .so が見えない"
    n=$($CA_NS grep -c 'name="ca_eq"' "$CA_SRC_XML")
    [ "$n" = "2" ] || ca_die "init の名前空間で XML の 2 行が見えない (n=$n)"

    {
        echo "CA_STAGE=$CA_STAGE"
        echo "CA_SRC_XML=$CA_SRC_XML"
        echo "CA_LIBDIR=$CA_LIBDIR"
        echo "CA_PRISTINE_XML=$CA_PRISTINE_XML"
    } > "$WORK/paths" || ca_die "paths を書けない"

    if [ "$CA_STAGE" = "late-load" ]; then
        ca_restart_audioserver || ca_log "late-load モードだが audioserver を作り直せない。反映は次の再起動から"
    fi

    ca_log "$CA_STAGE 完了"
}
