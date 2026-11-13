#version 450

// Converts one eye's crop of the decoded frame into a composition layer swapchain: a fullscreen triangle strip with no
// vertex buffer. Texture row 0 is the top of the swapchain image, so the video is written upright.

layout(push_constant) uniform PushConstants {
    vec2  uvOffset;         // top-left of the eye's crop in the decoded frame
    vec2  uvScale;          // size of the crop relative to the decoded frame
    float sharpness;        // 0.0 = direct sampling, > 0.0 = SGSR1 strength
    float texelWidth;       // 1.0 / decoded width
    float texelHeight;      // 1.0 / decoded height
    int   isHdr;            // 1 = PQ, 2 = HLG
    float colorTemperature; // Kelvin, or negative for Night Mode
    float hdrLuminance;     // viewer brightness multiplier on the HDR exposure, 1.0 = neutral
} pc;

layout(location = 0) out vec2 vTexCoord;

void main() {
    vec2 corner = vec2(float(gl_VertexIndex & 1), float(gl_VertexIndex >> 1));
    gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = corner * pc.uvScale + pc.uvOffset;
}
