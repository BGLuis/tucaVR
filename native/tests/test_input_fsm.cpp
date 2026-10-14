#include <cassert>
#include <cmath>
#include <iostream>

// Maquina de estados pura de debouncing e deteccao de borda
// reproduzindo exatamente o contrato de vr_player_input_vulkan.h.
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
    float uiAlpha = 1.0f;
    bool controlsExplicitlyDismissed = false;
    float controllerStationaryTime = 0.0f;
    bool isControllerStationary = false;

    // Metricas de observabilidade do teste
    int playPauseToggleCount = 0;
    int uiToggleCount = 0;
    int hierarchicalBackCount = 0;
    int recenterCount = 0;
    float screenPosX = 0.0f;
    float screenPosY = 0.0f;
    float screenPosZ = 0.0f;
};

inline void ProcessButtonInputs(InputFsmState &state, bool currA, bool currX, bool currB, bool currY, bool currMenu,
                                float dt, bool keyboardActive = false, bool backHandledByUi = false,
                                float defaultPosX = 0.0f, float defaultPosY = 1.5f, float defaultPosZ = -2.0f) {
    constexpr float kRecenterHoldSeconds = 0.75f;
    constexpr float kUiAutoHideSeconds = 5.0f;

    // Borda de subida de acoes intencionais limpa dispensa explicita
    if ((currA && !state.prevA) || (currX && !state.prevX)) {
        state.controlsExplicitlyDismissed = false;
    }

    // A (direita) ou X (esquerda) = alterna Play/Pause na borda de subida (curr && !prev)
    if (((currA && !state.prevA) || (currX && !state.prevX)) && !keyboardActive) {
        state.playPauseToggleCount++;
        state.controlsIdleTime = 0.0f;
        state.controlsAlpha = 1.0f;
        state.controlsExplicitlyDismissed = false;
    }

    // B (direita) ou Y (esquerda) = navegação hierárquica para trás (Issue #87).
    if (((currB && !state.prevB) || (currY && !state.prevY)) && !keyboardActive) {
        bool controlsVisible = (state.controlsAlpha > 0.05f) || (state.controlsIdleTime < kUiAutoHideSeconds);
        bool uiVisible = (state.uiAlpha > 0.05f) || (state.uiIdleTime < kUiAutoHideSeconds);

        if (uiVisible && backHandledByUi) {
            state.hierarchicalBackCount++;
        } else {
            if (controlsVisible || uiVisible) {
                state.uiIdleTime = kUiAutoHideSeconds;
                state.controlsIdleTime = kUiAutoHideSeconds;
                state.controlsAlpha = 0.0f;
                state.uiAlpha = 0.0f;
                state.controlsExplicitlyDismissed = true;
            } else {
                state.uiIdleTime = 0.0f;
                state.controlsIdleTime = 0.0f;
                state.controlsAlpha = 1.0f;
                state.uiAlpha = 1.0f;
                state.controlsExplicitlyDismissed = false;
            }
            state.uiToggleCount++;
        }
    }

    // Regra C-01: salvar estado anterior obrigatoriamente a cada frame
    state.prevA = currA;
    state.prevX = currX;
    state.prevB = currB;
    state.prevY = currY;

    // Menu: long-press >= 0.75s faz recenter; short-press no RELEASE faz toggle UI e controles
    if (currMenu) {
        state.menuHoldTime += dt;
        if (!state.recenterFiredThisHold && state.menuHoldTime >= kRecenterHoldSeconds) {
            state.recenterCount++;
            state.screenPosX = defaultPosX;
            state.screenPosY = defaultPosY;
            state.screenPosZ = defaultPosZ;
            state.recenterFiredThisHold = true;
        }
    } else {
        if (state.prevMenu && !state.recenterFiredThisHold && !keyboardActive) {
            bool controlsVisible = (state.controlsAlpha > 0.05f) || (state.controlsIdleTime < kUiAutoHideSeconds);
            bool uiVisible = (state.uiAlpha > 0.05f) || (state.uiIdleTime < kUiAutoHideSeconds);
            if (controlsVisible || uiVisible) {
                state.uiIdleTime = kUiAutoHideSeconds;
                state.controlsIdleTime = kUiAutoHideSeconds;
                state.controlsAlpha = 0.0f;
                state.uiAlpha = 0.0f;
                state.controlsExplicitlyDismissed = true;
            } else {
                state.uiIdleTime = 0.0f;
                state.controlsIdleTime = 0.0f;
                state.controlsAlpha = 1.0f;
                state.uiAlpha = 1.0f;
                state.controlsExplicitlyDismissed = false;
            }
            state.uiToggleCount++;
        }
        state.menuHoldTime = 0.0f;
        state.recenterFiredThisHold = false;
    }
    state.prevMenu = currMenu;
}

