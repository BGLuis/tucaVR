#version 450

layout(set = 0, binding = 0) uniform sampler2D albedoMap;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vTintColor;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = texture(albedoMap, vTexCoord);
    vec3 lightDir = normalize(vec3(0.2, 1.0, 0.5));
    float diff = max(dot(normalize(vNormal), lightDir), 0.0);
    float ambient = 0.55;
    vec3 lighting = vec3(ambient + diff * 0.45);

    outColor = vec4(texColor.rgb * lighting * vTintColor.rgb, texColor.a * vTintColor.a);
}
