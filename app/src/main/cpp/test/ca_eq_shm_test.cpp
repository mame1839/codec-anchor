#include <cstring>
#include <limits>
#include <memory>
#include <string>
#include <thread>
#include <vector>

#include "ca_test_support.h"
#include "../ca_eq_curve_io.h"
#include "../ca_eq_pick.h"
#include "../dsp/ca_eq_poll.h"

namespace {

using catest::Report;

constexpr uint32_t kCanary = 0xA5C3F00Du;
constexpr int kCanaryWords = 32;

struct ShmWithCanary {
    uint32_t front[kCanaryWords];
    ca_shm_t m;
    uint32_t back[kCanaryWords];
};

ShmWithCanary* newCanaryShm() {
    auto* w = new ShmWithCanary{};
    for (int i = 0; i < kCanaryWords; i++) { w->front[i] = kCanary; w->back[i] = kCanary; }
    w->m.magic = CA_SHM_MAGIC;
    w->m.version = CA_SHM_VERSION;
    w->m.slot_count = CA_SHM_SLOTS;
    w->m.slot_size = static_cast<uint32_t>(sizeof(ca_slot_t));
    w->m.param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t));
    w->m.curve_points = static_cast<uint32_t>(caeq::kCurvePoints);
    return w;
}

bool canaryIntact(const ShmWithCanary& w) {
    for (int i = 0; i < kCanaryWords; i++) {
        if (w.front[i] != kCanary || w.back[i] != kCanary) return false;
    }
    return true;
}

float curveSample(uint32_t slot, int i) {
    return static_cast<float>(slot) * 0.5f + static_cast<float>(i % 61) * 0.125f - 3.0f;
}

uint8_t padSample(uint32_t slot, size_t i) {
    return static_cast<uint8_t>(0xC0u + ((slot * 7u + static_cast<uint32_t>(i)) & 0x3Fu));
}

void fillSlotPattern(ca_eq_slot_t* q, uint32_t slot) {
    q->generation = slot + 1u;
    q->curve_gen  = slot + 101u;
    q->flags      = CA_EQ_FLAG_ENABLED;
    q->band_count = 1;
    q->preamp_db  = -static_cast<float>(slot);
    q->band[0].fc_hz   = 1000.0f + static_cast<float>(slot);
    q->band[0].q       = 1.0f;
    q->band[0].gain_db = 0.0f;
    q->band[0].type    = CA_EQ_BAND_PEAKING;
    for (size_t i = 0; i < sizeof(q->pad); i++) q->pad[i] = padSample(slot, i);
    for (int i = 0; i < caeq::kCurvePoints; i++) q->curve_db[i] = curveSample(slot, i);
}

void fillAllSlots(ca_shm_t* m) {
    for (int i = CA_SHM_SLOTS - 1; i >= 0; i--) {
        fillSlotPattern(&m->params[i], static_cast<uint32_t>(i));
    }
}

bool slotPatternIntact(const ca_eq_slot_t& q, uint32_t slot) {
    if (q.seq != 0u) return false;
    if (q.generation != slot + 1u || q.curve_gen != slot + 101u) return false;
    if (q.flags != CA_EQ_FLAG_ENABLED || q.band_count != 1u) return false;
    if (q.band[0].fc_hz != 1000.0f + static_cast<float>(slot)) return false;
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        if (q.curve_db[i] != curveSample(slot, i)) return false;
    }
    for (size_t i = 0; i < sizeof(q.pad); i++) {
        if (q.pad[i] != padSample(slot, i)) return false;
    }
    return true;
}

std::vector<float> tiltCurve(float lo, float hi) {
    std::vector<float> c(static_cast<size_t>(caeq::kCurvePoints));
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        const float t = static_cast<float>(i) / static_cast<float>(caeq::kCurvePoints - 1);
        c[static_cast<size_t>(i)] = lo + (hi - lo) * t;
    }
    return c;
}

void writeSlot(ca_eq_slot_t* dst, uint32_t gen, uint32_t curve_gen, const float* curve,
               uint32_t flags, double preamp_db) {
    caeq::paramsBeginWrite(dst);
    dst->generation = gen;
    dst->curve_gen  = curve_gen;
    dst->flags      = flags;
    dst->band_count = 0;
    dst->preamp_db  = static_cast<float>(preamp_db);
    if (curve != nullptr) {
        for (int i = 0; i < caeq::kCurvePoints; i++) dst->curve_db[i] = curve[i];
    }
    caeq::paramsEndWrite(dst);
}

struct Driver {
    caeq::EqPipeline& pl;
    int block;
    int ch;
    std::vector<float> in, out;
    catest::Rng rng{7};

    Driver(caeq::EqPipeline& p, int b, int c)
        : pl(p), block(b), ch(c),
          in(static_cast<size_t>(b * c)), out(static_cast<size_t>(b * c)) {}

    void step() {
        for (size_t i = 0; i < in.size(); i++) in[i] = static_cast<float>(0.2 * rng.uniform());
        pl.process(in.data(), out.data(), block, false);
    }
};

uint64_t fakeClock() {
    static uint64_t t = 0;
    t += 1000;
    return t;
}

void putStatSlot(ca_shm_t* m, uint32_t i, uint64_t pid, int32_t session) {
    ca_slot_t& s = m->slots[i];
    s.in_use = CA_SHM_MAGIC;
    s.pid = pid;
    s.session_id = session;
    s.param_slot = i;
    s.sample_rate = 48000;
}

struct FakeAlive {
    static bool fn(uint64_t pid, void*) { return pid != 0; }
};

}

