#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done
sleep 10

ca_log "===== service 開始 ====="

fail=""

if [ -f /dev/caeq/paths ]; then
    . /dev/caeq/paths
    ca_log "$CA_STAGE 段が置いたパスを使う: $CA_SRC_XML / $CA_LIBDIR"
else
    fail="$fail post-fs-data/late-load のどちらも最後まで走っていない"
    CA_SRC_XML=/vendor/etc/audio_effects.xml
    CA_LIBDIR=/vendor/lib64/soundfx
fi

[ -f "$CA_LIBDIR/libcaeq.so" ] || fail="$fail .so が見えない"
[ "$(grep -c 'name="ca_eq"' "$CA_SRC_XML" 2>/dev/null)" = "2" ] || fail="$fail XML の 2 行が見えない"

mapper=""
for m in /proc/[0-9]*/maps; do
    grep -q libcaeq.so "$m" 2>/dev/null || continue
    p=${m#/proc/}; mapper="$mapper ${p%/maps}"
done
other=""
for m in /proc/[0-9]*/maps; do
    grep -q soundfx "$m" 2>/dev/null || continue
    p=${m#/proc/}; other="$other ${p%/maps}"
done

if [ -n "$mapper" ]; then
    ca_log ".so の読み込み: 確認"
    for p in $mapper; do
        ca_log "  libcaeq.so を map しているプロセス: pid $p ($(cat "/proc/$p/cmdline" 2>/dev/null | tr '\0' ' '))"
    done
elif [ -z "$other" ]; then
    ca_log ".so の読み込み: 未確定 (soundfx を map しているプロセスが 1 つも無い)"
else
    ca_log "⚠️ .so の読み込み: 要調査 — 他の soundfx は map されているのに libcaeq.so だけ無い"
    ca_log "   (ファクトリが遅延読み込みなら正常。descriptor の列挙で開くなら異常)"
fi
if [ -z "$mapper" ]; then
    ca_log "--- 参考: soundfx を map しているプロセス ---"
    for p in $other; do
        {
            echo "pid $p ($(cat "/proc/$p/cmdline" 2>/dev/null | tr '\0' ' '))"
            grep soundfx "/proc/$p/maps" 2>/dev/null
        } >> "$CA_LOG"
    done
fi

{
    echo "--- 端末情報 ---"
    echo "設定 XML: $(ls -Z "$CA_SRC_XML" 2>/dev/null)"
    echo "ro.codecanchor.module_version=$(getprop ro.codecanchor.module_version)"
    echo "ro.codecanchor.module_semver=$(getprop ro.codecanchor.module_semver)"
    echo "ro.vendor.audio.fweffect=$(getprop ro.vendor.audio.fweffect)"
    echo "persist.vendor.audio.effectimplenter=$(getprop persist.vendor.audio.effectimplenter)"
    echo "persist.audio.effect.device_map=$(getprop persist.audio.effect.device_map)"
    echo "vendor.af.threshold.src_and_effect_count=$(getprop vendor.af.threshold.src_and_effect_count)"
    echo "ro.audio.ignore_effects=$(getprop ro.audio.ignore_effects)"
    echo "AIDL factory: $(service list 2>/dev/null | grep -c android.hardware.audio.effect.IFactory)"
    echo "--- postprocess の中身 ---"
    sed -n '/<postprocess>/,/<\/postprocess>/p' "$CA_SRC_XML" 2>/dev/null
} >> "$CA_LOG"

if [ -n "$fail" ]; then
    ca_log "自己検証に失敗:$fail"
    touch "$MODDIR/disable"
    ca_log "disable を置いた。次の起動で素の状態に戻る"
else
    ca_log "自己検証 OK"
fi
