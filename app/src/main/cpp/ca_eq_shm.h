#ifndef CA_EQ_SHM_H
#define CA_EQ_SHM_H

#include <stdint.h>
#include <stddef.h>

/* 目標曲線のグリッド (点数・両端の周波数) の定義はここには無い。
 * **出どころは dsp/ca_eq_curve.h ただ 1 箇所**で、枠の並びはそこから算術で決まる。
 * 同じ点数を 2 箇所に持つと、片方だけ直したときに読み手が別の場所を読む。
 *
 * この include のせいで**このヘッダは C++ 専用**になっている。C から読む必要が出たら、
 * グリッドの定数を C でも読める形に移すのが先で、ここに数を書き写すのではない。 */
#include "dsp/ca_eq_curve.h"

#define CA_SHM_PATH   "/data/vendor/audio/ca_eq_stats.bin"
#define CA_SHM_MAGIC  0x51454143u   /* 'CAEQ' (little endian で 43 41 45 51) */

/* 版 2 でパラメータの領域が付いた。**ファイルが版 1 の大きさのままだと、後ろを触った
 * 瞬間に SIGBUS で vendor の audio HAL ごと落ちる。**読む側は必ず大きさを確かめること。
 *
 * 版 3 で ca_slot_t の末尾の pad が session_id になった。**大きさは変わっていない**ので
 * SIGBUS にはならないが、版 2 の .so は pad をゼロのまま残すので、読む側には
 * 「DEVICE の枠が 1 つも無い」= 「イヤホンが繋がっていない」に見える。**嘘の理由が出る形で
 * 壊れる**ので、書き手はこの版を必ず突き合わせること (caeqset は版が違えば専用の終了コードで落ちる)。
 *
 * 版 4 で「高精度」(最小位相 FIR) の目標曲線が枠に載り、統計に FIR の診断が付いた。
 * **枠も統計も大きさが変わった** (128→256 / 576→2176) ので、版 3 との食い違いは
 * 次の 2 通りになる。どちらも**端末が無音になったり落ちたりはしない**が、放置すると
 * 原因の分からない「効かない」になるので、読み書きする側は必ず版を突き合わせること:
 *
 *   - **版 4 の .so / caeqset / caeqstat が版 3 の大きさ (5760 B) のファイルを見たとき**
 *     … `fstat` の大きさ検査で弾いて map しない。統計もパラメータも出ないだけで済む。
 *     **この検査を外すと、後ろを触った瞬間に SIGBUS で HAL ごと落ちる。**
 *   - **版 3 の .so が版 4 のファイル (19584 B) を見たとき** … 大きさは足りるので map は
 *     成功する。統計の枠は 128 B 刻みで書くので版 4 の枠 0〜3 の領域を塗り潰し、
 *     パラメータは 1152 B から 576 B 刻みで読むので**統計の領域をパラメータとして読む。**
 *     そこは版 4 の `slots[4]` の先頭で、`seq` に読むのが `in_use`、`generation` に読むのが
 *     `seq` になる。**結果として何も起きない**:
 *       - `slots[4]` が使用中なら `in_use = CA_SHM_MAGIC` で**奇数**なので、seqlock が
 *         「書き込み中」と見て `paramsRead` が 3 回とも失敗する → **`rejected` も増えない**
 *       - 未使用なら `generation` に 0 を読んで早期 return → やはり何も起きない
 *     素通しのまま、**どのカウンタも動かない。`version` が 3 のままなのが唯一の手掛かり。**
 *
 *     ⚠️ **ここは紙の上の推論で、版 3 のバイナリでも実機でも未確認**
 *     (signature の実機確認は段 4 の項目)。**「rejected が増える」と書いていた版があり、
 *     それだと現地で探すものを間違える** — 増えないカウンタを待つことになる。
 */
#define CA_SHM_VERSION 4u
#define CA_SHM_SLOTS  8

/* audio_session_t の AUDIO_SESSION_DEVICE。**<deviceEffects> 経由で作られたインスタンス
 * (= イヤホンに挿さっている側) にだけ渡る値。**<postprocess> 経由のインスタンスは別の値になる。 */
#define CA_AUDIO_SESSION_DEVICE  (-2)

/* このヘッダは .so とリーダの両方が読む。片方だけレイアウトが変わると、リーダが別の場所を
 * 読んで気づかないまま嘘の数字を出す。サイズはコンパイル時に固定する。 */
#define CA_STATIC_ASSERT(cond, msg) static_assert(cond, msg)

