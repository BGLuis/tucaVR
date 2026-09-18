#include <cassert>
#include <iostream>

// Maquina de estados pura de debouncing e deteccao de borda
// reproduzindo exatamente o contrato de vr_player_input_vulkan.h:600-635.
struct InputFsmState {
    bool prevA = false;
    bool prevX = false;
    bool prevB = false;
    bool prevY = false;
    bool prevMenu = false;
    bool prevTrigger = false;
    float menuHoldTime = 0.0f;
    bool recenterFiredThisHold = false;
    float uiIdleTime = 0.0f;
    float controlsIdleTime = 0.0f;
    float controlsAlpha = 1.0f;

    // Metricas de observabilidade do teste
    int playPauseToggleCount = 0;
    int uiToggleCount = 0;
    int recenterCount = 0;
};

inline void ProcessButtonInputs(InputFsmState& state, bool currA, bool currX, bool currB, bool currY, bool currMenu, float dt) {
    constexpr float kRecenterHoldSeconds = 0.75f;
    constexpr float kUiAutoHideSeconds = 5.0f;

    // A (direita) ou X (esquerda) = alterna Play/Pause na borda de subida (curr && !prev)
    if ((currA && !state.prevA) || (currX && !state.prevX)) {
        state.playPauseToggleCount++;
        state.controlsIdleTime = 0.0f;
        state.controlsAlpha = 1.0f;
    }

    // B (direita) ou Y (esquerda) = alterna visibilidade da UI na borda de subida
    if ((currB && !state.prevB) || (currY && !state.prevY)) {
        bool isCurrentlyVisible = state.uiIdleTime < kUiAutoHideSeconds;
        state.uiIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
        state.controlsIdleTime = 0.0f;
        state.uiToggleCount++;
    }

    // Regra C-01: salvar estado anterior obrigatoriamente a cada frame
    state.prevA = currA;
    state.prevX = currX;
    state.prevB = currB;
    state.prevY = currY;

    // Menu: long-press >= 0.75s faz recenter; short-press no RELEASE faz toggle UI
    if (currMenu) {
        state.menuHoldTime += dt;
        if (!state.recenterFiredThisHold && state.menuHoldTime >= kRecenterHoldSeconds) {
            state.recenterCount++;
            state.recenterFiredThisHold = true;
        }
    } else {
        if (state.prevMenu && !state.recenterFiredThisHold) {
            state.uiToggleCount++;
        }
        state.menuHoldTime = 0.0f;
        state.recenterFiredThisHold = false;
    }
    state.prevMenu = currMenu;
}

inline void ProcessTriggerInputs(InputFsmState& state, bool currTrigger, int dispatchHitPanel,
                                 bool handTrackingActive, bool isHoveringScreen, bool isSphereMode) {
    bool triggerOutsideUi = currTrigger && !state.prevTrigger && (dispatchHitPanel == 0);
    bool handTrackingGrabPinch = handTrackingActive && isHoveringScreen && !isSphereMode;
    bool triggerPlayPause = triggerOutsideUi && !handTrackingGrabPinch;

    if (triggerPlayPause) {
        state.playPauseToggleCount++;
        state.controlsIdleTime = 0.0f;
        state.controlsAlpha = 1.0f;
    }
    state.prevTrigger = currTrigger;
}

static void TestButtonAHoldTriggersOnlyOnce() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f; // ~11.1ms por frame (90Hz)

    // Frame 1: usuario aperta o botao A
    ProcessButtonInputs(state, /*currA=*/true, false, false, false, false, dt);
    assert(state.playPauseToggleCount == 1);

    // Frames 2 a 100: usuario mantem o botao A pressionado por ~1 segundo
    for (int i = 2; i <= 100; i++) {
        ProcessButtonInputs(state, /*currA=*/true, false, false, false, false, dt);
        // Invariante C-01: NAO pode disparar continuamente a 90Hz
        assert(state.playPauseToggleCount == 1);
    }

    // Frame 101: solta o botao A
    ProcessButtonInputs(state, /*currA=*/false, false, false, false, false, dt);
    assert(state.playPauseToggleCount == 1);

    // Frame 102: aperta novamente -> agora deve disparar o segundo toggle
    ProcessButtonInputs(state, /*currA=*/true, false, false, false, false, dt);
    assert(state.playPauseToggleCount == 2);

    std::cout << "[PASS] TestButtonAHoldTriggersOnlyOnce (Invariante C-01 validada)\n";
}

