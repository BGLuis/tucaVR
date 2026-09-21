#version 450

// Fragment shader para projeções Cubemap e Equi-Angular Cubemap (EAC).
// Suporta layouts 3x2 padrão, YouTube EAC 3x2, 6x1 horizontal e Cross,
// com proteção anti-seam, correção de distorção angular EAC, estereoscopia
// SBS/OU por olho e upscaling SGSR1 adaptativo.

layout(set = 0, binding = 0) uniform sampler2D videoTexture;

layout(location = 0) in vec2  vTexCoord;
layout(location = 1) flat in int vEye;
layout(location = 2) flat in int vSwapEyes;
layout(location = 3) flat in int vStereoLayout;
layout(location = 4) flat in float vSharpness;
layout(location = 5) flat in int vUpscalingMode;
layout(location = 6) flat in int vCubemapLayout;
layout(location = 7) flat in int vProjectionType;
layout(location = 8) in vec3 vWorldDirection;
layout(location = 9) flat in int vIsHdr;
layout(location = 10) flat in vec2 vTexelSize;
layout(location = 11) flat in float vColorTemperature;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

// T-HDR (Fase 2, primeira versao) — ver o comentario completo em video.frag.
vec3 PqEotf(vec3 pq) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 p = pow(clamp(pq, 0.0, 1.0), vec3(1.0 / m2));
    vec3 num = max(p - c1, 0.0);
    vec3 den = max(c2 - c3 * p, 1e-6);
    return pow(num / den, vec3(1.0 / m1));
}

vec3 TonemapHdrToSdr(vec3 hdrColor) {
    vec3 nits = PqEotf(hdrColor) * 10000.0;
    vec3 scene = nits / 100.0;
    vec3 tonemapped = scene / (1.0 + scene);
    return pow(clamp(tonemapped, 0.0, 1.0), vec3(1.0 / 2.2));
}

// Planckian locus approximation (Kelvin 2700K a 6500K / Night Mode ~3000K)
vec3 ApplyColorTemperature(vec3 color, float tempK) {
    float t = tempK / 100.0;
    vec3 tempColor;
    if (t <= 66.0) {
        tempColor.r = 1.0;
    } else {
        tempColor.r = clamp(1.292 * pow(t - 60.0, -0.1332), 0.0, 1.0);
    }

    if (t <= 66.0) {
        tempColor.g = clamp(0.3901 * log(max(t, 1.0)) - 0.6318, 0.0, 1.0);
    } else {
        tempColor.g = clamp(1.130 * pow(t - 60.0, -0.0755), 0.0, 1.0);
    }

    if (t >= 66.0) {
        tempColor.b = 1.0;
    } else if (t <= 19.0) {
        tempColor.b = 0.0;
    } else {
        tempColor.b = clamp(0.5432 * log(max(t - 10.0, 1.0)) - 1.1962, 0.0, 1.0);
    }

    return color * tempColor;
}

// Kernel adaptativo SGSR1 para upscaling e nitidez de vídeo
vec3 ApplySGSR1(vec2 uv, float sharpness, vec2 texelSize) {
    vec2 dx = vec2(texelSize.x, 0.0);
    vec2 dy = vec2(0.0, texelSize.y);

    vec3 c  = texture(videoTexture, uv).rgb;
    vec3 n  = texture(videoTexture, uv + dy).rgb;
    vec3 s  = texture(videoTexture, uv - dy).rgb;
    vec3 e  = texture(videoTexture, uv + dx).rgb;
    vec3 w  = texture(videoTexture, uv - dx).rgb;
    vec3 ne = texture(videoTexture, uv + dx + dy).rgb;
    vec3 nw = texture(videoTexture, uv - dx + dy).rgb;
    vec3 se = texture(videoTexture, uv + dx - dy).rgb;
    vec3 sw = texture(videoTexture, uv - dx - dy).rgb;

    const vec3 luma = vec3(0.299, 0.587, 0.114);
    float lC  = dot(c, luma);
    float lN  = dot(n, luma);
    float lS  = dot(s, luma);
    float lE  = dot(e, luma);
    float lW  = dot(w, luma);
    float lNE = dot(ne, luma);
    float lNW = dot(nw, luma);
    float lSE = dot(se, luma);
    float lSW = dot(sw, luma);

    float gradH = abs((lNW + 2.0 * lW + lSW) - (lNE + 2.0 * lE + lSE));
    float gradV = abs((lNW + 2.0 * lN + lNE) - (lSW + 2.0 * lS + lSE));
    float gradD1 = abs((2.0 * lNW + lN + lW) - (2.0 * lSE + lS + lE));
    float gradD2 = abs((2.0 * lNE + lN + lE) - (2.0 * lSW + lS + lW));

    vec3 minColor = min(c, min(min(n, s), min(e, w)));
    minColor = min(minColor, min(min(ne, nw), min(se, sw)));
    vec3 maxColor = max(c, max(max(n, s), max(e, w)));
    maxColor = max(maxColor, max(max(ne, nw), max(se, sw)));

    float wH = 1.0 / (1.0 + gradH * 4.0);
    float wV = 1.0 / (1.0 + gradV * 4.0);
    float wDiag = 1.0 / (1.0 + (gradD1 + gradD2) * 2.0);

    vec3 cardinalAvg = (n + s) * wH + (e + w) * wV;
    float cardinalSum = 2.0 * (wH + wV);
    vec3 diagonalAvg = (ne + nw + se + sw) * wDiag;
    float diagonalSum = 4.0 * wDiag;

    vec3 reconstructed = (c * 2.0 + cardinalAvg + diagonalAvg * 0.5) / (2.0 + cardinalSum + diagonalSum * 0.5);

    vec3 amp = sqrt(clamp(min(minColor, 2.0 - maxColor) / max(maxColor, vec3(0.001)), 0.0, 1.0));
    float peak = -mix(8.0, 4.0, clamp(sharpness, 0.0, 1.0));
    vec3 wgt = amp / peak;
    vec3 sharpened = ((n + s + e + w) * wgt + reconstructed) / (1.0 + 4.0 * wgt);

    vec3 finalColor = clamp(sharpened, minColor, maxColor);
    return mix(c, finalColor, clamp(sharpness * 1.5, 0.0, 1.0));
}

