#!/system/bin/sh
# 入口は 2 つある。KernelSU の late-load モード (起動が終わってからカーネルモジュールを読む構成)
# では post-fs-data 段が丸ごと skip され、代わりに late-load 段が走るため、両方に入口を置く。
# late-load.sh は late-load モードでしか走らないので、2 つが同時に走ることはない。
# 本体は common/setup.sh に 1 つだけ置く。この 2 ファイルの差は ca_setup の引数だけで、
# module/test/module_test.sh がそれを見張っている。
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"
. "$MODDIR/common/patch_xml.sh"
. "$MODDIR/common/setup.sh"

ca_setup late-load
