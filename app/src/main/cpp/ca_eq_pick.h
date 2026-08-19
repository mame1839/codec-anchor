// 「いま設定を書くべき枠」を共有メモリの統計から選ぶ。**Android に依存しない** —
// プロセスの生存確認だけを外から差し込む形にして、ホストのハーネスが
// 実機でしか出ない並び (死んだ残骸・DEVICE でないインスタンス・2 台同時) を静的に固定できるようにする。
//
// ⚠️ **枠と MAC の対応は「1 台のときだけ確実」。**`.so` は自分がどのイヤホンの枠か知らない
// (`deviceId` は SW の device effect では常に 0、`EFFECT_CMD_SET_DEVICE` は MAC を運ばない)。
// ここで分かるのは「イヤホン側のインスタンスかどうか」までで、**どのイヤホンかは分からない。**
// だから DEVICE の枠が 2 つ以上あったら選ばずに断る — 推測で 1 つ選ぶと、
// 2 台繋いだ人が「たまに別のイヤホンの設定になる」を踏む。
//
// ⚠️ **ここに `<unistd.h>` (や POSIX のヘッダ) を足さないこと。**このヘッダは
// ホストのハーネスからも読まれ、そのハーネスは MSVC でも組む (この環境で sanitizer を
// 持っているのは MSVC だけ)。POSIX を持ち込んだ瞬間にホストの全件がビルドできなくなる。
// **`/proc` を触る機構は `ca_eq_proc.h` にある** — 方針はここ、機構はあちら。
#ifndef CA_EQ_PICK_H_
#define CA_EQ_PICK_H_

#include <stdint.h>

#include <atomic>
#include <cerrno>
#include <cstring>

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
    /**
     * **まだ 1 ブロックも `process()` が回っていない。**「標準モード」より先に言う。
     *
     * `fir_flags` を書くのは `ca_stats_add` (= `process()` の中) だけなので、そこまで
     * 音が来ていない枠では**全欄が 0 のまま**で、以下の理由はどれも根拠が無い。
     * とくに `CA_FIR_F_REQUESTED` が 0 なので、**素で読むと「標準モード」に化ける** —
     * ユーザが高精度を選んだ直後にいちばん出やすい嘘。
     * 判定は `firWhyReported` が入口で行う (ここは表の値としての席)。
     */
    kNoAudio,
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
 * ⚠️ **読み手はこれを直接呼ばないこと。入口は `firWhyReported`。**
 * ここは「`fir_flags` が既に書かれている」ことを前提にした表で、それ自体は見ていない。
 * 直接呼ぶと、音がまだ来ていない枠 (全欄 0) で `kNotRequested` (= 標準モード) を返す。
 *
 * ⚠️ **見てよいのは「いまの状態」を表す値だけ。累積カウンタを述語に使わないこと。**
 * ここには `fir_design_failures > 0` (累積) を使った版があり、**一度失敗したら
 * 永久にラッチして、その後は新しい曲線を温めている最中もずっと「設計器が止まった」と
 * 嘘を言っていた** (検分が製品経路で実測。9 ブロックのあいだ、まさにユーザが
 * 「なぜまだ効かないのか」と見る窓で嘘が出る)。
 * **表のテストではこれを見られない** — テストは値を代入して次の行へ進むが、
 * その遷移 (累積カウンタが 0 に戻る) は製品では起こせないため。
 *
 * ⚠️ **この規則が掛かるのは「いまどうなっているか」を答える述語。**
 * 「**これまでに一度でも**起きたか」を答えるなら、累積カウンタが正しい (唯一の) 証拠になる —
 * 問いのほうもラッチするので、答えのラッチが嘘にならない。`firWhyReported` が
 * `frames` を見ているのがそれで、上の禁止には当たらない。
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

/**
 * **読み手の入口。**表を引く前に「`fir_flags` が一度でも書かれたか」を見る。
 *
 * `frames` と `fir_flags` は `ca_stats_add` の同じ seqlock の区間で書かれるので、
 * **`frames != 0` は「`fir_flags` が最低 1 回は書かれた」と同値。**
 * (`process()` は `frames == 0` のブロックでは何も書かずに戻るので、`frames` は
 * 進むときだけ進む。枠が持ち主を変えるときは 0 に戻る。)
 *
 * ⚠️ **「新鮮」だとは言っていない。**見ているのは「1 ブロックでも回ったか」だけで、
 * それが直近かどうかは見ない。**直近かどうかが要る呼び手は `last_ns` を自分で見ること**
 * (`caeqset` は `age_ms` として外へ出している)。
 * 報告に使う値のうち **`CA_FIR_F_BLOCK_OK` / `_ARENA` / `_ADDRESSABLE` は、そのスレッドと
 * インスタンスの性質**なので、再生が止まっていても古くならない。古くなるのは
 * `CA_FIR_F_REQUESTED` と `fir_state` の側。
 */
