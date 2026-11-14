#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

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

// Fisheye mesh vertex with 3D normalized position and precomputed equidistant UV coordinates.
struct FisheyeVertex {
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
    float u = 0.0f;
    float v = 0.0f;
};

// Generates a subdivided hemisphere mesh matching the equidistant (f-theta) fisheye projection (#253).
//
// Coordinate conventions:
// - XR Right-Handed: Forward is -Z, Up is +Y, Right is +X.
// - Optical center (psi = 0) is at (0, 0, -1) with UV (0.5, 0.5).
// - Lens FOV defines the maximum angular radius: kMaxHalfFov = lensFovDegrees * PI / 360.
// - Positions (x, y, z) are strictly normalized onto the unit sphere (length == 1.0).
// - UV coordinates (u, v) map into the local eye tile [0, 1] x [0, 1].
// - Triangles are wound in counter-clockwise order from the perspective of an observer at (0, 0, 0).
inline void GenerateFisheyeHemisphereMesh(float lensFovDegrees, int rings, int sectors,
                                          std::vector<FisheyeVertex> &outVertices, std::vector<uint32_t> &outIndices) {
    if (rings < 1) {
        rings = 1;
    }
    if (sectors < 3) {
        sectors = 3;
    }
    if (lensFovDegrees <= 0.0f) {
        lensFovDegrees = static_cast<float>(DEFAULT_FISHEYE_FOV_DEGREES);
    } else if (lensFovDegrees > 360.0f) {
        lensFovDegrees = 360.0f;
    }

    constexpr float kPi = 3.14159265358979323846f;
    const float kMaxHalfFov = (lensFovDegrees * kPi / 180.0f) * 0.5f;

    outVertices.clear();
    outIndices.clear();

    const size_t totalVertices = 1 + static_cast<size_t>(rings) * static_cast<size_t>(sectors);
    outVertices.reserve(totalVertices);

    // Apex / pole vertex at index 0 (forward optical center towards -Z)
    outVertices.push_back({0.0f, 0.0f, -1.0f, 0.5f, 0.5f});

    for (int r = 1; r <= rings; ++r) {
        const float rNorm = static_cast<float>(r) / static_cast<float>(rings);
        const float psi = kMaxHalfFov * rNorm;
        const float sinPsi = std::sin(psi);
        const float cosPsi = std::cos(psi);

        for (int s = 0; s < sectors; ++s) {
            const float alpha = 2.0f * kPi * static_cast<float>(s) / static_cast<float>(sectors);
            const float sinAlpha = std::sin(alpha);
            const float cosAlpha = std::cos(alpha);

            const float x = sinPsi * sinAlpha;
            const float y = sinPsi * cosAlpha;
            const float z = -cosPsi;

            const float u = 0.5f + 0.5f * rNorm * sinAlpha;
            const float v = 0.5f - 0.5f * rNorm * cosAlpha;

            outVertices.push_back({x, y, z, u, v});
        }
    }

    const size_t totalTriangles = static_cast<size_t>(2 * rings - 1) * static_cast<size_t>(sectors);
    outIndices.reserve(totalTriangles * 3);

    // First ring: triangle fan connecting apex (index 0) to ring 1
    for (int s = 0; s < sectors; ++s) {
        const uint32_t s0 = 1 + static_cast<uint32_t>(s);
        const uint32_t s1 = 1 + static_cast<uint32_t>((s + 1) % sectors);
        outIndices.push_back(0);
        outIndices.push_back(s1);
        outIndices.push_back(s0);
    }

    // Concentric quads from ring r to r + 1, each subdivided into two CCW triangles
    for (int r = 1; r < rings; ++r) {
        const uint32_t innerBase = 1 + static_cast<uint32_t>((r - 1) * sectors);
        const uint32_t outerBase = 1 + static_cast<uint32_t>(r * sectors);

        for (int s = 0; s < sectors; ++s) {
            const uint32_t nextS = static_cast<uint32_t>((s + 1) % sectors);
            const uint32_t a = innerBase + static_cast<uint32_t>(s);
            const uint32_t b = innerBase + nextS;
            const uint32_t c = outerBase + static_cast<uint32_t>(s);
            const uint32_t d = outerBase + nextS;

            outIndices.push_back(a);
            outIndices.push_back(b);
            outIndices.push_back(d);

            outIndices.push_back(a);
            outIndices.push_back(d);
            outIndices.push_back(c);
        }
    }
}