inline void ProcessTriggerInputs(InputFsmState &state, bool currTrigger, int dispatchHitPanel, bool handTrackingActive,
                                 bool isHoveringScreen, bool isSphereMode) {
    if (currTrigger && !state.prevTrigger) {
        state.controlsExplicitlyDismissed = false;
    }

    bool triggerOutsideUi = currTrigger && !state.prevTrigger && (dispatchHitPanel == 0);
    bool handTrackingGrabPinch = handTrackingActive && isHoveringScreen && !isSphereMode;
    bool triggerPlayPause = triggerOutsideUi && !handTrackingGrabPinch;

    if (triggerPlayPause) {
        state.playPauseToggleCount++;
        state.controlsIdleTime = 0.0f;
        state.controlsAlpha = 1.0f;
        state.controlsExplicitlyDismissed = false;
    }
    state.prevTrigger = currTrigger;
}

inline void UpdateStationaryDetection(InputFsmState &state, bool isStill, float dt) {
    if (isStill) {
        state.controllerStationaryTime += dt;
        if (state.controllerStationaryTime >= 1.0f) {
            state.isControllerStationary = true;
        }
    } else {
        state.controllerStationaryTime = 0.0f;
        state.isControllerStationary = false;
    }
}

inline void UpdateIdleAndAlpha(InputFsmState &state, bool hitScreen, int currentHitPanel, bool isPlaying, float dt) {
    constexpr float kUiAutoHideSeconds = 5.0f;
    constexpr float kUiAutoHideStationarySeconds = 1.0f;
    constexpr float kUiFadeDuration = 0.35f;

    bool activeScreenHover = hitScreen && !state.isControllerStationary && !state.controlsExplicitlyDismissed;
    bool activePanelHover =
        (currentHitPanel == 2) && !state.controlsExplicitlyDismissed && (!state.isControllerStationary || !isPlaying);
    bool keepPausedVisible = !isPlaying && !state.controlsExplicitlyDismissed;

    if (activePanelHover || activeScreenHover || keepPausedVisible) {
        state.controlsIdleTime = 0.0f;
    } else {
        state.controlsIdleTime += dt;
    }

    if (currentHitPanel == 1) {
        state.uiIdleTime = 0.0f;
    } else {
        state.uiIdleTime += dt;
    }

    float effectiveControlsTimeout =
        (state.isControllerStationary && isPlaying) ? kUiAutoHideStationarySeconds : kUiAutoHideSeconds;
    float effectiveUiTimeout = state.isControllerStationary ? kUiAutoHideStationarySeconds : kUiAutoHideSeconds;

    float uiTargetAlpha = (state.uiIdleTime < effectiveUiTimeout) ? 1.0f : 0.0f;
    float controlsTargetAlpha = (state.controlsIdleTime < effectiveControlsTimeout) ? 1.0f : 0.0f;

    float fadeStep = (kUiFadeDuration > 0.0f) ? dt / kUiFadeDuration : 1.0f;
    auto MoveTowards = [](float current, float target, float maxDelta) {
        if (std::abs(target - current) <= maxDelta)
            return target;
        return current + ((target > current) ? maxDelta : -maxDelta);
    };
    state.uiAlpha = MoveTowards(state.uiAlpha, uiTargetAlpha, fadeStep);
    state.controlsAlpha = MoveTowards(state.controlsAlpha, controlsTargetAlpha, fadeStep);
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

    // Caso 4: Hand Tracking desativado (handTrackingActive=false) em modo esférico - trigger alterna reprodução
    {
        InputFsmState state;
        ProcessTriggerInputs(state, /*currTrigger=*/true, /*dispatchHitPanel=*/0,
                             /*handTrackingActive=*/false, /*isHoveringScreen=*/true, /*isSphereMode=*/true);
        assert(state.playPauseToggleCount == 1);
    }

    std::cout << "[PASS] TestTriggerPlayPauseInSphericalAnd2DModes\n";
}

