#include <dlfcn.h>
#include <cstdio>
#include "aosp/audio_effect.h"

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    void* h = dlopen(argv[1], RTLD_NOW);
    if (h == nullptr) { fprintf(stderr, "dlopen failed: %s\n", dlerror()); return 1; }
    void* s = dlsym(h, AUDIO_EFFECT_LIBRARY_INFO_SYM_AS_STR);
    if (s == nullptr) { fprintf(stderr, "symbol missing: %s\n", dlerror()); return 1; }
    const auto* lib = static_cast<const audio_effect_library_t*>(s);
    if (lib->tag != AUDIO_EFFECT_LIBRARY_TAG) { fprintf(stderr, "bad tag 0x%x\n", lib->tag); return 1; }
    if (lib->create_effect == nullptr || lib->get_descriptor == nullptr) {
        fprintf(stderr, "null entry points\n"); return 1;
    }
    printf("ok tag=0x%x version=0x%x name=%s\n", lib->tag, lib->version, lib->name);
    return 0;
}