/* 1 エフェクトインスタンス = 1 スロット。256 バイト固定。
 * 書き手は .so (vendor の audio HAL プロセス)、読み手は root の caeqstat。
 * seq は seqlock: 書く前に奇数、書き終えたら偶数。読み手は前後で同じ偶数を見たら採用する。
 *
 * **先頭 128 B の並びは版 3 と 1 バイトも変えていない。**版 3 の .so が版 4 のファイルへ
 * 書いても枠 0 の統計だけは正しい位置に載るので、版ずれの診断が読める側に倒れる。 */
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
     *
     * **<postprocess> にも登録した端末では、別の値の枠が同時に立つ。**そのとき
     * 「使用中の枠が 2 つある = イヤホンが 2 台」と読むと誤判定する。
     * **登録するかどうかを決めるのは `module/common/setup.sh` の `ca_want_pp`。**
     *
     * ⚠️ **ここに既定値を書かないこと。**以前は「同時に居るのが**通常の構成**」と
     * 書いてあった — `<postprocess>` の既定が 1 だった頃の記述で、既定をオフに
     * 変えたときに直し損ねたもの。**その 1 文が、非 DEVICE インスタンスの無駄な確保を
     * 「常時起きる恒久的な純損失」と誤判定させ、実装の優先順位の判断まで動かした**
     * (2026-08-13)。コードもテストも正しく動いていたので、どの計器にも掛からなかった。
     * **既定は変わる。値を写さず、決めている場所を指すこと。** */
    int32_t  session_id;
    /* --- ここまでが版 3 の 128 B (並びを動かさないこと) ------------------- */

    /* 「高精度」(最小位相 FIR) の診断。**インスタンスごとに出す。**
     * 「入っている / 有効 / 設定済み / でも音が通っていない」の切り分けは、この 1 行で
     * 完結していないと現地で追えない。EqPipeline のカウンタをそのまま写す。 */
    uint32_t fir_state;       /* CA_FIR_STATE_*。EqPipeline::FirState と同じ番号 */
    uint32_t fir_flags;       /* CA_FIR_F_* */
    uint32_t fir_fill;        /* FDL の充填。partitions に届くまで FIR は鳴らない */
    uint32_t fir_partitions;  /* K = ceil(taps / block) */
    uint32_t fir_rebuilds;         /* 設計器を起こした回数 */
    uint32_t fir_face_fades;       /* FIR→FIR の差し替えが鳴り切った回数 */
    /* **鳴っていた FIR が端末側の都合で止まり biquad へ乗り換わった回数。**
     * ユーザ操作 (mode_offs) も、音の経路が変わらない事象も混ぜない。 */
    uint32_t fir_fallbacks;
    uint32_t fir_mode_offs;        /* ユーザが高精度を切った回数 */
    uint32_t fir_design_failures;  /* 設計器が非有限で止まった回数 */
    uint32_t fir_unfit_size;       /* ブロック長が大きさで不適 (5-smooth でない等) */
    uint32_t fir_unfit_budget;     /* ブロック長が予算で不適 */
    /* 曲線の検査に落ちた回数。⚠️ **本番では常に 0。**枠の更新を丸ごと採るか丸ごと捨てるかを
     * 決めるために、読み手 (dsp/ca_eq_poll.h) が曲線の検査を先に通すので、ここまで来ない。
     * **曲線が原因の棄却は param_rejected に出る。**この欄が 0 でも曲線の棄却は除外できない。
     * カウンタが指しているのは poll を通らない呼び手 (ハーネス) 用の最後の砦のほう。 */
    uint32_t fir_curve_rejected;
    uint32_t fir_curve_gen;        /* **いま鳴っている**曲線の世代。0 = FIR 未稼働 */
    uint32_t fir_taps;
    uint32_t fir_m;                /* ケプストラムの FFT 長 */
    uint32_t fir_arena_kb;         /* 実際に確保した作業領域。0 = arena なし */
    uint64_t fir_max_slice_ns;     /* 1 スライスの実測最大。予算に収まっているかを見る */
    uint32_t fir_scrubbed;         /* 非有限を潰したサンプル数 */
    /* uint32/int32 が 35 本 (140 B) + uint64 が 7 本 (56 B) + float が 2 本 (8 B) = 204 B。
     * **残り 52 B が下の pad。**フィールドを足すなら pad をその分だけ減らす —
     * 減らし忘れると sizeof が 256 を超えて、下の CA_STATIC_ASSERT が止める。
     * 52 B を使い切ったときは CA_SHM_BYTES と module/common/setup.sh の bs= を
     * 両方直すことになる (module/test/module_test.sh が一致を見張っている)。 */
    uint8_t  pad[52];
} ca_slot_t;

#define CA_PARAM_SLOT_NONE  0xFFFFFFFFu
#define CA_SLOT_BYTES  256

