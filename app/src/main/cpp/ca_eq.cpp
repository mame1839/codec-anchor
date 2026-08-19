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
#include "ca_eq_pick.h"
#include "ca_eq_proc.h"
#include "ca_eq_shm.h"
#include "dsp/ca_eq_dsp.h"
#include "dsp/ca_eq_params.h"
#include "dsp/ca_eq_pipeline.h"
#include "dsp/ca_eq_poll.h"
#include "dsp/ca_eq_stats.h"

#define CA_LOG_TAG "CodecAnchorEQ"
#define CA_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  CA_LOG_TAG, __VA_ARGS__)
#define CA_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CA_LOG_TAG, __VA_ARGS__)

namespace {

const effect_descriptor_t kCaEqDescriptor = {
    .type        = { 0x7a1c9f61, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    .uuid        = { 0x7a1c9f60, 0x4a2e, 0x4f6b, 0x9d21, { 0x0a, 0x5c, 0x1b, 0x3e, 0x77, 0xd1 } },
    .apiVersion  = EFFECT_CONTROL_API_VERSION,
    .flags       = EFFECT_FLAG_TYPE_POST_PROC | EFFECT_FLAG_INSERT_LAST | EFFECT_FLAG_DEVICE_IND,
    .cpuLoad     = 10,
    .memoryUsage = caeq::kDeclaredMemoryKb,
    .name        = "Codec Anchor EQ",
    .implementor = "Codec Anchor",
};

struct CaCtx {
    const struct effect_interface_s* itfe;
    effect_config_t cfg;
    bool     enabled;
    bool     passthrough_only;
    int32_t  gain_mb;
    uint32_t channels;
    ca_slot_t* slot;
    uint32_t   param_slot;
    int32_t  session_id;
    int32_t  io_id;
    caeq::PollState poll;
    caeq::EqPipeline dsp;
};

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

inline uint32_t ca_abs_i32(int32_t v) {
    return v < 0 ? static_cast<uint32_t>(-static_cast<int64_t>(v)) : static_cast<uint32_t>(v);
}

inline uint32_t ca_abs_bits(float v) {
    int32_t i;
    std::memcpy(&i, &v, sizeof(i));
    return ca_abs_i32(i);
}

uint32_t ca_channel_count(uint32_t mask) {
    const uint32_t n = static_cast<uint32_t>(__builtin_popcount(mask & 0x3FFFFFFFu));
    return n ? n : 2;
}

constexpr caeq::Structure kStructure = caeq::kDefaultStructure;

static_assert(sizeof(std::atomic<uint32_t>) == sizeof(uint32_t), "atomic が同じ大きさでない");
static_assert(std::atomic<uint32_t>::is_always_lock_free, "atomic がロックを使う");

ca_shm_t* g_shm = nullptr;

enum { kShmIdle = 0, kShmOpening = 1, kShmDone = 2 };
std::atomic<int> g_shm_state{kShmIdle};

constexpr int    kShmWaitSteps = 500;
constexpr long   kShmWaitNs    = 200000;

void ca_stats_open_locked();

void ca_stats_open() {
    if (g_shm_state.load(std::memory_order_acquire) == kShmDone) return;
    int expected = kShmIdle;
    if (g_shm_state.compare_exchange_strong(expected, kShmOpening)) {
        ca_stats_open_locked();
        g_shm_state.store(kShmDone, std::memory_order_release);
        return;
    }
    for (int i = 0; i < kShmWaitSteps; i++) {
        if (g_shm_state.load(std::memory_order_acquire) == kShmDone) return;
        struct timespec ts = {0, kShmWaitNs};
        nanosleep(&ts, nullptr);
    }
    CA_LOGE("stats open wait timed out");
}

void ca_stats_open_locked() {
    const int fd = open(CA_SHM_PATH, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
        CA_LOGE("stats open failed: %s (errno=%d)", CA_SHM_PATH, errno);
        return;
    }
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
    g_shm->version = CA_SHM_VERSION;
    g_shm->slot_count = CA_SHM_SLOTS;
    g_shm->slot_size = static_cast<uint32_t>(sizeof(ca_slot_t));
    g_shm->param_slot_size = static_cast<uint32_t>(sizeof(ca_eq_slot_t));
    g_shm->curve_points = static_cast<uint32_t>(caeq::kCurvePoints);
    std::atomic_thread_fence(std::memory_order_release);
    g_shm->magic = CA_SHM_MAGIC;
    CA_LOGI("stats mapped at %p pid=%d", p, static_cast<int>(getpid()));
}

inline void ca_seq_begin(ca_slot_t* s) { caeq::statsBeginWrite(s); }

inline void ca_seq_end(ca_slot_t* s) { caeq::statsEndWrite(s); }

bool ca_stats_take_slot(CaCtx* c, bool reclaim) {
    if (g_shm == nullptr) return false;
    const uint64_t self = static_cast<uint64_t>(getpid());
    const uint32_t i = caeq::acquireSlot(g_shm, reclaim ? caeq::procPidAlive : nullptr, nullptr,
                                         self);
    if (i == caeq::kNoSlot) return false;
    ca_slot_t* s = &g_shm->slots[i];
    s->seq = 0; s->frames = 0; s->sample_rate = 0; s->channels = 0;
    s->block_frames = 0; s->state = 0; s->gain_mb = 0;
    s->io_id = c->io_id;
    c->param_slot = caeq::paramSlotAfterAttach(c->param_slot, i);
    s->session_id = c->session_id;
    s->param_slot = c->param_slot;
    s->pid = self;
    s->ctx = reinterpret_cast<uint64_t>(c);
    s->last_ns = 0;
    c->slot = s;
    return true;
}

void ca_stats_attach(CaCtx* c) {
    ca_stats_open();
    c->slot = nullptr;
    if (!ca_stats_take_slot(c, true)) {
        CA_LOGE("stats: no free slot (session=%d io=%d)", c->session_id, c->io_id);
        return;
    }
    CA_LOGI("stats slot %u taken session=%d io=%d param_slot=%u ctx=%p",
            static_cast<uint32_t>(c->slot - g_shm->slots), c->session_id, c->io_id,
            c->param_slot, static_cast<void*>(c));
}

void ca_stats_detach(CaCtx* c) {
    ca_slot_t* s = c->slot;
    if (s == nullptr) return;
    c->slot = nullptr;
    if (s->ctx != reinterpret_cast<uint64_t>(c) || s->pid != static_cast<uint64_t>(getpid())) {
        CA_LOGE("stats: slot no longer ours (ctx=%p) — 触らない", static_cast<void*>(c));
        return;
    }
    caeq::releaseSlot(s);
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

inline void ca_params_poll(CaCtx* c) {
    if (g_shm == nullptr) return;
    if (c->param_slot >= static_cast<uint32_t>(CA_SHM_SLOTS)) return;
    caeq::pollSlot(&g_shm->params[c->param_slot], &c->poll, &c->dsp, c->enabled);
}

uint64_t ca_now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ull +
           static_cast<uint64_t>(ts.tv_nsec);
}

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
    s->param_gen = c->poll.param_gen;
    s->param_rejected = c->poll.rejected;
    caeq::firStatsOf(c->poll, c->dsp, s);
    s->last_ns = ca_now_ns();
    ca_seq_end(s);
}

}

