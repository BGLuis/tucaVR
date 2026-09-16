#pragma once

#include <openxr/openxr.h>
#include "vk_math.h"
#include <cmath>
#include <algorithm>
#include <cstdint>

namespace vrplayer {

// Limiares de histerese para detecção de pinch (polegar e indicador)
// Conforme T5.3: pinch inicia com distância < 1.5cm (0.015m) e termina com distância > 2.5cm (0.025m)
constexpr float kPinchStartThresholdMeters = 0.015f; // 1.5 cm
constexpr float kPinchEndThresholdMeters   = 0.025f; // 2.5 cm

// Fator alpha da média móvel exponencial (EMA) para suavização do raio de apontamento (T5.4)
constexpr float kRaySmoothingAlpha         = 0.3f;

// Tolerância mínima para posições colapsadas de juntas
constexpr float kMinJointDistanceMeters    = 1e-4f;

// Calcula a distância euclidiana entre duas posições 3D
inline float ComputeJointDistance(const XrVector3f& a, const XrVector3f& b) {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    float dz = a.z - b.z;
    return sqrtf(dx * dx + dy * dy + dz * dz);
}

// Atualiza o estado booleano de pinch aplicando histerese para eliminar flickering
inline bool UpdatePinchHysteresis(bool currentlyPinching, float distance,
                                  float startThreshold = kPinchStartThresholdMeters,
                                  float endThreshold = kPinchEndThresholdMeters) {
    if (!currentlyPinching) {
        if (distance < startThreshold) {
            return true;
        }
    } else {
        if (distance > endThreshold) {
            return false;
        }
    }
    return currentlyPinching;
}

// Calcula a direção normalizada do raio apontando a partir do dedo indicador:
// direção = normalize(indexTip - indexDistal)
inline XrVector3f ComputePointingRayDirection(const XrVector3f& indexTip, const XrVector3f& indexDistal) {
    XrVector3f dir = {
        indexTip.x - indexDistal.x,
        indexTip.y - indexDistal.y,
        indexTip.z - indexDistal.z
    };
    float len = sqrtf(dir.x * dir.x + dir.y * dir.y + dir.z * dir.z);
    if (len > kMinJointDistanceMeters) {
        dir.x /= len;
        dir.y /= len;
        dir.z /= len;
    } else {
        dir = {0.0f, 0.0f, -1.0f}; // Fallback padrão para a frente no espaço local (-Z)
    }
    return dir;
}

// Aplica suavização exponencial (EMA) entre o vetor de direção anterior e o atual, renormalizando para norma 1
inline XrVector3f ApplyEmaSmoothing(const XrVector3f& prevSmoothed, const XrVector3f& currentRaw,
                                    float alpha = kRaySmoothingAlpha) {
    XrVector3f mixed = {
        prevSmoothed.x + alpha * (currentRaw.x - prevSmoothed.x),
        prevSmoothed.y + alpha * (currentRaw.y - prevSmoothed.y),
        prevSmoothed.z + alpha * (currentRaw.z - prevSmoothed.z)
    };
    float len = sqrtf(mixed.x * mixed.x + mixed.y * mixed.y + mixed.z * mixed.z);
    if (len > kMinJointDistanceMeters) {
        mixed.x /= len;
        mixed.y /= len;
        mixed.z /= len;
    } else {
        mixed = {0.0f, 0.0f, -1.0f};
    }
    return mixed;
}

// Detecta orientação da palma (Palm Up / Palm Down) usando o vetor normal derivado da pose
// Na especificação do OpenXR Hand Tracking, o eixo -Y local aponta para fora da palma (palmar direction).
inline void DetectPalmOrientation(const XrPosef& palmPose, bool& outPalmUp, bool& outPalmDown) {
    Mat4 rot = Mat4FromXrPose(palmPose);
    // rot.m[4, 5, 6] é o eixo +Y local transformado para o mundo. O eixo -Y é a normal da palma.
    float palmNormalY = -rot.m[5];

    outPalmUp = (palmNormalY > 0.6f);
    outPalmDown = (palmNormalY < -0.6f);
}

// Estrutura de saída processada para interação e raycasting de uma mão
struct HandGestureOutput {
    bool hasRay = false;
    XrVector3f rayOrigin = {0.0f, 0.0f, 0.0f};
    XrVector3f rayDirection = {0.0f, 0.0f, -1.0f};
    bool isPinching = false;
    float pinchDistance = 0.0f;
    bool isPalmUp = false;
    bool isPalmDown = false;
};

// Filtro e rastreador de estado por mão (preserva histórico de suavização e histerese)
class HandTrackingFilter {
public:
    HandTrackingFilter() = default;

