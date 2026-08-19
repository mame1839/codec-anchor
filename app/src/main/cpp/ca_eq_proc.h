// `/proc` を触る機構だけ。**方針は ca_eq_pick.h、機構がここ。**
//
// 分けてあるのは、ハーネスが MSVC でも組むから (この環境で sanitizer を持っているのは
// MSVC だけ)。`ca_eq_pick.h` に POSIX のヘッダを足すと、ホストの全件がビルドできなくなる。
// こちらは `.so` と `caeqset` / `caeqstat` だけが読む。
#ifndef CA_EQ_PROC_H_
#define CA_EQ_PROC_H_

#include <stdint.h>

#include <cerrno>
#include <cstdio>
#include <unistd.h>

#include "ca_eq_pick.h"

namespace caeq {

/**
 * プロセスが生きているか。[caeq::PidAliveFn] の実装で、`.so` と `caeqset` が同じものを使う。
 *
 * **ここは syscall を撃つだけ。判定の規則は `caeq::aliveFromAccess`** (`ca_eq_pick.h`) に
 * 置いてあり、ハーネスがそちらを撃っている — このヘッダは POSIX なのでホストでは
 * 1 行も回らず、規則をここに書くと誰も検査できない。
 *
 * ⚠️ **`return access(path, F_OK) == 0;` に短くしないこと。**戻り値だけで判定すると、
 * 拒否系の `errno` が「死んでいる」に潰れて、判定が**生きている枠を奪う向きに反転する。**
 */
inline bool procPidAlive(uint64_t pid, void* /*user*/) {
    if (pid == 0) return false;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%llu", static_cast<unsigned long long>(pid));
    errno = 0;
    const int rc = access(path, F_OK);
    return aliveFromAccess(rc, errno);
}

}  // namespace caeq

#endif  // CA_EQ_PROC_H_
