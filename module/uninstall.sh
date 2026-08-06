#!/system/bin/sh
# bind mount は再起動で消えるので、消すのは自分が作った実体だけ。
rm -rf /dev/caeq
rm -f /data/vendor/audio/ca_eq_stats.bin
rm -f /data/adb/codecanchor_eq.log
