#version 450

// Estagio 5 — fragment shader para quad SBS/OU e esfera 360/180.
// Porta a logica identica do caminho GLES (vr_player_app.cpp:673-831):
//   - Modo Flat2D: UV passado diretamente (mono, sem recorte)
//   - Modos SBS/SBSHalf: recorta em X (uv.x * 0.5 + eye * 0.5)
//   - Modos OU/OUHalf:  recorta em Y (uv.y * 0.5 + eye * 0.5)
//   - Modos esfera 180: discard fora do hemisferio frontal [0.25..0.75]
//   - Modos esfera SBS/OU: recorte de olho DEPOIS do recorte polar
//
// A textura e a mesma do Estagio 3 (YCbCr via sampler imutavel), entao
// este shader usa set=0/binding=0 combined image sampler — identico ao
// video.frag, mas com logica de UV extra para estereo/esfera.
//
// CAS (AMD FidelityFX Contrast Adaptive Sharpening simplificado) portado
// do shader GLES (vr_player_app.cpp:692-708 e 812-828) — formula de 4
// vizinhos que adapta a forca ao contraste local. Sempre ativo (kSharpen=1)
// pois o custo e irrelevante para 1-2 quads e a melhora de nitidez e
// visivel no Quest 3 (validado com vr_player_app.cpp).

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

layout(location = 0) in vec2  vTexCoord;
layout(location = 1) flat in int vEye;
layout(location = 2) flat in int vSwapEyes;
layout(location = 3) flat in int vStereoLayout;
layout(location = 4) flat in int vPolar180;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv = vTexCoord;
    int  eye = vEye;

    // Swap eyes (T1.5)
    if (vSwapEyes != 0) {
        eye = 1 - eye;
    }

    // Recorte polar 180 (T2.3): discard fora do hemisferio frontal
    if (vPolar180 != 0) {
        if (uv.x < 0.25 || uv.x > 0.75) {
            discard;
        }
        uv.x = (uv.x - 0.25) * 2.0;
    }

    // Recorte estereo por olho (T1.1/T1.2 flat, T2.4/T2.5 esfera)
    if (vStereoLayout == 2) {
        // OU: recorta em Y — olho esquerdo = metade superior
        uv.y = uv.y * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 1) {
        // SBS: recorta em X — olho esquerdo = metade esquerda
        uv.x = uv.x * 0.5 + float(eye) * 0.5;
    }
    // stereoLayout == 0: mono, sem recorte

    // CAS (AMD FidelityFX Contrast Adaptive Sharpening simplificado)
    // Forca fixa de sharpness = 0.5 (equivale a m_sharpness=0.5 do GLES)
    const float kSharpness = 0.5;
    vec2 texelSize = 1.0 / vec2(textureSize(videoTexture, 0));
    vec3 c = texture(videoTexture, uv).rgb;
    vec3 n = texture(videoTexture, uv + vec2(0.0,  texelSize.y)).rgb;
    vec3 s = texture(videoTexture, uv + vec2(0.0, -texelSize.y)).rgb;
    vec3 e = texture(videoTexture, uv + vec2( texelSize.x, 0.0)).rgb;
    vec3 w = texture(videoTexture, uv + vec2(-texelSize.x, 0.0)).rgb;
    vec3 mn  = min(c, min(min(n, s), min(e, w)));
    vec3 mx  = max(c, max(max(n, s), max(e, w)));
    vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / mx, 0.0, 1.0));
    float peak = -mix(8.0, 5.0, kSharpness);
    vec3 wgt = amp / peak;
    vec3 sharpened = ((n + s + e + w) * wgt + c) / (1.0 + 4.0 * wgt);

    outColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
}
