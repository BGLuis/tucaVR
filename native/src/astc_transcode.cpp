#include "astc_transcode.h"

#include "../third_party/astcenc/astcenc.h"

#include <algorithm>
#include <cstring>
#include <memory>
#include <thread>

namespace {

constexpr char kMagic[5] = {'T', 'A', 'S', 'T', 'C'};
constexpr uint8_t kCacheVersion = 1;
constexpr size_t kHeaderBytes = 5 + 1 + 1 + 1 + 4 + 4;
constexpr unsigned kMaxEncoderThreads = 4;
const float kEncoderQuality = ASTCENC_PRE_FAST;

struct ContextDeleter {
    void operator()(astcenc_context *ctx) const { astcenc_context_free(ctx); }
};
using ContextPtr = std::unique_ptr<astcenc_context, ContextDeleter>;

bool InitContext(bool srgb, unsigned threads, ContextPtr *ctx) {
    astcenc_config config;
    const astcenc_profile profile = srgb ? ASTCENC_PRF_LDR_SRGB : ASTCENC_PRF_LDR;
    if (astcenc_config_init(profile, kAstcBlockDim, kAstcBlockDim, 1, kEncoderQuality, 0, &config) != ASTCENC_SUCCESS) {
        return false;
    }
    astcenc_context *raw = nullptr;
    if (astcenc_context_alloc(&config, threads, &raw) != ASTCENC_SUCCESS) {
        return false;
    }
    ctx->reset(raw);
    return true;
}

void WriteU32(std::vector<uint8_t> &out, uint32_t v) {
    for (int shift = 0; shift < 32; shift += 8) {
        out.push_back(static_cast<uint8_t>((v >> shift) & 0xFF));
    }
}

uint32_t ReadU32(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) | (static_cast<uint32_t>(p[2]) << 16) |
           (static_cast<uint32_t>(p[3]) << 24);
}

} // namespace

size_t Astc6x6ByteSize(uint32_t width, uint32_t height) {
    const size_t blocksX = (width + kAstcBlockDim - 1) / kAstcBlockDim;
    const size_t blocksY = (height + kAstcBlockDim - 1) / kAstcBlockDim;
    return blocksX * blocksY * kAstcBlockBytes;
}

bool TranscodeRgbaToAstc6x6(const uint8_t *rgba, uint32_t width, uint32_t height, bool srgb, AstcImage *out) {
    if (!rgba || !out || width == 0 || height == 0) {
        return false;
    }
    const unsigned threads = std::clamp(std::thread::hardware_concurrency(), 1u, kMaxEncoderThreads);
    ContextPtr ctx;
    if (!InitContext(srgb, threads, &ctx)) {
        return false;
    }

    void *slice = const_cast<uint8_t *>(rgba);
    astcenc_image image{width, height, 1, ASTCENC_TYPE_U8, &slice};
    const astcenc_swizzle swizzle{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};

    std::vector<uint8_t> blocks(Astc6x6ByteSize(width, height));
    std::vector<astcenc_error> results(threads, ASTCENC_SUCCESS);
    std::vector<std::thread> workers;
    for (unsigned t = 0; t < threads; ++t) {
        workers.emplace_back([&, t] {
            results[t] = astcenc_compress_image(ctx.get(), &image, &swizzle, blocks.data(), blocks.size(), t);
        });
    }
    for (auto &w : workers) {
        w.join();
    }
    if (std::any_of(results.begin(), results.end(), [](astcenc_error e) { return e != ASTCENC_SUCCESS; })) {
        return false;
    }

    out->width = width;
    out->height = height;
    out->srgb = srgb;
    out->blocks = std::move(blocks);
    return true;
}

bool DecodeAstc6x6ToRgba(const AstcImage &image, std::vector<uint8_t> *rgbaOut) {
    if (!rgbaOut || image.width == 0 || image.height == 0 ||
        image.blocks.size() != Astc6x6ByteSize(image.width, image.height)) {
        return false;
    }
    ContextPtr ctx;
    if (!InitContext(image.srgb, 1, &ctx)) {
        return false;
    }
    rgbaOut->assign(static_cast<size_t>(image.width) * image.height * 4, 0);
    void *slice = rgbaOut->data();
    astcenc_image decoded{image.width, image.height, 1, ASTCENC_TYPE_U8, &slice};
    const astcenc_swizzle swizzle{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    return astcenc_decompress_image(ctx.get(), image.blocks.data(), image.blocks.size(), &decoded, &swizzle, 0) ==
           ASTCENC_SUCCESS;
}

std::vector<uint8_t> SerializeAstcCache(const AstcImage &image) {
    std::vector<uint8_t> out(kMagic, kMagic + sizeof(kMagic));
    out.push_back(kCacheVersion);
    out.push_back(static_cast<uint8_t>(kAstcBlockDim));
    out.push_back(image.srgb ? 1 : 0);
    WriteU32(out, image.width);
    WriteU32(out, image.height);
    out.insert(out.end(), image.blocks.begin(), image.blocks.end());
    return out;
}

bool ParseAstcCache(const uint8_t *data, size_t size, AstcImage *out) {
    if (!data || !out || size < kHeaderBytes || std::memcmp(data, kMagic, sizeof(kMagic)) != 0 ||
        data[5] != kCacheVersion || data[6] != kAstcBlockDim) {
        return false;
    }
    const uint32_t width = ReadU32(data + 8);
    const uint32_t height = ReadU32(data + 12);
    if (width == 0 || height == 0 || size - kHeaderBytes != Astc6x6ByteSize(width, height)) {
        return false;
    }
    out->width = width;
    out->height = height;
    out->srgb = data[7] != 0;
    out->blocks.assign(data + kHeaderBytes, data + size);
    return true;
}
