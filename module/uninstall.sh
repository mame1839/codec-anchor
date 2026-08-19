#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"
. "$MODDIR/common/setup.sh"

ca_log "===== uninstall 開始 ====="

safe=1
if [ -f "$CA_WORK/paths" ]; then
    . "$CA_WORK/paths"
    ca_pick_ns
    ca_unmount_ours || safe=0
fi

if [ "$safe" = 1 ]; then
    rm -rf "$CA_WORK"
else
    ca_log "警告: bind mount を外せないので $CA_WORK は残す (消すとベンダーの soundfx ごと消える)"
fi

rm -f /data/vendor/audio/ca_eq_stats.bin
rm -f "$CA_DEVICES"
ca_log "uninstall 完了"
rm -f /data/adb/codecanchor_eq.log
