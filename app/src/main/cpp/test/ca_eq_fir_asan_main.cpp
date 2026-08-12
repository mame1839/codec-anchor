// ASan ビルド専用の入口 (CMake の CA_EQ_ASAN_FIR=ON でだけ使う)。
//
// **なぜ別の main が要るか:** ハーネス本体の ca_eq_test.cpp は dsp/ca_eq_params.h を
// 引き、あれが GCC の `__atomic_*` builtin を直接使っているため MSVC ではコンパイル
// できない。ASan を持っているのは MSVC だけ (mingw-w64 には libasan が無い) なので、
// FIR 側の節だけを MSVC で組んで走らせる。
//
// `ca_eq_params.h` が `std::atomic` へ移れば、この入口は要らなくなり
// ハーネス全体を ASan に載せられる (段 2 の申し送り)。
#include <cstdio>

#include "ca_test_support.h"

void runFirSections(catest::Report& r);
void runFirGateSections(catest::Report& r);
void runFirAlignmentSection(catest::Report& r);

int main() {
    std::printf("Codec Anchor EQ — FIR の節を ASan で (MSVC /fsanitize=address)\n");
    catest::Report r;
    runFirSections(r);          // 19〜27
    runFirGateSections(r);      // 28
    runFirAlignmentSection(r);  // 29
    std::printf("\n%d / %d 件が通った。\n", r.total() - r.failures(), r.total());
    return r.failures() == 0 ? 0 : 1;
}
