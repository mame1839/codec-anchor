// 共有メモリ v4 の境界と、枠を読む側 (`.so` の poll) の見張り。
//
// ⚠️ **ここが一次防御線。**段 2 で増えるのは「1 個の大きな構造体の中で隣の欄へはみ出す」
// 形の間違い (曲線が次の枠へ食い込む / 401 点の添字の off-by-one) で、これは
// **ASan もガードページも捕まえない** — 赤帯は確保の外側にしか無く、欄と欄の間には
// 無いため (MSVC の ASan で実測確認済み。clang の -fsanitize-address-field-padding が
// 要るが MSVC には無い)。捕まえられるのは次の 3 つだけ:
//
//   1. グリッド定数 (点数・両端) の一本化をコンパイル時に縛る
//   2. 境界の明示的なテスト (先頭/末尾の点、先頭/末尾の枠、枠と枠の継ぎ目)
//   3. ca_shm_t の外に置いた自前の番兵 (確保の外側 = ASan と同じ形を mingw でも見る)
#include <cstring>
#include <limits>
#include <string>

#include "ca_test_support.h"
#include "../ca_eq_curve_io.h"
#include "../ca_eq_pick.h"
#include "../dsp/ca_eq_poll.h"

namespace {

using catest::Report;

// ca_shm_t の前後を自前の番兵で挟む。**ASan が居ない mingw でも効く** 2 本目の計器。
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

// 枠ごとに違う模様。**隣へ食い込んだら必ず値が変わる**ように、枠の添字と点の添字の
// 両方を混ぜる。全点同値だと 1 枠ぶんずれても一致してしまう。
float curveSample(uint32_t slot, int i) {
    return static_cast<float>(slot) * 0.5f + static_cast<float>(i % 61) * 0.125f - 3.0f;
}

// 枠の末尾の pad にも番兵を置く。
//
// ⚠️ **これが無いと「曲線を 1 点はみ出して書く」を誰も捕まえない** (変異試験で確認した)。
// pad は 48 B = float 12 個ぶんあるので、12 点までのはみ出しは pad に落ちて隣の枠へ
// 届かず、枠ごとの模様の照合を素通りする。**pad は製品側の誰も読み書きしない**ので、
// テストが番兵として使ってよい。
uint8_t padSample(uint32_t slot, size_t i) {
    return static_cast<uint8_t>(0xC0u + ((slot * 7u + static_cast<uint32_t>(i)) & 0x3Fu));
}

// ⚠️ **書く順序が検査の効き目を決める。**pad を先に、曲線を最後に置くこと —
// 逆にすると、曲線がはみ出して汚した pad を、その後の pad の書き込みが直してしまう。
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

// ⚠️ **枠は添字の大きいほうから埋めること。**前から埋めると、枠 i のはみ出しで壊れた
// 枠 i+1 を、その後の枠 i+1 の書き込みが直してしまう (pad を曲線より先に書くのと同じ理由)。
void fillAllSlots(ca_shm_t* m) {
    for (int i = CA_SHM_SLOTS - 1; i >= 0; i--) {
        fillSlotPattern(&m->params[i], static_cast<uint32_t>(i));
    }
}

bool slotPatternIntact(const ca_eq_slot_t& q, uint32_t slot) {
    // seq は誰も書いていない。**隣からはみ出してきた書き込みが最初に当たる欄**なので、
    // 模様の一部として見る。
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

// 平坦でない、検査を通る曲線。
std::vector<float> tiltCurve(float lo, float hi) {
    std::vector<float> c(static_cast<size_t>(caeq::kCurvePoints));
    for (int i = 0; i < caeq::kCurvePoints; i++) {
        const float t = static_cast<float>(i) / static_cast<float>(caeq::kCurvePoints - 1);
        c[static_cast<size_t>(i)] = lo + (hi - lo) * t;
    }
    return c;
}

// poll を通した枠の更新を 1 回。書き手と同じ作法 (seqlock) で書く。
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

// EqPipeline を回す最小の駆動。
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

}  // namespace

void runShmSections(Report& r) {
    r.section("30. 共有メモリ v4 — 並びと枠の境界");

    // --- 1. グリッドの定義が 1 箇所であること -------------------------------
    //
    // 点数を動かしたときに何が連鎖して止まるか。**ここが黙って通ると、書き手と読み手が
    // 別の格子で話し始める。**
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
    // 版 3 のファイル (5760 B) は版 4 の構造体より小さい。**読む側の大きさ検査が
    // 必ず弾く**ので SIGBUS には行かない。ここが逆転したら検査が意味を失う。
    r.check(5760u < sizeof(ca_shm_t),
            "版 3 の大きさ (5760 B) では版 4 の構造体が入らない — 大きさ検査が必ず弾く");

    // --- 2. 枠の並びが等間隔で、重なりも隙間も無いこと -----------------------
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

    // --- 3. 枠と枠の継ぎ目 — 書いた枠だけが変わること ------------------------
    //
    // **曲線が隣の枠へ食い込む形は ASan も番兵も捕まえない** (どちらも確保の外しか
    // 見ない)。枠ごとに違う模様を書いて全部読み戻すのが唯一の手。
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

        // 末尾の枠の末尾の点を書いても、確保の外へ出ない。
        w->m.params[CA_SHM_SLOTS - 1].curve_db[caeq::kCurvePoints - 1] = 12.5f;
        r.check(canaryIntact(*w),
                "最後の枠の最後の点 (params[%d].curve_db[%d]) を書いても番兵が無傷",
                CA_SHM_SLOTS - 1, caeq::kCurvePoints - 1);

        // 統計の最後の枠を丸ごと埋めても、パラメータの先頭に触れない。
        // (`.so` の detach が offsetof から長さを計算して memset する形と同じ。)
        const uint32_t keep = w->m.params[0].generation;
        ca_slot_t* last = &w->m.slots[CA_SHM_SLOTS - 1];
        std::memset(&last->seq, 0xEE, sizeof(ca_slot_t) - offsetof(ca_slot_t, seq));
        r.check(w->m.params[0].generation == keep && slotPatternIntact(w->m.params[0], 0),
                "統計の最後の枠を末尾まで塗ってもパラメータの先頭に届かない");
        delete w;
    }

