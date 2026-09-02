#include "subtitle_layout.h"
#include <cassert>
#include <cmath>
#include <iostream>

using namespace vrplayer;

static bool FloatNear(float a, float b, float eps = 0.001f) {
    return std::fabs(a - b) < eps;
}

static void TestPgsQuadBoundsFlat() {
    PgsSubtitleInfo info{};
    info.screen_width = 1920;
    info.screen_height = 1080;
    // Legenda no centro inferior: x = 460, y = 900, w = 1000, h = 100
    info.x = 460;
    info.y = 900;
    info.width = 1000;
    info.height = 100;

    float screenScaleX = 3.2f;
    float screenScaleY = 1.8f;
    float posX = 0.0f, posY = 0.0f, scaleX = 0.0f, scaleY = 0.0f;

    bool ok = ComputePgsQuadBounds(info, screenScaleX, screenScaleY, false, posX, posY, scaleX, scaleY);
    assert(ok);

    // Centro X: 460 + 500 = 960 (exatamente no centro de 1920) => normX = 0
    assert(FloatNear(posX, 0.0f));
    // Centro Y: 900 + 50 = 950 => normY = 0.5 - (950/1080) = -0.3796
    assert(posY < 0.0f); // Parte inferior da tela
    assert(FloatNear(scaleX, (1000.0f / 1920.0f) * 3.2f));
    assert(FloatNear(scaleY, (100.0f / 1080.0f) * 1.8f));

    std::cout << "[PASS] TestPgsQuadBoundsFlat\n";
}

static void TestPgsQuadBoundsSphere() {
    PgsSubtitleInfo info{};
    info.screen_width = 1920;
    info.screen_height = 1080;
    info.x = 460;
    info.y = 900;
    info.width = 1000;
    info.height = 100;

    float posX = 0.0f, posY = 0.0f, scaleX = 0.0f, scaleY = 0.0f;
    bool ok = ComputePgsQuadBounds(info, 1.0f, 1.0f, true, posX, posY, scaleX, scaleY);
    assert(ok);
    assert(FloatNear(posX, 0.0f));
    assert(posY < 0.0f);
    assert(scaleX > 0.0f && scaleY > 0.0f);

    std::cout << "[PASS] TestPgsQuadBoundsSphere\n";
}

static void TestAssOffsetsAlignment() {
    float screenScaleX = 3.0f;
    float screenScaleY = 2.0f;
    float offX = 0.0f, offY = 0.0f;

    // Alignment 2: Bottom-center
    AssSubtitleInfo info2{};
    info2.alignment = 2;
    info2.has_pos = 0;
    ComputeAssOffsets(info2, screenScaleX, screenScaleY, false, offX, offY);
    assert(FloatNear(offX, 0.0f));
    assert(FloatNear(offY, -screenScaleY * 0.42f));

    // Alignment 8: Top-center (superior)
    AssSubtitleInfo info8{};
    info8.alignment = 8;
    info8.has_pos = 0;
    ComputeAssOffsets(info8, screenScaleX, screenScaleY, false, offX, offY);
    assert(FloatNear(offX, 0.0f));
    assert(FloatNear(offY, screenScaleY * 0.42f));

    // Alignment 5: Center-middle (central)
    AssSubtitleInfo info5{};
    info5.alignment = 5;
    info5.has_pos = 0;
    ComputeAssOffsets(info5, screenScaleX, screenScaleY, false, offX, offY);
    assert(FloatNear(offX, 0.0f));
    assert(FloatNear(offY, 0.0f));

    // Alignment 1: Bottom-left
    AssSubtitleInfo info1{};
    info1.alignment = 1;
    info1.has_pos = 0;
    ComputeAssOffsets(info1, screenScaleX, screenScaleY, false, offX, offY);
    assert(offX < 0.0f);
    assert(FloatNear(offY, -screenScaleY * 0.42f));

    // Alignment 9: Top-right
    AssSubtitleInfo info9{};
    info9.alignment = 9;
    info9.has_pos = 0;
    ComputeAssOffsets(info9, screenScaleX, screenScaleY, false, offX, offY);
    assert(offX > 0.0f);
    assert(FloatNear(offY, screenScaleY * 0.42f));

    std::cout << "[PASS] TestAssOffsetsAlignment\n";
}

