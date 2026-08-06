// Codec Anchor の音響処理エフェクト。legacy (HIDL) の C ABI で vendor の audio HAL に読まれる。
#include <atomic>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <new>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <android/log.h>
#include "aosp/audio_effect.h"
#include "ca_eq_shm.h"
#include "dsp/ca_eq_dsp.h"
#include "dsp/ca_eq_params.h"

#define CA_LOG_TAG "CodecAnchorEQ"
// ログは制御スレッド (create / SET_CONFIG / ENABLE / release) からだけ呼ぶ。
// process() からは絶対に呼ばない — オーディオスレッドで確保とロックが起きる。
#define CA_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  CA_LOG_TAG, __VA_ARGS__)
#define CA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CA_LOG_TAG, __VA_ARGS__)

namespace {

// eq-plan-1.md の識別子と 1 文字も違えないこと。
// **ABI に依存する構造体は位置で初期化しない。**フィールド名で書けば、ヘッダの並びが
// 変わっても値が別のフィールドに落ちない (並びが食い違えばコンパイルエラーになる)。
const effect_descriptor_t kCaEqDescriptor = {
    .type        = { 0x7a1c9f61, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    .uuid        = { 0x7a1c9f60, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    .apiVersion  = EFFECT_CONTROL_API_VERSION,
    // POST_PROC は AUDIO_SESSION_DEVICE に必須。INSERT_LAST は Dolby DAP / MiSound の後段に入るため。
    // DEVICE_IND はデバイスの変化を教えてもらうため。
    .flags       = EFFECT_FLAG_TYPE_POST_PROC | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_DEVICE_IND,
    .cpuLoad     = 10,
    // KB 単位。caeq::Eq が状態と作業領域で 40 KB ほど持つ (最大構成を create の時点で
    // 確保して process() で一切確保しないため)。
    .memoryUsage = 64,
    .name        = "Codec Anchor EQ",
    .implementor = "Codec Anchor",
};

struct CaCtx {
    // 先頭でなければならない (effect_handle_t がこのアドレスを指す)。
    // 仮想関数を持たせない — vtable ポインタが先頭に入って ABI が壊れる。
    // caeq::Eq も仮想関数を持たないが、念のため itfe より後ろに置く。
    const struct effect_interface_s* itfe;
    effect_config_t cfg;
    bool     enabled;
    bool     passthrough_only;   // format が float でないときに立つ。サンプルに触らない
    int32_t  gain_mb;            // millibel。0 = 素通し
    uint32_t channels;
    ca_slot_t* slot;             // 共有メモリのスロット。nullptr なら記録しない
    // 読みに行くパラメータ枠。**割り当てられていないあいだは何も適用せず素通しする** —
    // 「たぶん自分宛て」で読むと、2 台目のイヤホンに 1 台目の設定が掛かる。
    uint32_t   param_slot;
    uint32_t   param_gen;        // 最後に適用した generation
    uint32_t   param_rejected;
    bool       user_enabled;     // 共有メモリ側の on/off。framework の ENABLE とは別
    caeq::Eq dsp;                // 演算本体。ホストのハーネスで検証してあるものと同じコード
};

// 1 ブロックぶんの計測。process() のスタックに置くだけで、確保はしない。
struct CaBlock {
    const void* in_addr;
    const void* out_addr;
    uint32_t in_frames;
    uint32_t out_frames;
    uint32_t samples;
    float    in_peak_f;
    float    out_peak_f;
    uint32_t in_peak_i;
    uint32_t out_peak_i;
};

// INT32_MIN の符号反転が int32 に収まらないので uint32 で受ける。
inline uint32_t ca_abs_i32(int32_t v) {
    return v < 0 ? static_cast<uint32_t>(-static_cast<int64_t>(v)) : static_cast<uint32_t>(v);
}

// 同じバッファを float と int32 の 2 通りに解釈して測る。**ポインタを付け替えないこと** —
// float* と int32_t* は別の型なので、strict aliasing のもとでは最適化が読みと書きを
// 入れ替えてよい。計測が唯一の観測点である以上、ここが嘘をつくのは重い。
// ビット列を memcpy で写せば、同じ値を見ていることが規格で保証される。
inline uint32_t ca_abs_bits(float v) {
    int32_t i;
    std::memcpy(&i, &v, sizeof(i));
    return ca_abs_i32(i);
}

uint32_t ca_channel_count(uint32_t mask) {
    // audio_channel_mask_t の下位ビットが 1 チャンネル 1 ビット。
    const uint32_t n = static_cast<uint32_t>(__builtin_popcount(mask & 0x3FFFFFFFu));
    return n ? n : 2;
}

// 実測でブロック長は 512 / 960 / 1024 / 2048、チャンネル数は 2 と 12 が来る。
// 決め打ちできないので SET_CONFIG の値をそのまま演算層へ渡す。
// 構造の選択の根拠は dsp/ca_eq_dsp.h の Structure にある (ハーネスの実測)。
constexpr caeq::Structure kStructure = caeq::kDefaultStructure;

// ---------------------------------------------------------------------------
// 共有メモリの統計。フレームワークには実処理フレーム数を外へ出す経路が無いので、
// 「本当に音声経路に入ったか」を見る唯一の観測点になる。
//
// mmap するのは制御スレッド (create_effect) だけ。process() は既に開いてある
// ポインタへ書くだけで、確保もロックもログもしない。
// ---------------------------------------------------------------------------

// in_use は .so 側の CAS とリーダの素読みで共有する。std::atomic を被せて使うので、
// 同じ大きさで、かつロックを持たないことを確かめておく (ロック付きだと別プロセスから読めない)。
static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t), "atomic が同じ大きさでない");
static_assert(std::atomic<uint32_t>::is_always_lock_free, "atomic がロックを使う");

ca_shm_t*        g_shm = nullptr;
std::atomic<int> g_shm_tried{0};

void ca_stats_open() {
    if (g_shm_tried.exchange(1)) return;   // 1 プロセスに 1 回だけ
    const int fd = open(CA_SHM_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        // 開けなくても絶対に落ちない。この 1 行が唯一の手がかりになる。
        CA_LOGE("stats open failed: %s (errno=%d)", CA_SHM_PATH, errno);
        return;
    }
    // **大きさを確かめてから map する。**短いファイルを map して後ろを触ると SIGBUS で
    // vendor の audio HAL ごと落ちる (= 端末が無音になる)。版 1 の 1152 バイトのまま
    // 残っている環境が実際にありうるので、ここは省けない。
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<uint64_t>(st.st_size) < sizeof(ca_shm_t)) {
        CA_LOGE("stats file too small: %lld < %zu — module/post-fs-data.sh が古い",
                fstat(fd, &st) == 0 ? static_cast<long long>(st.st_size) : -1LL,
                sizeof(ca_shm_t));
        close(fd);
        return;
    }
    void* p = mmap(nullptr, sizeof(ca_shm_t), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { CA_LOGE("stats mmap failed errno=%d", errno); return; }
    g_shm = static_cast<ca_shm_t*>(p);
    g_shm->magic = CA_SHM_MAGIC;
    g_shm->version = CA_SHM_VERSION;
    g_shm->slot_count = CA_SHM_SLOTS;
    g_shm->slot_size = static_cast<uint32_t>(sizeof(ca_slot_t));
    CA_LOGI("stats mapped at %p pid=%d", p, static_cast<int>(getpid()));
}

// seqlock。書く前に奇数、書き終えたら偶数にする。読み手は前後で同じ偶数を見たら採用する。
inline void ca_seq_begin(ca_slot_t* s) {
    s->seq++;
    std::atomic_thread_fence(std::memory_order_release);
}

inline void ca_seq_end(ca_slot_t* s) {
    std::atomic_thread_fence(std::memory_order_release);
    s->seq++;
}

inline std::atomic<uint32_t>* ca_in_use(ca_slot_t* s) {
    return reinterpret_cast<std::atomic<uint32_t>*>(&s->in_use);
}

void ca_stats_attach(CaCtx* c, int32_t ioId) {
    ca_stats_open();
    c->slot = nullptr;
    if (g_shm == nullptr) return;
    for (int i = 0; i < CA_SHM_SLOTS; i++) {
        ca_slot_t* s = &g_shm->slots[i];
        uint32_t expected = 0;
        if (ca_in_use(s)->compare_exchange_strong(expected, CA_SHM_MAGIC)) {
            // ここに来た時点で中身はゼロ (ca_stats_detach の不変条件)。念のため書き直すが、
            // ゼロ化を detach 側に置いてあることが、リーダの偽陽性を防いでいる本体。
            s->seq = 0; s->frames = 0; s->sample_rate = 0; s->channels = 0;
            s->block_frames = 0; s->state = 0; s->gain_mb = 0;
            s->io_id = ioId;
            s->pid = static_cast<uint64_t>(getpid());
            s->ctx = reinterpret_cast<uint64_t>(c);
            s->last_ns = 0;
            c->slot = s;
            // 既定では自分が取った枠と同じ添字のパラメータを読む。書き手は統計側の
            // io_id / ctx を見てどの枠がどのイヤホンかを決められるので、これで足りる。
            // SET_PARAM (id=2) が来たらそちらで上書きする。
            c->param_slot = static_cast<uint32_t>(i);
            s->param_slot = c->param_slot;
            CA_LOGI("stats slot %d taken io=%d ctx=%p", i, ioId, static_cast<void*>(c));
            return;
        }
    }
    CA_LOGE("stats: no free slot");
}

// 不変条件: in_use == 0 のスロットは、中身も必ずゼロ。
//
// **この順序を入れ替えないこと。**先に in_use を落とすと、次に attach した側が CAS を通してから
// フィールドを初期化するまでの隙に、リーダが前のインスタンスの frames を読む。それは
// 「カウンタが進んでいる」という偽陽性になり、この実証の結論そのものを壊す
// (処理フレーム数が唯一の観測点なので、そこに嘘が混じる経路を作らない)。
void ca_stats_detach(CaCtx* c) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    c->slot = nullptr;
    // in_use 以外を全部ゼロにする。フィールドを足したときに消し忘れないよう memset で消す。
    std::memset(&s->seq, 0, sizeof(ca_slot_t) - offsetof(ca_slot_t, seq));
    std::atomic_thread_fence(std::memory_order_release);
    ca_in_use(s)->store(0, std::memory_order_release);
}

