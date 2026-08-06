/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Android Open Source Project の legacy (HIDL) エフェクト ABI から、この実装が使う型と定数だけを
 * 写したもの。原典:
 *   hardware/libhardware/include/hardware/audio_effect.h
 *   system/media/audio/include/system/audio.h        (audio_buffer_t)
 *
 * 構造体のサイズとオフセットが 1 バイトでもずれると、AudioFlinger が渡してくる effect_config_t の
 * 読み方がずれて誤動作する。メンバの型・順序・並びを原典から変えないこと。
 */

#ifndef CA_AOSP_AUDIO_EFFECT_H
#define CA_AOSP_AUDIO_EFFECT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*----------------------------------------------------------------------------
 * UUID と descriptor
 *--------------------------------------------------------------------------*/

typedef struct effect_uuid_s {
    uint32_t timeLow;
    uint16_t timeMid;
    uint16_t timeHiAndVersion;
    uint16_t clockSeq;
    uint8_t  node[6];
} effect_uuid_t;

typedef struct effect_descriptor_s {
    effect_uuid_t type;
    effect_uuid_t uuid;
    uint32_t apiVersion;
    uint32_t flags;
    uint16_t cpuLoad;      /* 0.1 MIPS 単位 */
    uint16_t memoryUsage;  /* KB 単位 */
    char     name[64];
    char     implementor[64];
} effect_descriptor_t;

/*----------------------------------------------------------------------------
 * バッファと設定
 *--------------------------------------------------------------------------*/

typedef struct audio_buffer_s {
    size_t frameCount;
    union {
        void*    raw;
        float*   f32;
        int32_t* s32;
        int16_t* s16;
        uint8_t* u8;
    };
} audio_buffer_t;

typedef int32_t (*buffer_function_t)(void* cookie, audio_buffer_t* buffer);

typedef struct buffer_provider_s {
    buffer_function_t getBuffer;
    buffer_function_t releaseBuffer;
    void* cookie;
} buffer_provider_t;

typedef struct buffer_config_s {
    audio_buffer_t    buffer;
    uint32_t          samplingRate;
    uint32_t          channels;      /* audio_channel_mask_t */
    buffer_provider_t bufferProvider;
    uint8_t           format;        /* audio_format_t の下位 8 bit だけが入る */
    uint8_t           accessMode;
    uint16_t          mask;
} buffer_config_t;

typedef struct effect_config_s {
    buffer_config_t inputCfg;
    buffer_config_t outputCfg;
} effect_config_t;

/* EFFECT_CMD_SET_PARAM / GET_PARAM が運ぶ形。
 * 先頭が status で、psize はその次。ここを 1 つ詰めて読むと必ず弾くことになる。
 * value は data + psize を sizeof(int) に切り上げた位置に置かれる (data の直後ではない)。 */
typedef struct effect_param_s {
    int32_t  status;
    uint32_t psize;
    uint32_t vsize;
    char     data[];
} effect_param_t;

/*----------------------------------------------------------------------------
 * エフェクトの制御インタフェース
 *--------------------------------------------------------------------------*/

struct effect_interface_s;
typedef const struct effect_interface_s** effect_handle_t;

struct effect_interface_s {
    int32_t (*process)(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out);
    int32_t (*command)(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize, void* pCmdData,
                       uint32_t* replySize, void* pReplyData);
    int32_t (*get_descriptor)(effect_handle_t self, effect_descriptor_t* pDescriptor);
    int32_t (*process_reverse)(effect_handle_t self, audio_buffer_t* in, audio_buffer_t* out);
};

/*----------------------------------------------------------------------------
 * ライブラリのエントリ
 *--------------------------------------------------------------------------*/

