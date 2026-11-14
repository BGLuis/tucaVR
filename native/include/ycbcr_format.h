#pragma once

#include <cstdint>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

namespace vrplayer {

// Resolves the chroma offset using the driver's suggested offset and the supported format features.
inline VkChromaLocation ResolveChromaLocation(VkChromaLocation suggested, VkFormatFeatureFlags features) {
    const bool supportsCosited = (features & VK_FORMAT_FEATURE_COSITED_CHROMA_SAMPLES_BIT) != 0;
    const bool supportsMidpoint = (features & VK_FORMAT_FEATURE_MIDPOINT_CHROMA_SAMPLES_BIT) != 0;

    if (suggested == VK_CHROMA_LOCATION_COSITED_EVEN) {
        if (supportsCosited) {
            return VK_CHROMA_LOCATION_COSITED_EVEN;
        }
        if (supportsMidpoint) {
            return VK_CHROMA_LOCATION_MIDPOINT;
        }
        return VK_CHROMA_LOCATION_COSITED_EVEN;
    }
    if (suggested == VK_CHROMA_LOCATION_MIDPOINT) {
        if (supportsMidpoint) {
            return VK_CHROMA_LOCATION_MIDPOINT;
        }
        if (supportsCosited) {
            return VK_CHROMA_LOCATION_COSITED_EVEN;
        }
        return VK_CHROMA_LOCATION_MIDPOINT;
    }
    if (supportsMidpoint) {
        return VK_CHROMA_LOCATION_MIDPOINT;
    }
    if (supportsCosited) {
        return VK_CHROMA_LOCATION_COSITED_EVEN;
    }
    return VK_CHROMA_LOCATION_MIDPOINT;
}

// Resolves linear or nearest chroma filtering based on format features.
inline VkFilter ResolveChromaFilter(VkFormatFeatureFlags features) {
    if ((features & VK_FORMAT_FEATURE_SAMPLED_IMAGE_YCBCR_CONVERSION_LINEAR_FILTER_BIT) != 0) {
        return VK_FILTER_LINEAR;
    }
    return VK_FILTER_NEAREST;
}

// Sanitizes a component swizzle value to ensure it stays within the valid enum range.
inline VkComponentSwizzle SanitizeComponentSwizzle(VkComponentSwizzle swizzle) {
    if (swizzle >= VK_COMPONENT_SWIZZLE_IDENTITY && swizzle <= VK_COMPONENT_SWIZZLE_A) {
        return swizzle;
    }
    return VK_COMPONENT_SWIZZLE_IDENTITY;
}

// Key identifying the YCbCr conversion configuration.
// Used to cache and avoid redundant pipeline rebuilds when incoming frames have identical properties.
struct YcbcrFormatKey {
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint64_t externalFormat = 0;
    VkSamplerYcbcrModelConversion ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
    VkSamplerYcbcrRange ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
    VkComponentMapping components = {
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
    };
    VkChromaLocation xChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    VkChromaLocation yChromaOffset = VK_CHROMA_LOCATION_MIDPOINT;
    VkFilter chromaFilter = VK_FILTER_LINEAR;
    VkBool32 forceExplicitReconstruction = VK_FALSE;

    bool operator==(const YcbcrFormatKey &other) const {
        return format == other.format && externalFormat == other.externalFormat && ycbcrModel == other.ycbcrModel &&
               ycbcrRange == other.ycbcrRange && components.r == other.components.r &&
               components.g == other.components.g && components.b == other.components.b &&
               components.a == other.components.a && xChromaOffset == other.xChromaOffset &&
               yChromaOffset == other.yChromaOffset && chromaFilter == other.chromaFilter &&
               forceExplicitReconstruction == other.forceExplicitReconstruction;
    }

    bool operator!=(const YcbcrFormatKey &other) const { return !(*this == other); }

    // Derives a key from VkAndroidHardwareBufferFormatPropertiesANDROID.
    static YcbcrFormatKey FromAndroidProperties(const VkAndroidHardwareBufferFormatPropertiesANDROID &props) {
        YcbcrFormatKey key{};

        if (props.format != VK_FORMAT_UNDEFINED) {
            key.format = props.format;
            key.externalFormat = 0;
        } else {
            key.format = VK_FORMAT_UNDEFINED;
            key.externalFormat = props.externalFormat;
        }

        switch (props.suggestedYcbcrModel) {
        case VK_SAMPLER_YCBCR_MODEL_CONVERSION_RGB_IDENTITY:
        case VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_IDENTITY:
        case VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709:
        case VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_601:
        case VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_2020:
            key.ycbcrModel = props.suggestedYcbcrModel;
            break;
        default:
            key.ycbcrModel = VK_SAMPLER_YCBCR_MODEL_CONVERSION_YCBCR_709;
            break;
        }

        switch (props.suggestedYcbcrRange) {
        case VK_SAMPLER_YCBCR_RANGE_ITU_FULL:
        case VK_SAMPLER_YCBCR_RANGE_ITU_NARROW:
            key.ycbcrRange = props.suggestedYcbcrRange;
            break;
        default:
            key.ycbcrRange = VK_SAMPLER_YCBCR_RANGE_ITU_NARROW;
            break;
        }

        key.components.r = SanitizeComponentSwizzle(props.samplerYcbcrConversionComponents.r);
        key.components.g = SanitizeComponentSwizzle(props.samplerYcbcrConversionComponents.g);
        key.components.b = SanitizeComponentSwizzle(props.samplerYcbcrConversionComponents.b);
        key.components.a = SanitizeComponentSwizzle(props.samplerYcbcrConversionComponents.a);

        key.xChromaOffset = ResolveChromaLocation(props.suggestedXChromaOffset, props.formatFeatures);
        key.yChromaOffset = ResolveChromaLocation(props.suggestedYChromaOffset, props.formatFeatures);

        key.chromaFilter = ResolveChromaFilter(props.formatFeatures);
        key.forceExplicitReconstruction = VK_FALSE;

        return key;
    }
};

// Populates VkSamplerYcbcrConversionCreateInfo and chained VkExternalFormatANDROID from a format key.
inline void PopulateYcbcrConversionCreateInfo(const YcbcrFormatKey &key, VkSamplerYcbcrConversionCreateInfo &outInfo,
                                              VkExternalFormatANDROID &outExtFormat) {
    outExtFormat = {};
    outExtFormat.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    outExtFormat.pNext = nullptr;
    outExtFormat.externalFormat = key.externalFormat;

    outInfo = {};
    outInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
    if (key.format == VK_FORMAT_UNDEFINED && key.externalFormat != 0) {
        outInfo.pNext = &outExtFormat;
        outInfo.format = VK_FORMAT_UNDEFINED;
    } else {
        outInfo.pNext = nullptr;
        outInfo.format = key.format;
    }
    outInfo.ycbcrModel = key.ycbcrModel;
    outInfo.ycbcrRange = key.ycbcrRange;
    outInfo.components = key.components;
    outInfo.xChromaOffset = key.xChromaOffset;
    outInfo.yChromaOffset = key.yChromaOffset;
    outInfo.chromaFilter = key.chromaFilter;
    outInfo.forceExplicitReconstruction = key.forceExplicitReconstruction;
}

} // namespace vrplayer
