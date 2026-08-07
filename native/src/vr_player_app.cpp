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
#include <cmath>
#include <unordered_map>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VRPlayerApp", __VA_ARGS__)

extern "C" {
    extern void start_video_playback(const char* path);
    extern void stop_video_playback();
    extern void toggle_play_pause();
    extern AHardwareBuffer* get_current_video_frame();
    extern void get_video_progress(float* current, float* total);
    extern void set_video_volume(float volume);
    extern float get_video_volume();
    extern void set_playback_speed(float speed);
    extern void cycle_audio_track();
    extern void seek_video_playback(float position);

    // T6/T7: SMB e HTTP(S) — ver rust/bridge/src/lib.rs. Playback de URL
    // HTTP(S) reusa start_video_playback (o Demuxer despacha por esquema);
    // so SMB precisa de uma entrada dedicada, porque as credenciais vem
    // como parametros separados em vez de uma URI unica (nunca cruza o JNI
    // uma string "smb://user:pass@host/..." — ver nota em bridge/src/lib.rs).
    extern void start_smb_playback(const char* host, int32_t port, const char* share,
                                    const char* path, const char* username,
                                    const char* password, const char* domain);
    extern char* smb_list_shares(const char* host, int32_t port, const char* username,
                                  const char* password, const char* domain);
    extern char* smb_list_directory(const char* host, int32_t port, const char* username,
                                     const char* password, const char* domain,
                                     const char* share, const char* path);
    extern char* probe_http_url(const char* url);
    extern void free_rust_string(char* ptr);
}

// Helper comum as 3 funcoes JNI que retornam string alocada pelo Rust
// (smb_list_shares/smb_list_directory/probe_http_url): converte para
// jstring e libera o buffer original do lado Rust antes de retornar.
static jstring RustStringToJStringAndFree(JNIEnv* env, char* rustStr) {
    if (!rustStr) {
        return env->NewStringUTF("ERROR:null");
    }
    jstring result = env->NewStringUTF(rustStr);
    free_rust_string(rustStr);
    return result;
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

// T6.4: inicia playback SMB. Ver nota acima de start_smb_playback sobre por
// que as credenciais vao como parametros separados, nao uma URI.
extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativePlaySmb(JNIEnv* env, jobject thiz, jstring host, jint port,
                                            jstring share, jstring path, jstring username,
                                            jstring password, jstring domain) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* shareStr = env->GetStringUTFChars(share, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* domainStr = env->GetStringUTFChars(domain, nullptr);

    start_smb_playback(hostStr, (int32_t)port, shareStr, pathStr, userStr, passStr, domainStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(share, shareStr);
    env->ReleaseStringUTFChars(path, pathStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(domain, domainStr);
}

// T6.1/T6.4: lista shares de um servidor (tambem serve de "testar conexao").
// BLOQUEANTE (I/O de rede sincrono do lado Rust) — o Kotlin so deve chamar
// isto de uma coroutine em Dispatchers.IO, nunca da UI thread.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeSmbListShares(JNIEnv* env, jobject thiz, jstring host, jint port,
                                                  jstring username, jstring password, jstring domain) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* domainStr = env->GetStringUTFChars(domain, nullptr);

    char* result = smb_list_shares(hostStr, (int32_t)port, userStr, passStr, domainStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(domain, domainStr);

    return RustStringToJStringAndFree(env, result);
}

// T6.1/T6.4: navega um diretorio dentro de um share. Mesma ressalva de
// bloqueio da funcao acima.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeSmbListDirectory(JNIEnv* env, jobject thiz, jstring host, jint port,
                                                     jstring username, jstring password, jstring domain,
                                                     jstring share, jstring path) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* domainStr = env->GetStringUTFChars(domain, nullptr);
    const char* shareStr = env->GetStringUTFChars(share, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);

    char* result = smb_list_directory(hostStr, (int32_t)port, userStr, passStr, domainStr, shareStr, pathStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(domain, domainStr);
    env->ReleaseStringUTFChars(share, shareStr);
    env->ReleaseStringUTFChars(path, pathStr);

    return RustStringToJStringAndFree(env, result);
}

// T7.1: probe HEAD-based de uma URL HTTP(S) (Accept-Ranges/tamanho) antes de
// tocar, para a UI poder avisar se seek nao vai funcionar. Mesma ressalva de
// bloqueio.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeProbeHttpUrl(JNIEnv* env, jobject thiz, jstring url) {
    const char* urlStr = env->GetStringUTFChars(url, nullptr);
    char* result = probe_http_url(urlStr);
    env->ReleaseStringUTFChars(url, urlStr);
    return RustStringToJStringAndFree(env, result);
}

