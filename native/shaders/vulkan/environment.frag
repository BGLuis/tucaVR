#version 450

layout(set = 0, binding = 0) uniform sampler2D screenAmbientTexture;

layout(set = 0, binding = 1) uniform EnvironmentSceneUBO {
    vec4 cameraPosLocal;    // xyz: eye pos in env local space, w: timeSeconds
    vec4 screenCenterLocal; // xyz: screen center in env local space, w: screenGlowIntensity (0..1)
    vec4 screenRightLocal;  // xyz: screen half-width axis in local space, w: envBrightnessLinear (0..1)
    vec4 screenUpLocal;     // xyz: screen half-height axis in local space, w: ambientHemiStrength
    vec4 screenNormalLocal; // xyz: screen front-facing normal in local space, w: emissiveScale
    vec4 keyLightDirInt;    // xyz: normalized key light direction in local space, w: keyIntensity
    vec4 keyLightColor;     // rgb: linear key color, w: skyAmbientIntensity
    vec4 hemiGroundColor;   // rgb: linear ground ambient color, w: reserved
} ubo;

layout(set = 1, binding = 0) uniform sampler2D baseColorTexture;
layout(set = 1, binding = 1) uniform sampler2D ormTexture;      // R: AO, G: Roughness, B: Metallic (linear)
layout(set = 1, binding = 2) uniform sampler2D emissiveTexture; // sRGB

layout(push_constant) uniform PushConstants {
    mat4 mvp;
    vec4 baseColorFactor; // linear RGBA
    vec4 emissiveFactor;  // linear RGB (pre-multiplied by emissiveStrength), w: metallicFactor
    vec4 materialParams;  // x: roughnessFactor, y: occlusionStrength, z: unlitFlag, w: hasEmissiveTex
} pc;

layout(location = 0) in vec3 vLocalPosition;
layout(location = 1) in vec3 vNormal;
layout(location = 2) in vec4 vColor;
layout(location = 3) in vec2 vUv;

layout(location = 0) out vec4 outColor;

const float kPi = 3.14159265359;

// The downsampled screenAmbientTexture (32x18 UNORM) holds gamma-coded RGB from the video frame.
// Linearize before lighting math so output into the *_SRGB eye swapchain is color-accurate.
vec3 SrgbEotf(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), greaterThan(c, vec3(0.04045)));
}

float DistributionGGX(float NdotH, float alpha) {
    float a2 = alpha * alpha;
    float d = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(kPi * d * d, 1e-5);
}

