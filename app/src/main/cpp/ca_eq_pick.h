// 「いま設定を書くべき枠」を共有メモリの統計から選ぶ。**Android に依存しない** —
// プロセスの生存確認だけを外から差し込む形にして、ホストのハーネスが
// 実機でしか出ない並び (死んだ残骸・DEVICE でないインスタンス・2 台同時) を静的に固定できるようにする。
//
// ⚠️ **枠と MAC の対応は「1 台のときだけ確実」。**`.so` は自分がどのイヤホンの枠か知らない
// (`deviceId` は SW の device effect では常に 0、`EFFECT_CMD_SET_DEVICE` は MAC を運ばない)。
// ここで分かるのは「イヤホン側のインスタンスかどうか」までで、**どのイヤホンかは分からない。**
// だから DEVICE の枠が 2 つ以上あったら選ばずに断る — 推測で 1 つ選ぶと、
// 2 台繋いだ人が「たまに別のイヤホンの設定になる」を踏む。
#ifndef CA_EQ_PICK_H_
#define CA_EQ_PICK_H_

#include <stdint.h>

#include "ca_eq_shm.h"

namespace caeq {

/**
 * 共有メモリそのものが読める状態か。
 *
 * ⚠️ **`version` だけを見て「版ずれ」と言わないこと。**`post-fs-data` は `dd if=/dev/zero` で
 * ファイルを作るので、起きた直後は `magic` も `version` も 0。版を刻むのは `ca_stats_open()` で、
 * **`create_effect()` からしか呼ばれない** — つまり **`.so` が入っていても、エフェクトの
 * インスタンスが 1 つも立つまで `version` は 0 のまま**で、これは毎回の起動で必ず通る正常な状態。
 * ここで「アプリとモジュールの版が合っていない」と出すと、真実が
 * 「イヤホンが繋がっていない」のときに嘘の理由を出すことになる。
 *
 * **`magic` が版の有効性を決める** (同じ関数で一緒に書かれ、`magic` が最後に立つ)。
 */
enum class ShmState {
    kOk = 0,
    /** `magic` が 0。**まだどの `.so` も attach していない。正常な状態。** */
    kNotInitialised,
    /** `magic` が別の値。この置き場にあるのは我々のファイルではない。 */
    kForeign,
    /** `magic` は正しいが版が違う。**本物の版ずれ。** */
    kVersionMismatch,
    /**
     * 版は合っているのに枠の大きさか曲線の点数が違う。
     *
     * **版を上げ忘れて並びだけ変えたビルド**がこれ。番号が同じなので `kVersionMismatch`
     * では捕まらず、そのまま読むと枠の境界がずれて別のインスタンスの設定を読む。
     * 開発中にしか起きないが、起きたときに黙って化けるのが一番高くつく形なので分けてある。
     */
    kLayoutMismatch,
};

inline ShmState shmState(const ca_shm_t* m) {
    if (m == nullptr) return ShmState::kForeign;
    if (m->magic == 0u) return ShmState::kNotInitialised;
    if (m->magic != CA_SHM_MAGIC) return ShmState::kForeign;
    if (m->version != CA_SHM_VERSION) return ShmState::kVersionMismatch;
    // **版の次に並びを見る。**順序が肝で、版が違うときは並びが違って当たり前なので、
    // 先に並びを見ると「版ずれ」という本当の理由が「並びが違う」に化ける。
    if (m->slot_size != static_cast<uint32_t>(sizeof(ca_slot_t)) ||
        m->param_slot_size != static_cast<uint32_t>(sizeof(ca_eq_slot_t)) ||
        m->curve_points != static_cast<uint32_t>(kCurvePoints)) {
        return ShmState::kLayoutMismatch;
    }
    return ShmState::kOk;
}

/**
 * このインスタンスに設定が宛てられうるか。
 *
 * ⚠️ **書き手の枠選び (`pickDeviceSlot`) と、読み手が FIR の作業領域を確保するかの判断が、
 * この 1 つの述語を見る。**別々に書くと、宛てられるのに作業領域を持たない
 * (= 高精度が黙って効かない) インスタンスか、その逆ができる。
 *
 * いまの答えは「`<deviceEffects>` 経由だけ」。`.so` の descriptor は
 * DEVICE / `<postprocess>` / session 0 の 3 経路すべてで動くように作ってあり
 * (`eq-spec.md` §3)、**DEVICE が通らない端末で退路へ落ちる可能性は設計に織り込まれている。**
 * そのとき書き手が退路の枠も選べるようにするなら、**ここを 1 箇所変えれば両側が追随する。**
 *
 * ⚠️ **これが false のインスタンスでも、`EFFECT_CMD_SET_PARAM` (`CA_PARAM_ID_SLOT`) で
 * 枠を宛てられることがある** — ハンドルを持つ保持者からの経路 (`hold-process.md` の退路段)。
 * `.so` はそこで作業領域を引き上げるので、この述語だけで固定しないこと。
 */
inline bool sessionCanBeAddressed(int32_t session_id) {
    return session_id == CA_AUDIO_SESSION_DEVICE;
}

/**
 * 「高精度 (最小位相 FIR) を頼んだのに biquad のまま」の理由。
 *
 * ⚠️ **理由の判定をリーダの表示コードに書かないこと。**この製品で繰り返し出ている
 * 失敗が「嘘の理由が出る」形なので、判定はここ 1 箇所に置いてハーネスで表ごと固定する。
 * **順序に意味がある** — 上のものほど根本的で、下は上が満たされて初めて意味を持つ。
 */
enum class FirWhy {
    kRunning = 0,     /* FIR が鳴っている。理由は要らない */
    kNotRequested,    /* 標準モード。**異常ではない** */
    /** このインスタンスには設定が宛てられない (退路経路)。**作業領域を持たないのは設計どおり**で、
     *  確保の失敗ではない。**kNoArena より先に言う** — 「作業領域が無い」と出すと、
     *  読んだ人が確保の失敗を疑って別の場所を探しに行く。 */
    kNotAddressable,
    kNoArena,         /* 作業領域が無い = 3ch 以上のインスタンスか確保に失敗 */
    kBlockUnfit,      /* このスレッドのブロック長では回せない */
    kNoCurve,         /* 曲線がまだ届いていない */
    kDesignFailed,    /* 設計器が止まった */
    kWarming,         /* FDL を温めている最中 */
    kAlmost,          /* 条件は揃っている。次のブロックから乗り移る */
};

/**
 * 統計の枠 (+ 対応するパラメータ枠。無ければ nullptr) から理由を決める。
 * `curve_gen` はパラメータ枠のもの (`.so` が鳴らしている世代ではない)。
 *
 * ⚠️ **見てよいのは「いまの状態」を表す値だけ。累積カウンタを述語に使わないこと。**
 * ここには `fir_design_failures > 0` (累積) を使った版があり、**一度失敗したら
 * 永久にラッチして、その後は新しい曲線を温めている最中もずっと「設計器が止まった」と
 * 嘘を言っていた** (検分が製品経路で実測。9 ブロックのあいだ、まさにユーザが
 * 「なぜまだ効かないのか」と見る窓で嘘が出る)。
 * **表のテストではこれを見られない** — テストは値を代入して次の行へ進むが、
 * その遷移 (累積カウンタが 0 に戻る) は製品では起こせないため。
 */
inline FirWhy firWhy(const ca_slot_t& s, const ca_eq_slot_t* q) {
    if (s.fir_state == CA_FIR_STATE_FIR) return FirWhy::kRunning;
    if ((s.fir_flags & CA_FIR_F_REQUESTED) == 0u) return FirWhy::kNotRequested;
    if ((s.fir_flags & CA_FIR_F_ADDRESSABLE) == 0u) return FirWhy::kNotAddressable;
    if ((s.fir_flags & CA_FIR_F_ARENA) == 0u) return FirWhy::kNoArena;
    if ((s.fir_flags & CA_FIR_F_BLOCK_OK) == 0u) return FirWhy::kBlockUnfit;
    if (q != nullptr && q->curve_gen == 0u) return FirWhy::kNoCurve;
    if ((s.fir_flags & CA_FIR_F_CURVE_FAILED) != 0u) return FirWhy::kDesignFailed;
    if (s.fir_fill < s.fir_partitions) return FirWhy::kWarming;
    return FirWhy::kAlmost;
}

enum class SlotPick {
    kOk = 0,      /* 生きた DEVICE の枠がちょうど 1 つ */
    kNone,        /* 1 つも無い。**失敗ではなく「イヤホンが繋がっていない」正常な状態** */
    kAmbiguous,   /* 2 つ以上。イヤホンが 2 台繋がっている */
};

struct SlotPickResult {
    SlotPick status;
    uint32_t param_slot;    /* 書き込み先。kOk のときだけ意味がある */
    uint32_t stats_slot;    /* 上の枠を読んでいるインスタンスの統計の添字。検査に使う fs の出どころ */
    uint32_t live_count;    /* 生きた DEVICE の枠の数 */
    uint32_t stale_count;   /* pid が死んでいる残骸。**.so はプロセスの死で枠を掃除しない** */
    /* 生きているが DEVICE でない枠。**`<postprocess>` にも登録した端末でだけ立つ** —
     * 登録するかどうかを決めるのは `module/common/setup.sh` の `ca_want_pp`。
     * ⚠️ **ここに既定値を書かないこと。**以前は「通常の構成で必ず居る」と書いてあり、
     * 0 を異常と読ませる (逆に本物の異常を見逃させる) 形になっていた (2026-08-13)。 */
    uint32_t other_count;
};

/* プロセスが生きているかを答える。実機では /proc/<pid> の有無、ハーネスでは表引き。 */
typedef bool (*PidAliveFn)(uint64_t pid, void* user);

/**
 * 枠を 1 つ選ぶ。
 *
 * **seqlock を通さずに素で読んでよい理由**は「値が動かない」であって「書かれる回数」ではない。
 * `in_use` / `pid` / `session_id` は attach のときだけ書かれる。**`param_slot` は
 * `ca_stats_add` が毎ブロック書き直しているが、書く値は同じ** (変わるのは SET_PARAM を
 * 受けたときだけ) で、自然境界の 32 bit なので、書いたことのない値を読むことはない。
 *
 * その SET_PARAM (`CA_PARAM_ID_SLOT`) は**エフェクトのハンドルを持つ者しか送れない。**
 * 静的な `<deviceEffects>` では誰も持たないので、**いまは誰も送らず `param_slot` は動かない**
 * (「パラメータは共有メモリ一択」の理由そのもの)。⚠️ **`hold-process.md` のフォールバック段
 * (BT プロセスのフック / root の `app_process` 常駐) はハンドルを持つので、そこで枠を
 * 振り直す実装を足すならここを見直すこと** — 値の入れ替わりは千切れないが、古い添字を読んで
 * 別の枠へ書く隙は残る。
 *
 * ⚠️ **ここに見るフィールドを足すときは、この条件を満たすかを確かめること。**
 * 毎ブロック値が変わるもの (frames / peak / last_ns) を足すなら seqlock が要る。
 *
 * ⚠️ **`in_use` が立っている = 生きている、と読まないこと。**`.so` はプロセスの死で枠を
 * 掃除しないので、audio HAL が落ちて再起動すると前の枠が使用中のまま残る (実測で確認済み)。
 *
 * ⚠️ **`session_id` を見ないと、イヤホンが 1 台でも複数の枠が生きて見える。**
 * `<postprocess>` にも登録した端末では、スピーカー / spatializer のスレッドにも同じ
 * エフェクトが挿さる。そちらへ書くと**イヤホンの設定がスピーカーに掛かる。**
 * **登録するかどうかを決めるのは `module/common/setup.sh` の `ca_want_pp`。**
 *
 * ⚠️ **ここに既定値を書かないこと。**以前は「既定で**有効** (`CA_PP=1`)」と書いてあり、
 * `ca_eq_shm.h` の同じ型の記述と合わせて実装の優先順位の判断を動かした (2026-08-13)。
 * **既定は変わる。値を写さず、決めている場所を指すこと。**
 */
inline SlotPickResult pickDeviceSlot(const ca_shm_t* m, PidAliveFn alive, void* user) {
    SlotPickResult r{SlotPick::kNone, 0u, 0u, 0u, 0u, 0u};
    if (m == nullptr || alive == nullptr) return r;

    uint32_t count = m->slot_count;
    if (count > static_cast<uint32_t>(CA_SHM_SLOTS)) count = static_cast<uint32_t>(CA_SHM_SLOTS);

    for (uint32_t i = 0; i < count; i++) {
        const ca_slot_t& s = m->slots[i];
        if (s.in_use != CA_SHM_MAGIC) continue;
        // pid が 0 の枠は attach の途中。次に呼べば埋まっているので、いまは無いものとして扱う。
        if (s.pid == 0) continue;
        if (!alive(s.pid, user)) { r.stale_count++; continue; }
        // **読み手が作業領域を確保するかの判断と同じ述語を見る** (sessionCanBeAddressed)。
        if (!sessionCanBeAddressed(s.session_id)) { r.other_count++; continue; }
        // 読みに行く枠が決まっていない (CA_PARAM_SLOT_NONE) インスタンスは、書いても素通しのまま。
        if (s.param_slot >= static_cast<uint32_t>(CA_SHM_SLOTS)) continue;
        r.live_count++;
        if (r.live_count == 1) {
            r.param_slot = s.param_slot;
            r.stats_slot = i;
        }
    }

    if (r.live_count == 1) {
        r.status = SlotPick::kOk;
    } else if (r.live_count > 1) {
        r.status = SlotPick::kAmbiguous;
    }
    return r;
}

}  // namespace caeq

#endif  // CA_EQ_PICK_H_
