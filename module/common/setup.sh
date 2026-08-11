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

# 作業領域。/dev は tmpfs で、どの名前空間から見ても同じ inode になる。
# 登録済みイヤホンの一覧は再起動をまたぐので /data 側に置く。
# eq_devices.sh と uninstall.sh はここを見る (service.sh だけは setup.sh を
# 読み込まないので自分で持っている)。
# 上書きできるのはホストでテストを走らせるため。実機では常に既定値。
CA_WORK=${CA_WORK:-/dev/caeq}
CA_DEVICES=${CA_DEVICES:-/data/adb/codecanchor_eq_devices}

# init の名前空間でコマンドを走らせるための接頭辞。使えなければ空 = 自分の名前空間。
# 展開時に単語分割させたいので $CA_NS は引用しない。
CA_NS=""

# <postprocess> にも登録するか。**既定はオフ。**
#
# <postprocess> の <stream type="music"> に載せると、エフェクトは stream type 単位で挿さる —
# つまり**スピーカーと spatializer の出力にも載る** (実測 io 69 / 12ch)。
# イヤホン用に測った補正曲線をスピーカーに当てるのは単に間違いで、ユーザから見ると
# 「スピーカーの音がおかしくなった」になる。
# これは DEVICE (<deviceEffects>) が通らない端末のための退路であって製品の経路ではない。
# DEVICE は実機で通っているので、既定を退路側に置く理由が無い。
#
# 退路が要る端末では $MODDIR/use_postprocess を置く。
# post-fs-data と eq_devices.sh で答えが違うと、作り直した XML が起動時のものとずれるので、
# 判断はこの 1 箇所でだけ行う。
ca_want_pp() {
    [ -f "$MODDIR/use_postprocess" ] && echo 1 || echo 0
}