static void TestAssOffsetsPos() {
    float screenScaleX = 1920.0f;
    float screenScaleY = 1080.0f;
    float offX = 0.0f, offY = 0.0f;

    AssSubtitleInfo infoPos{};
    infoPos.has_pos = 1;
    infoPos.pos_x = 960.0f;
    infoPos.pos_y = 540.0f;
    infoPos.play_res_x = 1920.0f;
    infoPos.play_res_y = 1080.0f;

    ComputeAssOffsets(infoPos, screenScaleX, screenScaleY, false, offX, offY);
    // Exatamente no centro: normX = 0, normY = 0
    assert(FloatNear(offX, 0.0f));
    assert(FloatNear(offY, 0.0f));

    // Canto superior esquerdo: x=0, y=0 => normX = -0.5, normY = +0.5
    infoPos.pos_x = 0.0f;
    infoPos.pos_y = 0.0f;
    ComputeAssOffsets(infoPos, screenScaleX, screenScaleY, false, offX, offY);
    assert(FloatNear(offX, -0.5f * screenScaleX));
    assert(FloatNear(offY, 0.5f * screenScaleY));

    std::cout << "[PASS] TestAssOffsetsPos\n";
}

static void TestAssLineCursorX() {
    float lineWidth = 100.0f;
    float maxLineWidth = 200.0f;

    // Alinhado à esquerda (\an 1, 4, 7)
    float curLeft = ComputeAssLineCursorX(1, lineWidth, maxLineWidth);
    assert(FloatNear(curLeft, -100.0f));

    // Centralizado (\an 2, 5, 8)
    float curCenter = ComputeAssLineCursorX(2, lineWidth, maxLineWidth);
    assert(FloatNear(curCenter, -50.0f));

    // Alinhado à direita (\an 3, 6, 9)
    float curRight = ComputeAssLineCursorX(3, lineWidth, maxLineWidth);
    assert(FloatNear(curRight, 0.0f)); // 200*0.5 - 100 = 0

    std::cout << "[PASS] TestAssLineCursorX\n";
}

static void TestFindSpanForByteOffset() {
    AssSpanFfi spans[2]{};
    spans[0].char_offset = 0;
    spans[0].char_length = 5;
    spans[0].r = 255; spans[0].g = 0; spans[0].b = 0; spans[0].a = 255;

    spans[1].char_offset = 5;
    spans[1].char_length = 6;
    spans[1].r = 0; spans[1].g = 255; spans[1].b = 0; spans[1].a = 255;

    const AssSpanFfi* s0 = FindSpanForByteOffset(spans, 2, 2);
    assert(s0 != nullptr && s0->r == 255 && s0->g == 0);

    const AssSpanFfi* s1 = FindSpanForByteOffset(spans, 2, 7);
    assert(s1 != nullptr && s1->r == 0 && s1->g == 255);

    const AssSpanFfi* sOut = FindSpanForByteOffset(spans, 2, 20);
    assert(sOut == nullptr);

    std::cout << "[PASS] TestFindSpanForByteOffset\n";
}

int main() {
    std::cout << "--- Executando testes unitarios de subtitle_layout.h ---\n";
    TestPgsQuadBoundsFlat();
    TestPgsQuadBoundsSphere();
    TestAssOffsetsAlignment();
    TestAssOffsetsPos();
    TestAssLineCursorX();
    TestFindSpanForByteOffset();
    std::cout << "--- Todos os testes de subtitle_layout.h passaram com sucesso! ---\n";
    return 0;
}
