cmake_minimum_required(VERSION 3.22)
project(caeq C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-Wall -Wextra -Werror -O2 -ffp-contract=fast
                    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
                    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>)

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

set_source_files_properties(dsp/pffft/pffft.c PROPERTIES
    COMPILE_OPTIONS "-w"
    COMPILE_DEFINITIONS "CA_PFFFT_NO_ASSERT=1")
target_link_options(caeq PRIVATE -Wl,--no-undefined -Wl,--exclude-libs,ALL)

add_executable(caeqstat caeqstat.cpp)
target_include_directories(caeqstat PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(caeqstat PROPERTIES PREFIX "lib" OUTPUT_NAME "caeqstat" SUFFIX ".so")

add_executable(caeqset caeqset.cpp dsp/ca_eq_dsp.cpp)
target_include_directories(caeqset PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
set_target_properties(caeqset PROPERTIES PREFIX "lib" OUTPUT_NAME "caeqset" SUFFIX ".so")

add_executable(dlopen_check dlopen_check.cpp)
target_include_directories(dlopen_check PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(dlopen_check dl)
set_target_properties(dlopen_check PROPERTIES PREFIX "lib" OUTPUT_NAME "dlopen_check" SUFFIX ".so")
