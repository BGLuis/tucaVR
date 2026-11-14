#version 450
#extension GL_GOOGLE_include_directive : require

// Fragment shader para projeções Cubemap e Equi-Angular Cubemap (EAC).
// Suporta layouts 3x2 padrão, YouTube EAC 3x2, 6x1 horizontal e Cross,
// com proteção anti-seam, correção de distorção angular EAC, estereoscopia
// SBS/OU por olho e upscaling SGSR1 adaptativo.

#include "video_common.glsl"

layout(location = 0) in vec2  vTexCoord;
layout(location = 1) flat in int vEye;
layout(location = 2) flat in int vSwapEyes;
layout(location = 3) flat in int vStereoLayout;
layout(location = 4) flat in float vSharpness;
layout(location = 5) flat in float vHdrLuminance;
layout(location = 6) flat in int vCubemapLayout;
layout(location = 7) flat in int vProjectionType;
layout(location = 8) in vec3 vWorldDirection;
layout(location = 9) flat in int vIsHdr;
layout(location = 10) flat in vec2 vTexelSize;
layout(location = 11) flat in float vColorTemperature;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

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
        color = TonemapHdrToSdr(color, vIsHdr, vHdrLuminance);
    }
    if (vColorTemperature < 0.0) {
        // Night Mode: reduce blue light (~3000K)
        color = ApplyColorTemperature(color, 3000.0);
    } else if (abs(vColorTemperature - 6500.0) > 1.0 && vColorTemperature > 1000.0) {
        color = ApplyColorTemperature(color, vColorTemperature);
    }
    outColor = vec4(SrgbEotf(color), 1.0);
}