class VRPlayerApp : public OVRFW::XrApp {
public:
    VRPlayerApp() : OVRFW::XrApp(), m_textureId(0), m_eglImage(EGL_NO_IMAGE_KHR), m_lastBuffer(nullptr),
                    m_uiImageReader(nullptr), m_uiTextureId(0), m_uiEglImage(EGL_NO_IMAGE_KHR),
                    m_controlsImageReader(nullptr), m_controlsTextureId(0), m_controlsEglImage(EGL_NO_IMAGE_KHR),
                    m_networkImageReader(nullptr), m_networkTextureId(0), m_networkEglImage(EGL_NO_IMAGE_KHR) {
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

    // T4.1: registra a action de haptics (feedback ao passar/clicar em botoes da UI)
    virtual std::unordered_map<XrPath, std::vector<XrActionSuggestedBinding>> GetSuggestedBindings(
        XrInstance instance) override {
        auto bindings = OVRFW::XrApp::GetSuggestedBindings(instance);

        XrPath handSubactionPaths[2] = {LeftHandPath, RightHandPath};
        m_hapticAction = CreateAction(
            BaseActionSet,
            XR_ACTION_TYPE_VIBRATION_OUTPUT,
            "haptic_feedback",
            nullptr,
            2,
            handSubactionPaths);

        XrPath touchInteractionProfile = XR_NULL_PATH;
        xrStringToPath(instance, "/interaction_profiles/oculus/touch_controller", &touchInteractionProfile);
        bindings[touchInteractionProfile].emplace_back(
            ActionSuggestedBinding(m_hapticAction, "/user/hand/left/output/haptic"));
        bindings[touchInteractionProfile].emplace_back(
            ActionSuggestedBinding(m_hapticAction, "/user/hand/right/output/haptic"));

        return bindings;
    }

    void FireHaptic(XrPath hand, float amplitude, XrDuration durationNs) {
        if (m_hapticAction == XR_NULL_HANDLE) {
            return;
        }
        XrHapticVibration vibration{XR_TYPE_HAPTIC_VIBRATION};
        vibration.amplitude = amplitude;
        vibration.duration = durationNs;
        vibration.frequency = XR_FREQUENCY_UNSPECIFIED;

        XrHapticActionInfo info{XR_TYPE_HAPTIC_ACTION_INFO};
        info.action = m_hapticAction;
        info.subactionPath = hand;
        xrApplyHapticFeedback(GetSession(), &info, (const XrHapticBaseHeader*)&vibration);
    }

    static float MoveTowards(float current, float target, float maxDelta) {
        if (fabsf(target - current) <= maxDelta) {
            return target;
        }
        return current + (target > current ? maxDelta : -maxDelta);
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
            uniform float uAlpha;
            void main() {
                vec4 texColor = texture(sTexture, vTexCoord);
                FragColor = vec4(texColor.rgb, uAlpha);
            }
        )";

        OVRFW::ovrProgramParm parms[] = {
            {"sTexture", OVRFW::ovrProgramParmType::TEXTURE_SAMPLED},
            {"uAlpha", OVRFW::ovrProgramParmType::FLOAT},
        };

        m_program = OVRFW::GlProgram::Build(vDirective, vertexShader, fDirective, fragmentShader, parms, 2);

        m_surfaceDef.geo = OVRFW::BuildTesselatedQuad(2, 2, false);

        m_surfaceDef.graphicsCommand.Textures[0].texture = m_textureId;
        m_surfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
        m_surfaceDef.graphicsCommand.Program = m_program;

        // CRITICAL: Link the Textures array to the UniformData for the shader!
        m_surfaceDef.graphicsCommand.BindUniformTextures();
        // Tela de video: sempre opaca (nao participa do auto-hide/fade)
        m_surfaceDef.graphicsCommand.UniformData[1].Data = &m_videoAlpha;

        m_surfaceRender.Init();
        m_beamRenderer.Init(256, true);

        // ------------------ INITIALIZE UI ------------------
        m_uiSurfaceDef.geo = OVRFW::BuildTesselatedQuad(1, 1, false);
        m_uiSurfaceDef.graphicsCommand.Program = m_program;
        m_uiSurfaceDef.graphicsCommand.UniformData[1].Data = &m_uiAlpha;
        // Auto-hide (T4.3): habilita alpha blending para o fade suave
        m_uiSurfaceDef.graphicsCommand.GpuState.blendEnable = OVRFW::ovrGpuState::BLEND_ENABLE;
        m_uiSurfaceDef.graphicsCommand.GpuState.blendSrc = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_uiSurfaceDef.graphicsCommand.GpuState.blendDst = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_uiSurfaceDef.graphicsCommand.GpuState.blendSrcAlpha = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_uiSurfaceDef.graphicsCommand.GpuState.blendDstAlpha = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_uiSurfaceDef.graphicsCommand.GpuState.depthMaskEnable = false;

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
        m_controlsSurfaceDef.graphicsCommand.UniformData[1].Data = &m_controlsAlpha;
        // Auto-hide (T4.3): habilita alpha blending para o fade suave
        m_controlsSurfaceDef.graphicsCommand.GpuState.blendEnable = OVRFW::ovrGpuState::BLEND_ENABLE;
        m_controlsSurfaceDef.graphicsCommand.GpuState.blendSrc = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_controlsSurfaceDef.graphicsCommand.GpuState.blendDst = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_controlsSurfaceDef.graphicsCommand.GpuState.blendSrcAlpha = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_controlsSurfaceDef.graphicsCommand.GpuState.blendDstAlpha = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_controlsSurfaceDef.graphicsCommand.GpuState.depthMaskEnable = false;

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

        // ------------------ INITIALIZE NETWORK UI (T6.4/T7.3) ------------------
        // Painel de gerenciamento de servidores SMB + input de URL HTTP(S),
        // seguindo o mesmo padrao VirtualDisplay+Presentation+quad OES dos
        // paineis acima (nao inventa mecanismo novo de UI).
        m_networkSurfaceDef.geo = OVRFW::BuildTesselatedQuad(1, 1, false);
        m_networkSurfaceDef.graphicsCommand.Program = m_program;
        m_networkSurfaceDef.graphicsCommand.UniformData[1].Data = &m_networkAlpha;
        m_networkSurfaceDef.graphicsCommand.GpuState.blendEnable = OVRFW::ovrGpuState::BLEND_ENABLE;
        m_networkSurfaceDef.graphicsCommand.GpuState.blendSrc = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_networkSurfaceDef.graphicsCommand.GpuState.blendDst = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_networkSurfaceDef.graphicsCommand.GpuState.blendSrcAlpha = OVRFW::ovrGpuState::kGL_SRC_ALPHA;
        m_networkSurfaceDef.graphicsCommand.GpuState.blendDstAlpha = OVRFW::ovrGpuState::kGL_ONE_MINUS_SRC_ALPHA;
        m_networkSurfaceDef.graphicsCommand.GpuState.depthMaskEnable = false;

        media_status_t networkStatus = AImageReader_newWithUsage(
            1024, 1024, AIMAGE_FORMAT_RGBA_8888,
            AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN,
            2, &m_networkImageReader);

        if (networkStatus == AMEDIA_OK && m_networkImageReader) {
            LOGI("VRPlayerApp: Network ImageReader created successfully!");
            ANativeWindow* window = nullptr;
            AImageReader_getWindow(m_networkImageReader, &window);
            if (window) {
                const xrJava* java = GetContext();
                JNIEnv* env = nullptr;
                if (java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jobject surfaceObj = ANativeWindow_toSurface(env, window);
                    jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                    jmethodID setupMethod = env->GetStaticMethodID(vrActivityClass, "setupNetworkVirtualDisplay", "(Lcom/vrplayer/VRActivity;Landroid/view/Surface;II)V");
                    if (setupMethod) {
                        env->CallStaticVoidMethod(vrActivityClass, setupMethod, java->ActivityObject, surfaceObj, 1024, 1024);
                        LOGI("VRPlayerApp: setupNetworkVirtualDisplay called successfully!");
                    } else {
                        LOGI("VRPlayerApp: setupNetworkVirtualDisplay NOT FOUND!");
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
        static bool prevX = false;
        static bool prevB = false;
        static bool prevY = false;
        static bool prevTrigger = false;
        bool currA = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonA) != 0;
        bool currX = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonX) != 0;
        bool currB = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonB) != 0;
        bool currY = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonY) != 0;
        bool currTrigger = in.LeftRemoteIndexClick || in.RightRemoteIndexClick || (in.AllButtons & OVRFW::ovrApplFrameIn::kTrigger) != 0;

        // --- RAYCAST FOR UI TOUCH (T4.1) ---
        OVR::Vector3f rayOrigin = in.RightRemotePointPose.Translation;
        OVR::Vector3f rawRayDir = in.RightRemotePointPose.Rotation * OVR::Vector3f(0.0f, 0.0f, -1.0f);

        // Dead zone/smoothing: o ray do controller tremula naturalmente; suaviza a
        // direcao com uma media movel exponencial para evitar que o cursor/laser fique
        // tremendo, sem introduzir lag perceptivel.
        if (!m_rayDirInitialized) {
            m_smoothedRayDir = rawRayDir;
            m_rayDirInitialized = true;
        } else {
            float smoothT = std::min(1.0f, in.DeltaSeconds * 18.0f);
            m_smoothedRayDir = m_smoothedRayDir * (1.0f - smoothT) + rawRayDir * smoothT;
            m_smoothedRayDir.Normalize();
        }
        const OVR::Vector3f& rayDir = m_smoothedRayDir;

        // Painel do File Browser: billboard - sempre virado para a cabeca do usuario (T4.5)
        OVR::Vector3f uiPos(-2.2f, 1.5f, -1.5f);
        OVR::Vector3f toHead = in.HeadPose.Translation - uiPos;
        toHead.y = 0.0f;
        float toHeadLen = sqrtf(toHead.x * toHead.x + toHead.z * toHead.z);
        float uiYaw = (toHeadLen > 1e-4f) ? atan2f(toHead.x / toHeadLen, toHead.z / toHeadLen) : 0.7f;
        m_uiTransform = OVR::Matrix4f::Translation(uiPos) * OVR::Matrix4f::RotationY(uiYaw);
        OVR::Vector3f uiPlaneCenter = uiPos;
        OVR::Vector3f uiPlaneNormal = OVR::Matrix4f::RotationY(uiYaw).Transform(OVR::Vector3f(0, 0, 1));

        // Painel de controles: fica logo abaixo da tela, com leve inclinacao fixa
        m_controlsTransform = OVR::Matrix4f::Translation({0.0f, 0.4f, -1.9f}) * OVR::Matrix4f::RotationX(-0.3f) * OVR::Matrix4f::Scaling(0.8f, 0.2f, 1.0f);
        OVR::Vector3f cPlaneCenter = m_controlsTransform.GetTranslation();
        OVR::Vector3f cPlaneNormal = OVR::Matrix4f::RotationX(-0.3f).Transform(OVR::Vector3f(0, 0, 1));

        // Painel de rede (T6.4/T7.3): billboard, espelhado do File Browser
        // (fica do lado direito em vez do esquerdo) — mesma logica de T4.5.
        OVR::Vector3f networkPos(2.2f, 1.5f, -1.5f);
        OVR::Vector3f networkToHead = in.HeadPose.Translation - networkPos;
        networkToHead.y = 0.0f;
        float networkToHeadLen = sqrtf(networkToHead.x * networkToHead.x + networkToHead.z * networkToHead.z);
        float networkYaw = (networkToHeadLen > 1e-4f) ? atan2f(networkToHead.x / networkToHeadLen, networkToHead.z / networkToHeadLen) : -0.7f;
        m_networkTransform = OVR::Matrix4f::Translation(networkPos) * OVR::Matrix4f::RotationY(networkYaw);
        OVR::Vector3f networkPlaneCenter = networkPos;
        OVR::Vector3f networkPlaneNormal = OVR::Matrix4f::RotationY(networkYaw).Transform(OVR::Vector3f(0, 0, 1));

        // Tela de video (usada apenas para detectar "apontando para a tela" -> mostra controles)
        OVR::Matrix4f screenTransform = OVR::Matrix4f::Translation(m_screenPosition) * OVR::Matrix4f::Scaling(m_screenScale.x, m_screenScale.y, 1.0f);

        OVR::Vector3f pointerEnd = rayOrigin + rayDir * 2.0f;

        static float lastUvX = 0.0f;
        static float lastUvY = 0.0f;
        static int activePanel = 0; // 0=None, 1=FileBrowser, 2=Controls, 3=Network
        static bool isTouchDown = false;

        // Intersecao raio-quad generica: retorna t (>0) se acertou dentro dos limites do
        // quad (-1..1 em espaco local), ou -1 caso contrario. Preenche outU/outV com as
        // coordenadas normalizadas de UV do ponto de acerto.
        auto rayHitsQuad = [&](const OVR::Matrix4f& transform,
                                const OVR::Vector3f& normal,
                                const OVR::Vector3f& center,
                                float& outU,
                                float& outV) -> float {
            float d = normal.Dot(rayDir);
            if (fabs(d) <= 0.001f) {
                return -1.0f;
            }
            float t = normal.Dot(center - rayOrigin) / d;
            if (t <= 0.0f) {
                return -1.0f;
            }
            OVR::Vector3f hitPoint = rayOrigin + rayDir * t;
            OVR::Vector3f localHit = transform.Inverted().Transform(hitPoint);
            if (localHit.x < -1.0f || localHit.x > 1.0f || localHit.y < -1.0f || localHit.y > 1.0f) {
                return -1.0f;
            }
            outU = (localHit.x + 1.0f) * 0.5f;
            outV = (1.0f - localHit.y) * 0.5f;
            return t;
        };

        int currentHitPanel = 0;
        float minT = 10.0f;
        float u = 0.0f, v = 0.0f;

        float tUi = rayHitsQuad(m_uiTransform, uiPlaneNormal, uiPlaneCenter, u, v);
        if (tUi > 0.0f && tUi < minT) {
            currentHitPanel = 1;
            minT = tUi;
            pointerEnd = rayOrigin + rayDir * tUi;
            lastUvX = u;
            lastUvY = v;
        }

        float tControls = rayHitsQuad(m_controlsTransform, cPlaneNormal, cPlaneCenter, u, v);
        if (tControls > 0.0f && tControls < minT) {
            currentHitPanel = 2;
            minT = tControls;
            pointerEnd = rayOrigin + rayDir * tControls;
            lastUvX = u;
            lastUvY = v;
        }

        float tNetwork = rayHitsQuad(m_networkTransform, networkPlaneNormal, networkPlaneCenter, u, v);
        if (tNetwork > 0.0f && tNetwork < minT) {
            currentHitPanel = 3;
            minT = tNetwork;
            pointerEnd = rayOrigin + rayDir * tNetwork;
            lastUvX = u;
            lastUvY = v;
        }

        float screenU = 0.0f, screenV = 0.0f;
        bool hitScreen = rayHitsQuad(screenTransform, OVR::Vector3f(0, 0, 1), m_screenPosition, screenU, screenV) > 0.0f;

        // --- T4.3: auto-hide dos paineis ---
        // Aponta pro File Browser -> mantem ele visivel. Aponta pra tela ou pros
        // controles -> mantem os controles visiveis. Aponta pro painel de rede ->
        // mantem ele visivel. Sem atividade por 5s -> fade out. O painel de rede NAO
        // usa o mesmo gatilho "aponta pra tela" dos controles (nao faz sentido
        // reaparecer sozinho so porque o usuario esta olhando o video) — so
        // aparece apontando pra ele mesmo ou via botao Menu (ver T4.4 abaixo).
        if (currentHitPanel == 1) {
            m_uiIdleTime = 0.0f;
        } else {
            m_uiIdleTime += in.DeltaSeconds;
        }
        if (currentHitPanel == 2 || hitScreen) {
            m_controlsIdleTime = 0.0f;
        } else {
            m_controlsIdleTime += in.DeltaSeconds;
        }
        if (currentHitPanel == 3) {
            m_networkIdleTime = 0.0f;
        } else {
            m_networkIdleTime += in.DeltaSeconds;
        }
        float uiTargetAlpha = (m_uiIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
        float controlsTargetAlpha = (m_controlsIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
        float networkTargetAlpha = (m_networkIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
        float fadeStep = in.DeltaSeconds / kUiFadeDuration;
        m_uiAlpha = MoveTowards(m_uiAlpha, uiTargetAlpha, fadeStep);
        m_controlsAlpha = MoveTowards(m_controlsAlpha, controlsTargetAlpha, fadeStep);
        m_networkAlpha = MoveTowards(m_networkAlpha, networkTargetAlpha, fadeStep);

        // So despacha toque/hover para um painel que esteja de fato visivel (evita
        // "clique invisivel" em um painel escondido pelo auto-hide); a deteccao
        // geometrica acima continua sempre ativa para poder trazer o painel de volta.
        bool uiVisible = m_uiAlpha > 0.5f;
        bool controlsVisible = m_controlsAlpha > 0.5f;
        bool networkVisible = m_networkAlpha > 0.5f;
        int dispatchHitPanel = 0;
        if (currentHitPanel == 1 && uiVisible) {
            dispatchHitPanel = 1;
        } else if (currentHitPanel == 2 && controlsVisible) {
            dispatchHitPanel = 2;
        } else if (currentHitPanel == 3 && networkVisible) {
            dispatchHitPanel = 3;
        }

        int action = -1;
        if (currTrigger && !prevTrigger && dispatchHitPanel != 0) {
            action = 0; // DOWN
            isTouchDown = true;
            activePanel = dispatchHitPanel;
        } else if (!currTrigger && prevTrigger && isTouchDown) {
            action = 1; // UP
            isTouchDown = false;
        } else if (currTrigger && isTouchDown) {
            action = 2; // MOVE
        } else if (dispatchHitPanel != 0 && !isTouchDown) {
            action = 7; // HOVER_MOVE
            activePanel = dispatchHitPanel;
        }

        if (action != -1 && activePanel != 0) {
            const xrJava* java = GetContext();
            JNIEnv* env = nullptr;
            if (java && java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                const char* methodName = (activePanel == 1) ? "dispatchVRTouch"
                    : (activePanel == 3) ? "dispatchNetworkVRTouch" : "dispatchControlsVRTouch";
                jmethodID touchMethod = env->GetStaticMethodID(vrActivityClass, methodName, "(Lcom/vrplayer/VRActivity;FFI)V");
                if (touchMethod) {
                    env->CallStaticVoidMethod(vrActivityClass, touchMethod, java->ActivityObject, lastUvX, lastUvY, action);
                }
                env->DeleteLocalRef(vrActivityClass);
            }
        }

        // Haptics (T4.1): pulso leve ao comecar a passar sobre um botao (hover-enter),
        // pulso mais forte ao clicar (trigger down).
        if (dispatchHitPanel != 0 && dispatchHitPanel != m_lastHoverPanel) {
            FireHaptic(RightHandPath, 0.25f, XR_MIN_HAPTIC_DURATION);
        }
        m_lastHoverPanel = dispatchHitPanel;
        if (action == 0) {
            FireHaptic(RightHandPath, 0.6f, 20000000 /* 20ms */);
        }

        // Remove active beam handle each frame
        if (m_beamHandle != OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE) {
            m_beamRenderer.RemoveBeam(m_beamHandle);
            m_beamHandle = OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE;
        }
        m_beamHandle = m_beamRenderer.AddBeam(in, 0.015f, rayOrigin, pointerEnd, OVR::Vector4f(1.0f, 0.0f, 0.0f, 1.0f));

        // T4.4: A (direita) ou X (esquerda) = Play/Pause. Trigger fora de qualquer
        // painel visivel tambem funciona como atalho de play/pause.
        if ((currA && !prevA) || (currX && !prevX) || (currTrigger && !prevTrigger && dispatchHitPanel == 0)) {
            LOGI("USER PRESSED PLAY/PAUSE!");
            toggle_video_state();
        }

        prevA = currA;
        prevX = currX;
        prevTrigger = currTrigger;

        // T4.4: B (direita) ou Y (esquerda) = Menu/Back -> alterna a visibilidade do
        // painel do File Browser instantaneamente (sem esperar o auto-hide).
        if ((currB && !prevB) || (currY && !prevY)) {
            bool isCurrentlyVisible = m_uiIdleTime < kUiAutoHideSeconds;
            m_uiIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
        }
        prevB = currB;
        prevY = currY;

        // T6.4/T7.3: botao Menu (esquerdo) alterna a visibilidade do painel de
        // rede (SMB + URL), mesmo padrao instantaneo do B/Y para o file browser.
        static bool prevMenu = false;
        bool currMenu = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonMenu) != 0;
        if (currMenu && !prevMenu) {
            bool isCurrentlyVisible = m_networkIdleTime < kUiAutoHideSeconds;
            m_networkIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
        }
        prevMenu = currMenu;

        static int frameCount = 0;
        frameCount++;
        if (frameCount % 6 == 0) { // Update approx 10 times per sec at 60fps
            get_video_progress(&m_lastKnownProgressCurrent, &m_lastKnownProgressTotal);

            if (m_lastKnownProgressTotal > 0.0f) {
                const xrJava* java = GetContext();
                JNIEnv* env = nullptr;
                if (java && java->Vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                    jclass vrActivityClass = env->GetObjectClass(java->ActivityObject);
                    jmethodID updateMethod = env->GetStaticMethodID(vrActivityClass, "updateMediaProgress", "(Lcom/vrplayer/VRActivity;FF)V");
                    if (updateMethod) {
                        env->CallStaticVoidMethod(vrActivityClass, updateMethod, java->ActivityObject, m_lastKnownProgressCurrent, m_lastKnownProgressTotal);
                    }
                    env->DeleteLocalRef(vrActivityClass);
                }
            }
        }

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

        // T4.4: thumbstick esquerdo = seek (X, com repeat/cooldown pois seek() e uma
        // operacao pesada que recria threads - ver T2.6) e volume (Y, continuo e
        // barato pois e so uma escrita atomica).
        {
            const float kDeadzone = 0.2f;
            OVR::Vector2f leftStick = in.LeftRemoteJoystick;
            if (fabsf(leftStick.x) < kDeadzone) leftStick.x = 0.0f;
            if (fabsf(leftStick.y) < kDeadzone) leftStick.y = 0.0f;

            if (leftStick.y != 0.0f) {
                float vol = get_video_volume();
                vol = std::max(0.0f, std::min(1.0f, vol + leftStick.y * 0.5f * in.DeltaSeconds));
                set_video_volume(vol);
            }

            m_seekRepeatCooldown = std::max(0.0f, m_seekRepeatCooldown - in.DeltaSeconds);
            if (leftStick.x != 0.0f && m_seekRepeatCooldown <= 0.0f && m_lastKnownProgressTotal > 0.0f) {
                const float kSeekJumpSeconds = 10.0f;
                float target = m_lastKnownProgressCurrent + (leftStick.x > 0.0f ? kSeekJumpSeconds : -kSeekJumpSeconds);
                target = std::max(0.0f, std::min(target, m_lastKnownProgressTotal));
                seek_video_playback(target);
                m_seekRepeatCooldown = 0.5f;
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

        // --- PROCESS NETWORK UI TEXTURE UPDATE (T6.4/T7.3) ---
        if (m_networkImageReader) {
            AImage* image = nullptr;
            if (AImageReader_acquireLatestImage(m_networkImageReader, &image) == AMEDIA_OK && image) {
                AHardwareBuffer* buffer = nullptr;
                AImage_getHardwareBuffer(image, &buffer);
                if (buffer) {
                    static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
                    if (m_networkEglImage != EGL_NO_IMAGE_KHR) {
                        static PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
                        if (eglDestroyImageKHR) eglDestroyImageKHR(eglGetCurrentDisplay(), m_networkEglImage);
                        m_networkEglImage = EGL_NO_IMAGE_KHR;
                    }
                    EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
                    EGLint eglImageAttributes[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
                    static PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
                    if (eglCreateImageKHR) {
                        m_networkEglImage = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, eglImageAttributes);
                        if (m_networkEglImage != EGL_NO_IMAGE_KHR) {
                            if (m_networkTextureId == 0) {
                                glGenTextures(1, &m_networkTextureId);
                                glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_networkTextureId);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                                glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                            }
                            glBindTexture(GL_TEXTURE_EXTERNAL_OES, m_networkTextureId);
                            static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
                            if (glEGLImageTargetTexture2DOES) glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, m_networkEglImage);
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

        // Painel do File Browser: billboard + fade in/out por auto-hide (T4.3/T4.5).
        // m_uiTransform/m_uiAlpha sao calculados em Update() para casar exatamente
        // com o raycast (evita transforms duplicados e divergentes entre os dois).
        if (m_uiTextureId != 0 && m_uiAlpha > 0.01f) {
            m_uiSurfaceDef.graphicsCommand.Textures[0].texture = m_uiTextureId;
            m_uiSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
            m_uiSurfaceDef.graphicsCommand.BindUniformTextures();
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(m_uiTransform, &m_uiSurfaceDef));
        }

        // Painel de controles embaixo do video, com o mesmo fade por auto-hide.
        if (m_controlsTextureId != 0 && m_controlsAlpha > 0.01f) {
            m_controlsSurfaceDef.graphicsCommand.Textures[0].texture = m_controlsTextureId;
            m_controlsSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
            m_controlsSurfaceDef.graphicsCommand.BindUniformTextures();
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(m_controlsTransform, &m_controlsSurfaceDef));
        }

        // Painel de rede (T6.4/T7.3): billboard + fade por auto-hide, espelhado
        // do File Browser (ver Update()).
        if (m_networkTextureId != 0 && m_networkAlpha > 0.01f) {
            m_networkSurfaceDef.graphicsCommand.Textures[0].texture = m_networkTextureId;
            m_networkSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
            m_networkSurfaceDef.graphicsCommand.BindUniformTextures();
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(m_networkTransform, &m_networkSurfaceDef));
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
    static constexpr float kUiAutoHideSeconds = 5.0f;
    static constexpr float kUiFadeDuration = 0.35f;

    // Posicao/tamanho da tela virtual, ajustaveis em runtime (T3.6)
    OVR::Vector3f m_screenPosition = OVR::Vector3f(0.0f, 1.5f, -2.0f);
    OVR::Vector2f m_screenScale = OVR::Vector2f(1.6f, 0.9f);

    // Auto-hide + billboard + alpha blending dos paineis de UI (T4.3/T4.5)
    float m_videoAlpha = 1.0f;
    float m_uiAlpha = 1.0f;
    float m_controlsAlpha = 1.0f;
    float m_networkAlpha = 1.0f;
    float m_uiIdleTime = 0.0f;
    float m_controlsIdleTime = 0.0f;
    float m_networkIdleTime = 0.0f;
    OVR::Matrix4f m_uiTransform;
    OVR::Matrix4f m_controlsTransform;
    OVR::Matrix4f m_networkTransform;

    // Raycast: smoothing de direcao + haptics de hover/click (T4.1)
    OVR::Vector3f m_smoothedRayDir = OVR::Vector3f(0.0f, 0.0f, -1.0f);
    bool m_rayDirInitialized = false;
    int m_lastHoverPanel = 0;
    XrAction m_hapticAction = XR_NULL_HANDLE;

    // Thumbstick esquerdo: seek/volume (T4.4)
    float m_seekRepeatCooldown = 0.0f;
    float m_lastKnownProgressCurrent = 0.0f;
    float m_lastKnownProgressTotal = 0.0f;

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

    // Network UI System (T6.4/T7.3): SMB server manager + URL input
    AImageReader* m_networkImageReader;
    GLuint m_networkTextureId;
    EGLImageKHR m_networkEglImage;
    OVRFW::ovrSurfaceDef m_networkSurfaceDef;

    OVRFW::ovrBeamRenderer m_beamRenderer;
    OVRFW::ovrBeamRenderer::handle_t m_beamHandle{OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE};
};

ENTRY_POINT(VRPlayerApp)
