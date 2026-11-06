cmake_minimum_required(VERSION 3.22.1)
project("link2symlink" CXX)

# CI-only native assurance switch. Production Android builds leave this OFF.
option(LINK2SYMLINK_ENABLE_SANITIZERS "Build link2symlink with ASan and UBSan" OFF)

add_library(${CMAKE_PROJECT_NAME} SHARED
        # List C/C++ source files with relative paths to this CMakeLists.txt.
        link2symlink.cpp)

if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    set(LINK2SYMLINK_OPTIMIZATION_FLAG -Oz)
else()
    set(LINK2SYMLINK_OPTIMIZATION_FLAG -O2)
endif()

target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=."
        "-ffile-prefix-map=${CMAKE_CURRENT_BINARY_DIR}=."
        "-g0"
        "-fno-ident"
        "-fno-exceptions"
        "-fno-rtti"
        "${LINK2SYMLINK_OPTIMIZATION_FLAG}"
        "-DNDEBUG")

if(ANDROID)
    target_link_libraries(${CMAKE_PROJECT_NAME} android log)
else()
    find_library(LOG_LIBRARY log)
    if(LOG_LIBRARY)
        target_link_libraries(${CMAKE_PROJECT_NAME} ${LOG_LIBRARY})
    endif()
endif()

if(LINK2SYMLINK_ENABLE_SANITIZERS)
    target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE
            -fsanitize=address,undefined -fno-omit-frame-pointer -O1 -g)
    target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
            -fsanitize=address,undefined)
    message(STATUS "link2symlink sanitizers enabled: address,undefined")
endif()

target_link_options(${CMAKE_PROJECT_NAME} PRIVATE
        "-Wl,-s"
        "-Wl,--build-id=none"
        "-Wl,--gc-sections"
        "-Wl,-z,noexecstack")

if(UNIX AND NOT ANDROID)
    include(CTest)
    add_executable(link2symlink_smoke_test
            ${CMAKE_CURRENT_SOURCE_DIR}/../../test/cpp/link2symlink_smoke_test.cpp)
    if(LINK2SYMLINK_ENABLE_SANITIZERS)
        target_compile_options(link2symlink_smoke_test PRIVATE
                -fsanitize=address,undefined -fno-omit-frame-pointer -O1 -g)
        target_link_options(link2symlink_smoke_test PRIVATE
                -fsanitize=address,undefined)
    endif()
    add_test(NAME link2symlink_smoke_test COMMAND link2symlink_smoke_test)
endif()
