#include "vk_math.h"
#include <cassert>
#include <cmath>
#include <iostream>

static constexpr float kEpsilon = 1e-4f;

static bool FloatNear(float a, float b, float eps = kEpsilon) {
    return std::fabs(a - b) <= eps;
}

static void TestMat4Identity() {
    Mat4 m = Mat4Identity();
    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float expected = (col == row) ? 1.0f : 0.0f;
            assert(FloatNear(m.m[col * 4 + row], expected));
        }
    }
    std::cout << "[PASS] TestMat4Identity\n";
}

static void TestMat4Multiply() {
    Mat4 t = Mat4Translation(2.0f, 3.0f, 4.0f);
    Mat4 s = Mat4Scale(0.5f, 2.0f, 1.5f);
    Mat4 combined = Mat4Multiply(t, s);

    // Combined transform applies scale first, then translation in column-major
    assert(FloatNear(combined.m[0], 0.5f));
    assert(FloatNear(combined.m[5], 2.0f));
    assert(FloatNear(combined.m[10], 1.5f));
    assert(FloatNear(combined.m[12], 2.0f));
    assert(FloatNear(combined.m[13], 3.0f));
    assert(FloatNear(combined.m[14], 4.0f));
    assert(FloatNear(combined.m[15], 1.0f));

    std::cout << "[PASS] TestMat4Multiply\n";
}

static void TestMat4RigidInverse() {
    // Matriz com rotacao de 45 graus em Y e translacao
    Mat4 rot = Mat4RotationY(0.785398f);
    Mat4 trans = Mat4Translation(10.0f, -5.0f, 2.5f);
    Mat4 transform = Mat4Multiply(trans, rot);

    Mat4 inv = Mat4RigidInverse(transform);
    Mat4 identityCandidate = Mat4Multiply(transform, inv);

    for (int col = 0; col < 4; col++) {
        for (int row = 0; row < 4; row++) {
            float expected = (col == row) ? 1.0f : 0.0f;
            assert(FloatNear(identityCandidate.m[col * 4 + row], expected, 1e-3f));
        }
    }
    std::cout << "[PASS] TestMat4RigidInverse\n";
}

static void TestQuatFromYaw() {
    // Yaw 0: quaternion identidade {0, 0, 0, 1}
    XrQuaternionf q0 = QuatFromYaw(0.0f);
    assert(FloatNear(q0.x, 0.0f));
    assert(FloatNear(q0.y, 0.0f));
    assert(FloatNear(q0.z, 0.0f));
    assert(FloatNear(q0.w, 1.0f));

    // Yaw 90 graus (PI / 2): sin(45) ~ 0.7071, cos(45) ~ 0.7071
    constexpr float kPi = 3.1415926535f;
    XrQuaternionf q90 = QuatFromYaw(kPi * 0.5f);
    assert(FloatNear(q90.x, 0.0f));
    assert(FloatNear(q90.y, 0.7071067f));
    assert(FloatNear(q90.z, 0.0f));
    assert(FloatNear(q90.w, 0.7071067f));

    // Invariante de norma unitaria para angulos arbitrarios
    for (float deg = -360.0f; deg <= 360.0f; deg += 15.0f) {
        float rad = deg * (kPi / 180.0f);
        XrQuaternionf q = QuatFromYaw(rad);
        float normSq = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w;
        assert(FloatNear(normSq, 1.0f, 1e-5f));
    }
    std::cout << "[PASS] TestQuatFromYaw\n";
}

static void TestQuatFromMat4() {
    // 1. Identidade
    Mat4 id = Mat4Identity();
    XrQuaternionf qId = QuatFromMat4(id);
    assert(FloatNear(qId.w, 1.0f));
    assert(FloatNear(qId.x, 0.0f));
    assert(FloatNear(qId.y, 0.0f));
    assert(FloatNear(qId.z, 0.0f));

    // 2. Rotacao de 90 graus em X
    constexpr float kPi = 3.1415926535f;
    Mat4 rotX = Mat4RotationX(kPi * 0.5f);
    XrQuaternionf qX = QuatFromMat4(rotX);
    assert(FloatNear(qX.x, 0.7071067f));
    assert(FloatNear(qX.w, 0.7071067f));
    assert(FloatNear(qX.y, 0.0f));
    assert(FloatNear(qX.z, 0.0f));

    // 3. Rotacao de 180 graus em Y (testa ramo de trace <= 0 com m[5] dominante)
    Mat4 rotY180 = Mat4RotationY(kPi);
    XrQuaternionf qY180 = QuatFromMat4(rotY180);
    float normY = qY180.x * qY180.x + qY180.y * qY180.y + qY180.z * qY180.z + qY180.w * qY180.w;
    assert(FloatNear(normY, 1.0f, 1e-4f));
    assert(FloatNear(std::fabs(qY180.y), 1.0f, 1e-3f));

    std::cout << "[PASS] TestQuatFromMat4\n";
}

static void TestVec3Operations() {
    XrVector3f a = {1.0f, 2.0f, 3.0f};
    XrVector3f b = {4.0f, -1.0f, 2.0f};

    XrVector3f sum = Vec3Add(a, b);
    assert(FloatNear(sum.x, 5.0f));
    assert(FloatNear(sum.y, 1.0f));
    assert(FloatNear(sum.z, 5.0f));

    XrVector3f sub = Vec3Sub(a, b);
    assert(FloatNear(sub.x, -3.0f));
    assert(FloatNear(sub.y, 3.0f));
    assert(FloatNear(sub.z, 1.0f));

    XrVector3f scaled = Vec3Scale(a, 2.0f);
    assert(FloatNear(scaled.x, 2.0f));
    assert(FloatNear(scaled.y, 4.0f));
    assert(FloatNear(scaled.z, 6.0f));

    constexpr float kPi = 3.1415926535f;
    XrVector3f forward = {0.0f, 0.0f, 1.0f};
    XrVector3f rotated = Vec3RotateY(forward, kPi * 0.5f);
    assert(FloatNear(rotated.x, 1.0f));
    assert(FloatNear(rotated.y, 0.0f));
    assert(FloatNear(rotated.z, 0.0f));

    std::cout << "[PASS] TestVec3Operations\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de vk_math.h ---\n";
    TestMat4Identity();
    TestMat4Multiply();
    TestMat4RigidInverse();
    TestQuatFromYaw();
    TestQuatFromMat4();
    TestVec3Operations();
    std::cout << "--- Todos os testes de vk_math.h passaram com sucesso! ---\n";
    return 0;
}
