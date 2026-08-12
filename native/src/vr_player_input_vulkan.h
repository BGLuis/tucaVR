#pragma once
#include <openxr/openxr.h>
#include <jni.h>
#include <android_native_app_glue.h>
#include "vk_math.h"
#include <math.h>

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

    XrPath selectPath[2];
    xrStringToPath(state.instance, "/user/hand/left/input/aim/pose", &selectPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/input/aim/pose", &selectPath[1]);
    XrPath triggerPath[2];
    xrStringToPath(state.instance, "/user/hand/left/input/trigger/value", &triggerPath[0]);
    xrStringToPath(state.instance, "/user/hand/right/input/trigger/value", &triggerPath[1]);

    XrActionSuggestedBinding bindings[4];
    bindings[0] = {state.aimAction, selectPath[0]};
    bindings[1] = {state.aimAction, selectPath[1]};
    bindings[2] = {state.triggerAction, triggerPath[0]};
    bindings[3] = {state.triggerAction, triggerPath[1]};

    XrPath profilePath;
    xrStringToPath(state.instance, "/interaction_profiles/oculus/touch_controller", &profilePath);
    XrInteractionProfileSuggestedBinding suggestedBindings{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggestedBindings.interactionProfile = profilePath;
    suggestedBindings.suggestedBindings = bindings;
    suggestedBindings.countSuggestedBindings = 4;
    OXR(xrSuggestInteractionProfileBindings(state.instance, &suggestedBindings));
}

inline void AttachInputsToSession(AppState& state) {
    XrSessionActionSetsAttachInfo attachInfo{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attachInfo.countActionSets = 1;
    attachInfo.actionSets = &state.actionSet;
    OXR(xrAttachSessionActionSets(state.session, &attachInfo));

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

inline float rayHitsQuad(const Mat4& transform, const XrVector3f& normal, const XrVector3f& center, 
                         float& outU, float& outV, const XrVector3f& rayOrigin, const XrVector3f& rayDir) {
    float dotDir = normal.x*rayDir.x + normal.y*rayDir.y + normal.z*rayDir.z;
    if (fabs(dotDir) <= 0.001f) return -1.0f;
    XrVector3f toCenter = {center.x - rayOrigin.x, center.y - rayOrigin.y, center.z - rayOrigin.z};
    float t = (normal.x*toCenter.x + normal.y*toCenter.y + normal.z*toCenter.z) / dotDir;
    if (t <= 0.0f) return -1.0f;
    XrVector3f hitPoint = {rayOrigin.x + rayDir.x * t, rayOrigin.y + rayDir.y * t, rayOrigin.z + rayDir.z * t};
    
    // transform hitPoint to local space
    Mat4 inv = Mat4RigidInverse(transform);
    float localX = inv.m[0]*hitPoint.x + inv.m[4]*hitPoint.y + inv.m[8]*hitPoint.z + inv.m[12];
    float localY = inv.m[1]*hitPoint.x + inv.m[5]*hitPoint.y + inv.m[9]*hitPoint.z + inv.m[13];
    float localZ = inv.m[2]*hitPoint.x + inv.m[6]*hitPoint.y + inv.m[10]*hitPoint.z + inv.m[14];
    
    // Scale localX/Y to check boundaries (-0.5 to 0.5 because the quad is 1x1)
    if (localX < -0.5f || localX > 0.5f || localY < -0.5f || localY > 0.5f) return -1.0f;
    
    outU = localX + 0.5f;
    outV = 0.5f - localY;
    return t;
}

inline void UpdateInteraction(AppState& state, XrTime predictedDisplayTime, XrVector3f headPos) {
    XrActiveActionSet activeActionSet{};
    activeActionSet.actionSet = state.actionSet;
    activeActionSet.subactionPath = XR_NULL_PATH;

    XrActionsSyncInfo syncInfo{XR_TYPE_ACTIONS_SYNC_INFO};
    syncInfo.countActiveActionSets = 1;
    syncInfo.activeActionSets = &activeActionSet;
    xrSyncActions(state.session, &syncInfo);

    XrActionStateGetInfo getInfo{XR_TYPE_ACTION_STATE_GET_INFO};
    getInfo.action = state.triggerAction;
    XrActionStateBoolean triggerState{XR_TYPE_ACTION_STATE_BOOLEAN};
    xrGetActionStateBoolean(state.session, &getInfo, &triggerState);
    bool currTrigger = triggerState.currentState == XR_TRUE;

    XrSpaceLocation spaceLocation{XR_TYPE_SPACE_LOCATION};
    // Prioritize right hand, fallback to left
    xrLocateSpace(state.aimSpaces[1], state.localSpace, predictedDisplayTime, &spaceLocation);
    if ((spaceLocation.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) == 0) {
        xrLocateSpace(state.aimSpaces[0], state.localSpace, predictedDisplayTime, &spaceLocation);
    }
    
    if (spaceLocation.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) {
        state.lastRayOrigin = spaceLocation.pose.position;
        Mat4 rot = Mat4FromXrPose(spaceLocation.pose);
        state.lastRayDir = {-rot.m[8], -rot.m[9], -rot.m[10]}; // -Z axis
    }

    bool prevTrigger = state.isTriggerPressed;
    state.isTriggerPressed = currTrigger;

    // Calcular matrizes de UI (tamanho visual x1.5 para melhor leitura)
    float uiPosX = -1.2f, uiPosY = 1.0f, uiPosZ = -1.0f;
    float uiScale = 1.5f;
    float uiYaw = atan2f((headPos.x - uiPosX), (headPos.z - uiPosZ));
    Mat4 uiModel = Mat4Multiply(Mat4Translation(uiPosX, uiPosY, uiPosZ), Mat4RotationY(uiYaw));
    
    Mat4 controlsModel = Mat4Multiply(
        Mat4Multiply(Mat4Translation(0.0f, 0.4f, -1.9f), Mat4RotationX(-0.3f)),
        Mat4Scale(0.8f, 0.3f, 1.0f));

    float uu=0, vu=0, uc=0, vc=0;
    float tUi = -1.0f;
    // Ajustar UV para o scale (hit test acha que e 1x1, mas a gente vai desenhar com uiScale)
    // Se o rayHitsQuad bateu, uu e vu estao entre 0 e 1 apenas se bateu num 1x1.
    // Como queremos hit num quad maior, remapeamos a coordenada de teste.
    // Ou melhor: mudamos a logica de bounds checking. 
    // Em vez de mudar rayHitsQuad, vamos apenas escalar o toCenter no local space?
    // Mais facil: recriar o rayHitsQuad inline com scale.
    float dotDirUI = uiModel.m[8]*state.lastRayDir.x + uiModel.m[9]*state.lastRayDir.y + uiModel.m[10]*state.lastRayDir.z;
    if (fabs(dotDirUI) > 0.001f) {
        XrVector3f toCenter = {uiPosX - state.lastRayOrigin.x, uiPosY - state.lastRayOrigin.y, uiPosZ - state.lastRayOrigin.z};
        float t = (uiModel.m[8]*toCenter.x + uiModel.m[9]*toCenter.y + uiModel.m[10]*toCenter.z) / dotDirUI;
        if (t > 0.0f) {
            XrVector3f hitPoint = {state.lastRayOrigin.x + state.lastRayDir.x*t, state.lastRayOrigin.y + state.lastRayDir.y*t, state.lastRayOrigin.z + state.lastRayDir.z*t};
            Mat4 inv = Mat4RigidInverse(uiModel);
            float localX = inv.m[0]*hitPoint.x + inv.m[4]*hitPoint.y + inv.m[8]*hitPoint.z + inv.m[12];
            float localY = inv.m[1]*hitPoint.x + inv.m[5]*hitPoint.y + inv.m[9]*hitPoint.z + inv.m[13];
            localX /= uiScale; localY /= uiScale; // aplica a escala visual
            if (localX >= -0.5f && localX <= 0.5f && localY >= -0.5f && localY <= 0.5f) {
                tUi = t; uu = localX + 0.5f; vu = 0.5f - localY;
            } else tUi = -1.0f;
        } else tUi = -1.0f;
    } else tUi = -1.0f;

    float tControls = rayHitsQuad(controlsModel, {controlsModel.m[8], controlsModel.m[9], controlsModel.m[10]}, {0, 0.4f, -1.9f}, uc, vc, state.lastRayOrigin, state.lastRayDir);
    
    int currentHitPanel = 0; // 0=none, 1=ui, 2=controls
    float hitU = 0, hitV = 0;
    state.lastHitDist = -1.0f;

    if (tControls > 0.0f) {
        currentHitPanel = 2; hitU = uc; hitV = vc;
        state.lastHitDist = tControls;
    } else if (tUi > 0.0f) {
        currentHitPanel = 1; hitU = uu; hitV = vu;
        state.lastHitDist = tUi;
    }
    int dispatchHitPanel = currentHitPanel; // ignorando auto-hide pra facilitar
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
            const char* methodName = (dispatchHitPanel == 2) ? "dispatchControlsVRTouch" : "dispatchVRTouch";
            jmethodID touchMethod = env->GetStaticMethodID(vrActivityClass, methodName, "(Lcom/vrplayer/VRActivity;FFI)V");
            if (touchMethod) {
                env->CallStaticVoidMethod(vrActivityClass, touchMethod, state.app->activity->clazz, hitU, hitV, action);
            }
            env->DeleteLocalRef(vrActivityClass);
        }
    }
}
