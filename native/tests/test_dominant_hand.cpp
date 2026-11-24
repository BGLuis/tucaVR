#include "dominant_hand.h"

#include <cassert>
#include <iostream>

using namespace vrplayer;

static void TestSanitizeDominantHandMode() {
    assert(SanitizeDominantHandMode(0) == DominantHandMode::Auto);
    assert(SanitizeDominantHandMode(1) == DominantHandMode::Right);
    assert(SanitizeDominantHandMode(2) == DominantHandMode::Left);
    assert(SanitizeDominantHandMode(-1) == DominantHandMode::Auto);
    assert(SanitizeDominantHandMode(99) == DominantHandMode::Auto);

    std::cout << "[PASS] TestSanitizeDominantHandMode\n";
}

static bool ExpectedLeftPointer(DominantHandMode mode, bool leftAvailable, bool rightAvailable, bool leftActive) {
    if (!leftAvailable) {
        return false;
    }
    switch (mode) {
    case DominantHandMode::Right:
        return !rightAvailable;
    case DominantHandMode::Left:
        return true;
    case DominantHandMode::Auto:
    default:
        return !rightAvailable || leftActive;
    }
}

static void TestShouldUseLeftPointerExhaustive() {
    const DominantHandMode modes[] = {
        DominantHandMode::Auto,
        DominantHandMode::Right,
        DominantHandMode::Left,
    };

    for (DominantHandMode mode : modes) {
        for (int leftAvail = 0; leftAvail <= 1; ++leftAvail) {
            for (int rightAvail = 0; rightAvail <= 1; ++rightAvail) {
                for (int leftAct = 0; leftAct <= 1; ++leftAct) {
                    const bool actual = ShouldUseLeftPointer(mode, leftAvail != 0, rightAvail != 0, leftAct != 0);
                    const bool expected = ExpectedLeftPointer(mode, leftAvail != 0, rightAvail != 0, leftAct != 0);
                    assert(actual == expected);
                }
            }
        }
    }

    // Explicit sanity checks for key scenarios
    assert(!ShouldUseLeftPointer(DominantHandMode::Auto, true, true, false));
    assert(ShouldUseLeftPointer(DominantHandMode::Auto, true, true, true));
    assert(ShouldUseLeftPointer(DominantHandMode::Auto, true, false, false));

    assert(!ShouldUseLeftPointer(DominantHandMode::Right, true, true, true));
    assert(ShouldUseLeftPointer(DominantHandMode::Right, true, false, false));

    assert(ShouldUseLeftPointer(DominantHandMode::Left, true, true, false));
    assert(!ShouldUseLeftPointer(DominantHandMode::Left, false, true, true));

    std::cout << "[PASS] TestShouldUseLeftPointerExhaustive\n";
}

int main() {
    TestSanitizeDominantHandMode();
    TestShouldUseLeftPointerExhaustive();
    return 0;
}
