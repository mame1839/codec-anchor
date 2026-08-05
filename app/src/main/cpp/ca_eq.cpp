// Codec Anchor の音響処理エフェクト。legacy (HIDL) の C ABI で vendor の audio HAL に読まれる。
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <new>
#include <android/log.h>
#include "aosp/audio_effect.h"

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
    void*    slot;               // 共有メモリのスロット (Task 3)。nullptr なら記録しない
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

// Task 3 で共有メモリのカウンタを実装する。ここでは呼び出し位置だけ確定させておく。
void ca_stats_attach(CaCtx*, int32_t) {}
void ca_stats_detach(CaCtx*) {}
void ca_stats_configure(CaCtx*) {}
void ca_stats_set_enabled(CaCtx*, bool) {}
void ca_stats_set_gain(CaCtx*, int32_t) {}
void ca_stats_add(CaCtx*, size_t) {}

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

    if (c->passthrough_only) {
        // format が想定外。サンプルに一切触らない (in-place なら何もしなくてよい)。
        if (in->raw != out->raw) std::memcpy(out->raw, in->raw, samples * sizeof(float));
    } else if (c->gain_lin == 1.0f) {
        if (in->raw != out->raw) std::memcpy(out->f32, in->f32, samples * sizeof(float));
    } else {
        // 将来ここが biquad のカスケードになる。ライブラリは使わず、係数は RBJ の cookbook を
        // 自前で書く。リアルタイムのコールバックに外部依存を持ち込まない。
        const float g = c->gain_lin;
        for (size_t i = 0; i < samples; i++) out->f32[i] = in->f32[i] * g;
    }

    ca_stats_add(c, frames);
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
        // レイアウト: [int32 psize][int32 vsize][param bytes][value bytes]
        if (pCmd == nullptr || cmdSize < 4 * sizeof(int32_t)) return -EINVAL;
        if (!intReply) return -EINVAL;
        const int32_t* h = static_cast<const int32_t*>(pCmd);
        if (h[0] != static_cast<int32_t>(sizeof(int32_t)) ||
            h[1] != static_cast<int32_t>(sizeof(int32_t))) {
            *static_cast<int*>(pReply) = -EINVAL;
            return 0;
        }
        const int32_t id = h[2];
        int32_t v = h[3];
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