typedef struct audio_effect_library_s {
    uint32_t tag;         /* AUDIO_EFFECT_LIBRARY_TAG */
    uint32_t version;     /* EFFECT_LIBRARY_API_VERSION */
    const char* name;
    const char* implementor;
    int32_t (*create_effect)(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                             effect_handle_t* pHandle);
    int32_t (*release_effect)(effect_handle_t handle);
    int32_t (*get_descriptor)(const effect_uuid_t* uuid, effect_descriptor_t* pDescriptor);
    /* 3.1 で足された 4 本目。**位置が ABI なので get_descriptor の後から動かさない。**
     * EffectsFactory.c の doEffectCreate() は AUDIO_SESSION_DEVICE のときだけこれを呼び、
     * version が 3.1 未満なら関数を見ずに -ENOSYS を返す (= device effect が作れない)。
     * しかも **NULL チェックをしないで呼ぶ**ので、メンバを持たずに 3.1 を名乗ると
     * 境界外へ間接ジャンプして HAL が落ち、音が全く出なくなる。version とセットで扱うこと。 */
    int32_t (*create_effect_3_1)(const effect_uuid_t* uuid, int32_t sessionId, int32_t ioId,
                                 int32_t deviceId, effect_handle_t* pHandle);
} audio_effect_library_t;

#define AUDIO_EFFECT_LIBRARY_TAG  ((('A') << 24) | (('E') << 16) | (('L') << 8) | ('T'))

/* ライブラリのエントリが名乗るシンボル名。原典どおり、識別子は AELI に展開される。
 * ローダ (EffectsXmlConfigLoader / EffectsFactory) は dlsym にこの文字列しか渡さないので、
 * AUDIO_EFFECT_LIBRARY_INFO_SYM という名前のまま export しても見つけてもらえない。 */
#define AUDIO_EFFECT_LIBRARY_INFO_SYM         AELI
#define AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR  "AELI"

/*----------------------------------------------------------------------------
 * バージョン
 *--------------------------------------------------------------------------*/

#define EFFECT_MAKE_API_VERSION(M, m)   (((M) << 16) | ((m) & 0xFFFF))
#define EFFECT_LIBRARY_API_VERSION_3_0  EFFECT_MAKE_API_VERSION(3, 0)
/* AUDIO_SESSION_DEVICE への生成は 3.1 以上でないと通らない (doEffectCreate)。 */
#define EFFECT_LIBRARY_API_VERSION_3_1  EFFECT_MAKE_API_VERSION(3, 1)
#define EFFECT_CONTROL_API_VERSION      EFFECT_MAKE_API_VERSION(2, 0)

/*----------------------------------------------------------------------------
 * descriptor の flags
 *--------------------------------------------------------------------------*/

#define EFFECT_FLAG_TYPE_POST_PROC   0x00000004
#define EFFECT_FLAG_INSERT_LAST      0x00000010
#define EFFECT_FLAG_DEVICE_IND       0x00000200

/*----------------------------------------------------------------------------
 * command code
 *--------------------------------------------------------------------------*/

#define EFFECT_CMD_INIT            0
#define EFFECT_CMD_SET_CONFIG      1
#define EFFECT_CMD_RESET           2
#define EFFECT_CMD_ENABLE          3
#define EFFECT_CMD_DISABLE         4
#define EFFECT_CMD_SET_PARAM       5
#define EFFECT_CMD_GET_PARAM       8
#define EFFECT_CMD_SET_DEVICE      10
#define EFFECT_CMD_SET_VOLUME      11
#define EFFECT_CMD_SET_AUDIO_MODE  12
#define EFFECT_CMD_GET_CONFIG      16

/* audio_format_t の AUDIO_FORMAT_PCM_FLOAT (0x00000005) を buffer_config_t.format の
 * uint8_t に切り詰めた値。この経路は float 固定で、int16 の経路は存在しない。 */
#define AUDIO_FORMAT_PCM_FLOAT_U8  5

/* buffer_config_t.accessMode。ACCUMULATE のときに出力を上書きすると、同じバッファへ
 * 書き込む他のトラックの音が消える。既定は WRITE だが値を見ずに決め打ちしないこと。 */
#define EFFECT_BUFFER_ACCESS_WRITE       0
#define EFFECT_BUFFER_ACCESS_READ        1
#define EFFECT_BUFFER_ACCESS_ACCUMULATE  2

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif  /* CA_AOSP_AUDIO_EFFECT_H */
