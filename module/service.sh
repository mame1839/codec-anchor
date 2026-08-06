#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"

# boot が完了するまで待つ。audio HAL はここより後に立ち上がることがある。
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done
sleep 10

ca_log "===== service 開始 ====="

fail=""

# 1. post-fs-data で置いたものが今も見えるか (/vendor の overlay の自己検証も兼ねる)
[ -f /vendor/lib64/soundfx/libcaeq.so ] || fail="$fail .so が見えない"
[ "$(grep -c 'name="ca_eq"' /vendor/etc/audio_effects.xml)" = "2" ] || fail="$fail XML の 2 行が見えない"

# 2. audio HAL プロセスが実際に .so を map しているか。
#    これが「リンカと SELinux を抜けて読み込まれた」ことの直接の証拠。
#    post-fs-data の dlopen セルフテストは root のドメインでの ABI 検査でしかないので、
#    vendor プロセスから読めることの確認はここでしかできない。
HALPID=$(pidof android.hardware.audio.service.mediatek)
[ -n "$HALPID" ] || HALPID=$(pidof audioserver)
if [ -n "$HALPID" ]; then
    if grep -q libcaeq.so "/proc/$HALPID/maps"; then
        ca_log "HAL (pid $HALPID) が libcaeq.so を map している"
    else
        fail="$fail HAL が .so を map していない"
        ca_log "--- 参考: HAL が map している soundfx ---"
        grep soundfx "/proc/$HALPID/maps" >> "$CA_LOG"
    fi
else
    fail="$fail audio HAL のプロセスが見つからない"
fi

# 3. 端末情報一式を残す。端末差の変数はここに集める。
{
    echo "--- 端末情報 ---"
    echo "設定 XML: $(ls -Z /vendor/etc/audio_effects.xml 2>/dev/null)"
    echo "ro.vendor.audio.fweffect=$(getprop ro.vendor.audio.fweffect)"
    echo "persist.vendor.audio.effectimplenter=$(getprop persist.vendor.audio.effectimplenter)"
    echo "persist.audio.effect.device_map=$(getprop persist.audio.effect.device_map)"
    echo "vendor.af.threshold.src_and_effect_count=$(getprop vendor.af.threshold.src_and_effect_count)"
    echo "ro.audio.ignore_effects=$(getprop ro.audio.ignore_effects)"
    echo "AIDL factory: $(service list 2>/dev/null | grep -c android.hardware.audio.effect.IFactory)"
    echo "--- postprocess の中身 ---"
    sed -n '/<postprocess>/,/<\/postprocess>/p' /vendor/etc/audio_effects.xml
} >> "$CA_LOG"

if [ -n "$fail" ]; then
    ca_log "自己検証に失敗:$fail"
    touch "$MODDIR/disable"
    ca_log "disable を置いた。次の起動で素の状態に戻る"
else
    ca_log "自己検証 OK"
fi
