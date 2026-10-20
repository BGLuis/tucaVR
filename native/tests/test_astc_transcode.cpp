#include "astc_transcode.h"

#include <cassert>
#include <cstdlib>
#include <iostream>
#include <vector>

static std::vector<uint8_t> GradientImage(uint32_t w, uint32_t h) {
    std::vector<uint8_t> rgba(static_cast<size_t>(w) * h * 4);
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            uint8_t *p = &rgba[(static_cast<size_t>(y) * w + x) * 4];
            p[0] = static_cast<uint8_t>(x * 255 / (w - 1));
            p[1] = static_cast<uint8_t>(y * 255 / (h - 1));
            p[2] = 128;
            p[3] = 255;
        }
    }
    return rgba;
}

static void TestBlockSizeRoundsUpToWholeBlocks() {
    assert(Astc6x6ByteSize(6, 6) == 16);
    assert(Astc6x6ByteSize(7, 6) == 32);
    assert(Astc6x6ByteSize(13, 13) == 9 * 16);
    assert(Astc6x6ByteSize(4096, 4096) == 683u * 683u * 16u);
}

static void TestRoundTripKeepsDimensionsAndApproximatesPixels() {
    const uint32_t w = 30, h = 20;
    const auto rgba = GradientImage(w, h);

    AstcImage encoded;
    assert(TranscodeRgbaToAstc6x6(rgba.data(), w, h, true, &encoded));
    assert(encoded.width == w && encoded.height == h);
    assert(encoded.blocks.size() == Astc6x6ByteSize(w, h));
    assert(encoded.blocks.size() == 5u * 4u * kAstcBlockBytes);

    std::vector<uint8_t> decoded;
    assert(DecodeAstc6x6ToRgba(encoded, &decoded));
    assert(decoded.size() == rgba.size());

    long totalError = 0;
    for (size_t i = 0; i < rgba.size(); ++i) {
        totalError += std::abs(static_cast<int>(rgba[i]) - static_cast<int>(decoded[i]));
    }
    assert(totalError / static_cast<long>(rgba.size()) <= 6);
}

static void TestCacheSerializationRoundTrips() {
    const auto rgba = GradientImage(12, 12);
    AstcImage encoded;
    assert(TranscodeRgbaToAstc6x6(rgba.data(), 12, 12, false, &encoded));

    const auto bytes = SerializeAstcCache(encoded);
    AstcImage parsed;
    assert(ParseAstcCache(bytes.data(), bytes.size(), &parsed));
    assert(parsed.width == 12 && parsed.height == 12 && !parsed.srgb);
    assert(parsed.blocks == encoded.blocks);
}

static void TestCacheRejectsCorruptInput() {
    const auto rgba = GradientImage(12, 12);
    AstcImage encoded;
    assert(TranscodeRgbaToAstc6x6(rgba.data(), 12, 12, true, &encoded));
    auto bytes = SerializeAstcCache(encoded);
    AstcImage parsed;

    auto truncated = bytes;
    truncated.pop_back();
    assert(!ParseAstcCache(truncated.data(), truncated.size(), &parsed));

    auto badMagic = bytes;
    badMagic[0] = 'X';
    assert(!ParseAstcCache(badMagic.data(), badMagic.size(), &parsed));

    auto badBlockDim = bytes;
    badBlockDim[6] = 8;
    assert(!ParseAstcCache(badBlockDim.data(), badBlockDim.size(), &parsed));

    assert(!ParseAstcCache(bytes.data(), 4, &parsed));
}

static void TestRejectsEmptyInput() {
    AstcImage out;
    const uint8_t pixel[4] = {0, 0, 0, 255};
    assert(!TranscodeRgbaToAstc6x6(nullptr, 6, 6, true, &out));
    assert(!TranscodeRgbaToAstc6x6(pixel, 0, 6, true, &out));
}

int main() {
    TestBlockSizeRoundsUpToWholeBlocks();
    TestRoundTripKeepsDimensionsAndApproximatesPixels();
    TestCacheSerializationRoundTrips();
    TestCacheRejectsCorruptInput();
    TestRejectsEmptyInput();
    std::cout << "test_astc_transcode: all tests passed\n";
    return 0;
}
