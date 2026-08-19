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

# ---------------------------------------------------------------------------
# ASan の構成。既定 OFF。**節の一覧も main も通常構成と同じ**で、違うのは旗だけ。
#
#   cmake -S app/src/main/cpp/test -B build/ca_eq_asan -DCA_EQ_ASAN=ON
#   cmake --build build/ca_eq_asan --config Release
#   build/ca_eq_asan/Release/ca_eq_test.exe
#
# **MSVC 専用。**mingw-w64 には libasan / libubsan が同梱されておらず
# (`cannot find -lasan`)、この環境で sanitizer を持っているのは MSVC だけ。
# UBSan は MSVC 自体が非対応なので、いまは ASan のみ。MSVC の構成を mingw の構成と
# 同じビルドディレクトリに混ぜないこと (ビルドディレクトリは 1 つの目的につき 1 つ)。
#
# ⚠️ **ASan の赤帯は「確保」の外側にしか無い。**`ca_shm_t` のような 1 個の大きな
# 構造体の**中で**隣の欄へはみ出す読み書き (曲線が次の枠へ食い込む、401 点の添字の
# off-by-one) は**素通りする** (実測で確認済み)。欄またぎを見るには clang の
# `-fsanitize-address-field-padding` が要るが MSVC には無い。
# → **共有メモリの境界は ca_eq_shm_test.cpp の明示的な見張りが一次防御線で、
#    ASan はその外側 (確保そのものを超える形) を見る 2 本目。**
#
# 起動時に interception failure で止まる環境がある (他プロセスのフックが原因の既知の症状)。
# そのときは環境変数 ASAN_WIN_CONTINUE_ON_INTERCEPTION_FAILURE=1 を立てる。
option(CA_EQ_ASAN "ハーネスを ASan で組む (MSVC 専用)" OFF)

add_executable(ca_eq_test
    ca_eq_test.cpp
    ca_eq_fir_test.cpp
    ca_eq_fir_gate_test.cpp
    ca_eq_shm_test.cpp
    ca_eq_preamp_boundary_test.cpp
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
    # std::fopen が C4996 (「fopen_s を使え」) になる。**fopen_s は MSVC 専用**で、
    # ここのコードは NDK の clang でも同じものが組まれるので置き換えられない。
    # ハーネスとツールがテキストを読むためだけの用途なので、この警告だけ黙らせる。
    target_compile_definitions(ca_eq_test PRIVATE _CRT_SECURE_NO_WARNINGS=1)
    # /utf-8 が無いと cl はソースを CP932 として読む。LF 改行のチェックアウトでは、行末が
    # CP932 の先行バイト (「、」「。」の UTF-8 末尾 0x81/0x82) の行が次の LF を食って行が
    # 融合し、コメントの次の行のプリプロセッサ指令が消える (#if が消えて #error が無条件で
    # 発火する形で実際に踏んだ)。
    target_compile_options(ca_eq_test PRIVATE /W4 /WX /fp:precise /utf-8)
else()
    # **-ffast-math を付けないこと。**NaN / Inf の検査ごと消える (ca_eq_dsp.h が #error で止める)。
    # FMA は歓迎 — 積和が 1 回の丸めで済むので精度が上がる。
    target_compile_options(ca_eq_test PRIVATE
        -Wall -Wextra -Werror -O2 -ffp-contract=fast)
endif()

if(CA_EQ_ASAN)
    if(NOT MSVC)
        message(FATAL_ERROR "CA_EQ_ASAN は MSVC 専用 (mingw-w64 には libasan が無い)")
    endif()
    # ASan の計装で遅くなるので /O1。**/RTC は ASan と併用できない**ので付けない。
    target_compile_options(ca_eq_test PRIVATE /O1 /Zi /fsanitize=address)
    target_link_options(ca_eq_test PRIVATE /DEBUG)
elseif(MSVC)
    target_compile_options(ca_eq_test PRIVATE /O2)
endif()