inline FirWhy firWhyReported(const ca_slot_t& s, const ca_eq_slot_t* q) {
    if (s.frames == 0u) return FirWhy::kNoAudio;
    return firWhy(s, q);
}

/**
 * 機械可読な短い名前。**アプリとの契約なので、綴りを変えないこと。**
 *
 * `caeqset` がこの綴りを 1 行で吐き、アプリはそれを見て文言を決める。
 * ⚠️ **`default:` を置かない。**`FirWhy` に値を足したとき、コンパイラに
 * 「ここも直せ」と言わせるため — `default:` を置くと黙って `"?"` が流れて、
 * アプリ側は未知の綴りを受け取ったことにすら気づけない。
 */
inline const char* firWhyToken(FirWhy w) {
    switch (w) {
    case FirWhy::kRunning:        return "running";
    case FirWhy::kNoAudio:        return "no_audio";
    case FirWhy::kNotRequested:   return "not_requested";
    case FirWhy::kNotAddressable: return "not_addressable";
    case FirWhy::kNoArena:        return "no_arena";
    case FirWhy::kBlockUnfit:     return "block_unfit";
    case FirWhy::kNoCurve:        return "no_curve";
    case FirWhy::kDesignFailed:   return "design_failed";
    case FirWhy::kWarming:        return "warming";
    case FirWhy::kAlmost:         return "almost";
    }
    return "";
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
    /* `in_use == CA_SHM_MAGIC` の枠の数。**上の 3 つの合計とは一致しない** —
     * `pid == 0` (attach の途中) と `param_slot` が未割り当ての枠はどれにも数えないので、
     * その分だけこちらが多い。
     *
     * **「枠が尽きた」と「イヤホンが繋がっていない」を分けるのはこの数。**
     * `live_count == 0` は両方で起きるが、`used_count == slot_count` なのは前者だけ。 */
    uint32_t used_count;
    /* 走査した枠の数 (= `m->slot_count` を CA_SHM_SLOTS で頭打ちにしたもの)。
     * `used_count` の相手方。**片方だけ見て「全部埋まっている」と言わないため**に返す。 */
    uint32_t slot_count;
};

/**
 * プロセスが生きているかを答える。実機では `/proc/<pid>` の有無、ハーネスでは表引き。
 *
 * ⚠️ **「分からない」は「生きている」に倒すこと。**実装は `ca_eq_proc.h`。
 * 判定が逆に倒れると、`reclaimDeadSlots` が**生きている枠を奪う**向きに反転する。
 */
typedef bool (*PidAliveFn)(uint64_t pid, void* user);

/**
 * `/proc/<pid>` を見に行った結果を「生きている / 死んでいる」に翻訳する規則。
 *
 * **`rc` は `access()` (や `stat()`) の戻り値、`err` はそのときの `errno`。**
 * 規則だけをここに置いてあるのは、**syscall を持ち込まずにハーネスで撃つため** —
 * `ca_eq_proc.h` は POSIX なのでホスト (MSVC) では 1 行も回らず、規則を向こうに
 * 書くと誰も検査できない。
 *
 * ⚠️ **戻り値だけで判定しないこと。**`access()` が 0 以外を返す理由は `ENOENT` だけ
 * ではない。拒否系の `errno` を「死んでいる」と読むと、判定が**生きている枠を奪う
 * 向きに反転する** — 回収は「死んだ枠を空きに戻す」操作なので、誤りの向きが
 * そのまま被害の向きになる。
 *
 * **`ENOENT` だけが「死んでいる」。それ以外は「分からない」で、生きている扱いに倒す。**
 * こうすれば見に行けなかったときの結末は「回収できない (= 回収を足す前と同じ)」で止まる。
 */
inline bool aliveFromAccess(int rc, int err) {
    if (rc == 0) return true;
    return err != ENOENT;
}

