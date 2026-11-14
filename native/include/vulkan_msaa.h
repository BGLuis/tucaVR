#pragma once

#include <array>
#include <cstdint>
#include <vulkan/vulkan.h>

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
    uint32_t fdmAttachmentIndex = VK_ATTACHMENT_UNUSED;
    bool hasResolveAttachment = false;
    bool colorStoreToMemory = true;
    bool colorIsTransient = false;
    std::array<VkAttachmentDescription, 4> attachments{};
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
// - When hasFragmentDensityMap is true: an additional attachment at index attachmentCount++ is appended
//   with format=fdmFormat (VK_FORMAT_R8G8_UNORM), samples=1_BIT (even under 4x MSAA),
//   loadOp=DONT_CARE, storeOp=DONT_CARE, initialLayout/finalLayout=FRAGMENT_DENSITY_MAP_OPTIMAL_EXT.
inline EyeRenderPassMsaaConfig BuildEyeRenderPassMsaaConfig(VkSampleCountFlagBits samples, VkFormat colorFormat,
                                                            VkFormat depthFormat, bool hasFragmentDensityMap = false,
                                                            VkFormat fdmFormat = VK_FORMAT_R8G8_UNORM) {
    EyeRenderPassMsaaConfig cfg{};
    const bool is4x = (samples == VK_SAMPLE_COUNT_4_BIT);

    if (is4x) {
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

        // Attachment 0: 4x MSAA transient color (discarded after on-chip resolve)
        cfg.attachments[0].flags = 0;
        cfg.attachments[0].format = colorFormat;
        cfg.attachments[0].samples = VK_SAMPLE_COUNT_4_BIT;
        cfg.attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        cfg.attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cfg.attachments[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        // Attachment 1: 4x MSAA transient depth
        cfg.attachments[1].flags = 0;
        cfg.attachments[1].format = depthFormat;
        cfg.attachments[1].samples = VK_SAMPLE_COUNT_4_BIT;
        cfg.attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        cfg.attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cfg.attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        // Attachment 2: 1x swapchain color resolve target
        cfg.attachments[2].flags = 0;
        cfg.attachments[2].format = colorFormat;
        cfg.attachments[2].samples = VK_SAMPLE_COUNT_1_BIT;
        cfg.attachments[2].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[2].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        cfg.attachments[2].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[2].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[2].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cfg.attachments[2].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
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

        // Attachment 0: 1x swapchain color target
        cfg.attachments[0].flags = 0;
        cfg.attachments[0].format = colorFormat;
        cfg.attachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
        cfg.attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        cfg.attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        cfg.attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cfg.attachments[0].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        // Attachment 1: 1x transient depth
        cfg.attachments[1].flags = 0;
        cfg.attachments[1].format = depthFormat;
        cfg.attachments[1].samples = VK_SAMPLE_COUNT_1_BIT;
        cfg.attachments[1].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        cfg.attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[1].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[1].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[1].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        cfg.attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    }

    if (hasFragmentDensityMap) {
        const uint32_t fdmIndex = cfg.attachmentCount++;
        cfg.fdmAttachmentIndex = fdmIndex;
        cfg.attachments[fdmIndex].flags = 0;
        cfg.attachments[fdmIndex].format = fdmFormat;
        cfg.attachments[fdmIndex].samples = VK_SAMPLE_COUNT_1_BIT;
        cfg.attachments[fdmIndex].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[fdmIndex].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[fdmIndex].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        cfg.attachments[fdmIndex].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        cfg.attachments[fdmIndex].initialLayout = VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT;
        cfg.attachments[fdmIndex].finalLayout = VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT;
    }

    return cfg;
}

// Single-argument overload backwards-compatible with callers querying only sample count
// (falls back to standard sRGB color and 24-bit depth formats).
inline EyeRenderPassMsaaConfig BuildEyeRenderPassMsaaConfig(uint32_t sampleCount) {
    const VkSampleCountFlagBits samples =
        (sampleCount == kMsaaSampleCount4) ? VK_SAMPLE_COUNT_4_BIT : VK_SAMPLE_COUNT_1_BIT;
    return BuildEyeRenderPassMsaaConfig(samples, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D24_UNORM_S8_UINT, false);
}

} // namespace vrplayer
