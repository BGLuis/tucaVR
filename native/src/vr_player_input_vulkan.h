#pragma once
#include <openxr/openxr.h>
#include <jni.h>
#include <android_native_app_glue.h>
#include "vk_math.h"
#include "vr_player_feedback_overlay.h"
#include <math.h>
#include <algorithm>
#include <atomic>
#include <cstdint>

// Timings de auto-hide/recenter — mesmos valores do caminho GLES
// (vr_player_app.cpp: kUiAutoHideSeconds/kUiFadeDuration/kRecenterHoldSeconds).
constexpr float kUiAutoHideSeconds = 5.0f;
constexpr float kUiFadeDuration = 0.35f;
constexpr float kRecenterHoldSeconds = 0.6f;

inline float MoveTowards(float current, float target, float maxDelta) {
    if (fabs(target - current) <= maxDelta) return target;
    return current + (target > current ? maxDelta : -maxDelta);
}

inline void FireHaptic(AppState& state, XrPath hand, float amplitude, XrDuration durationNs) {
    if (state.hapticAction == XR_NULL_HANDLE) return;
    XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
    vibration.amplitude = amplitude;
    vibration.duration = durationNs;
    vibration.frequency = XR_FREQUENCY_UNSPECIFIED;
    XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};
    info.action = state.hapticAction;
    info.subactionPath = hand;
    xrApplyHapticFeedback(state.session, &info, (const XrHapticBaseHeader*)&vibration);
}

// Posicoes/escalas "base" dos paineis — mesmos valores do caminho GLES
// (vr_player_app.cpp: baseUiPos/baseControlsPos em Update()), pra manter os
// dois caminhos comparaveis visualmente. Transformadas por
// sceneTranslationOffset/sceneYawOffset em ComputeSceneTransforms abaixo.
//
// Distancia/escala aumentadas na revisao pos-migracao — os valores
// originais (-2.2,1.5,-1.5 / escala 0.8x0.6) colocavam o painel a ~2.66m
// com so 0.8m de largura, angulo visual pequeno demais (~17 graus) pro
// texto ficar legivel — bug reportado em teste real de hardware ("muito
// longe, nao da pra ler o texto"). Agora ~1.64m de distancia, 50% maior,
// mantendo a proporcao da textura (4:3 pro UI/kUiTexWidth/kUiTexHeight,
// 1582:800 pros controles/kControlsTexWidth/kControlsTexHeight).
constexpr XrVector3f kBaseUiPos = {-1.3f, 1.5f, -1.0f};
constexpr float kUiPanelScaleX = 1.2f;
constexpr float kUiPanelScaleY = 0.9f;
constexpr XrVector3f kBaseControlsPos = {0.0f, 0.4f, -1.3f};
constexpr float kControlsPanelScaleX = 1.2f;
constexpr float kControlsPanelScaleY = 1.2f * (800.0f / 1582.0f); // ~0.607m (aspect ratio 1582:800)
constexpr float kControlsPitch = -0.3f;
constexpr XrVector3f kBaseModalPos = {0.0f, 1.35f, -1.35f};
constexpr float kModalPanelScaleX = 1.2f;
constexpr float kModalPanelScaleY = 0.9f;

// Transforms de cena computados uma vez por frame e compartilhados entre
// UpdateInteraction (hit-test) e DrawUiQuads (render) — antes cada um
// recalculava isso de forma independente e ja tinham divergido (Estagio
// Camera, achado da revisao pos-migracao). Os *ModelNoScale sao rigidos
// (so rotacao+translacao) de proposito: Mat4RigidInverse usada no hit-test
// assume isso, entao a escala visual e aplicada separadamente (na hora de
// desenhar via Mat4Scale, na hora de testar dividindo o ponto local).
struct SceneTransforms {
    Mat4 uiModelNoScale;
    XrVector3f uiCenter;
    XrVector3f uiNormal;

    Mat4 controlsModelNoScale;
    XrVector3f controlsCenter;
    XrVector3f controlsNormal;

    Mat4 modalModelNoScale;
    XrVector3f modalCenter;
    XrVector3f modalNormal;

    Mat4 screenModelNoScale;
    XrVector3f screenCenter;
    XrVector3f screenNormal;
};