    // --- 4. 曲線の端 — 先頭と末尾の点が化けずに往復すること ------------------
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

    // --- 5. 版と並びの食い違い ----------------------------------------------
    //
    // **版が違えば並びも違って当たり前**なので、版を先に見ること。順序を逆にすると
    // 「版ずれ」という本当の理由が「並びが違う」に化ける。
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

        // 版が違うほうを先に言う。並びは版が違えばどうせ違う。
        m->version = CA_SHM_VERSION - 1u;
        r.check(caeq::shmState(m) == caeq::ShmState::kVersionMismatch,
                "版と並びが両方違うときは「版ずれ」と言う (本当の理由が先)");
        delete m;
    }

    r.section("31. 枠を読む側 (`.so` の poll) — 丸ごと採るか丸ごと捨てるか");

    const std::vector<float> good = tiltCurve(-6.0f, 6.0f);

    // --- 6. 自分の枠だけを読む ----------------------------------------------
    {
        ShmWithCanary* w = newCanaryShm();
        fillAllSlots(&w->m);
        // 枠 5 にだけ本物の曲線を置く。
        writeSlot(&w->m.params[5], 77u, 88u, good.data(), CA_EQ_FLAG_ENABLED, -2.0);

        caeq::EqPipeline pl;
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

    // --- 7. 曲線が 1 点でも外れたら、bands ごと捨てる ------------------------
    //
    // 仕様 §1「1 つでも外れたら更新を丸ごと捨てて前の設定を保ち、rejected を進める」。
    // **bands だけ通すと「新しい bands と古い曲線」が同時に鳴る**ので、片方だけは通さない。
    {
        ShmWithCanary* w = newCanaryShm();
        caeq::EqPipeline pl;
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

        // ちょうど境界 (±40.0) は通ること。**上限を跨いだ側だけを弾いている**ことの確認で、
        // これが無いと「全部弾く」実装でもテストが緑になる。
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

        // bands が範囲外でも同じ扱い。曲線が正常でも枠ごと捨てる。
        {
            const uint32_t before = st.rejected;
            caeq::paramsBeginWrite(&w->m.params[0]);
            w->m.params[0].generation = gen;
            w->m.params[0].band_count = 1;
            w->m.params[0].band[0].fc_hz = 30000.0f;  // 48 kHz では Nyquist 超え
            w->m.params[0].band[0].q = 1.0f;
            w->m.params[0].band[0].gain_db = 3.0f;
            w->m.params[0].band[0].type = CA_EQ_BAND_PEAKING;
            caeq::paramsEndWrite(&w->m.params[0]);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            r.check(st.rejected == before + 1u,
                    "曲線が正常でも bands が範囲外なら枠ごと捨てる");
            gen++;
        }

        // 捨てた世代を覚えているので、同じ並びを毎回検査し直さない。
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

    // --- 8. 曲線の版と枠の版を分ける効き目 -----------------------------------
    //
    // プリアンプやバンドのドラッグ (60 Hz) で FIR の再構築が走らないこと。
    // **走ると 0.3〜0.6 s の再構築が毎フレーム始まり直して永久に完成しない。**
    {
        ShmWithCanary* w = newCanaryShm();
        caeq::EqPipeline pl;
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

        // プリアンプだけ 20 回動かす。曲線の版は据え置き。
        for (uint32_t k = 0; k < 20; k++) {
            writeSlot(&w->m.params[0], 100u + k, 1u, nullptr,
                      CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -3.0 - 0.1 * k);
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(pl.rebuilds() == builds && pl.firState() == caeq::EqPipeline::FirState::kFir,
                "プリアンプを 20 回動かしても再構築が走らない (rebuilds %u のまま)",
                pl.rebuilds());

        // 曲線の版を動かせば、今度は組み直す。
        const std::vector<float> other = tiltCurve(4.0f, -4.0f);
        writeSlot(&w->m.params[0], 200u, 2u, other.data(),
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, -3.0);
        caeq::pollSlot(&w->m.params[0], &st, &pl, true);
        drv.step();
        r.check(pl.rebuilds() == builds + 1u,
                "曲線の版が動けば組み直す (rebuilds %u -> %u)", builds, pl.rebuilds());
        delete w;
    }

    // --- 9. 曲線が載っていない枠 -------------------------------------------
    {
        ShmWithCanary* w = newCanaryShm();
        caeq::EqPipeline pl;
        pl.configure(48000.0, 2, caeq::Structure::kTdf2);
        pl.setClock(&fakeClock);
        pl.warmUp();
        caeq::PollState st;
        Driver drv(pl, 960, 2);

        // curve_gen == 0 = 曲線が載っていない。高精度を頼まれても biquad のまま。
        writeSlot(&w->m.params[0], 1u, 0u, nullptr,
                  CA_EQ_FLAG_ENABLED | CA_EQ_FLAG_HIGH_PRECISION, 0.0);
        for (int b = 0; b < 30; b++) {
            caeq::pollSlot(&w->m.params[0], &st, &pl, true);
            drv.step();
        }
        r.check(st.fir_requested && pl.firState() == caeq::EqPipeline::FirState::kBiquad &&
                    pl.rebuilds() == 0 && pl.curveGeneration() == 0,
                "curve_gen=0 の枠では、高精度を頼まれても biquad のまま (再構築 0 回)");

        // 診断がその理由を持っていること。
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

    // --- 9b. 理由の判定 — **上のものほど根本的** ----------------------------
    //
    // ⚠️ この製品で繰り返し出ている失敗が「嘘の理由が出る」形なので、順序を表で固定する。
    // 例: 作業領域が無いのに「曲線が届いていない」と出ると、モジュールの入れ直しから始まる。
    {
        ca_slot_t s{};
        ca_eq_slot_t q{};
        s.fir_state = CA_FIR_STATE_FIR;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kRunning, "鳴っていれば理由は要らない");

        s.fir_state = CA_FIR_STATE_BIQUAD;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNotRequested,
                "要求されていなければ「標準モード」(異常ではない)");

        // 以降は「1 つ手前まで満たしたうえで次だけ欠けている」形にして、順序を固定する。
        s.fir_flags = CA_FIR_F_REQUESTED;
        s.fir_design_failures = 3;   // **より下の理由を先に言わないこと**の見張り
        s.fir_fill = 0;
        s.fir_partitions = 9;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNoArena,
                "作業領域が無ければ、設計失敗や温め中より先に「作業領域が無い」");

        s.fir_flags |= CA_FIR_F_ARENA;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kBlockUnfit,
                "作業領域があってブロックが不適なら「ブロック長」");

        s.fir_flags |= CA_FIR_F_BLOCK_OK;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kNoCurve,
                "曲線が無ければ、設計失敗より先に「曲線が届いていない」");

        q.curve_gen = 5;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kDesignFailed,
                "曲線があって設計器が止まっていれば「設計器が止まった」");

        s.fir_design_failures = 0;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kWarming, "温め中なら「温めている最中」");

        s.fir_fill = s.fir_partitions;
        r.check(caeq::firWhy(s, &q) == caeq::FirWhy::kAlmost, "全部揃っていれば「まだ乗り移る前」");

        // 枠が未割り当て (params が無い) のインスタンスでも落ちない。
        s.fir_fill = 0;
        r.check(caeq::firWhy(s, nullptr) == caeq::FirWhy::kWarming,
                "パラメータ枠が無くても判定できる (曲線の有無だけ飛ばす)");
    }

    // --- 9c. 曲線のテキストの解析 (caeqset が使う) ---------------------------
    {
        std::vector<float> got(static_cast<size_t>(caeq::kCurvePoints) + 8, -999.0f);

        // ちょうどの点数 + 空行 + コメント + 前置きの空白。
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

        // 1 点足りない / 1 点多い。**どちらも失敗**で、足りない分を 0 dB で埋めない。
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

    // --- 10. 診断の写し取りが pipeline の値そのものであること -----------------
    {
        ShmWithCanary* w = newCanaryShm();
        caeq::EqPipeline pl;
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

        // 状態の番号がヘッダの表と一致していること (caeqstat がこの番号で文言を選ぶ)。
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

    // --- 10b. サンプルレートが変わっても FIR が戻ってくること -----------------
    //
    // ⚠️ **ここが繋がっていないと、EQ が黙って平坦に戻ったまま二度と戻らない。**
    // fs が変わると Eq はパラメータを丸ごと捨て (新しい Nyquist で fc が範囲外に
    // なりうるため)、arena も組み直しになって曲線が失われる。共有メモリの枠は
    // 何も変わっていないので、読み手が「適用済み」を覚えたままだと再送の機会が無い。
    // `.so` は SET_CONFIG で PollState::param_gen を 0 に戻してこれを塞いでいる。
    // **往復のどちらの側を外しても、この 1 件が落ちる** (変異試験で両方確認した)。
    {
        ShmWithCanary* w = newCanaryShm();
        caeq::EqPipeline pl;
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

        // SET_CONFIG が 44.1 kHz で来たときと同じ手順。**枠は 1 バイトも変わらない。**
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

    // --- 11. 申告するメモリ量が実測を上回っていること -------------------------
    {
        const double rates[] = {44100.0, 48000.0, 88200.0, 96000.0};
        size_t worst = 0;
        double worst_fs = 0.0;
        for (double fs : rates) {
            caeq::EqPipeline pl;
            pl.configure(fs, 2, caeq::Structure::kTdf2);
            if (pl.arenaBytes() > worst) { worst = pl.arenaBytes(); worst_fs = fs; }
        }
        // Eq 自身の状態と作業領域 (kMaxBands × kMaxChannels の double 2 面ほか)。
        const size_t eq_bytes = sizeof(caeq::Eq);
        const unsigned need = static_cast<unsigned>((worst + eq_bytes + 1023u) / 1024u);
        r.check(need <= caeq::kDeclaredMemoryKb,
                "descriptor の申告 %u KB >= 実測の最悪 %u KB (arena %zu KB @%.0f Hz + Eq %zu KB)",
                caeq::kDeclaredMemoryKb, need, worst / 1024, worst_fs, eq_bytes / 1024);
        // 12ch のインスタンスには作業領域を作らない (spatializer に 1 MB を持たせない)。
        caeq::EqPipeline wide;
        wide.configure(48000.0, 12, caeq::Structure::kTdf2);
        r.check(!wide.firAvailable() && wide.arenaBytes() == 0,
                "12ch のインスタンスには作業領域を作らない");
    }
}
