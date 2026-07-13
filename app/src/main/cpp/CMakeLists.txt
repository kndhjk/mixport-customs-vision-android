cmake_minimum_required(VERSION 3.22.1)

project(scannerbridge C)

add_library(scannerbridge SHARED
    scanner_native_bridge.c
)

target_compile_options(scannerbridge PRIVATE
    -Wall
    -Wextra
    -Werror
)

target_link_options(scannerbridge PRIVATE
    -Wl,--gc-sections
)