void runShmSections(Report& r) {
    r.section("30. 共有メモリ v4 — 並びと枠の境界");

    r.check(offsetof(ca_eq_slot_t, curve_db) +
                    sizeof(float) * static_cast<size_t>(caeq::kCurvePoints) <=
                sizeof(ca_eq_slot_t),
            "曲線 %d 点が枠に収まる (curve_db @%zu + %zu B <= 枠 %zu B)", caeq::kCurvePoints,
            offsetof(ca_eq_slot_t, curve_db),
            sizeof(float) * static_cast<size_t>(caeq::kCurvePoints), sizeof(ca_eq_slot_t));
    r.check(sizeof(ca_shm_t) == CA_SHM_BYTES &&
                sizeof(ca_shm_t) == 128 + sizeof(ca_slot_t) * CA_SHM_SLOTS +
                                        sizeof(ca_eq_slot_t) * CA_SHM_SLOTS,
            "全体 %zu B = header 128 + 統計 %zu×%d + 枠 %zu×%d、"
            "かつ setup.sh が作る CA_SHM_BYTES (%d) と一致",
            sizeof(ca_shm_t), sizeof(ca_slot_t), CA_SHM_SLOTS, sizeof(ca_eq_slot_t),
            CA_SHM_SLOTS, CA_SHM_BYTES);
    r.check(5760u < sizeof(ca_shm_t),
            "版 3 の大きさ (5760 B) では版 4 の構造体が入らない — 大きさ検査が必ず弾く");

    {
        ShmWithCanary* w = newCanaryShm();
        const char* base = reinterpret_cast<const char*>(&w->m);
        bool ok = true;
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            const size_t want_stat = 128u + sizeof(ca_slot_t) * i;
            const size_t want_par =
                128u + sizeof(ca_slot_t) * CA_SHM_SLOTS + sizeof(ca_eq_slot_t) * i;
            if (static_cast<size_t>(reinterpret_cast<const char*>(&w->m.slots[i]) - base) !=
                    want_stat ||
                static_cast<size_t>(reinterpret_cast<const char*>(&w->m.params[i]) - base) !=
                    want_par) {
                ok = false;
            }
        }
        r.check(ok, "統計 %d 枠とパラメータ %d 枠の位置が計算どおり (隙間も重なりも無い)",
                CA_SHM_SLOTS, CA_SHM_SLOTS);
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m);
        bool all = true;
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            if (!slotPatternIntact(w->m.params[i], i)) all = false;
        }
        r.check(all, "%d 枠すべてに枠ごとの模様 (曲線 + 末尾の pad) を書いて、"
                     "どれも隣にも pad にも食い込んでいない",
                CA_SHM_SLOTS);

        w->m.params[CA_SHM_SLOTS - 1].curve_db[caeq::kCurvePoints - 1] = 12.5f;
        r.check(canaryIntact(*w),
                "最後の枠の最後の点 (params[%d].curve_db[%d]) を書いても番兵が無傷",
                CA_SHM_SLOTS - 1, caeq::kCurvePoints - 1);

        const uint32_t keep = w->m.params[0].generation;
        ca_slot_t* last = &w->m.slots[CA_SHM_SLOTS - 1];
        std::memset(&last->seq, 0xEE, sizeof(ca_slot_t) - offsetof(ca_slot_t, seq));
        r.check(w->m.params[0].generation == keep && slotPatternIntact(w->m.params[0], 0),
                "統計の最後の枠を末尾まで塗ってもパラメータの先頭に届かない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::vector<float> c = tiltCurve(-9.75f, 7.25f);
        writeSlot(&w->m.params[3], 5u, 9u, c.data(), CA_EQ_FLAG_ENABLED, -1.5);

        ca_eq_slot_t snap{};
        const bool got = caeq::paramsRead(&w->m.params[3], &snap);
        bool same = got;
        for (int i = 0; i < caeq::kCurvePoints && same; i++) {
            if (snap.curve_db[i] != c[static_cast<size_t>(i)]) same = false;
        }
        r.check(same, "曲線 %d 点が往復して 1 点も化けない (先頭 %.3f 末尾 %.3f)",
                caeq::kCurvePoints, static_cast<double>(snap.curve_db[0]),
                static_cast<double>(snap.curve_db[caeq::kCurvePoints - 1]));
        r.check(snap.curve_db[0] == c[0] &&
                    snap.curve_db[caeq::kCurvePoints - 1] ==
                        c[static_cast<size_t>(caeq::kCurvePoints - 1)],
                "両端が厳密にグリッド上の値のまま (端の 1 点は個別に見る)");
        r.check(canaryIntact(*w), "読み書きを通しても番兵が無傷");
        delete w;
    }

    {
        ca_shm_t* m = new ca_shm_t{};
        m->magic = CA_SHM_MAGIC;
        m->version = CA_SHM_VERSION;
        m->slot_count = CA_SHM_SLOTS;
        m->slot_size = static_cast<uint32_t>(sizeof(ca_slot_t));
        m->param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t));
        m->curve_points = static_cast<uint32_t>(caeq::kCurvePoints);
        r.check(caeq::shmState(m) == caeq::ShmState::kOk, "版も並びも揃っていれば通る");

        m->curve_points = static_cast<uint32_t>(caeq::kCurvePoints) - 1u;
        r.check(caeq::shmState(m) == caeq::ShmState::kLayoutMismatch,
                "曲線の点数が違えば断る (版は同じ = 版を上げ忘れたビルド)");
        m->curve_points = static_cast<uint32_t>(caeq::kCurvePoints);

        m->param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t)) - 4u;
        r.check(caeq::shmState(m) == caeq::ShmState::kLayoutMismatch, "枠の大きさが違えば断る");
        m->param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t));

        m->slot_size = static_cast<uint32_t>(sizeof(ca_slot_t)) - 4u;
        r.check(caeq::shmState(m) == caeq::ShmState::kLayoutMismatch,
                "統計の枠の大きさが違えば断る");

        m->version = CA_SHM_VERSION - 1u;
        r.check(caeq::shmState(m) == caeq::ShmState::kVersionMismatch,
                "版と並びが両方違うときは「版ずれ」と言う (本当の理由が先)");
        delete m;
    }

    r.section("31. 枠を読む側 (`.so` の poll) — 丸ごと採るか丸ごと捨てるか");

    const std::vector<float> good = tiltCurve(-6.0f, 6.0f);

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m);
        writeSlot(&w->m.params[5], 77u, 88u, good.data(), CA_EQ_FLAG_ENABLED, -2.0);

        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        caeq::PollState st;
        caeq::pollSlot(&w->m.params[5], &st, &pl, true);
        r.check(st.param_gen == 77u && st.rejected == 0 && st.user_enabled,
                "指定した枠だけを読む (gen=%u 却下=%u)", st.param_gen, st.rejected);

        bool others = true;
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            if (i != 5 && !slotPatternIntact(w->m.params[i], i)) others = false;
        }
        r.check(others && canaryIntact(*w), "読み手は他の枠にも番兵にも触らない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        caeq::PollState st;

        writeSlot(&w->m.params[0], 1u, 1u, good.data(), CA_EQ_FLAG_ENABLED, -2.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        r.check(st.param_gen == 1u && st.rejected == 0, "まず正常な曲線が通る");

        struct Bad {
            const char* name;
            int index;
            float value;
        };
        const Bad bad[] = {
            {"先頭の点が NaN", 0, std::numeric_limits<float>::quiet_NaN()},
            {"末尾の点が +inf", caeq::kCurvePoints - 1,
             std::numeric_limits<float>::infinity()},
            {"真ん中の点が +40.001 dB", caeq::kCurvePoints / 2, 40.001f},
            {"末尾の 1 つ手前が -40.001 dB", caeq::kCurvePoints - 2, -40.001f},
        };
        uint32_t gen = 2u;
        bool all_rejected = true;
        for (const Bad& b : bad) {
            std::vector<float> c = good;
            c[static_cast<size_t>(b.index)] = b.value;
            const uint32_t before = st.rejected;
            const float keep_preamp = w->m.params[0].preamp_db;
            (void)keep_preamp;
            writeSlot(&w->m.params[0], gen, gen + 100u, c.data(), CA_EQ_FLAG_ENABLED, -7.0);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            if (st.rejected != before + 1u || st.param_gen != gen) all_rejected = false;
            gen++;
        }
        r.check(all_rejected, "端・中・上限外の %zu 通りをすべて丸ごと捨てて rejected を進める "
                              "(却下 %u 回)",
                sizeof(bad) / sizeof(bad[0]), st.rejected);

        {
            std::vector<float> c = good;
            c[0] = -caeq::kCurveMaxAbsDb;
            c[static_cast<size_t>(caeq::kCurvePoints - 1)] = caeq::kCurveMaxAbsDb;
            const uint32_t before = st.rejected;
            writeSlot(&w->m.params[0], gen, gen + 100u, c.data(), CA_EQ_FLAG_ENABLED, -3.0);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            r.check(st.rejected == before && st.param_gen == gen,
                    "ちょうど ±%.0f dB は通る (境界の内側は弾かない)",
                    static_cast<double>(caeq::kCurveMaxAbsDb));
            gen++;
        }

        {
            const uint32_t before = st.rejected;
            caeq::paramsBeginWrite(&w->m.params[0]);
            w->m.params[0].generation = gen;
            w->m.params[0].band_count = 1;
            w->m.params[0].band[0].fc_hz = 30000.0f;
            w->m.params[0].band[0].q = 1.0f;
            w->m.params[0].band[0].gain_db = 3.0f;
            w->m.params[0].band[0].type = CA_EQ_BAND_PEAKING;
            caeq::paramsEndWrite(&w->m.params[0]);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            r.check(st.rejected == before + 1u,
                    "曲線が正常でも bands が範囲外なら枠ごと捨てる");
            gen++;
        }

        {
            const uint32_t before = st.rejected;
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            r.check(st.rejected == before,
                    "同じ壊れた世代を読み直しても rejected は増えない (毎ブロック検査し直さない)");
        }
        r.check(canaryIntact(*w), "検査に落ちる経路でも番兵が無傷");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);

        writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        r.check(st.fir_requested, "高精度の旗が読み取れている");
        for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(pl.firState() == caeq::EqPipeline::FirState::kFir,
                "共有メモリ経由の曲線だけで FIR まで到達 (再構築 %u 回)", pl.rebuilds());
        const uint32_t builds = pl.rebuilds();

        for (uint32_t k = 0; k < 20; k++) {
            writeSlot(&w->m.params[0], 100u + k, 1u, nullptr,
                      CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -3.0 - 0.1 * k);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(pl.rebuilds() == builds && pl.firState() == caeq::EqPipeline::FirState::kFir,
                "プリアンプを 20 回動かしても再構築が走らない (rebuilds %u のまま)",
                pl.rebuilds());

        const std::vector<float> other = tiltCurve(4.0f, -4.0f);
        writeSlot(&w->m.params[0], 200u, 2u, other.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -3.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        drv.step();
        r.check(pl.rebuilds() == builds + 1u,
                "曲線の版が動けば組み直す (rebuilds %u -> %u)", builds, pl.rebuilds());
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);

        writeSlot(&w->m.params[0], 1u, 0u, nullptr,
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, 0.0);
        for (int b = 0; b < 30; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(st.fir_requested && pl.firState() == caeq::EqPipeline::FirState::kBiquad &&
                    pl.rebuilds() == 0 && pl.curveGeneration() == 0,
                "curve_gen=0 の枠では、高精度を頼まれても biquad のまま (再構築 0 回)");

        ca_slot_t s{};
        caeq::firStatsOf(st, pl, &s);
        r.check((s.fir_flags & CA_FIR_F_REQUESTED) && (s.fir_flags & CA_FIR_F_ARENA) &&
                    (s.fir_flags & CA_FIR_F_BLOCK_OK) && s.fir_curve_gen == 0,
                "診断で「要求あり・作業領域あり・ブロック可・でも曲線が無い」と読める "
                "(flags=0x%x)", s.fir_flags);
        r.check(caeq::firWhy(s, &w->m.params[0]) == caeq::FirWhy::kNoCurve,
                "理由の判定が「曲線が届いていない」を返す");
        delete w;
    }

    {
        ca_slot_t s{};
        ca_eq_slot_t q{};
        s.fir_state = CA_FIR_STATE_FIR;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kRunning, "鳴っていれば理由は要らない");

        s.fir_state = CA_FIR_STATE_BIQUAD;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNotRequested,
                "要求されていなければ「標準モード」(異常ではない)");

        s.fir_flags = CA_FIR_F_REQUESTED;
        s.fir_design_failures = 3;
        s.fir_fill = 0;
        s.fir_partitions = 9;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNotAddressable,
                "宛てられない経路なら、作業領域や設計失敗より先に「宛てられない」");

        s.fir_flags |= CA_FIR_F_ADDRESSABLE;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNoArena,
                "宛てられるのに作業領域が無ければ「作業領域が無い」");

        s.fir_flags |= CA_FIR_F_ARENA;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kBlockUnfit,
                "作業領域があってブロックが不適なら「ブロック長」");

        s.fir_flags |= CA_FIR_F_BLOCK_OK;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNoCurve,
                "曲線が無ければ、設計失敗より先に「曲線が届いていない」");

        q.curve_gen = 5;
        s.fir_flags |= CA_FIR_F_CURVE_FAILED;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kDesignFailed,
                "曲線があって**いま**設計器が止まっていれば「設計器が止まった」");

        s.fir_flags &= ~CA_FIR_F_CURVE_FAILED;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kWarming &&
                    s.fir_design_failures == 3,
                "累積 %u 回の失敗が残っていても、いま失敗していなければ「温めている最中」",
                s.fir_design_failures);

        s.fir_fill = s.fir_partitions;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kAlmost, "全部揃っていれば「まだ乗り移る前」");

        s.fir_fill = 0;
        r.check(caeq::firWhy(s, nullptr) == caeq::FirWhy::kWarming,
                "パラメータ枠が無くても判定できる (曲線の有無だけ飛ばす)");
    }

    {
        std::vector<float> got(static_cast<size_t>(caeq::kCurvePoints) + 8, -999.0f);

        {
            std::string text;
            text += "# comment\n\n";
            for (int i = 0; i < caeq::kCurvePoints; i++) {
                text += (i % 3 == 0 ? "  " : "");
                text += std::to_string(i % 17) + ".5\n";
                if (i % 50 == 0) text += "# 途中のコメント\n\n";
            }
            catest::TempFile f(text);
            const caeq::CurveReadResult res = caeq::readCurveFile(f.path(), got.data());
            bool same = res.status == caeq::CurveRead::kOk && res.count == caeq::kCurvePoints;
            for (int i = 0; i < caeq::kCurvePoints && same; i++) {
                if (got[static_cast<size_t>(i)] != static_cast<float>(i % 17) + 0.5f) same = false;
            }
            r.check(same, "%d 点ちょうどのファイルを読める (空行・コメント・前置きの空白を飛ばす)",
                    caeq::kCurvePoints);
            r.check(got[static_cast<size_t>(caeq::kCurvePoints)] == -999.0f,
                    "配列の外へ 1 つも書いていない");
        }

        {
            std::string few, many;
            for (int i = 0; i < caeq::kCurvePoints - 1; i++) few += "1.0\n";
            for (int i = 0; i < caeq::kCurvePoints + 1; i++) many += "1.0\n";
            catest::TempFile ff(few), fm(many);
            const caeq::CurveReadResult a = caeq::readCurveFile(ff.path(), got.data());
            const caeq::CurveReadResult b = caeq::readCurveFile(fm.path(), got.data());
            r.check(a.status == caeq::CurveRead::kWrongCount &&
                        a.count == caeq::kCurvePoints - 1 &&
                        b.status == caeq::CurveRead::kWrongCount &&
                        b.count == caeq::kCurvePoints + 1,
                    "1 点足りない (%d) も 1 点多い (%d) も失敗にする", a.count, b.count);
            r.check(got[static_cast<size_t>(caeq::kCurvePoints)] == -999.0f,
                    "点数が多いファイルでも配列の外へ書かない");
        }

        r.check(caeq::readCurveFile("__no_such_curve_file__", got.data()).status ==
                    caeq::CurveRead::kOpenFailed,
                "開けないファイルは「点数が違う」ではなく「開けない」と言う");
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);
        writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
        for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        ca_slot_t s{};
        caeq::firStatsOf(st, pl, &s);
        r.check(s.fir_state == static_cast<uint32_t>(pl.firState()) &&
                    s.fir_state == CA_FIR_STATE_FIR &&
                    s.fir_fill == static_cast<uint32_t>(pl.fdlFill()) &&
                    s.fir_partitions == static_cast<uint32_t>(pl.fdlPartitions()) &&
                    s.fir_rebuilds == pl.rebuilds() && s.fir_fallbacks == pl.fallbacks() &&
                    s.fir_taps == static_cast<uint32_t>(pl.designTaps()) &&
                    s.fir_m == static_cast<uint32_t>(pl.designM()) &&
                    s.fir_curve_gen == pl.curveGeneration() &&
                    s.fir_arena_kb == static_cast<uint32_t>(pl.arenaBytes() / 1024u),
                "統計へ写した値が pipeline の値と一致 (state=%u fill=%u/%u taps=%u M=%u "
                "arena=%u KB)",
                s.fir_state, s.fir_fill, s.fir_partitions, s.fir_taps, s.fir_m,
                s.fir_arena_kb);
        r.check(s.fir_max_slice_ns > 0,
                "スライスの実測が入っている (%.1f µs)",
                static_cast<double>(s.fir_max_slice_ns) / 1000.0);

        r.check(static_cast<uint32_t>(caeq::EqPipeline::FirState::kBiquad) ==
                        CA_FIR_STATE_BIQUAD &&
                    static_cast<uint32_t>(caeq::EqPipeline::FirState::kPrepare) ==
                        CA_FIR_STATE_PREPARE &&
                    static_cast<uint32_t>(caeq::EqPipeline::FirState::kFadeIn) ==
                        CA_FIR_STATE_FADE_IN &&
                    static_cast<uint32_t>(caeq::EqPipeline::FirState::kFir) ==
                        CA_FIR_STATE_FIR &&
                    static_cast<uint32_t>(caeq::EqPipeline::FirState::kFadeOut) ==
                        CA_FIR_STATE_FADE_OUT,
                "FirState の番号が共有メモリの表と一致 (読み手が表を引き直さない)");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);
        writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
        for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(pl.firState() == caeq::EqPipeline::FirState::kFir, "まず 48 kHz で FIR が鳴る");

        pl.configure(44100.0, 2, caeq::Structure::kTdf2);
        pl.warmUp();
        st.param_gen = 0;
        Driver drv2(pl, 960, 2);
        for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv2.step();
        }
        r.check(pl.firState() == caeq::EqPipeline::FirState::kFir &&
                    pl.designTaps() == caeq::firTapsFor(44100.0),
                "44.1 kHz へ変えても、枠が変わらないまま FIR が戻る (taps=%d)",
                pl.designTaps());
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
        auto& pl = *pl_h;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);

        writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        drv.step();
        pl.designerForTest().injectNonFinite();
        for (int b = 0; b < 30 && pl.designFailures() == 0; b++) drv.step();
        r.check(pl.designFailures() > 0 && pl.curveFailed(),
                "設計器を失敗させた (designFailures=%u curveFailed=%d)", pl.designFailures(),
                pl.curveFailed() ? 1 : 0);
        {
            ca_slot_t s{};
            caeq::firStatsOf(st, pl, &s);
            r.check(caeq::firWhy(s, &w->m.params[0]) == caeq::FirWhy::kDesignFailed,
                    "失敗している最中は「設計器が止まった」");
        }

        const std::vector<float> other = tiltCurve(3.0f, -3.0f);
        writeSlot(&w->m.params[0], 2u, 2u, other.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        bool lied = false;
        int blocks = 0;
        for (; blocks < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; blocks++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
            ca_slot_t s{};
            caeq::firStatsOf(st, pl, &s);
            if (caeq::firWhy(s, &w->m.params[0]) == caeq::FirWhy::kDesignFailed) lied = true;
        }
        r.check(!lied,
                "新しい曲線を温めている %d ブロックのあいだ、一度も「設計器が止まった」と"
                "言わない (designFailures は %u のまま累積している)",
                blocks, pl.designFailures());
        r.check(pl.firState() == caeq::EqPipeline::FirState::kFir && !pl.curveFailed(),
                "新しい曲線で FIR まで到達し、curveFailed が解除されている");
        delete w;
    }

    {
        size_t before_bytes = 0, after_bytes = 0;
        bool before_avail = false, after_avail = false;
        {
            std::unique_ptr<caeq::EqPipeline> a_h(new caeq::EqPipeline());
            auto& a = *a_h;
            a.setFirCapable(true);
            a.configure(48000.0, 2, caeq::Structure::kTdf2);
            before_avail = a.firAvailable();
            before_bytes = a.arenaBytes();
        }
        {
            std::unique_ptr<caeq::EqPipeline> b_h(new caeq::EqPipeline());
            auto& b = *b_h;
            b.configure(48000.0, 2, caeq::Structure::kTdf2);
            b.setFirCapable(true);
            after_avail = b.firAvailable();
            after_bytes = b.arenaBytes();
        }
        r.check(before_avail && after_avail && before_bytes == after_bytes &&
                    before_bytes > 0,
                "true の順序に依存しない (先に呼んでも後で呼んでも %zu KB)",
                before_bytes / 1024);

        {
            std::unique_ptr<caeq::EqPipeline> a_h(new caeq::EqPipeline());
            auto& a = *a_h;
            std::unique_ptr<caeq::EqPipeline> b_h(new caeq::EqPipeline());
            auto& b = *b_h;
            a.setFirCapable(false);
            a.configure(48000.0, 2, caeq::Structure::kTdf2);
            b.configure(48000.0, 2, caeq::Structure::kTdf2);
            b.setFirCapable(false);
            r.check(!a.firAvailable() && a.arenaBytes() == 0 && !b.firAvailable() &&
                        b.arenaBytes() == 0,
                    "false の順序にも依存しない (どちらも作業領域なし)");
        }

        {
            std::unique_ptr<caeq::EqPipeline> d_h(new caeq::EqPipeline());
            auto& d = *d_h;
            d.configure(48000.0, 2, caeq::Structure::kTdf2);
            r.check(d.firCapable() && d.firAvailable(),
                    "既定は true — 呼び忘れても高精度は死なない (無駄なだけ)");
        }

        {
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.setFirCapable(false);
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            caeq::Eq ref;
            ref.configure(48000.0, 2, caeq::Structure::kTdf2);
            caeq::Params p;
            p.band_count = 1;
            p.preamp_db  = -2.0;
            p.bands[0] = caeq::Band{caeq::BandType::kPeaking, 1000.0, 1.0, 6.0};
            pl.snapParams(p);
            ref.snapParams(p);
            pl.setActive(true);
            ref.setActive(true);
            pl.setFirEnabled(true);
            const std::vector<float> curve = tiltCurve(-6.0f, 6.0f);
            pl.setCurve(curve.data(), 1);

            catest::Rng rng(99);
            std::vector<float> in(1920), out_a(1920), out_b(1920);
            bool same = true;
            for (int b = 0; b < 20 && same; b++) {
                for (size_t i = 0; i < in.size(); i++) {
                    in[i] = static_cast<float>(0.2 * rng.uniform());
                }
                pl.process(in.data(), out_a.data(), 960, false);
                ref.process(in.data(), out_b.data(), 960, false);
                for (size_t i = 0; i < in.size(); i++) {
                    if (out_a[i] != out_b[i]) same = false;
                }
            }
            r.check(same && !pl.firAvailable() &&
                        pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                    "capable=false なら高精度を頼まれても素の Eq とビット同一 (FIR 経路が"
                    "音に影響しない)");
            r.check(pl.rebuilds() == 0 && pl.fallbacks() == 0 && pl.unfitSizeCount() == 0,
                    "capable=false では設計も落下も起きない (カウンタが全部 0)");
        }

        {
            ShmWithCanary* w = newCanaryShm();
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            pl.setClock(&fakeClock);
            pl.warmUp();
            caeq::PollState st;
            Driver drv(pl, 960, 2);
            writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                      CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
            for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
                caeq::pollSlot(&w->m.params[0], &st, &pl, true);
                drv.step();
            }
            const uint32_t gen = pl.curveGeneration();
            const int fill = pl.fdlFill();
            const uint32_t fb = pl.fallbacks();
            const uint32_t rb = pl.rebuilds();
            std::vector<float> in(1920), out_a(1920), out_b(1920);
            catest::Rng rng(5);
            for (size_t i = 0; i < in.size(); i++) in[i] = static_cast<float>(0.2 * rng.uniform());
            for (int k = 0; k < 5; k++) pl.setFirCapable(true);
            pl.process(in.data(), out_a.data(), 960, false);
            r.check(pl.firState() == caeq::EqPipeline::FirState::kFir &&
                        pl.curveGeneration() == gen && pl.fdlFill() == fill &&
                        pl.fallbacks() == fb && pl.rebuilds() == rb,
                    "true→true を 5 回撃っても鳴っている FIR が畳まれない");
            delete w;
        }

        {
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.setFirCapable(false);
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            r.check(!pl.firAvailable(), "false のあいだは作業領域なし");

            caeq::Params p;
            p.band_count = 0;
            p.preamp_db  = 0.0;
            pl.snapParams(p);
            pl.setActive(true);
            pl.setFirEnabled(true);
            const std::vector<float> curve = tiltCurve(-6.0f, 6.0f);
            pl.setCurve(curve.data(), 1);
            Driver drv(pl, 960, 2);
            drv.step();

            pl.setFirCapable(true);
            pl.warmUp();
            r.check(pl.firAvailable() && pl.arenaBytes() > 0,
                    "false→true で、configure を挟まずにその場で確保する (%zu KB)",
                    pl.arenaBytes() / 1024);

            int reached = -1;
            for (int b = 0; b < 80; b++) {
                drv.step();
                if (pl.firState() == caeq::EqPipeline::FirState::kFir) { reached = b; break; }
            }
            r.check(reached >= 0,
                    "同じブロック長のまま FIR へ到達する (%d ブロック) — "
                    "確保の後にブロック長の再評価が走っている", reached);
        }

        {
            ShmWithCanary* w = newCanaryShm();
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            pl.setClock(&fakeClock);
            pl.warmUp();
            caeq::PollState st;
            Driver drv(pl, 960, 2);
            writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                      CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
            for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
                caeq::pollSlot(&w->m.params[0], &st, &pl, true);
                drv.step();
            }
            const uint32_t fb = pl.fallbacks();
            const uint32_t mo = pl.modeOffs();
            pl.setFirCapable(false);
            r.check(!pl.firAvailable() && pl.arenaBytes() == 0 &&
                        pl.firState() == caeq::EqPipeline::FirState::kBiquad,
                    "鳴っている最中に false にしても作業領域が解放されて biquad へ戻る");
            r.check(pl.fallbacks() == fb && pl.modeOffs() == mo,
                    "落下にもモード切にも数えない (枠の宛先が動いただけ)");
            r.check(pl.biquad().active(),
                    "受け渡しで Eq を起こしている (駐機したままだと素通しの段差になる)");
            bool finite = true;
            double energy = 0.0;
            for (int b = 0; b < 10; b++) {
                drv.step();
                for (float v : drv.out) {
                    if (!std::isfinite(v)) finite = false;
                }
                for (size_t i = 0; i < drv.out.size(); i++) {
                    energy += std::fabs(static_cast<double>(drv.out[i]) -
                                        static_cast<double>(drv.in[i]));
                }
            }
            r.check(finite, "解放したあとも出力が有限 (解放済みの領域を触っていない)");
            r.check(energy > 0.0,
                    "biquad が実際に鳴っている (素通しに落ちていない。差の総和 %.3g)", energy);
            delete w;
        }

        {
            ShmWithCanary* w = newCanaryShm();
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.setFirCapable(false);
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            pl.setClock(&fakeClock);
            caeq::PollState st;
            Driver drv(pl, 960, 2);

            writeSlot(&w->m.params[0], 1u, 1u, good.data(),
                      CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -2.0);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();

            pl.setFirCapable(false);
            pl.setFirCapable(true);
            pl.warmUp();

            for (int b = 0; b < 60 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
                caeq::pollSlot(&w->m.params[0], &st, &pl, true);
                drv.step();
            }
            r.check(pl.firState() == caeq::EqPipeline::FirState::kFir &&
                        pl.curveGeneration() == 1u,
                    "冗長な false のあとでも、届いていた曲線で FIR に到達する "
                    "(送り直さずに。state=%d gen=%u)",
                    static_cast<int>(pl.firState()), pl.curveGeneration());
            delete w;
        }

        {
            const std::vector<float> curve = tiltCurve(-6.0f, 6.0f);
            caeq::Params p0, p1;
            p0.band_count = 1;
            p0.preamp_db  = -2.0;
            p0.bands[0] = caeq::Band{caeq::BandType::kPeaking, 1000.0, 1.0, 6.0};
            p1 = p0;
            p1.preamp_db = -9.0;

            std::unique_ptr<caeq::EqPipeline> a_h(new caeq::EqPipeline());
            auto& a = *a_h;
            std::unique_ptr<caeq::EqPipeline> b_h(new caeq::EqPipeline());
            auto& b = *b_h;
            a.configure(48000.0, 2, caeq::Structure::kTdf2);
            b.setFirCapable(false);
            b.configure(48000.0, 2, caeq::Structure::kTdf2);
            b.setFirCapable(true);

            caeq::EqPipeline* pls[2] = {&a, &b};
            for (caeq::EqPipeline* pl : pls) {
                pl->snapParams(p0);
                pl->setActive(true);
                pl->setFirEnabled(true);
                pl->setCurve(curve.data(), 1);
                pl->warmUp();
            }
            r.check(a.firAvailable() && b.firAvailable() &&
                        a.arenaBytes() == b.arenaBytes() &&
                        a.designTaps() == b.designTaps() && a.designM() == b.designM(),
                    "両経路とも同じ作業領域 (%zu KB / taps %d / M %d)", a.arenaBytes() / 1024,
                    a.designTaps(), a.designM());

            catest::Rng rng(4242);
            std::vector<float> in(1920), out_a(1920), out_b(1920);
            bool same = true;
            int first_diff = -1;
            for (int blk = 0; blk < 80; blk++) {
                for (size_t i = 0; i < in.size(); i++) {
                    in[i] = static_cast<float>(0.2 * rng.uniform());
                }
                if (blk == 40) { a.setParams(p1); b.setParams(p1); }
                a.process(in.data(), out_a.data(), 960, false);
                b.process(in.data(), out_b.data(), 960, false);
                for (size_t i = 0; i < in.size() && same; i++) {
                    if (out_a[i] != out_b[i]) { same = false; first_diff = blk; }
                }
            }
            r.check(same && a.firState() == caeq::EqPipeline::FirState::kFir &&
                        b.firState() == caeq::EqPipeline::FirState::kFir,
                    "80 ブロック (preamp のランプを含む) を通して出力がビット同一 "
                    "%s— 確保の経路が音に出ない",
                    first_diff >= 0 ? "でない (最初の差はブロック " : "");
            r.check(a.rebuilds() == b.rebuilds() && a.fallbacks() == b.fallbacks() &&
                        a.curveGeneration() == b.curveGeneration(),
                    "診断カウンタも一致 (rebuilds %u / 落下 %u / 曲線 gen %u)", a.rebuilds(),
                    a.fallbacks(), a.curveGeneration());
        }

        {
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.configure(48000.0, 2, caeq::Structure::kTdf2);
            const std::vector<float> curve = tiltCurve(-6.0f, 6.0f);
            caeq::Params p;
            p.band_count = 0;
            p.preamp_db  = -2.0;
            pl.snapParams(p);
            pl.setActive(true);
            pl.setFirEnabled(true);
            pl.setCurve(curve.data(), 1);
            pl.warmUp();

            std::vector<float> in(1920, 0.25f), out(1920);
            for (int b = 0; b < 80 && pl.firState() != caeq::EqPipeline::FirState::kFir; b++) {
                pl.process(in.data(), out.data(), 960, false);
            }
            for (int b = 0; b < 12; b++) pl.process(in.data(), out.data(), 960, false);
            const double steady = static_cast<double>(out[0]);

            p.preamp_db = -20.0;
            pl.setParams(p);
            pl.process(in.data(), out.data(), 960, false);

            const double first = static_cast<double>(out[0]);
            const double last  = static_cast<double>(out[2 * 900]);
            const double want_ratio = std::pow(10.0, -18.0 / 20.0);
            const double settled = steady * want_ratio;

            const double head_err = std::fabs(first - steady) / std::fabs(steady);
            const double tail_err = std::fabs(last - settled) / std::fabs(settled);
            r.check(head_err < 0.02 && tail_err < 0.02,
                    "preamp がランプする — ブロック先頭は旧ゲインのまま (誤差 %.3f%%)、"
                    "900 サンプル後に新ゲインへ着地 (%.3f%%)",
                    head_err * 100.0, tail_err * 100.0);

            int moving = 0;
            for (int i = 1; i < 480; i++) {
                if (static_cast<double>(out[2 * i]) != static_cast<double>(out[2 * (i - 1)])) {
                    moving++;
                }
            }
            r.check(moving > 400,
                    "ランプの区間で毎サンプル動いている (%d / 479 サンプル) — "
                    "即時の入れ替えでも階段でもない", moving);
        }
    }

    {
        ShmWithCanary* w = newCanaryShm();
        FakeAlive alive;
        putStatSlot(&w->m, 0, 4242, CA_AUDIO_SESSION_DEVICE);
        putStatSlot(&w->m, 1, 4242, 77);
        const caeq::SlotPickResult pick = caeq::pickDeviceSlot(&w->m, FakeAlive::fn, &alive);
        r.check(pick.status == caeq::SlotPick::kOk && pick.stats_slot == 0 &&
                    pick.other_count == 1,
                "書き手は DEVICE の枠だけを選ぶ (other=%u)", pick.other_count);
        r.check(caeq::sessionCanBeAddressed(w->m.slots[0].session_id) &&
                    !caeq::sessionCanBeAddressed(w->m.slots[1].session_id),
                "読み手の確保もまったく同じ述語で決まる (sessionCanBeAddressed)");
        delete w;
    }

    {
        const double rates[] = {44100.0, 48000.0, 88200.0, 96000.0};
        size_t worst = 0;
        double worst_fs = 0.0;
        for (double fs : rates) {
            std::unique_ptr<caeq::EqPipeline> pl_h(new caeq::EqPipeline());
            auto& pl = *pl_h;
            pl.configure(fs, 2, caeq::Structure::kTdf2);
            if (pl.arenaBytes() > worst) { worst = pl.arenaBytes(); worst_fs = fs; }
        }
        const size_t eq_bytes = sizeof(caeq::Eq);
        const unsigned need = static_cast<unsigned>((worst + eq_bytes + 1023u) / 1024u);
        r.check(need <= 1536,
                "実測の最悪 %u KB が上限 1536 KB に収まる (arena %zu KB @%.0f Hz + Eq %zu KB)",
                need, worst / 1024, worst_fs, eq_bytes / 1024);
        std::unique_ptr<caeq::EqPipeline> wide_h(new caeq::EqPipeline());
        auto& wide = *wide_h;
        wide.configure(48000.0, 12, caeq::Structure::kTdf2);
        r.check(!wide.firAvailable() && wide.arenaBytes() == 0,
                "12ch のインスタンスには作業領域を作らない");
    }
}

