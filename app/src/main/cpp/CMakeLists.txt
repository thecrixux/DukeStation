# SPDX-FileCopyrightText: 2026 SBRO
# SPDX-License-Identifier: LicenseRef-EmuCoreR-Proprietary
cmake_minimum_required(VERSION 3.22)
project(EmuCoreRAndroid LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Discord Social SDK is an optional local build input used by the isolated
# :discord helper process. When absent, emucorer_discord is not built and the
# integration reports unavailable.
if(NOT DISCORD_SDK_DIR AND DEFINED ENV{DISCORD_SDK_DIR})
    set(DISCORD_SDK_DIR "$ENV{DISCORD_SDK_DIR}")
endif()
set(DISCORD_SDK_SO "${DISCORD_SDK_DIR}/${ANDROID_ABI}/libdiscord_partner_sdk.so")
set(DISCORD_SDK_INCLUDE "${DISCORD_SDK_DIR}/include")
if(DISCORD_SDK_DIR AND EXISTS "${DISCORD_SDK_SO}" AND EXISTS "${DISCORD_SDK_INCLUDE}/discordpp.h")
    add_library(discord_partner_sdk SHARED IMPORTED)
    set_target_properties(discord_partner_sdk PROPERTIES
        IMPORTED_LOCATION "${DISCORD_SDK_SO}"
        INTERFACE_INCLUDE_DIRECTORIES "${DISCORD_SDK_INCLUDE}")
    add_library(emucorer_discord SHARED discord_bridge.cpp)
    target_link_libraries(emucorer_discord PRIVATE discord_partner_sdk log)
    set_target_properties(emucorer_discord PROPERTIES OUTPUT_NAME "emucorer_discord")
    message(STATUS "Discord Social SDK enabled: ${DISCORD_SDK_SO}")
else()
    message(STATUS "Discord Social SDK unavailable; Discord integration will report unavailable")
endif()

# -----------------------------------------------------------------------------
# SwanStation libretro core (vendored, in-tree dependencies).
# -----------------------------------------------------------------------------
set(SWANSTATION_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/swanstation")
set(SWANSTATION_BIN_DIR "${CMAKE_BINARY_DIR}/swanstation")
set(GOOSE_CORE "${CMAKE_CURRENT_SOURCE_DIR}/../goosestation/arm64-v8a/libgoosestation_libretro.so")
if(NOT EXISTS "${GOOSE_CORE}")
    message(FATAL_ERROR "Missing GooseStation core: ${GOOSE_CORE}")
endif()
add_library(goosestation_libretro SHARED IMPORTED)
set_target_properties(goosestation_libretro PROPERTIES IMPORTED_LOCATION "${GOOSE_CORE}")

# RetroAchievements client (rcheevos), driven by the Android frontend.
set(RCHEEVOS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/rcheevos")
add_subdirectory("${RCHEEVOS_DIR}" "${CMAKE_BINARY_DIR}/rcheevos")
target_include_directories(rcheevos PRIVATE
    "${SWANSTATION_DIR}/dep/libretro-common/include"
)

# libchdr ships a benchmark executable that is not part of the core and does
# not link the libretro VFS. Android's externalNativeBuild builds the `all`
# target, so exclude it and, defensively, satisfy its filestream_* symbols.
if(TARGET chdr-benchmark)
    set_target_properties(chdr-benchmark PROPERTIES EXCLUDE_FROM_ALL TRUE)
    if(TARGET libretro-common)
        target_link_libraries(chdr-benchmark PRIVATE libretro-common)
    endif()
endif()
if(TARGET chdr-fuzz)
    set_target_properties(chdr-fuzz PROPERTIES EXCLUDE_FROM_ALL TRUE)
    if(TARGET libretro-common)
        target_link_libraries(chdr-fuzz PRIVATE libretro-common)
    endif()
endif()

# JNI bridge that drives the core through the libretro API.
add_library(emucorer_jni SHARED
    native_bridge.cpp
    achievements_bridge.cpp
    shader_chain.cpp
    vulkan_frontend.cpp
)

target_include_directories(emucorer_jni PRIVATE
    "${SWANSTATION_DIR}/dep/libretro-common/include"
    "${SWANSTATION_DIR}/src"
    "${RCHEEVOS_DIR}/src"
)

find_library(VULKAN_LIBRARY vulkan)

set(EMUCORER_HAVE_LIBRASHADER OFF)
# Custom shader integration is disabled for the GooseStation boot probe.


target_link_libraries(emucorer_jni PRIVATE
    goosestation_libretro
    rcheevos
    android
    log
    aaudio
    EGL
    GLESv3
    ${VULKAN_LIBRARY}
)

if(TARGET librashader)
    target_link_libraries(emucorer_jni PRIVATE librashader)
    target_compile_definitions(emucorer_jni PRIVATE EMUCORER_HAVE_LIBRASHADER=1)
endif()

target_compile_options(emucorer_jni PRIVATE
    -Wall -Wextra
    $<$<NOT:$<CONFIG:Debug>>:-O3>
)

set_target_properties(emucorer_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

set_target_properties(emucorer_jni PROPERTIES OUTPUT_NAME "emucorer_jni")

# DukePocket uses only PokketStation's MIT core, pinned to 53ab779.
file(GLOB POCKET_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/pokketstation/src/*.c")
add_library(dukepocket SHARED pocket_bridge.cpp ${POCKET_SOURCES})
target_include_directories(dukepocket PRIVATE pokketstation/include pokketstation/src)
set_target_properties(dukepocket PROPERTIES C_STANDARD 99 C_STANDARD_REQUIRED ON)
target_compile_options(dukepocket PRIVATE -O2)