// Converte vetor de direção tridimensional (espaço do mundo/cubo) para face e coordenadas UV locais [-1, 1]
// Faces: 0=Right (+X), 1=Left (-X), 2=Top (+Y), 3=Bottom (-Y), 4=Front (-Z), 5=Back (+Z)
void CubemapDirection(vec3 dir, out int face, out vec2 localUV) {
    vec3 absDir = abs(dir);

    if (absDir.x >= absDir.y && absDir.x >= absDir.z) {
        if (dir.x > 0.0) {
            face = 0; // Right (+X)
            localUV = vec2(dir.z, -dir.y) / absDir.x;
        } else {
            face = 1; // Left (-X)
            localUV = vec2(-dir.z, -dir.y) / absDir.x;
        }
    } else if (absDir.y >= absDir.x && absDir.y >= absDir.z) {
        if (dir.y > 0.0) {
            face = 2; // Top (+Y)
            localUV = vec2(dir.x, dir.z) / absDir.y;
        } else {
            face = 3; // Bottom (-Y)
            localUV = vec2(dir.x, -dir.z) / absDir.y;
        }
    } else {
        if (dir.z < 0.0) {
            face = 4; // Front (-Z, frente do usuário)
            localUV = vec2(dir.x, -dir.y) / absDir.z;
        } else {
            face = 5; // Back (+Z, costas do usuário)
            localUV = vec2(-dir.x, -dir.y) / absDir.z;
        }
    }
}

// Transformação Equi-Angular (EAC): distribui a densidade de pixels angularmente uniforme
vec2 EacTransform(vec2 uv) {
    return vec2(
        (2.0 / PI) * atan(2.0 * uv.x - 1.0) + 0.5,
        (2.0 / PI) * atan(2.0 * uv.y - 1.0) + 0.5
    );
}

// Aplica rotação de 90° em passos horários: 0=0°, 1=90°, 2=180°, 3=270°
vec2 RotateFaceUV(vec2 uv, int rotation) {
    if (rotation == 1) {
        return vec2(1.0 - uv.y, uv.x);
    } else if (rotation == 2) {
        return vec2(1.0 - uv.x, 1.0 - uv.y);
    } else if (rotation == 3) {
        return vec2(uv.y, 1.0 - uv.x);
    }
    return uv;
}