void ca_stats_configure(CaCtx* c) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    ca_seq_begin(s);
    s->sample_rate = c->cfg.outputCfg.samplingRate;
    s->channels = c->channels;
    s->state |= CA_STATE_CONFIGURED;
    if (c->passthrough_only) s->state |= CA_STATE_PASSTHROUGH_ONLY;
    else                     s->state &= ~CA_STATE_PASSTHROUGH_ONLY;
    ca_seq_end(s);
}

void ca_stats_set_enabled(CaCtx* c, bool enabled) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    ca_seq_begin(s);
    if (enabled) s->state |= CA_STATE_ENABLED;
    else         s->state &= ~CA_STATE_ENABLED;
    ca_seq_end(s);
}

void ca_stats_set_gain(CaCtx* c, int32_t gain_mb) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    ca_seq_begin(s);
    s->gain_mb = gain_mb;
    ca_seq_end(s);
}

// ---------------------------------------------------------------------------
// パラメータの読み出し。統計とは向きが逆で、書き手が外・読み手がここ。
// ---------------------------------------------------------------------------

// seqlock の読み手と並びの変換は dsp/ca_eq_params.h。**書き手と同じ定義を共有していて、
// ホストのハーネスが書き手のスレッドを立てて千切れた読みが起きないことを確かめている。**

