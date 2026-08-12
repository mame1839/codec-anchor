// 統計ファイルを読んで人間が読める形で出す。実体は実行ファイル。
#include <fcntl.h>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "ca_eq_pick.h"
#include "ca_eq_shm.h"
#include "dsp/ca_eq_stats.h"

namespace {

uint64_t now_monotonic_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull + static_cast<uint64_t>(ts.tv_nsec);
}

// 振幅を dBFS で。無音 (0.0) は -inf なので別扱いにする。
void format_dbfs(char* buf, size_t n, float peak) {
    if (peak <= 0.0f) { snprintf(buf, n, "silent"); return; }
    snprintf(buf, n, "%.1f", 20.0 * log10(static_cast<double>(peak)));
}

// 最後に process() が回ってからの経過。進んでいるのか凍っているのかは、これで 1 回で分かる。
void format_age(char* buf, size_t n, uint64_t last_ns, uint64_t now_ns) {
    if (last_ns == 0) { snprintf(buf, n, "never"); return; }
    if (last_ns > now_ns) { snprintf(buf, n, "?"); return; }
    snprintf(buf, n, "%.2fs", static_cast<double>(now_ns - last_ns) / 1e9);
}

const char* fir_state_name(uint32_t s) {
    switch (s) {
    case CA_FIR_STATE_BIQUAD:   return "biquad";
    case CA_FIR_STATE_PREPARE:  return "準備中";
    case CA_FIR_STATE_FADE_IN:  return "乗り移り中 (biquad→FIR)";
    case CA_FIR_STATE_FIR:      return "FIR";
    case CA_FIR_STATE_FADE_OUT: return "降り中 (FIR→biquad)";
    default:                    return "?";
    }
}

// **「高精度を頼んだのに biquad のまま」の理由を 1 行で言い切る。**
// ここが曖昧だと、現地で「入っていない」「効いていない」「そもそも要求していない」の
// どれなのかが分からず、モジュールの入れ直しから始めることになる。
// **判定は caeq::firWhy (ca_eq_pick.h) にあり、ハーネスが表ごと固定している。**
// ここは文言だけ。
const char* fir_why_text(caeq::FirWhy w) {
    switch (w) {
    case caeq::FirWhy::kRunning:      return "";
    case caeq::FirWhy::kNotRequested: return "高精度が要求されていない (標準モード)";
    case caeq::FirWhy::kNotAddressable:
        return "このインスタンスには設定を宛てられない (DEVICE 経由ではない = 退路経路)。"
               "作業領域を持たないのは設計どおりで、確保の失敗ではない";
    case caeq::FirWhy::kNoArena:
        return "作業領域が無い — このインスタンスは 3ch 以上 (spatializer 等) か確保に失敗";
    case caeq::FirWhy::kBlockUnfit:
        return "このスレッドのブロック長では回せない (不適の内訳は size/budget を見る)";
    case caeq::FirWhy::kNoCurve:      return "曲線がまだ届いていない (書き手が送っていない)";
    case caeq::FirWhy::kDesignFailed: return "設計器が止まった (曲線が非有限になった)";
    case caeq::FirWhy::kWarming:      return "FDL を温めている最中 (fill/K を見る)";
    case caeq::FirWhy::kAlmost:       return "準備は整っているが、まだ乗り移っていない";
    }
    return "?";
}

}  // namespace

