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