inline SceneTransforms ComputeSceneTransforms(const AppState& state, const XrVector3f& headCenter) {
    SceneTransforms t{};

    // UI: billboard - sempre virado pra cabeca (mesma logica do GLES:
    // vr_player_app.cpp, calculo de uiYaw a partir de toHead).
    XrVector3f worldUiPos = Vec3Add(state.sceneTranslationOffset, Vec3RotateY(kBaseUiPos, state.sceneYawOffset));
    XrVector3f toHead = Vec3Sub(headCenter, worldUiPos);
    toHead.y = 0.0f;
    float toHeadLen = sqrtf(toHead.x * toHead.x + toHead.z * toHead.z);
    float uiYaw = (toHeadLen > 1e-4f) ? atan2f(toHead.x / toHeadLen, toHead.z / toHeadLen) : 0.7f;
    t.uiModelNoScale = Mat4Multiply(Mat4Translation(worldUiPos.x, worldUiPos.y, worldUiPos.z), Mat4RotationY(uiYaw));
    t.uiCenter = worldUiPos;
    t.uiNormal = Vec3RotateY({0.0f, 0.0f, 1.0f}, uiYaw);

    // Controles: segue o yaw da cena (recenter) mais uma inclinacao fixa.
    XrVector3f worldControlsPos = Vec3Add(state.sceneTranslationOffset, Vec3RotateY(kBaseControlsPos, state.sceneYawOffset));
    Mat4 controlsRot = Mat4Multiply(Mat4RotationY(state.sceneYawOffset), Mat4RotationX(kControlsPitch));
    t.controlsModelNoScale = Mat4Multiply(Mat4Translation(worldControlsPos.x, worldControlsPos.y, worldControlsPos.z), controlsRot);
    t.controlsCenter = worldControlsPos;
    // RotationX(kControlsPitch).Transform(0,0,1) = (0, -sin(pitch), cos(pitch))
    // (ver convencao de Mat4RotationX em vk_math.h), depois rotacionado pelo yaw da cena.
    XrVector3f pitchedNormal = {0.0f, -sinf(kControlsPitch), cosf(kControlsPitch)};
    t.controlsNormal = Vec3RotateY(pitchedNormal, state.sceneYawOffset);

    // Modal (3o Quad frontal): alinhado com a tela de video e controles no centro da cena
    XrVector3f worldModalPos = Vec3Add(state.sceneTranslationOffset, Vec3RotateY(kBaseModalPos, state.sceneYawOffset));
    t.modalModelNoScale = Mat4Multiply(
        Mat4Translation(worldModalPos.x, worldModalPos.y, worldModalPos.z),
        Mat4RotationY(state.sceneYawOffset)
    );
    t.modalCenter = worldModalPos;
    t.modalNormal = Vec3RotateY({0.0f, 0.0f, 1.0f}, state.sceneYawOffset);

    // Tela virtual: posicao/escala ajustaveis via thumbstick (Estagio 6).
    XrVector3f worldScreenPos = Vec3Add(state.sceneTranslationOffset, Vec3RotateY(state.screenPosition, state.sceneYawOffset));
    t.screenModelNoScale = Mat4Multiply(Mat4Translation(worldScreenPos.x, worldScreenPos.y, worldScreenPos.z), Mat4RotationY(state.sceneYawOffset));
    t.screenCenter = worldScreenPos;
    t.screenNormal = Vec3RotateY({0.0f, 0.0f, 1.0f}, state.sceneYawOffset);

    return t;
}

