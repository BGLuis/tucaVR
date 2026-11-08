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
    std::cout << "[PASS] TestBuildEyeRenderPassMsaaConfig1xInvariants\n";
}

int main() {
    TestSelectEyeMsaaSampleCountDefaultsTo4xWhenSupported();
    TestSelectEyeMsaaSampleCountFallsBackTo1xWhenUnsupported();
    TestSelectEyeMsaaSampleCountHonorsExplicit1xOverride();
    TestParseMsaaOverrideProperty();
    TestBuildEyeRenderPassMsaaConfig4xInvariants();
    TestBuildEyeRenderPassMsaaConfig1xInvariants();
    return 0;
}
