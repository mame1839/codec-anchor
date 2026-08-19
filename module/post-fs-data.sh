#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"
. "$MODDIR/common/patch_xml.sh"
. "$MODDIR/common/setup.sh"

ca_setup post-fs-data
