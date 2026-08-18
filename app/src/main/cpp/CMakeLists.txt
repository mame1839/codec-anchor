cmake_minimum_required(VERSION 3.22)
project(caeq C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# DSP のライブラリは使わない。biquad は自前で数十行。
# リアルタイムのコールバックに外部依存を持ち込まない。find_package を書かないこと。
#
# **-ffast-math を付けないこと。**NaN / Inf の検査ごと消える (dsp/ca_eq_dsp.h が #error で
# 止める)。FMA は積和が 1 回の丸めで済むので精度が上がる。ホストのハーネス
# (test/CMakeLists.txt) と同じ方針にしてあり、同じコードが同じ丸めで走る。
#
# -fno-exceptions / -fno-rtti は C++ にだけ渡す。vendoring した pffft.c は C なので、
# 素で並べると「C では無効な指定」が -Werror で落ちる。
add_compile_options(-Wall -Wextra -Werror -O2 -ffp-contract=fast
                    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
                    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)

# エフェクト本体。vendor の audio HAL プロセスが dlopen する。
# dsp/ は Android に依存しない演算層で、同じソースを test/ のハーネスがホストでビルドする。
add_library(caeq SHARED
    ca_eq.cpp
    dsp/ca_eq_dsp.cpp
    dsp/ca_eq_fft.cpp
    dsp/ca_eq_fir.cpp
    dsp/ca_eq_conv.cpp
    dsp/ca_eq_pipeline.cpp
    dsp/pffft/pffft.c)
target_include_directories(caeq PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(caeq log)
target_compile_options(caeq PRIVATE -fvisibility=hidden)

# vendored の pffft.c だけ 2 つ扱いが違う。
#
# 1. **警告は上流のまま** — 改変を最小に保つため -Werror の対象から外す。
# 2. **assert を no-op にする。**pffft は process 経路でも整列を assert で見ていて、
#    この製品は release / debug のどちらも NDEBUG を定義しない。条件が崩れると
#    audio HAL が abort() = 端末全体が無音になる。**ホストのハーネスでは定義しない** —
#    開発時に整列のバグを握り潰さないため (詳細は dsp/pffft/pffft.c 冒頭)。
set_source_files_properties(dsp/pffft/pffft.c PROPERTIES
    COMPILE_OPTIONS "-w"
    COMPILE_DEFINITIONS "CA_PFFFT_NO_ASSERT=1")
# 見えるべきシンボルは AUDIO_EFFECT_LIBRARY_INFO_SYM (= AELI) だけ。
# -fvisibility=hidden は自分の翻訳単位にしか効かない。ANDROID_STL=c++_static が引き込む
# prebuilt の静的ライブラリは既定の可視性で入るので、--exclude-libs で libc++abi の
# __cxa_* / _ZTI* をまとめて隠す。vendor の audio HAL に読ませる .so なので見える面は狭くする。
target_link_options(caeq PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL)

# 統計ファイルのリーダ。実体は実行ファイルだが、AGP に APK へ載せてもらうため lib*.so を名乗る。
# 実機では chmod 755 して直接実行する。
add_executable(caeqstat caeqstat.cpp)
target_include_directories(caeqstat PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(caeqstat PROPERTIES PREFIX "lib" OUTPUT_NAME "caeqstat" SUFFIX ".so")

# パラメータ枠への書き手。.so にバンドを掛けさせる唯一の書き手で、アプリが APK の中の
# これを su 経由で実行する (経路は core/EqParams.kt、詳細は caeqset.cpp 冒頭)。
# seqlock の書き込みは .so の読み手と同じ定義 (dsp/ca_eq_params.h) を使い、
# 書く前に演算層と同じ検査を通す。これも実体は実行ファイル。
add_executable(caeqset caeqset.cpp dsp/ca_eq_dsp.cpp)
target_include_directories(caeqset PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(caeqset PROPERTIES PREFIX "lib" OUTPUT_NAME "caeqset" SUFFIX ".so")

# post-fs-data から呼ぶ dlopen のセルフテスト。これも実体は実行ファイル。
add_executable(dlopen_check dlopen_check.cpp)
target_include_directories(dlopen_check PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(dlopen_check dl)
set_target_properties(dlopen_check PROPERTIES PREFIX "lib" OUTPUT_NAME "dlopen_check" SUFFIX ".so")