# audioserver を作り直す。XML は起動時に 1 回しか読まれず再読込の API が無いので、
# 登録するイヤホンを増減したらこれしか手が無い。引き換えに再生中の音は切れ、
# 他のエフェクト (Dolby DAP など) も作り直しになる (audioserver.rc の
# onrestart restart vendor.audio-hal*)。audioserver.rc に critical / oneshot / disabled が
# 無いので init が即座に作り直す。
#   0 = 新しい pid で戻ってきた / 1 = 戻ってこない / 2 = そもそも居ない
#
# ⚠️ **pid が変わるまで戻らないこと。この待ちを外すとアプリ側の押さえが黙って効かなくなる。**
# アプリは apply が戻ってから「Bluetooth の出口が戻るまで」音を押さえる (QuietSwitch)。
# 出口の一覧は audioserver から引くので、**まだ作り直しが始まっていないうちに聞けば、
# 前の audioserver が「出口はある」と答える。**押さえはその 1 回で解け、直後に来る
# 本当の作り直しは何にも守られない。**症状は元のまま・テストは緑・ログも正常**という
# 形で外れるので、短くしたくなったらここを読むこと。
ca_restart_audioserver() {
    ca_as_old=$(pidof audioserver)
    if [ -z "$ca_as_old" ]; then
        ca_log "警告: audioserver が居ない"
        return 2
    fi
    ca_log "audioserver を作り直す (音が一瞬切れる)。旧 pid $ca_as_old"
    # pidof は複数返しうるので引用しない。
    kill $ca_as_old 2>>"$CA_LOG"
    # 先に寝てから見る。kill は非同期なので、直後の pidof はまだ死にかけの旧 pid を返す。
    # pid が変わったことを戻ってきた証拠にする。
    ca_as_i=0
    while [ $ca_as_i -lt 10 ]; do
        sleep 1
        ca_as_new=$(pidof audioserver)
        if [ -n "$ca_as_new" ] && [ "$ca_as_new" != "$ca_as_old" ]; then
            ca_log "audioserver を作り直した (新 pid $ca_as_new)"
            return 0
        fi
        ca_as_i=$((ca_as_i+1))
    done
    ca_log "警告: audioserver を落としたが戻ってこない"
    return 1
}

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

    WORK="$CA_WORK"

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
    # patch 前の原本を取っておく。ここはまだ bind mount する前なので $CA_SRC_XML は無傷。
    # eq_devices.sh は登録を変えるたびに毎回ここから作り直す —
    # patch 済みを patch し直す形にすると、消し漏れが積み上がる。
    # 名前を .orig にしないこと。ca_patch_xml が <出力>.orig を作業に使う。
    CA_PRISTINE_XML="$WORK/etc/audio_effects.xml.pristine"
    cp "$CA_SRC_XML" "$CA_PRISTINE_XML" || ca_die "patch 前の原本を取っておけない"

    # 登録済みのイヤホン。再起動をまたぐので /data 側にある。
    # 壊れていても起動は止めない — <deviceEffects> を書かないだけにする
    # (ここで ca_die すると、一覧の 1 行の壊れがモジュール全体の無効化になる)。
    CA_DEVLIST="$WORK/devices"
    : > "$CA_DEVLIST"
    if [ -f "$CA_DEVICES" ]; then
        if ca_canon_devices "$CA_DEVICES" "$CA_DEVLIST" 2>>"$CA_LOG"; then
            ca_log "登録済みのイヤホン: $(awk 'END { print NR }' "$CA_DEVLIST") 件"
        else
            : > "$CA_DEVLIST"
            ca_log "警告: $CA_DEVICES が壊れている。<deviceEffects> は書かない"
        fi
    fi

    CA_PP=$(ca_want_pp)
    ca_log "postprocess への登録: $CA_PP"
    if ! ca_patch_xml "$CA_PRISTINE_XML" "$WORK/etc/audio_effects.xml" "$CA_PP" "$CA_DEVLIST" 2>>"$CA_LOG"; then
        ca_die "XML の patch に失敗"
    fi
    chmod 644 "$WORK/etc/audio_effects.xml"
    chcon --reference="$CA_SRC_XML" "$WORK/etc/audio_effects.xml" || ca_die "XML のラベル継承に失敗"
    ca_log "XML patch 完了 ($(wc -c < "$CA_SRC_XML") -> $(wc -c < "$WORK/etc/audio_effects.xml") バイト)"

    # --- 7. 共有メモリを用意する ---------------------------------------------
    # 統計 (.so -> 外) とパラメータ (外 -> .so) の両方が入る。
    # **大きさは app/src/main/cpp/ca_eq_shm.h の CA_SHM_BYTES と一致させること。**
    # 足りないと .so がマップの外を触って SIGBUS で vendor の audio HAL ごと落ちる
    # (= 端末が無音になる)。.so 側でも大きさを確かめてから mmap している。
    SHM=/data/vendor/audio/ca_eq_stats.bin
    if [ -d /data/vendor/audio ]; then
        rm -f "$SHM"
        dd if=/dev/zero of="$SHM" bs=5760 count=1 2>/dev/null
        chmod 664 "$SHM"
        chown audioserver:audio "$SHM"
        chcon --reference=/data/vendor/audio "$SHM"
        ca_log "共有メモリを用意: $(ls -Z "$SHM")"
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
        echo "CA_PRISTINE_XML=$CA_PRISTINE_XML"
    } > "$WORK/paths" || ca_die "paths を書けない"

    # --- 12. late-load なら audioserver を作り直させる -----------------------
    # 起動後に走るこの段では、audioserver も audio HAL も元の XML と元の soundfx を
    # 読み終えている。bind mount しただけでは何も起きず、service.sh の自己検証も落ちて
    # 自分を無効化してしまう。
    # ⚠️ **この経路には音の押さえが無い。**アプリからの登録 (eq_devices.sh) は QuietSwitch が
    # 音声フォーカスを取ってから作り直すが、ここはモジュールを読み込む側から走るので、
    # フォーカスを取れるアプリのプロセスがそもそも居ない。**このとき音楽が鳴っていれば、
    # 数百 ms だけ本体スピーカーから出る。**
    # モジュールを入れた直後の 1 回だけなので、シェルから他人の再生を止めにいく
    # (media_session を叩く) 危険と釣り合わないと判断して、そのままにしてある。
    if [ "$CA_STAGE" = "late-load" ]; then
        ca_restart_audioserver || ca_log "late-load モードだが audioserver を作り直せない。反映は次の再起動から"
    fi

    ca_log "$CA_STAGE 完了"
}
