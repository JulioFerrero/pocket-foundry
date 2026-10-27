cmake_minimum_required(VERSION 3.22)
project(pocketfoundry C)

# Debug builds compile without optimization, which makes decompression several
# times slower; this code is debugged through its results, not a debugger.
add_compile_options(-O2)

# liblzma (0BSD) to unpack .tar.xz archives: the game import was 6x slower
# with the pure-Java decoder.
include(FetchContent)
FetchContent_Declare(xz
    URL https://github.com/tukaani-project/xz/releases/download/v5.8.4/xz-5.8.4.tar.gz
    URL_HASH SHA256=0014c7886930454fe8bd4228665b51af55eeae560ea135c9c4cd33f55b2591d9)
FetchContent_GetProperties(xz)
if(NOT xz_POPULATED)
    FetchContent_Populate(xz)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(XZ_NLS OFF CACHE BOOL "" FORCE)
    # Only liblzma gets built: it's the one target we link.
    add_subdirectory(${xz_SOURCE_DIR} ${xz_BINARY_DIR} EXCLUDE_FROM_ALL)
endif()

add_library(pocketfoundry SHARED xz_jni.c)
target_include_directories(pocketfoundry PRIVATE ${xz_SOURCE_DIR}/src/liblzma/api)
target_link_libraries(pocketfoundry PRIVATE liblzma)
