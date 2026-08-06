# post-fs-data.sh と late-load.sh の共通本体。
#
# KernelSU の動作モードは built-in / lkm / late-load の 3 つ (`KSU_RUNTIME_MODE`)。
# late-load は起動が終わってからカーネルモジュールを読む構成で、公式ドキュメントの差分表は
# post-fs-data.sh / post-fs-data.d/ を "Replaced by `late-load` stage" と書いている。
# late-load.sh は逆に late-load モードでしか走らないので、両方置いても二重には走らない。
# service.sh / post-mount.sh / boot-completed.sh / system.prop は late-load でも走る。
#
# late-load が post-fs-data と違うのは 2 点だけ。どちらも「OS が既に立ち上がっている」ことから来る:
#   - 前回の bind mount が生きていることがある (消す前に外さないとベンダーの soundfx が空になる)
#   - audioserver と audio HAL が元の XML と元の soundfx を読み終えている (作り直させないと効かない)

# init の名前空間でコマンドを走らせるための接頭辞。使えなければ空 = 自分の名前空間。
# 展開時に単語分割させたいので $CA_NS は引用しない。
CA_NS=""

# init の名前空間に入る手段を決める。
# 同じ XML を 2 プロセスが別々に読む (vendor HAL が <libraries>/<effects>、
# audioserver が <postprocess>) ので、両方から見えている必要がある。どちらも init 起動。
# KernelSU Next では nsenter -t 1 -m は実質 no-op だが、Magisk は namespace mode を
# 持つので残す。ここを決め打ちにすると、nsenter の無い端末で
# 「マウントには成功して検証だけが落ちる」ことになる。
ca_pick_ns() {
    CA_NS=""
    if nsenter -t 1 -m -- true 2>/dev/null; then CA_NS="nsenter -t 1 -m --"; fi
    ca_log "init の名前空間: ${CA_NS:-使わない (自分の名前空間で操作)}"
}

ca_mount() {
    $CA_NS mount -o bind "$1" "$2" 2>>"$CA_LOG" && return 0
    [ -n "$CA_NS" ] || return 1
    mount -o bind "$1" "$2" 2>>"$CA_LOG" || return 1
    # init の名前空間には入れなかったが自分の名前空間では通った。以後の検証もそちらで見る —
    # 置いた場所と見る場所がずれると、成功しているのに検証だけが落ちる。
    ca_log "警告: init の名前空間にマウントできないので、以後は自分の名前空間で見る"
    CA_NS=""
}

# 自分の bind mount が生きているか。起動直後は常に偽。
ca_ours_mounted() {
    [ -n "${CA_LIBDIR:-}" ] || return 1
    $CA_NS test -f "$CA_LIBDIR/libcaeq.so" && return 0
    $CA_NS grep -q 'name="ca_eq"' "$CA_SRC_XML" 2>/dev/null
}

# 自分の bind mount を外す。外し切れなければ非 0。
# /dev/caeq を消す前に必ず通すこと。mount が生きたまま消すと、bind mount 越しに
# ベンダーの soundfx が空に見える = 端末のエフェクトが全滅する。
# 起動後に走る経路 (KernelSU の late-load、prune_modules からの uninstall.sh) でしか
# 起きないので、実機の built-in モードでは踏めない。
ca_unmount_ours() {
    ca_i=0
    while [ $ca_i -lt 8 ] && ca_ours_mounted; do
        $CA_NS umount "$CA_SRC_XML" 2>>"$CA_LOG"
        $CA_NS umount "$CA_LIBDIR"  2>>"$CA_LOG"
        ca_i=$((ca_i+1))
    done
    ca_ours_mounted && return 1
    [ $ca_i -eq 0 ] || ca_log "前の bind mount を $ca_i 回で外した"
    return 0
}

