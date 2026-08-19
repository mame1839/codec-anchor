#ifndef CA_EQ_SHM_H
#define CA_EQ_SHM_H

#include <stdint.h>
#include <stddef.h>

#include "dsp/ca_eq_curve.h"

#define CA_SHM_PATH   "/data/vendor/audio/ca_eq_stats.bin"
#define CA_SHM_MAGIC  0x51454143u

#define CA_SHM_RECLAIM 0x52454143u

#define CA_SHM_VERSION 4u
#define CA_SHM_SLOTS  8

#define CA_AUDIO_SESSION_DEVICE  (-2)

#define CA_STATIC_ASSERT(cond, msg) static_assert(cond, msg)

typedef struct ca_slot_s {
    uint32_t in_use;
    uint32_t seq;
    uint64_t frames;
    uint32_t sample_rate;
    uint32_t channels;
    uint32_t block_frames;
    uint32_t state;
    int32_t  gain_mb;
    int32_t  io_id;
    uint64_t pid;
    uint64_t ctx;
    uint64_t last_ns;
    float    in_peak;
    float    out_peak;
    uint64_t in_addr;
    uint64_t out_addr;
    uint32_t dbg_in_frames;
    uint32_t dbg_out_frames;
    uint32_t dbg_samples;
    uint32_t dbg_fmt;
    uint32_t in_peak_i32;
    uint32_t out_peak_i32;
    uint32_t param_slot;
    uint32_t param_gen;
    uint32_t param_rejected;
    int32_t  session_id;

    uint32_t fir_state;
    uint32_t fir_flags;
    uint32_t fir_fill;
    uint32_t fir_partitions;
    uint32_t fir_rebuilds;
    uint32_t fir_face_fades;
    uint32_t fir_fallbacks;
    uint32_t fir_mode_offs;
    uint32_t fir_design_failures;
    uint32_t fir_unfit_size;
    uint32_t fir_unfit_budget;
    uint32_t fir_curve_rejected;
    uint32_t fir_curve_gen;
    uint32_t fir_taps;
    uint32_t fir_m;
    uint32_t fir_arena_kb;
    uint64_t fir_max_slice_ns;
    uint32_t fir_scrubbed;
    uint8_t  pad[52];
} ca_slot_t;

#define CA_PARAM_SLOT_NONE  0xFFFFFFFFu
#define CA_SLOT_BYTES  256

CA_STATIC_ASSERT(CA_SHM_SLOTS > 0 && CA_PARAM_SLOT_NONE > (unsigned)CA_SHM_SLOTS,
                 "CA_PARAM_SLOT_NONE が本物の枠の添字と衝突している");

CA_STATIC_ASSERT(CA_SHM_RECLAIM != 0u && CA_SHM_RECLAIM != CA_SHM_MAGIC,
                 "CA_SHM_RECLAIM が空き (0) か使用中 (MAGIC) と同じ値になっている");
CA_STATIC_ASSERT((CA_SHM_RECLAIM & 1u) == 1u, "CA_SHM_RECLAIM が偶数");
CA_STATIC_ASSERT((CA_SHM_MAGIC & 1u) == 1u, "CA_SHM_MAGIC が偶数");

CA_STATIC_ASSERT(sizeof(ca_slot_t) == CA_SLOT_BYTES,
                 "ca_slot_t は 256 バイト固定 (pad を足し引きして合わせる)");
CA_STATIC_ASSERT(offsetof(ca_slot_t, fir_state) == 128,
                 "版 3 の 128 B の並びが動いている");

#define CA_FIR_STATE_BIQUAD   0u
#define CA_FIR_STATE_PREPARE  1u
#define CA_FIR_STATE_FADE_IN  2u
#define CA_FIR_STATE_FIR      3u
#define CA_FIR_STATE_FADE_OUT 4u

#define CA_FIR_F_REQUESTED  0x1u
#define CA_FIR_F_ARENA      0x2u
#define CA_FIR_F_BLOCK_OK   0x4u
#define CA_FIR_F_ADDRESSABLE 0x8u
#define CA_FIR_F_CURVE_FAILED 0x10u

#define CA_EQ_MAX_BANDS 31

enum {
    CA_EQ_BAND_PEAKING    = 0,
    CA_EQ_BAND_LOW_SHELF  = 1,
    CA_EQ_BAND_HIGH_SHELF = 2
};

typedef struct ca_eq_band_s {
    float    fc_hz;
    float    q;
    float    gain_db;
    uint32_t type;
} ca_eq_band_t;

CA_STATIC_ASSERT(sizeof(ca_eq_band_t) == 16, "ca_eq_band_t は 16 バイト固定");

#define CA_EQ_PARAM_SLOT_BYTES  2176

typedef struct ca_eq_slot_s {
    uint32_t seq;
    uint32_t generation;
    uint32_t flags;
    uint32_t band_count;
    float    preamp_db;
    uint32_t writer_pid;
    ca_eq_band_t band[CA_EQ_MAX_BANDS];

    uint32_t curve_gen;
    float    curve_db[caeq::kCurvePoints];
    uint8_t  pad[48];
} ca_eq_slot_t;

CA_STATIC_ASSERT(sizeof(ca_eq_slot_t) == CA_EQ_PARAM_SLOT_BYTES,
                 "ca_eq_slot_t は 2176 バイト固定");
CA_STATIC_ASSERT(offsetof(ca_eq_slot_t, curve_gen) == 520,
                 "版 3 の 520 B の並びが動いている");
CA_STATIC_ASSERT(offsetof(ca_eq_slot_t, curve_db) +
                     sizeof(float) * static_cast<size_t>(caeq::kCurvePoints) <=
                 sizeof(ca_eq_slot_t),
                 "曲線が枠からはみ出している");

#define CA_EQ_FLAG_ENABLED         0x1u
#define CA_EQ_FLAG_HIGH_PRECISION  0x2u

#define CA_PARAM_ID_GAIN  1
#define CA_PARAM_ID_SLOT  2

typedef struct ca_shm_s {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;
    uint32_t param_slot_size;
    uint32_t curve_points;
    uint8_t  pad[104];
    ca_slot_t slots[CA_SHM_SLOTS];
    ca_eq_slot_t params[CA_SHM_SLOTS];
} ca_shm_t;

CA_STATIC_ASSERT(offsetof(ca_shm_t, slots) == 128, "header は 128 バイト固定");
CA_STATIC_ASSERT(sizeof(ca_shm_t) ==
                     128 + CA_SLOT_BYTES * CA_SHM_SLOTS +
                         CA_EQ_PARAM_SLOT_BYTES * CA_SHM_SLOTS,
                 "ca_shm_t の並びが変わっている");

#define CA_SHM_BYTES  19584
CA_STATIC_ASSERT(sizeof(ca_shm_t) == CA_SHM_BYTES, "CA_SHM_BYTES と実際の大きさが違う");

#define CA_STATE_ENABLED           0x1u
#define CA_STATE_CONFIGURED        0x2u
#define CA_STATE_PASSTHROUGH_ONLY  0x4u

#endif
