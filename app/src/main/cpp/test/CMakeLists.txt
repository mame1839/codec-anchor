# EQ のオフラインハーネス。**ホスト用で、APK には入らない。**
# AGP が見るのは app/src/main/cpp/CMakeLists.txt だけなので、ここは Android のビルドから
# 完全に独立している。
#
#   cmake -S app/src/main/cpp/test -B build/ca_eq_test
#   cmake --build build/ca_eq_test --parallel
#   ./build/ca_eq_test/ca_eq_test
cmake_minimum_required(VERSION 3.16)
project(ca_eq_test C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ASan の構成では本体を定義しない (本体は MSVC でビルドできないので、同じビルド
# ディレクトリに置くと必ず失敗する)。ビルドディレクトリは 1 つの目的につき 1 つ。
option(CA_EQ_ASAN_FIR "FIR の節を ASan で組む (MSVC 専用)" OFF)

if(NOT CA_EQ_ASAN_FIR)
add_executable(ca_eq_test
    ca_eq_test.cpp
    ca_eq_fir_test.cpp
    ca_eq_fir_gate_test.cpp
    ../dsp/ca_eq_dsp.cpp
    ../dsp/ca_eq_fft.cpp
    ../dsp/ca_eq_fir.cpp
    ../dsp/ca_eq_conv.cpp
    ../dsp/ca_eq_pipeline.cpp
    ../dsp/pffft/pffft.c)

target_include_directories(ca_eq_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# vendored の pffft.c だけ警告オプションを外す (改変を最小に保つため警告は上流のまま)。
# 自作コードは下の -Werror / /WX の対象。
if(MSVC)
    set_source_files_properties(../dsp/pffft/pffft.c PROPERTIES COMPILE_OPTIONS "/w")
else()
    set_source_files_properties(../dsp/pffft/pffft.c PROPERTIES COMPILE_OPTIONS "-w")
endif()

# ハーネスだけが持つ入口 (状態に NaN や極小値を直接撃ち込む)。`.so` 側では定義しないので、
# 製品のバイナリにこの経路は存在しない。
target_compile_definitions(ca_eq_test PRIVATE CA_EQ_DSP_TEST_HOOKS=1)

if(MSVC)
    # /utf-8 が無いと cl はソースを CP932 として読む。LF 改行のチェックアウトでは、行末が
    # CP932 の先行バイト (「、」「。」の UTF-8 末尾 0x81/0x82) の行が次の LF を食って行が
    # 融合し、コメントの次の行のプリプロセッサ指令が消える (#if が消えて #error が無条件で
    # 発火する形で実際に踏んだ)。
    target_compile_options(ca_eq_test PRIVATE /W4 /WX /O2 /fp:precise /utf-8)
else()
    # **-ffast-math を付けないこと。**NaN / Inf の検査ごと消える (ca_eq_dsp.h が #error で止める)。
    # FMA は歓迎 — 積和が 1 回の丸めで済むので精度が上がる。
    target_compile_options(ca_eq_test PRIVATE
        -Wall -Wextra -Werror -O2 -ffp-contract=fast)
endif()
endif()  # NOT CA_EQ_ASAN_FIR

# ---------------------------------------------------------------------------
# ASan ビルド (FIR の節だけ)。既定 OFF。
#
#   cmake -S app/src/main/cpp/test -B build/ca_eq_asan -DCA_EQ_ASAN_FIR=ON
#   cmake --build build/ca_eq_asan --config Release
#   build/ca_eq_asan/Release/ca_eq_fir_asan.exe
#
# **MSVC 専用。**mingw-w64 には libasan / libubsan が同梱されておらず
# (`cannot find -lasan`)、この環境で sanitizer を持っているのは MSVC だけ。
# UBSan は MSVC 自体が非対応なので、いまは ASan のみ。
#
# **なぜ FIR の節だけか:** ハーネス本体 (ca_eq_test.cpp) は dsp/ca_eq_params.h を引き、
# あれが GCC の `__atomic_*` builtin を使うので MSVC ではコンパイルできない。
# あそこが std::atomic へ移れば全体を載せられる (段 2 の申し送り)。
#
# 起動時に interception failure で止まる環境がある (他プロセスのフックが原因の既知の症状)。
# そのときは環境変数 ASAN_WIN_CONTINUE_ON_INTERCEPTION_FAILURE=1 を立てる。
if(CA_EQ_ASAN_FIR)
    if(NOT MSVC)
        message(FATAL_ERROR
            "CA_EQ_ASAN_FIR は MSVC 専用 (mingw-w64 には libasan が無い)")
    endif()
    add_executable(ca_eq_fir_asan
        ca_eq_fir_asan_main.cpp
        ca_eq_fir_test.cpp
        ca_eq_fir_gate_test.cpp
        ../dsp/ca_eq_dsp.cpp
        ../dsp/ca_eq_fft.cpp
        ../dsp/ca_eq_fir.cpp
        ../dsp/ca_eq_conv.cpp
        ../dsp/ca_eq_pipeline.cpp
        ../dsp/pffft/pffft.c)
    target_include_directories(ca_eq_fir_asan PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_definitions(ca_eq_fir_asan PRIVATE CA_EQ_DSP_TEST_HOOKS=1)
    # vendored の pffft は上流のまま warning free ではないので /w。
    # cl が「/W4 より /w が優先」(D9025) と言うが、それが意図した通りの結果。
    set_source_files_properties(../dsp/pffft/pffft.c PROPERTIES COMPILE_OPTIONS "/w")
    # ASan の計装で遅くなるので /O1。**/RTC は ASan と併用できない**ので付けない。
    target_compile_options(ca_eq_fir_asan PRIVATE
        /W4 /WX /O1 /Zi /fp:precise /utf-8 /fsanitize=address)
    target_link_options(ca_eq_fir_asan PRIVATE /DEBUG)
endif()