/* 走査する枠の数。ヘッダの申告を我々の並びで頭打ちにする。 */
inline uint32_t slotCountOf(const ca_shm_t* m) {
    const uint32_t n = m->slot_count;
    return n > static_cast<uint32_t>(CA_SHM_SLOTS) ? static_cast<uint32_t>(CA_SHM_SLOTS) : n;
}

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
    SlotPickResult r{SlotPick::kNone, 0u, 0u, 0u, 0u, 0u, 0u, 0u};
    if (m == nullptr || alive == nullptr) return r;

    const uint32_t count = slotCountOf(m);
    r.slot_count = count;

    for (uint32_t i = 0; i < count; i++) {
        const ca_slot_t& s = m->slots[i];
        if (s.in_use != CA_SHM_MAGIC) continue;
        r.used_count++;
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

// ---------------------------------------------------------------------------
// 統計の枠の取得と返却。
//
// **`.so` (`ca_eq.cpp`)・`caeqset`・ハーネスが同じ実体を通る。**ここに置いてあるのは
// `ca_eq.cpp` がホストのハーネスに 1 行も入らないため — あちらに書くと、枠の取り合いを
// 誰も撃てなくなる。**Android にも POSIX にも依存しない** (生存確認だけ外から差す)。
//
// 直した不具合: apply のたびに audioserver が作り直され、道連れで audio HAL も死ぬ。
// `release_effect` が呼ばれないので枠は `in_use` のまま残り、**8 回で枠が尽きて
// EQ が黙って完全な素通しになる** (再起動でしか戻らない)。
// ---------------------------------------------------------------------------

/** 枠が取れなかったことを表す添字。 */
constexpr uint32_t kNoSlot = 0xFFFFFFFFu;

/** 共有メモリの `in_use` を不可分に触る。**型を置き換えないこと** (理由は dsp/ca_eq_seq.h)。 */
inline std::atomic<uint32_t>* slotInUse(ca_slot_t* s) {
    return reinterpret_cast<std::atomic<uint32_t>*>(&s->in_use);
}

/**
 * 使用中の枠を 1 つ手放す。手順は `MAGIC → RECLAIM → 本体ゼロ → 0`。
 *
 * **`MAGIC → 0` を直接書かないこと。**中身をゼロにする前に `in_use` を落とすと、
 * 次に取った側が初期化を終えるまでの隙にリーダが前のインスタンスの `frames` を読み、
 * 「カウンタが進んでいる」という偽陽性になる — **処理フレーム数は「本当に音声経路に
 * 入ったか」を見る唯一の観測点**なので、そこに嘘が混じる経路を作らない。
 * 第 3 の値 (`CA_SHM_RECLAIM`) を挟めば「`in_use == 0` の枠は中身も必ずゼロ」という
 * 既存の不変条件が 1 文字も変わらない。
 *
 * CAS で入るので、**回収と持ち主の detach が同時に走っても本体を消すのは片方だけ。**
 * 負けた側は `false` を受けて何もしない (枠には触れていない)。
 */
inline bool releaseSlot(ca_slot_t* s) {
    if (s == nullptr) return false;
    std::atomic<uint32_t>* in_use = slotInUse(s);
    uint32_t expected = CA_SHM_MAGIC;
    if (!in_use->compare_exchange_strong(expected, CA_SHM_RECLAIM)) return false;
    std::memset(&s->seq, 0, sizeof(ca_slot_t) - offsetof(ca_slot_t, seq));
    std::atomic_thread_fence(std::memory_order_release);
    in_use->store(0u, std::memory_order_release);
    return true;
}

/**
 * 持ち主のプロセスが死んでいる枠を回収する。回収した数を返す。
 *
 * ⚠️ **`self_pid` の枠は回収しない。**同じプロセスの別インスタンスの枠を奪うと、
 * 鳴っている側の観測点が消える。`alive()` は自分の pid には必ず「生きている」と
 * 答えるはずだが、**そこに寄りかからず明示的に外す** — 生存確認は外から差す
 * 差し替え可能な部品なので、ここの安全性をあちらの正しさに預けない。
 *
 * ⚠️ **`params[i]` は消さない。統計の枠 (`slots[i]`) だけを空きに戻す。**
 * 枠が残ることが「アプリが走っていなくても EQ が生き延びる」仕組みそのもの
 * (統計は `.so` が書き、パラメータは外が書く、同じファイルの別の領域)。
 *
 * **実測 (2026-08-19、実機): 漏れた枠にユーザの曲線 (10 バンド + 高精度) が
 * そのまま残っていることを確認した。**漏れた直後の並びは「死んだ枠 0 に曲線が取り残され、
 * 生きている枠 1 は空 (`gen=0`)」。アプリが設定を押し直せば新しい枠へ書き直されるので、
 * **漏れは静かに溜まり、枯渇するまで手がかりが無い** (だから枯渇を正直に言う必要がある)。
 *
 * 回収して同じ添字を取り直せば、押し直しを待たずに `pollSlot` が残っている曲線を拾う。
 * **消していたら、回収のたびに曲線を自分で捨てることになる。**
 */
inline uint32_t reclaimDeadSlots(ca_shm_t* m, PidAliveFn alive, void* user, uint64_t self_pid) {
    if (m == nullptr || alive == nullptr) return 0u;
    const uint32_t count = slotCountOf(m);
    uint32_t n = 0u;
    for (uint32_t i = 0; i < count; i++) {
        ca_slot_t* s = &m->slots[i];
        if (slotInUse(s)->load(std::memory_order_acquire) != CA_SHM_MAGIC) continue;
        const uint64_t pid = s->pid;
        if (pid == 0u) continue;          // attach の途中。次に呼べば埋まっている
        if (pid == self_pid) continue;    // 自分の枠
        if (alive(pid, user)) continue;
        if (releaseSlot(s)) n++;
    }
    return n;
}

/**
 * 統計の枠 `taken` を取ったインスタンスが、読みに行くパラメータ枠をどう決めるか。
 *
 * **初回 (宛先が未割り当て) だけ「取った枠と同じ添字」を既定にし、
 * 既に有効な宛先を持っているなら保つ。**
 *
 * 保つ理由: 生成時に枠が尽きていたインスタンスは、`SET_PARAM` (id=2) で `params[v]` を
 * 宛てられて鳴っていることがある。あとから統計の枠が空いて取れたとき、宛先まで
 * 取った添字へ付け替えると、**ユーザの曲線は `params[v]` に残ったまま読み手だけが
 * 空の枠へ移り、EQ が黙って素通しに戻る** — 「生きている側が空の枠を読む」症状を
 * 修正自身が作る形。書き手は `pickDeviceSlot` が返す `s->param_slot` (= ここで決めた値)
 * へ書くので、保てば書き手と読み手は同じ枠で合流する。
 *
 * `CA_PARAM_SLOT_NONE` は範囲外なので「未割り当て」に落ちる (静的表明が縛っている)。
 */
inline uint32_t paramSlotAfterAttach(uint32_t current, uint32_t taken) {
    return current < static_cast<uint32_t>(CA_SHM_SLOTS) ? current : taken;
}

/**
 * 枠を 1 つ取る。返すのは添字か [kNoSlot]。**中身は呼び手が埋める。**
 *
 * 空きが無かったときだけ死んだ枠の回収を試し、もう一度だけ探す。
 *
 * ⚠️ **毎回は回収しない。**理由は費用ではなく**誤爆の面積** — 判定が万一間違っていても、
 * 「空きが 1 つも無い」= **既に EQ が死んでいる状態でしか動かない**ので失うものがほぼ無い。
 * (他社の端末で自己検証に失敗して自分を無効化した前科と同じ形の判断。)
 *
 * `alive == nullptr` を渡すと回収そのものを行わない。**syscall を 1 つも撃たない**
 * 経路が要る呼び手 (`EFFECT_CMD_ENABLE`) のためにある。
 */
inline uint32_t acquireSlot(ca_shm_t* m, PidAliveFn alive, void* user, uint64_t self_pid) {
    if (m == nullptr) return kNoSlot;
    const uint32_t count = slotCountOf(m);
    for (int pass = 0; pass < 2; pass++) {
        for (uint32_t i = 0; i < count; i++) {
            uint32_t expected = 0u;
            if (slotInUse(&m->slots[i])->compare_exchange_strong(expected, CA_SHM_MAGIC)) {
                return i;
            }
        }
        if (pass != 0) break;
        // 1 つも回収できなかったなら、もう一周しても結果は同じ。
        if (reclaimDeadSlots(m, alive, user, self_pid) == 0u) break;
    }
    return kNoSlot;
}

}  // namespace caeq

#endif  // CA_EQ_PICK_H_
