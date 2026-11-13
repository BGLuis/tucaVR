#include "frame_time_histogram.h"

#include <cassert>
#include <iostream>

using namespace vrplayer;

static void TestOnTimeFramesAt90HzLandInFirstBucket() {
    assert(FrameTimeHistogramBucket(11.0f, 90.0f) == 0);
    assert(FrameTimeHistogramBucket(1000.0f / 90.0f, 90.0f) == 0);
    assert(FrameTimeHistogramBucket(11.3f, 90.0f) == 0);
    std::cout << "[PASS] TestOnTimeFramesAt90HzLandInFirstBucket\n";
}

static void TestMissedVsyncsCountPerRefreshRate() {
    assert(FrameTimeHistogramBucket(2.0f * 1000.0f / 90.0f, 90.0f) == 1);
    assert(FrameTimeHistogramBucket(3.0f * 1000.0f / 90.0f, 90.0f) == 2);
    assert(FrameTimeHistogramBucket(1000.0f / 72.0f, 72.0f) == 0);
    assert(FrameTimeHistogramBucket(2.0f * 1000.0f / 72.0f, 72.0f) == 1);
    assert(FrameTimeHistogramBucket(1000.0f / 120.0f, 120.0f) == 0);
    assert(FrameTimeHistogramBucket(2.0f * 1000.0f / 120.0f, 120.0f) == 1);
    std::cout << "[PASS] TestMissedVsyncsCountPerRefreshRate\n";
}

static void TestFreezeLandsInLastBucketAtAnyRate() {
    for (float hz : {60.0f, 72.0f, 80.0f, 90.0f, 120.0f}) {
        assert(FrameTimeHistogramBucket(300.0f, hz) == kFrameTimeHistogramBucketCount - 1);
    }
    std::cout << "[PASS] TestFreezeLandsInLastBucketAtAnyRate\n";
}

static void TestEdgesStrictlyIncrease() {
    for (float hz : {0.0f, 60.0f, 72.0f, 80.0f, 90.0f, 120.0f}) {
        FrameTimeHistogramEdges edges = MakeFrameTimeHistogramEdges(hz);
        for (int i = 1; i < kFrameTimeHistogramBucketCount - 1; ++i) {
            assert(edges.ms[i] > edges.ms[i - 1]);
        }
    }
    std::cout << "[PASS] TestEdgesStrictlyIncrease\n";
}

static void TestUnknownRateFallsBackTo90Hz() {
    assert(FrameTimeHistogramBucket(1000.0f / 90.0f, 0.0f) == 0);
    assert(FrameTimeHistogramBucket(2.0f * 1000.0f / 90.0f, 0.0f) == 1);
    std::cout << "[PASS] TestUnknownRateFallsBackTo90Hz\n";
}

int main() {
    TestOnTimeFramesAt90HzLandInFirstBucket();
    TestMissedVsyncsCountPerRefreshRate();
    TestFreezeLandsInLastBucketAtAnyRate();
    TestEdgesStrictlyIncrease();
    TestUnknownRateFallsBackTo90Hz();
    return 0;
}
