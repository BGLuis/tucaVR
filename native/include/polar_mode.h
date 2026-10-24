#pragma once

#include <cstdint>

// Helpers for encoding and decoding polarMode + fisheye lens FOV into a single 32-bit integer,
// preserving the exact 128-byte StereoPushConstants size limit.
//
// Bits [0..7]:   polarMode (0=flat/none, 1=Sphere180, 2=Fisheye)
// Bits [8..23]:  fisheye FOV in degrees (e.g. 180, 190, 200, 220; 0 means default 190)

inline constexpr int DEFAULT_FISHEYE_FOV_DEGREES = 190;

inline constexpr int EncodePolar180(int polarMode, int fovDegrees = DEFAULT_FISHEYE_FOV_DEGREES) {
    uint32_t mode = static_cast<uint32_t>(polarMode) & 0xFFu;
    uint32_t fov = static_cast<uint32_t>(fovDegrees) & 0xFFFFu;
    return static_cast<int>(mode | (fov << 8));
}

inline constexpr int DecodePolarMode(int packed) {
    return packed & 0xFF;
}

inline constexpr int DecodeFisheyeFov(int packed) {
    int fov = (packed >> 8) & 0xFFFF;
    return (fov == 0) ? DEFAULT_FISHEYE_FOV_DEGREES : fov;
}