static void TestButtonXHoldTriggersOnlyOnce() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    // Frame 1: usuario aperta X (controle esquerdo)
    ProcessButtonInputs(state, false, /*currX=*/true, false, false, false, dt);
    assert(state.playPauseToggleCount == 1);

    // 50 frames mantendo X pressionado
    for (int i = 2; i <= 50; i++) {
        ProcessButtonInputs(state, false, /*currX=*/true, false, false, false, dt);
        assert(state.playPauseToggleCount == 1);
    }

    std::cout << "[PASS] TestButtonXHoldTriggersOnlyOnce\n";
}

static void TestMenuButtonShortPressVsLongPress() {
    const float dt = 1.0f / 90.0f;

    // Caso 1: Short press (aperta por 100ms e solta)
    {
        InputFsmState state;
        // Pressiona por 9 frames (~100ms)
        for (int i = 0; i < 9; i++) {
            ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/true, dt);
            assert(state.recenterCount == 0);
            assert(state.uiToggleCount == 0);
        }
        // Solta o Menu
        ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/false, dt);
        // Short press deve ter disparado toggle da UI e NENHUM recenter
        assert(state.uiToggleCount == 1);
        assert(state.recenterCount == 0);
    }

    // Caso 2: Long press (aperta por 800ms >= 750ms e solta)
    {
        InputFsmState state;
        // Pressiona por 75 frames (~833ms)
        for (int i = 0; i < 75; i++) {
            ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/true, dt);
        }
        // Recenter deve ter disparado exatamente uma vez
        assert(state.recenterCount == 1);
        assert(state.uiToggleCount == 0);

        // Solta o botao Menu
        ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/false, dt);
        // O release NÃO deve disparar o toggle de UI porque o long press consumiu o gesto
        assert(state.uiToggleCount == 0);
        assert(state.recenterCount == 1);
    }

    std::cout << "[PASS] TestMenuButtonShortPressVsLongPress\n";
}

static void TestTriggerPlayPauseInSphericalAnd2DModes() {
    // Caso 1: Modo Esférico (180/360) via controle Touch Plus - clicar na tela deve pausar
    {
        InputFsmState state;
        // Frame 1: Clica com gatilho apontando para a tela 360 (dispatchHitPanel=0, isSphereMode=true)
        ProcessTriggerInputs(state, /*currTrigger=*/true, /*dispatchHitPanel=*/0,
                             /*handTrackingActive=*/false, /*isHoveringScreen=*/true, /*isSphereMode=*/true);
        assert(state.playPauseToggleCount == 1);

        // Mantém pressionado por vários frames: não pode disparar novamente
        ProcessTriggerInputs(state, /*currTrigger=*/true, 0, false, true, true);
        ProcessTriggerInputs(state, /*currTrigger=*/true, 0, false, true, true);
        assert(state.playPauseToggleCount == 1);

        // Solta o gatilho
        ProcessTriggerInputs(state, /*currTrigger=*/false, 0, false, true, true);
        assert(state.playPauseToggleCount == 1);

        // Clica novamente -> alterna de novo
        ProcessTriggerInputs(state, /*currTrigger=*/true, 0, false, true, true);
        assert(state.playPauseToggleCount == 2);
    }

    // Caso 2: Clicar sobre botão da UI (dispatchHitPanel != 0) NÃO deve disparar o atalho de tela
    {
        InputFsmState state;
        ProcessTriggerInputs(state, /*currTrigger=*/true, /*dispatchHitPanel=*/2,
                             /*handTrackingActive=*/false, /*isHoveringScreen=*/false, /*isSphereMode=*/false);
        assert(state.playPauseToggleCount == 0);
    }

    // Caso 3: Hand Tracking em tela 2D reservado para Grab & Drag - não deve pausar
    {
        InputFsmState state;
        ProcessTriggerInputs(state, /*currTrigger=*/true, /*dispatchHitPanel=*/0,
                             /*handTrackingActive=*/true, /*isHoveringScreen=*/true, /*isSphereMode=*/false);
        assert(state.playPauseToggleCount == 0);
    }

    std::cout << "[PASS] TestTriggerPlayPauseInSphericalAnd2DModes\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de Input FSM (C-01) ---\n";
    TestButtonAHoldTriggersOnlyOnce();
    TestButtonXHoldTriggersOnlyOnce();
    TestMenuButtonShortPressVsLongPress();
    TestTriggerPlayPauseInSphericalAnd2DModes();
    std::cout << "--- Todos os testes de Input FSM passaram com sucesso! ---\n";
    return 0;
}
