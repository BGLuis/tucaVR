#include "ycbcr_format.h"

#include <cassert>
#include <iostream>

using namespace vrplayer;

static void TestDefaultFormatKey() {
    YcbcrFormatKey key{};
    assert(key.format == VK_FORMAT_UNDEFINED);
    assert(key.externalFormat == 0);
    assert(key.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709);
    assert(key.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_NARROW);
    assert(key.components.r == VK_COMPONENT_SWIZZLE_IDENTITY);
    assert(key.components.g == VK_COMPONENT_SWIZZLE_IDENTITY);
    assert(key.components.b == VK_COMPONENT_SWIZZLE_IDENTITY);
    assert(key.components.a == VK_COMPONENT_SWIZZLE_IDENTITY);
    assert(key.xChromaOffset == VK_CHROMA_LOCATION_MIDPOINT);
    assert(key.yChromaOffset == VK_CHROMA_LOCATION_MIDPOINT);
    assert(key.chromaFilter == VK_FILTER_LINEAR);
    assert(key.forceExplicitReconstruction == VK_FALSE);
    std::cout << "[PASS] TestDefaultFormatKey\n";
}

static void TestFromAndroidPropertiesKnownFormat() {
    VkAndroidHardwareBufferFormatPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    props.format = VK_FORMAT_G8_B8R8_2PLANE_420_UNORM;
    props.externalFormat = 0xdeadbeefULL;
    props.formatFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT |
                           VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
    props.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    props.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    props.suggestedXChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    props.suggestedYChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    props.samplerYcbcrConversionComponents = {VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                                              VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY};

    YcbcrFormatKey key = YcbcrFormatKey::FromAndroidProperties(props);
    assert(key.format == VK_FORMAT_G8_B8R8_2PLANE_420_UNORM);
    assert(key.externalFormat == 0);
    assert(key.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709);
    assert(key.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_NARROW);
    assert(key.chromaFilter == VK_FILTER_LINEAR);
    std::cout << "[PASS] TestFromAndroidPropertiesKnownFormat\n";
}

static void TestFromAndroidPropertiesExternalFormat() {
    VkAndroidHardwareBufferFormatPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    props.format = VK_FORMAT_UNDEFINED;
    props.externalFormat = 0x12345678ULL;
    props.formatFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT |
                           VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT;
    props.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    props.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    props.suggestedXChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    props.suggestedYChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    props.samplerYcbcrConversionComponents = {VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B,
                                              VK_COMPONENT_SWIZZLE_A};

    YcbcrFormatKey key = YcbcrFormatKey::FromAndroidProperties(props);
    assert(key.format == VK_FORMAT_UNDEFINED);
    assert(key.externalFormat == 0x12345678ULL);
    assert(key.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601);
    assert(key.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_FULL);
    assert(key.xChromaOffset == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(key.yChromaOffset == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(key.components.r == VK_COMPONENT_SWIZZLE_R);
    std::cout << "[PASS] TestFromAndroidPropertiesExternalFormat\n";
}

static void TestSuggestedModelAndRange() {
    VkAndroidHardwareBufferFormatPropertiesANDROID props{};
    props.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    props.format = VK_FORMAT_UNDEFINED;
    props.externalFormat = 0x100;
    props.formatFeatures = VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;

    // BT.2020 and narrow range
    props.suggestedYcbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020;
    props.suggestedYcbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    YcbcrFormatKey key1 = YcbcrFormatKey::FromAndroidProperties(props);
    assert(key1.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020);
    assert(key1.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_NARROW);

    // Invalid model and range fallback to BT.709 and narrow
    props.suggestedYcbcrModel = static_cast<VkSamplerYcbcrModelConversion>(9999);
    props.suggestedYcbcrRange = static_cast<VkSamplerYcbcrRange>(9999);
    YcbcrFormatKey key2 = YcbcrFormatKey::FromAndroidProperties(props);
    assert(key2.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709);
    assert(key2.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_NARROW);
    std::cout << "[PASS] TestSuggestedModelAndRange\n";
}

static void TestChromaLocationFallback() {
    // Both supported: preserves suggested
    const VkFormatFeatureFlags bothFeatures =
        VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT | VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_COSITED_EVEN, bothFeatures) == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_MIDPOINT, bothFeatures) == VK_CHROMA_LOCATION_MIDPOINT);

    // Only midpoint supported: COSITED falls back to MIDPOINT
    const VkFormatFeatureFlags onlyMidpoint = VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT;
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_COSITED_EVEN, onlyMidpoint) == VK_CHROMA_LOCATION_MIDPOINT);
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_MIDPOINT, onlyMidpoint) == VK_CHROMA_LOCATION_MIDPOINT);

    // Only cosited supported: MIDPOINT falls back to COSITED
    const VkFormatFeatureFlags onlyCosited = VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT;
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_MIDPOINT, onlyCosited) == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_COSITED_EVEN, onlyCosited) == VK_CHROMA_LOCATION_COSITED_EVEN);

    // Neither supported: retains suggested
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_COSITED_EVEN, 0) == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(ResolveChromaLocation(VK_CHROMA_LOCATION_MIDPOINT, 0) == VK_CHROMA_LOCATION_MIDPOINT);
    std::cout << "[PASS] TestChromaLocationFallback\n";
}

