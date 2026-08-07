cmake_minimum_required(VERSION 3.22.1)
project(vrplayer_native LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# -----------------------------------------------------------------------------
# Meta / Khronos OpenXR Integration via Prefab
# -----------------------------------------------------------------------------
find_package(OpenXR REQUIRED CONFIG)


# -----------------------------------------------------------------------------
# Include Directories
# -----------------------------------------------------------------------------
include_directories(include)

# -----------------------------------------------------------------------------
# Source Files
# -----------------------------------------------------------------------------
set(SOURCE_FILES
    src/vr_player_app.cpp
)

# -----------------------------------------------------------------------------
# Shared Library Creation
# -----------------------------------------------------------------------------
add_library(vrplayer_native SHARED ${SOURCE_FILES})

# -----------------------------------------------------------------------------
# Android Native App Glue
# -----------------------------------------------------------------------------
add_library(native_app_glue STATIC ${ANDROID_NDK}/sources/android/native_app_glue/android_native_app_glue.c)
target_include_directories(native_app_glue PUBLIC ${ANDROID_NDK}/sources/android/native_app_glue)

# -----------------------------------------------------------------------------
# NDK Libraries
# -----------------------------------------------------------------------------
# Find NDK libraries required for Android app development
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)
find_library(vulkan-lib vulkan)
find_library(mediandk-lib mediandk)
find_library(nativewindow-lib nativewindow)

# Link everything together
target_link_libraries(vrplayer_native
    ${log-lib}
    ${android-lib}
    ${egl-lib}
    ${gles3-lib}
    ${vulkan-lib}
    ${mediandk-lib}
    ${nativewindow-lib}
    native_app_glue
    # OpenXR library from prefab
    OpenXR::openxr_loader
    # Rust bridge dynamic library
    ${CMAKE_CURRENT_SOURCE_DIR}/../rust/target/aarch64-linux-android/release/libbridge.so
    samplexrframework
)

set_target_properties(vrplayer_native PROPERTIES LINK_FLAGS "-Wl,-u,ANativeActivity_onCreate")

set(OPENXR_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../sdk/meta-openxr-sdk)
add_subdirectory(${OPENXR_SDK_DIR}/Samples/3rdParty ${CMAKE_CURRENT_BINARY_DIR}/3rdParty)
add_subdirectory(${OPENXR_SDK_DIR}/Samples/SampleXrFramework ${CMAKE_CURRENT_BINARY_DIR}/SampleXrFramework)
