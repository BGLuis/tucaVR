#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

constexpr uint32_t kAstcBlockDim = 6;
constexpr size_t kAstcBlockBytes = 16;

struct AstcImage {
    uint32_t width = 0;
    uint32_t height = 0;
    bool srgb = false;
    std::vector<uint8_t> blocks;
};

size_t Astc6x6ByteSize(uint32_t width, uint32_t height);

// rgba must hold width * height * 4 bytes. Returns false on invalid input or encoder failure.
bool TranscodeRgbaToAstc6x6(const uint8_t *rgba, uint32_t width, uint32_t height, bool srgb, AstcImage *out);

bool DecodeAstc6x6ToRgba(const AstcImage &image, std::vector<uint8_t> *rgbaOut);

// On-disk cache layout: "TASTC" magic, version, block dim, srgb flag, width, height, then the raw blocks.
std::vector<uint8_t> SerializeAstcCache(const AstcImage &image);
bool ParseAstcCache(const uint8_t *data, size_t size, AstcImage *out);
