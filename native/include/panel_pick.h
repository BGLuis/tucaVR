#pragma once

#include <limits>

namespace vrplayer {

// Valores espelham currentHitPanel em vr_player_input_vulkan.h.
enum class Panel { None = 0, Ui = 1, Controls = 2, Modal = 3, Browser = 4 };

// Escolhe o painel atingido mais próximo (menor t > 0). O modal fica de fora:
// quando ativo ele tem foco exclusivo e é tratado antes desta escolha.
// Em empate de distância prevalece a ordem Browser > Controls > Ui.
inline Panel PickNearestPanel(float tUi, float tControls, float tBrowser) {
    Panel best = Panel::None;
    float bestT = std::numeric_limits<float>::max();
    auto consider = [&](Panel panel, float t) {
        if (t > 0.0f && t < bestT) {
            best = panel;
            bestT = t;
        }
    };
    consider(Panel::Browser, tBrowser);
    consider(Panel::Controls, tControls);
    consider(Panel::Ui, tUi);
    return best;
}

} // namespace vrplayer