    void Reset() {
        m_isPinching = false;
        m_hasHistory = false;
        m_smoothedOrigin = {0.0f, 0.0f, 0.0f};
        m_smoothedDirection = {0.0f, 0.0f, -1.0f};
    }

    bool IsPinching() const { return m_isPinching; }
    bool HasHistory() const { return m_hasHistory; }
    const XrVector3f& GetSmoothedOrigin() const { return m_smoothedOrigin; }
    const XrVector3f& GetSmoothedDirection() const { return m_smoothedDirection; }

    // Processa o conjunto de 26 juntas de uma mão do OpenXR
    bool Process(const XrHandJointLocationEXT* joints, uint32_t jointCount, HandGestureOutput& out) {
        if (!joints || jointCount < XR_HAND_JOINT_COUNT_EXT) {
            Reset();
            out = HandGestureOutput{};
            return false;
        }

        const auto& indexTip = joints[XR_HAND_JOINT_INDEX_TIP_EXT];
        const auto& indexDistal = joints[XR_HAND_JOINT_INDEX_DISTAL_EXT];
        const auto& thumbTip = joints[XR_HAND_JOINT_THUMB_TIP_EXT];

        constexpr XrSpaceLocationFlags kPosValid = XR_SPACE_LOCATION_POSITION_VALID_BIT;
        constexpr XrSpaceLocationFlags kRotValid = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;

        bool indexTipValid = (indexTip.locationFlags & kPosValid) != 0;
        bool indexDistalValid = (indexDistal.locationFlags & kPosValid) != 0;
        bool thumbTipValid = (thumbTip.locationFlags & kPosValid) != 0;

        // Se o indicador não possuir posição válida, não há raio de apontamento confiável
        if (!indexTipValid || !indexDistalValid) {
            Reset();
            out = HandGestureOutput{};
            return false;
        }

        // T5.3: Detecção de Pinch com histerese
        if (thumbTipValid) {
            out.pinchDistance = ComputeJointDistance(thumbTip.pose.position, indexTip.pose.position);
            m_isPinching = UpdatePinchHysteresis(m_isPinching, out.pinchDistance);
        } else {
            m_isPinching = false;
            out.pinchDistance = 1.0f;
        }
        out.isPinching = m_isPinching;

        // T5.4: Raycasting a partir da ponta do indicador com suavização EMA
        XrVector3f rawOrigin = indexTip.pose.position;
        XrVector3f rawDirection = ComputePointingRayDirection(indexTip.pose.position, indexDistal.pose.position);

        if (!m_hasHistory) {
            m_smoothedOrigin = rawOrigin;
            m_smoothedDirection = rawDirection;
            m_hasHistory = true;
        } else {
            // Suavização suave da origem e EMA na direção
            m_smoothedOrigin = Vec3Add(m_smoothedOrigin, Vec3Scale(Vec3Sub(rawOrigin, m_smoothedOrigin), kRaySmoothingAlpha));
            m_smoothedDirection = ApplyEmaSmoothing(m_smoothedDirection, rawDirection, kRaySmoothingAlpha);
        }

        out.hasRay = true;
        out.rayOrigin = m_smoothedOrigin;
        out.rayDirection = m_smoothedDirection;

        // T5.5: Detecção de palma para cima / palma para baixo
        const auto& palm = joints[XR_HAND_JOINT_PALM_EXT];
        if ((palm.locationFlags & (kPosValid | kRotValid)) == (kPosValid | kRotValid)) {
            DetectPalmOrientation(palm.pose, out.isPalmUp, out.isPalmDown);
        } else {
            out.isPalmUp = false;
            out.isPalmDown = false;
        }

        return true;
    }

private:
    bool m_isPinching = false;
    bool m_hasHistory = false;
    XrVector3f m_smoothedOrigin = {0.0f, 0.0f, 0.0f};
    XrVector3f m_smoothedDirection = {0.0f, 0.0f, -1.0f};
};

} // namespace vrplayer
