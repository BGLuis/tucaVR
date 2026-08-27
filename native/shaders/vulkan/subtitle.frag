#version 450

layout(binding = 0) uniform sampler2D uSdfAtlas;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform SubtitlePushConstants {
    mat4 mvp;
    vec4 textColor;
    vec4 outlineColor;
    float textScale;
    float outlineWidth;
} pc;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec4 sampleColor = texture(uSdfAtlas, vTexCoord);
    
    // Suporte tanto para MSDF (RGB) quanto para SDF (Alpha/Monocromático)
    float dist = sampleColor.a > 0.0 ? sampleColor.a : median(sampleColor.r, sampleColor.g, sampleColor.b);
    
    float smoothWidth = fwidth(dist);
    if (smoothWidth <= 0.0) smoothWidth = 0.05;

    // Transição suave para o texto principal
    float alpha = smoothstep(0.5 - smoothWidth, 0.5 + smoothWidth, dist);

    // Contorno preto para contraste sobre qualquer fundo
    float outlineAlpha = smoothstep(0.5 - pc.outlineWidth - smoothWidth, 0.5 - pc.outlineWidth + smoothWidth, dist);

    vec4 finalColor = mix(pc.outlineColor, vColor, alpha);
    finalColor.a = outlineAlpha * vColor.a;

    if (finalColor.a < 0.01) {
        discard;
    }

    fragColor = finalColor;
}
