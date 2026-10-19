#pragma GCC diagnostic ignored "-Wunused-function"
#pragma GCC diagnostic ignored "-Wunused-variable"
#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_STDIO
#define CGLTF_IMPLEMENTATION
#include "environment_validator.h"

#include <cassert>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

// Helper to construct a minimal valid PNG image header in memory
static std::vector<uint8_t> CreateMinimalPng(uint32_t width, uint32_t height) {
    std::vector<uint8_t> png = {
        137,
        80,
        78,
        71,
        13,
        10,
        26,
        10, // PNG signature
        0,
        0,
        0,
        13, // IHDR chunk length (13 bytes)
        'I',
        'H',
        'D',
        'R',
        static_cast<uint8_t>((width >> 24) & 0xFF),
        static_cast<uint8_t>((width >> 16) & 0xFF),
        static_cast<uint8_t>((width >> 8) & 0xFF),
        static_cast<uint8_t>(width & 0xFF),
        static_cast<uint8_t>((height >> 24) & 0xFF),
        static_cast<uint8_t>((height >> 16) & 0xFF),
        static_cast<uint8_t>((height >> 8) & 0xFF),
        static_cast<uint8_t>(height & 0xFF),
        8,
        2,
        0,
        0,
        0, // 8-bit truecolor (RGB)
        0,
        0,
        0,
        0, // IHDR CRC dummy
        0,
        0,
        0,
        0, // IDAT length 0
        'I',
        'D',
        'A',
        'T',
        0,
        0,
        0,
        0 // IDAT CRC dummy
    };
    return png;
}

// Builds a valid .glb binary model in memory with the requested triangle count and optional embedded image
static std::vector<uint8_t> BuildGlb(size_t triangleCount, const std::vector<uint8_t> &imageBytes = {},
                                     bool indexed = true) {
    size_t indexCount = indexed ? (triangleCount * 3) : 0;
    size_t vertexCount = indexed ? 3 : (triangleCount * 3);
    size_t vertexByteSize = vertexCount * 3 * sizeof(float);
    size_t indexByteSize = indexCount * sizeof(uint32_t); // 5125 = UNSIGNED_INT

    size_t binSize = vertexByteSize + indexByteSize + imageBytes.size();
    while (binSize % 4 != 0) {
        binSize++;
    }

    std::string json = "{\"asset\":{\"version\":\"2.0\"}";
    if (indexed) {
        json += ",\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0},\"indices\":1}]}]";
    } else {
        json += ",\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0}}]}]";
    }

    json += ",\"accessors\":[";
    // Accessor 0: positions
    json += "{\"bufferView\":0,\"byteOffset\":0,\"componentType\":5126,\"count\":" + std::to_string(vertexCount) +
            ",\"type\":\"VEC3\",\"max\":[1,1,0],\"min\":[0,0,0]}";
    if (indexed) {
        // Accessor 1: indices
        json += ",{\"bufferView\":1,\"byteOffset\":0,\"componentType\":5125,\"count\":" + std::to_string(indexCount) +
                ",\"type\":\"SCALAR\",\"max\":[2],\"min\":[0]}";
    }
    json += "]";

    json += ",\"bufferViews\":[";
    // bufferView 0: vertices
    json += "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":" + std::to_string(vertexByteSize) + "}";
    if (indexed) {
        // bufferView 1: indices
        json += ",{\"buffer\":0,\"byteOffset\":" + std::to_string(vertexByteSize) +
                ",\"byteLength\":" + std::to_string(indexByteSize) + "}";
    }
    if (!imageBytes.empty()) {
        size_t imgOffset = vertexByteSize + indexByteSize;
        size_t imgViewIdx = indexed ? 2 : 1;
        json += ",{\"buffer\":0,\"byteOffset\":" + std::to_string(imgOffset) +
                ",\"byteLength\":" + std::to_string(imageBytes.size()) + "}";
        json += "]";
        json += ",\"images\":[{\"bufferView\":" + std::to_string(imgViewIdx) + ",\"mimeType\":\"image/png\"}]";
    } else {
        json += "]";
    }

    json += ",\"buffers\":[{\"byteLength\":" + std::to_string(binSize) + "}]";
    json += "}";

    // Align JSON to 4-byte boundary with spaces
    while (json.size() % 4 != 0) {
        json += ' ';
    }

    uint32_t jsonChunkLen = static_cast<uint32_t>(json.size());
    uint32_t binChunkLen = static_cast<uint32_t>(binSize);
    uint32_t totalLen = 12 + 8 + jsonChunkLen + 8 + binChunkLen;

    std::vector<uint8_t> glb;
    glb.reserve(totalLen);

    auto push32 = [&](uint32_t v) {
        glb.push_back(static_cast<uint8_t>(v & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };

    // Header
    push32(0x46546C67); // "glTF"
    push32(2);          // version 2
    push32(totalLen);

    // JSON chunk
    push32(jsonChunkLen);
    push32(0x4E4F534A); // "JSON"
    glb.insert(glb.end(), json.begin(), json.end());

    // BIN chunk
    push32(binChunkLen);
    push32(0x004E4942); // "BIN\0"

    // Vertex data (positions)
    std::vector<float> verts(vertexCount * 3, 0.0f);
    const uint8_t *vptr = reinterpret_cast<const uint8_t *>(verts.data());
    glb.insert(glb.end(), vptr, vptr + vertexByteSize);

    // Index data
    if (indexed) {
        std::vector<uint32_t> indices(indexCount, 0);
        const uint8_t *iptr = reinterpret_cast<const uint8_t *>(indices.data());
        glb.insert(glb.end(), iptr, iptr + indexByteSize);
    }

    // Image data
    if (!imageBytes.empty()) {
        glb.insert(glb.end(), imageBytes.begin(), imageBytes.end());
    }

    // Align to totalLen
    while (glb.size() < totalLen) {
        glb.push_back(0);
    }

    return glb;
}

static void Test100kTrianglesAccepted() {
    auto glb = BuildGlb(100000);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::Ok);
    assert(report.result == EnvironmentValidationResult::Ok);
    assert(report.totalTriangles == 100000);
    assert(std::string(report.messageKey) == "env_validate_ok");
    std::cout << "[PASS] Test100kTrianglesAccepted (100,000 triangles accepted)\n";
}

