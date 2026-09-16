#include "hand_tracking.h"
#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static void TestJointDistance() {
    // 1. Distância entre o mesmo ponto deve ser 0
    XrVector3f p1 = {1.0f, 2.0f, 3.0f};
    assert(fabs(ComputeJointDistance(p1, p1)) < 1e-6f);

    // 2. Distância no eixo X de 1.5cm (0.015m)
    XrVector3f p2 = {1.015f, 2.0f, 3.0f};
    assert(fabs(ComputeJointDistance(p1, p2) - 0.015f) < 1e-6f);

    // 3. Teorema de Pitágoras 3D: dx=0.03, dy=0.04 -> hipotenusa 2D = 0.05; dz=0.12 -> hipotenusa 3D = 0.13
    XrVector3f a = {0.0f, 0.0f, 0.0f};
    XrVector3f b = {0.03f, 0.04f, 0.12f};
    float dist = ComputeJointDistance(a, b);
    assert(fabs(dist - 0.13f) < 1e-5f);

    std::cout << "[PASS] TestJointDistance" << std::endl;
}

static void TestPinchHysteresis() {
    bool isPinching = false;

    // Estado inicial: dedos afastados a 4cm (0.04m)
    isPinching = UpdatePinchHysteresis(isPinching, 0.04f);
    assert(!isPinching);

    // Aproximando para 2.0cm (dentro da zona morta de histerese 1.5cm - 2.5cm)
    // Como começou solto, deve permanecer solto
    isPinching = UpdatePinchHysteresis(isPinching, 0.020f);
    assert(!isPinching);

    // Aproximando para 1.4cm (< 1.5cm kPinchStartThresholdMeters) -> inicia pinch
    isPinching = UpdatePinchHysteresis(isPinching, 0.014f);
    assert(isPinching);

    // Dedos afastam levemente para 2.0cm (zona morta)
    // Histerese garante que NÃO ocorra flickering: pinch continua ativo
    isPinching = UpdatePinchHysteresis(isPinching, 0.020f);
    assert(isPinching);

    // Dedos afastam para 2.4cm (ainda <= 2.5cm kPinchEndThresholdMeters)
    isPinching = UpdatePinchHysteresis(isPinching, 0.024f);
    assert(isPinching);

    // Dedos afastam para 2.6cm (> 2.5cm) -> encerra pinch
    isPinching = UpdatePinchHysteresis(isPinching, 0.026f);
    assert(!isPinching);

    // Aproxima novamente para 2.0cm: continua inativo até cruzar < 1.5cm
    isPinching = UpdatePinchHysteresis(isPinching, 0.020f);
    assert(!isPinching);

    std::cout << "[PASS] TestPinchHysteresis" << std::endl;
}

