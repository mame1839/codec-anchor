# ホスト用ハーネス。組み方と罠は test-harness.md。
cmake_minimum_required(VERSION 3.16)
project(ca_eq_test C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(CA_EQ_ASAN "ハーネスを ASan で組む (MSVC 専用)" OFF)

add_executable(ca_eq_test
    ca_eq_test.cpp
    ca_eq_fir_test.cpp
    ca_eq_fir_gate_test.cpp
    ca_eq_shm_test.cpp
    ../dsp/ca_eq_dsp.cpp
    ../dsp/ca_eq_fft.cpp
    ../dsp/ca_eq_fir.cpp
    ../dsp/ca_eq_conv.cpp
    ../dsp/ca_eq_pipeline.cpp
    ../dsp/pffft/pffft.c)

target_include_directories(ca_eq_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

# ⚠️ /w を外さないこと。外すと MSVC が pffft.c の C4456 で /WX 込みで落ちる (test-harness.md §12)。
if(MSVC)
    set_source_files_properties(../dsp/pffft/pffft.c PROPERTIES COMPILE_OPTIONS "/w")
else()
    set_source_files_properties(../dsp/pffft/pffft.c PROPERTIES COMPILE_OPTIONS "-w")
endif()

target_compile_definitions(ca_eq_test PRIVATE CA_EQ_DSP_TEST_HOOKS=1)

if(MSVC)
    target_compile_definitions(ca_eq_test PRIVATE _CRT_SECURE_NO_WARNINGS=1)
    target_compile_options(ca_eq_test PRIVATE /W4 /WX /fp:precise /utf-8)
else()
    # ⚠️ -ffast-math を付けないこと。NaN / Inf の検査ごと消える (test-harness.md §12)。
    target_compile_options(ca_eq_test PRIVATE
        -Wall -Wextra -Werror -O2 -ffp-contract=fast)
endif()

if(CA_EQ_ASAN)
    if(NOT MSVC)
        message(FATAL_ERROR "CA_EQ_ASAN は MSVC 専用 (mingw-w64 には libasan が無い)")
    endif()
    target_compile_options(ca_eq_test PRIVATE /O1 /Zi /fsanitize=address)
    target_link_options(ca_eq_test PRIVATE /DEBUG)
elseif(MSVC)
    target_compile_options(ca_eq_test PRIVATE /O2)
endif()
