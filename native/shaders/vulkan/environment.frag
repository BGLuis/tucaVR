#version 450

layout(location = 0) in vec3 vNormal;
layout(location = 1) in vec4 vColor;

layout(location = 0) out vec4 outColor;

void main() {
    vec3 lightDir = normalize(vec3(0.2, 1.0, 0.4));
    float diff = max(dot(normalize(vNormal), lightDir), 0.0);
    float ambient = 0.50;
    vec3 lighting = vec3(ambient + diff * 0.50);

    outColor = vec4(vColor.rgb * lighting, vColor.a);
}
