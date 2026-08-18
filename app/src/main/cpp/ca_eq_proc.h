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
 * プロセスが生きているか。[PidAliveFn] の実装で、`.so` と `caeqset` が同じものを使う。
 *
 * ⚠️ **戻り値ではなく `errno` で判定する。**`access()` が 0 以外を返す理由は
 * ENOENT だけではない。拒否系の `errno` (EACCES など) を「死んでいる」と読むと、
 * 判定が**生きている枠を奪う向きに反転する** — 回収は「死んだ枠を空きに戻す」操作なので、
 * 誤りの向きがそのまま被害の向きになる。
 *
 * **`ENOENT` だけが「死んでいる」。それ以外の `errno` は「分からない」で、
 * 生きている扱いに倒す。**こうすれば読めなかったときの結末は
 * 「回収できない (= 回収を足す前と同じ)」で止まる。
 *
 * ⚠️ **`access(path, F_OK) == 0` という書き方を戻すと、この安全側の倒し方が消える。**
 * 短く見えるが等価ではない。
 */
inline bool procPidAlive(uint64_t pid, void* /*user*/) {
    if (pid == 0) return false;
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%llu", static_cast<unsigned long long>(pid));
    errno = 0;
    if (access(path, F_OK) == 0) return true;
    return errno != ENOENT;
}

}  // namespace caeq

#endif  // CA_EQ_PROC_H_
