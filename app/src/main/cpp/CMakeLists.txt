cmake_minimum_required(VERSION 3.22.1)
project(mallo_genie LANGUAGES CXX)

set(genie_include "${MALLO_QAIRT_SDK}/include/Genie")
if(NOT EXISTS "${genie_include}/GenieDialog.h")
    message(FATAL_ERROR "Set malloQairtSdk to QAIRT 2.40.0.251030 with its complete include/Genie directory.")
endif()

add_library(mallo_genie SHARED genie_jni.cpp)
target_include_directories(mallo_genie PRIVATE "${genie_include}")
target_compile_features(mallo_genie PRIVATE cxx_std_17)
target_compile_options(mallo_genie PRIVATE -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
# The SDK headers need C++ headers. Hide static-runtime symbols from the QAIRT host libraries.
target_link_options(mallo_genie PRIVATE -Wl,--exclude-libs,ALL)
target_link_libraries(mallo_genie PRIVATE dl log)