static void TestButtonBAndYDismissControlsInstantly() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    // Inicialmente controles e UI estão visíveis
    state.controlsAlpha = 1.0f;
    state.uiAlpha = 1.0f;
    state.controlsIdleTime = 0.0f;
    state.uiIdleTime = 0.0f;

    // Frame 1: Pressiona B -> deve dispensar imediatamente
    ProcessButtonInputs(state, false, false, /*currB=*/true, false, false, dt);
    assert(state.controlsAlpha == 0.0f);
    assert(state.uiAlpha == 0.0f);
    assert(state.controlsIdleTime >= 5.0f);
    assert(state.uiIdleTime >= 5.0f);
    assert(state.controlsExplicitlyDismissed == true);

    // Frame 2-30: Ray paira sobre a tela (hitScreen = true).
    // Como controlsExplicitlyDismissed é true, NÃO reseta o idle time e não reabre os controles.
    for (int i = 0; i < 30; i++) {
        UpdateIdleAndAlpha(state, /*hitScreen=*/true, /*currentHitPanel=*/0, /*isPlaying=*/true, dt);
        assert(state.controlsAlpha == 0.0f);
        assert(state.controlsExplicitlyDismissed == true);
    }

    // Solta o botão B
    ProcessButtonInputs(state, false, false, /*currB=*/false, false, false, dt);

    // Pressiona Y (controle esquerdo) -> deve reabrir imediatamente
    ProcessButtonInputs(state, false, false, false, /*currY=*/true, false, dt);
    assert(state.controlsAlpha == 1.0f);
    assert(state.uiAlpha == 1.0f);
    assert(state.controlsIdleTime == 0.0f);
    assert(state.controlsExplicitlyDismissed == false);

    // Solta Y
    ProcessButtonInputs(state, false, false, false, /*currY=*/false, false, dt);

    // Pressiona Y novamente para dispensar
    ProcessButtonInputs(state, false, false, false, /*currY=*/true, false, dt);
    assert(state.controlsAlpha == 0.0f);
    assert(state.controlsExplicitlyDismissed == true);

    // Ação intencional do usuário: pressiona A (play/pause) -> limpa dispensa explícita e reabre controles
    ProcessButtonInputs(state, /*currA=*/true, false, false, false, false, dt);
    assert(state.controlsExplicitlyDismissed == false);
    assert(state.controlsAlpha == 1.0f);

    std::cout << "[PASS] TestButtonBAndYDismissControlsInstantly\n";
}

static void TestMenuButtonShortPressDismissesControls() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    state.controlsAlpha = 1.0f;
    state.uiAlpha = 1.0f;
    state.controlsIdleTime = 0.0f;
    state.uiIdleTime = 0.0f;

    // Pressiona Menu por 5 frames (~55ms) e solta
    for (int i = 0; i < 5; i++) {
        ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/true, dt);
    }
    // Ao soltar (release), deve dispensar controles e UI
    ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/false, dt);
    assert(state.controlsAlpha == 0.0f);
    assert(state.uiAlpha == 0.0f);
    assert(state.controlsExplicitlyDismissed == true);

    // Pressiona Menu novamente por 5 frames e solta -> reabre
    for (int i = 0; i < 5; i++) {
        ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/true, dt);
    }
    ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/false, dt);
    assert(state.controlsAlpha == 1.0f);
    assert(state.uiAlpha == 1.0f);
    assert(state.controlsExplicitlyDismissed == false);

    std::cout << "[PASS] TestMenuButtonShortPressDismissesControls\n";
}

static void TestStationaryControllerAcceleratesAutoHide() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    state.controlsAlpha = 1.0f;
    state.controlsIdleTime = 0.0f;
    state.isControllerStationary = false;
    state.controllerStationaryTime = 0.0f;

    // Caso 1: Na mão com tremor fisiológico (isStill = false) apontando para a tela durante reprodução
    // Controles devem permanecer visíveis indefinidamente
    for (int i = 0; i < 200; i++) {
        UpdateStationaryDetection(state, /*isStill=*/false, dt);
        UpdateIdleAndAlpha(state, /*hitScreen=*/true, /*currentHitPanel=*/0, /*isPlaying=*/true, dt);
    }
    assert(state.isControllerStationary == false);
    assert(state.controlsIdleTime == 0.0f);
    assert(state.controlsAlpha == 1.0f);

    // Caso 2: Controle é solto sobre a mesa (isStill = true)
    // Nos primeiros 90 frames (~1.0s), o estado stationary é detectado
    for (int i = 0; i < 90; i++) {
        UpdateStationaryDetection(state, /*isStill=*/true, dt);
        UpdateIdleAndAlpha(state, /*hitScreen=*/true, /*currentHitPanel=*/0, /*isPlaying=*/true, dt);
    }
    assert(state.isControllerStationary == true);

    // Uma vez estacionário, mesmo que a ponta do raio atinja a tela (hitScreen = true),
    // o idleTime começa a acumular porque não há mão viva operando.
    // O timeout acelerado de repouso é de 1.0s.
    // Após 1.0s + tempo de fade (0.35s = ~32 frames), os controles devem sumir (alpha -> 0.0f)
    for (int i = 0; i < 150; i++) { // ~1.66s adicionais
        UpdateStationaryDetection(state, /*isStill=*/true, dt);
        UpdateIdleAndAlpha(state, /*hitScreen=*/true, /*currentHitPanel=*/0, /*isPlaying=*/true, dt);
    }
    assert(state.controlsAlpha <= 0.01f);

    // Caso 3: Usuário pega o controle de volta (isStill = false)
    UpdateStationaryDetection(state, /*isStill=*/false, dt);
    assert(state.isControllerStationary == false);

    // Agora apontar para a tela acorda os controles imediatamente
    UpdateIdleAndAlpha(state, /*hitScreen=*/true, /*currentHitPanel=*/0, /*isPlaying=*/true, dt);
    assert(state.controlsIdleTime == 0.0f);

    std::cout << "[PASS] TestStationaryControllerAcceleratesAutoHide\n";
}

