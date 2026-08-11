#version 450
#extension GL_EXT_samplerless_texture_functions : enable

// Estagio 3 do plano de migracao Vulkan (docs/VULKAN-MIGRATION-PLAN.md):
// fragment shader que amostra a textura YCbCr importada de AHardwareBuffer.
//
// O sampler precisa ser do tipo combinedImageSampler com conversao YCbCr —
// VkSamplerYcbcrConversion e embutida no layout do descriptor set via
// VkSamplerYcbcrConversionInfo. Isso e diferente do GLES, onde a extensao
// GL_OES_EGL_image_external_essl3 fazia a conversao de forma transparente.
// Aqui a conversao e explicita no pipeline Vulkan, mas o acesso no shader
// ainda e um texture2D normal — o driver resolve o YUV internamente.

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(videoTexture, vTexCoord);
}
