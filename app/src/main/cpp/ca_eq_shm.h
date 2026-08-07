#ifndef CA_EQ_SHM_H
#define CA_EQ_SHM_H

#include <stdint.h>

#define CA_SHM_PATH   "/data/vendor/audio/ca_eq_stats.bin"
#define CA_SHM_MAGIC  0x51454143u   /* 'CAEQ' (little endian で 43 41 45 51) */
/* 版 2 でパラメータの領域が付いた。**ファイルが版 1 の大きさのままだと、後ろを触った
 * 瞬間に SIGBUS で vendor の audio HAL ごと落ちる。**読む側は必ず大きさを確かめること。
 *
 * 版 3 で ca_slot_t の末尾の pad が session_id になった。**大きさは変わっていない**ので
 * SIGBUS にはならないが、版 2 の .so は pad をゼロのまま残すので、読む側には
 * 「DEVICE の枠が 1 つも無い」= 「イヤホンが繋がっていない」に見える。**嘘の理由が出る形で
 * 壊れる**ので、書き手はこの版を必ず突き合わせること (caeqset は版が違えば専用の終了コードで落ちる)。 */
#define CA_SHM_VERSION 3u
#define CA_SHM_SLOTS  8

/* audio_session_t の AUDIO_SESSION_DEVICE。**<deviceEffects> 経由で作られたインスタンス
 * (= イヤホンに挿さっている側) にだけ渡る値。**<postprocess> 経由のインスタンスは別の値になる。 */
#define CA_AUDIO_SESSION_DEVICE  (-2)

/* このヘッダは .so とリーダの両方が読む。片方だけレイアウトが変わると、リーダが別の場所を
 * 読んで気づかないまま嘘の数字を出す。サイズはコンパイル時に固定する。 */
#ifdef __cplusplus
#define CA_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#else
#define CA_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#endif

/* 1 エフェクトインスタンス = 1 スロット。128 バイト固定。
 * 書き手は .so (vendor の audio HAL プロセス)、読み手は root の caeqstat。
 * seq は seqlock: 書く前に奇数、書き終えたら偶数。読み手は前後で同じ偶数を見たら採用する。 */
typedef struct ca_slot_s {
    uint32_t in_use;        /* 0 = 空き、CA_SHM_MAGIC = 使用中 */
    uint32_t seq;
    uint64_t frames;        /* 累積の処理フレーム数 */
    uint32_t sample_rate;
    uint32_t channels;
    uint32_t block_frames;  /* 直近の process() のフレーム数。1024=primary / 2048=deep_buffer */
    uint32_t state;         /* bit0 enabled / bit1 configured / bit2 passthrough_only */
    int32_t  gain_mb;
    int32_t  io_id;         /* create_effect に渡された ioId */
    uint64_t pid;           /* 書き手のプロセス */
    uint64_t ctx;           /* ctx ポインタ。インスタンスの一意な識別子として使う */
    uint64_t last_ns;       /* 直近に process() した CLOCK_MONOTONIC */
    /* 直近の process() の絶対値の最大。in が 0 なら無音が来ているだけで、
     * 経路に入っていないのとは別。out/in の比がゲインと一致すれば加工が効いている。 */
    float    in_peak;
    float    out_peak;
    /* バッファの正体を決めるための計測。float 解釈と int32 解釈を並べ、
     * さらに in-place かどうかとループ回数も出す。3 つの仮説
     * (int32 を float と誤読 / 本当に無音 / ピークの計算違い) を 1 回で分ける。 */
    uint64_t in_addr;         /* process() に来た in->raw。out_addr と同じなら in-place */
    uint64_t out_addr;
    uint32_t dbg_in_frames;   /* in->frameCount */
    uint32_t dbg_out_frames;  /* out->frameCount */
    uint32_t dbg_samples;     /* 実際にループした回数 */
    uint32_t dbg_fmt;         /* SET_CONFIG で受け取った outputCfg.format */
    uint32_t in_peak_i32;     /* 同じバッファを int32 として読んだ絶対値の最大 */
    uint32_t out_peak_i32;
    /* パラメータ経路の診断。**捨てたことが見えないと「効かない」の原因が追えない。**
     * param_slot は読みに行っている params[] の添字 (0xFFFFFFFF = 未割り当て = 素通し)。 */
    uint32_t param_slot;
    uint32_t param_gen;       /* 最後に適用した generation。0 = まだ何も適用していない */
    uint32_t param_rejected;  /* 検査に落ちて丸ごと捨てた回数 */
    /* create_effect に渡された sessionId。**書き手が「イヤホン側の枠」を選ぶ唯一の手掛かり。**
     * CA_AUDIO_SESSION_DEVICE なら <deviceEffects> 経由 = A2DP のスレッド。
     * <postprocess> 経由のインスタンスは別の値で**同時に居るのが通常の構成**なので、
     * 「使用中の枠が 2 つある = イヤホンが 2 台」と読むと必ず誤判定する。 */
    int32_t  session_id;
    /* uint32/int32 が 18 本 (72 B) + uint64 が 6 本 (48 B) + float が 2 本 (8 B) = 128 B。
     * **ちょうど埋まっていて pad は無い。**次にフィールドを足すときは 128 バイトに収まらないので、
     * CA_SHM_BYTES と module/common/setup.sh の bs= を両方直すことになる。 */
} ca_slot_t;

