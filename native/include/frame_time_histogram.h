#pragma once

namespace vrplayer {

constexpr int kFrameTimeHistogramBucketCount = 8;

struct FrameTimeHistogramEdges {
    float ms[kFrameTimeHistogramBucketCount - 1];
};

// Bucket upper edges for the render frame time histogram (G2 / hist_bucket_0..7). The first three
// sit halfway between display periods, so buckets 0-2 read "on time", "1 vsync missed" and
// "2 vsyncs missed" at whatever rate the runtime runs (72/80/90/120 Hz) despite timing jitter.
// The remaining edges are fixed in ms; the last one is the freeze threshold. Below ~70 Hz the
// period-based edges would pass 50 ms, so every edge is kept above the previous one.
inline FrameTimeHistogramEdges MakeFrameTimeHistogramEdges(float refreshHz) {
    const float periodMs = 1000.0f / (refreshHz > 0.0f ? refreshHz : 90.0f);
    FrameTimeHistogramEdges edges = {
        {1.5f * periodMs, 2.5f * periodMs, 3.5f * periodMs, 50.0f, 100.0f, 150.0f, 250.0f}};
    for (int i = 1; i < kFrameTimeHistogramBucketCount - 1; ++i) {
        if (edges.ms[i] <= edges.ms[i - 1])
            edges.ms[i] = edges.ms[i - 1] + 1.0f;
    }
    return edges;
}

// Index of the first bucket whose edge frameMs is below; the last bucket takes everything above.
inline int FrameTimeHistogramBucket(float frameMs, float refreshHz) {
    const FrameTimeHistogramEdges edges = MakeFrameTimeHistogramEdges(refreshHz);
    for (int i = 0; i < kFrameTimeHistogramBucketCount - 1; ++i) {
        if (frameMs < edges.ms[i])
            return i;
    }
    return kFrameTimeHistogramBucketCount - 1;
}

} // namespace vrplayer