ca_setup() {
    CA_STAGE="$1"
    ca_log "===== $CA_STAGE 開始 (runtime=${KSU_RUNTIME_MODE:-?} late_load=${KSU_LATE_LOAD:-0}) ====="

    WORK=/dev/caeq

    # --- 0. init の名前空間に入る手段を決める --------------------------------
    ca_pick_ns

    # --- 1. 設定 XML と .so の置き場を決める ---------------------------------
    # HIDL 経路の探索路は audio_config.h の逐語で 3 ディレクトリ、ファイル名は 1 つだけ。
    # ro.boot.product.vendor.sku が空なので sku_* は生成されない。
    CA_SRC_XML=""
    for d in /odm/etc /vendor/etc /system/etc; do
        if [ -f "$d/audio_effects.xml" ]; then CA_SRC_XML="$d/audio_effects.xml"; break; fi
    done
    [ -n "$CA_SRC_XML" ] || ca_die "設定 XML が 1 本も無い。この端末は非対応 (新規作成するとベンダーエフェクトが全滅する)"
    ca_log "実効の設定 XML: $CA_SRC_XML"

    # 実効設定ファイルと同じパーティションの lib64/soundfx。/system/lib64/soundfx には置かない
    # (この端末に存在しないうえ、linker の permitted.paths が /odm /vendor /system/vendor に
    #  限られているので vendor プロセスから dlopen できない)。
    case "$CA_SRC_XML" in
        /odm/*)    CA_LIBDIR=/odm/lib64/soundfx ;;
        /vendor/*) CA_LIBDIR=/vendor/lib64/soundfx ;;
        *)         ca_die "設定 XML が $CA_SRC_XML にある。この経路の lib64/soundfx は未対応" ;;
    esac
    [ -d "$CA_LIBDIR" ] || ca_die "$CA_LIBDIR が無い"

    # --- 2. 前回の bind mount を外す -----------------------------------------
    # late-load は起動後に走るので 2 回目の実行がありうる。
    ca_unmount_ours \
      || ca_die "前の bind mount を外せない。$WORK を消すとベンダーの soundfx ごと消えるので中止する"

    # --- 3. 作業領域 ---------------------------------------------------------
    # 毎起動で作り直す。焼き込んだ patch 済みファイルを被せると、OS 更新でベンダーが増やした
    # エフェクトを消すことになる。ここが一番危険な失敗。
    rm -rf "$WORK"
    mkdir -p "$WORK/soundfx" "$WORK/etc" || ca_die "作業領域を作れない"

    # --- 4. .so を置く -------------------------------------------------------
    cp -a "$CA_LIBDIR"/. "$WORK/soundfx/" || ca_die "soundfx の複製に失敗"
    cp "$MODDIR/libcaeq.so" "$WORK/soundfx/libcaeq.so" || ca_die "libcaeq.so の配置に失敗"
    chmod 644 "$WORK/soundfx/libcaeq.so"
    # ラベルはハードコードせず、元のディレクトリから継承する。
    chcon -R --reference="$CA_LIBDIR" "$WORK/soundfx" || ca_die "soundfx のラベル継承に失敗"

    # --- 5. dlopen のセルフテスト --------------------------------------------
    # 先に実行ビットを立て直す。zip の作られ方でここは簡単に落ちる —
    # Windows の info-zip は FAT 属性しか記録しないので staging での chmod が zip に残らず、
    # root マネージャ側の既定も 0644 のことがある。落ちたまま実行すると「.so が駄目」に見える。
    chmod 755 "$MODDIR/dlopen_check" "$MODDIR/caeqstat" 2>/dev/null

    # ここで落ちるものを overlay しない。
    # ただしこれは root のドメインでの dlopen なので、ABI とシンボルしか確かめていない。
    # 「vendor プロセスから読めるか」(linker の permitted.paths と SELinux) は service.sh が
    # /proc/<pid>/maps で確かめる。別々の検査であることを取り違えないこと。
    if ! "$MODDIR/dlopen_check" "$WORK/soundfx/libcaeq.so" >>"$CA_LOG" 2>&1; then
        ca_die ".so の dlopen セルフテストに失敗。overlay しない (ABI/シンボルの検査。SELinux は別)"
    fi

    # --- 6. XML に行を足す ---------------------------------------------------
    # <postprocess> に載せると framework が自動でエフェクトを挿すので、
    # ユーザの再生に即座に掛かる。止めたいときにモジュールごと外さずに済むよう、
    # 空ファイル 1 つで切れるようにしておく (touch $MODDIR/no_postprocess で無効)。
    CA_PP=1
    [ -f "$MODDIR/no_postprocess" ] && CA_PP=0
    ca_log "postprocess への登録: $CA_PP"
    if ! ca_patch_xml "$CA_SRC_XML" "$WORK/etc/audio_effects.xml" "$CA_PP" 2>>"$CA_LOG"; then
        ca_die "XML の patch に失敗"
    fi
    chmod 644 "$WORK/etc/audio_effects.xml"
    chcon --reference="$CA_SRC_XML" "$WORK/etc/audio_effects.xml" || ca_die "XML のラベル継承に失敗"
    ca_log "XML patch 完了 ($(wc -c < "$CA_SRC_XML") -> $(wc -c < "$WORK/etc/audio_effects.xml") バイト)"

    # --- 7. 統計ファイルを用意する -------------------------------------------
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

    # --- 8. sepolicy ---------------------------------------------------------
    # 静的な sepolicy.rule と二重化する。判定は $KSU -> $APATCH -> Magisk の順。
    # KernelSU は互換のため MAGISK_VER / MAGISK_VER_CODE を名乗り、magiskpolicy も同梱するので、
    # Magisk を先に見ると必ず誤判定する。環境変数だけでなくコマンドの有無も見て、
    # マネージャが名乗りだけ互換にしている場合に次の段へ落ちるようにする。
    if   [ "${KSU:-}" = "true" ] && command -v ksud >/dev/null 2>&1; then
        ca_log "root マネージャ: KernelSU (${KSU_VER:-?})"
        ksud sepolicy apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
    elif [ "${APATCH:-}" = "true" ] && command -v apd >/dev/null 2>&1; then
        ca_log "root マネージャ: APatch (${APATCH_VER:-?})"
        apd sepolicy --live --apply "$MODDIR/sepolicy.rule" >>"$CA_LOG" 2>&1
    elif command -v magiskpolicy >/dev/null 2>&1; then
        ca_log "root マネージャ: Magisk (${MAGISK_VER:-?})"
        while read -r r; do [ -n "$r" ] && magiskpolicy --live "$r" >>"$CA_LOG" 2>&1; done < "$MODDIR/sepolicy.rule"
    else
        ca_log "警告: sepolicy の適用手段が見つからない"
    fi

    # OEM がエフェクト機構ごと殺すプロパティ。この実機には無いが、ある端末がある。
    # -n を外さないこと。post-fs-data は blocking な段で、property_service を経由する書き込みは
    # 起動をデッドロックさせる (KernelSU / Magisk 両方のドキュメントが
    # "Using setprop will deadlock the boot process! Please use resetprop -n" と明記)。
    # -n は property_service を通さず直接書くので、その経路に入らない。
    resetprop -n ro.audio.ignore_effects false 2>>"$CA_LOG" || ca_log "警告: resetprop に失敗"

    # --- 9. bind mount -------------------------------------------------------
    # /vendor の overlay はこの実機では効いていないので、init の名前空間へ直接入れるのが唯一の道。
    ca_mount "$WORK/soundfx" "$CA_LIBDIR"  || ca_die "soundfx の bind mount に失敗"
    ca_mount "$WORK/etc/audio_effects.xml" "$CA_SRC_XML" || ca_die "XML の bind mount に失敗"

    # --- 10. init の視点から読み直して検証 -----------------------------------
    $CA_NS test -f "$CA_LIBDIR/libcaeq.so" || ca_die "init の名前空間に .so が見えない"
    n=$($CA_NS grep -c 'name="ca_eq"' "$CA_SRC_XML")
    [ "$n" = "2" ] || ca_die "init の名前空間で XML の 2 行が見えない (n=$n)"

    # --- 11. service.sh へ渡す -----------------------------------------------
    # service.sh がパスを決め打ちすると、audio_effects.xml が /odm にある端末で
    # 自己検証が必ず落ちて自分を無効化する。解決済みの値をここから渡す。
    # このファイルの存在が「この段が最後まで通った」印でもある。
    {
        echo "CA_STAGE=$CA_STAGE"
        echo "CA_SRC_XML=$CA_SRC_XML"
        echo "CA_LIBDIR=$CA_LIBDIR"
    } > "$WORK/paths" || ca_die "paths を書けない"

    # --- 12. late-load なら audioserver を作り直させる -----------------------
    # 起動後に走るこの段では、audioserver も audio HAL も元の XML と元の soundfx を
    # 読み終えている。bind mount しただけでは何も起きず、service.sh の自己検証も落ちて
    # 自分を無効化してしまう。audioserver.rc に critical / oneshot / disabled が無いので
    # init が即座に作り直し、onrestart で vendor.audio-hal* も一緒に立ち上がる。
    # 引き換えに再生中の音は切れ、他のエフェクト (Dolby DAP など) も作り直しになる。
    if [ "$CA_STAGE" = "late-load" ]; then
        pid=$(pidof audioserver)
        if [ -n "$pid" ]; then
            ca_log "late-load モードなので audioserver を作り直す (音が一瞬切れる)。旧 pid $pid"
            # pidof は複数返しうるので引用しない。
            kill $pid 2>>"$CA_LOG"
            # 先に寝てから見る。kill は非同期なので、直後の pidof はまだ死にかけの
            # 旧 pid を返す。pid が変わったことを戻ってきた証拠にする。
            i=0
            newpid=""
            while [ $i -lt 10 ]; do
                sleep 1
                newpid=$(pidof audioserver)
                if [ -n "$newpid" ] && [ "$newpid" != "$pid" ]; then break; fi
                i=$((i+1))
            done
            if [ -n "$newpid" ] && [ "$newpid" != "$pid" ]; then
                ca_log "late-load モードなので audioserver を作り直した (新 pid $newpid)"
            else
                ca_log "警告: audioserver を落としたが戻ってこない"
            fi
        else
            ca_log "警告: late-load モードだが audioserver が居ない。反映は次の再起動から"
        fi
    fi

    ca_log "$CA_STAGE 完了"
}