extern "C" int32_t ca_process(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out) {
    CaCtx* c = reinterpret_cast<CaCtx*>(self);
    if (c == nullptr || in == nullptr || out == nullptr) return -EINVAL;
    if (in->raw == nullptr || out->raw == nullptr) return -EINVAL;
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
    } else {
        for (size_t i = 0; i < samples; i++) {
            const float x = in->f32[i];
            const float ax = x < 0.0f ? -x : x;
            if (ax > blk.in_peak_f) blk.in_peak_f = ax;
            const uint32_t ai = ca_abs_bits(x);
            if (ai > blk.in_peak_i) blk.in_peak_i = ai;
        }

        ca_params_poll(c);

        const bool accumulate =
            c->cfg.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE;
        c->dsp.process(in->f32, out->f32, static_cast<int>(frames), accumulate);

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
        c->passthrough_only = (c->cfg.outputCfg.format != AUDIO_FORMAT_PCM_FLOAT_U8) ||
                              (c->channels > static_cast<uint32_t>(caeq::kMaxChannels));
        CA_LOGI("SET_CONFIG rate=%u ch=%u fmt=%u access=%u passthrough_only=%d",
                c->cfg.outputCfg.samplingRate, c->channels,
                static_cast<unsigned>(c->cfg.outputCfg.format),
                static_cast<unsigned>(c->cfg.outputCfg.accessMode), c->passthrough_only);
        c->dsp.configure(static_cast<double>(c->cfg.outputCfg.samplingRate),
                         static_cast<int>(c->channels), kStructure);
        c->dsp.warmUp();
        c->poll.param_gen = 0;
        if (c->slot == nullptr) {
            ca_stats_attach(c);
            ca_stats_set_enabled(c, c->enabled);
            ca_stats_set_gain(c, c->gain_mb);
        }
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
        c->dsp.reset();
        return 0;

    case EFFECT_CMD_ENABLE:
    case EFFECT_CMD_DISABLE:
        if (!intReply) return -EINVAL;
        c->enabled = (cmd == EFFECT_CMD_ENABLE);
        if (c->enabled) c->dsp.reset();
        if (c->enabled && c->slot == nullptr) {
            if (ca_stats_take_slot(c, false)) {
                ca_stats_configure(c);
                ca_stats_set_gain(c, c->gain_mb);
            }
        }
        c->dsp.setActive(c->enabled && c->poll.user_enabled);
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
        const size_t voff = ((pp->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
        if (cmdSize < sizeof(effect_param_t) + voff + pp->vsize) return -EINVAL;
        int32_t id = 0;
        int32_t v = 0;
        std::memcpy(&id, pp->data, sizeof(id));
        std::memcpy(&v, pp->data + voff, sizeof(v));
        if (id == CA_PARAM_ID_SLOT) {
            if (v < 0 || v >= CA_SHM_SLOTS) {
                CA_LOGE("SET_PARAM slot=%d は範囲外", v);
                *static_cast<int*>(pReply) = -EINVAL;
                return 0;
            }
            c->param_slot = static_cast<uint32_t>(v);
            c->poll.param_gen = 0;
            c->dsp.setFirCapable(true);
            CA_LOGI("SET_PARAM slot=%d ctx=%p", v, static_cast<void*>(c));
            *static_cast<int*>(pReply) = 0;
            return 0;
        }
        if (id != CA_PARAM_ID_GAIN) { *static_cast<int*>(pReply) = -EINVAL; return 0; }
        if (v > 0) v = 0;
        if (v < -4000) v = -4000;
        c->gain_mb = v;
        caeq::Params p;
        p.band_count = 0;
        p.preamp_db = static_cast<double>(v) / 100.0;
        if (!c->dsp.setParams(p)) {
            CA_LOGE("SET_PARAM rejected gain=%d mB (rejected=%u)", v,
                    c->dsp.biquad().rejectedCount());
            *static_cast<int*>(pReply) = -EINVAL;
            return 0;
        }
        c->poll.user_enabled = true;
        c->dsp.setActive(c->enabled);
        CA_LOGI("SET_PARAM gain=%d mB (preamp=%.2f dB)", c->gain_mb, p.preamp_db);
        ca_stats_set_gain(c, c->gain_mb);
        *static_cast<int*>(pReply) = 0;
        return 0;
    }

    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
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
    .process         = ca_process,
    .command         = ca_command,
    .get_descriptor  = ca_get_descriptor,
    .process_reverse = nullptr,
};
}

extern "C" int32_t ca_lib_create(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                                 effect_handle_t* pHandle) {
    if (pHandle == nullptr || uuid == nullptr) return -EINVAL;
    if (std::memcmp(uuid, &kCaEqDescriptor.uuid, sizeof(effect_uuid_t)) != 0) return -EINVAL;

    CaCtx* c = new (std::nothrow) CaCtx{};
    if (c == nullptr) return -ENOMEM;
    c->itfe = &kCaEqInterface;
    c->enabled = false;
    c->passthrough_only = false;
    c->gain_mb = 0;
    c->channels = 2;
    c->slot = nullptr;
    c->param_slot = CA_PARAM_SLOT_NONE;
    c->dsp.setClock(ca_now_ns);
    c->dsp.setFirCapable(caeq::sessionCanBeAddressed(sessionId));
    c->dsp.configure(48000.0, 2, kStructure);
    c->dsp.warmUp();
    c->session_id = sessionId;
    c->io_id = ioId;
    ca_stats_attach(c);
    CA_LOGI("create session=%d io=%d ctx=%p", sessionId, ioId, static_cast<void*>(c));
    *pHandle = reinterpret_cast<effect_handle_t>(c);
    return 0;
}

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

extern "C" __attribute__((visibility("default")))
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
