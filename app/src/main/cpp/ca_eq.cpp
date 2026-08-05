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
#include <unistd.h>
#include <android/log.h>
#include "aosp/audio_effect.h"
#include "ca_eq_shm.h"

#define CA_LOG_TAG "CodecAnchorEQ"
// ログは制御スレッド (create / SET_CONFIG / ENABLE / release) からだけ呼ぶ。
// process() からは絶対に呼ばない — オーディオスレッドで確保とロックが起きる。
#define CA_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  CA_LOG_TAG, __VA_ARGS__)
#define CA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CA_LOG_TAG, __VA_ARGS__)

namespace {

// eq-plan-1.md の識別子と 1 文字も違えないこと。
// C++ では指示付き初期化子の順序が宣言順に固定されるので、メンバの順序も守る。
const effect_descriptor_t kCaEqDescriptor = {
    /* type */        { 0x7a1c9f61, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    /* uuid */        { 0x7a1c9f60, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    /* apiVersion */  EFFECT_CONTROL_API_VERSION,
    // POST_PROC は AUDIO_SESSION_DEVICE に必須。INSERT_LAST は Dolby DAP / MiSound の後段に入るため。
    // DEVICE_IND はデバイスの変化を教えてもらうため。
    /* flags */       EFFECT_FLAG_TYPE_POST_PROC | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_DEVICE_IND,
    /* cpuLoad */     10,
    /* memoryUsage */ 1,
    /* name */        "Codec Anchor EQ",
    /* implementor */ "Codec Anchor",
};

struct CaCtx {
    // 先頭でなければならない (effect_handle_t がこのアドレスを指す)。
    // 仮想関数を持たせない — vtable ポインタが先頭に入って ABI が壊れる。
    const struct effect_interface_s* itfe;
    effect_config_t cfg;
    bool     enabled;
    bool     passthrough_only;   // format が float でないときに立つ。サンプルに触らない
    int32_t  gain_mb;            // millibel。0 = 素通し
    float    gain_lin;           // 10^(gain_mb / 2000)
    uint32_t channels;
    ca_slot_t* slot;             // 共有メモリのスロット。nullptr なら記録しない
};

uint32_t ca_channel_count(uint32_t mask) {
    // audio_channel_mask_t の下位ビットが 1 チャンネル 1 ビット。
    const uint32_t n = static_cast<uint32_t>(__builtin_popcount(mask & 0x3FFFFFFFu));
    return n ? n : 2;
}

float ca_mb_to_lin(int32_t mb) {
    // 10^(mb / 2000)。制御スレッドからしか呼ばれないので powf でよい。
    return __builtin_powf(10.0f, static_cast<float>(mb) / 2000.0f);
}

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

// process() から呼ばれる。確保・ロック・ログを一切しない。
// clock_gettime(CLOCK_MONOTONIC) は vDSO 経由でシステムコールにならない。
inline void ca_stats_add(CaCtx* c, size_t frames, float in_peak, float out_peak) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    ca_seq_begin(s);
    s->frames += static_cast<uint64_t>(frames);
    s->block_frames = static_cast<uint32_t>(frames);
    s->in_peak = in_peak;
    s->out_peak = out_peak;
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
    if (!c->enabled) return -ENODATA;   // 無効時の作法。AudioFlinger は呼ばない想定だが念のため

    const size_t frames = in->frameCount < out->frameCount ? in->frameCount : out->frameCount;
    if (frames == 0) return 0;
    const uint32_t ch = c->channels ? c->channels : 2;
    const size_t samples = frames * ch;
    float in_peak = 0.0f;
    float out_peak = 0.0f;

    if (c->passthrough_only) {
        // 何もしない。format が float でない = 1 フレームのバイト数が分からないので、コピーの
        // 長さを計算できない (samples * sizeof(float) は実サイズを超えて読み書きし、vendor HAL
        // ごと落とす)。AUDIO_SESSION_DEVICE は in-place で out には既に入力が入っているから、
        // 触らないことがそのまま素通しになる。仮に out-of-place で来ても、無音のほうが
        // SIGSEGV よりまし。
    } else {
        // 将来ここが biquad のカスケードになる。ライブラリは使わず、係数は RBJ の cookbook を
        // 自前で書く。リアルタイムのコールバックに外部依存を持ち込まない。
        // ピークはゲインの適用と同じ 1 パスで採る。加工が効いたかを外から見る唯一の手段なので、
        // 素通し (g==1) のときも測る。確保もロックもしないのでオーディオスレッドで許される。
        const float g = c->gain_lin;
        for (size_t i = 0; i < samples; i++) {
            const float x = in->f32[i];
            const float y = x * g;
            out->f32[i] = y;
            const float ax = x < 0.0f ? -x : x;
            const float ay = y < 0.0f ? -y : y;
            if (ax > in_peak) in_peak = ax;
            if (ay > out_peak) out_peak = ay;
        }
    }

    ca_stats_add(c, frames, in_peak, out_peak);
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
        c->passthrough_only = (c->cfg.outputCfg.format != AUDIO_FORMAT_PCM_FLOAT_U8);
        CA_LOGI("SET_CONFIG rate=%u ch=%u fmt=%u frames=%zu passthrough_only=%d",
                c->cfg.outputCfg.samplingRate, c->channels,
                static_cast<unsigned>(c->cfg.outputCfg.format),
                c->cfg.outputCfg.buffer.frameCount, c->passthrough_only);
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
        return 0;

    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (!intReply) return -EINVAL;
        c->enabled = (cmd == EFFECT_CMD_ENABLE);
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
        if (id != 1) { *static_cast<int*>(pReply) = -EINVAL; return 0; }
        // 安全装置: 正のゲインは構造的に受け付けない。ここを緩めない。
        // 実機で音を鳴らす実験の上限を、スクリプトではなくドライバ側で保証する。
        if (v > 0) v = 0;
        if (v < -6000) v = -6000;
        c->gain_mb = v;
        c->gain_lin = ca_mb_to_lin(v);
        CA_LOGI("SET_PARAM gain=%d mB (lin=%.6f)", c->gain_mb, static_cast<double>(c->gain_lin));
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
const struct effect_interface_s kCaEqInterface = {
    /* process */         ca_process,
    /* command */         ca_command,
    /* get_descriptor */  ca_get_descriptor,
    /* process_reverse */ nullptr,
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
    c->gain_lin = 1.0f;
    c->channels = 2;
    c->slot = nullptr;
    ca_stats_attach(c, ioId);
    CA_LOGI("create session=%d io=%d ctx=%p", sessionId, ioId, static_cast<void*>(c));
    *pHandle = reinterpret_cast<effect_handle_t>(c);
    return 0;
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
audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    /* tag */            AUDIO_EFFECT_LIBRARY_TAG,
    /* version */        EFFECT_LIBRARY_API_VERSION_3_0,
    /* name */           "Codec Anchor EQ Library",
    /* implementor */    "Codec Anchor",
    /* create_effect */  ca_lib_create,
    /* release_effect */ ca_lib_release,
    /* get_descriptor */ ca_lib_get_descriptor,
};