inline void SetupOpenXrInputs(AppState& state) {
    XrActionSetCreateInfo actionSetInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(actionSetInfo.actionSetName, "gameplay");
    strcpy(actionSetInfo.localizedActionSetName, "Gameplay");
    OXR(xrCreateActionSet(state.instance, &actionSetInfo, &state.actionSet));

    XrPath handPaths[2];
    xrStringToPath(state.instance, "/user/hand/left", &handPaths[0]);
    xrStringToPath(state.instance, "/user/hand/right", &handPaths[1]);

    XrActionCreateInfo actionInfo{XR_TYPE_ACTION_CREATE_INFO};
    actionInfo.actionType = XR_ACTION_TYPE_POSE_INPUT;
    strcpy(actionInfo.actionName, "aim_pose");
    strcpy(actionInfo.localizedActionName, "Aim Pose");
    actionInfo.countSubactionPaths = 2;
    actionInfo.subactionPaths = handPaths;
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.aimAction));

    actionInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    strcpy(actionInfo.actionName, "trigger");
    strcpy(actionInfo.localizedActionName, "Trigger");
    actionInfo.countSubactionPaths = 2;
    actionInfo.subactionPaths = handPaths;
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.triggerAction));

    // Paridade com o caminho GLES (Estagio 6) — ver AppState pro que cada
    // action controla.
    actionInfo.countSubactionPaths = 0;
    actionInfo.subactionPaths = nullptr;

    actionInfo.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    strcpy(actionInfo.actionName, "a_click");
    strcpy(actionInfo.localizedActionName, "A Button");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.aButtonAction));

    strcpy(actionInfo.actionName, "x_click");
    strcpy(actionInfo.localizedActionName, "X Button");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.xButtonAction));

    strcpy(actionInfo.actionName, "b_click");
    strcpy(actionInfo.localizedActionName, "B Button");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.bButtonAction));

    strcpy(actionInfo.actionName, "y_click");
    strcpy(actionInfo.localizedActionName, "Y Button");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.yButtonAction));

    strcpy(actionInfo.actionName, "menu_click");
    strcpy(actionInfo.localizedActionName, "Menu Button");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.menuAction));

    actionInfo.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
    strcpy(actionInfo.actionName, "squeeze");
    strcpy(actionInfo.localizedActionName, "Squeeze");
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.squeezeAction));

    actionInfo.actionType = XR_ACTION_TYPE_VECTOR2F_INPUT;
    strcpy(actionInfo.actionName, "thumbstick");
    strcpy(actionInfo.localizedActionName, "Thumbstick");
    actionInfo.countSubactionPaths = 2;
    actionInfo.subactionPaths = handPaths;
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.thumbstickAction));

    actionInfo.actionType = XR_ACTION_TYPE_VIBRATION_OUTPUT;
    strcpy(actionInfo.actionName, "haptic");
    strcpy(actionInfo.localizedActionName, "Haptic");
    actionInfo.countSubactionPaths = 2;
    actionInfo.subactionPaths = handPaths;
    OXR(xrCreateAction(state.actionSet, &actionInfo, &state.hapticAction));

    XrPath selectPath[2];
    xrStringToPath(state.instance, "/user/hand/left/input/aim/pose", &selectPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/input/aim/pose", &selectPath[1]);
    XrPath triggerPath[2];
    xrStringToPath(state.instance, "/user/hand/left/input/trigger/value", &triggerPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/input/trigger/value", &triggerPath[1]);
    XrPath aPath, xPath, bPath, yPath, menuPath, squeezePath;
    xrStringToPath(state.instance, "/user/hand/right/input/a/click", &aPath);
    xrStringToPath(state.instance, "/user/hand/left/input/x/click", &xPath);
    xrStringToPath(state.instance, "/user/hand/right/input/b/click", &bPath);
    xrStringToPath(state.instance, "/user/hand/left/input/y/click", &yPath);
    xrStringToPath(state.instance, "/user/hand/left/input/menu/click", &menuPath);
    xrStringToPath(state.instance, "/user/hand/right/input/squeeze/value", &squeezePath);
    XrPath thumbstickPath[2];
    xrStringToPath(state.instance, "/user/hand/left/input/thumbstick", &thumbstickPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/input/thumbstick", &thumbstickPath[1]);
    XrPath hapticPath[2];
    xrStringToPath(state.instance, "/user/hand/left/output/haptic", &hapticPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/output/haptic", &hapticPath[1]);

    XrActionSuggestedBinding bindings[14];
    bindings[0]  = {state.aimAction, selectPath[0]};
    bindings[1]  = {state.aimAction, selectPath[1]};
    bindings[2]  = {state.triggerAction, triggerPath[0]};
    bindings[3]  = {state.triggerAction, triggerPath[1]};
    bindings[4]  = {state.aButtonAction, aPath};
    bindings[5]  = {state.xButtonAction, xPath};
    bindings[6]  = {state.bButtonAction, bPath};
    bindings[7]  = {state.yButtonAction, yPath};
    bindings[8]  = {state.menuAction, menuPath};
    bindings[9]  = {state.squeezeAction, squeezePath};
    bindings[10] = {state.thumbstickAction, thumbstickPath[0]};
    bindings[11] = {state.thumbstickAction, thumbstickPath[1]};
    bindings[12] = {state.hapticAction, hapticPath[0]};
    bindings[13] = {state.hapticAction, hapticPath[1]};

    XrPath profilePath;
    xrStringToPath(state.instance, "/interaction_profiles/oculus/touch_controller", &profilePath);
    XrInteractionProfileSuggestedBinding suggestedBindings{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggestedBindings.interactionProfile = profilePath;
    suggestedBindings.suggestedBindings = bindings;
    suggestedBindings.countSuggestedBindings = 14;
    OXR(xrSuggestInteractionProfileBindings(state.instance, &suggestedBindings));
}

inline void AttachInputsToSession(AppState& state) {
    if (state.actionSetsAttached) {
        return;
    }
    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &state.actionSet;
    OXR(xrAttachSessionActionSets(state.session, &attachInfo));
    state.actionSetsAttached = true;

    XrPath handPaths[2];
    xrStringToPath(state.instance, "/user/hand/left", &handPaths[0]);
    xrStringToPath(state.instance, "/user/hand/right", &handPaths[1]);

    XrActionSpaceCreateInfo actionSpaceInfo{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    actionSpaceInfo.action = state.aimAction;
    actionSpaceInfo.poseInActionSpace.orientation.w = 1.0f;
    actionSpaceInfo.subactionPath = handPaths[0];
    OXR(xrCreateActionSpace(state.session, &actionSpaceInfo, &state.aimSpaces[0]));
    actionSpaceInfo.subactionPath = handPaths[1];
    OXR(xrCreateActionSpace(state.session, &actionSpaceInfo, &state.aimSpaces[1]));
}

// `transformNoScale` PRECISA ser rigida (so rotacao+translacao, sem escala):
// Mat4RigidInverse assume isso (usa a transposta da rotacao como inversa, o
// que so vale para matrizes ortonormais). A escala visual do painel entra
// via scaleX/scaleY, dividindo o ponto ja em espaco local — matematicamente
// equivalente a ter a escala embutida na matriz (o parametro `t` da
// interseccao raio-plano nao depende da escala do objeto, so do plano em
// espaco de mundo), mas sem quebrar a premissa da inversa rigida.
inline float rayHitsQuad(const Mat4& transformNoScale, const XrVector3f& normal, const XrVector3f& center,
                         float scaleX, float scaleY,
                         float& outU, float& outV, const XrVector3f& rayOrigin, const XrVector3f& rayDir) {
    float dotDir = normal.x*rayDir.x + normal.y*rayDir.y + normal.z*rayDir.z;
    if (fabs(dotDir) <= 0.001f) return -1.0f;
    XrVector3f toCenter = {center.x - rayOrigin.x, center.y - rayOrigin.y, center.z - rayOrigin.z};
    float t = (normal.x*toCenter.x + normal.y*toCenter.y + normal.z*toCenter.z) / dotDir;
    if (t <= 0.0f) return -1.0f;
    XrVector3f hitPoint = {rayOrigin.x + rayDir.x * t, rayOrigin.y + rayDir.y * t, rayOrigin.z + rayDir.z * t};

    Mat4 inv = Mat4RigidInverse(transformNoScale);
    float localX = inv.m[0]*hitPoint.x + inv.m[4]*hitPoint.y + inv.m[8]*hitPoint.z + inv.m[12];
    float localY = inv.m[1]*hitPoint.x + inv.m[5]*hitPoint.y + inv.m[9]*hitPoint.z + inv.m[13];
    localX /= scaleX;
    localY /= scaleY;

    // Boundaries: -0.5 a 0.5 porque o quad local (sem escala) e 1x1.
    if (localX < -0.5f || localX > 0.5f || localY < -0.5f || localY > 0.5f) return -1.0f;

    outU = localX + 0.5f;
    outV = 0.5f - localY;
    return t;
}

inline void UpdateInteraction(AppState& state, XrTime predictedDisplayTime, XrVector3f headCenter,
                               const XrQuaternionf& headOrientation) {
    XrActiveActionSet activeActionSet{};
    activeActionSet.actionSet = state.actionSet;
    activeActionSet.subactionPath = XR_NULL_PATH;

    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeActionSet;
    xrSyncActions(state.session, &syncInfo);

    XrPath leftPath = XR_NULL_PATH, rightPath = XR_NULL_PATH;
    xrStringToPath(state.instance, "/user/hand/left", &leftPath);
    xrStringToPath(state.instance, "/user/hand/right", &rightPath);

    XrActionStateGetInfo triggerInfoL{XR_TYPE_ACTION_STATE_GET_INFO};
    triggerInfoL.action = state.triggerAction;
    triggerInfoL.subactionPath = leftPath;
    XrActionStateBoolean triggerL{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &triggerInfoL, &triggerL);

    XrActionStateGetInfo triggerInfoR{XR_TYPE_ACTION_STATE_GET_INFO};
    triggerInfoR.action = state.triggerAction;
    triggerInfoR.subactionPath = rightPath;
    XrActionStateBoolean triggerR{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &triggerInfoR, &triggerR);

    XrSpaceLocation locL{XR_TYPE_SPACE_LOCATION};
    XrSpaceLocation locR{XR_TYPE_SPACE_LOCATION};
    xrLocateSpace(state.aimSpaces[0], state.localSpace, predictedDisplayTime, &locL);
    xrLocateSpace(state.aimSpaces[1], state.localSpace, predictedDisplayTime, &locR);
    bool leftTracked = (locL.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
    bool rightTracked = (locR.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;

    // Mesma preferencia do caminho GLES (vr_player_app.cpp): usa a
    // esquerda se ela estiver rastreada e (a direita nao estiver rastreada
    // OU o trigger esquerdo estiver pressionado) — deixa o usuario apontar
    // com a mao que estiver usando ativamente.
    bool useLeft = leftTracked && (!rightTracked || triggerL.currentState == XR_TRUE);
    const XrSpaceLocation& spaceLocation = useLeft ? locL : locR;
    bool currTrigger = (useLeft ? triggerL.currentState : triggerR.currentState) == XR_TRUE;

    state.hasRay = (spaceLocation.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) != 0;
    if (state.hasRay) {
        state.lastRayOrigin = spaceLocation.pose.position;
        Mat4 rot = Mat4FromXrPose(spaceLocation.pose);
        state.lastRayDir = {-rot.m[8], -rot.m[9], -rot.m[10]}; // -Z axis
    }

    bool prevTrigger = state.isTriggerPressed;
    state.isTriggerPressed = currTrigger;

    if (::g_modalPanelShowRequested.exchange(false)) {
        state.modalActive = true;
    }
    if (::g_modalPanelHideRequested.exchange(false)) {
        state.modalActive = false;
    }
    ::g_modalPanelActive.store(state.modalActive);

    SceneTransforms scene = ComputeSceneTransforms(state, headCenter);

    float uu = 0, vu = 0, uc = 0, vc = 0, um = 0, vm = 0;
    float tUi = state.hasRay
        ? rayHitsQuad(scene.uiModelNoScale, scene.uiNormal, scene.uiCenter, kUiPanelScaleX, kUiPanelScaleY,
                      uu, vu, state.lastRayOrigin, state.lastRayDir)
        : -1.0f;
    float tControls = state.hasRay
        ? rayHitsQuad(scene.controlsModelNoScale, scene.controlsNormal, scene.controlsCenter,
                      kControlsPanelScaleX, kControlsPanelScaleY, uc, vc, state.lastRayOrigin, state.lastRayDir)
        : -1.0f;
    float tModal = (state.hasRay && state.modalAlpha > 0.01f)
        ? rayHitsQuad(scene.modalModelNoScale, scene.modalNormal, scene.modalCenter,
                      kModalPanelScaleX, kModalPanelScaleY, um, vm, state.lastRayOrigin, state.lastRayDir)
        : -1.0f;

    int currentHitPanel = 0; // 0=none, 1=ui, 2=controls, 3=modal
    float hitU = 0, hitV = 0;
    state.lastHitDist = -1.0f;

    // Foco exclusivo no modal se estiver ativo e visível
    if (state.modalActive && state.modalAlpha > 0.5f) {
        if (tModal > 0.0f) {
            currentHitPanel = 3; hitU = um; hitV = vm;
            state.lastHitDist = tModal;
        } else {
            currentHitPanel = 0;
            state.lastHitDist = -1.0f;
        }
    } else {
        if (tControls > 0.0f) {
            currentHitPanel = 2; hitU = uc; hitV = vc;
            state.lastHitDist = tControls;
        } else if (tUi > 0.0f) {
            currentHitPanel = 1; hitU = uu; hitV = vu;
            state.lastHitDist = tUi;
        }
    }
    // Delta de tempo entre frames — XrTime e nanosegundos desde uma epoca
    // arbitraria do runtime; convertido pra segundos pra alimentar o
    // auto-hide/thumbsticks/seek abaixo (equivalente a in.DeltaSeconds do
    // caminho GLES, que so existe la porque vem do OVRFW).
    float dt = 0.0f;
    if (state.lastPredictedDisplayTime != 0) {
        int64_t deltaNs = predictedDisplayTime - state.lastPredictedDisplayTime;
        if (deltaNs > 0) dt = static_cast<float>(deltaNs) / 1e9f;
    }
    state.lastPredictedDisplayTime = predictedDisplayTime;

    // Auto-hide com fade (Estagio 6, paridade com GLES kUiAutoHideSeconds/
    // kUiFadeDuration): aponta pro Home -> mantem visivel; aponta pra tela
    // ou pros controles -> mantem os controles visiveis; sem atividade por
    // kUiAutoHideSeconds -> fade out. Suprime o auto-hide do Home enquanto o
    // teclado nativo estiver ativo — mesmo motivo do GLES: com o teclado
    // aberto o raio aponta pro overlay do sistema, nao mais pro quad.
    float su = 0, sv = 0;
    bool hitScreen = state.hasRay &&
        rayHitsQuad(scene.screenModelNoScale, scene.screenNormal, scene.screenCenter,
                    state.screenScaleX, state.screenScaleY, su, sv,
                    state.lastRayOrigin, state.lastRayDir) > 0.0f;
    bool keyboardActive = get_keyboard_active() != 0;
    if (::g_requestUiPanelVisible.exchange(false)) {
        state.uiIdleTime = 0.0f;
    }

    if (currentHitPanel == 1 || keyboardActive) {
        state.uiIdleTime = 0.0f;
    } else {
        state.uiIdleTime += dt;
    }
    if (currentHitPanel == 2 || hitScreen) {
        state.controlsIdleTime = 0.0f;
    } else {
        state.controlsIdleTime += dt;
    }
    float uiTargetAlpha = (state.uiIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
    float controlsTargetAlpha = (state.controlsIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
    float modalTargetAlpha = state.modalActive ? 1.0f : 0.0f;
    float fadeStep = (kUiFadeDuration > 0.0f) ? dt / kUiFadeDuration : 1.0f;
    state.uiAlpha = MoveTowards(state.uiAlpha, uiTargetAlpha, fadeStep);
    state.controlsAlpha = MoveTowards(state.controlsAlpha, controlsTargetAlpha, fadeStep);
    state.modalAlpha = MoveTowards(state.modalAlpha, modalTargetAlpha, fadeStep);

    // Overlay de feedback (paridade com o GLES): mesmo fade dos paineis acima,
    // so que o alvo cai sozinho por tempo. Qualquer mudanca na sequencia do
    // bridge conta como evento novo — nao ha relogio compartilhado com o Rust.
    {
        uint64_t feedbackEvent = get_playback_feedback_event();
        uint32_t feedbackSeq = static_cast<uint32_t>(feedbackEvent >> 32);
        if (feedbackSeq != state.feedbackSeq) {
            state.feedbackSeq = feedbackSeq;
            state.feedbackKind = static_cast<vrplayer::FeedbackKind>(feedbackEvent & 0xFFFFFFFFu);
            state.feedbackHoldTime = 0.0f;
        } else {
            state.feedbackHoldTime += dt;
        }
        // Pause fica parado na tela (nao desaparece sozinho) — so some quando um
        // novo evento troca state.feedbackKind (ex: play). Play/seek continuam
        // com hold+fade normal, sao acoes pontuais, nao um estado persistente.
        float feedbackTargetAlpha = 0.0f;
        if (state.feedbackKind == vrplayer::FeedbackKind::Pause) {
            feedbackTargetAlpha = 1.0f;
        } else if (state.feedbackKind != vrplayer::FeedbackKind::None &&
                   state.feedbackHoldTime < vrplayer::kFeedbackHoldSeconds) {
            feedbackTargetAlpha = 1.0f;
        }
        state.feedbackAlpha = MoveTowards(state.feedbackAlpha, feedbackTargetAlpha, fadeStep);
    }

    // So despacha toque/hover pra um painel de fato visivel (evita "clique
    // invisivel" num painel escondido pelo auto-hide); a deteccao geometrica
    // acima continua sempre ativa pra poder trazer o painel de volta.
    bool uiVisible = state.uiAlpha > 0.5f;
    bool controlsVisible = state.controlsAlpha > 0.5f;
    bool modalVisible = state.modalAlpha > 0.5f;
    int dispatchHitPanel = 0;
    if (currentHitPanel == 3 && modalVisible) {
        dispatchHitPanel = 3;
    } else if (currentHitPanel == 1 && uiVisible) {
        dispatchHitPanel = 1;
    } else if (currentHitPanel == 2 && controlsVisible) {
        dispatchHitPanel = 2;
    }

    int action = -1;
    if (currTrigger && !prevTrigger && dispatchHitPanel != 0) {
        action = 0; // DOWN
        state.isTouchDown = true;
        state.activePanel = dispatchHitPanel;
    } else if (!currTrigger && prevTrigger && state.isTouchDown) {
        action = 1; // UP
        state.isTouchDown = false;
    } else if (currTrigger && state.isTouchDown) {
        action = 2; // MOVE
    } else if (dispatchHitPanel != 0 && !state.isTouchDown) {
        action = 7; // HOVER_MOVE
        state.activePanel = dispatchHitPanel;
    }

    if (action >= 0) {
        JNIEnv* env = nullptr;
        state.app->activity->vm->AttachCurrentThread(&env, nullptr);
        if (env) {
            jclass vrActivityClass = env->GetObjectClass(state.app->activity->clazz);
            const char* methodName = (dispatchHitPanel == 3) ? "dispatchModalVRTouch"
                                   : ((dispatchHitPanel == 2) ? "dispatchControlsVRTouch" : "dispatchVRTouch");
            jmethodID touchMethod = env->GetStaticMethodID(vrActivityClass, methodName, "(Lcom/tucavr/VRActivity;FFI)V");
            if (touchMethod) {
                env->CallStaticVoidMethod(vrActivityClass, touchMethod, state.app->activity->clazz, hitU, hitV, action);
            }
            env->DeleteLocalRef(vrActivityClass);
        }
    }

    // Cursor/reticulo no ponto de acerto — desenhado por DrawUiQuads. GLES
    // usa 2 segmentos que compartilham o StartPos pra parecer um disco
    // solido (o BeamRenderer dele desvanece a opacidade ao longo do
    // segmento); o pipeline de beam deste caminho usa cor solida
    // (quad.frag), entao um unico segmento curto ja basta.
    state.cursorDotVisible = dispatchHitPanel != 0;
    if (state.cursorDotVisible) {
        state.cursorDotPos = {
            state.lastRayOrigin.x + state.lastRayDir.x * state.lastHitDist,
            state.lastRayOrigin.y + state.lastRayDir.y * state.lastHitDist,
            state.lastRayOrigin.z + state.lastRayDir.z * state.lastHitDist,
        };
    }

    // Haptics (paridade com GLES: pulso leve no hover-enter, mais forte no
    // click) — sempre na mao direita, igual ao FireHaptic(RightHandPath,...)
    // do GLES (nao acompanha useLeft).
    if (dispatchHitPanel != 0 && dispatchHitPanel != state.lastHoverPanel) {
        FireHaptic(state, rightPath, 0.25f, XR_MIN_HAPTIC_DURATION);
    }
    state.lastHoverPanel = dispatchHitPanel;
    if (action == 0) {
        FireHaptic(state, rightPath, 0.6f, 20000000 /* 20ms */);
    }

    // --- A/X/B/Y/Menu (Estagio 6, paridade com GLES T4.4) ---
    XrActionStateGetInfo aInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    aInfo.action = state.aButtonAction;
    XrActionStateBoolean aState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &aInfo, &aState);

    XrActionStateGetInfo xInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    xInfo.action = state.xButtonAction;
    XrActionStateBoolean xState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &xInfo, &xState);

    XrActionStateGetInfo bInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    bInfo.action = state.bButtonAction;
    XrActionStateBoolean bState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &bInfo, &bState);

    XrActionStateGetInfo yInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    yInfo.action = state.yButtonAction;
    XrActionStateBoolean yState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &yInfo, &yState);

    XrActionStateGetInfo menuInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    menuInfo.action = state.menuAction;
    XrActionStateBoolean menuState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &menuInfo, &menuState);

    bool currA = aState.currentState == XR_TRUE;
    bool currX = xState.currentState == XR_TRUE;
    bool currB = bState.currentState == XR_TRUE;
    bool currY = yState.currentState == XR_TRUE;
    bool currMenu = menuState.currentState == XR_TRUE;

    // Se o modal estiver ativo, fechar modal ao clicar fora ou ao pressionar B/Y
    if (state.modalActive) {
        if (((currTrigger && !prevTrigger && dispatchHitPanel == 0) ||
             (currB && !state.prevB) || (currY && !state.prevY)) && !keyboardActive) {
            state.modalActive = false;
            ::g_modalPanelActive.store(false);
            JNIEnv* env = nullptr;
            state.app->activity->vm->AttachCurrentThread(&env, nullptr);
            if (env) {
                jclass vrActivityClass = env->GetObjectClass(state.app->activity->clazz);
                jmethodID dismissMethod = env->GetStaticMethodID(vrActivityClass, "dismissModalFromNative", "(Lcom/tucavr/VRActivity;)V");
                if (dismissMethod) {
                    env->CallStaticVoidMethod(vrActivityClass, dismissMethod, state.app->activity->clazz);
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }
    } else {
        // A (direita) ou X (esquerda) = Play/Pause. Trigger fora de qualquer
        // painel visivel tambem funciona como atalho.
        if (((currA && !state.prevA) || (currX && !state.prevX) ||
            (currTrigger && !prevTrigger && dispatchHitPanel == 0)) && !keyboardActive) {
            toggle_play_pause();
        }

        // B (direita) ou Y (esquerda) = alterna a visibilidade do painel Home
        // instantaneamente (sem esperar o auto-hide).
        if ((currB && !state.prevB) || (currY && !state.prevY)) {
            bool isCurrentlyVisible = state.uiIdleTime < kUiAutoHideSeconds;
            state.uiIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
        }
    }
    state.prevB = currB;
    state.prevY = currY;

    // Menu: long-press = recenter da cena inteira; short-press (no RELEASE,
    // e so se o long-press nao tiver disparado durante o hold) = mesmo
    // toggle de B/Y — mesma logica do GLES.
    if (currMenu) {
        state.menuHoldTime += dt;
        if (!state.recenterFiredThisHold && state.menuHoldTime >= kRecenterHoldSeconds) {
            Mat4 headRot = Mat4FromXrPose(XrPosef{headOrientation, {0.0f, 0.0f, 0.0f}});
            XrVector3f fwd = {-headRot.m[8], -headRot.m[9], -headRot.m[10]};
            // Ver comentario equivalente em RenderFrame (vr_player_app_vulkan.cpp)
            // sobre por que e -fwd.x e nao fwd.x.
            state.sceneYawOffset = atan2f(-fwd.x, -fwd.z);
            state.sceneTranslationOffset = headCenter;
            state.sceneTranslationOffset.y = headCenter.y - 1.5f;
            state.recenterFiredThisHold = true;
            FireHaptic(state, rightPath, 0.5f, 30000000 /* 30ms */);
        }
    } else {
        if (state.prevMenu && !state.recenterFiredThisHold) {
            bool isCurrentlyVisible = state.uiIdleTime < kUiAutoHideSeconds;
            state.uiIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
        }
        state.menuHoldTime = 0.0f;
        state.recenterFiredThisHold = false;
    }
    state.prevMenu = currMenu;

    // Progresso de reproducao -> UI Kotlin, throttled (~10x/seg a 60fps,
    // mesmo throttle do GLES — seek() e uma operacao pesada).
    state.frameCount++;
    if (state.frameCount % 6 == 0) {
        get_video_progress(&state.lastKnownProgressCurrent, &state.lastKnownProgressTotal);
        if (state.lastKnownProgressTotal > 0.0f) {
            JNIEnv* env = nullptr;
            state.app->activity->vm->AttachCurrentThread(&env, nullptr);
            if (env) {
                jclass vrActivityClass = env->GetObjectClass(state.app->activity->clazz);
                jmethodID updateMethod = env->GetStaticMethodID(
                    vrActivityClass, "updateMediaProgress", "(Lcom/tucavr/VRActivity;FF)V");
                if (updateMethod) {
                    env->CallStaticVoidMethod(vrActivityClass, updateMethod, state.app->activity->clazz,
                        state.lastKnownProgressCurrent, state.lastKnownProgressTotal);
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }

        // Feedback de loading/play-pause (T-seek-ux), paridade com o
        // caminho GLES — NAO gated por progressTotal>0: precisa disparar
        // ja no primeiro load, antes de qualquer duracao ser conhecida.
        {
            uint32_t isLoading = get_playback_is_loading();
            uint32_t isPlaying = get_playback_is_playing();
            JNIEnv* env = nullptr;
            state.app->activity->vm->AttachCurrentThread(&env, nullptr);
            if (env) {
                jclass vrActivityClass = env->GetObjectClass(state.app->activity->clazz);
                jmethodID stateMethod = env->GetStaticMethodID(
                    vrActivityClass, "updateMediaState", "(Lcom/tucavr/VRActivity;ZZ)V");
                if (stateMethod) {
                    env->CallStaticVoidMethod(vrActivityClass, stateMethod, state.app->activity->clazz,
                        (jboolean)(isLoading != 0), (jboolean)(isPlaying != 0));
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }

        // HUD de debug (docs/DEBUGGING.md / docs/reports/DEBUG-STATS-MODAL.md)
        if (g_debugStatsEnabled.load(std::memory_order_relaxed)) {
            StereoParams spHud = GetStereoParams(state.screenMode, 0);
            const char* upscaleStr = "OFF";
            if (state.upscalingMode == 1) upscaleStr = "QUAL";
            else if (state.upscalingMode == 2) upscaleStr = "PERF";
            else if (state.upscalingMode == 3) upscaleStr = "AUTO";

            DebugStats stats;
            stats.backend = "VULKAN";
            stats.screenMode = ScreenModeName(state.screenMode);
            stats.stereoLayout = spHud.stereoLayout;
            stats.polar180 = spHud.polar180;
            stats.swapEyes = spHud.swapEyes;
            stats.hasActiveFrame = (state.activeVideoFrame != nullptr) ? 1 : 0;
            stats.msSinceLastVideoFrame = state.msSinceLastVideoFrame;
            stats.videoFps = state.videoFps;
            stats.decodedFps = state.decodedFps;
            stats.outputFps = state.outputFps;
            stats.droppedFps = state.droppedFps;
            stats.videoJitterMs = state.videoJitterMs;
            stats.netMBs = state.netMBs;
            stats.videoQueueDepth = state.videoQueueDepth;
            stats.seekLatencyMs = get_last_seek_latency_ms();
            stats.smoothedFps = state.smoothedFps;
            stats.lastFrameMs = state.lastFrameMs;
            stats.gpuTimeMs = state.lastGpuTimeMs;
            stats.smoothedGpuTimeMs = state.smoothedGpuTimeMs;
            stats.upscalingMode = upscaleStr;
            stats.upscalingSharpness = state.upscalingSharpness;
            stats.mqsrEnabled = (int)(state.supportsMqsr && state.upscalingEnabled);
            stats.stutterCount = state.stutterCount;
            stats.freezeCount = state.freezeCount;
            stats.thermalLevel = state.thermalLevel;
            stats.renderResolutionScale = state.renderResolutionScale;
            stats.displayRefreshRate = 90.0f;
            stats.avDriftMs = get_last_av_drift_ms();
            stats.netLastFetchMs = get_network_last_block_fetch_ms();
            stats.netBlocksFetched = get_network_blocks_fetched();
            stats.netBlocksDiscarded = get_network_blocks_discarded();
            stats.foveationEnabled = (int)get_foveation_enabled();
            stats.spatialAudioMode = (int)get_spatial_audio_mode();
            stats.spatialHeadTracking = (int)get_spatial_audio_head_tracking();
            stats.playbackSpeed = get_playback_speed();
            stats.audioVolume = get_video_volume();
            stats.audioTrackIndex = 0;
            stats.audioTrackCount = (int)get_audio_track_count();
            stats.subtitleTrackIndex = get_subtitle_track();
            stats.subtitleOffsetMs = (int32_t)get_subtitle_offset_ms();

            char hudBuffer[2048];
            SerializeDebugStats(stats, hudBuffer, sizeof(hudBuffer));

            JNIEnv* env = nullptr;
            state.app->activity->vm->AttachCurrentThread(&env, nullptr);
            if (env) {
                jclass vrActivityClass = env->GetObjectClass(state.app->activity->clazz);
                jmethodID hudMethod = env->GetStaticMethodID(
                    vrActivityClass, "updateDebugHud", "(Lcom/tucavr/VRActivity;Ljava/lang/String;)V");
                if (hudMethod) {
                    jstring hudStr = env->NewStringUTF(hudBuffer);
                    env->CallStaticVoidMethod(vrActivityClass, hudMethod, state.app->activity->clazz, hudStr);
                    env->DeleteLocalRef(hudStr);
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }
    }

    // --- Thumbsticks (Estagio 6, paridade com GLES T3.6/T4.4) ---
    XrActionStateGetInfo rightStickInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    rightStickInfo.action = state.thumbstickAction;
    rightStickInfo.subactionPath = rightPath;
    XrActionStateVector2f rightStick{XR_TYPE_ACTION_STATE_VECTOR2F};
    xrGetActionStateVector2f(state.session, &rightStickInfo, &rightStick);

    XrActionStateGetInfo leftStickInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    leftStickInfo.action = state.thumbstickAction;
    leftStickInfo.subactionPath = leftPath;
    XrActionStateVector2f leftStick{XR_TYPE_ACTION_STATE_VECTOR2F};
    xrGetActionStateVector2f(state.session, &leftStickInfo, &leftStick);

    XrActionStateGetInfo squeezeInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    squeezeInfo.action = state.squeezeAction;
    XrActionStateFloat squeezeState{XR_TYPE_ACTION_STATE_FLOAT};
    xrGetActionStateFloat(state.session, &squeezeInfo, &squeezeState);

    // Thumbstick direito: sem grip move a tela (Y=frente/tras, X=cima/baixo);
    // com grip redimensiona mantendo o aspect ratio 16:9.
    {
        const float kDeadzone = 0.15f;
        float sx = (fabsf(rightStick.currentState.x) < kDeadzone) ? 0.0f : rightStick.currentState.x;
        float sy = (fabsf(rightStick.currentState.y) < kDeadzone) ? 0.0f : rightStick.currentState.y;
        bool gripHeld = squeezeState.currentState > 0.5f;

        if (gripHeld && sy != 0.0f) {
            const float kResizeSpeedMetersPerSec = 1.0f;
            float newWidth = state.screenScaleX + sy * kResizeSpeedMetersPerSec * dt;
            newWidth = std::max(0.5f, std::min(newWidth, 6.0f));
            state.screenScaleX = newWidth;
            state.screenScaleY = newWidth * (9.0f / 16.0f);
        } else if (!gripHeld && (sx != 0.0f || sy != 0.0f)) {
            const float kMoveSpeedMetersPerSec = 1.5f;
            state.screenPosition.z -= sy * kMoveSpeedMetersPerSec * dt;
            state.screenPosition.y += sx * kMoveSpeedMetersPerSec * dt;
            // Limites de conforto: nunca deixar a tela grudada no rosto nem
            // sumir no chao/teto.
            state.screenPosition.z = std::min(-0.75f, std::max(state.screenPosition.z, -8.0f));
            state.screenPosition.y = std::max(0.2f, std::min(state.screenPosition.y, 3.5f));
        }
    }

    // Thumbstick esquerdo: X = seek (com cooldown, seek() e pesado), Y = volume.
    {
        const float kDeadzone = 0.2f;
        float lx = (fabsf(leftStick.currentState.x) < kDeadzone) ? 0.0f : leftStick.currentState.x;
        float ly = (fabsf(leftStick.currentState.y) < kDeadzone) ? 0.0f : leftStick.currentState.y;

        if (ly != 0.0f) {
            float vol = get_video_volume();
            vol = std::max(0.0f, std::min(1.0f, vol + ly * 0.5f * dt));
            set_video_volume(vol);
        }

        state.seekRepeatCooldown = std::max(0.0f, state.seekRepeatCooldown - dt);
        if (lx != 0.0f && state.seekRepeatCooldown <= 0.0f && state.lastKnownProgressTotal > 0.0f) {
            const float kSeekJumpSeconds = 10.0f;
            float target = state.lastKnownProgressCurrent + (lx > 0.0f ? kSeekJumpSeconds : -kSeekJumpSeconds);
            target = std::max(0.0f, std::min(target, state.lastKnownProgressTotal));
            seek_video_playback(target);
            state.seekRepeatCooldown = 0.5f;
        }
    }
}
