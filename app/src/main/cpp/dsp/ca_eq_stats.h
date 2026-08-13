// 統計の枠を読み書きする規約。**Android に依存しない** —
// ホストのハーネスが、書き手のスレッドを立てて千切れた読みが起きないことを直接確かめる。
//
// 向きはパラメータと逆で、**書き手が `.so` (オーディオスレッド)、読み手が外の
// `caeqstat`。**枠の中身を組み立てるのは dsp/ca_eq_poll.h の `firStatsOf`。
//
// **ここを ca_eq.cpp に直接書かないこと** (2026-08-13 まではそうなっていた)。
// あちらは mmap と android/log.h に依存するのでホストではビルドできず、
// パラメータ側には書き手スレッドを立てた試験があるのに、同じ共有メモリの
// もう一方向だけが**一度も回っていない**状態になっていた。
#ifndef CA_EQ_STATS_H_
#define CA_EQ_STATS_H_

#include <cstring>

#include "../ca_eq_shm.h"
#include "ca_eq_seq.h"

namespace caeq {

// --- 書き手 (`.so`。オーディオスレッドと制御スレッドの両方から) ---------------
//
// **読み手はスピンして待つので、書き込みは短く。**確保もログもここには置かない。

inline void statsBeginWrite(ca_slot_t* s) { seqBeginWrite(&s->seq); }

inline void statsEndWrite(ca_slot_t* s) { seqEndWrite(&s->seq); }

// --- 読み手 (`caeqstat`。別プロセス) -----------------------------------------

// seq が偶数で、写しの前後で変わっていなければ内容は一貫している。
// 書き手が奇数の区間に居るのは数十 ns なので、まず 1 回で通る。
//
// **パラメータ側 (paramsRead) と違ってここは待ってよい** — 呼び手は外のプロセスで、
// オーディオスレッドではない。逆に、あちらは 3 回で諦める (音を止めないため)。
//
// **諦めたときも dst は必ず埋める。**呼び手 (caeqstat) は「一貫していない」と印を
// 付けたうえで数字を出す — 出さないと、書き手が書き込みの途中で死んだ枠が
// 画面から消えるだけになり、いちばん見たい状態が見えなくなる。
inline bool statsRead(const ca_slot_t* src, ca_slot_t* dst, int attempts = 100) {
    const std::atomic<uint32_t>* seq = asAtomic(&src->seq);
    for (int i = 0; i < attempts; i++) {
        const uint32_t s1 = seq->load(std::memory_order_acquire);
        if (s1 & 1u) continue;                    // 書き込みの最中
        std::memcpy(dst, src, sizeof(*dst));
        std::atomic_thread_fence(std::memory_order_acquire);
        if (s1 == seq->load(std::memory_order_acquire)) return true;
    }
    std::memcpy(dst, src, sizeof(*dst));
    return false;
}

}  // namespace caeq

#endif  // CA_EQ_STATS_H_