static void Test100kPlusOneTrianglesRejected() {
    auto glb = BuildGlb(100001);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::TooManyTriangles);
    assert(report.result == EnvironmentValidationResult::TooManyTriangles);
    assert(report.totalTriangles == 100001);
    assert(std::string(report.messageKey) == "env_error_too_many_triangles");
    std::cout << "[PASS] Test100kPlusOneTrianglesRejected (100,001 triangles rejected)\n";
}

static void TestTexture4096Accepted() {
    auto png = CreateMinimalPng(4096, 2048);
    auto glb = BuildGlb(10, png);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::Ok);
    assert(report.result == EnvironmentValidationResult::Ok);
    assert(report.maxTextureWidth == 4096);
    assert(report.maxTextureHeight == 2048);
    assert(std::string(report.messageKey) == "env_validate_ok");
    std::cout << "[PASS] TestTexture4096Accepted (4096px texture accepted)\n";
}

static void TestTexture4097Rejected() {
    auto png = CreateMinimalPng(4097, 1024);
    auto glb = BuildGlb(10, png);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::TextureTooLarge);
    assert(report.result == EnvironmentValidationResult::TextureTooLarge);
    assert(report.maxTextureWidth == 4097);
    assert(std::string(report.messageKey) == "env_error_texture_too_large");
    std::cout << "[PASS] TestTexture4097Rejected (4097px texture rejected)\n";
}

static void TestCorruptFileRejected() {
    std::vector<uint8_t> corruptData = {0x00, 0x01, 0x02, 0x03, 0xFF, 0xFE};
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(corruptData.data(), corruptData.size(), &report);

    assert(res == EnvironmentValidationResult::CorruptFile);
    assert(report.result == EnvironmentValidationResult::CorruptFile);
    assert(std::string(report.messageKey) == "env_error_corrupt_file");

    // Null buffer
    res = ValidateEnvironmentMemory(nullptr, 0, &report);
    assert(res == EnvironmentValidationResult::CorruptFile);

    std::cout << "[PASS] TestCorruptFileRejected (corrupt and empty files rejected)\n";
}

