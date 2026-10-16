#version 450

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
    vec4 glowParams; // x: screenGlow, y: brightness, z: timeSeconds, w: reserved
    vec4 screenPos;
} pc;

layout(location = 0) in vec3 vWorldPosition;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vQuadCoord;

layout(location = 0) out vec4 outColor;

void main() {
    // Soft circular particle shape
    float distSq = dot(vQuadCoord, vQuadCoord);
    if (distSq > 1.0) {
        discard;
    }
    float shape = exp(-distSq * 2.5);

    // Subtle sinusoidal twinkle based on time and particle position hash
    float time = pc.glowParams.z;
    float seed = dot(vWorldPosition, vec3(12.9898, 78.233, 45.164));
    float freq = 1.2 + fract(sin(seed) * 43758.5453) * 1.8;
    float phase = fract(sin(seed * 1.5) * 23421.631) * 6.28318;
    float twinkle = 0.5 + 0.5 * sin(time * freq + phase);

    // Modulated by environment brightness (glowParams.y)
    float brightness = pc.glowParams.y;
    float alpha = vColor.a * shape * (0.35 + 0.65 * twinkle) * brightness;
    outColor = vec4(vColor.rgb * (0.8 + 0.4 * twinkle) * brightness, alpha);
}