/* 「未割り当て」の印が、本物の添字と衝突しないこと。**衝突すると素通しのつもりの
 * インスタンスが枠を 1 つ読み始める** — 2 台目のイヤホンに 1 台目の設定が掛かる形。
 * 枠を増やすときに気づけるようコンパイル時に縛る。 */
CA_STATIC_ASSERT(CA_SHM_SLOTS > 0 && CA_PARAM_SLOT_NONE > (unsigned)CA_SHM_SLOTS,
                 "CA_PARAM_SLOT_NONE が本物の枠の添字と衝突している");

CA_STATIC_ASSERT(sizeof(ca_slot_t) == CA_SLOT_BYTES,
                 "ca_slot_t は 256 バイト固定 (pad を足し引きして合わせる)");
CA_STATIC_ASSERT(offsetof(ca_slot_t, fir_state) == 128,
                 "版 3 の 128 B の並びが動いている");

/* fir_state。**EqPipeline::FirState と同じ番号**にしてある (写すときに表を引かない)。 */
#define CA_FIR_STATE_BIQUAD   0u
#define CA_FIR_STATE_PREPARE  1u
#define CA_FIR_STATE_FADE_IN  2u
#define CA_FIR_STATE_FIR      3u
#define CA_FIR_STATE_FADE_OUT 4u

/* fir_flags。「要求されているのに始まらない」の理由を切り分けるためのビット。 */
#define CA_FIR_F_REQUESTED  0x1u  /* 共有メモリの flags で高精度が指示されている */
#define CA_FIR_F_ARENA      0x2u  /* 作業領域を確保できている */
#define CA_FIR_F_BLOCK_OK   0x4u  /* いまのブロック長で FIR を回せる */
/* このインスタンスに設定が宛てられうる (= FIR の宿主になれる)。判定は
 * caeq::sessionCanBeAddressed。**退路経路 (<postprocess> / session 0) のインスタンスは
 * ここが落ちる**ので、そこで高精度が効かないことの理由がこのビットで読める。 */
#define CA_FIR_F_ADDRESSABLE 0x8u
/* **いま採用している曲線で設計器が失敗している** (新しい曲線が来れば解除される現在値)。
 * ⚠️ 理由づけに fir_design_failures (累積) を使わないこと — 減らないので、一度失敗すると
 * 新しい曲線を温めている最中も永久に「設計器が止まった」と出続ける。 */
#define CA_FIR_F_CURVE_FAILED 0x10u

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

#define CA_EQ_PARAM_SLOT_BYTES  2176

/* 1 エフェクトインスタンス = 1 枠。統計のスロットと同じ添字で対応する。
 * generation == 0 は「まだ誰にも宛てられていない」。**この枠を読んでいるエフェクトは
 * パラメータを一切適用せず素通しする** — 「たぶん自分宛て」で読むと、2 台目の
 * イヤホンに 1 台目の設定が掛かる。
 *
 * **bands と曲線は同じ枠・同じ seqlock に置いてある。**別の領域に分けると
 * 「bands は新しいのに曲線は古い」状態が作れてしまい、鳴っている音がどちらの設定なのか
 * 分からなくなる。1 枚のスナップショットで届くので、読み手は丸ごと採るか丸ごと捨てるかだけ。 */
typedef struct ca_eq_slot_s {
    uint32_t seq;                   /* seqlock。奇数 = 書き込み中 */
    /* **枠の版。書き手は書くたびに必ず進める。**読み手はここが動いていなければ
     * 枠を写しもしない (定常では 32 bit の読み 1 回で終わる)。0 = 未割り当て。 */
    uint32_t generation;
    uint32_t flags;                 /* CA_EQ_FLAG_* */
    uint32_t band_count;            /* 0..CA_EQ_MAX_BANDS */
    float    preamp_db;             /* 解いた後の合成応答のピークから計算済み */
    uint32_t writer_pid;            /* 診断用。書き手が誰か */
    ca_eq_band_t band[CA_EQ_MAX_BANDS];
    /* --- ここまでが版 3 の 520 B ------------------------------------------ */

    /* **曲線の版。曲線の中身が変わったときだけ進める。**generation とは別に持つ理由は、
     * プリアンプやバンドのドラッグ (60 Hz) で FIR の再構築を走らせないため
     * (eq-fir-design.md §2 — プリアンプはスカラのままで IR に焼かない)。
     * **0 = この枠に曲線は載っていない。**書き手が版 4 を知らない / まだ送っていない
     * 場合がこれで、読み手は「高精度が要求されていても曲線待ちで biquad」と判別できる。 */
    uint32_t curve_gen;
    /* 目標曲線。20 Hz〜20 kHz の対数等間隔 kCurvePoints 点の dB。
     * **IR ではなく曲線を運ぶ**理由は eq-fir-design.md §1 (アプリは fs を知らない)。
     * 検査は「各点が有限かつ |dB| <= 40」で、1 点でも外れたら枠の更新ごと捨てる。 */
    float    curve_db[caeq::kCurvePoints];
    /* 先の版のための余白。**長さは固定の数で書くこと。**
     *
     * ⚠️ ここを「宣言した大きさ − 中身」の式で書くと、**kCurvePoints や
     * CA_EQ_MAX_BANDS を動かしても pad が黙って伸び縮みして sizeof が変わらず、
     * 下の CA_STATIC_ASSERT が 1 本も発火しない。**
     * **実際に式で書いていて、検分で見つかった** (2026-08-13)。格子の点数は特に危険で、
     * どのオフセットも動かさないので `offsetof` の釘も素通りする。
     * 格子と band 数は Kotlin の送り手・モジュールが作るファイルの大きさまで繋がる
     * 取り決めなので、動かしたことに誰も気づかないのが一番高くつく。
     * **フィールドを足すときは、この数を手で減らす。**減らし忘れれば sizeof が
     * 2176 を超えて下の釘が止める。 */
    uint8_t  pad[48];
} ca_eq_slot_t;

