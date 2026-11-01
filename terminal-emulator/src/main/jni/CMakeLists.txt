cmake_minimum_required(VERSION 3.22.1)
project("termux" C)

add_library(termux SHARED termux.c)

target_compile_options(termux PRIVATE
    "-std=c11"
    "-Wall"
    "-Wextra"
    "-Werror"
    "-Os"
    "-fno-stack-protector"
    "-fno-ident"
    "-g0"
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
    "-ffile-prefix-map=${CMAKE_CURRENT_BINARY_DIR}=.")

target_link_options(termux PRIVATE
    "-Wl,--build-id=none"
    "-Wl,--gc-sections"
    "-Wl,-s"
    "-Wl,-z,noexecstack")
