# EQ のオフラインハーネス。**ホスト用で、APK には入らない。**
# AGP が見るのは app/src/main/cpp/CMakeLists.txt だけなので、ここは Android のビルドから
# 完全に独立している。
#
#   cmake -S app/src/main/cpp/test -B build/ca_eq_test
#   cmake --build build/ca_eq_test --parallel
#   ./build/ca_eq_test/ca_eq_test
cmake_minimum_required(VERSION 3.16)
project(ca_eq_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_executable(ca_eq_test
    ca_eq_test.cpp
    ../dsp/ca_eq_dsp.cpp)

target_include_directories(ca_eq_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ハーネスだけが持つ入口 (状態に NaN や極小値を直接撃ち込む)。`.so` 側では定義しないので、
# 製品のバイナリにこの経路は存在しない。
target_compile_definitions(ca_eq_test PRIVATE CA_EQ_DSP_TEST_HOOKS=1)

if(MSVC)
    target_compile_options(ca_eq_test PRIVATE /W4 /WX /O2 /fp:precise)
else()
    # **-ffast-math を付けないこと。**NaN / Inf の検査ごと消える (ca_eq_dsp.h が #error で止める)。
    # FMA は歓迎 — 積和が 1 回の丸めで済むので精度が上がる。
    target_compile_options(ca_eq_test PRIVATE
        -Wall -Wextra -Werror -O2 -ffp-contract=fast)
endif()