static void TestPointingRayDirection() {
    // 1. Apontando exatamente no eixo -Z (padrão frontal)
    XrVector3f distal = {0.0f, 1.2f, -0.20f};
    XrVector3f tip    = {0.0f, 1.2f, -0.25f}; // ponta mais à frente no -Z
    XrVector3f dir = ComputePointingRayDirection(tip, distal);

    assert(fabs(dir.x) < 1e-5f);
    assert(fabs(dir.y) < 1e-5f);
    assert(fabs(dir.z - (-1.0f)) < 1e-5f);

    // Comprimento do vetor de direção deve ser unitário (norma = 1)
    float len = sqrtf(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
    assert(fabs(len - 1.0f) < 1e-5f);

    // 2. Apontando em 45 graus (+X e -Z)
    XrVector3f tipDiag = {0.03f, 1.2f, -0.23f};
    XrVector3f dirDiag = ComputePointingRayDirection(tipDiag, distal);
    float lenDiag = sqrtf(dirDiag.x * dirDiag.x + dirDiag.y * dirDiag.y + dirDiag.z * dirDiag.z);
    assert(fabs(lenDiag - 1.0f) < 1e-5f);
    assert(dirDiag.x > 0.6f && dirDiag.x < 0.8f);
    assert(dirDiag.z < -0.6f && dirDiag.z > -0.8f);

    // 3. Juntas colapsadas (distância zero) não devem causar divisão por zero (NaN)
    XrVector3f fallbackDir = ComputePointingRayDirection(distal, distal);
    assert(!std::isnan(fallbackDir.x) && !std::isnan(fallbackDir.y) && !std::isnan(fallbackDir.z));
    assert(fabs(fallbackDir.z - (-1.0f)) < 1e-5f);

    std::cout << "[PASS] TestPointingRayDirection" << std::endl;
}

static void TestEmaSmoothing() {
    XrVector3f smoothed = {0.0f, 0.0f, -1.0f};
    XrVector3f raw = {1.0f, 0.0f, 0.0f};

    // 1 passo de EMA com alpha = 0.3
    XrVector3f step1 = ApplyEmaSmoothing(smoothed, raw, 0.3f);

    // Vetor deve permanecer unitário
    float len1 = sqrtf(step1.x * step1.x + step1.y * step1.y + step1.z * step1.z);
    assert(fabs(len1 - 1.0f) < 1e-5f);

    // Componente X deve ter se movido em direção a 1.0, e Z em direção a 0.0
    assert(step1.x > 0.2f && step1.x < 0.5f);
    assert(step1.z < -0.7f && step1.z > -1.0f);

    // Após 30 passos repetidos em direção a raw, deve ter convergido para raw
    XrVector3f curr = step1;
    for (int i = 0; i < 30; ++i) {
        curr = ApplyEmaSmoothing(curr, raw, 0.3f);
    }
    assert(fabs(curr.x - 1.0f) < 1e-3f);
    assert(fabs(curr.y) < 1e-3f);
    assert(fabs(curr.z) < 1e-3f);

    std::cout << "[PASS] TestEmaSmoothing" << std::endl;
}

static void TestPalmOrientation() {
    // 1. Pose com rotação identidade:
    // Na convenção OpenXR, +Y local sai das costas da mão, logo -Y sai da palma.
    // Com rotação identidade, o vetor -Y da palma aponta para baixo (Y = -1).
    XrPosef palmPoseDown{};
    palmPoseDown.orientation = {0.0f, 0.0f, 0.0f, 1.0f}; // Identidade

    bool isPalmUp = false;
    bool isPalmDown = false;
    DetectPalmOrientation(palmPoseDown, isPalmUp, isPalmDown);
    assert(!isPalmUp);
    assert(isPalmDown);

    // 2. Pose com a mão virada para cima: rotação de 180 graus no eixo Z
    // q = (0, 0, sin(pi/2), cos(pi/2)) = (0, 0, 1, 0)
    XrPosef palmPoseUp{};
    palmPoseUp.orientation = {0.0f, 0.0f, 1.0f, 0.0f};
    DetectPalmOrientation(palmPoseUp, isPalmUp, isPalmDown);
    assert(isPalmUp);
    assert(!isPalmDown);

    std::cout << "[PASS] TestPalmOrientation" << std::endl;
}

static void TestHandTrackingFilterEndToEnd() {
    HandTrackingFilter filter;
    assert(!filter.IsPinching());
    assert(!filter.HasHistory());

    // Criar array de 26 juntas simuladas
    XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT]{};

    // Frame 1: juntas sem flag de validade de posição -> deve falhar graciosamente
    HandGestureOutput out{};
    bool success = filter.Process(joints, XR_HAND_JOINT_COUNT_EXT, out);
    assert(!success);
    assert(!out.hasRay);
    assert(!out.isPinching);

    // Frame 2: Configurar juntas válidas (dedos afastados)
    for (int i = 0; i < XR_HAND_JOINT_COUNT_EXT; ++i) {
        joints[i].locationFlags = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        joints[i].pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
    }

    // Indicador apontando para -Z
    joints[XR_HAND_JOINT_INDEX_DISTAL_EXT].pose.position = {0.2f, 1.0f, -0.3f};
    joints[XR_HAND_JOINT_INDEX_TIP_EXT].pose.position    = {0.2f, 1.0f, -0.35f};
    // Polegar afastado a 5cm
    joints[XR_HAND_JOINT_THUMB_TIP_EXT].pose.position    = {0.25f, 1.0f, -0.35f};

    success = filter.Process(joints, XR_HAND_JOINT_COUNT_EXT, out);
    assert(success);
    assert(out.hasRay);
    assert(!out.isPinching);
    assert(fabs(out.pinchDistance - 0.05f) < 1e-5f);
    assert(fabs(out.rayDirection.z - (-1.0f)) < 1e-5f);
    assert(filter.HasHistory());

    // Frame 3: Aproxima o polegar para 1.0cm (< 1.5cm) -> ativa pinch
    joints[XR_HAND_JOINT_THUMB_TIP_EXT].pose.position = {0.21f, 1.0f, -0.35f};
    success = filter.Process(joints, XR_HAND_JOINT_COUNT_EXT, out);
    assert(success);
    assert(out.hasRay);
    assert(out.isPinching);
    assert(filter.IsPinching());

    // Frame 4: Perda súbita de rastreamento (ex: mão saiu do campo de visão)
    joints[XR_HAND_JOINT_INDEX_TIP_EXT].locationFlags = 0; // flag inválida
    success = filter.Process(joints, XR_HAND_JOINT_COUNT_EXT, out);
    assert(!success);
    assert(!out.hasRay);
    assert(!out.isPinching);
    assert(!filter.IsPinching());
    assert(!filter.HasHistory());

    std::cout << "[PASS] TestHandTrackingFilterEndToEnd (Graceful Fallback validado)" << std::endl;
}

int main() {
    std::cout << "--- Executando testes unitarios de hand_tracking.h ---" << std::endl;
    TestJointDistance();
    TestPinchHysteresis();
    TestPointingRayDirection();
    TestEmaSmoothing();
    TestPalmOrientation();
    TestHandTrackingFilterEndToEnd();
    std::cout << "--- Todos os testes de hand_tracking.h passaram com sucesso! ---" << std::endl;
    return 0;
}