int main(int argc, char** argv) {
    const char* path = (argc > 1) ? argv[1] : CA_SHM_PATH;
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { fprintf(stderr, "open %s failed\n", path); return 2; }

    // 短いファイルを mmap して読むと、ページの終端を越えた時点で SIGBUS になる。
    // 「カウンタが出ない」を調べている最中に理由の分からない死に方をされると困るので先に弾く。
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < sizeof(ca_shm_t)) {
        fprintf(stderr, "%s is too small: %lld bytes (%zu required)\n",
                path, static_cast<long long>(st.st_size), sizeof(ca_shm_t));
        close(fd);
        return 2;
    }

    void* p = mmap(nullptr, sizeof(ca_shm_t), PROT_READ, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { fprintf(stderr, "mmap failed\n"); return 2; }
    const ca_shm_t* m = static_cast<const ca_shm_t*>(p);
    if (m->magic != CA_SHM_MAGIC) {
        // ここの文言を「.so が読み込まれていない」に戻さないこと。
        // この領域を初期化する ca_stats_open() は create_effect() からしか呼ばれないので、
        // HAL が .so を dlopen 済みでも、エフェクトが 1 度も生成されていなければ magic は 0 のまま。
        // 2 つを混同すると、正しい観測から「刺さっていない」という誤った結論が出る。
        printf("magic=0x%08x — まだエフェクトのインスタンスが 1 つも作られていない\n", m->magic);
        printf("\n");
        printf("  これは「.so が読み込まれていない」という意味ではない。\n");
        printf("  この領域を初期化するのは ca_stats_open() で、create_effect() からしか呼ばれない。\n");
        printf("  .so が読み込まれたかどうかは、HAL が map しているかで別に確かめる:\n");
        printf("    grep libcaeq /proc/$(pidof android.hardware.audio.service.mediatek)/maps\n");
        return 1;
    }
    printf("version=%u (期待 %u) slots=%u slot_size=%u (期待 %zu) param_slot_size=%u "
           "(期待 %zu) curve_points=%u (期待 %d)\n",
           m->version, CA_SHM_VERSION, m->slot_count, m->slot_size, sizeof(ca_slot_t),
           m->param_slot_size, sizeof(ca_eq_slot_t), m->curve_points, caeq::kCurvePoints);
    if (m->version != CA_SHM_VERSION) {
        // 版 3 の .so は 128 B 刻みで統計を書き、1152 B から 576 B 刻みでパラメータを読む。
        // 版 4 のファイルではどちらも枠の境界がずれるので、下の表は意味を持たない。
        printf("\n⚠️ 版が食い違っている。以下の表は枠の境界がずれているので読まないこと。\n");
        printf("   モジュールとアプリのどちらかが古い。入れ直すこと。\n");
        return 1;
    }
    if (m->slot_size != sizeof(ca_slot_t) || m->param_slot_size != sizeof(ca_eq_slot_t) ||
        m->curve_points != static_cast<uint32_t>(caeq::kCurvePoints)) {
        printf("\n⚠️ 版は同じなのに並びが違う。**版を上げずに構造体を変えたビルドが混ざっている。**\n");
        printf("   以下の表は枠の境界がずれているので読まないこと。\n");
        return 1;
    }
    printf("%-4s %-18s %-6s %-10s %-4s %-7s %-6s %-9s %-6s %-7s %-8s %-8s %s\n",
           "slot", "ctx", "io", "frames", "ch", "rate", "block", "age", "pid", "gain_mB",
           "in_dBFS", "out_dBFS", "state");

    const uint64_t now_ns = now_monotonic_ns();
    int active = 0;
    for (uint32_t i = 0; i < m->slot_count && i < static_cast<uint32_t>(CA_SHM_SLOTS); i++) {
        ca_slot_t s;
        const bool stable = caeq::statsRead(&m->slots[i], &s);
        if (s.in_use != CA_SHM_MAGIC) continue;
        active++;
        char age[16], ind[16], outd[16];
        format_age(age, sizeof(age), s.last_ns, now_ns);
        format_dbfs(ind, sizeof(ind), s.in_peak);
        format_dbfs(outd, sizeof(outd), s.out_peak);
        printf("%-4u 0x%-16llx %-6d %-10llu %-4u %-7u %-6u %-9s %-6llu %-7d %-8s %-8s %s%s%s%s\n",
               i, (unsigned long long) s.ctx, s.io_id,
               (unsigned long long) s.frames, s.channels, s.sample_rate,
               s.block_frames, age, (unsigned long long) s.pid, s.gain_mb, ind, outd,
               (s.state & CA_STATE_ENABLED) ? "enabled " : "",
               (s.state & CA_STATE_CONFIGURED) ? "configured " : "",
               (s.state & CA_STATE_PASSTHROUGH_ONLY) ? "PASSTHROUGH_ONLY " : "",
               stable ? "" : "(読み取り中に更新された)");
        // バッファの正体を決めるための行。float 解釈と int32 解釈を並べて出す。
        printf("     buf: in=0x%llx out=0x%llx %s  frames in/out=%u/%u samples=%u cfg_fmt=%u\n",
               (unsigned long long) s.in_addr, (unsigned long long) s.out_addr,
               (s.in_addr == s.out_addr) ? "(in-place)" : "(out-of-place)",
               s.dbg_in_frames, s.dbg_out_frames, s.dbg_samples, s.dbg_fmt);
        printf("     peak: float in=%.9g out=%.9g / int32 in=%u out=%u\n",
               static_cast<double>(s.in_peak), static_cast<double>(s.out_peak),
               s.in_peak_i32, s.out_peak_i32);
        // **どの枠がイヤホン側かは session_id でしか分からない。**<postprocess> にも登録すると
        // スピーカー / spatializer のスレッドにも同じエフェクトが挿さり、枠が同時に複数立つ。
        printf("     session=%d %s\n", s.session_id,
               s.session_id == CA_AUDIO_SESSION_DEVICE
                   ? "(DEVICE = <deviceEffects> 経由 = イヤホン側)"
                   : "(DEVICE でない = postprocess 等。イヤホンの設定を書く先ではない)");
        // パラメータ経路。**捨てたことが見えないと「効かない」の原因が追えない。**
        const ca_eq_slot_t* q = nullptr;
        if (s.param_slot == CA_PARAM_SLOT_NONE) {
            printf("     param: 枠が未割り当て — パラメータを一切適用せず素通し\n");
        } else if (s.param_slot >= static_cast<uint32_t>(CA_SHM_SLOTS)) {
            printf("     param: 枠=%u は範囲外 (0..%d)。**壊れた値**\n",
                   s.param_slot, CA_SHM_SLOTS - 1);
        } else {
            q = &m->params[s.param_slot];
            printf("     param: 枠=%u 適用済み gen=%u / 共有メモリ gen=%u bands=%u "
                   "preamp=%.2f dB flags=0x%x 却下=%u\n",
                   s.param_slot, s.param_gen, q->generation, q->band_count,
                   static_cast<double>(q->preamp_db), q->flags, s.param_rejected);
            if (q->generation == 0) {
                printf("            (書き手がまだ一度も書いていない = 誰にも宛てられていない)\n");
            } else if (s.param_gen != q->generation) {
                printf("            (共有メモリの世代に追いついていない。"
                       "却下が増えているなら検査に落ちている)\n");
            }
        }
        // 「高精度」(最小位相 FIR)。**要求と実際が別々に出ることが要点** —
        // 「設定は高精度なのに biquad で鳴っている」を、理由まで含めてここで読む。
        printf("     fir  : %s / 要求=%s 作業領域=%s ブロック=%s  FDL %u/%u  曲線 gen=%u/%u\n",
               fir_state_name(s.fir_state),
               (s.fir_flags & CA_FIR_F_REQUESTED) ? "高精度" : "標準",
               (s.fir_flags & CA_FIR_F_ARENA) ? "あり" : "なし",
               (s.fir_flags & CA_FIR_F_BLOCK_OK) ? "可" : "不可",
               s.fir_fill, s.fir_partitions, s.fir_curve_gen,
               q != nullptr ? q->curve_gen : 0u);
        printf("            taps=%u M=%u arena=%u KB スライス最大=%.1f µs "
               "再構築=%u 差し替え=%u\n",
               s.fir_taps, s.fir_m, s.fir_arena_kb,
               static_cast<double>(s.fir_max_slice_ns) / 1000.0,
               s.fir_rebuilds, s.fir_face_fades);
        printf("            落下=%u モード切=%u 設計失敗=%u 不適(大きさ)=%u 不適(予算)=%u "
               "曲線却下=%u 潰し=%u\n",
               s.fir_fallbacks, s.fir_mode_offs, s.fir_design_failures, s.fir_unfit_size,
               s.fir_unfit_budget, s.fir_curve_rejected, s.fir_scrubbed);
        const caeq::FirWhy why = caeq::firWhy(s, q);
        if (why != caeq::FirWhy::kRunning) printf("            → %s\n", fir_why_text(why));
    }
    if (active == 0) {
        // magic が立っている = ca_stats_open() が走った = create_effect() が最低 1 回はあった。
        // ここでも「.so が読み込まれていない」とは書かない。
        printf("(使用中のスロットなし — エフェクトが作られたことはあるが、いま生きているものは無い)\n");
    }
    printf("\n");
    printf("age = 最後に process() が回ってからの経過。読み方:\n");
    printf("  never          このインスタンスで process() が 1 度も呼ばれていない。\n");
    printf("                 生成も有効化もされたが、音声がここを通っていない\n");
    printf("  伸び続ける     過去には回っていたが、いまは止まっている。\n");
    printf("                 (再生していない / 経路から外れた / pid のプロセスが死んだ残骸)\n");
    printf("  更新され続ける 音声が実際に通っている\n");
    printf("\n");
    printf("frames は 2 回実行して差を見ること。age だけでは「いま回っているか」しか分からない。\n");
    printf("in_dBFS が silent なら無音が来ているだけで、経路に入っていないのとは別。\n");
    printf("out_dBFS - in_dBFS が gain_mB/100 と一致していれば、加工が実際に効いている。\n");
    printf("\n");
    printf("peak の読み方 (バッファの正体を決める):\n");
    printf("  float 側が 0〜1 の常識的な値      → バッファは本当に float\n");
    printf("  float 側が 1e-38 級で int32 側が\n");
    printf("  10^6〜10^9 の値                   → 実体は int32。float として誤読している\n");
    printf("  両方 0                            → 本当に無音が来ている\n");
    printf("samples が frames*channels と合わなければ、数え方のほうが間違っている。\n");
    return 0;
}
