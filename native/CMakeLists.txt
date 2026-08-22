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
# Graphics API selection (ver docs/VULKAN-MIGRATION-PLAN.md)
# -----------------------------------------------------------------------------
# GLES = caminho de producao (default), usa o SampleXrFramework/OVRFW da Meta.
# VULKAN = esqueleto do Estagio 1 do plano de migracao — caminho paralelo,
# independente do OVRFW (que e GLES-only por construcao, ver o plano).
set(VRPLAYER_GRAPHICS_API "GLES" CACHE STRING "Graphics API for VR rendering: GLES or VULKAN")

# -----------------------------------------------------------------------------
# Source Files
# -----------------------------------------------------------------------------
if(VRPLAYER_GRAPHICS_API STREQUAL "VULKAN")
    set(SOURCE_FILES
        src/vr_player_app_vulkan.cpp
        src/vr_player_jni_vulkan.cpp
    )
else()
    set(SOURCE_FILES
        src/vr_player_app.cpp
    )
endif()

# -----------------------------------------------------------------------------
# Shaders Vulkan (Estagio 2+ do plano de migracao) — GLSL -> SPIR-V -> header
# C++ embutido em build-time, so quando VRPLAYER_GRAPHICS_API=VULKAN.
# -----------------------------------------------------------------------------
if(VRPLAYER_GRAPHICS_API STREQUAL "VULKAN")
    find_program(GLSLC_EXECUTABLE glslc HINTS ${ANDROID_NDK}/shader-tools/linux-x86_64)
    if(NOT GLSLC_EXECUTABLE)
        message(FATAL_ERROR "glslc nao encontrado — necessario para compilar shaders Vulkan (esperado em ${ANDROID_NDK}/shader-tools/linux-x86_64 ou no PATH)")
    endif()

    set(SHADER_SRC_DIR ${CMAKE_CURRENT_SOURCE_DIR}/shaders/vulkan)
    set(SHADER_GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated_shaders)
    file(MAKE_DIRECTORY ${SHADER_GEN_DIR})

    function(vrplayer_compile_shader SHADER_NAME VAR_NAME)
        set(SRC ${SHADER_SRC_DIR}/${SHADER_NAME})
        set(SPV ${SHADER_GEN_DIR}/${SHADER_NAME}.spv)
        set(HDR ${SHADER_GEN_DIR}/${SHADER_NAME}.h)
        add_custom_command(
            OUTPUT ${SPV}
            COMMAND ${GLSLC_EXECUTABLE} --target-env=vulkan1.1 -O -o ${SPV} ${SRC}
            DEPENDS ${SRC}
            COMMENT "glslc: ${SHADER_NAME} -> SPIR-V"
        )
        add_custom_command(
            OUTPUT ${HDR}
            COMMAND ${CMAKE_COMMAND} -DSPV=${SPV} -DHEADER=${HDR} -DVARNAME=${VAR_NAME} -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedSpirv.cmake
            DEPENDS ${SPV} ${CMAKE_CURRENT_SOURCE_DIR}/cmake/EmbedSpirv.cmake
            COMMENT "Embutindo ${SHADER_NAME}.spv em ${HDR}"
        )
        set(GENERATED_SHADER_HEADERS ${GENERATED_SHADER_HEADERS} ${HDR} PARENT_SCOPE)
    endfunction()

    vrplayer_compile_shader(quad.vert kQuadVertSpirv)
    vrplayer_compile_shader(quad.frag kQuadFragSpirv)
    # Estagio 3: shaders de video com textura YCbCr via AHardwareBuffer
    vrplayer_compile_shader(video.vert kVideoVertSpirv)
    vrplayer_compile_shader(video.frag kVideoFragSpirv)
    # Estagio 4: shaders dos paineis de UI/controles (RGBA8888 normal)
    vrplayer_compile_shader(ui.vert kUiVertSpirv)
    vrplayer_compile_shader(ui.frag kUiFragSpirv)
    # Estagio 5: shaders estereo/esfera (SBS/OU/360/180) com CAS sharpening
    vrplayer_compile_shader(stereo.vert kStereoVertSpirv)
    vrplayer_compile_shader(stereo.frag kStereoFragSpirv)
    # Estagio 6: shaders de legendas MSDF (T9.3)
    vrplayer_compile_shader(subtitle.vert kSubtitleVertSpirv)
    vrplayer_compile_shader(subtitle.frag kSubtitleFragSpirv)

    add_custom_target(vrplayer_shaders DEPENDS ${GENERATED_SHADER_HEADERS})
endif()

# -----------------------------------------------------------------------------
# Shared Library Creation
# -----------------------------------------------------------------------------
add_library(vrplayer_native SHARED ${SOURCE_FILES})

if(VRPLAYER_GRAPHICS_API STREQUAL "VULKAN")
    add_dependencies(vrplayer_native vrplayer_shaders)
    target_include_directories(vrplayer_native PRIVATE ${SHADER_GEN_DIR})
endif()

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

set(OPENXR_SDK_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../sdk/meta-openxr-sdk)
add_subdirectory(${OPENXR_SDK_DIR}/Samples/3rdParty ${CMAKE_CURRENT_BINARY_DIR}/3rdParty)
add_subdirectory(${OPENXR_SDK_DIR}/Samples/SampleXrFramework ${CMAKE_CURRENT_BINARY_DIR}/SampleXrFramework)

# Link everything together
if(VRPLAYER_GRAPHICS_API STREQUAL "VULKAN")
    # Caminho isolado: NAO linka samplexrframework. Esse alvo propaga
    # XR_USE_GRAPHICS_API_OPENGL_ES=1, XR_USE_PLATFORM_ANDROID=1 e -Werror
    # como PUBLIC (ver SampleXrFramework/CMakeLists.txt), o que contaminaria
    # a compilacao de vr_player_app_vulkan.cpp (que define
    # XR_USE_GRAPHICS_API_VULKAN e nao deriva de OVRFW::XrApp).
    target_link_libraries(vrplayer_native
        ${log-lib}
        ${android-lib}
        ${vulkan-lib}
        ${nativewindow-lib}
        ${mediandk-lib}
        native_app_glue
        OpenXR::openxr_loader
        # Bridge Rust: get_current_video_frame e outras funcoes de playback
        # (Estagio 3 precisa de get_current_video_frame para textura de video)
        ${CMAKE_CURRENT_SOURCE_DIR}/../rust/target/aarch64-linux-android/release/libbridge.so
    )
else()
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
endif()

set_target_properties(vrplayer_native PROPERTIES LINK_FLAGS "-Wl,-u,ANativeActivity_onCreate")
