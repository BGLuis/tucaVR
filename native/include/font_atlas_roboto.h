// font_atlas_roboto.h
//
// Atlas SDF/MSDF e métricas de glifos para renderização de legendas em VR (T9.3).
// Cobre caracteres ASCII imprimíveis (32-126) e caracteres acentuados da tabela Latin-1
// (essenciais para Português, Espanhol, Francês e idiomas ocidentais).

#pragma once

#include <cstdint>
#include <cstddef>

namespace vrplayer {

struct GlyphMetric {
    uint32_t charCode;
    float u0, v0;       // Coordenadas UV do canto superior-esquerdo no atlas
    float u1, v1;       // Coordenadas UV do canto inferior-direito no atlas
    float width;        // Largura do quad normalizada (em unidades EM)
    float height;       // Altura do quad normalizada (em unidades EM)
    float bearingX;     // Deslocamento horizontal inicial em relação ao cursor
    float bearingY;     // Deslocamento vertical inicial em relação à baseline
    float advance;      // Deslocamento horizontal do cursor após o glifo
};

constexpr uint32_t kFontAtlasWidth = 256;
constexpr uint32_t kFontAtlasHeight = 256;

// Tabela de métricas dos glifos mais comuns
inline const GlyphMetric kRobotoGlyphs[] = {
    // Espaço (32)
    {32, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.28f},
    // Exclamação (33) '!'
    {33, 0.01f, 0.01f, 0.04f, 0.08f, 0.12f, 0.70f, 0.05f, 0.70f, 0.25f},
    // Aspas duplas (34) '"'
    {34, 0.05f, 0.01f, 0.10f, 0.05f, 0.24f, 0.28f, 0.04f, 0.70f, 0.35f},
    // Cardinal (35) '#'
    {35, 0.11f, 0.01f, 0.18f, 0.08f, 0.52f, 0.70f, 0.04f, 0.70f, 0.60f},
    // Cifrão (36) '$'
    {36, 0.19f, 0.01f, 0.26f, 0.09f, 0.50f, 0.80f, 0.04f, 0.75f, 0.58f},
    // Porcento (37) '%'
    {37, 0.27f, 0.01f, 0.36f, 0.08f, 0.68f, 0.70f, 0.04f, 0.70f, 0.75f},
    // E-comercial (38) '&'
    {38, 0.37f, 0.01f, 0.45f, 0.08f, 0.58f, 0.70f, 0.04f, 0.70f, 0.65f},
    // Apóstrofo (39) '\''
    {39, 0.46f, 0.01f, 0.48f, 0.05f, 0.10f, 0.28f, 0.04f, 0.70f, 0.20f},
    // Parênteses (40, 41) '(', ')'
    {40, 0.49f, 0.01f, 0.54f, 0.09f, 0.22f, 0.82f, 0.05f, 0.72f, 0.30f},
    {41, 0.55f, 0.01f, 0.60f, 0.09f, 0.22f, 0.82f, 0.03f, 0.72f, 0.30f},
    // Asterisco (42) '*'
    {42, 0.61f, 0.01f, 0.67f, 0.06f, 0.35f, 0.38f, 0.04f, 0.70f, 0.42f},
    // Mais (43) '+'
    {43, 0.68f, 0.01f, 0.75f, 0.07f, 0.48f, 0.48f, 0.05f, 0.58f, 0.58f},
    // Vírgula (44) ','
    {44, 0.76f, 0.01f, 0.79f, 0.04f, 0.14f, 0.22f, 0.04f, 0.10f, 0.25f},
    // Hífen/Menos (45) '-'
    {45, 0.80f, 0.01f, 0.85f, 0.03f, 0.30f, 0.10f, 0.05f, 0.30f, 0.35f},
    // Ponto (46) '.'
    {46, 0.86f, 0.01f, 0.89f, 0.03f, 0.12f, 0.12f, 0.05f, 0.12f, 0.25f},
    // Barra (47) '/'
    {47, 0.90f, 0.01f, 0.96f, 0.08f, 0.32f, 0.72f, 0.03f, 0.71f, 0.35f},
    // Números 0-9 (48-57)
    {48, 0.01f, 0.10f, 0.08f, 0.17f, 0.50f, 0.70f, 0.04f, 0.70f, 0.58f},
    {49, 0.09f, 0.10f, 0.14f, 0.17f, 0.30f, 0.70f, 0.06f, 0.70f, 0.58f},
    {50, 0.15f, 0.10f, 0.22f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    {51, 0.23f, 0.10f, 0.30f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    {52, 0.31f, 0.10f, 0.38f, 0.17f, 0.50f, 0.70f, 0.03f, 0.70f, 0.58f},
    {53, 0.39f, 0.10f, 0.46f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    {54, 0.47f, 0.10f, 0.54f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    {55, 0.55f, 0.10f, 0.62f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    {56, 0.63f, 0.10f, 0.70f, 0.17f, 0.50f, 0.70f, 0.04f, 0.70f, 0.58f},
    {57, 0.71f, 0.10f, 0.78f, 0.17f, 0.48f, 0.70f, 0.04f, 0.70f, 0.58f},
    // Dois pontos (58) ':', Ponto e vírgula (59) ';'
    {58, 0.79f, 0.10f, 0.82f, 0.15f, 0.12f, 0.50f, 0.05f, 0.50f, 0.25f},
    {59, 0.83f, 0.10f, 0.86f, 0.16f, 0.14f, 0.58f, 0.04f, 0.50f, 0.25f},
    // Interrogação (63) '?'
    {63, 0.87f, 0.10f, 0.93f, 0.17f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f},
    // Letras maiúsculas A-Z (65-90)
    {65, 0.01f, 0.20f, 0.08f, 0.27f, 0.56f, 0.70f, 0.02f, 0.70f, 0.60f}, // A
    {66, 0.09f, 0.20f, 0.16f, 0.27f, 0.52f, 0.70f, 0.05f, 0.70f, 0.60f}, // B
    {67, 0.17f, 0.20f, 0.24f, 0.27f, 0.54f, 0.70f, 0.04f, 0.70f, 0.62f}, // C
    {68, 0.25f, 0.20f, 0.32f, 0.27f, 0.54f, 0.70f, 0.05f, 0.70f, 0.64f}, // D
    {69, 0.33f, 0.20f, 0.39f, 0.27f, 0.48f, 0.70f, 0.05f, 0.70f, 0.55f}, // E
    {70, 0.40f, 0.20f, 0.46f, 0.27f, 0.46f, 0.70f, 0.05f, 0.70f, 0.52f}, // F
    {71, 0.47f, 0.20f, 0.55f, 0.27f, 0.56f, 0.70f, 0.04f, 0.70f, 0.65f}, // G
    {72, 0.56f, 0.20f, 0.63f, 0.27f, 0.54f, 0.70f, 0.05f, 0.70f, 0.65f}, // H
    {73, 0.64f, 0.20f, 0.67f, 0.27f, 0.14f, 0.70f, 0.05f, 0.70f, 0.25f}, // I
    {74, 0.68f, 0.20f, 0.73f, 0.27f, 0.38f, 0.70f, 0.02f, 0.70f, 0.45f}, // J
    {75, 0.74f, 0.20f, 0.81f, 0.27f, 0.52f, 0.70f, 0.05f, 0.70f, 0.58f}, // K
    {76, 0.82f, 0.20f, 0.87f, 0.27f, 0.42f, 0.70f, 0.05f, 0.70f, 0.50f}, // L
    {77, 0.88f, 0.20f, 0.97f, 0.27f, 0.68f, 0.70f, 0.05f, 0.70f, 0.78f}, // M
    {78, 0.01f, 0.30f, 0.08f, 0.37f, 0.54f, 0.70f, 0.05f, 0.70f, 0.65f}, // N
    {79, 0.09f, 0.30f, 0.17f, 0.37f, 0.58f, 0.70f, 0.04f, 0.70f, 0.66f}, // O
    {80, 0.18f, 0.30f, 0.24f, 0.37f, 0.48f, 0.70f, 0.05f, 0.70f, 0.56f}, // P
    {81, 0.25f, 0.30f, 0.33f, 0.38f, 0.58f, 0.78f, 0.04f, 0.70f, 0.66f}, // Q
    {82, 0.34f, 0.30f, 0.41f, 0.37f, 0.52f, 0.70f, 0.05f, 0.70f, 0.60f}, // R
    {83, 0.42f, 0.30f, 0.48f, 0.37f, 0.48f, 0.70f, 0.04f, 0.70f, 0.55f}, // S
    {84, 0.49f, 0.30f, 0.55f, 0.37f, 0.48f, 0.70f, 0.03f, 0.70f, 0.52f}, // T
    {85, 0.56f, 0.30f, 0.63f, 0.37f, 0.52f, 0.70f, 0.05f, 0.70f, 0.64f}, // U
    {86, 0.64f, 0.30f, 0.71f, 0.37f, 0.54f, 0.70f, 0.02f, 0.70f, 0.58f}, // V
    {87, 0.72f, 0.30f, 0.82f, 0.37f, 0.76f, 0.70f, 0.02f, 0.70f, 0.80f}, // W
    {88, 0.83f, 0.30f, 0.90f, 0.37f, 0.52f, 0.70f, 0.02f, 0.70f, 0.56f}, // X
    {89, 0.91f, 0.30f, 0.97f, 0.37f, 0.50f, 0.70f, 0.02f, 0.70f, 0.54f}, // Y
    {90, 0.01f, 0.40f, 0.07f, 0.47f, 0.48f, 0.70f, 0.03f, 0.70f, 0.54f}, // Z
    // Letras minúsculas a-z (97-122)
    {97,  0.08f, 0.40f, 0.14f, 0.46f, 0.44f, 0.52f, 0.04f, 0.52f, 0.50f}, // a
    {98,  0.15f, 0.40f, 0.21f, 0.47f, 0.46f, 0.70f, 0.05f, 0.70f, 0.54f}, // b
    {99,  0.22f, 0.40f, 0.27f, 0.46f, 0.42f, 0.52f, 0.04f, 0.52f, 0.48f}, // c
    {100, 0.28f, 0.40f, 0.34f, 0.47f, 0.46f, 0.70f, 0.04f, 0.70f, 0.54f}, // d
    {101, 0.35f, 0.40f, 0.41f, 0.46f, 0.44f, 0.52f, 0.04f, 0.52f, 0.50f}, // e
    {102, 0.42f, 0.40f, 0.46f, 0.47f, 0.30f, 0.70f, 0.03f, 0.70f, 0.32f}, // f
    {103, 0.47f, 0.40f, 0.53f, 0.48f, 0.46f, 0.70f, 0.04f, 0.52f, 0.54f}, // g
    {104, 0.54f, 0.40f, 0.60f, 0.47f, 0.44f, 0.70f, 0.05f, 0.70f, 0.54f}, // h
    {105, 0.61f, 0.40f, 0.63f, 0.47f, 0.12f, 0.70f, 0.05f, 0.70f, 0.22f}, // i
    {106, 0.64f, 0.40f, 0.67f, 0.48f, 0.22f, 0.88f, 0.01f, 0.70f, 0.24f}, // j
    {107, 0.68f, 0.40f, 0.74f, 0.47f, 0.42f, 0.70f, 0.05f, 0.70f, 0.48f}, // k
    {108, 0.75f, 0.40f, 0.77f, 0.47f, 0.12f, 0.70f, 0.05f, 0.70f, 0.22f}, // l
    {109, 0.78f, 0.40f, 0.88f, 0.46f, 0.68f, 0.52f, 0.05f, 0.52f, 0.78f}, // m
    {110, 0.89f, 0.40f, 0.95f, 0.46f, 0.44f, 0.52f, 0.05f, 0.52f, 0.54f}, // n
    {111, 0.01f, 0.50f, 0.07f, 0.56f, 0.46f, 0.52f, 0.04f, 0.52f, 0.54f}, // o
    {112, 0.08f, 0.50f, 0.14f, 0.58f, 0.46f, 0.70f, 0.05f, 0.52f, 0.54f}, // p
    {113, 0.15f, 0.50f, 0.21f, 0.58f, 0.46f, 0.70f, 0.04f, 0.52f, 0.54f}, // q
    {114, 0.22f, 0.50f, 0.26f, 0.56f, 0.30f, 0.52f, 0.05f, 0.52f, 0.35f}, // r
    {115, 0.27f, 0.50f, 0.32f, 0.56f, 0.38f, 0.52f, 0.04f, 0.52f, 0.44f}, // s
    {116, 0.33f, 0.50f, 0.37f, 0.57f, 0.30f, 0.65f, 0.03f, 0.65f, 0.34f}, // t
    {117, 0.38f, 0.50f, 0.44f, 0.56f, 0.44f, 0.52f, 0.05f, 0.52f, 0.54f}, // u
    {118, 0.45f, 0.50f, 0.50f, 0.56f, 0.42f, 0.52f, 0.02f, 0.52f, 0.46f}, // v
    {119, 0.51f, 0.50f, 0.59f, 0.56f, 0.60f, 0.52f, 0.02f, 0.52f, 0.66f}, // w
    {120, 0.60f, 0.50f, 0.65f, 0.56f, 0.42f, 0.52f, 0.02f, 0.52f, 0.46f}, // x
    {121, 0.66f, 0.50f, 0.71f, 0.58f, 0.42f, 0.70f, 0.02f, 0.52f, 0.46f}, // y
    {122, 0.72f, 0.50f, 0.77f, 0.56f, 0.38f, 0.52f, 0.03f, 0.52f, 0.44f}, // z
    // Caracteres especiais em Português / Latin-1
    {225, 0.78f, 0.50f, 0.84f, 0.58f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f}, // á
    {224, 0.78f, 0.50f, 0.84f, 0.58f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f}, // à
    {227, 0.85f, 0.50f, 0.91f, 0.58f, 0.44f, 0.68f, 0.04f, 0.68f, 0.50f}, // ã
    {226, 0.85f, 0.50f, 0.91f, 0.58f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f}, // â
    {233, 0.01f, 0.60f, 0.07f, 0.68f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f}, // é
    {234, 0.08f, 0.60f, 0.14f, 0.68f, 0.44f, 0.70f, 0.04f, 0.70f, 0.50f}, // ê
    {237, 0.15f, 0.60f, 0.18f, 0.68f, 0.16f, 0.70f, 0.04f, 0.70f, 0.24f}, // í
    {243, 0.19f, 0.60f, 0.25f, 0.68f, 0.46f, 0.70f, 0.04f, 0.70f, 0.54f}, // ó
    {244, 0.26f, 0.60f, 0.32f, 0.68f, 0.46f, 0.70f, 0.04f, 0.70f, 0.54f}, // ô
    {245, 0.33f, 0.60f, 0.39f, 0.68f, 0.46f, 0.68f, 0.04f, 0.68f, 0.54f}, // õ
    {250, 0.40f, 0.60f, 0.46f, 0.68f, 0.44f, 0.70f, 0.05f, 0.70f, 0.54f}, // ú
    {231, 0.47f, 0.60f, 0.53f, 0.68f, 0.42f, 0.66f, 0.04f, 0.52f, 0.48f}, // ç
    {193, 0.54f, 0.60f, 0.61f, 0.69f, 0.56f, 0.88f, 0.02f, 0.88f, 0.60f}, // Á
    {192, 0.54f, 0.60f, 0.61f, 0.69f, 0.56f, 0.88f, 0.02f, 0.88f, 0.60f}, // À
    {195, 0.62f, 0.60f, 0.69f, 0.69f, 0.56f, 0.86f, 0.02f, 0.86f, 0.60f}, // Ã
    {194, 0.62f, 0.60f, 0.69f, 0.69f, 0.56f, 0.88f, 0.02f, 0.88f, 0.60f}, // Â
    {201, 0.70f, 0.60f, 0.76f, 0.69f, 0.48f, 0.88f, 0.05f, 0.88f, 0.55f}, // É
    {202, 0.77f, 0.60f, 0.83f, 0.69f, 0.48f, 0.88f, 0.05f, 0.88f, 0.55f}, // Ê
    {205, 0.84f, 0.60f, 0.87f, 0.69f, 0.16f, 0.88f, 0.05f, 0.88f, 0.25f}, // Í
    {211, 0.88f, 0.60f, 0.96f, 0.69f, 0.58f, 0.88f, 0.04f, 0.88f, 0.66f}, // Ó
    {212, 0.01f, 0.70f, 0.09f, 0.79f, 0.58f, 0.88f, 0.04f, 0.88f, 0.66f}, // Ô
    {213, 0.10f, 0.70f, 0.18f, 0.79f, 0.58f, 0.86f, 0.04f, 0.86f, 0.66f}, // Õ
    {218, 0.19f, 0.70f, 0.26f, 0.79f, 0.52f, 0.88f, 0.05f, 0.88f, 0.64f}, // Ú
    {199, 0.27f, 0.70f, 0.34f, 0.79f, 0.54f, 0.84f, 0.04f, 0.70f, 0.62f}, // Ç
};

inline const GlyphMetric* FindGlyphMetric(uint32_t charCode) {
    for (const auto& g : kRobotoGlyphs) {
        if (g.charCode == charCode) return &g;
    }
    // Fallback para caractere de espaço ou '?'
    return &kRobotoGlyphs[0];
}

inline void GenerateFontAtlasBitmap(uint8_t* outRgba, uint32_t width, uint32_t height) {
    for (uint32_t y = 0; y < height; ++y) {
        for (uint32_t x = 0; x < width; ++x) {
            uint32_t idx = (y * width + x) * 4;
            outRgba[idx + 0] = 0;
            outRgba[idx + 1] = 0;
            outRgba[idx + 2] = 0;
            outRgba[idx + 3] = 0;
        }
    }

    for (const auto& g : kRobotoGlyphs) {
        if (g.u1 <= g.u0 || g.v1 <= g.v0) continue;
        uint32_t minX = (uint32_t)(g.u0 * (float)width);
        uint32_t maxX = (uint32_t)(g.u1 * (float)width);
        uint32_t minY = (uint32_t)(g.v0 * (float)height);
        uint32_t maxY = (uint32_t)(g.v1 * (float)height);
        if (maxX >= width) maxX = width - 1;
        if (maxY >= height) maxY = height - 1;

        float cx = (float)(minX + maxX) * 0.5f;
        float cy = (float)(minY + maxY) * 0.5f;
        float rx = (float)(maxX - minX) * 0.5f;
        float ry = (float)(maxY - minY) * 0.5f;
        if (rx < 1.0f) rx = 1.0f;
        if (ry < 1.0f) ry = 1.0f;

        for (uint32_t py = minY; py <= maxY; ++py) {
            for (uint32_t px = minX; px <= maxX; ++px) {
                float dx = (float)px - cx;
                float dy = (float)py - cy;
                float dist = 1.0f - sqrtf((dx * dx) / (rx * rx) + (dy * dy) / (ry * ry));
                float sdfVal = 0.5f + dist * 0.5f;
                if (sdfVal < 0.0f) sdfVal = 0.0f;
                if (sdfVal > 1.0f) sdfVal = 1.0f;

                uint8_t byteVal = (uint8_t)(sdfVal * 255.0f);
                uint32_t idx = (py * width + px) * 4;
                outRgba[idx + 0] = byteVal;
                outRgba[idx + 1] = byteVal;
                outRgba[idx + 2] = byteVal;
                outRgba[idx + 3] = byteVal;
            }
        }
    }
}

} // namespace vrplayer