// process() の先頭で 1 回だけ呼ぶ。**ブロックの途中で読み直さない** —
// 取り込みを process() 1 回につき 1 回に縛ることが、係数の変調速度に構造的な上限を
// 与えている (dsp/ca_eq_dsp.h の setParams を参照)。
void ca_params_poll(CaCtx* c) {
    if (g_shm == nullptr || c->param_slot >= CA_SHM_SLOTS) return;
    const ca_eq_slot_t* src = &g_shm->params[c->param_slot];

    // 世代が動いていなければ何もしない。ここは目安なので素で読んでよい
    // (途中まで書かれた並びを掴んでも、下の seqlock が弾く)。
    const uint32_t gen = __atomic_load_n(&src->generation, __ATOMIC_RELAXED);
    if (gen == 0 || gen == c->param_gen) return;

    ca_eq_slot_t snap;
    if (!caeq::paramsRead(src, &snap)) return;   // 掴めなければ次のブロックで
    if (snap.generation == 0 || snap.generation == c->param_gen) return;

    caeq::Params p;
    if (!caeq::paramsConvert(snap, &p) || !c->dsp.setParams(p)) {
        // **丸ごと捨てて前の設定を保つ。**部分適用はしない。
        // 同じ世代を毎ブロック試し直さないよう、捨てた世代も覚える。
        c->param_gen = snap.generation;
        c->param_rejected++;
        return;
    }
    c->param_gen = snap.generation;
    c->user_enabled = (snap.flags & CA_EQ_FLAG_ENABLED) != 0;
    c->dsp.setActive(c->enabled && c->user_enabled);
    // 統計への転記は ca_stats_add が seqlock の中でまとめてやる。
}

