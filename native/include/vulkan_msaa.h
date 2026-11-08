#pragma once

#include <cstdint>

namespace vrplayer {

constexpr uint32_t kMsaaSampleCount1 = 1u;
constexpr uint32_t kMsaaSampleCount4 = 4u;

struct EyeRenderPassMsaaConfig {
    uint32_t rasterizationSamples = kMsaaSampleCount1;
    uint32_t colorAttachmentSamples = kMsaaSampleCount1;
    uint32_t depthAttachmentSamples = kMsaaSampleCount1;
    uint32_t resolveAttachmentSamples = kMsaaSampleCount1;
    uint32_t attachmentCount = 2u;
    uint32_t colorAttachmentIndex = 0u;
    uint32_t depthAttachmentIndex = 1u;
    uint32_t resolveAttachmentIndex = 0u;
    bool hasResolveAttachment = false;
    bool colorStoreToMemory = true;
    bool colorIsTransient = false;
};

// Selects the eye render pass sample count (4x by default when supported by both color and depth
// attachments, falling back to 1x). An explicit requestedSamples of 1 forces single-sampled
// rendering (e.g. via `adb shell setprop debug.tucavr.msaa 1` for A/B GPU timing comparison).
inline uint32_t SelectEyeMsaaSampleCount(uint32_t colorSampleCountMask, uint32_t depthSampleCountMask,
                                         uint32_t requestedSamples = kMsaaSampleCount4) {
    if (requestedSamples == kMsaaSampleCount1) {
        return kMsaaSampleCount1;
    }
    const uint32_t commonMask = colorSampleCountMask & depthSampleCountMask;
    if ((commonMask & kMsaaSampleCount4) != 0u) {
        return kMsaaSampleCount4;
    }
    return kMsaaSampleCount1;
}

// Parses the value of `debug.tucavr.msaa` ("1" -> 1x, "4" or empty/other -> 4x).
inline uint32_t ParseMsaaOverrideProperty(const char *propValue) {
    if (propValue != nullptr && propValue[0] == '1' && propValue[1] == '\0') {
        return kMsaaSampleCount1;
    }
    return kMsaaSampleCount4;
}

// Builds the attachment topology for the main eye render pass.
// - At 4x MSAA: 3 attachments ([0] = 4x transient color with storeOp=DONT_CARE,
//   [1] = 4x transient depth with storeOp=DONT_CARE, [2] = 1x swapchain resolve with
//   loadOp=DONT_CARE and storeOp=STORE). All pipelines targeting this pass use
//   rasterizationSamples=4, avoiding VUID-VkGraphicsPipelineCreateInfo-multisampledRenderToSingleSampled-06853.
// - At 1x: 2 attachments ([0] = 1x swapchain color with storeOp=STORE, [1] = 1x transient depth)
//   and no resolve attachment (avoiding VUID-VkSubpassDescription-pResolveAttachments-00848).
inline EyeRenderPassMsaaConfig BuildEyeRenderPassMsaaConfig(uint32_t sampleCount) {
    EyeRenderPassMsaaConfig cfg{};
    if (sampleCount == kMsaaSampleCount4) {
        cfg.rasterizationSamples = kMsaaSampleCount4;
        cfg.colorAttachmentSamples = kMsaaSampleCount4;
        cfg.depthAttachmentSamples = kMsaaSampleCount4;
        cfg.resolveAttachmentSamples = kMsaaSampleCount1;
        cfg.attachmentCount = 3u;
        cfg.colorAttachmentIndex = 0u;
        cfg.depthAttachmentIndex = 1u;
        cfg.resolveAttachmentIndex = 2u;
        cfg.hasResolveAttachment = true;
        cfg.colorStoreToMemory = false;
        cfg.colorIsTransient = true;
    } else {
        cfg.rasterizationSamples = kMsaaSampleCount1;
        cfg.colorAttachmentSamples = kMsaaSampleCount1;
        cfg.depthAttachmentSamples = kMsaaSampleCount1;
        cfg.resolveAttachmentSamples = kMsaaSampleCount1;
        cfg.attachmentCount = 2u;
        cfg.colorAttachmentIndex = 0u;
        cfg.depthAttachmentIndex = 1u;
        cfg.resolveAttachmentIndex = 0u;
        cfg.hasResolveAttachment = false;
        cfg.colorStoreToMemory = true;
        cfg.colorIsTransient = false;
    }
    return cfg;
}

} // namespace vrplayer
