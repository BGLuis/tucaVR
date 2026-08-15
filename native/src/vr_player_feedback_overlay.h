#pragma once

#include <cstdint>

// Overlay de feedback de acao (play/pause/seek) desenhado sobre a tela de
// video. Geometria vetorial pura (triangle list, sem textura/asset nenhum) —
// compartilhada pelos DOIS caminhos de render (GLES em vr_player_app.cpp,
// Vulkan em vr_player_app_vulkan.cpp) justamente pra os icones nao divergirem
// entre eles. O estado (tipo + sequencia) vem do bridge Rust em
// get_playback_feedback_event(); ver FEEDBACK_EVENT em rust/bridge/src/lib.rs.
namespace vrplayer {

// DEVE casar com a codificacao de FEEDBACK_EVENT em rust/bridge/src/lib.rs.
enum class FeedbackKind : uint32_t {
    None = 0,
    Play = 1,
    Pause = 2,
    SeekForward = 3,
    SeekBackward = 4,
};

// Tempo em alpha cheio antes de comecar a sumir. O fade em si reusa
// kUiFadeDuration (0.35s) dos paineis, pelo mesmo MoveTowards por frame.
constexpr float kFeedbackHoldSeconds = 0.6f;
// Lado (em metros) do quadrado que envolve o icone, na posicao/orientacao da
// tela de video. Pequeno de proposito: e um flash de confirmacao, nao UI.
constexpr float kFeedbackScale = 0.3f;
// Deslocamento em Z local (normal da tela, apontando pro usuario) pra o icone
// nao ficar coplanar com o quad de video.
constexpr float kFeedbackDepthOffset = 0.02f;

// Vertices XY (Z=0 implicito) em espaco local -0.5..0.5, triangle list.
struct FeedbackShape {
    const float* xy;
    uint32_t vertexCount;
};

inline constexpr float kFeedbackPlayXY[] = {
    -0.22f, -0.40f,  0.38f, 0.0f,  -0.22f, 0.40f,
};

inline constexpr float kFeedbackPauseXY[] = {
    -0.30f, -0.40f, -0.10f, -0.40f, -0.10f, 0.40f,
    -0.30f, -0.40f, -0.10f,  0.40f, -0.30f, 0.40f,
     0.10f, -0.40f,  0.30f, -0.40f,  0.30f, 0.40f,
     0.10f, -0.40f,  0.30f,  0.40f,  0.10f, 0.40f,
};

inline constexpr float kFeedbackSeekForwardXY[] = {
    -0.40f, -0.34f,  0.02f, 0.0f, -0.40f, 0.34f,
     0.00f, -0.34f,  0.42f, 0.0f,  0.00f, 0.34f,
};

inline constexpr float kFeedbackSeekBackwardXY[] = {
    0.40f, -0.34f, 0.40f, 0.34f, -0.02f, 0.0f,
    0.00f, -0.34f, 0.00f, 0.34f, -0.42f, 0.0f,
};

inline FeedbackShape GetFeedbackShape(FeedbackKind kind) {
    switch (kind) {
        case FeedbackKind::Play:
            return {kFeedbackPlayXY, 3};
        case FeedbackKind::Pause:
            return {kFeedbackPauseXY, 12};
        case FeedbackKind::SeekForward:
            return {kFeedbackSeekForwardXY, 6};
        case FeedbackKind::SeekBackward:
            return {kFeedbackSeekBackwardXY, 6};
        default:
            return {nullptr, 0};
    }
}

// Numero de valores de FeedbackKind (incluindo None) — o caminho Vulkan
// indexa por kind um array de offsets no vertex buffer concatenado.
constexpr uint32_t kFeedbackKindCount = 5;

} // namespace vrplayer
