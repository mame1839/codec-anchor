cmake_minimum_required(VERSION 3.22)
project(caeq CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# DSP のライブラリは使わない。biquad は自前で数十行。
# リアルタイムのコールバックに外部依存を持ち込まない。find_package を書かないこと。
add_compile_options(-Wall -Wextra -Werror -O2 -fno-exceptions -fno-rtti)

# エフェクト本体。vendor の audio HAL プロセスが dlopen する。
add_library(caeq SHARED ca_eq.cpp)
target_include_directories(caeq PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(caeq log)
target_compile_options(caeq PRIVATE -fvisibility=hidden)
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
