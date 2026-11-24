#pragma once

#include <cstdint>

namespace vrplayer {

/// Pointer hand selection policy configured by the user.
enum class DominantHandMode : int32_t {
    Auto = 0,
    Right = 1,
    Left = 2,
};

/// Sanitizes a raw integer mode coming from JNI or persistent storage, defaulting unknown values to Auto.
inline DominantHandMode SanitizeDominantHandMode(int32_t raw) {
    switch (raw) {
    case 1:
        return DominantHandMode::Right;
    case 2:
        return DominantHandMode::Left;
    default:
        return DominantHandMode::Auto;
    }
}

/// Selects whether the left controller/hand should drive the UI pointer ray.
inline bool ShouldUseLeftPointer(DominantHandMode mode, bool leftAvailable, bool rightAvailable, bool leftActive) {
    switch (mode) {
    case DominantHandMode::Right:
        // Prefer right when tracked; fall back to left only when right is unavailable.
        return !rightAvailable && leftAvailable;
    case DominantHandMode::Left:
        // Prefer left when tracked; fall back to right only when left is unavailable.
        return leftAvailable;
    case DominantHandMode::Auto:
    default:
        return leftAvailable && (!rightAvailable || leftActive);
    }
}

} // namespace vrplayer
