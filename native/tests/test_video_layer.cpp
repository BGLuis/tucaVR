#include "video_layer.h"

#include <cassert>
#include <cstdio>

using vrplayer::ComputeEyeCrop;
using vrplayer::ComputeVideoLayerExtent;
using vrplayer::DirectVideoLayerInputs;
using vrplayer::ShouldUseDirectVideoLayer;
using vrplayer::VideoLayerLayout;

static DirectVideoLayerInputs EligibleInputs() {
    DirectVideoLayerInputs in;
    in.featureEnabled = true;
    in.screenMode = ScreenMode::Flat2D;
    in.hasVideoFrame = true;
    in.swapchainsReady = true;
    return in;
}

static void TestEquirectNeedsTheExtensionAndFullSizeContent() {
    DirectVideoLayerInputs in = EligibleInputs();
    in.screenMode = ScreenMode::Vr180SBS;
    in.equirectSupported = true;
    in.contentFitsLayer = true;
    assert(ShouldUseDirectVideoLayer(in));

    in.equirectSupported = false;
    assert(!ShouldUseDirectVideoLayer(in));

    in.equirectSupported = true;
    in.contentFitsLayer = false;
    assert(!ShouldUseDirectVideoLayer(in));
    assert(!vrplayer::IsVideoLayerCandidate(in));

    in.screenMode = ScreenMode::Flat2D;
    assert(ShouldUseDirectVideoLayer(in));
    printf("[PASS] TestEquirectNeedsTheExtensionAndFullSizeContent\n");
}

static void TestSphereFitsOnlyWithoutDownscale() {
    using vrplayer::FitsVideoLayerWithoutDownscale;
    assert(FitsVideoLayerWithoutDownscale(7680, 3840, VideoLayerLayout::SideBySide, 4096, 4096));
    assert(FitsVideoLayerWithoutDownscale(3840, 7680, VideoLayerLayout::OverUnder, 4096, 4096));
    assert(!FitsVideoLayerWithoutDownscale(7680, 3840, VideoLayerLayout::Mono, 4096, 4096));
    assert(!FitsVideoLayerWithoutDownscale(7680, 3840, VideoLayerLayout::SideBySide, 2048, 4096));
    assert(!FitsVideoLayerWithoutDownscale(0, 3840, VideoLayerLayout::Mono, 4096, 4096));
    printf("[PASS] TestSphereFitsOnlyWithoutDownscale\n");
}

static void TestEquirectAnglesCoverTheRightPortionOfTheSphere() {
    constexpr float kPi = 3.14159265358979323846f;
    const auto hemisphere = vrplayer::ComputeEquirectLayerAngles(ScreenMode::Vr180SBS);
    assert(hemisphere.centralHorizontal == kPi);
    assert(vrplayer::ComputeEquirectLayerAngles(ScreenMode::Sphere180).centralHorizontal == kPi);
    const auto full = vrplayer::ComputeEquirectLayerAngles(ScreenMode::Sphere360OU);
    assert(full.centralHorizontal == 2.0f * kPi);
    assert(full.upperVertical == kPi * 0.5f);
    assert(full.lowerVertical == -kPi * 0.5f);
    printf("[PASS] TestEquirectAnglesCoverTheRightPortionOfTheSphere\n");
}

static void TestDirectPathCoversFlatAndEquirectModes() {
    for (uint32_t raw = 0; raw <= static_cast<uint32_t>(ScreenMode::Fisheye190SBS); ++raw) {
        DirectVideoLayerInputs in = EligibleInputs();
        in.screenMode = static_cast<ScreenMode>(raw);
        in.equirectSupported = true;
        in.contentFitsLayer = true;
        const bool expected = in.screenMode == ScreenMode::Flat2D || in.screenMode == ScreenMode::SBS ||
                              in.screenMode == ScreenMode::SBSHalf || in.screenMode == ScreenMode::OU ||
                              in.screenMode == ScreenMode::OUHalf || in.screenMode == ScreenMode::Sphere360 ||
                              in.screenMode == ScreenMode::Sphere180 || in.screenMode == ScreenMode::Sphere360SBS ||
                              in.screenMode == ScreenMode::Sphere360OU || in.screenMode == ScreenMode::Vr180SBS;
        assert(ShouldUseDirectVideoLayer(in) == expected);
    }
    printf("[PASS] TestDirectPathCoversFlatAndEquirectModes\n");
}

