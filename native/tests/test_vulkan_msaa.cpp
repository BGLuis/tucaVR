#include "vulkan_msaa.h"

#include <cassert>
#include <iostream>

using namespace vrplayer;

static void TestSelectEyeMsaaSampleCountDefaultsTo4xWhenSupported() {
    const uint32_t colorMask = kMsaaSampleCount1 | 2u | kMsaaSampleCount4;
    const uint32_t depthMask = kMsaaSampleCount1 | kMsaaSampleCount4;
    assert(SelectEyeMsaaSampleCount(colorMask, depthMask) == kMsaaSampleCount4);
    assert(SelectEyeMsaaSampleCount(colorMask, depthMask, kMsaaSampleCount4) == kMsaaSampleCount4);
    std::cout << "[PASS] TestSelectEyeMsaaSampleCountDefaultsTo4xWhenSupported\n";
}

static void TestSelectEyeMsaaSampleCountFallsBackTo1xWhenUnsupported() {
    const uint32_t colorOnly1x = kMsaaSampleCount1 | 2u;
    const uint32_t depthWith4x = kMsaaSampleCount1 | kMsaaSampleCount4;
    assert(SelectEyeMsaaSampleCount(colorOnly1x, depthWith4x) == kMsaaSampleCount1);
    assert(SelectEyeMsaaSampleCount(depthWith4x, colorOnly1x) == kMsaaSampleCount1);
    assert(SelectEyeMsaaSampleCount(0u, 0u) == kMsaaSampleCount1);
    std::cout << "[PASS] TestSelectEyeMsaaSampleCountFallsBackTo1xWhenUnsupported\n";
}

static void TestSelectEyeMsaaSampleCountHonorsExplicit1xOverride() {
    const uint32_t maskWith4x = kMsaaSampleCount1 | kMsaaSampleCount4;
    assert(SelectEyeMsaaSampleCount(maskWith4x, maskWith4x, kMsaaSampleCount1) == kMsaaSampleCount1);
    std::cout << "[PASS] TestSelectEyeMsaaSampleCountHonorsExplicit1xOverride\n";
}

static void TestParseMsaaOverrideProperty() {
    assert(ParseMsaaOverrideProperty(nullptr) == kMsaaSampleCount4);
    assert(ParseMsaaOverrideProperty("") == kMsaaSampleCount4);
    assert(ParseMsaaOverrideProperty("4") == kMsaaSampleCount4);
    assert(ParseMsaaOverrideProperty("1") == kMsaaSampleCount1);
    assert(ParseMsaaOverrideProperty("10") == kMsaaSampleCount4);
    assert(ParseMsaaOverrideProperty("invalid") == kMsaaSampleCount4);
    std::cout << "[PASS] TestParseMsaaOverrideProperty\n";
}

static void TestBuildEyeRenderPassMsaaConfig4xInvariants() {
    const EyeRenderPassMsaaConfig cfg = BuildEyeRenderPassMsaaConfig(kMsaaSampleCount4);
    assert(cfg.attachmentCount == 3u);
    assert(cfg.colorAttachmentIndex == 0u);
    assert(cfg.depthAttachmentIndex == 1u);
    assert(cfg.resolveAttachmentIndex == 2u);
    assert(cfg.hasResolveAttachment);
    assert(!cfg.colorStoreToMemory);
    assert(cfg.colorIsTransient);
    // VUID-VkGraphicsPipelineCreateInfo-multisampledRenderToSingleSampled-06853:
    // pipeline rasterizationSamples must match subpass color and depth attachments.
    assert(cfg.rasterizationSamples == kMsaaSampleCount4);
    assert(cfg.colorAttachmentSamples == cfg.rasterizationSamples);
    assert(cfg.depthAttachmentSamples == cfg.rasterizationSamples);
    // VUID-VkSubpassDescription-pResolveAttachments-00848 & 00849:
    // color attachment is > 1x and resolve attachment is 1x.
    assert(cfg.colorAttachmentSamples > kMsaaSampleCount1);
    assert(cfg.resolveAttachmentSamples == kMsaaSampleCount1);
    assert(cfg.fdmAttachmentIndex == VK_ATTACHMENT_UNUSED);
    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfig4xInvariants\n";
}

static void TestBuildEyeRenderPassMsaaConfig1xInvariants() {
    const EyeRenderPassMsaaConfig cfg = BuildEyeRenderPassMsaaConfig(kMsaaSampleCount1);
    assert(cfg.attachmentCount == 2u);
    assert(cfg.colorAttachmentIndex == 0u);
    assert(cfg.depthAttachmentIndex == 1u);
    // VUID-VkSubpassDescription-pResolveAttachments-00848:
    // must not specify a resolve attachment when color attachment is 1x.
    assert(!cfg.hasResolveAttachment);
    assert(cfg.colorStoreToMemory);
    assert(!cfg.colorIsTransient);
    assert(cfg.rasterizationSamples == kMsaaSampleCount1);
    assert(cfg.colorAttachmentSamples == cfg.rasterizationSamples);
    assert(cfg.depthAttachmentSamples == cfg.rasterizationSamples);
    assert(cfg.fdmAttachmentIndex == VK_ATTACHMENT_UNUSED);
    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfig1xInvariants\n";
}

