# 上書きできるのはホストでテストを走らせるため。実機では常に既定値。
CA_LOG=${CA_LOG:-/data/adb/codecanchor_eq.log}
ca_log() { echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$CA_LOG"; }
ca_die() {
    ca_log "FATAL: $*"
    touch "$MODDIR/disable"
    ca_log "disable を置いた。次の起動で素の状態に戻る"
    exit 1
}
