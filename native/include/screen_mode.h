#pragma once

#include <cstdint>

// Codificacao numerica (DEVE casar exatamente com SCREEN_MODE em
// rust/bridge/src/lib.rs, rust/media-logic/src/format3d.rs e ScreenFormatCatalog.kt).
//
// Puramente projecao/layout — ortogonal a espaco de cor/HDR (ver
// media_logic::color::TransferFunction e get_video_is_hdr() no bridge).
// Nao adicionar um eixo de cor aqui: HDR e uma flag separada, valida para
// qualquer um destes modos.
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
    Vr180SBS      = 9,
    Cubemap3x2    = 10,
    Cubemap6x1    = 11,
    EAC3x2        = 12,
    Cubemap3x2SBS = 13,
    EAC3x2SBS     = 14,
    Fisheye190    = 15,
    Fisheye190SBS = 16,
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
static_assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2) == 10, "Cubemap3x2 deve ser 10");
static_assert(static_cast<uint32_t>(ScreenMode::Cubemap6x1) == 11, "Cubemap6x1 deve ser 11");
static_assert(static_cast<uint32_t>(ScreenMode::EAC3x2) == 12, "EAC3x2 deve ser 12");
static_assert(static_cast<uint32_t>(ScreenMode::Cubemap3x2SBS) == 13, "Cubemap3x2SBS deve ser 13");
static_assert(static_cast<uint32_t>(ScreenMode::EAC3x2SBS) == 14, "EAC3x2SBS deve ser 14");
static_assert(static_cast<uint32_t>(ScreenMode::Fisheye190) == 15, "Fisheye190 deve ser 15");
static_assert(static_cast<uint32_t>(ScreenMode::Fisheye190SBS) == 16, "Fisheye190SBS deve ser 16");
static_assert(static_cast<uint32_t>(ScreenMode::Fisheye190SBS) + 1 == 17, "Total de modos de tela deve ser exatamente 17");

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
        case ScreenMode::Cubemap3x2: return "Cubemap3x2";
        case ScreenMode::Cubemap6x1: return "Cubemap6x1";
        case ScreenMode::EAC3x2: return "EAC3x2";
        case ScreenMode::Cubemap3x2SBS: return "Cubemap3x2SBS";
        case ScreenMode::EAC3x2SBS: return "EAC3x2SBS";
        case ScreenMode::Fisheye190: return "Fisheye190";
        case ScreenMode::Fisheye190SBS: return "Fisheye190SBS";
        default: return "Desconhecido";
    }
}

inline bool IsCubemapMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Cubemap3x2:
        case ScreenMode::Cubemap6x1:
        case ScreenMode::EAC3x2:
        case ScreenMode::Cubemap3x2SBS:
        case ScreenMode::EAC3x2SBS:
            return true;
        default:
            return false;
    }
}

inline bool IsSphereMode(ScreenMode mode) {
    if (IsCubemapMode(mode)) {
        return true;
    }
    switch (mode) {
        case ScreenMode::Sphere360:
        case ScreenMode::Sphere180:
        case ScreenMode::Sphere360SBS:
        case ScreenMode::Sphere360OU:
        case ScreenMode::Vr180SBS:
        case ScreenMode::Fisheye190:
        case ScreenMode::Fisheye190SBS:
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

inline bool Is360Mode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Sphere360:
        case ScreenMode::Sphere360SBS:
        case ScreenMode::Sphere360OU:
            return true;
        default:
            return false;
    }
}

inline bool Is180Mode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Sphere180:
        case ScreenMode::Vr180SBS:
            return true;
        default:
            return false;
    }
}

inline bool IsFisheyeMode(ScreenMode mode) {
    switch (mode) {
        case ScreenMode::Fisheye190:
        case ScreenMode::Fisheye190SBS:
            return true;
        default:
            return false;
    }
}

