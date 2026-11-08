#pragma once

#include "screen_layout.h"
#include "vk_math.h"

#include <algorithm>
#include <cmath>

namespace vrplayer {

// Raycast hit-testing against the inner/front surface of a vertical cylinder arc.
// transformNoScale: rigid transformation matrix for the cylinder apex (center of visible arc).
// radius: curvature radius in meters.
// centralAngle: angular span of the cylinder arc in radians.
// aspectRatio: width / height ratio of the panel texture.
// outU, outV: normalized UV texture coordinates [0, 1] at the intersection point.
// rayOrigin, rayDir: ray origin and normalized direction in world coordinates.
// Returns ray distance t > 0, or -1.0f on miss.
inline float rayHitsCylinder(const Mat4 &transformNoScale, float radius, float centralAngle, float aspectRatio,
                             float &outU, float &outV, const XrVector3f &rayOrigin, const XrVector3f &rayDir) {
    if (radius <= 1e-4f || centralAngle <= 1e-4f || aspectRatio <= 1e-4f) {
        return -1.0f;
    }

    Mat4 inv = Mat4RigidInverse(transformNoScale);
    XrVector3f oLoc = {
        inv.m[0] * rayOrigin.x + inv.m[4] * rayOrigin.y + inv.m[8] * rayOrigin.z + inv.m[12],
        inv.m[1] * rayOrigin.x + inv.m[5] * rayOrigin.y + inv.m[9] * rayOrigin.z + inv.m[13],
        inv.m[2] * rayOrigin.x + inv.m[6] * rayOrigin.y + inv.m[10] * rayOrigin.z + inv.m[14],
    };
    XrVector3f dLoc = {
        inv.m[0] * rayDir.x + inv.m[4] * rayDir.y + inv.m[8] * rayDir.z,
        inv.m[1] * rayDir.x + inv.m[5] * rayDir.y + inv.m[9] * rayDir.z,
        inv.m[2] * rayDir.x + inv.m[6] * rayDir.y + inv.m[10] * rayDir.z,
    };

    float a = dLoc.x * dLoc.x + dLoc.z * dLoc.z;
    if (a <= 1e-6f) {
        return -1.0f;
    }

    float b = 2.0f * (oLoc.x * dLoc.x + (oLoc.z - radius) * dLoc.z);
    float c = oLoc.x * oLoc.x + (oLoc.z - radius) * (oLoc.z - radius) - radius * radius;

    float disc = b * b - 4.0f * a * c;
    if (disc < 0.0f) {
        return -1.0f;
    }

    float sqrtDisc = sqrtf(disc);
    float tCandidates[2] = {(-b - sqrtDisc) / (2.0f * a), (-b + sqrtDisc) / (2.0f * a)};

    float halfAngle = centralAngle * 0.5f;
    float arcLength = radius * centralAngle;
    float height = arcLength / aspectRatio;
    float halfHeight = height * 0.5f;

    for (float t : tCandidates) {
        if (t <= 0.0f) {
            continue;
        }

        float hitX = oLoc.x + t * dLoc.x;
        float hitY = oLoc.y + t * dLoc.y;
        float hitZ = oLoc.z + t * dLoc.z;

        float phi = atan2f(hitX, radius - hitZ);
        if (fabsf(phi) > halfAngle) {
            continue;
        }

        if (fabsf(hitY) > halfHeight) {
            continue;
        }

        outU = std::max(0.0f, std::min(1.0f, (phi / centralAngle) + 0.5f));
        outV = std::max(0.0f, std::min(1.0f, 0.5f - (hitY / height)));

        return t;
    }

    return -1.0f;
}

// Raycast hit-testing against a flat quad panel.
// transformNoScale: rigid transformation matrix for the quad center.
// normal: plane normal vector in world coordinates (points towards front).
// center: quad center in world coordinates.
// scaleX, scaleY: width and height in meters.
// outU, outV: normalized UV texture coordinates [0, 1] at intersection.
inline float rayHitsQuad(const Mat4 &transformNoScale, const XrVector3f &normal, const XrVector3f &center, float scaleX,
                         float scaleY, float &outU, float &outV, const XrVector3f &rayOrigin,
                         const XrVector3f &rayDir) {
    float dotDir = normal.x * rayDir.x + normal.y * rayDir.y + normal.z * rayDir.z;
    if (std::fabs(dotDir) <= 0.001f) {
        return -1.0f;
    }
    XrVector3f toCenter = {center.x - rayOrigin.x, center.y - rayOrigin.y, center.z - rayOrigin.z};
    float dotDist = toCenter.x * normal.x + toCenter.y * normal.y + toCenter.z * normal.z;
    float t = dotDist / dotDir;
    if (t <= 0.0f) {
        return -1.0f;
    }
    XrVector3f hitWorld = {rayOrigin.x + rayDir.x * t, rayOrigin.y + rayDir.y * t, rayOrigin.z + rayDir.z * t};
    Mat4 inv = Mat4RigidInverse(transformNoScale);
    XrVector3f hitLoc = {
        inv.m[0] * hitWorld.x + inv.m[4] * hitWorld.y + inv.m[8] * hitWorld.z + inv.m[12],
        inv.m[1] * hitWorld.x + inv.m[5] * hitWorld.y + inv.m[9] * hitWorld.z + inv.m[13],
        inv.m[2] * hitWorld.x + inv.m[6] * hitWorld.y + inv.m[10] * hitWorld.z + inv.m[14],
    };
    float halfX = scaleX * 0.5f;
    float halfY = scaleY * 0.5f;
    if (std::fabs(hitLoc.x) > halfX || std::fabs(hitLoc.y) > halfY) {
        return -1.0f;
    }
    outU = (hitLoc.x / scaleX) + 0.5f;
    outV = 0.5f - (hitLoc.y / scaleY);
    return t;
}

// Unified raycast hit-testing for virtual screen supporting both flat quad and cylindrical curvature.
inline float rayHitsScreen(const Mat4 &transformNoScale, const XrVector3f &normal, const XrVector3f &center,
                           float scaleX, float scaleY, float curvature, float &outU, float &outV,
                           const XrVector3f &rayOrigin, const XrVector3f &rayDir) {
    if (curvature > 1e-4f) {
        const auto params = ComputeCurvedScreenParams(curvature, scaleX, scaleY);
        const float aspect = (scaleY > 1e-4f) ? (scaleX / scaleY) : (16.0f / 9.0f);
        return rayHitsCylinder(transformNoScale, params.radius, params.centralAngle, aspect, outU, outV, rayOrigin,
                               rayDir);
    }
    return rayHitsQuad(transformNoScale, normal, center, scaleX, scaleY, outU, outV, rayOrigin, rayDir);
}

} // namespace vrplayer

using vrplayer::rayHitsCylinder;
using vrplayer::rayHitsQuad;
using vrplayer::rayHitsScreen;
