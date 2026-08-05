#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/common/log.sh"
. "$MODDIR/common/patch_xml.sh"

ca_log "===== post-fs-data 開始 ====="

WORK=/dev/caeq
# 毎起動で作り直す。焼き込んだ patch 済みファイルを被せると、OS 更新でベンダーが増やした
# エフェクトを消すことになる。ここが一番危険な失敗。
rm -rf "$WORK"
mkdir -p "$WORK/soundfx" "$WORK/etc" || ca_die "作業領域を作れない"

# --- 1. 設定 XML を探す ---------------------------------------------------
# HIDL 経路の探索路は audio_config.h の逐語で 3 ディレクトリ、ファイル名は 1 つだけ。
# ro.boot.product.vendor.sku が空なので sku_* は生成されない。
SRC_XML=""
for d in /odm/etc /vendor/etc /system/etc; do
    if [ -f "$d/audio_effects.xml" ]; then SRC_XML="$d/audio_effects.xml"; break; fi
done
[ -n "$SRC_XML" ] || ca_die "設定 XML が 1 本も無い。この端末は非対応 (新規作成するとベンダーエフェクトが全滅する)"
ca_log "実効の設定 XML: $SRC_XML"

# --- 2. .so を置く --------------------------------------------------------
# 実効設定ファイルと同じパーティションの lib64/soundfx。/system/lib64/soundfx には置かない
# (この端末に存在しないうえ、linker の permitted.paths が /odm /vendor /system/vendor に
#  限られているので vendor プロセスから dlopen できない)。
case "$SRC_XML" in
    /odm/*)    LIBDIR=/odm/lib64/soundfx ;;
    /vendor/*) LIBDIR=/vendor/lib64/soundfx ;;
    *)         ca_die "設定 XML が $SRC_XML にある。この経路の lib64/soundfx は未対応" ;;
esac
[ -d "$LIBDIR" ] || ca_die "$LIBDIR が無い"

cp -a "$LIBDIR"/. "$WORK/soundfx/" || ca_die "soundfx の複製に失敗"
cp "$MODDIR/libcaeq.so" "$WORK/soundfx/libcaeq.so" || ca_die "libcaeq.so の配置に失敗"
chmod 644 "$WORK/soundfx/libcaeq.so"
# ラベルはハードコードせず、元のディレクトリから継承する。
chcon -R --reference="$LIBDIR" "$WORK/soundfx" || ca_die "soundfx のラベル継承に失敗"

# --- 3. dlopen のセルフテスト --------------------------------------------
# 先に実行ビットを立て直す。zip の作られ方でここは簡単に落ちる —
# Windows の info-zip は FAT 属性しか記録しないので staging での chmod が zip に残らず、
# root マネージャ側の既定も 0644 のことがある。落ちたまま実行すると「.so が駄目」に見える。
chmod 755 "$MODDIR/dlopen_check" "$MODDIR/caeqstat" 2>/dev/null

# ここで落ちるものを overlay しない。
# ただしこれは root のドメインでの dlopen なので、ABI とシンボルしか確かめていない。
# 「vendor プロセスから読めるか」(linker の permitted.paths と SELinux) は service.sh が
# /proc/<hal_pid>/maps で確かめる。別々の検査であることを取り違えないこと。
if ! "$MODDIR/dlopen_check" "$WORK/soundfx/libcaeq.so" >>"$CA_LOG" 2>&1; then
    ca_die ".so の dlopen セルフテストに失敗。overlay しない (ABI/シンボルの検査。SELinux は別)"
fi

# --- 4. XML に 2 行足す --------------------------------------------------
if ! ca_patch_xml "$SRC_XML" "$WORK/etc/audio_effects.xml" 2>>"$CA_LOG"; then
    ca_die "XML の patch に失敗"
fi
chmod 644 "$WORK/etc/audio_effects.xml"
chcon --reference="$SRC_XML" "$WORK/etc/audio_effects.xml" || ca_die "XML のラベル継承に失敗"
ca_log "XML patch 完了 ($(wc -c < "$SRC_XML") -> $(wc -c < "$WORK/etc/audio_effects.xml") バイト)"

# --- 5. 統計ファイルを用意する -------------------------------------------
SHM=/data/vendor/audio/ca_eq_stats.bin
if [ -d /data/vendor/audio ]; then
    rm -f "$SHM"
    dd if=/dev/zero of="$SHM" bs=1152 count=1 2>/dev/null
    chmod 664 "$SHM"
    chown audioserver:audio "$SHM"
    chcon --reference=/data/vendor/audio "$SHM"
    ca_log "統計ファイルを用意: $(ls -Z "$SHM")"
else
    ca_log "警告: /data/vendor/audio が無い。カウンタは logcat だけになる"
fi

# --- 6. sepolicy ---------------------------------------------------------
# root マネージャ 3 分岐。静的な sepolicy.rule と二重化する。
if   command -v magiskpolicy >/dev/null 2>&1; then
    while read -r r; do [ -n "$r" ] && magiskpolicy --live "$r" >>"$CA_LOG" 2>&1; done < "$MODDIR/sepolicy.rule"
elif command -v ksud >/dev/null 2>&1; then
    ksud sepolicy apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
elif command -v apd >/dev/null 2>&1; then
    apd sepolicy --live --apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
else
    ca_log "警告: sepolicy の適用手段が見つからない"
fi

# OEM がエフェクト機構ごと殺すプロパティ。この実機には無いが、ある端末がある。
i=0; while [ $i -lt 5 ]; do resetprop ro.audio.ignore_effects false 2>/dev/null; i=$((i+1)); done

# --- 7. bind mount -------------------------------------------------------
# /vendor の overlay はこの実機では効いていないので、init の名前空間へ直接入れるのが唯一の道。
# 同じ XML を 2 プロセスが別々に読む (vendor HAL が <libraries>/<effects>、
# audioserver が <postprocess>) ので、両方から見えている必要がある。
# どちらも init 起動なので nsenter -t 1 -m で足りる。
ca_mount() {
    nsenter -t 1 -m -- mount -o bind "$1" "$2" 2>>"$CA_LOG" \
      || mount -o bind "$1" "$2" 2>>"$CA_LOG" \
      || return 1
    return 0
}
ca_mount "$WORK/soundfx" "$LIBDIR" || ca_die "soundfx の bind mount に失敗"
ca_mount "$WORK/etc/audio_effects.xml" "$SRC_XML" || ca_die "XML の bind mount に失敗"

# --- 8. init の視点から読み直して検証 ------------------------------------
nsenter -t 1 -m -- test -f "$LIBDIR/libcaeq.so" || ca_die "init の名前空間に .so が見えない"
n=$(nsenter -t 1 -m -- grep -c 'name="ca_eq"' "$SRC_XML")
[ "$n" = "2" ] || ca_die "init の名前空間で XML の 2 行が見えない (n=$n)"
ca_log "post-fs-data 完了"
