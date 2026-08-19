#ifndef CA_EQ_PROC_H_
#define CA_EQ_PROC_H_

#include <stdint.h>

#include <cerrno>
#include <cstdio>
#include <unistd.h>

#include "ca_eq_pick.h"

namespace caeq {

inline bool procPidAlive(uint64_t pid, void* /*user*/) {
    if (pid == 0) return false;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%llu", static_cast<unsigned long long>(pid));
    errno = 0;
    const int rc = access(path, F_OK);
    return aliveFromAccess(rc, errno);
}

}

#endif