static void TestButtonBYHierarchicalBackAndKeyboard() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    state.controlsAlpha = 1.0f;
    state.uiAlpha = 1.0f;

    // Cenário 1: Teclado ativo -> B e Y são ignorados pela UI FSM
    ProcessButtonInputs(state, false, false, /*currB=*/true, false, false, dt, /*keyboardActive=*/true);
    assert(state.uiToggleCount == 0);
    assert(state.hierarchicalBackCount == 0);
    assert(state.uiAlpha == 1.0f);
    ProcessButtonInputs(state, false, false, /*currB=*/false, false, false, dt, /*keyboardActive=*/true);

    // Cenário 2: UI aberta numa subpasta (backHandledByUi = true)
    // Pressionar B deve ser consumido pela UI sem fechar o painel
    ProcessButtonInputs(state, false, false, /*currB=*/true, false, false, dt, /*keyboardActive=*/false,
                        /*backHandledByUi=*/true);
    assert(state.hierarchicalBackCount == 1);
    assert(state.uiToggleCount == 0);
    assert(state.uiAlpha == 1.0f);
    ProcessButtonInputs(state, false, false, /*currB=*/false, false, false, dt);

    // Cenário 3: UI na raiz (backHandledByUi = false)
    // Pressionar B agora fecha os painéis (dispensa rápida)
    ProcessButtonInputs(state, false, false, /*currB=*/true, false, false, dt, /*keyboardActive=*/false,
                        /*backHandledByUi=*/false);
    assert(state.uiToggleCount == 1);
    assert(state.uiAlpha == 0.0f);
    ProcessButtonInputs(state, false, false, /*currB=*/false, false, false, dt);

    // Cenário 4: Painéis fechados -> pressionar Y reabre
    ProcessButtonInputs(state, false, false, false, /*currY=*/true, false, dt, /*keyboardActive=*/false,
                        /*backHandledByUi=*/false);
    assert(state.uiToggleCount == 2);
    assert(state.uiAlpha == 1.0f);
    ProcessButtonInputs(state, false, false, false, /*currY=*/false, false, dt);

    std::cout << "[PASS] TestButtonBYHierarchicalBackAndKeyboard\n";
}

static void TestRecenterUsesDefaultPosition() {
    InputFsmState state;
    const float dt = 1.0f / 90.0f;

    // Segura Menu por 0.8s com ambiente configurado para z = -3.5m
    for (int i = 0; i < 75; i++) {
        ProcessButtonInputs(state, false, false, false, false, /*currMenu=*/true, dt,
                            /*keyboardActive=*/false, /*backHandledByUi=*/false,
                            /*defaultPosX=*/0.0f, /*defaultPosY=*/1.5f, /*defaultPosZ=*/-3.5f);
    }
    assert(state.recenterCount == 1);
    assert(state.screenPosZ == -3.5f);

    std::cout << "[PASS] TestRecenterUsesDefaultPosition\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de Input FSM (C-01) ---\n";
    TestButtonAHoldTriggersOnlyOnce();
    TestButtonXHoldTriggersOnlyOnce();
    TestMenuButtonShortPressVsLongPress();
    TestTriggerPlayPauseInSphericalAnd2DModes();
    TestButtonBAndYDismissControlsInstantly();
    TestButtonBYHierarchicalBackAndKeyboard();
    TestRecenterUsesDefaultPosition();
    TestMenuButtonShortPressDismissesControls();
    TestStationaryControllerAcceleratesAutoHide();
    std::cout << "--- Todos os testes de Input FSM passaram com sucesso! ---\n";
    return 0;
}