static void TestCorruptTextureRejected() {
    std::vector<uint8_t> invalidPngBytes = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    auto glb = BuildGlb(5, invalidPngBytes);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::TextureCorrupt);
    assert(report.result == EnvironmentValidationResult::TextureCorrupt);
    assert(std::string(report.messageKey) == "env_error_corrupt_texture");
    std::cout << "[PASS] TestCorruptTextureRejected (corrupt texture rejected)\n";
}

static void TestInvalidGltfRejected() {
    // Construct a GLB whose accessor specifies bufferView out of range or inconsistent sizes
    std::string json = "{\"asset\":{\"version\":\"2.0\"},\"accessors\":[{\"bufferView\":0,\"count\":10,"
                       "\"componentType\":5126,\"type\":\"VEC3\"}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,"
                       "\"byteLength\":10}],\"buffers\":[{\"byteLength\":10}]}";
    while (json.size() % 4 != 0) {
        json += ' ';
    }
    uint32_t jsonLen = static_cast<uint32_t>(json.size());
    uint32_t binLen = 12;
    uint32_t totalLen = 12 + 8 + jsonLen + 8 + binLen;

    std::vector<uint8_t> glb;
    auto push32 = [&](uint32_t v) {
        glb.push_back(static_cast<uint8_t>(v & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        glb.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
    };
    push32(0x46546C67);
    push32(2);
    push32(totalLen);
    push32(jsonLen);
    push32(0x4E4F534A);
    glb.insert(glb.end(), json.begin(), json.end());
    push32(binLen);
    push32(0x004E4942);
    for (size_t i = 0; i < binLen; ++i) {
        glb.push_back(0);
    }

    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::InvalidGltf);
    assert(report.result == EnvironmentValidationResult::InvalidGltf);
    assert(std::string(report.messageKey) == "env_error_invalid_gltf");
    std::cout << "[PASS] TestInvalidGltfRejected (invalid glTF specification rejected)\n";
}

static void TestNonIndexedTriangles() {
    auto glb = BuildGlb(200, {}, false);
    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentMemory(glb.data(), glb.size(), &report);

    assert(res == EnvironmentValidationResult::Ok);
    assert(report.totalTriangles == 200);
    assert(std::string(report.messageKey) == "env_validate_ok");
    std::cout << "[PASS] TestNonIndexedTriangles (non-indexed triangles counted correctly)\n";
}

static void TestValidateEnvironmentFile() {
    auto glb = BuildGlb(50);
    const char *tmpPath = "/tmp/tucavr_test_env_valid.glb";
    {
        std::ofstream out(tmpPath, std::ios::binary);
        out.write(reinterpret_cast<const char *>(glb.data()), glb.size());
    }

    EnvironmentValidationReport report;
    EnvironmentValidationResult res = ValidateEnvironmentFile(tmpPath, &report);
    assert(res == EnvironmentValidationResult::Ok);
    assert(report.totalTriangles == 50);
    assert(std::string(report.messageKey) == "env_validate_ok");

    std::remove(tmpPath);

    // Non-existent file
    res = ValidateEnvironmentFile("/tmp/non_existent_file_tucavr.glb", &report);
    assert(res == EnvironmentValidationResult::CorruptFile);

    std::cout << "[PASS] TestValidateEnvironmentFile (file loading variant tested)\n";
}

int main() {
    std::cout << "--- Executing unit tests for environment_validator.h ---\n";
    Test100kTrianglesAccepted();
    Test100kPlusOneTrianglesRejected();
    TestTexture4096Accepted();
    TestTexture4097Rejected();
    TestCorruptFileRejected();
    TestCorruptTextureRejected();
    TestInvalidGltfRejected();
    TestNonIndexedTriangles();
    TestValidateEnvironmentFile();
    std::cout << "--- All environment_validator.h unit tests passed successfully! ---\n";
    return 0;
}
