#version 450

// docs/reports/MODO-AMBIENTE.md F2: passe de reducao. Fullscreen triangle
// via gl_VertexIndex (sem vertex buffer) — o alvo e so 32x18, nao precisa
// de geometria posicionada no mundo, so cobrir o framebuffer inteiro.

layout(location = 0) out vec2 vTexCoord;

void main() {
    vec2 pos = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
    vTexCoord = pos;
}
