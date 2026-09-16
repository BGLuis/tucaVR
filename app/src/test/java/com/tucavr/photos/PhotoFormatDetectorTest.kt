package com.tucavr.photos

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFormatDetectorTest {

    @Test
    fun testDetectStereoModeSbsSuffixes() {
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("vacation_sbs.jpg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("panorama-sbs.png"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("image.sbs.jpeg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("museum_lr.jpg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("view-lr.png"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("concert_3d_sbs.jpg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("show_hsbs.jpg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("side-by-side-render.png"))
    }

    @Test
    fun testDetectStereoModeOuSuffixes() {
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("mountain_ou.jpg"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("cliff-ou.png"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("lake.ou.jpeg"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("stereo_tb.jpg"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("nature-tb.png"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("render_3d_ou.jpg"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("top-bottom.jpg"))
        assertEquals(PhotoStereoMode.OVER_UNDER, PhotoFormatDetector.detectStereoMode("scene_overunder.png"))
    }

    @Test
    fun testDetectStereoModeGeneric3dSuffix() {
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("camera_shot_3d.jpg"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("render-3d.png"))
        assertEquals(PhotoStereoMode.SIDE_BY_SIDE, PhotoFormatDetector.detectStereoMode("photo.3d.jpeg"))
    }

    @Test
    fun testDetectStereoModeMono() {
        assertEquals(PhotoStereoMode.MONO, PhotoFormatDetector.detectStereoMode("normal_photo.jpg"))
        assertEquals(PhotoStereoMode.MONO, PhotoFormatDetector.detectStereoMode("family_portrait.png"))
        assertEquals(PhotoStereoMode.MONO, PhotoFormatDetector.detectStereoMode("document.webp"))
    }

    @Test
    fun testParseXmpProjection() {
        val xmpEquirect = "<x:xmpmeta><rdf:Description GPano:ProjectionType=\"equirectangular\"/></x:xmpmeta>"
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.parseXmpProjection(xmpEquirect))

        val xmpViewer = "<x:xmpmeta><rdf:Description GPano:UsePanoramaViewer=\"true\"/></x:xmpmeta>"
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.parseXmpProjection(xmpViewer))

        val xmpOther = "<x:xmpmeta><rdf:Description dc:title=\"Simple Picture\"/></x:xmpmeta>"
        assertEquals(null, PhotoFormatDetector.parseXmpProjection(xmpOther))
        assertEquals(null, PhotoFormatDetector.parseXmpProjection(""))
        assertEquals(null, PhotoFormatDetector.parseXmpProjection(null))
    }

    @Test
    fun testDetectProjectionByFilename() {
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(1920, 1080, null, "pano_360_beach.jpg"))
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(1920, 1080, null, "photosphere_room.jpg"))
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(1920, 1080, null, "equirectangular_street.jpg"))
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(1920, 1080, null, "tour_vr360.jpeg"))

        assertEquals(PhotoProjection.VR_180, PhotoFormatDetector.detectProjection(1920, 1080, null, "vr180_stage.jpg"))
        assertEquals(PhotoProjection.VR_180, PhotoFormatDetector.detectProjection(1920, 1080, null, "show_180.png"))
    }

    @Test
    fun testDetectProjectionByAspectRatioHeuristic() {
        // Alta resolução com aspect 2:1 -> 360 equirretangular
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(5760, 2880, null, "img001.jpg"))
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(6080, 3040, null, "insta360.jpg"))
        assertEquals(PhotoProjection.EQUIRECTANGULAR_360, PhotoFormatDetector.detectProjection(8192, 4096, null, "theta_z1.jpg"))

        // Baixa resolução com aspect 2:1 sem metadados -> Flat
        assertEquals(PhotoProjection.FLAT, PhotoFormatDetector.detectProjection(800, 400, null, "banner.jpg"))
        assertEquals(PhotoProjection.FLAT, PhotoFormatDetector.detectProjection(1920, 960, null, "banner_fullhd.jpg"))

        // Proporções comuns que não são 2:1 -> Flat
        assertEquals(PhotoProjection.FLAT, PhotoFormatDetector.detectProjection(4000, 3000, null, "camera.jpg"))
        assertEquals(PhotoProjection.FLAT, PhotoFormatDetector.detectProjection(3840, 2160, null, "wallpaper_4k.jpg"))
        assertEquals(PhotoProjection.FLAT, PhotoFormatDetector.detectProjection(1080, 1920, null, "vertical.jpg"))
    }

    @Test
    fun testToScreenModeMapping() {
        // Flat 2D
        assertEquals(0, PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.MONO).toScreenMode())
        // SBS Flat
        assertEquals(1, PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.SIDE_BY_SIDE).toScreenMode())
        // OU Flat
        assertEquals(3, PhotoFormat(PhotoProjection.FLAT, PhotoStereoMode.OVER_UNDER).toScreenMode())

        // 360 Mono Sphere
        assertEquals(5, PhotoFormat(PhotoProjection.EQUIRECTANGULAR_360, PhotoStereoMode.MONO).toScreenMode())
        // 360 SBS Sphere
        assertEquals(7, PhotoFormat(PhotoProjection.EQUIRECTANGULAR_360, PhotoStereoMode.SIDE_BY_SIDE).toScreenMode())
        // 360 OU Sphere
        assertEquals(8, PhotoFormat(PhotoProjection.EQUIRECTANGULAR_360, PhotoStereoMode.OVER_UNDER).toScreenMode())

        // 180 Mono Sphere
        assertEquals(6, PhotoFormat(PhotoProjection.VR_180, PhotoStereoMode.MONO).toScreenMode())
        // 180 SBS Sphere
        assertEquals(9, PhotoFormat(PhotoProjection.VR_180, PhotoStereoMode.SIDE_BY_SIDE).toScreenMode())
    }
}
