cmake_minimum_required(VERSION 3.22.1)

project(camex_native VERSION 0.1.0 LANGUAGES CXX)

add_library(camex_native SHARED
    src/main/cpp/camex_native.cpp
)

target_compile_features(camex_native PRIVATE cxx_std_20)
set_target_properties(camex_native PROPERTIES
    CXX_EXTENSIONS OFF
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)

target_compile_options(camex_native PRIVATE
    -Wall
    -Wextra
    -Werror
)
