#pragma once

#include <cstdint>

// Codificacao numerica (DEVE casar exatamente com SCREEN_MODE em
// rust/bridge/src/lib.rs, rust/media-logic/src/format3d.rs e ScreenFormatCatalog.kt).
enum class ScreenMode : uint32_t {
    Flat2D       = 0,
    SBS          = 1,
    SBSHalf      = 2,
    OU           = 3,
    OUHalf       = 4,
    Sphere360    = 5,
    Sphere180    = 6,
    Sphere360SBS = 7,
    Sphere360OU  = 8,
    Vr180SBS     = 9,
};

// Validacao em tempo de compilacao para garantir invariantes de contrato C-ABI
static_assert(static_cast<uint32_t>(ScreenMode::Flat2D) == 0, "Flat2D deve ser 0");
static_assert(static_cast<uint32_t>(ScreenMode::SBS) == 1, "SBS deve ser 1");
static_assert(static_cast<uint32_t>(ScreenMode::SBSHalf) == 2, "SBSHalf deve ser 2");
static_assert(static_cast<uint32_t>(ScreenMode::OU) == 3, "OU deve ser 3");
static_assert(static_cast<uint32_t>(ScreenMode::OUHalf) == 4, "OUHalf deve ser 4");
static_assert(static_cast<uint32_t>(ScreenMode::Sphere360) == 5, "Sphere360 deve ser 5");
static_assert(static_cast<uint32_t>(ScreenMode::Sphere180) == 6, "Sphere180 deve ser 6");
static_assert(static_cast<uint32_t>(ScreenMode::Sphere360SBS) == 7, "Sphere360SBS deve ser 7");
static_assert(static_cast<uint32_t>(ScreenMode::Sphere360OU) == 8, "Sphere360OU deve ser 8");
static_assert(static_cast<uint32_t>(ScreenMode::Vr180SBS) == 9, "Vr180SBS deve ser 9");
static_assert(static_cast<uint32_t>(ScreenMode::Vr180SBS) + 1 == 10, "Total de modos de tela deve ser exatamente 10");

inline const char* ScreenModeName(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Flat2D: return "Flat2D";
        case ScreenMode::SBS: return "SBS";
        case ScreenMode::SBSHalf: return "SBSHalf";
        case ScreenMode::OU: return "OU";
        case ScreenMode::OUHalf: return "OUHalf";
        case ScreenMode::Sphere360: return "Sphere360";
        case ScreenMode::Sphere180: return "Sphere180";
        case ScreenMode::Sphere360SBS: return "Sphere360SBS";
        case ScreenMode::Sphere360OU: return "Sphere360OU";
        case ScreenMode::Vr180SBS: return "Vr180SBS";
        default: return "Desconhecido";
    }
}

inline bool IsSphereMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Sphere360:
        case ScreenMode::Sphere180:
        case ScreenMode::Sphere360SBS:
        case ScreenMode::Sphere360OU:
        case ScreenMode::Vr180SBS:
            return true;
        default:
            return false;
    }
}

inline bool IsFlatStereoMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::SBS:
        case ScreenMode::SBSHalf:
        case ScreenMode::OU:
        case ScreenMode::OUHalf:
            return true;
        default:
            return false;
    }
}