namespace {

struct PidTable {
    uint64_t alive[16];
    int count;
    static bool fn(uint64_t pid, void* user) {
        const PidTable* t = static_cast<const PidTable*>(user);
        for (int i = 0; i < t->count; i++) {
            if (t->alive[i] == pid) return true;
        }
        return false;
    }
};

void fillAllSlots(ca_shm_t* m, int32_t session) {
    for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) putStatSlot(m, i, 1000ull + i, session);
}

void putParams(ca_shm_t* m, uint32_t i, uint32_t gen) {
    ca_eq_slot_t& q = m->params[i];
    q.generation = gen;
    q.band_count = 10;
    q.curve_gen = 3;
    q.preamp_db = -2.5f;
    q.flags = CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION;
    for (int p = 0; p < caeq::kCurvePoints; p++) q.curve_db[p] = curveSample(i, p);
}

bool paramsIntact(const ca_shm_t* m, uint32_t i, uint32_t gen) {
    const ca_eq_slot_t& q = m->params[i];
    if (q.generation != gen || q.band_count != 10u || q.curve_gen != 3u) return false;
    if (q.flags != (CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION)) return false;
    for (int p = 0; p < caeq::kCurvePoints; p++) {
        if (q.curve_db[p] != curveSample(i, p)) return false;
    }
    return true;
}

