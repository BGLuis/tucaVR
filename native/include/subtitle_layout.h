#pragma once

#include <cstdint>
#include <cstddef>
#include <algorithm>

namespace vrplayer {

struct PgsSubtitleInfo {
    uint16_t x;
    uint16_t y;
    uint16_t width;
    uint16_t height;
    uint16_t screen_width;
    uint16_t screen_height;
    uint64_t start_ms;
    uint64_t end_ms;
};

struct AssSpanFfi {
    uint32_t char_offset;
    uint32_t char_length;
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
    uint8_t bold;
    uint8_t italic;
    uint16_t _reserved;
    float font_size;
};

struct AssSubtitleInfo {
    uint32_t alignment; // 1..9 (numpad)
    uint32_t has_pos;   // 1 se \pos, 0 se alinhamento
    float pos_x;
    float pos_y;
    float play_res_x;
    float play_res_y;
    uint64_t start_ms;
    uint64_t end_ms;
    uint32_t span_count;
};

// Calcula a posição e escala do quad de textura PGS sobre a tela virtual
inline bool ComputePgsQuadBounds(
    const PgsSubtitleInfo& info,
    float screenScaleX,
    float screenScaleY,
    bool sphereMode,
    float& outPosX,
    float& outPosY,
    float& outScaleX,
    float& outScaleY) {
    if (info.width == 0 || info.height == 0 || info.screen_width == 0 || info.screen_height == 0) {
        return false;
    }

    float centerX = static_cast<float>(info.x) + static_cast<float>(info.width) * 0.5f;
    float centerY = static_cast<float>(info.y) + static_cast<float>(info.height) * 0.5f;

    // Normalizado [-0.5, 0.5] relativo ao centro da tela
    float normX = (centerX / static_cast<float>(info.screen_width)) - 0.5f;
    // Y do PGS vai do topo (0) para a base; no espaço 3D/VR, Y aponta para cima
    float normY = 0.5f - (centerY / static_cast<float>(info.screen_height));

    float normW = static_cast<float>(info.width) / static_cast<float>(info.screen_width);
    float normH = static_cast<float>(info.height) / static_cast<float>(info.screen_height);

    if (sphereMode) {
        // No modo esfera, a projeção cobre o campo visual frontal
        outPosX = normX * 2.0f;
        outPosY = -0.40f + normY * 1.5f;
        outScaleX = normW * 2.0f;
        outScaleY = normH * 1.5f;
    } else {
        // No modo plano (2D/SBS/OU), escala diretamente pelas dimensões da tela virtual
        outPosX = normX * screenScaleX;
        outPosY = normY * screenScaleY;
        outScaleX = normW * screenScaleX;
        outScaleY = normH * screenScaleY;
    }
    return true;
}

// Calcula os deslocamentos X e Y do texto ASS considerando \pos ou \an (1..9)
inline void ComputeAssOffsets(
    const AssSubtitleInfo& info,
    float screenScaleX,
    float screenScaleY,
    bool sphereMode,
    float& outOffsetX,
    float& outOffsetY) {
    if (info.has_pos != 0) {
        float playResX = (info.play_res_x > 0.0f) ? info.play_res_x : 384.0f;
        float playResY = (info.play_res_y > 0.0f) ? info.play_res_y : 288.0f;

        float normX = (info.pos_x / playResX) - 0.5f;
        float normY = 0.5f - (info.pos_y / playResY);

        if (sphereMode) {
            outOffsetX = normX * 2.0f;
            outOffsetY = normY * 1.5f;
        } else {
            outOffsetX = normX * screenScaleX;
            outOffsetY = normY * screenScaleY;
        }
        return;
    }

    uint32_t align = (info.alignment >= 1 && info.alignment <= 9) ? info.alignment : 2;

    // Horizontal: 1, 4, 7 = Esquerda; 2, 5, 8 = Centro; 3, 6, 9 = Direita
    if (align == 1 || align == 4 || align == 7) {
        outOffsetX = sphereMode ? -0.50f : -screenScaleX * 0.35f;
    } else if (align == 3 || align == 6 || align == 9) {
        outOffsetX = sphereMode ? 0.50f : screenScaleX * 0.35f;
    } else {
        outOffsetX = 0.0f;
    }

    // Vertical: 7, 8, 9 = Superior; 4, 5, 6 = Central; 1, 2, 3 = Inferior
    if (align >= 7 && align <= 9) {
        outOffsetY = sphereMode ? 0.40f : screenScaleY * 0.42f;
    } else if (align >= 4 && align <= 6) {
        outOffsetY = 0.0f;
    } else {
        outOffsetY = sphereMode ? -0.40f : -screenScaleY * 0.42f;
    }
}

// Calcula o alinhamento horizontal inicial de cada linha de texto
inline float ComputeAssLineCursorX(uint32_t alignment, float lineWidth, float maxLineWidth) {
    uint32_t align = (alignment >= 1 && alignment <= 9) ? alignment : 2;
    if (align == 1 || align == 4 || align == 7) {
        // Alinhado à esquerda
        return -maxLineWidth * 0.5f;
    } else if (align == 3 || align == 6 || align == 9) {
        // Alinhado à direita
        return (maxLineWidth * 0.5f) - lineWidth;
    } else {
        // Centralizado
        return -lineWidth * 0.5f;
    }
}

// Encontra o span correspondente ao deslocamento de bytes no texto
inline const AssSpanFfi* FindSpanForByteOffset(
    const AssSpanFfi* spans,
    uint32_t spanCount,
    uint32_t byteOffset) {
    if (!spans || spanCount == 0) return nullptr;
    for (uint32_t i = 0; i < spanCount; ++i) {
        if (byteOffset >= spans[i].char_offset &&
            byteOffset < spans[i].char_offset + spans[i].char_length) {
            return &spans[i];
        }
    }
    return nullptr;
}

} // namespace vrplayer