float GeometrySmithMobile(float NdotV, float NdotL, float roughness) {
    float k = (roughness + 1.0);
    k = (k * k) * 0.125;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 FresnelSchlick(float cosTheta, vec3 F0) {
    float f = 1.0 - clamp(cosTheta, 0.0, 1.0);
    float f2 = f * f;
    return F0 + (1.0 - F0) * (f2 * f2 * f);
}

// Evaluates rectangular video screen diffuse + glossy specular bounce light using a representative-point
// approximation over the 32x18 temporally-smoothed video texture.
vec3 ComputeRectangularScreenBounce(vec3 pos, vec3 N, vec3 V, vec3 diffuseColor, vec3 F0, float roughness) {
    float glowIntensity = ubo.screenCenterLocal.w;
    if (glowIntensity <= 0.001) {
        return vec3(0.0);
    }

    vec3 screenCenter = ubo.screenCenterLocal.xyz;
    vec3 screenN = ubo.screenNormalLocal.xyz;
    vec3 halfR = ubo.screenRightLocal.xyz;
    vec3 halfU = ubo.screenUpLocal.xyz;

    float rLenSq = max(dot(halfR, halfR), 1e-5);
    float uLenSq = max(dot(halfU, halfU), 1e-5);

    vec3 rel = pos - screenCenter;
    float signedFront = dot(rel, screenN);
    // Prevent light bleed behind the screen plane while keeping a soft edge near the bezel.
    float frontMask = smoothstep(-0.08, 0.25, signedFront);
    if (frontMask <= 0.001) {
        return vec3(0.0);
    }

    // 1. Diffuse bounce from the closest point on the screen rectangle + soft center fill.
    float projU = clamp(dot(rel, halfR) / rLenSq, -1.0, 1.0);
    float projV = clamp(dot(rel, halfU) / uLenSq, -1.0, 1.0);
    vec3 closestPt = screenCenter + halfR * projU + halfU * projV;

    vec3 toClosest = closestPt - pos;
    float distClosest = length(toClosest);
    vec3 L = toClosest / max(distClosest, 1e-4);

    float NdotL = max(dot(N, L), 0.0);
    // Screen area factor scales reach naturally between a 2.6m living room TV and a 7.6m IMAX screen.
    float screenDiag = sqrt(rLenSq + uLenSq);
    float normDist = distClosest / max(screenDiag * 0.75, 0.5);
    float diffuseAtten = 1.0 / (1.0 + 0.55 * normDist + 0.45 * normDist * normDist);

    // Map local screen coords to video UV (V is flipped: +up -> UV.y = 0).
    vec2 uvClosest = clamp(vec2(projU * 0.5 + 0.5, 0.5 - projV * 0.5), vec2(0.06), vec2(0.94));
    vec3 localScreenColor = SrgbEotf(texture(screenAmbientTexture, uvClosest).rgb);
    vec3 avgScreenColor = SrgbEotf(texture(screenAmbientTexture, vec2(0.5, 0.5)).rgb);
    vec3 irrColor = mix(avgScreenColor, localScreenColor, 0.65);

    // Direct diffuse + subtle omnidirectional room bounce fill (so ceilings/seats pick up soft movie tint).
    vec3 diffuseBounce = diffuseColor * irrColor * (NdotL * 1.35 + 0.18) * diffuseAtten;

    // 2. Specular representative-point reflection on leather, wood, and metal surfaces.
    vec3 specularBounce = vec3(0.0);
    if (roughness < 0.85) {
        vec3 R = reflect(-V, N);
        float denom = dot(R, screenN);
        // Intersect reflection ray with screen plane; if nearly parallel, fall back to closest point.
        vec3 reflPt = closestPt;
        if (denom < -1e-4) {
            float t = dot(screenCenter - pos, screenN) / denom;
            if (t > 0.0) {
                vec3 hit = pos + R * t;
                vec3 hitRel = hit - screenCenter;
                float hitU = clamp(dot(hitRel, halfR) / rLenSq, -1.0, 1.0);
                float hitV = clamp(dot(hitRel, halfU) / uLenSq, -1.0, 1.0);
                reflPt = screenCenter + halfR * hitU + halfU * hitV;
            }
        }
        vec3 toRefl = reflPt - pos;
        float reflDist = length(toRefl);
        vec3 Lspec = toRefl / max(reflDist, 1e-4);
        float NdotLs = max(dot(N, Lspec), 0.0);
        if (NdotLs > 0.0) {
            vec3 reflRel = reflPt - screenCenter;
            vec2 uvRefl = clamp(
                vec2((dot(reflRel, halfR) / rLenSq) * 0.5 + 0.5, 0.5 - (dot(reflRel, halfU) / uLenSq) * 0.5),
                vec2(0.04), vec2(0.96));
            vec3 reflColor = SrgbEotf(texture(screenAmbientTexture, uvRefl).rgb);

            // Solid-angle roughness widening keeps highlights smooth on large screens.
            float effRough = clamp(roughness + 0.12 * clamp(screenDiag / max(reflDist, 0.5), 0.0, 1.0), 0.12, 1.0);
            float alpha = effRough * effRough;
            vec3 H = normalize(V + Lspec);
            float NdotH = max(dot(N, H), 0.0);
            float NdotV = max(dot(N, V), 0.001);
            float HdotV = max(dot(H, V), 0.0);

            float D = DistributionGGX(NdotH, alpha);
            float G = GeometrySmithMobile(NdotV, NdotLs, effRough);
            vec3 F = FresnelSchlick(HdotV, F0);
            float specAtten = 1.0 / (1.0 + 0.35 * normDist * normDist);

            // Normalize energy to stay pleasant on high-contrast OLED/LCD panels.
            vec3 spec = (D * G * F) / max(4.0 * NdotV * NdotLs, 0.05);
            specularBounce = min(spec, vec3(2.2)) * reflColor * NdotLs * specAtten * 0.65;
        }
    }

    return (diffuseBounce + specularBounce) * (glowIntensity * frontMask);
}

// Soft highlight roll-off above 0.9 so bright specular + screen glow never clip harshly.
vec3 SoftCompressHighlights(vec3 c) {
    return c / (1.0 + max(c - vec3(0.85), vec3(0.0)) * 0.45);
}

void main() {
    vec4 texBase = texture(baseColorTexture, vUv);
    vec3 albedo = vColor.rgb * pc.baseColorFactor.rgb * texBase.rgb;

    // Unlit glTF materials (KHR_materials_unlit) bypass lighting and only respect environment brightness.
    if (pc.materialParams.z > 0.5) {
        vec3 unlitCol = albedo * ubo.screenRightLocal.w;
        outColor = vec4(SoftCompressHighlights(unlitCol), 1.0);
        return;
    }

    vec3 N = normalize(vNormal);
    if (!gl_FrontFacing) {
        N = -N;
    }
    vec3 V = normalize(ubo.cameraPosLocal.xyz - vLocalPosition);
    float NdotV = max(dot(N, V), 0.001);

    vec3 orm = texture(ormTexture, vUv).rgb;
    float ao = mix(1.0, orm.r, clamp(pc.materialParams.y, 0.0, 1.0));
    float roughness = clamp(orm.g * pc.materialParams.x, 0.08, 1.0);
    float metallic = clamp(orm.b * pc.emissiveFactor.w, 0.0, 1.0);

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 diffuseAlbedo = albedo * (1.0 - metallic);

    // 1. Hemisphere ambient + single architectural key light (modulated once by linear envBrightness).
    float envBrightness = ubo.screenRightLocal.w;
    float hemiFactor = N.y * 0.5 + 0.5;
    vec3 skyColor = ubo.keyLightColor.rgb * ubo.keyLightColor.w;
    vec3 groundColor = ubo.hemiGroundColor.rgb;
    vec3 hemiAmbient = mix(groundColor, skyColor, hemiFactor) * ubo.screenUpLocal.w;

    // Lightweight analytic hemisphere reflection along R = reflect(-V, N) so brushed metals,
    // satin walnut, and leather retain rich surface contrast without normal maps or extra samplers.
    vec3 Renv = reflect(-V, N);
    vec3 reflHemi = mix(groundColor, skyColor, Renv.y * 0.5 + 0.5) * ubo.screenUpLocal.w;
    vec3 Fenv = FresnelSchlick(NdotV, F0);
    float envGloss = 1.0 - roughness * 0.72;
    vec3 ambientTerm = (diffuseAlbedo * hemiAmbient + Fenv * reflHemi * envGloss * (0.55 + 0.45 * metallic)) * ao;

    vec3 Lkey = normalize(ubo.keyLightDirInt.xyz);
    float NdotL = max(dot(N, Lkey), 0.0);
    vec3 keyRadiance = ubo.keyLightColor.rgb * ubo.keyLightDirInt.w;

    vec3 keyTerm = vec3(0.0);
    if (NdotL > 0.0) {
        vec3 H = normalize(V + Lkey);
        float NdotH = max(dot(N, H), 0.0);
        float HdotV = max(dot(H, V), 0.0);
        float alpha = roughness * roughness;
        float D = DistributionGGX(NdotH, alpha);
        float G = GeometrySmithMobile(NdotV, NdotL, roughness);
        vec3 F = FresnelSchlick(HdotV, F0);
        vec3 kD = (1.0 - F) * (1.0 - metallic);
        vec3 spec = (D * G * F) / max(4.0 * NdotV * NdotL, 0.05);
        keyTerm = (kD * albedo / kPi + min(spec, vec3(1.5))) * keyRadiance * NdotL * ao;
    }

    vec3 roomLighting = (ambientTerm + keyTerm) * envBrightness;

    // 2. Reactive Rectangular Screen Glow (independent of room dimming; shines in a pitch-black theater).
    vec3 screenBounce = ComputeRectangularScreenBounce(vLocalPosition, N, V, diffuseAlbedo, F0, roughness);

    // 3. Emissive accents (step LEDs, wall sconces, arc lamp, space neons).
    vec3 emissiveTex = (pc.materialParams.w > 0.5) ? texture(emissiveTexture, vUv).rgb : vec3(1.0);
    vec3 emissive = pc.emissiveFactor.rgb * emissiveTex * ubo.screenNormalLocal.w;

    vec3 finalColor = SoftCompressHighlights(roomLighting + screenBounce + emissive);

    // Always opaque: the OpenXR compositor blends the projection layer by alpha.
    outColor = vec4(finalColor, 1.0);
}
