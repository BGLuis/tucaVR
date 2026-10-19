#pragma once

#ifndef CGLTF_H_INCLUDED__
#include "cgltf.h"
#endif
#ifndef STBI_INCLUDE_STB_IMAGE_H
#include "stb_image.h"
#endif

#include <cstddef>
#include <cstdint>
#include <string>

// Maximum allowed triangle count and texture dimensions for 3D environments
// per Phase 1.0 §1 T1.1 (Issue #62).
constexpr size_t kMaxEnvironmentTriangles = 100000;
constexpr int kMaxEnvironmentTextureDimension = 4096;

enum class EnvironmentValidationResult {
    Ok,
    CorruptFile,
    InvalidGltf,
    TooManyTriangles,
    TextureTooLarge,
    TextureCorrupt,
};

inline const char *EnvironmentValidationResultToMessageKey(EnvironmentValidationResult result) {
    switch (result) {
    case EnvironmentValidationResult::Ok:
        return "env_validate_ok";
    case EnvironmentValidationResult::CorruptFile:
        return "env_error_corrupt_file";
    case EnvironmentValidationResult::InvalidGltf:
        return "env_error_invalid_gltf";
    case EnvironmentValidationResult::TooManyTriangles:
        return "env_error_too_many_triangles";
    case EnvironmentValidationResult::TextureTooLarge:
        return "env_error_texture_too_large";
    case EnvironmentValidationResult::TextureCorrupt:
        return "env_error_corrupt_texture";
    }
    return "env_error_corrupt_file";
}

struct EnvironmentValidationReport {
    EnvironmentValidationResult result = EnvironmentValidationResult::Ok;
    const char *messageKey = "env_validate_ok";
    size_t totalTriangles = 0;
    int maxTextureWidth = 0;
    int maxTextureHeight = 0;
};

/**
 * Validates a parsed cgltf_data structure against hardware resource limits:
 * - Structural validity via cgltf_validate
 * - Total triangles <= 100,000 (summed across indexed and non-indexed triangle primitives)
 * - Texture image dimensions <= 4096 px per side
 */
inline EnvironmentValidationResult ValidateEnvironment(cgltf_data *data,
                                                       EnvironmentValidationReport *outReport = nullptr) {
    EnvironmentValidationReport report;
    if (!data) {
        report.result = EnvironmentValidationResult::CorruptFile;
        report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
        if (outReport) {
            *outReport = report;
        }
        return report.result;
    }

    // 1. Structural glTF validation
    if (cgltf_validate(data) != cgltf_result_success) {
        report.result = EnvironmentValidationResult::InvalidGltf;
        report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
        if (outReport) {
            *outReport = report;
        }
        return report.result;
    }

    // 2. Sum triangles across all meshes and primitives
    size_t totalTriangles = 0;
    for (size_t m = 0; m < data->meshes_count; ++m) {
        const cgltf_mesh &mesh = data->meshes[m];
        for (size_t p = 0; p < mesh.primitives_count; ++p) {
            const cgltf_primitive &prim = mesh.primitives[p];
            if (prim.type != cgltf_primitive_type_triangles) {
                continue;
            }

            if (prim.indices) {
                totalTriangles += prim.indices->count / 3;
            } else {
                for (size_t a = 0; a < prim.attributes_count; ++a) {
                    if (prim.attributes[a].type == cgltf_attribute_type_position && prim.attributes[a].data) {
                        totalTriangles += prim.attributes[a].data->count / 3;
                        break;
                    }
                }
            }
        }
    }
    report.totalTriangles = totalTriangles;

    if (totalTriangles > kMaxEnvironmentTriangles) {
        report.result = EnvironmentValidationResult::TooManyTriangles;
        report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
        if (outReport) {
            *outReport = report;
        }
        return report.result;
    }

    // 3. Check texture dimensions for each image (width <= 4096, height <= 4096)
    int maxWidth = 0;
    int maxHeight = 0;
    for (size_t i = 0; i < data->images_count; ++i) {
        const cgltf_image &img = data->images[i];
        const uint8_t *imgBuffer = nullptr;
        size_t imgBufferSize = 0;

        if (img.buffer_view) {
            imgBuffer = cgltf_buffer_view_data(img.buffer_view);
            imgBufferSize = img.buffer_view->size;
        }

        if (!imgBuffer || imgBufferSize == 0) {
            report.result = EnvironmentValidationResult::TextureCorrupt;
            report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
            if (outReport) {
                *outReport = report;
            }
            return report.result;
        }

        int width = 0;
        int height = 0;
        int channels = 0;
        int stbiOk = stbi_info_from_memory(reinterpret_cast<const stbi_uc *>(imgBuffer),
                                           static_cast<int>(imgBufferSize), &width, &height, &channels);

        if (!stbiOk || width <= 0 || height <= 0) {
            report.result = EnvironmentValidationResult::TextureCorrupt;
            report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
            if (outReport) {
                *outReport = report;
            }
            return report.result;
        }

        if (width > maxWidth) {
            maxWidth = width;
        }
        if (height > maxHeight) {
            maxHeight = height;
        }

        if (width > kMaxEnvironmentTextureDimension || height > kMaxEnvironmentTextureDimension) {
            report.maxTextureWidth = maxWidth;
            report.maxTextureHeight = maxHeight;
            report.result = EnvironmentValidationResult::TextureTooLarge;
            report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
            if (outReport) {
                *outReport = report;
            }
            return report.result;
        }
    }

    report.maxTextureWidth = maxWidth;
    report.maxTextureHeight = maxHeight;
    report.result = EnvironmentValidationResult::Ok;
    report.messageKey = EnvironmentValidationResultToMessageKey(report.result);
    if (outReport) {
        *outReport = report;
    }
    return report.result;
}

/**
 * Validates a .glb or .gltf binary/JSON buffer in memory.
 */
inline EnvironmentValidationResult ValidateEnvironmentMemory(const void *buffer, size_t size,
                                                             EnvironmentValidationReport *outReport = nullptr) {
    if (!buffer || size == 0) {
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    cgltf_options options{};
    cgltf_data *gltfData = nullptr;
    cgltf_result res = cgltf_parse(&options, buffer, size, &gltfData);
    if (res != cgltf_result_success || !gltfData) {
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    res = cgltf_load_buffers(&options, gltfData, nullptr);
    if (res != cgltf_result_success) {
        cgltf_free(gltfData);
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    EnvironmentValidationResult validationRes = ValidateEnvironment(gltfData, outReport);
    cgltf_free(gltfData);
    return validationRes;
}

/**
 * Validates a .glb or .gltf file from a filesystem path.
 */
inline EnvironmentValidationResult ValidateEnvironmentFile(const char *filePath,
                                                           EnvironmentValidationReport *outReport = nullptr) {
    if (!filePath || filePath[0] == '\0') {
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    cgltf_options options{};
    cgltf_data *gltfData = nullptr;
    cgltf_result res = cgltf_parse_file(&options, filePath, &gltfData);
    if (res != cgltf_result_success || !gltfData) {
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    res = cgltf_load_buffers(&options, gltfData, filePath);
    if (res != cgltf_result_success) {
        cgltf_free(gltfData);
        if (outReport) {
            outReport->result = EnvironmentValidationResult::CorruptFile;
            outReport->messageKey = EnvironmentValidationResultToMessageKey(outReport->result);
        }
        return EnvironmentValidationResult::CorruptFile;
    }

    EnvironmentValidationResult validationRes = ValidateEnvironment(gltfData, outReport);
    cgltf_free(gltfData);
    return validationRes;
}