// Retorna o retângulo de recorte [uMin, vMin, uMax, vMax] e a rotação no atlas 2D
vec4 GetFaceRectAndRotation(int face, int cbLayout, out int rotation) {
    rotation = 0;

    // Layout 0: Standard Cubemap 3x2 (Row 0: R, L, U; Row 1: D, F, B)
    if (cbLayout == 0) {
        const float w = 1.0 / 3.0;
        const float h = 0.5;
        // Face 0: Right (col 0, row 0)
        // Face 1: Left (col 1, row 0)
        // Face 2: Top / Up (col 2, row 0)
        // Face 3: Bottom / Down (col 0, row 1)
        // Face 4: Front (col 1, row 1)
        // Face 5: Back (col 2, row 1)
        if (face == 0) return vec4(0.0, 0.0, w, h);
        if (face == 1) return vec4(w, 0.0, 2.0 * w, h);
        if (face == 2) return vec4(2.0 * w, 0.0, 1.0, h);
        if (face == 3) return vec4(0.0, h, w, 1.0);
        if (face == 4) return vec4(w, h, 2.0 * w, 1.0);
        return vec4(2.0 * w, h, 1.0, 1.0);
    }

    // Layout 1: Cubemap 6x1 (faixa horizontal de 6 faces: R, L, U, D, F, B)
    if (cbLayout == 1) {
        const float w = 1.0 / 6.0;
        return vec4(float(face) * w, 0.0, float(face + 1) * w, 1.0);
    }

    // Layout 2: YouTube EAC 3x2 (Ordem lfrdbu: Left, Front, Right, Down, Back, Up)
    // Com rotações padrão Google/FFmpeg v360: in_frot = '000313'
    if (cbLayout == 2) {
        const float w = 1.0 / 3.0;
        const float h = 0.5;
        // Col 0, Row 0: Left (Face 1), rotação 0
        if (face == 1) { rotation = 0; return vec4(0.0, 0.0, w, h); }
        // Col 1, Row 0: Front (Face 4), rotação 0
        if (face == 4) { rotation = 0; return vec4(w, 0.0, 2.0 * w, h); }
        // Col 2, Row 0: Right (Face 0), rotação 0
        if (face == 0) { rotation = 0; return vec4(2.0 * w, 0.0, 1.0, h); }
        // Col 0, Row 1: Down / Bottom (Face 3), rotação 3 (270° CW)
        if (face == 3) { rotation = 3; return vec4(0.0, h, w, 1.0); }
        // Col 1, Row 1: Back (Face 5), rotação 1 (90° CW)
        if (face == 5) { rotation = 1; return vec4(w, h, 2.0 * w, 1.0); }
        // Col 2, Row 1: Up / Top (Face 2), rotação 3 (270° CW)
        rotation = 3;
        return vec4(2.0 * w, h, 1.0, 1.0);
    }

    // Layout 3: Cross horizontal (4 colunas, 3 linhas)
    const float cw = 0.25;
    const float ch = 1.0 / 3.0;
    if (face == 2) return vec4(cw, 0.0, 2.0 * cw, ch);        // Top
    if (face == 1) return vec4(0.0, ch, cw, 2.0 * ch);        // Left
    if (face == 4) return vec4(cw, ch, 2.0 * cw, 2.0 * ch);   // Front
    if (face == 0) return vec4(2.0 * cw, ch, 3.0 * cw, 2.0 * ch); // Right
    if (face == 5) return vec4(3.0 * cw, ch, 1.0, 2.0 * ch);  // Back
    return vec4(cw, 2.0 * ch, 2.0 * cw, 1.0);                 // Bottom
}

void main() {
    int eye = vEye;
    if (vSwapEyes != 0) {
        eye = 1 - eye;
    }

    vec3 dir = normalize(vWorldDirection);

    int face;
    vec2 localUV;
    CubemapDirection(dir, face, localUV);

    // Mapeia coordenadas locais de [-1, 1] para [0, 1]
    vec2 faceUV = localUV * 0.5 + 0.5;

    // Se for EAC, aplica transformação equi-angular
    if (vProjectionType == 1) {
        faceUV = EacTransform(faceUV);
    }

    // Clamp anti-costuras (anti-seam) para evitar sangramento do filtro bilinear nas bordas da face
    faceUV = clamp(faceUV, 0.002, 0.998);

    int faceRotation = 0;
    vec4 faceRect = GetFaceRectAndRotation(face, vCubemapLayout, faceRotation);
    faceUV = RotateFaceUV(faceUV, faceRotation);

    // Mapeia faceUV para coordenadas da textura atlas
    vec2 texUV = mix(faceRect.xy, faceRect.zw, faceUV);

    // Divisão estereoscópica por olho (T6.5)
    if (vStereoLayout == 1) {
        // SBS: olho esquerdo = metade esquerda (0.0..0.5), olho direito = metade direita (0.5..1.0)
        texUV.x = texUV.x * 0.5 + float(eye) * 0.5;
    } else if (vStereoLayout == 2) {
        // OU: olho esquerdo = metade superior (0.0..0.5), olho direito = metade inferior (0.5..1.0)
        texUV.y = texUV.y * 0.5 + float(eye) * 0.5;
    }

    vec3 color = (vSharpness <= 0.01 || vTexelSize.x <= 0.0 || vTexelSize.y <= 0.0)
        ? texture(videoTexture, texUV).rgb
        : ApplySGSR1(texUV, vSharpness, vTexelSize);
    if (vIsHdr != 0) {
        color = TonemapHdrToSdr(color);
    }
    if (vColorTemperature < 0.0) {
        // Night Mode: reduz luz azul (~3000K)
        color = ApplyColorTemperature(color, 3000.0);
    } else if (abs(vColorTemperature - 6500.0) > 1.0 && vColorTemperature > 1000.0) {
        color = ApplyColorTemperature(color, vColorTemperature);
    }
    outColor = vec4(color, 1.0);
}
