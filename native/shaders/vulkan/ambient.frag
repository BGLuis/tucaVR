#version 450

// docs/reports/MODO-AMBIENTE.md F3: distancia assinada ate o retangulo do
// video (2.2 do relatorio). Dentro do retangulo [rectMin, rectMax] (que
// corresponde exatamente ao quad de video, ja que o halo e esse mesmo
// retangulo escalado por kAmbientHaloScale a partir do centro), amostra a
// textura reduzida remapeando a UV de volta pra 0..1; fora dele, clampa a
// amostra na borda (evita repetir/distorcer) e aplica queda smoothstep no
// alpha ate zero na borda externa do halo.
//
// A distancia em si NAO roda no UV cru (0..1) do quad do halo — a tela nao
// e quadrada (screenScaleX/Y), entao um smoothstep isotropico em UV vira
// anisotropico em unidades fisicas (o eixo com maior screenScale* "estica"
// a zona de queda proporcionalmente mais), e o halo lia como um retangulo
// esticado em vez de um brilho redondo. `vAspect` corrige isso: `p` abaixo
// e proporcional a posicao real no mundo (os dois eixos divididos pelo
// mesmo fator screenScaleY*kAmbientHaloScale), entao a distancia calculada
// sobre `p` ja e isotropica fisicamente.

layout(set = 0, binding = 0) uniform sampler2D ambientTexture;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) flat in float vInsetScale;
layout(location = 2) flat in float vEdgeWidth;
layout(location = 3) flat in float vIntensity;
layout(location = 4) flat in float vAspect;
layout(location = 0) out vec4 outColor;

void main() {
    vec2 p = (vTexCoord - 0.5) * vec2(vAspect, 1.0);
    vec2 rectHalf = vec2(vInsetScale * 0.5 * vAspect, vInsetScale * 0.5);

    vec2 outside = abs(p) - rectHalf;
    float dist = length(max(outside, 0.0));
    float alpha = (1.0 - smoothstep(0.0, vEdgeWidth, dist)) * vIntensity;

    vec2 rectMin = vec2(0.5 - vInsetScale * 0.5);
    vec2 texUv = clamp((vTexCoord - rectMin) / vInsetScale, 0.0, 1.0);
    vec3 color = texture(ambientTexture, texUv).rgb;

    outColor = vec4(color, alpha);
}
