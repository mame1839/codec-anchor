#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"
. "$MODDIR/common/setup.sh"

ca_log "===== uninstall 開始 ====="

# bind mount は再起動で消えるので、消すのは自分が作った実体だけ。
# ただし mount が生きたまま /dev/caeq を消すと、bind mount 越しにベンダーの soundfx が
# 空になる (= 端末のエフェクトが全滅する)。先に外す。
# uninstall.sh は起動時の prune_modules から走るので普通は何も載っていないが、
# KernelSU の late-load は起動後に走るので、載ったまま来る経路がある。
safe=1
if [ -f /dev/caeq/paths ]; then
    . /dev/caeq/paths
    ca_pick_ns
    ca_unmount_ours || safe=0
fi

if [ "$safe" = 1 ]; then
    rm -rf /dev/caeq
else
    ca_log "警告: bind mount を外せないので /dev/caeq は残す (消すとベンダーの soundfx ごと消える)"
fi

rm -f /data/vendor/audio/ca_eq_stats.bin
ca_log "uninstall 完了"
rm -f /data/adb/codecanchor_eq.log