// process() から呼ばれる。確保・ロック・ログを一切しない。
// clock_gettime(CLOCK_MONOTONIC) は vDSO 経由でシステムコールにならない。
inline void ca_stats_add(CaCtx* c, size_t frames, const CaBlock& b) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    ca_seq_begin(s);
    s->frames += static_cast<uint64_t>(frames);
    s->block_frames = static_cast<uint32_t>(frames);
    s->in_peak = b.in_peak_f;
    s->out_peak = b.out_peak_f;
    s->in_peak_i32 = b.in_peak_i;
    s->out_peak_i32 = b.out_peak_i;
    s->in_addr = reinterpret_cast<uint64_t>(b.in_addr);
    s->out_addr = reinterpret_cast<uint64_t>(b.out_addr);
    s->dbg_in_frames = b.in_frames;
    s->dbg_out_frames = b.out_frames;
    s->dbg_samples = b.samples;
    s->dbg_fmt = c->cfg.outputCfg.format;
    s->param_slot = c->param_slot;
    s->param_gen = c->param_gen;
    s->param_rejected = c->param_rejected;
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    s->last_ns = static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
                 static_cast<uint64_t>(ts.tv_nsec);
    ca_seq_end(s);
}

}  // namespace

extern "C" int32_t ca_process(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    CaCtx* c = reinterpret_cast<CaCtx*>(self);
    // 落ちたら vendor HAL ごと死ぬ。毎回すべて検査する。
    if (c == nullptr || in == nullptr || out == nullptr) return -EINVAL;
    if (in->raw == nullptr || out->raw == nullptr) return -EINVAL;
    // **無効になっても即座には抜けない。**DISABLE のあともフレームワークは 10 秒ほど
    // process() を呼び続けるので、そのあいだにフェードを終わらせる。ここで -ENODATA を
    // 返すと呼ばれなくなり、フェードが途中で切れて -26.7 dBFS のクリックが出る (実測)。
    if (!c->enabled && c->dsp.idle()) return -ENODATA;

    const size_t frames = in->frameCount < out->frameCount ? in->frameCount : out->frameCount;
    if (frames == 0) return 0;
    const uint32_t ch = c->channels ? c->channels : 2;
    const size_t samples = frames * ch;
    CaBlock blk = {};
    blk.in_addr = in->raw;
    blk.out_addr = out->raw;
    blk.in_frames = static_cast<uint32_t>(in->frameCount);
    blk.out_frames = static_cast<uint32_t>(out->frameCount);
    blk.samples = static_cast<uint32_t>(samples);

    if (c->passthrough_only) {
        // 何もしない。format が float でない = 1 フレームのバイト数が分からないので、コピーの
        // 長さを計算できない (samples * sizeof(float) は実サイズを超えて読み書きし、vendor HAL
        // ごと落とす)。AUDIO_SESSION_DEVICE は in-place で out には既に入力が入っているから、
        // 触らないことがそのまま素通しになる。仮に out-of-place で来ても、無音のほうが
        // SIGSEGV よりまし。
    } else {
        // 1 パス目は入力を読むだけ。in-place だと書きながら読むことになり、加工後の値を
        // 「入力」として測ってしまう。float と int32 の両方で測り、どちらの解釈が
        // 本物かを外から判定できるようにする。
        for (size_t i = 0; i < samples; i++) {
            const float x = in->f32[i];
            const float ax = x < 0.0f ? -x : x;
            if (ax > blk.in_peak_f) blk.in_peak_f = ax;
            const uint32_t ai = ca_abs_bits(x);
            if (ai > blk.in_peak_i) blk.in_peak_i = ai;
        }

        // 設定の取り込みはここ 1 回だけ。ブロックの途中では読み直さない。
        ca_params_poll(c);

        // 2 パス目は演算層に任せる。ここから先はホストのハーネスで検証済みのコード
        // (app/src/main/cpp/dsp/)。確保もロックもログもしない。
        const bool accumulate =
            c->cfg.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
        c->dsp.process(in->f32, out->f32, static_cast<int>(frames), accumulate);

        // 3 パス目で出力を測る。
        for (size_t i = 0; i < samples; i++) {
            const float y = out->f32[i];
            const float ay = y < 0.0f ? -y : y;
            if (ay > blk.out_peak_f) blk.out_peak_f = ay;
            const uint32_t ao = ca_abs_bits(y);
            if (ao > blk.out_peak_i) blk.out_peak_i = ao;
        }
    }

    ca_stats_add(c, frames, blk);
    return 0;
}