static void TestEveryDisqualifierFallsBackToTheEyeBuffer() {
    assert(ShouldUseDirectVideoLayer(EligibleInputs()));

    DirectVideoLayerInputs in = EligibleInputs();
    in.featureEnabled = false;
    assert(!ShouldUseDirectVideoLayer(in));

    in = EligibleInputs();
    in.hasVideoFrame = false;
    assert(!ShouldUseDirectVideoLayer(in));

    in = EligibleInputs();
    in.photoActive = true;
    assert(!ShouldUseDirectVideoLayer(in));

    in = EligibleInputs();
    in.chromaKeyActive = true;
    assert(!ShouldUseDirectVideoLayer(in));

    in = EligibleInputs();
    in.screenMode = ScreenMode::SBS;
    in.packedAlphaActive = true;
    assert(!ShouldUseDirectVideoLayer(in));

    in = EligibleInputs();
    in.swapchainsReady = false;
    assert(!ShouldUseDirectVideoLayer(in));
    printf("[PASS] TestEveryDisqualifierFallsBackToTheEyeBuffer\n");
}

static void TestCurvedScreenNeedsTheCylinderExtension() {
    DirectVideoLayerInputs in = EligibleInputs();
    in.curvedScreen = true;
    in.cylinderSupported = false;
    assert(!ShouldUseDirectVideoLayer(in));
    in.cylinderSupported = true;
    assert(ShouldUseDirectVideoLayer(in));

    in.curvedScreen = false;
    in.cylinderSupported = false;
    assert(ShouldUseDirectVideoLayer(in));
    printf("[PASS] TestCurvedScreenNeedsTheCylinderExtension\n");
}

static void TestLayoutFollowsTheScreenMode() {
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Flat2D) == VideoLayerLayout::Mono);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::SBS) == VideoLayerLayout::SideBySide);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::SBSHalf) == VideoLayerLayout::SideBySide);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::OU) == VideoLayerLayout::OverUnder);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::OUHalf) == VideoLayerLayout::OverUnder);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Sphere360) == VideoLayerLayout::Mono);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Sphere180) == VideoLayerLayout::Mono);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Sphere360SBS) == VideoLayerLayout::SideBySide);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Vr180SBS) == VideoLayerLayout::SideBySide);
    assert(vrplayer::VideoLayerLayoutForMode(ScreenMode::Sphere360OU) == VideoLayerLayout::OverUnder);

    assert(!vrplayer::UsesPerEyeVideoLayers(VideoLayerLayout::Mono, false));
    assert(vrplayer::UsesPerEyeVideoLayers(VideoLayerLayout::SideBySide, false));
    assert(vrplayer::UsesPerEyeVideoLayers(VideoLayerLayout::OverUnder, false));
    assert(!vrplayer::UsesPerEyeVideoLayers(VideoLayerLayout::SideBySide, true));
    printf("[PASS] TestLayoutFollowsTheScreenMode\n");
}

static void TestExtentFollowsPerEyeContent() {
    constexpr uint32_t kLimit = 8192;

    auto mono4k = ComputeVideoLayerExtent(3840, 2160, VideoLayerLayout::Mono, kLimit, kLimit);
    assert(mono4k.width == 3840 && mono4k.height == 2160);

    auto fullSbs = ComputeVideoLayerExtent(7680, 2160, VideoLayerLayout::SideBySide, kLimit, kLimit);
    assert(fullSbs.width == 3840 && fullSbs.height == 2160);

    auto halfSbs = ComputeVideoLayerExtent(1920, 1080, VideoLayerLayout::SideBySide, kLimit, kLimit);
    assert(halfSbs.width == 960 && halfSbs.height == 1080);

    auto ou = ComputeVideoLayerExtent(3840, 2160, VideoLayerLayout::OverUnder, kLimit, kLimit);
    assert(ou.width == 3840 && ou.height == 1080);

    auto none = ComputeVideoLayerExtent(0, 1080, VideoLayerLayout::Mono, kLimit, kLimit);
    assert(none.width == 0 && none.height == 0);
    printf("[PASS] TestExtentFollowsPerEyeContent\n");
}

