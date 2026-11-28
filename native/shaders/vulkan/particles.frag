#version 450

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 tintColor;
    vec4 glowParams; // x: screenGlow, y: brightnessLinear, z: timeSeconds, w: reserved
    vec4 screenPos;
} pc;

layout(location = 0) in vec3 vWorldPosition;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vQuadCoord;

layout(location = 0) out vec4 outColor;

void main() {
    // Soft radial falloff without `discard` so Adreno Early-Z / LRZ stays intact.
    float distSq = dot(vQuadCoord, vQuadCoord);
    float radial = clamp(1.0 - distSq, 0.0, 1.0);
    float shape = radial * radial * radial;

    // Gentle sinusoidal twinkle driven by time and particle color/position hash.
    float time = pc.glowParams.z;
    float seed = dot(vColor.rgb, vec3(37.1, 91.7, 53.3));
    float freq = 0.8 + fract(sin(seed) * 43758.5453) * 1.4;
    float phase = fract(sin(seed * 1.7) * 23421.631) * 6.28318;
    float twinkle = 0.55 + 0.45 * sin(time * freq + phase);

    // Keep stardust faintly perceptible even at low environment brightness.
    float brightness = 0.18 + 0.82 * pc.glowParams.y;
    float alpha = vColor.a * shape * twinkle * brightness;
    outColor = vec4(vColor.rgb * (0.85 + 0.35 * twinkle) * brightness, alpha);
}