static void TestChromaFilterSelection() {
    const VkFormatFeatureFlags linearFeature = VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT;
    assert(ResolveChromaFilter(linearFeature) == VK_FILTER_LINEAR);
    assert(ResolveChromaFilter(0) == VK_FILTER_NEAREST);
    std::cout << "[PASS] TestChromaFilterSelection\n";
}

static void TestComponentSwizzleSanitization() {
    assert(SanitizeComponentSwizzle(VK_COMPONENT_SWIZZLE_IDENTITY) == VK_COMPONENT_SWIZZLE_IDENTITY);
    assert(SanitizeComponentSwizzle(VK_COMPONENT_SWIZZLE_R) == VK_COMPONENT_SWIZZLE_R);
    assert(SanitizeComponentSwizzle(VK_COMPONENT_SWIZZLE_A) == VK_COMPONENT_SWIZZLE_A);
    assert(SanitizeComponentSwizzle(static_cast<VkComponentSwizzle>(100)) == VK_COMPONENT_SWIZZLE_IDENTITY);
    std::cout << "[PASS] TestComponentSwizzleSanitization\n";
}

static void TestKeyEqualityAndInequality() {
    YcbcrFormatKey a{};
    YcbcrFormatKey b{};
    assert(a == b);
    assert(!(a != b));

    b.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    assert(a != b);
    assert(!(a == b));

    b = a;
    b.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    assert(a != b);

    b = a;
    b.externalFormat = 0x555;
    assert(a != b);

    b = a;
    b.chromaFilter = VK_FILTER_NEAREST;
    assert(a != b);
    std::cout << "[PASS] TestKeyEqualityAndInequality\n";
}

static void TestPopulateYcbcrConversionCreateInfo() {
    YcbcrFormatKey key{};
    key.format = VK_FORMAT_UNDEFINED;
    key.externalFormat = 0xabcdefULL;
    key.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601;
    key.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_FULL;
    key.xChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    key.yChromaOffset = VK_CHROMA_LOCATION_COSITED_EVEN;
    key.chromaFilter = VK_FILTER_NEAREST;

    VkSamplerYcbcrConversionCreateInfo info{};
    VkExternalFormatANDROID extFormat{};
    PopulateYcbcrConversionCreateInfo(key, info, extFormat);

    assert(info.pNext == &extFormat);
    assert(info.format == VK_FORMAT_UNDEFINED);
    assert(extFormat.externalFormat == 0xabcdefULL);
    assert(info.ycbcrModel == VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601);
    assert(info.ycbcrRange == VK_SAMPLER_YCBCR_RANGE_ITU_FULL);
    assert(info.xChromaOffset == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(info.yChromaOffset == VK_CHROMA_LOCATION_COSITED_EVEN);
    assert(info.chromaFilter == VK_FILTER_NEAREST);
    std::cout << "[PASS] TestPopulateYcbcrConversionCreateInfo\n";
}

int main() {
    std::cout << "--- Executing unit tests for ycbcr_format.h ---\n";
    TestDefaultFormatKey();
    TestFromAndroidPropertiesKnownFormat();
    TestFromAndroidPropertiesExternalFormat();
    TestSuggestedModelAndRange();
    TestChromaLocationFallback();
    TestChromaFilterSelection();
    TestComponentSwizzleSanitization();
    TestKeyEqualityAndInequality();
    TestPopulateYcbcrConversionCreateInfo();
    std::cout << "--- All ycbcr_format.h unit tests passed successfully! ---\n";
    return 0;
}