CA_STATIC_ASSERT(sizeof(ca_eq_slot_t) == CA_EQ_PARAM_SLOT_BYTES,
                 "ca_eq_slot_t は 2176 バイト固定");
CA_STATIC_ASSERT(offsetof(ca_eq_slot_t, curve_gen) == 520,
                 "版 3 の 520 B の並びが動いている");
/* 曲線が枠の中に収まっていること。**pad の式と独立に効かせる** —
 * 式を間違えて pad をゼロ長にしたときでも、こちらが先に止まる。 */
CA_STATIC_ASSERT(offsetof(ca_eq_slot_t, curve_db) +
                     sizeof(float) * static_cast<size_t>(caeq::kCurvePoints) <=
                 sizeof(ca_eq_slot_t),
                 "曲線が枠からはみ出している");

#define CA_EQ_FLAG_ENABLED         0x1u
/* 処理方式の 2 段目「高精度」(最小位相 FIR)。立っていなければ「標準」(biquad)。
 * **曲線が載っていない (curve_gen == 0) 枠でこれが立っていても、読み手は biquad のまま。**
 * 保存形式に増えるのはこの enum だけで、曲線は毎回この共有メモリで運ぶ。 */
#define CA_EQ_FLAG_HIGH_PRECISION  0x2u

/* EFFECT_CMD_SET_PARAM の id。**更新には使わない** (スライダーのドラッグは 60 Hz、
 * SET_PARAM の往復はステレオ 10 バンドの総入れ替えで 30〜80 ms かかる)。
 * 経路の確立と、共有メモリを使わない単体の実証だけ。 */
#define CA_PARAM_ID_GAIN  1   /* 実証用。プリアンプを millibel で */
#define CA_PARAM_ID_SLOT  2   /* 「お前が読むのは params[N] だ」。生成の直後に 1 回 */

typedef struct ca_shm_s {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;        /* sizeof(ca_slot_t) */
    /* 版 4 で足した 2 つ。**版だけでは「同じ番号で並びが違う」開発中のビルドを捕まえられない。**
     * 読み手は自分の期待値と突き合わせて、違えば読まずに断ること。 */
    uint32_t param_slot_size;  /* sizeof(ca_eq_slot_t) */
    uint32_t curve_points;     /* caeq::kCurvePoints */
    uint8_t  pad[104];
    ca_slot_t slots[CA_SHM_SLOTS];
    ca_eq_slot_t params[CA_SHM_SLOTS];
} ca_shm_t;

CA_STATIC_ASSERT(offsetof(ca_shm_t, slots) == 128, "header は 128 バイト固定");
CA_STATIC_ASSERT(sizeof(ca_shm_t) ==
                     128 + CA_SLOT_BYTES * CA_SHM_SLOTS +
                         CA_EQ_PARAM_SLOT_BYTES * CA_SHM_SLOTS,
                 "ca_shm_t の並びが変わっている");

/* module/common/setup.sh がこの大きさでファイルを作る。**片方だけ変えると、
 * .so がマップの外を触って SIGBUS で HAL ごと落ちる。**必ず同時に直すこと。
 * module/test/module_test.sh が両者の一致を見張っている。 */
#define CA_SHM_BYTES  19584
CA_STATIC_ASSERT(sizeof(ca_shm_t) == CA_SHM_BYTES, "CA_SHM_BYTES と実際の大きさが違う");

/* state のビット */
#define CA_STATE_ENABLED           0x1u
#define CA_STATE_CONFIGURED        0x2u
#define CA_STATE_PASSTHROUGH_ONLY  0x4u

#endif