static void TestBuildEyeRenderPassMsaaConfig4xWithFdmInvariants() {
    const EyeRenderPassMsaaConfig cfg =
        BuildEyeRenderPassMsaaConfig(VK_SAMPLE_COUNT_4_BIT, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D24_UNORM_S8_UINT, true);
    assert(cfg.attachmentCount == 4u);
    assert(cfg.colorAttachmentIndex == 0u);
    assert(cfg.depthAttachmentIndex == 1u);
    assert(cfg.resolveAttachmentIndex == 2u);
    assert(cfg.fdmAttachmentIndex == 3u);
    assert(cfg.hasResolveAttachment);
    assert(cfg.rasterizationSamples == kMsaaSampleCount4);
    assert(cfg.colorAttachmentSamples == kMsaaSampleCount4);
    assert(cfg.depthAttachmentSamples == kMsaaSampleCount4);
    assert(cfg.resolveAttachmentSamples == kMsaaSampleCount1);

    // FDM attachment invariants:
    // MUST be VK_SAMPLE_COUNT_1_BIT even when color/depth are 4x MSAA
    assert(cfg.attachments[3].format == VK_FORMAT_R8G8_UNORM);
    assert(cfg.attachments[3].samples == VK_SAMPLE_COUNT_1_BIT);
    assert(cfg.attachments[3].loadOp == VK_ATTACHMENT_LOAD_OP_DONT_CARE);
    assert(cfg.attachments[3].storeOp == VK_ATTACHMENT_STORE_OP_DONT_CARE);
    assert(cfg.attachments[3].stencilLoadOp == VK_ATTACHMENT_LOAD_OP_DONT_CARE);
    assert(cfg.attachments[3].stencilStoreOp == VK_ATTACHMENT_STORE_OP_DONT_CARE);
    assert(cfg.attachments[3].initialLayout == VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT);
    assert(cfg.attachments[3].finalLayout == VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT);

    // Ensure color and depth attachments have correct 4x MSAA settings
    assert(cfg.attachments[0].samples == VK_SAMPLE_COUNT_4_BIT);
    assert(cfg.attachments[1].samples == VK_SAMPLE_COUNT_4_BIT);
    assert(cfg.attachments[2].samples == VK_SAMPLE_COUNT_1_BIT);

    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfig4xWithFdmInvariants\n";
}

static void TestBuildEyeRenderPassMsaaConfig1xWithFdmInvariants() {
    const EyeRenderPassMsaaConfig cfg =
        BuildEyeRenderPassMsaaConfig(VK_SAMPLE_COUNT_1_BIT, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D24_UNORM_S8_UINT, true);
    assert(cfg.attachmentCount == 3u);
    assert(cfg.colorAttachmentIndex == 0u);
    assert(cfg.depthAttachmentIndex == 1u);
    assert(cfg.fdmAttachmentIndex == 2u);
    assert(!cfg.hasResolveAttachment);
    assert(cfg.rasterizationSamples == kMsaaSampleCount1);
    assert(cfg.colorAttachmentSamples == kMsaaSampleCount1);
    assert(cfg.depthAttachmentSamples == kMsaaSampleCount1);

    // FDM attachment invariants at index 2
    assert(cfg.attachments[2].format == VK_FORMAT_R8G8_UNORM);
    assert(cfg.attachments[2].samples == VK_SAMPLE_COUNT_1_BIT);
    assert(cfg.attachments[2].loadOp == VK_ATTACHMENT_LOAD_OP_DONT_CARE);
    assert(cfg.attachments[2].storeOp == VK_ATTACHMENT_STORE_OP_DONT_CARE);
    assert(cfg.attachments[2].stencilLoadOp == VK_ATTACHMENT_LOAD_OP_DONT_CARE);
    assert(cfg.attachments[2].stencilStoreOp == VK_ATTACHMENT_STORE_OP_DONT_CARE);
    assert(cfg.attachments[2].initialLayout == VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT);
    assert(cfg.attachments[2].finalLayout == VK_IMAGE_LAYOUT_FRAGMENT_DENSITY_MAP_OPTIMAL_EXT);

    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfig1xWithFdmInvariants\n";
}

static void TestBuildEyeRenderPassMsaaConfigBackwardsCompatible3ArgCall() {
    const EyeRenderPassMsaaConfig cfg4x =
        BuildEyeRenderPassMsaaConfig(VK_SAMPLE_COUNT_4_BIT, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D24_UNORM_S8_UINT);
    assert(cfg4x.attachmentCount == 3u);
    assert(cfg4x.fdmAttachmentIndex == VK_ATTACHMENT_UNUSED);

    const EyeRenderPassMsaaConfig cfg1x =
        BuildEyeRenderPassMsaaConfig(VK_SAMPLE_COUNT_1_BIT, VK_FORMAT_R8G8B8A8_SRGB, VK_FORMAT_D24_UNORM_S8_UINT);
    assert(cfg1x.attachmentCount == 2u);
    assert(cfg1x.fdmAttachmentIndex == VK_ATTACHMENT_UNUSED);

    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfigBackwardsCompatible3ArgCall\n";
}

int main() {
    TestSelectEyeMsaaSampleCountDefaultsTo4xWhenSupported();
    TestSelectEyeMsaaSampleCountFallsBackTo1xWhenUnsupported();
    TestSelectEyeMsaaSampleCountHonorsExplicit1xOverride();
    TestParseMsaaOverrideProperty();
    TestBuildEyeRenderPassMsaaConfig4xInvariants();
    TestBuildEyeRenderPassMsaaConfig1xInvariants();
    TestBuildEyeRenderPassMsaaConfig4xWithFdmInvariants();
    TestBuildEyeRenderPassMsaaConfig1xWithFdmInvariants();
    TestBuildEyeRenderPassMsaaConfigBackwardsCompatible3ArgCall();
    return 0;
}
