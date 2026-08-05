#ifndef CA_EQ_SHM_H
#define CA_EQ_SHM_H

#include <stdint.h>

#define CA_SHM_PATH   "/data/vendor/audio/ca_eq_stats.bin"
#define CA_SHM_MAGIC  0x51454143u   /* 'CAEQ' (little endian で 43 41 45 51) */
#define CA_SHM_VERSION 1u
#define CA_SHM_SLOTS  8

/* このヘッダは .so とリーダの両方が読む。片方だけレイアウトが変わると、リーダが別の場所を
 * 読んで気づかないまま嘘の数字を出す。サイズはコンパイル時に固定する。 */
#ifdef __cplusplus
#define CA_STATIC_ASSERT(cond, msg) static_assert(cond, msg)
#else
#define CA_STATIC_ASSERT(cond, msg) _Static_assert(cond, msg)
#endif

/* 1 エフェクトインスタンス = 1 スロット。128 バイト固定。
 * 書き手は .so (vendor の audio HAL プロセス)、読み手は root の caeqstat。
 * seq は seqlock: 書く前に奇数、書き終えたら偶数。読み手は前後で同じ偶数を見たら採用する。 */
typedef struct ca_slot_s {
    uint32_t in_use;        /* 0 = 空き、CA_SHM_MAGIC = 使用中 */
    uint32_t seq;
    uint64_t frames;        /* 累積の処理フレーム数 */
    uint32_t sample_rate;
    uint32_t channels;
    uint32_t block_frames;  /* 直近の process() のフレーム数。1024=primary / 2048=deep_buffer */
    uint32_t state;         /* bit0 enabled / bit1 configured / bit2 passthrough_only */
    int32_t  gain_mb;
    int32_t  io_id;         /* create_effect に渡された ioId */
    uint64_t pid;           /* 書き手のプロセス */
    uint64_t ctx;           /* ctx ポインタ。インスタンスの一意な識別子として使う */
    uint64_t last_ns;       /* 直近に process() した CLOCK_MONOTONIC */
    /* 上は uint32/int32 が 8 本 (32 バイト) + uint64 が 4 本 (32 バイト) = 64 バイト。 */
    uint8_t  pad[128 - (8 * 4) - (4 * 8)];
} ca_slot_t;

CA_STATIC_ASSERT(sizeof(ca_slot_t) == 128, "ca_slot_t は 128 バイト固定");

typedef struct ca_shm_s {
    uint32_t magic;
    uint32_t version;
    uint32_t slot_count;
    uint32_t slot_size;
    uint8_t  pad[112];
    ca_slot_t slots[CA_SHM_SLOTS];
} ca_shm_t;

CA_STATIC_ASSERT(sizeof(ca_shm_t) == 128 + 128 * CA_SHM_SLOTS, "ca_shm_t のヘッダは 128 バイト");

/* state のビット */
#define CA_STATE_ENABLED           0x1u
#define CA_STATE_CONFIGURED        0x2u
#define CA_STATE_PASSTHROUGH_ONLY  0x4u

#endif
