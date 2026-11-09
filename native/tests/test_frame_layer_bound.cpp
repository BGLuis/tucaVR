#include "video_layer.h"

#include <cassert>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>

static size_t CountOccurrences(const std::string &text, const std::string &needle) {
    size_t count = 0;
    for (size_t pos = text.find(needle); pos != std::string::npos; pos = text.find(needle, pos + needle.size())) {
        ++count;
    }
    return count;
}

// The frame builder writes into a fixed array. Every write must go through the one bounds-checked helper, and the
// array must be sized with the worst case that video_layer.h documents.
static void TestFrameLayerArrayStaysWithinItsBound() {
    static_assert(vrplayer::kFrameLayerCapacity == 10, "update the layer budget when a layer is added");

    std::ifstream file("native/src/vr_player_app_vulkan.cpp");
    assert(file.good() && "run from the repository root");
    const std::string source((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());

    assert(CountOccurrences(source, "layers[vrplayer::kFrameLayerCapacity]") == 1);
    assert(CountOccurrences(source, "layers[layerCount++]") == 1);
    assert(CountOccurrences(source, "layerCount < vrplayer::kFrameLayerCapacity") == 1);
    printf("[PASS] TestFrameLayerArrayStaysWithinItsBound\n");
}

int main() {
    printf("--- Executing source guard for the xrEndFrame layer array ---\n");
    TestFrameLayerArrayStaysWithinItsBound();
    printf("--- Layer array guard passed! ---\n");
    return 0;
}
