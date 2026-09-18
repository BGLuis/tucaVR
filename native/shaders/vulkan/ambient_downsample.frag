#version 450

// docs/reports/MODO-AMBIENTE.md F2: reduz a textura YCbCr do video (set 0,
// mesmo videoDescriptorSetLayout do pipeline de video — reuso, nao
// duplicacao, ver 1.3/2.1 do relatorio) pra uma grade pequena, amostrando
// 4x4 taps por texel de saida. Mistura com o resultado do frame anterior
// (set 1, textura ping-pong) em vez de escrever a media nova direto — essa
// e a suavizacao temporal (2.3 do relatorio) rodando por-pixel sobre a
// textura reduzida inteira, em vez de uma unica cor RGB suavizada por
// MoveTowards (por isso o halo tem uma leve variacao espacial em vez de
// ficar chapado).

layout(set = 0, binding = 0) uniform sampler2D videoTexture;
layout(set = 1, binding = 0) uniform sampler2D prevAmbientTexture;

layout(push_constant) uniform PushConstants {
    float blend; // 0 = mantem o frame anterior, 1 = usa so a media nova
} pc;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    // Deve casar com vrplayer::kAmbientTargetWidth/Height (vr_player_ambient.h).
    const vec2 texelSize = vec2(1.0) / vec2(32.0, 18.0);
    // Footprint alargado (deve casar com vrplayer::kAmbientDownsampleKernelScale):
    // cada texel de saida amostra uma area 2x maior que o proprio texel,
    // sobrepondo 50% com os vizinhos. Sem isso, um detalhe fino do video
    // (ex. uma marca de porcentagem colorida) pode cair inteiro num texel
    // so, criando um salto de valor na borda dele que a interpolacao
    // bilinear nao suaviza — ampliado ~50-80x pelo halo, isso aparece como
    // banda/"borda treta" visivel.
    const float kKernelScale = 2.0;
    vec2 footprint = texelSize * kKernelScale;
    vec2 base = vTexCoord - footprint * 0.5;

    vec3 sum = vec3(0.0);
    for (int y = 0; y < 4; y++) {
        for (int x = 0; x < 4; x++) {
            vec2 offset = (vec2(x, y) + 0.5) / 4.0 * footprint;
            sum += texture(videoTexture, base + offset).rgb;
        }
    }
    vec3 newColor = sum / 16.0;
    vec3 prevColor = texture(prevAmbientTexture, vTexCoord).rgb;

    outColor = vec4(mix(prevColor, newColor, pc.blend), 1.0);
}
