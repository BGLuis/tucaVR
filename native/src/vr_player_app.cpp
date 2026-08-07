#include <openxr/openxr.h>
#include "XrApp.h"
#include "OVR_Math.h"
#include "Render/GlGeometry.h"
#include "Render/SurfaceRender.h"
#include "Render/GlProgram.h"
#include "Render/GlTexture.h"
#include <android/hardware_buffer.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3ext.h>
#include <android/log.h>
#include "Render/BeamRenderer.h"
#include <media/NdkImageReader.h>
#include <media/NdkImage.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VRPlayerApp", __VA_ARGS__)

extern "C" {
    extern void start_video_playback(const char* path);
    extern void stop_video_playback();
    extern void toggle_play_pause();
    extern AHardwareBuffer* get_current_video_frame();
    extern void get_video_progress(float* current, float* total);
    extern void set_video_volume(float volume);
    extern void set_playback_speed(float speed);
    extern void cycle_audio_track();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativePlayVideo(JNIEnv* env, jobject thiz, jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    start_video_playback(pathStr);
    env->ReleaseStringUTFChars(path, pathStr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeTogglePlayPause(JNIEnv* env, jobject thiz) {
    toggle_play_pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSeekVideo(JNIEnv* env, jobject thiz, jfloat positionSeconds) {
    extern void seek_video_playback(float position);
    seek_video_playback(positionSeconds);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetVolume(JNIEnv* env, jobject thiz, jfloat volume) {
    set_video_volume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetSpeed(JNIEnv* env, jobject thiz, jfloat speed) {
    set_playback_speed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeCycleAudioTrack(JNIEnv* env, jobject thiz) {
    cycle_audio_track();
}

class VRPlayerApp : public OVRFW::XrApp {
public:
    VRPlayerApp() : OVRFW::XrApp(), m_textureId(0), m_eglImage(EGL_NO_IMAGE_KHR), m_lastBuffer(nullptr),
                    m_uiImageReader(nullptr), m_uiTextureId(0), m_uiEglImage(EGL_NO_IMAGE_KHR),
                    m_controlsImageReader(nullptr), m_controlsTextureId(0), m_controlsEglImage(EGL_NO_IMAGE_KHR) {
        // Ambiente "void": fundo totalmente preto, sem geometria de ambiente (T3.3)
        BackgroundColor = OVR::Vector4f(0.0f, 0.0f, 0.0f, 1.0f);
    }

    void toggle_video_state() {
        ::toggle_play_pause();
    }

    virtual std::vector<const char*> GetExtensions() override {
        std::vector<const char*> extensions = OVRFW::XrApp::GetExtensions();
        extensions.push_back("XR_FB_display_refresh_rate");
        return extensions;
    }

    virtual bool AppInit(const xrJava* context) override {
        LOGI("VRPlayerApp::AppInit");
        return true;
    }

    virtual bool SessionInit() override {
        LOGI("VRPlayerApp::SessionInit");
        
        PFN_xrRequestDisplayRefreshRateFB pfnRequestDisplayRefreshRateFB = nullptr;
        xrGetInstanceProcAddr(GetInstance(), "xrRequestDisplayRefreshRateFB", (PFN_xrVoidFunction*)&pfnRequestDisplayRefreshRateFB);
        if (pfnRequestDisplayRefreshRateFB) {
            pfnRequestDisplayRefreshRateFB(GetSession(), 90.0f);
            LOGI("VRPlayerApp: Requested 90Hz refresh rate.");
        } else {
            LOGI("VRPlayerApp: xrRequestDisplayRefreshRateFB not found.");
        }
        
        glGenTextures(1, &m_textureId);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_textureId);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

        const char* vDirective = "";
        const char* vertexShader = R"(
            in vec3 Position;
            in vec2 TexCoord;
            out vec2 vTexCoord;
            void main() {
                gl_Position = TransformVertex(vec4(Position, 1.0));
                vTexCoord = TexCoord; // Removida a inversao, o OVRFW aparentemente ja trata isso
            }
        )";

        const char* fDirective = "#extension GL_OES_EGL_image_external_essl3 : require\n";
        const char* fragmentShader = R"(
            in vec2 vTexCoord;
            out vec4 FragColor;
            uniform samplerExternalOES sTexture;
            void main() {
                vec4 texColor = texture(sTexture, vTexCoord);
                FragColor = vec4(texColor.rgb, 1.0); // Forca opacidade 1.0
            }
        )";

        OVRFW::ovrProgramParm parms[] = {
            {"sTexture", OVRFW::ovrProgramParmType::TEXTURE_SAMPLED},
        };

        m_program = OVRFW::GlProgram::Build(vDirective, vertexShader, fDirective, fragmentShader, parms, 1);

        m_surfaceDef.geo = OVRFW::BuildTesselatedQuad(2, 2, false);

        m_surfaceDef.graphicsCommand.Textures[0].texture = m_textureId;
        m_surfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
        m_surfaceDef.graphicsCommand.Program = m_program;
        
        // CRITICAL: Link the Textures array to the UniformData for the shader!
        m_surfaceDef.graphicsCommand.BindUniformTextures();
        
        m_surfaceRender.Init();
        m_beamRenderer.Init(256, true);

        // ------------------ INITIALIZE UI ------------------
        m_uiSurfaceDef.geo = OVRFW::BuildTesselatedQuad(1, 1, false);
        m_uiSurfaceDef.graphicsCommand.Program = m_program;
        
        media_status_t status = AImageReader_newWithUsage(
            1024, 1024, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            2, &m_uiImageReader);
            
        if (status == AMEDIA_OK && m_uiImageReader) {
            LOGI("VRPlayerApp: UI ImageReader created successfully!");
            ANativeWindow* window = nullptr;
            AImageReader_getWindow(m_uiImageReader, &window);
            if (window) {
                const xrJava* java = GetContext();
                JNIEnv* env = nullptr;
                if (java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jobject surfaceObj = ANativeWindow_toSurface(env, window);
                    jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                    jmethodID setupMethod = env->GetStaticMethodID(vrActivityClass, "setupVirtualDisplay", "(Lcom/vrplayer/VRActivity;Landroid/view/Surface;II)V");
                    if (setupMethod) {
                        env->CallStaticVoidMethod(vrActivityClass, setupMethod, java->ActivityObject, surfaceObj, 1024, 1024);
                        LOGI("VRPlayerApp: setupVirtualDisplay called successfully!");
                    } else {
                        LOGI("VRPlayerApp: setupVirtualDisplay NOT FOUND!");
                    }
                    env->DeleteLocalRef(surfaceObj);
                    env->DeleteLocalRef(vrActivityClass);
                }
            }
        }

        // ------------------ INITIALIZE CONTROLS UI ------------------
        m_controlsSurfaceDef.geo = OVRFW::BuildTesselatedQuad(1, 1, false);
        m_controlsSurfaceDef.graphicsCommand.Program = m_program;
        
        media_status_t controlsStatus = AImageReader_newWithUsage(
            1024, 256, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            2, &m_controlsImageReader);
            
        if (controlsStatus == AMEDIA_OK && m_controlsImageReader) {
            LOGI("VRPlayerApp: Controls ImageReader created successfully!");
            ANativeWindow* window = nullptr;
            AImageReader_getWindow(m_controlsImageReader, &window);
            if (window) {
                const xrJava* java = GetContext();
                JNIEnv* env = nullptr;
                if (java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jobject surfaceObj = ANativeWindow_toSurface(env, window);
                    jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                    jmethodID setupMethod = env->GetStaticMethodID(vrActivityClass, "setupControlsVirtualDisplay", "(Lcom/vrplayer/VRActivity;Landroid/view/Surface;II)V");
                    if (setupMethod) {
                        env->CallStaticVoidMethod(vrActivityClass, setupMethod, java->ActivityObject, surfaceObj, 1024, 256);
                        LOGI("VRPlayerApp: setupControlsVirtualDisplay called successfully!");
                    }
                    env->DeleteLocalRef(surfaceObj);
                    env->DeleteLocalRef(vrActivityClass);
                }
            }
        }

        // INICIAR VÍDEO DE TESTE AUTOMATICAMENTE
        LOGI("VRPlayerApp: Iniciando vídeo de teste automaticamente!");
        start_video_playback("/sdcard/Android/data/com.vrplayer/files/test.mp4");

        return true;
    }

    virtual void Update(const OVRFW::ovrApplFrameIn& in) override {
        static bool prevA = false;
        static bool prevTrigger = false;
        bool currA = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonA) != 0;
        bool currTrigger = in.LeftRemoteIndexClick || in.RightRemoteIndexClick || (in.AllButtons & OVRFW::ovrApplFrameIn::kTrigger) != 0;
        
        // --- RAYCAST FOR UI TOUCH ---
        OVR::Vector3f rayOrigin = in.RightRemotePointPose.Translation;
        OVR::Vector3f rayDir = in.RightRemotePointPose.Rotation * OVR::Vector3f(0.0f, 0.0f, -1.0f);
        
        // Move UI to the left side
        OVR::Matrix4f uiTransform = OVR::Matrix4f::Translation({-2.2f, 1.5f, -1.5f}) * OVR::Matrix4f::RotationY(0.7f) * OVR::Matrix4f::Scaling(1.0f, 1.0f, 1.0f);
        OVR::Vector3f uiPlaneCenter = uiTransform.GetTranslation();
        OVR::Vector3f uiPlaneNormal = OVR::Matrix4f::RotationY(0.7f).Transform(OVR::Vector3f(0,0,1));
        
        OVR::Matrix4f cTransform = OVR::Matrix4f::Translation({0.0f, 0.4f, -1.9f}) * OVR::Matrix4f::RotationX(-0.3f) * OVR::Matrix4f::Scaling(0.8f, 0.2f, 1.0f);
        OVR::Vector3f cPlaneCenter = cTransform.GetTranslation();
        OVR::Vector3f cPlaneNormal = OVR::Matrix4f::RotationX(-0.3f).Transform(OVR::Vector3f(0,0,1));
        
        OVR::Vector3f pointerEnd = rayOrigin + rayDir * 2.0f;

        static float lastUvX = 0.0f;
        static float lastUvY = 0.0f;
        static int activePanel = 0; // 0=None, 1=FileBrowser, 2=Controls
        static bool isTouchDown = false;
        
        int currentHitPanel = 0;
        float minT = 10.0f;
        
        // Check File Browser UI
        float d1 = uiPlaneNormal.Dot(rayDir);
        if (fabs(d1) > 0.001f) {
            float t = uiPlaneNormal.Dot(uiPlaneCenter - rayOrigin) / d1;
            if (t > 0.0f && t < minT) {
                OVR::Vector3f hitPoint = rayOrigin + rayDir * t;
                OVR::Vector3f localHit = uiTransform.Inverted().Transform(hitPoint);
                if (localHit.x >= -1.0f && localHit.x <= 1.0f && localHit.y >= -1.0f && localHit.y <= 1.0f) {
                    currentHitPanel = 1;
                    minT = t;
                    pointerEnd = hitPoint;
                    lastUvX = (localHit.x + 1.0f) * 0.5f;
                    lastUvY = (1.0f - localHit.y) * 0.5f;
                }
            }
        }
        
        // Check Controls UI
        float d2 = cPlaneNormal.Dot(rayDir);
        if (fabs(d2) > 0.001f) {
            float t = cPlaneNormal.Dot(cPlaneCenter - rayOrigin) / d2;
            if (t > 0.0f && t < minT) {
                OVR::Vector3f hitPoint = rayOrigin + rayDir * t;
                OVR::Vector3f localHit = cTransform.Inverted().Transform(hitPoint);
                if (localHit.x >= -1.0f && localHit.x <= 1.0f && localHit.y >= -1.0f && localHit.y <= 1.0f) {
                    currentHitPanel = 2;
                    minT = t;
                    pointerEnd = hitPoint;
                    lastUvX = (localHit.x + 1.0f) * 0.5f;
                    lastUvY = (1.0f - localHit.y) * 0.5f;
                }
            }
        }
        
        int action = -1;
        if (currTrigger && !prevTrigger && currentHitPanel != 0) {
            action = 0; // DOWN
            isTouchDown = true;
            activePanel = currentHitPanel;
        } else if (!currTrigger && prevTrigger && isTouchDown) {
            action = 1; // UP
            isTouchDown = false;
        } else if (currTrigger && isTouchDown) {
            action = 2; // MOVE
        } else if (currentHitPanel != 0 && !isTouchDown) {
            action = 7; // HOVER_MOVE
            activePanel = currentHitPanel;
        }

        if (action != -1 && activePanel != 0) {
            const xrJava* java = GetContext();
            JNIEnv* env = nullptr;
            if (java && java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                const char* methodName = (activePanel == 1) ? "dispatchVRTouch" : "dispatchControlsVRTouch";
                jmethodID touchMethod = env->GetStaticMethodID(vrActivityClass, methodName, "(Lcom/vrplayer/VRActivity;FFI)V");
                if (touchMethod) {
                    env->CallStaticVoidMethod(vrActivityClass, touchMethod, java->ActivityObject, lastUvX, lastUvY, action);
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }
        
        // Remove active beam handle each frame
        if (m_beamHandle != OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE) {
            m_beamRenderer.RemoveBeam(m_beamHandle);
            m_beamHandle = OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE;
        }
        m_beamHandle = m_beamRenderer.AddBeam(in, 0.015f, rayOrigin, pointerEnd, OVR::Vector4f(1.0f, 0.0f, 0.0f, 1.0f));

        if ((currA && !prevA) || (currTrigger && !prevTrigger && currentHitPanel == 0)) {
            LOGI("USER PRESSED PLAY/PAUSE!");
            toggle_video_state();
        }

        prevA = currA;
        prevTrigger = currTrigger;
        
        static int frameCount = 0;
        frameCount++;
        if (frameCount % 6 == 0) { // Update approx 10 times per sec at 60fps
            float current = 0.0f;
            float total = 0.0f;
            get_video_progress(&current, &total);
            
            if (total > 0.0f) {
                const xrJava* java = GetContext();
                JNIEnv* env = nullptr;
                if (java && java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                    jmethodID updateMethod = env->GetStaticMethodID(vrActivityClass, "updateMediaProgress", "(Lcom/vrplayer/VRActivity;FF)V");
                    if (updateMethod) {
                        env->CallStaticVoidMethod(vrActivityClass, updateMethod, java->ActivityObject, current, total);
                    }
                    env->DeleteLocalRef(vrActivityClass);
                }
            }
        }

        static bool prevX = false;
        static bool prevY = false;
        bool currX = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonX) != 0;
        bool currY = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonY) != 0;

        if ((currX && !prevX) || (currY && !prevY)) {
            LOGI("USER PRESSED X/Y! Opening file picker!");
        }
        prevX = currX;
        prevY = currY;

        // --- MOVER/REDIMENSIONAR A TELA VIRTUAL (T3.6) ---
        // Sem grip: thumbstick direito move a tela (Y do stick = frente/tras,
        // X do stick = cima/baixo). Com grip: thumbstick redimensiona,
        // mantendo o aspect ratio 16:9.
        {
            const float kDeadzone = 0.15f;
            OVR::Vector2f stick = in.RightRemoteJoystick;
            if (fabsf(stick.x) < kDeadzone) stick.x = 0.0f;
            if (fabsf(stick.y) < kDeadzone) stick.y = 0.0f;

            bool gripHeld = in.RightRemoteGripTrigger > 0.5f;

            if (gripHeld && stick.y != 0.0f) {
                const float kResizeSpeedMetersPerSec = 1.0f;
                float newWidth = m_screenScale.x + stick.y * kResizeSpeedMetersPerSec * in.DeltaSeconds;
                newWidth = std::max(0.5f, std::min(newWidth, 6.0f));
                m_screenScale.x = newWidth;
                m_screenScale.y = newWidth * (9.0f / 16.0f);
            } else if (!gripHeld && (stick.x != 0.0f || stick.y != 0.0f)) {
                const float kMoveSpeedMetersPerSec = 1.5f;
                m_screenPosition.z -= stick.y * kMoveSpeedMetersPerSec * in.DeltaSeconds;
                m_screenPosition.y += stick.x * kMoveSpeedMetersPerSec * in.DeltaSeconds;
                // Limites de conforto: nunca deixar a tela grudada no rosto
                // nem sumir no chao/teto.
                m_screenPosition.z = std::min(-0.75f, std::max(m_screenPosition.z, -8.0f));
                m_screenPosition.y = std::max(0.2f, std::min(m_screenPosition.y, 3.5f));
            }
        }

        AHardwareBuffer* buffer = get_current_video_frame();
        
        static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = 
            (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
        static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = 
            (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
        static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = 
            (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
        static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = 
            (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");

        if (buffer == nullptr && m_lastBuffer != nullptr) {
            m_lastBuffer = nullptr;
            if (m_textureId != 0) {
                glDeleteTextures(1, &m_textureId);
                m_textureId = 0;
            }
            if (m_eglImage != EGL_NO_IMAGE_KHR) {
                if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_eglImage);
                m_eglImage = EGL_NO_IMAGE_KHR;
            }
        } else if (buffer && buffer != m_lastBuffer) {
            m_lastBuffer = buffer;
            
            if (m_eglImage != EGL_NO_IMAGE_KHR) {
                if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_eglImage);
                m_eglImage = EGL_NO_IMAGE_KHR;
            }

            EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
            EGLint attribs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
            
            if (eglCreateImageKHR) {
                m_eglImage = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribs);
                if (m_eglImage == EGL_NO_IMAGE_KHR) {
                    LOGI("VRPlayerApp: eglCreateImageKHR FAILED! Error: 0x%x", eglGetError());
                }
            }

            if (m_eglImage != EGL_NO_IMAGE_KHR && glEGLImageTargetTexture2DOES) {
                if (m_textureId == 0) {
                    glGenTextures(1, &m_textureId);
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_textureId);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                    
                    // CRITICAL: Update surface definition!
                    m_surfaceDef.graphicsCommand.Textures[0].texture = m_textureId;
                } else {
                    glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_textureId);
                }
                glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, (GLeglImageOES)m_eglImage);
                GLenum err = glGetError();
                if (err != GL_NO_ERROR) {
                    LOGI("VRPlayerApp: glEGLImageTargetTexture2DOES failed! GL error: 0x%x", err);
                }
                glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
            }
        }
    }

    virtual void Render(const OVRFW::ovrApplFrameIn& in, OVRFW::ovrRendererOutput& out) override {
        // --- PROCESS UI TEXTURE UPDATE ---
        if (m_uiImageReader) {
            AImage* image = nullptr;
            if (AImageReader_acquireLatestImage(m_uiImageReader, &image) == AMEDIA_OK && image) {
                AHardwareBuffer* buffer = nullptr;
                AImage_getHardwareBuffer(image, &buffer);
                if (buffer) {
                    static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
                    if (m_uiEglImage != EGL_NO_IMAGE_KHR) {
                        static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
                        if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_uiEglImage);
                        m_uiEglImage = EGL_NO_IMAGE_KHR;
                    }
                    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
                    EGLint eglImageAttributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
                    static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
                    if (eglCreateImageKHR) {
                        m_uiEglImage = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, eglImageAttributes);
                        if (m_uiEglImage != EGL_NO_IMAGE_KHR) {
                            if (m_uiTextureId == 0) {
                                glGenTextures(1, &m_uiTextureId);
                                glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_uiTextureId);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                            }
                            glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_uiTextureId);
                            static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
                            if (glEGLImageTargetTexture2DOES) glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, m_uiEglImage);
                            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
                        }
                    }
                }
                AImage_delete(image);
            }
        }
        
        // --- PROCESS CONTROLS UI TEXTURE UPDATE ---
        if (m_controlsImageReader) {
            AImage* image = nullptr;
            if (AImageReader_acquireLatestImage(m_controlsImageReader, &image) == AMEDIA_OK && image) {
                AHardwareBuffer* buffer = nullptr;
                AImage_getHardwareBuffer(image, &buffer);
                if (buffer) {
                    static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
                    if (m_controlsEglImage != EGL_NO_IMAGE_KHR) {
                        static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
                        if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_controlsEglImage);
                        m_controlsEglImage = EGL_NO_IMAGE_KHR;
                    }
                    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
                    EGLint eglImageAttributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
                    static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
                    if (eglCreateImageKHR) {
                        m_controlsEglImage = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, eglImageAttributes);
                        if (m_controlsEglImage != EGL_NO_IMAGE_KHR) {
                            if (m_controlsTextureId == 0) {
                                glGenTextures(1, &m_controlsTextureId);
                                glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_controlsTextureId);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                            }
                            glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_controlsTextureId);
                            static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
                            if (glEGLImageTargetTexture2DOES) glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, m_controlsEglImage);
                            glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
                        }
                    }
                }
                AImage_delete(image);
            }
        }
        
        // Posicao/escala ajustaveis pelo usuario via thumbstick (T3.6)
        OVR::Matrix4f transform = OVR::Matrix4f::Translation(m_screenPosition) *
            OVR::Matrix4f::Scaling(m_screenScale.x, m_screenScale.y, 1.0f);
        out.Surfaces.push_back(OVRFW::ovrDrawSurface(transform, &m_surfaceDef));
        
        // Mover a UI do File Browser para a esquerda (X = -2.5), rotacionada levemente para o usuario (Y = 45 graus)
        if (m_uiTextureId != 0) {
            m_uiSurfaceDef.graphicsCommand.Textures[0].texture = m_uiTextureId;
            m_uiSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
            m_uiSurfaceDef.graphicsCommand.BindUniformTextures();
            OVR::Matrix4f uiTransform = OVR::Matrix4f::Translation({-2.2f, 1.5f, -1.5f}) * OVR::Matrix4f::RotationY(0.7f) * OVR::Matrix4f::Scaling(1.0f, 1.0f, 1.0f);
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(uiTransform, &m_uiSurfaceDef));
        }

        // Desenhar UI de controles embaixo do video
        if (m_controlsTextureId != 0) {
            m_controlsSurfaceDef.graphicsCommand.Textures[0].texture = m_controlsTextureId;
            m_controlsSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
            m_controlsSurfaceDef.graphicsCommand.BindUniformTextures();
            // Scaling X by 0.8 and Y by 0.2 to match aspect ratio of 1024x256
            OVR::Matrix4f cTransform = OVR::Matrix4f::Translation({0.0f, 0.4f, -1.9f}) * OVR::Matrix4f::RotationX(-0.3f) * OVR::Matrix4f::Scaling(0.8f, 0.2f, 1.0f);
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(cTransform, &m_controlsSurfaceDef));
        }
        
        m_beamRenderer.Frame(in, out.FrameMatrices.CenterView);
        m_beamRenderer.Render(out.Surfaces);
    }

    virtual void SessionEnd() override {
        m_surfaceRender.Shutdown();
        m_beamRenderer.Shutdown();
        if (m_textureId != 0) {
            glDeleteTextures(1, &m_textureId);
            m_textureId = 0;
        }

        // Stop audio and video threads when session ends
        stop_video_playback();

        if (m_eglImage != EGL_NO_IMAGE_KHR) {
            static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = 
                (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
            if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_eglImage);
            m_eglImage = EGL_NO_IMAGE_KHR;
        }
        m_surfaceDef.geo.Free();
        OVRFW::GlProgram::Free(m_program);
    }

