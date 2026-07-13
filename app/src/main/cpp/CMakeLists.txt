cmake_minimum_required(VERSION 3.22.1)

project(mixportscanner C)

add_library(
    mixportscanner
    SHARED
    scanner_native.c
)

find_library(log-lib log)

target_link_libraries(
    mixportscanner
    ${log-lib}
)
