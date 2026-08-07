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
# 登録済みイヤホンの一覧。/data にあるので再起動では消えない。
rm -f "$CA_DEVICES"
ca_log "uninstall 完了"
rm -f /data/adb/codecanchor_eq.log
