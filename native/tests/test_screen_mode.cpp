#include "polar_mode.h"
#include "screen_mode.h"

#include <cassert>
#include <cstring>
#include <iostream>

static void TestScreenModeIndices() {
    assert(static_cast<uint32_t>(ScreenMode::Flat2D) == 0);
    assert(static_cast<uint32_t>(ScreenMode::SBS) == 1);
    assert(static_cast<uint32_t>(ScreenMode::SBSHalf) == 2);
    assert(static_cast<uint32_t>(ScreenMode::OU) == 3);
    assert(static_cast<uint32_t>(ScreenMode::OUHalf) == 4);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360) == 5);
    assert(static_cast<uint32_t>(ScreenMode::Sphere180) == 6);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360SBS) == 7);
    assert(static_cast<uint32_t>(ScreenMode::Sphere360OU) == 8);
    assert(static_cast<uint32_t>(ScreenMode::Vr180SBS) == 9);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2) == 10);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap6x1) == 11);
    assert(static_cast<uint32_t>(ScreenMode::EAC3x2) == 12);
    assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2SBS) == 13);
    assert(static_cast<uint32_t>(ScreenMode::EAC3x2SBS) == 14);
    assert(static_cast<uint32_t>(ScreenMode::Fisheye190) == 15);
    assert(static_cast<uint32_t>(ScreenMode::Fisheye190SBS) == 16);
    std::cout << "[PASS] TestScreenModeIndices\n";
}

static void TestScreenModeNames() {
    assert(std::strcmp(ScreenModeName(ScreenMode::Flat2D), "Flat2D") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::SBS), "SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::SBSHalf), "SBSHalf") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::OU), "OU") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::OUHalf), "OUHalf") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360), "Sphere360") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere180), "Sphere180") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360SBS), "Sphere360SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Sphere360OU), "Sphere360OU") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Vr180SBS), "Vr180SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap3x2), "Cubemap3x2") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap6x1), "Cubemap6x1") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::EAC3x2), "EAC3x2") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Cubemap3x2SBS), "Cubemap3x2SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::EAC3x2SBS), "EAC3x2SBS") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Fisheye190), "Fisheye190") == 0);
    assert(std::strcmp(ScreenModeName(ScreenMode::Fisheye190SBS), "Fisheye190SBS") == 0);
    std::cout << "[PASS] TestScreenModeNames\n";
}

static void TestScreenModePredicates() {
    for (uint32_t i = 0; i <= 16; i++) {
        ScreenMode m = static_cast<ScreenMode>(i);
        if (i < 5) {
            assert(!IsSphereMode(m));
        } else {
            assert(IsSphereMode(m));
        }

        if (i >= 1 && i <= 4) {
            assert(IsFlatStereoMode(m));
        } else {
            assert(!IsFlatStereoMode(m));
        }

        if (i >= 10 && i <= 14) {
            assert(IsCubemapMode(m));
        } else {
            assert(!IsCubemapMode(m));
        }

        if (i == 15 || i == 16) {
            assert(IsFisheyeMode(m));
        } else {
            assert(!IsFisheyeMode(m));
        }
    }
    std::cout << "[PASS] TestScreenModePredicates\n";
}

static void TestPolarModeEncodeDecode() {
    const int angles[] = {180, 190, 200, 220};
    const int polarModes[] = {0, 1, 2};

    for (int angle : angles) {
        for (int mode : polarModes) {
            int packed = EncodePolar180(mode, angle);
            assert(DecodePolarMode(packed) == mode);
            assert(DecodeFisheyeFov(packed) == angle);
        }
    }

    // Default/fallback: angle omitted or upper bits 0
    for (int mode : polarModes) {
        int packedDefault = EncodePolar180(mode);
        assert(DecodePolarMode(packedDefault) == mode);
        assert(DecodeFisheyeFov(packedDefault) == 190);

        // Raw mode integer with 0 in upper bits
        assert(DecodePolarMode(mode) == mode);
        assert(DecodeFisheyeFov(mode) == 190);
    }

    std::cout << "[PASS] TestPolarModeEncodeDecode\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de screen_mode.h ---\n";
    TestScreenModeIndices();
    TestScreenModeNames();
    TestScreenModePredicates();
    TestPolarModeEncodeDecode();
    std::cout << "--- Todos os testes de screen_mode.h passaram com sucesso! ---\n";
    return 0;
}