static void TestOversizeContentIsClampedKeepingAspect() {
    // 8K mono: capped by the 4096 axis limit, 16:9 preserved.
    auto mono8k = ComputeVideoLayerExtent(7680, 4320, VideoLayerLayout::Mono, 16384, 16384);
    assert(mono8k.width == 4096 && mono8k.height == 2304);

    // 8K full SBS: each eye is 7680x4320 and is capped the same way.
    auto sbs8k = ComputeVideoLayerExtent(15360, 4320, VideoLayerLayout::SideBySide, 16384, 16384);
    assert(sbs8k.width == 4096 && sbs8k.height == 2304);

    // A runtime limit lower than 4096 wins, on the height axis here.
    auto smallHeight = ComputeVideoLayerExtent(3840, 2160, VideoLayerLayout::Mono, 8192, 1080);
    assert(smallHeight.width == 1920 && smallHeight.height == 1080);

    // A runtime limit lower than 4096 on the width axis.
    auto smallWidth = ComputeVideoLayerExtent(3840, 2160, VideoLayerLayout::Mono, 2048, 8192);
    assert(smallWidth.width == 2048 && smallWidth.height == 1152);

    // Tall content is limited by its height.
    auto portrait = ComputeVideoLayerExtent(2160, 7680, VideoLayerLayout::Mono, 16384, 16384);
    assert(portrait.height == 4096 && portrait.width == 1152);

    // An unreported runtime limit still honours the 4096 cap.
    auto unreported = ComputeVideoLayerExtent(7680, 4320, VideoLayerLayout::Mono, 0, 0);
    assert(unreported.width == 4096 && unreported.height == 2304);

    // Never degenerates to an empty swapchain.
    auto sliver = ComputeVideoLayerExtent(16384, 2, VideoLayerLayout::Mono, 16384, 16384);
    assert(sliver.width == 4096 && sliver.height >= 1);
    printf("[PASS] TestOversizeContentIsClampedKeepingAspect\n");
}

static void TestEyeCropRectangles() {
    auto mono = ComputeEyeCrop(VideoLayerLayout::Mono, 1, false);
    assert(mono.offsetU == 0.0f && mono.offsetV == 0.0f && mono.scaleU == 1.0f && mono.scaleV == 1.0f);

    auto sbsLeft = ComputeEyeCrop(VideoLayerLayout::SideBySide, 0, false);
    assert(sbsLeft.offsetU == 0.0f && sbsLeft.scaleU == 0.5f && sbsLeft.offsetV == 0.0f && sbsLeft.scaleV == 1.0f);
    auto sbsRight = ComputeEyeCrop(VideoLayerLayout::SideBySide, 1, false);
    assert(sbsRight.offsetU == 0.5f && sbsRight.scaleU == 0.5f && sbsRight.scaleV == 1.0f);

    auto ouLeft = ComputeEyeCrop(VideoLayerLayout::OverUnder, 0, false);
    assert(ouLeft.offsetV == 0.0f && ouLeft.scaleV == 0.5f && ouLeft.offsetU == 0.0f && ouLeft.scaleU == 1.0f);
    auto ouRight = ComputeEyeCrop(VideoLayerLayout::OverUnder, 1, false);
    assert(ouRight.offsetV == 0.5f && ouRight.scaleV == 0.5f && ouRight.scaleU == 1.0f);
    printf("[PASS] TestEyeCropRectangles\n");
}

static void TestSwappedEyesExchangeTheHalves() {
    auto sbsLeft = ComputeEyeCrop(VideoLayerLayout::SideBySide, 0, true);
    assert(sbsLeft.offsetU == 0.5f);
    auto sbsRight = ComputeEyeCrop(VideoLayerLayout::SideBySide, 1, true);
    assert(sbsRight.offsetU == 0.0f);

    auto ouLeft = ComputeEyeCrop(VideoLayerLayout::OverUnder, 0, true);
    assert(ouLeft.offsetV == 0.5f);
    auto ouRight = ComputeEyeCrop(VideoLayerLayout::OverUnder, 1, true);
    assert(ouRight.offsetV == 0.0f);
    printf("[PASS] TestSwappedEyesExchangeTheHalves\n");
}

int main() {
    printf("--- Executing unit tests for video_layer.h ---\n");
    TestDirectPathCoversFlatAndEquirectModes();
    TestEquirectNeedsTheExtensionAndFullSizeContent();
    TestSphereFitsOnlyWithoutDownscale();
    TestEquirectAnglesCoverTheRightPortionOfTheSphere();
    TestEveryDisqualifierFallsBackToTheEyeBuffer();
    TestCurvedScreenNeedsTheCylinderExtension();
    TestLayoutFollowsTheScreenMode();
    TestExtentFollowsPerEyeContent();
    TestOversizeContentIsClampedKeepingAspect();
    TestEyeCropRectangles();
    TestSwappedEyesExchangeTheHalves();
    printf("--- All video_layer.h unit tests passed successfully! ---\n");
    return 0;
}