extern "C" int32_t ca_command(effect_handle_t self, uint32_t cmd, uint32_t cmdSize, void* pCmd,
                              uint32_t* replySize, void* pReply) {
    CaCtx* c = reinterpret_cast<CaCtx*>(self);
    if (c == nullptr) return -EINVAL;

    const bool intReply = (pReply != nullptr && replySize != nullptr && *replySize == sizeof(int));

    switch (cmd) {
    case EFFECT_CMD_INIT:
        if (!intReply) return -EINVAL;
        *static_cast<int*>(pReply) = 0;
        return 0;

    case EFFECT_CMD_SET_CONFIG: {
        if (pCmd == nullptr || cmdSize != sizeof(effect_config_t)) return -EINVAL;
        if (!intReply) return -EINVAL;
        std::memcpy(&c->cfg, pCmd, sizeof(effect_config_t));
        c->channels = ca_channel_count(c->cfg.outputCfg.channels);
        // 想定は float 固定。違ったらサンプルに触らない側へ倒す (エラーは返さない — 返すと
        // AudioFlinger がこのインスタンスを諦めて、何が起きたか分からなくなる)。
        // 演算層が持てるチャンネル数を超えたときも同じ扱いにする。演算層は自分の上限まで
        // しか回さないので、そのまま通すと後ろのチャンネルだけ素通しになって並びも狂う。
        c->passthrough_only = (c->cfg.outputCfg.format != AUDIO_FORMAT_PCM_FLOAT_U8) ||
                              (c->channels > static_cast<uint32_t>(caeq::kMaxChannels));
        // buffer.frameCount は常に 0 で来る (バッファは process() の引数で渡される)。
        // ここを DSP の作業領域の大きさに使うと 0 で組んでしまうので、載せない。
        CA_LOGI("SET_CONFIG rate=%u ch=%u fmt=%u access=%u passthrough_only=%d",
                c->cfg.outputCfg.samplingRate, c->channels,
                static_cast<unsigned>(c->cfg.outputCfg.format),
                static_cast<unsigned>(c->cfg.outputCfg.accessMode), c->passthrough_only);
        c->dsp.configure(static_cast<double>(c->cfg.outputCfg.samplingRate),
                         static_cast<int>(c->channels), kStructure);
        ca_stats_configure(c);
        *static_cast<int*>(pReply) = 0;
        return 0;
    }

    case EFFECT_CMD_GET_CONFIG:
        if (pReply == nullptr || replySize == nullptr || *replySize != sizeof(effect_config_t))
            return -EINVAL;
        std::memcpy(pReply, &c->cfg, sizeof(effect_config_t));
        return 0;

    case EFFECT_CMD_RESET:
        // 状態をゼロにしてよいのはここと ENABLE のときだけ。パラメータの変更のたびに
        // ゼロにするとクリックが 27 dB 悪化する (実測 -64.6 → -37.7 dBFS)。
        c->dsp.reset();
        return 0;

    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (!intReply) return -EINVAL;
        c->enabled = (cmd == EFFECT_CMD_ENABLE);
        if (c->enabled) c->dsp.reset();
        // 有効になるのは framework とユーザ設定の両方が有効なときだけ。
        // **どちらの側の OFF でも、素通しになるまでフェードを掛けてから止まる。**
        c->dsp.setActive(c->enabled && c->user_enabled);
        CA_LOGI("%s ctx=%p", c->enabled ? "ENABLE" : "DISABLE", static_cast<void*>(c));
        ca_stats_set_enabled(c, c->enabled);
        *static_cast<int*>(pReply) = 0;
        return 0;

    case EFFECT_CMD_SET_PARAM: {
        if (pCmd == nullptr || cmdSize < sizeof(effect_param_t)) return -EINVAL;
        if (!intReply) return -EINVAL;
        const effect_param_t* pp = static_cast<const effect_param_t*>(pCmd);
        if (pp->psize != sizeof(int32_t) || pp->vsize != sizeof(int32_t)) {
            *static_cast<int*>(pReply) = -EINVAL;
            return 0;
        }
        // value は param の直後ではなく、sizeof(int) 境界に切り上げた位置。
        const size_t voff = ((pp->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
        if (cmdSize < sizeof(effect_param_t) + voff + pp->vsize) return -EINVAL;
        int32_t id = 0;
        int32_t v = 0;
        std::memcpy(&id, pp->data, sizeof(id));
        std::memcpy(&v, pp->data + voff, sizeof(v));
        if (id == CA_PARAM_ID_SLOT) {
            // 経路の確立。**「お前が読むのは枠 N だ」を 1 回だけ受ける。**
            // 以後の更新は共有メモリの seqlock で、ここは通らない。
            if (v < 0 || v >= CA_SHM_SLOTS) {
                CA_LOGE("SET_PARAM slot=%d は範囲外", v);
                *static_cast<int*>(pReply) = -EINVAL;
                return 0;
            }
            c->param_slot = static_cast<uint32_t>(v);
            c->param_gen = 0;   // 新しい枠なので、次のブロックで読み直す
            CA_LOGI("SET_PARAM slot=%d ctx=%p", v, static_cast<void*>(c));
            *static_cast<int*>(pReply) = 0;
            return 0;
        }
        if (id != CA_PARAM_ID_GAIN) { *static_cast<int*>(pReply) = -EINVAL; return 0; }
        // 実証用のゲイン。共有メモリの経路が通ったあとも、`.so` だけを単体で動かして
        // 音が変わることを確かめる手段として残す。
        // 安全装置: 正のゲインは構造的に受け付けない。ここを緩めない。
        // 実機で音を鳴らす実験の上限を、スクリプトではなくドライバ側で保証する。
        if (v > 0) v = 0;
        // 下限は演算層のプリアンプの下限 (-40 dB) と揃える。ここだけ深くしても弾かれる。
        if (v < -4000) v = -4000;
        c->gain_mb = v;
        caeq::Params p;
        p.band_count = 0;
        p.preamp_db = static_cast<double>(v) / 100.0;
        if (!c->dsp.setParams(p)) {
            // 検査に落ちたときは前の設定のまま鳴らし続ける。黙って捨てると
            // 原因不明の「効かない」になるので、診断カウンタを見せる。
            CA_LOGE("SET_PARAM rejected gain=%d mB (rejected=%u)", v, c->dsp.rejectedCount());
            *static_cast<int*>(pReply) = -EINVAL;
            return 0;
        }
        // 明示的に指示された以上「宛てられた」とみなす。共有メモリを使わずに
        // この経路だけで実証するときに、ここが無いと素通しのままになる。
        c->user_enabled = true;
        c->dsp.setActive(c->enabled);
        CA_LOGI("SET_PARAM gain=%d mB (preamp=%.2f dB)", c->gain_mb, p.preamp_db);
        ca_stats_set_gain(c, c->gain_mb);
        *static_cast<int*>(pReply) = 0;
        return 0;
    }

    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
        // 受け取るが何もしない。エラーを返すと AudioFlinger が警告を出すだけで実害は無いが、
        // ログが埋まって実証の観測が濁るので 0 を返す。
        return 0;

    default:
        return -EINVAL;
    }
}

extern "C" int32_t ca_get_descriptor(effect_handle_t self, effect_descriptor_t* pDesc) {
    if (self == nullptr || pDesc == nullptr) return -EINVAL;
    *pDesc = kCaEqDescriptor;
    return 0;
}

namespace {
// 関数ポインタ表。位置を 1 つずらすと別の関数が呼ばれるので、必ずフィールド名で書く。
const struct effect_interface_s kCaEqInterface = {
    .process         = ca_process,
    .command         = ca_command,
    .get_descriptor  = ca_get_descriptor,
    .process_reverse = nullptr,
};
}  // namespace

extern "C" int32_t ca_lib_create(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                                 effect_handle_t* pHandle) {
    if (pHandle == nullptr || uuid == nullptr) return -EINVAL;
    if (std::memcmp(uuid, &kCaEqDescriptor.uuid, sizeof(effect_uuid_t)) != 0) return -EINVAL;

    // -fno-exceptions なので nothrow 版を使う。失敗は nullptr で返る。
    CaCtx* c = new (std::nothrow) CaCtx{};
    if (c == nullptr) return -ENOMEM;
    c->itfe = &kCaEqInterface;
    c->enabled = false;
    c->passthrough_only = false;
    c->gain_mb = 0;
    c->channels = 2;
    c->slot = nullptr;
    // 枠を宛てられるまでパラメータを一切適用しない。ca_stats_attach が枠を取れたら
    // その添字が入る。
    c->param_slot = CA_PARAM_SLOT_NONE;
    c->param_gen = 0;
    c->param_rejected = 0;
    c->user_enabled = false;
    c->dsp.configure(48000.0, 2, kStructure);
    // 初回のページフォルトと係数の初期化を process() の外へ出す。
    c->dsp.warmUp();
    ca_stats_attach(c, ioId);
    CA_LOGI("create session=%d io=%d ctx=%p", sessionId, ioId, static_cast<void*>(c));
    *pHandle = reinterpret_cast<effect_handle_t>(c);
    return 0;
}

// AUDIO_SESSION_DEVICE のときに呼ばれる 3.1 の入口。
// deviceId は捨てる — SW の device effect には AUDIO_PORT_HANDLE_NONE (0) が literal で渡され、
// どのイヤホンかは分からない。デバイスごとの設定は共有メモリで運ぶ前提のまま。
extern "C" int32_t ca_lib_create_3_1(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                                     int32_t deviceId, effect_handle_t* pHandle) {
    CA_LOGI("create_3_1 session=%d io=%d device=%d", sessionId, ioId, deviceId);
    return ca_lib_create(uuid, sessionId, ioId, pHandle);
}

extern "C" int32_t ca_lib_release(effect_handle_t handle) {
    CaCtx* c = reinterpret_cast<CaCtx*>(handle);
    if (c == nullptr) return -EINVAL;
    CA_LOGI("release ctx=%p", static_cast<void*>(c));
    ca_stats_detach(c);
    delete c;
    return 0;
}

extern "C" int32_t ca_lib_get_descriptor(const effect_uuid_t* uuid, effect_descriptor_t* pDesc) {
    if (pDesc == nullptr || uuid == nullptr) return -EINVAL;
    if (std::memcmp(uuid, &kCaEqDescriptor.uuid, sizeof(effect_uuid_t)) != 0) return -EINVAL;
    *pDesc = kCaEqDescriptor;
    return 0;
}

// このシンボル名は固定。ローダはこの名前 (AELI) だけを dlsym する。
// extern "C" と visibility("default") の両方が要る。
extern "C" __attribute__((visibility("default")))
// version と create_effect_3_1 は必ずセットで動かす。
// 3.1 を名乗ると doEffectCreate が NULL チェックなしで create_effect_3_1 を呼ぶので、
// 片方だけ変えると HAL が落ちて音が全く出なくなる。
// ここもフィールド名で書く — 位置を 1 つ間違えるだけで同じ事故になる。
audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag               = AUDIO_EFFECT_LIBRARY_TAG,
    .version           = EFFECT_LIBRARY_API_VERSION_3_1,
    .name              = "Codec Anchor EQ Library",
    .implementor       = "Codec Anchor",
    .create_effect     = ca_lib_create,
    .release_effect    = ca_lib_release,
    .get_descriptor    = ca_lib_get_descriptor,
    .create_effect_3_1 = ca_lib_create_3_1,
};