private:
    // Posicao/tamanho da tela virtual, ajustaveis em runtime (T3.6)
    OVR::Vector3f m_screenPosition = OVR::Vector3f(0.0f, 1.5f, -2.0f);
    OVR::Vector2f m_screenScale = OVR::Vector2f(1.6f, 0.9f);

    GLuint m_textureId;
    EGLImageKHR m_eglImage;
    AHardwareBuffer* m_lastBuffer;
    OVRFW::GlProgram m_program;
    OVRFW::ovrSurfaceDef m_surfaceDef;
    OVRFW::ovrSurfaceRender m_surfaceRender;
    
    // UI System
    AImageReader* m_uiImageReader;
    GLuint m_uiTextureId;
    EGLImageKHR m_uiEglImage;
    OVRFW::ovrSurfaceDef m_uiSurfaceDef;
    
    // Controls UI System
    AImageReader* m_controlsImageReader;
    GLuint m_controlsTextureId;
    EGLImageKHR m_controlsEglImage;
    OVRFW::ovrSurfaceDef m_controlsSurfaceDef;
    
    OVRFW::ovrBeamRenderer m_beamRenderer;
    OVRFW::ovrBeamRenderer::handle_t m_beamHandle{OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE};
};

ENTRY_POINT(VRPlayerApp)