#define CA_PARAM_SLOT_NONE  0xFFFFFFFFu

CA_STATIC_ASSERT(sizeof(ca_slot_t) == 128, "ca_slot_t は 128 バイト固定");

/* ---------------------------------------------------------------------------
 * パラメータの領域。**向きが上の統計と逆で、書き手が外 (設定を持っている側)、
 * 読み手が .so。**同じファイルの別の領域に置いてあるだけで、seqlock は独立している。
 *
 * 係数ではなくパラメータ (fc / Q / gain) を運ぶ。サンプルレートを知っているのは
 * オーディオスレッド側だけで、不安定な係数を渡されると発散して爆音になる。
 * 仕様は llmdocs/eq-spec.md §3。**フィールドの追加は末尾のみ。**
 * ------------------------------------------------------------------------- */

#define CA_EQ_MAX_BANDS 31

enum {                              /* RBJ Audio EQ Cookbook。dsp/ca_eq_dsp.h の BandType と同値 */
    CA_EQ_BAND_PEAKING    = 0,
    CA_EQ_BAND_LOW_SHELF  = 1,
    CA_EQ_BAND_HIGH_SHELF = 2
};

typedef struct ca_eq_band_s {       /* 16 B */
    float    fc_hz;                 /* 中心周波数 / コーナー周波数 */
    float    q;
    float    gain_db;               /* 干渉補正を解いた後の値。ユーザの目標値ではない */
    uint32_t type;                  /* CA_EQ_BAND_* */
} ca_eq_band_t;

CA_STATIC_ASSERT(sizeof(ca_eq_band_t) == 16, "ca_eq_band_t は 16 バイト固定");

/* 1 エフェクトインスタンス = 1 枠。統計のスロットと同じ添字で対応する。
 * generation == 0 は「まだ誰にも宛てられていない」。**この枠を読んでいるエフェクトは
 * パラメータを一切適用せず素通しする** — 「たぶん自分宛て」で読むと、2 台目の
 * イヤホンに 1 台目の設定が掛かる。 */
typedef struct ca_eq_slot_s {
    uint32_t seq;                   /* seqlock。奇数 = 書き込み中 */
    uint32_t generation;            /* 設定の世代。0 = 未割り当て */
    uint32_t flags;                 /* bit0: enabled */
    uint32_t band_count;            /* 0..CA_EQ_MAX_BANDS */
    float    preamp_db;             /* 解いた後の合成応答のピークから計算済み */
    uint32_t writer_pid;            /* 診断用。書き手が誰か */
    ca_eq_band_t band[CA_EQ_MAX_BANDS];
    uint8_t  pad[576 - 24 - 16 * CA_EQ_MAX_BANDS];
} ca_eq_slot_t;

CA_STATIC_ASSERT(sizeof(ca_eq_slot_t) == 576, "ca_eq_slot_t は 576 バイト固定");

#define CA_EQ_FLAG_ENABLED  0x1u

/* EFFECT_CMD_SET_PARAM の id。**更新には使わない** (スライダーのドラッグは 60 Hz、
 * SET_PARAM の往復はステレオ 10 バンドの総入れ替えで 30〜80 ms かかる)。
 * 経路の確立と、共有メモリを使わない単体の実証だけ。 */
#define CA_PARAM_ID_GAIN  1   /* 実証用。プリアンプを millibel で */
#define CA_PARAM_ID_SLOT  2   /* 「お前が読むのは params[N] だ」。生成の直後に 1 回 */

typedef struct ca_shm_s {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;
    uint8_t  pad[112];
    ca_slot_t slots[CA_SHM_SLOTS];
    ca_eq_slot_t params[CA_SHM_SLOTS];
} ca_shm_t;

CA_STATIC_ASSERT(sizeof(ca_shm_t) == 128 + 128 * CA_SHM_SLOTS + 576 * CA_SHM_SLOTS,
                 "ca_shm_t の並びが変わっている");

/* module/common/setup.sh がこの大きさでファイルを作る。**片方だけ変えると、
 * .so がマップの外を触って SIGBUS で HAL ごと落ちる。**必ず同時に直すこと。
 * module/test/module_test.sh が両者の一致を見張っている。 */
#define CA_SHM_BYTES  5760
CA_STATIC_ASSERT(sizeof(ca_shm_t) == CA_SHM_BYTES, "CA_SHM_BYTES と実際の大きさが違う");

/* state のビット */
#define CA_STATE_ENABLED           0x1u
#define CA_STATE_CONFIGURED        0x2u
#define CA_STATE_PASSTHROUGH_ONLY  0x4u

#endif
