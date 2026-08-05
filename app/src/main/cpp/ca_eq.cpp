// Codec Anchor の音響処理エフェクト。legacy (HIDL) の C ABI で vendor の audio HAL に読まれる。
#include <android/log.h>

#define CA_LOG_TAG "CodecAnchorEQ"

// C++ で書くが、外へ出すシンボルは必ず extern "C"。
// マングルされるとローダの dlsym が見つけられない。
extern "C" __attribute__((visibility("default")))
int ca_eq_placeholder() {
    __android_log_print(ANDROID_LOG_INFO, CA_LOG_TAG, "placeholder");
    return 0;
}