bool statBodyZero(const ca_slot_t& s) {
    const uint8_t* p = reinterpret_cast<const uint8_t*>(&s) + offsetof(ca_slot_t, seq);
    for (size_t i = 0; i < sizeof(ca_slot_t) - offsetof(ca_slot_t, seq); i++) {
        if (p[i] != 0u) return false;
    }
    return true;
}

}

void runSlotLifecycleSection(Report& r) {
    r.section("32. 枠の取得と回収 (枠の枯渇で EQ が素通しになる不具合)");

    {
        ShmWithCanary* w = newCanaryShm();
        putStatSlot(&w->m, 3, 4242, CA_AUDIO_SESSION_DEVICE);
        putParams(&w->m, 3, 7);
        w->m.slots[3].frames = 99999;

        r.check(caeq::releaseSlot(&w->m.slots[3]), "使用中の枠は手放せる");
        r.check(w->m.slots[3].in_use == 0u, "in_use が空きに戻る (0x%x)", w->m.slots[3].in_use);
        r.check(statBodyZero(w->m.slots[3]),
                "in_use == 0 の枠は中身も必ずゼロ (frames が残っていない)");
        r.check(paramsIntact(&w->m, 3, 7),
                "パラメータ枠は無傷 (曲線 %d 点・バンド 10・gen 7 が残っている)",
                caeq::kCurvePoints);

        r.check(!caeq::releaseSlot(&w->m.slots[3]),
                "空きの枠は手放せない (二重解放を CAS が弾く)");
        r.check(!caeq::releaseSlot(nullptr), "nullptr でも落ちない");
        r.check(canaryIntact(*w), "番兵が無傷");
        delete w;
    }

    {
        r.check(caeq::aliveFromAccess(0, 0), "見に行けた = 生きている");
        r.check(!caeq::aliveFromAccess(-1, ENOENT), "ENOENT だけが「死んでいる」");
        r.check(caeq::aliveFromAccess(-1, EACCES), "EACCES は「分からない」= 生きている扱い");
        r.check(caeq::aliveFromAccess(-1, EPERM), "EPERM は「分からない」= 生きている扱い");
        r.check(caeq::aliveFromAccess(-1, ENAMETOOLONG),
                "そのほかの errno も「分からない」= 生きている扱い");
        r.check(caeq::aliveFromAccess(-1, 0), "errno が立っていなければ死とは言わない");
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{1002, 1005}, 2};

        const uint32_t freed = caeq::reclaimDeadSlots(&w->m, PidTable::fn, &t, 0);
        r.check(freed == 6u, "死んでいる 6 枠だけを回収した (%u)", freed);
        r.check(w->m.slots[2].in_use == CA_SHM_MAGIC && w->m.slots[5].in_use == CA_SHM_MAGIC,
                "生きている枠は使用中のまま");
        r.check(w->m.slots[2].pid == 1002 && w->m.slots[5].pid == 1005,
                "生きている枠の中身が消えていない");
        bool others_free = true;
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            if (i == 2u || i == 5u) continue;
            if (w->m.slots[i].in_use != 0u) others_free = false;
        }
        r.check(others_free, "残りの 6 枠は空きに戻った");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007}, 8};
        r.check(caeq::reclaimDeadSlots(&w->m, PidTable::fn, &t, 0) == 0u,
                "pid が全部生きていれば 1 つも回収しない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{0}, 0};
        const uint32_t freed = caeq::reclaimDeadSlots(&w->m, PidTable::fn, &t, 1004);
        r.check(freed == 7u && w->m.slots[4].in_use == CA_SHM_MAGIC,
                "pid == 自分 (1004) の枠は回収されない (回収 %u / 枠 4 は使用中のまま)", freed);
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        putStatSlot(&w->m, 0, 0, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{0}, 0};
        r.check(caeq::reclaimDeadSlots(&w->m, PidTable::fn, &t, 9999) == 0u &&
                    w->m.slots[0].in_use == CA_SHM_MAGIC,
                "pid == 0 (attach の途中) の枠は回収しない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        r.check(caeq::reclaimDeadSlots(&w->m, nullptr, nullptr, 0) == 0u,
                "生存確認が無ければ回収しない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        PidTable t{{0}, 0};
        r.check(caeq::acquireSlot(&w->m, PidTable::fn, &t, 1) == 0u,
                "空いていれば最小の添字を取る");
        r.check(w->m.slots[0].in_use == CA_SHM_MAGIC, "取った枠が使用中になる");
        r.check(caeq::acquireSlot(&w->m, PidTable::fn, &t, 1) == 1u, "次は 1 番");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            w->m.slots[i].frames = 12345u + i;
            putParams(&w->m, i, 20u + i);
        }
        PidTable t{{0}, 0};

        const uint32_t got = caeq::acquireSlot(&w->m, PidTable::fn, &t, 999);
        r.check(got == 0u, "8 枠全部が残骸なら、回収して枠 0 が取れる (返り値 %u)", got);
        r.check(w->m.slots[0].in_use == CA_SHM_MAGIC,
                "取った枠は使用中 (中身は呼び手がこれから埋める)");
        r.check(w->m.slots[0].frames == 0u, "取った枠の frames は 0 に戻っている (残骸の値ではない)");
        r.check(paramsIntact(&w->m, 0, 20),
                "回収した枠のパラメータは無傷 — 取り直せば EQ がそのまま復帰する");
        r.check(canaryIntact(*w), "番兵が無傷");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007}, 8};
        r.check(caeq::acquireSlot(&w->m, PidTable::fn, &t, 999) == caeq::kNoSlot,
                "pid が全部生きていれば枠は取れない");
        bool kept = true;
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) {
            if (w->m.slots[i].in_use != CA_SHM_MAGIC || w->m.slots[i].pid != 1000ull + i) {
                kept = false;
            }
        }
        r.check(kept, "生きている 8 枠のどれも奪われていない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) putStatSlot(&w->m, i, 555, 0);
        PidTable t{{0}, 0};
        r.check(caeq::acquireSlot(&w->m, PidTable::fn, &t, 555) == caeq::kNoSlot,
                "全部が自分 (pid 555) の枠なら取れない");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        r.check(caeq::acquireSlot(&w->m, nullptr, nullptr, 999) == caeq::kNoSlot,
                "生存確認が無ければ、残骸だらけでも枠は取れない (syscall を撃たない経路)");
        caeq::releaseSlot(&w->m.slots[6]);
        r.check(caeq::acquireSlot(&w->m, nullptr, nullptr, 999) == 6u,
                "空きが 1 つできれば、生存確認なしでもそこを取る");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{1003}, 1};
        const uint32_t got = caeq::acquireSlot(&w->m, PidTable::fn, &t, 999);
        r.check(got == 0u, "残骸を回収して最小の添字 0 を取る (%u)", got);
        r.check(w->m.slots[3].in_use == CA_SHM_MAGIC && w->m.slots[3].pid == 1003,
                "生きている枠 3 はそのまま");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m, CA_AUDIO_SESSION_DEVICE);
        PidTable t{{0}, 0};

        constexpr int kThreads = CA_SHM_SLOTS;
        uint32_t got[kThreads] = {};
        std::vector<std::thread> th;
        for (int i = 0; i < kThreads; i++) {
            th.emplace_back([&, i] { got[i] = caeq::acquireSlot(&w->m, PidTable::fn, &t, 999); });
        }
        for (std::thread& x : th) x.join();

        int seen[CA_SHM_SLOTS] = {};
        int failed = 0;
        for (int i = 0; i < kThreads; i++) {
            if (got[i] == caeq::kNoSlot) { failed++; continue; }
            if (got[i] < static_cast<uint32_t>(CA_SHM_SLOTS)) seen[got[i]]++;
        }
        int dup = 0;
        for (int i = 0; i < CA_SHM_SLOTS; i++) {
            if (seen[i] > 1) dup++;
        }
        r.check(failed == 0, "%d スレッドが同時に来ても全員が枠を取れた (取れなかった %d)",
                kThreads, failed);
        r.check(dup == 0, "同じ枠を 2 人が持っていない (重複 %d)", dup);
        r.check(canaryIntact(*w), "番兵が無傷");
        delete w;
    }

    {
        ShmWithCanary* w = newCanaryShm();
        FakeAlive alive;
        caeq::SlotPickResult p = caeq::pickDeviceSlot(&w->m, FakeAlive::fn, &alive);
        r.check(p.status == caeq::SlotPick::kNone && p.used_count == 0u &&
                    p.slot_count == CA_SHM_SLOTS,
                "1 枠も使われていない = イヤホンが繋がっていない (使用中 %u/%u)",
                p.used_count, p.slot_count);

        fillAllSlots(&w->m, 77);
        p = caeq::pickDeviceSlot(&w->m, FakeAlive::fn, &alive);
        r.check(p.status == caeq::SlotPick::kNone && p.live_count == 0u &&
                    p.used_count == CA_SHM_SLOTS && p.other_count == CA_SHM_SLOTS,
                "8 枠全部が使用中で DEVICE が 0 = 枠が尽きた (使用中 %u/%u / イヤホン以外 %u)",
                p.used_count, p.slot_count, p.other_count);
        delete w;

        ShmWithCanary* w2 = newCanaryShm();
        for (uint32_t i = 0; i < CA_SHM_SLOTS; i++) putStatSlot(&w2->m, i, 0, 0);
        p = caeq::pickDeviceSlot(&w2->m, FakeAlive::fn, &alive);
        r.check(p.used_count == CA_SHM_SLOTS && p.stale_count == 0u && p.other_count == 0u,
                "attach 途中の枠も「使用中」に数える (使用中 %u / 残骸 %u / イヤホン以外 %u)",
                p.used_count, p.stale_count, p.other_count);
        delete w2;
    }

    {
        ca_slot_t s{};
        ca_eq_slot_t q{};
        q.curve_gen = 4;

        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNotRequested,
                "素の表は、音が来ていない枠を「標準モード」と読む (だから入口が要る)");
        r.check(caeq::firWhyReported(s, &q) == caeq::FirWhy::kNoAudio,
                "入口は「まだ音が来ていない」と答える");

        s.fir_flags = CA_FIR_F_REQUESTED | CA_FIR_F_ADDRESSABLE | CA_FIR_F_ARENA;
        r.check(caeq::firWhyReported(s, &q) == caeq::FirWhy::kNoAudio,
                "旗が立っていても frames == 0 なら「まだ音が来ていない」");

        s.frames = 896;
        s.sample_rate = 44100;
        s.block_frames = 896;
        r.check(caeq::firWhyReported(s, &q) == caeq::FirWhy::kBlockUnfit,
                "44.1 kHz / block 896 は「ブロック長が不適」— 無音でも遅延でもない");

        s.fir_flags |= CA_FIR_F_BLOCK_OK;
        s.fir_partitions = 9;
        s.fir_fill = 9;
        r.check(caeq::firWhyReported(s, &q) == caeq::FirWhy::kAlmost,
                "条件が揃えば「まだ乗り移る前」");
        s.fir_state = CA_FIR_STATE_FIR;
        r.check(caeq::firWhyReported(s, &q) == caeq::FirWhy::kRunning,
                "鳴っていれば理由は要らない");
    }

    {
        static const char* const kExpect[] = {
            "running", "no_audio", "not_requested", "not_addressable", "no_arena",
            "block_unfit", "no_curve", "design_failed", "warming", "almost",
        };
        constexpr int kCount = static_cast<int>(sizeof(kExpect) / sizeof(kExpect[0]));
        bool ok = true;
        for (int i = 0; i < kCount; i++) {
            const char* got = caeq::firWhyToken(static_cast<caeq::FirWhy>(i));
            if (std::strcmp(got, kExpect[i]) != 0) {
                ok = false;
                r.note("添字 %d: [%s] のはずが [%s]", i, kExpect[i], got);
            }
        }
        r.check(ok, "理由の綴り %d 個が添字ごと一致する (アプリとの契約)", kCount);
        r.check(caeq::firWhyToken(static_cast<caeq::FirWhy>(kCount))[0] == '\0',
                "%d 個で全部 (足したら綴りの表も一緒に直すこと)", kCount);
    }

    {
        r.check(caeq::paramSlotAfterAttach(CA_PARAM_SLOT_NONE, 3) == 3u,
                "宛先が未割り当てなら、取った枠と同じ添字が既定 (初回の attach)");
        r.check(caeq::paramSlotAfterAttach(5, 2) == 5u,
                "既に宛てられていれば保つ (再取得で潰さない)");
        r.check(caeq::paramSlotAfterAttach(0, 2) == 0u,
                "添字 0 も有効な宛先 (0 を「未割り当て」と混同しない)");
        r.check(caeq::paramSlotAfterAttach(CA_SHM_SLOTS, 2) == 2u,
                "範囲外 (%d 以上) はすべて未割り当て扱い", CA_SHM_SLOTS);

        ShmWithCanary* w = newCanaryShm();
        PidTable t{{0}, 0};
        const uint32_t taken = caeq::acquireSlot(&w->m, PidTable::fn, &t, 999);
        const uint32_t reader = caeq::paramSlotAfterAttach(5u, taken);
        putStatSlot(&w->m, taken, 4242, CA_AUDIO_SESSION_DEVICE);
        w->m.slots[taken].param_slot = reader;
        FakeAlive alive;
        const caeq::SlotPickResult p = caeq::pickDeviceSlot(&w->m, FakeAlive::fn, &alive);
        r.check(taken == 0u && reader == 5u,
                "統計は枠 %u、宛先は 5 のまま (取った添字に付け替えていない)", taken);
        r.check(p.status == caeq::SlotPick::kOk && p.param_slot == 5u && p.stats_slot == 0u,
                "書き手も params[%u] へ書く (読み手と同じ宛先。fs は統計の枠 %u から)",
                p.param_slot, p.stats_slot);
        delete w;
    }
}
