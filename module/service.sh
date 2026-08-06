#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"

# boot が完了するまで待つ。audio HAL はここより後に立ち上がることがある。
# late-load モードでは sys.boot_completed は最初から 1 だが、その段で audioserver を
# 落として作り直させているので、待ち時間の意味は同じ。
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 120 ]; do sleep 1; i=$((i+1)); done
sleep 10

ca_log "===== service 開始 ====="

fail=""

# 0. post-fs-data / late-load が解決したパスを受け取る。
#    ここで /vendor を決め打ちすると、audio_effects.xml が /odm にある端末では
#    自己検証が必ず落ちて、動いているモジュールを自分で無効化することになる。
if [ -f /dev/caeq/paths ]; then
    . /dev/caeq/paths
    ca_log "$CA_STAGE 段が置いたパスを使う: $CA_SRC_XML / $CA_LIBDIR"
else
    fail="$fail post-fs-data/late-load のどちらも最後まで走っていない"
    CA_SRC_XML=/vendor/etc/audio_effects.xml
    CA_LIBDIR=/vendor/lib64/soundfx
fi

# 1. post-fs-data で置いたものが今も見えるか (/vendor の overlay の自己検証も兼ねる)
[ -f "$CA_LIBDIR/libcaeq.so" ] || fail="$fail .so が見えない"
[ "$(grep -c 'name="ca_eq"' "$CA_SRC_XML" 2>/dev/null)" = "2" ] || fail="$fail XML の 2 行が見えない"

# 2. エフェクトを読み込むプロセスが実際に .so を map しているか。
#    これが「リンカと SELinux を抜けて読み込まれた」ことの直接の証拠。
#    post-fs-data の dlopen セルフテストは root のドメインでの ABI 検査でしかないので、
#    vendor プロセスから読めることの確認はここでしかできない。
#
#    プロセス名で引かないこと。この実機は android.hardware.audio.service.mediatek だが、
#    HAL の名前は SoC とベンダーで変わり、audioserver 自身が読む構成もある。
#    名前で引くと MediaTek 以外の端末で必ず「map していない」に落ちて自分を無効化する。
mapper=""
for m in /proc/[0-9]*/maps; do
    grep -q libcaeq.so "$m" 2>/dev/null || continue
    p=${m#/proc/}; mapper="$mapper ${p%/maps}"
done

if [ -n "$mapper" ]; then
    for p in $mapper; do
        ca_log "libcaeq.so を map しているプロセス: pid $p ($(cat "/proc/$p/cmdline" 2>/dev/null | tr '\0' ' '))"
    done
else
    fail="$fail .so を map しているプロセスが 1 つも無い"
    ca_log "--- 参考: soundfx を map しているプロセス ---"
    for m in /proc/[0-9]*/maps; do
        grep -q soundfx "$m" 2>/dev/null || continue
        p=${m#/proc/}; p=${p%/maps}
        {
            echo "pid $p ($(cat "/proc/$p/cmdline" 2>/dev/null | tr '\0' ' '))"
            grep soundfx "$m"
        } >> "$CA_LOG"
    done
fi

# 3. 端末情報一式を残す。端末差の変数はここに集める。
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
