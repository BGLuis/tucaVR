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
#include <atomic>
#include <mutex>
#include <string>
#include <cstdio>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VRPlayerApp", __VA_ARGS__)

extern "C" {
    extern void start_video_playback(const char* path);
    extern void stop_video_playback();
    extern void toggle_play_pause();
    extern void on_app_focus_lost();
    extern void on_app_focus_gained();
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
    extern char* take_last_playback_error();

    // T6.4: FTP — mesmo padrao de start_smb_playback/smb_list_directory
    // (credenciais como parametros separados, nunca uma URI unica cruzando
    // o JNI). Ver rust/bridge/src/lib.rs.
    extern void start_ftp_playback(const char* host, int32_t port, const char* path,
                                    const char* username, const char* password);
    extern char* ftp_list_directory(const char* host, int32_t port, const char* username,
                                     const char* password, const char* path);

    // T6.4: SFTP — mesmo padrao, com `private_key` (conteudo PEM, nao um
    // caminho de arquivo — ver rust/protocols/src/sftp/uri.rs) a mais.
    extern void start_sftp_playback(const char* host, int32_t port, const char* path,
                                     const char* username, const char* password,
                                     const char* private_key);
    extern char* sftp_list_directory(const char* host, int32_t port, const char* username,
                                      const char* password, const char* private_key, const char* path);

    // T1.4/T1.5/T2: modo de exibicao 3D (2D/SBS/OU/360/180) e swap-eyes —
    // estado de apresentacao puro, vive como atomics no bridge Rust (ver
    // rust/bridge/src/lib.rs para a codificacao numerica exata, que
    // `enum class ScreenMode` abaixo espelha 1:1).
    extern uint32_t cycle_3d_mode();
    extern uint32_t get_3d_mode();
    extern void set_3d_mode(uint32_t mode);
    extern uint32_t toggle_swap_eyes();
    extern uint32_t get_swap_eyes();

    // Bug de auto-hide durante digitacao no teclado nativo — ver comentario
    // completo em rust/bridge/src/lib.rs junto de KEYBOARD_ACTIVE.
    extern void set_keyboard_active(uint32_t active);
    extern uint32_t get_keyboard_active();
}

// Debug: dump do olho esquerdo pra PPM sob demanda (ver nativeRequestFrameCapture)
// — screencap/screenrecord nao capturam vr_only (compositor OpenXR, sem layer 2D).
static std::atomic<bool> g_captureRequested{false};
static std::string g_capturePath;
static std::mutex g_capturePathMutex;

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeRequestFrameCapture(JNIEnv* env, jobject thiz, jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_capturePathMutex);
        g_capturePath = pathStr;
    }
    env->ReleaseStringUTFChars(path, pathStr);
    g_captureRequested = true;
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

// Kotlin faz polling disto (ver autoPlayHandler em VRActivity.kt) pra mostrar um Toast quando
// controller.load() falha (ex.: codec nao suportado) — antes o erro so ia pro logcat.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeTakeLastPlaybackError(JNIEnv* env, jobject thiz) {
    char* rustStr = take_last_playback_error();
    if (!rustStr) {
        return nullptr;
    }
    jstring result = env->NewStringUTF(rustStr);
    free_rust_string(rustStr);
    return result;
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

// T1.4: botao "🧊" do painel de controles — avanca o ciclo de modo 3D e
// retorna o novo valor (0..6, ver ScreenMode/rust/bridge/src/lib.rs) pra o
// Kotlin atualizar o texto do botao sem precisar de uma segunda chamada.
extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeCycle3DMode(JNIEnv* env, jobject thiz) {
    return (jint)cycle_3d_mode();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeGet3DMode(JNIEnv* env, jobject thiz) {
    return (jint)get_3d_mode();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetScreenMode(JNIEnv* env, jobject thiz, jint mode) {
    set_3d_mode((uint32_t)mode);
}

// T1.5: botao "👁 Swap eyes" — inverte qual metade do frame SBS/OU (flat ou
// esferico) vai pro olho esquerdo/direito (ver uSwapEyes nos shaders,
// SessionInit()).
extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeToggleSwapEyes(JNIEnv* env, jobject thiz) {
    return (jint)toggle_swap_eyes();
}

// Bug de auto-hide durante digitacao — ver KEYBOARD_ACTIVE em
// rust/bridge/src/lib.rs. Chamado por VRActivity.showNativeKeyboardFor/
// hideNativeKeyboard exatamente quando o teclado nativo abre/fecha.
extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetKeyboardActive(JNIEnv* env, jobject thiz, jboolean active) {
    set_keyboard_active(active ? 1 : 0);
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

// T6.4: playback FTP. Ver nota acima de start_smb_playback sobre por que as
// credenciais vao como parametros separados, nao uma URI.
extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativePlayFtp(JNIEnv* env, jobject thiz, jstring host, jint port,
                                            jstring path, jstring username, jstring password) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);

    start_ftp_playback(hostStr, (int32_t)port, pathStr, userStr, passStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(path, pathStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
}

// T6.1/T6.4: navega um diretorio num servidor FTP. BLOQUEANTE (I/O de rede
// sincrono do lado Rust) — o Kotlin so deve chamar isto de uma coroutine em
// Dispatchers.IO, nunca da UI thread.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeFtpListDirectory(JNIEnv* env, jobject thiz, jstring host, jint port,
                                                     jstring username, jstring password, jstring path) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);

    char* result = ftp_list_directory(hostStr, (int32_t)port, userStr, passStr, pathStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(path, pathStr);

    return RustStringToJStringAndFree(env, result);
}

// T6.4: playback SFTP. `privateKey` pode ser uma jstring vazia (nao null,
// para evitar checagem de null extra do lado Kotlin) quando a autenticacao
// e por senha — `start_sftp_playback` (Rust) ja trata string vazia como
// "sem chave" (ver cstr_to_string + filter(!empty) em rust/bridge/src/lib.rs).
extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativePlaySftp(JNIEnv* env, jobject thiz, jstring host, jint port,
                                             jstring path, jstring username, jstring password,
                                             jstring privateKey) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* keyStr = env->GetStringUTFChars(privateKey, nullptr);

    start_sftp_playback(hostStr, (int32_t)port, pathStr, userStr, passStr, keyStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(path, pathStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(privateKey, keyStr);
}

// T6.2/T6.4: navega um diretorio num servidor SFTP. BLOQUEANTE, mesma
// ressalva de nativeFtpListDirectory.
extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeSftpListDirectory(JNIEnv* env, jobject thiz, jstring host, jint port,
                                                      jstring username, jstring password,
                                                      jstring privateKey, jstring path) {
    const char* hostStr = env->GetStringUTFChars(host, nullptr);
    const char* userStr = env->GetStringUTFChars(username, nullptr);
    const char* passStr = env->GetStringUTFChars(password, nullptr);
    const char* keyStr = env->GetStringUTFChars(privateKey, nullptr);
    const char* pathStr = env->GetStringUTFChars(path, nullptr);

    char* result = sftp_list_directory(hostStr, (int32_t)port, userStr, passStr, keyStr, pathStr);

    env->ReleaseStringUTFChars(host, hostStr);
    env->ReleaseStringUTFChars(username, userStr);
    env->ReleaseStringUTFChars(password, passStr);
    env->ReleaseStringUTFChars(privateKey, keyStr);
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

// T1.4/T2: espelha 1:1 a codificacao numerica de rust/bridge/src/lib.rs
// (SCREEN_MODE/cycle_3d_mode) — qualquer mudanca aqui exige a mudanca
// correspondente la (e vice-versa), os dois lados nao compartilham um tipo.
enum class ScreenMode : uint32_t {
    Flat2D = 0,
    SBS = 1,
    SBSHalf = 2,
    OU = 3,
    OUHalf = 4,
    Sphere360 = 5,
    Sphere180 = 6,
    Sphere360SBS = 7,
    Sphere360OU = 8,
    Vr180SBS = 9,
};

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

    void AppHandleEvent(XrEventDataBaseHeader* baseEventHeader) override {
        if (baseEventHeader->type == XR_TYPE_EVENT_DATA_REFERENCE_SPACE_CHANGE_PENDING) {
            // Em vez de processar imediatamente com o HeadPose desatualizado,
            // agendamos o recenter para rodar com o HeadPose fresco no proximo Update()
            m_needsOsRecenter = true;
            LOGI("VRPlayerApp: OS Recenter event recebido. Agendando reset para o proximo frame.");
        }
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

    // T2: todo modo que desenha a esfera (mono OU estereo) em vez do quad
    // plano — usado tanto pro dispatch de desenho (Render()) quanto pro gate
    // do recenter (T4.3, so faz sentido com a esfera ativa).
    static bool IsSphereMode(ScreenMode mode) {
        switch (mode) {
            case ScreenMode::Sphere360:
            case ScreenMode::Sphere180:
            case ScreenMode::Sphere360SBS:
            case ScreenMode::Sphere360OU:
            case ScreenMode::Vr180SBS:
                return true;
            default:
                return false;
        }
    }

    // T1/T2.4/T2.5: traduz o ScreenMode atual pros uniforms que os dois
    // programas (m_stereoFlatProgram/m_sphereProgram) de fato leem. Separado
    // num metodo em vez de inline em Update() so pra nao duplicar este switch
    // entre Update() (recenter gate) e Render() (dispatch de desenho) — ver
    // chamadas em ambos.
    void UpdateScreenModeUniforms() {
        m_swapEyesF = (get_swap_eyes() != 0) ? 1.0f : 0.0f;

        switch (m_screenMode) {
            case ScreenMode::Sphere180:
            case ScreenMode::Vr180SBS:
                m_uPolar180 = 1.0f;
                break;
            default:
                m_uPolar180 = 0.0f;
                break;
        }

        switch (m_screenMode) {
            case ScreenMode::Sphere360SBS:
            case ScreenMode::Vr180SBS:
                m_sphereStereoLayout = 1.0f; // SBS
                break;
            case ScreenMode::Sphere360OU:
                m_sphereStereoLayout = 2.0f; // OU
                break;
            default:
                m_sphereStereoLayout = 0.0f; // mono
                break;
        }

        switch (m_screenMode) {
            case ScreenMode::SBS:
            case ScreenMode::SBSHalf:
                m_flatStereoLayout = 1.0f; // SBS
                break;
            case ScreenMode::OU:
            case ScreenMode::OUHalf:
                m_flatStereoLayout = 2.0f; // OU
                break;
            default:
                m_flatStereoLayout = 0.0f; // nao usado (Flat2D usa m_program, nao m_stereoFlatProgram)
                break;
        }
    }

    // T1: SBS/OU (flat, nao esferico) — mesma logica de IsSphereMode acima.
    static bool IsFlatStereoMode(ScreenMode mode) {
        switch (mode) {
            case ScreenMode::SBS:
            case ScreenMode::SBSHalf:
            case ScreenMode::OU:
            case ScreenMode::OUHalf:
                return true;
            default:
                return false;
        }
    }

    virtual bool AppInit(const xrJava* context) override {
        LOGI("VRPlayerApp::AppInit");
        return true;
    }

    // Sem isso, o decoder HW continua ocupado em segundo plano ao trocar de app no Quest.
    // So STOPPING pausa: VISIBLE e normal (guardian, notificacao, dashboard piscando) e
    // pausava o video toda hora, sentido pelo usuario como travada (achado em teste real).
    virtual void SessionStateChanged(XrSessionState state) override {
        if (state == XR_SESSION_STATE_FOCUSED) {
            on_app_focus_gained();
        } else if (state == XR_SESSION_STATE_STOPPING) {
            on_app_focus_lost();
        }
    }

    virtual void AppRenderEye(const OVRFW::ovrApplFrameIn& in, OVRFW::ovrRendererOutput& out, int eye) override {
        OVRFW::XrApp::AppRenderEye(in, out, eye);
        // Captura os DOIS olhos (nao so eye 0) — pra comparar resolucao/qualidade entre
        // eles precisa dos dois arquivos da MESMA reproducao. So limpa g_captureRequested
        // depois do ultimo olho (eye 1), senao o olho 1 nunca seria capturado.
        if (g_captureRequested.load()) {
            GLint viewport[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            int w = viewport[2], h = viewport[3];
            std::vector<uint8_t> pixels((size_t)w * h * 4);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
            std::string basePath;
            { std::lock_guard<std::mutex> lock(g_capturePathMutex); basePath = g_capturePath; }
            std::string path = basePath + (eye == 0 ? ".left.ppm" : ".right.ppm");
            FILE* f = fopen(path.c_str(), "wb");
            if (f) {
                fprintf(f, "P6\n%d %d\n255\n", w, h);
                // glReadPixels vem de baixo pra cima; PPM espera de cima pra baixo.
                for (int y = h - 1; y >= 0; y--) {
                    for (int x = 0; x < w; x++) {
                        fwrite(&pixels[(size_t)(y * w + x) * 4], 1, 3, f);
                    }
                }
                fclose(f);
                LOGI("VRPlayerApp: frame capturado em %s (%dx%d)", path.c_str(), w, h);
            }
            if (eye == 1) g_captureRequested = false;
        }
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

        // FLAG: liga/desliga o sharpen adaptativo (CAS) nos shaders de video estereo/esfera.
        // Pesquisado a pedido do usuario apos comparacao com outro player (4XVR) — formula
        // portada do AMD FidelityFX CAS simplificado (4 vizinhos, sem upsampling/FP16), ver
        // https://gpuopen.com/fidelityfx-cas/. Sharpen adapta a forca por contraste local
        // (min/max da vizinhanca), diferente do unsharp mask de peso fixo testado antes.
        constexpr bool kSharpenEnabled = true;
        const std::string fDirectiveSharpen = std::string(fDirective) +
            "#define SHARPEN_ENABLED " + (kSharpenEnabled ? "1" : "0") + "\n";
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

        // ------------------ INITIALIZE STEREO FLAT QUAD (T1.1/T1.2/T1.5) ------------------
        // Programa SEPARADO de m_program (que continua servindo o quad 2D/
        // mono E os paineis de UI/controles, que NUNCA devem ser recortados
        // por olho). A descoberta que destrava isto: mesmo sem
        // GL_OVR_multiview2 (nao usado neste app — ver ScreenMode/
        // TransformVertex), o framework OVRFW ja seta um uniform inteiro
        // `ViewID` (0=esquerdo, 1=direito) em TODO draw call, uma vez por
        // olho, automaticamente — e injeta a macro `VIEW_ID` que resolve pra
        // ele (ver `TransformVertex` no header gerado por
        // OVRFW::GlProgram::Build). So precisa ser repassado do vertex pro
        // fragment shader via varying (o framework so declara isso no vertex
        // header, nao no fragment).
        const char* stereoFlatVertexShader = R"(
            in vec3 Position;
            in vec2 TexCoord;
            out vec2 vTexCoord;
            flat out int vEye;
            void main() {
                gl_Position = TransformVertex(vec4(Position, 1.0));
                vTexCoord = TexCoord;
                vEye = int(VIEW_ID);
            }
        )";

        // T1.1/T1.2: uStereoLayout escolhe SBS (recorta em X) ou OU (recorta
        // em Y) — convencao identica ao sample GLSL do doc (secao 1): olho
        // esquerdo (view 0) recebe a METADE ESQUERDA (SBS) ou SUPERIOR (OU)
        // do frame. uSwapEyes (T1.5) inverte qual metade cada olho recebe —
        // cuidado do doc (CAUTION "Olho trocado causa nausea"): o botao
        // dedicado pra isto (ver VRControlsPresentation.kt) precisa ficar
        // facil de achar justamente por causa disto.
        const char* stereoFlatFragmentShader = R"(
            in vec2 vTexCoord;
            flat in int vEye;
            out vec4 FragColor;
            uniform samplerExternalOES sTexture;
            uniform float uStereoLayout; // 1 = SBS, 2 = OU
            uniform float uSwapEyes;
            uniform float uSharpness; // 0..1, ver SHARPEN_ENABLED/kSharpenEnabled
            void main() {
                vec2 uv = vTexCoord;
                int eye = vEye;
                if (uSwapEyes > 0.5) {
                    eye = 1 - eye;
                }
                if (uStereoLayout > 1.5) {
                    uv.y = uv.y * 0.5 + float(eye) * 0.5;
                } else {
                    uv.x = uv.x * 0.5 + float(eye) * 0.5;
                }
#if SHARPEN_ENABLED
                // CAS (AMD FidelityFX Contrast Adaptive Sharpening, simplificado) — forca se
                // adapta ao contraste local (min/max da vizinhanca) em vez de peso fixo, entao
                // areas planas/ruido sao menos afetadas que bordas reais. uSharpness em [0,1].
                vec2 texel = 1.0 / vec2(textureSize(sTexture, 0));
                vec3 c = texture(sTexture, uv).rgb;
                vec3 n = texture(sTexture, uv + vec2(0.0, texel.y)).rgb;
                vec3 s = texture(sTexture, uv - vec2(0.0, texel.y)).rgb;
                vec3 e = texture(sTexture, uv + vec2(texel.x, 0.0)).rgb;
                vec3 w = texture(sTexture, uv - vec2(texel.x, 0.0)).rgb;
                vec3 mn = min(c, min(min(n, s), min(e, w)));
                vec3 mx = max(c, max(max(n, s), max(e, w)));
                vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / mx, 0.0, 1.0));
                float peak = -mix(8.0, 5.0, uSharpness);
                vec3 wgt = amp / peak;
                vec3 sharpened = ((n + s + e + w) * wgt + c) / (1.0 + 4.0 * wgt);
                FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
#else
                FragColor = vec4(texture(sTexture, uv).rgb, 1.0);
#endif
            }
        )";

        OVRFW::ovrProgramParm stereoFlatParms[] = {
            {"sTexture", OVRFW::ovrProgramParmType::TEXTURE_SAMPLED},
            {"uStereoLayout", OVRFW::ovrProgramParmType::FLOAT},
            {"uSwapEyes", OVRFW::ovrProgramParmType::FLOAT},
            {"uSharpness", OVRFW::ovrProgramParmType::FLOAT},
        };
        m_stereoFlatProgram = OVRFW::GlProgram::Build(vDirective, stereoFlatVertexShader, fDirectiveSharpen.c_str(), stereoFlatFragmentShader, stereoFlatParms, 4);

        m_stereoFlatSurfaceDef.geo = OVRFW::BuildTesselatedQuad(2, 2, false);
        m_stereoFlatSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
        m_stereoFlatSurfaceDef.graphicsCommand.Program = m_stereoFlatProgram;
        m_stereoFlatSurfaceDef.graphicsCommand.UniformData[1].Data = &m_flatStereoLayout;
        m_stereoFlatSurfaceDef.graphicsCommand.UniformData[2].Data = &m_swapEyesF;
        m_stereoFlatSurfaceDef.graphicsCommand.UniformData[3].Data = &m_sharpness;

        // ------------------ INITIALIZE 360/180 SPHERE (T2) ------------------
        // Mesmo vertex shader do quad plano (so faz TransformVertex + repassa
        // UV) — a diferenca toda esta na GEOMETRIA (globo em vez de quad) e
        // no fragment shader (que sabe recortar o hemisferio frontal pra
        // 180). `BuildGlobe` (Render/GlGeometry.h) ja gera a malha exatamente
        // no formato que T2.1 pede: mapeamento UV equirectangular padrao,
        // resolucao 128x~70 (bem acima do minimo de 64x32 do doc, com linhas
        // extras nos polos pra reduzir artefatos de triangulos degenerados),
        // e normals apontando pra FORA (`position.Normalized()`, comentario
        // "Build it with the equirect center down -Z" no SDK). Nao precisamos
        // inverter as normals nem reconstruir a malha a mao: o CAUTION do doc
        // (secao 2) sobre "normals pra dentro" e resolvido de forma
        // equivalente desabilitando backface culling abaixo — com culling
        // desligado, a face vista de dentro da esfera renderiza
        // independente de winding/normal, sem inverter nada.
        const char* sphereVertexShader = R"(
            in vec3 Position;
            in vec2 TexCoord;
            out vec2 vTexCoord;
            flat out int vEye;
            void main() {
                gl_Position = TransformVertex(vec4(Position, 1.0));
                vTexCoord = TexCoord;
                vEye = int(VIEW_ID);
            }
        )";

        // T2.3: 180 reaproveita a MESMA esfera de 360 (opcao explicitamente
        // aceita pelo doc: "esfera completa com UV mapping limitado a 180°
        // horizontal") em vez de uma malha de semi-esfera separada. `BuildGlobe`
        // centra o texel U=0.5 em -Z (frente do usuario) e mapeia U linear
        // sobre os 360° completos; logo o hemisferio frontal (180° de longitude)
        // corresponde exatamente a U em [0.25, 0.75]. Fora dessa faixa (o
        // hemisferio de TRAS, que o video VR180 nao cobre) descarta o
        // fragmento com `discard` em vez de desenhar preto — GPU nao gasta
        // bandwidth de textura ali, e evita qualquer wrap/bleed visivel na
        // borda U=0/U=1 da malha.
        // (reaproveita `fDirective`/`vDirective` ja declarados acima para o
        // programa do quad plano — mesmo extension GLSL, sem motivo pra
        // duplicar a string.)
        // T2.4/T2.5: uStereoLayout (0=mono, 1=SBS, 2=OU) recorta por olho DEPOIS
        // do recorte polar — a ordem importa. `uv` comeca como o parametro de
        // longitude da GEOMETRIA (0..1 ao redor da esfera INTEIRA, sempre no
        // range original de vTexCoord.x); o recorte 180 (se ativo) reduz isso
        // pro hemisferio frontal, em coordenadas 0..1 relativas a esse
        // hemisferio. SO DEPOIS disso o recorte de olho reescala esse
        // resultado (360 completo OU so o hemisferio frontal) pra dentro da
        // METADE do frame FISICO que pertence a este olho — o que modela
        // corretamente um frame estereo empacotado como [olho-esq | olho-dir]
        // onde cada metade e uma copia INDEPENDENTE e completa do conteudo
        // logico (360 ou 180). Pra Vr180SBS isso assume que cada metade do
        // frame fisico segue a MESMA convencao "conteudo 180 centrado num
        // canvas logico de largura 360" que o caso mono 180 ja usa — formatos
        // VR180 reais variam bastante na pratica (equirect vs fisheye
        // double-circle); nunca validado contra um arquivo VR180 SBS real
        // nesta sessao, so raciocinado a partir do doc (secao 2, T2.3/T2.4).
        const char* sphereFragmentShader = R"(
            in vec2 vTexCoord;
            flat in int vEye;
            out vec4 FragColor;
            uniform samplerExternalOES sTexture;
            uniform float uPolar180;
            uniform float uStereoLayout; // 0 = mono, 1 = SBS, 2 = OU
            uniform float uSwapEyes;
            uniform float uSharpness; // 0..1, ver SHARPEN_ENABLED/kSharpenEnabled
            void main() {
                vec2 uv = vTexCoord;
                if (uPolar180 > 0.5) {
                    if (uv.x < 0.25 || uv.x > 0.75) {
                        discard;
                    }
                    uv.x = (uv.x - 0.25) * 2.0;
                }
                int eye = vEye;
                if (uSwapEyes > 0.5) {
                    eye = 1 - eye;
                }
                if (uStereoLayout > 1.5) {
                    uv.y = uv.y * 0.5 + float(eye) * 0.5;
                } else if (uStereoLayout > 0.5) {
                    uv.x = uv.x * 0.5 + float(eye) * 0.5;
                }
#if SHARPEN_ENABLED
                // CAS (AMD FidelityFX Contrast Adaptive Sharpening, simplificado) — ver
                // stereoFlatFragmentShader acima pro comentario completo da formula.
                vec2 texel = 1.0 / vec2(textureSize(sTexture, 0));
                vec3 c = texture(sTexture, uv).rgb;
                vec3 n = texture(sTexture, uv + vec2(0.0, texel.y)).rgb;
                vec3 s = texture(sTexture, uv - vec2(0.0, texel.y)).rgb;
                vec3 e = texture(sTexture, uv + vec2(texel.x, 0.0)).rgb;
                vec3 w = texture(sTexture, uv - vec2(texel.x, 0.0)).rgb;
                vec3 mn = min(c, min(min(n, s), min(e, w)));
                vec3 mx = max(c, max(max(n, s), max(e, w)));
                vec3 amp = sqrt(clamp(min(mn, 2.0 - mx) / mx, 0.0, 1.0));
                float peak = -mix(8.0, 5.0, uSharpness);
                vec3 wgt = amp / peak;
                vec3 sharpened = ((n + s + e + w) * wgt + c) / (1.0 + 4.0 * wgt);
                FragColor = vec4(clamp(sharpened, 0.0, 1.0), 1.0);
#else
                FragColor = vec4(texture(sTexture, uv).rgb, 1.0);
#endif
            }
        )";

        OVRFW::ovrProgramParm sphereParms[] = {
            {"sTexture", OVRFW::ovrProgramParmType::TEXTURE_SAMPLED},
            {"uPolar180", OVRFW::ovrProgramParmType::FLOAT},
            {"uStereoLayout", OVRFW::ovrProgramParmType::FLOAT},
            {"uSwapEyes", OVRFW::ovrProgramParmType::FLOAT},
            {"uSharpness", OVRFW::ovrProgramParmType::FLOAT},
        };
        m_sphereProgram = OVRFW::GlProgram::Build(vDirective, sphereVertexShader, fDirectiveSharpen.c_str(), sphereFragmentShader, sphereParms, 5);

        m_sphereSurfaceDef.geo = OVRFW::BuildGlobe(1.0f, 1.0f, kSphereRadius);
        m_sphereSurfaceDef.graphicsCommand.Textures[0].target = GL_TEXTURE_EXTERNAL_OES;
        m_sphereSurfaceDef.graphicsCommand.Program = m_sphereProgram;
        m_sphereSurfaceDef.graphicsCommand.UniformData[1].Data = &m_uPolar180;
        m_sphereSurfaceDef.graphicsCommand.UniformData[2].Data = &m_sphereStereoLayout;
        m_sphereSurfaceDef.graphicsCommand.UniformData[3].Data = &m_swapEyesF;
        m_sphereSurfaceDef.graphicsCommand.UniformData[4].Data = &m_sharpness;
        // T2 CAUTION/IMPORTANT do doc: sem culling (camera fica DENTRO da
        // esfera — ver comentario acima sobre normals) e sem depth test/write
        // (a esfera esta "infinitamente longe", nao deve interagir com o
        // depth de paineis de UI que ja sao renderizados sem depth tambem).
        m_sphereSurfaceDef.graphicsCommand.GpuState.cullEnable = false;
        m_sphereSurfaceDef.graphicsCommand.GpuState.depthEnable = false;
        m_sphereSurfaceDef.graphicsCommand.GpuState.depthMaskEnable = false;

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
            1024, 768, AIMAGE_FORMAT_RGBA_8888,
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
                        env->CallStaticVoidMethod(vrActivityClass, setupMethod, java->ActivityObject, surfaceObj, 1024, 768);
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
            1024, 384, AIMAGE_FORMAT_RGBA_8888,
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
                        env->CallStaticVoidMethod(vrActivityClass, setupMethod, java->ActivityObject, surfaceObj, 1024, 384);
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
        // Se o OS aplicou um recenter, calibra a altura da UI para os olhos na nova origem
        if (m_needsOsRecenter) {
            OVR::Vector3f fwd = in.HeadPose.Rotation.Rotate(OVR::Vector3f(0.0f, 0.0f, -1.0f));
            m_sceneYawOffset = atan2f(fwd.x, -fwd.z);
            m_sceneTranslationOffset = in.HeadPose.Translation;
            m_sceneTranslationOffset.y = in.HeadPose.Translation.y - 1.5f;
            m_needsOsRecenter = false;
        }

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
        bool useLeft = in.LeftRemoteTracked && (!in.RightRemoteTracked || in.LeftRemoteIndexTrigger > 0.1f || in.LeftRemoteIndexClick);
        OVR::Vector3f rayOrigin = useLeft ? in.LeftRemotePointPose.Translation : in.RightRemotePointPose.Translation;
        OVR::Vector3f rawRayDir = (useLeft ? in.LeftRemotePointPose.Rotation : in.RightRemotePointPose.Rotation) * OVR::Vector3f(0.0f, 0.0f, -1.0f);

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
        OVR::Vector3f baseUiPos(-2.2f, 1.5f, -1.5f);
        OVR::Vector3f uiPos = m_sceneTranslationOffset + OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(baseUiPos);
        OVR::Vector3f toHead = in.HeadPose.Translation - uiPos;
        toHead.y = 0.0f;
        float toHeadLen = sqrtf(toHead.x * toHead.x + toHead.z * toHead.z);
        float uiYaw = (toHeadLen > 1e-4f) ? atan2f(toHead.x / toHeadLen, toHead.z / toHeadLen) : 0.7f;
        m_uiTransform = OVR::Matrix4f::Translation(uiPos) * OVR::Matrix4f::RotationY(uiYaw) * OVR::Matrix4f::Scaling(0.8f, 0.6f, 1.0f);
        OVR::Vector3f uiPlaneCenter = uiPos;
        OVR::Vector3f uiPlaneNormal = OVR::Matrix4f::RotationY(uiYaw).Transform(OVR::Vector3f(0, 0, 1));

        // Painel de controles: fica logo abaixo da tela, com leve inclinacao fixa.
        OVR::Vector3f baseControlsPos(0.0f, 0.4f, -1.9f);
        OVR::Vector3f worldControlsPos = m_sceneTranslationOffset + OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(baseControlsPos);
        m_controlsTransform = OVR::Matrix4f::Translation(worldControlsPos) * OVR::Matrix4f::RotationY(m_sceneYawOffset) * OVR::Matrix4f::RotationX(-0.3f) * OVR::Matrix4f::Scaling(0.8f, 0.3f, 1.0f);
        OVR::Vector3f cPlaneCenter = worldControlsPos;
        OVR::Vector3f cPlaneNormal = OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(OVR::Matrix4f::RotationX(-0.3f).Transform(OVR::Vector3f(0, 0, 1)));

        // T1.4/T2: polling barato do modo 3D (o Rust bridge e quem guarda o
        // estado real — ver cycle_3d_mode/get_3d_mode). Os uniforms derivados
        // do modo (uPolar180/uStereoLayout/uSwapEyes pros dois programas) sao
        // recalculados aqui a cada frame em vez de so na troca de modo,
        // porque e mais simples que adicionar um segundo caminho de callback
        // so pra isso — o custo e irrelevante (uns poucos ifs).
        m_screenMode = static_cast<ScreenMode>(get_3d_mode());
        UpdateScreenModeUniforms();

        // T2.6: a esfera acompanha a TRANSLACAO da cabeca (o usuario sempre
        // fica no centro dela) mas NAO a rotacao — a rotacao "de olhar ao
        // redor" ja acontece de graca via viewProjection[eye] em
        // TransformVertex, igual pra qualquer outro objeto da cena (mesmo
        // motivo pelo qual T4.1-T4.4 nunca precisaram ler orientacao de
        // cabeca manualmente: OVRFW ja aplica isso no pipeline padrao).
        // Ignorar a rotacao da cabeca aqui e o que T4.4 pede: conteudo 360
        // nao tem paralaxe, entao so a translacao (pra manter o usuario
        // "dentro" da esfera mesmo andando fisicamente no espaco do
        // Guardian) importa. `m_sceneYawOffset` e o unico ajuste manual de
        // rotacao, aplicado so pelo recenter (T4.3, abaixo).
        m_sphereTransform = OVR::Matrix4f::Translation(in.HeadPose.Translation) * OVR::Matrix4f::RotationY(m_sceneYawOffset);

        // Tela de video (usada apenas para detectar "apontando para a tela" -> mostra controles)
        OVR::Vector3f worldScreenPos = m_sceneTranslationOffset + OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(m_screenPosition);
        OVR::Matrix4f screenTransform = OVR::Matrix4f::Translation(worldScreenPos) * OVR::Matrix4f::RotationY(m_sceneYawOffset) * OVR::Matrix4f::Scaling(m_screenScale.x, m_screenScale.y, 1.0f);

        OVR::Vector3f pointerEnd = rayOrigin + rayDir * 10.0f;

        static float lastUvX = 0.0f;
        static float lastUvY = 0.0f;
        static int activePanel = 0; // 0=None, 1=FileBrowser (Home/Arquivos/Rede/Player), 2=Controls
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

        float screenU = 0.0f, screenV = 0.0f;
        OVR::Vector3f screenNormal = OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(OVR::Vector3f(0, 0, 1));
        bool hitScreen = rayHitsQuad(screenTransform, screenNormal, worldScreenPos, screenU, screenV) > 0.0f;

        // --- T4.3: auto-hide dos paineis ---
        // Aponta pro File Browser (Home/Arquivos/Rede/Player, tudo no mesmo quad
        // desde a Fase 2) -> mantem ele visivel. Aponta pra tela ou pros
        // controles -> mantem os controles visiveis. Sem atividade por 5s -> fade
        // out. Com a fusao dos paineis Local/Rede num so quad, so sobram estes 2
        // paineis de UI (fora a tela de video) — o timing de auto-hide abaixo
        // (kUiAutoHideSeconds/kUiFadeDuration) nao mudou, mas vale re-validar em
        // headset fisico agora que ha um painel a menos disputando atencao.
        //
        // Bug reportado em validacao real: com o teclado nativo aberto
        // (VRActivity.showNativeKeyboardFor), o raio do controller aponta pro
        // teclado (overlay do sistema, fora deste app), nao mais pro quad do
        // painel — sem a checagem de `get_keyboard_active()` abaixo, isso
        // contava como "usuario inativo" e o painel "Adicionar servidor"
        // (ou qualquer outro com EditText focado) desaparecia no meio da
        // digitacao. Suprime o auto-hide inteiro (nao so o do painel Home —
        // um EditText SEMPRE vive dentro dele, nunca no painel de controles,
        // mas manter os dois presos aqui e mais simples que decidir qual dos
        // dois "e o painel certo" a cada chamada) enquanto o teclado estiver
        // ativo.
        bool keyboardActive = get_keyboard_active() != 0;
        if (currentHitPanel == 1 || keyboardActive) {
            m_uiIdleTime = 0.0f;
        } else {
            m_uiIdleTime += in.DeltaSeconds;
        }
        if (currentHitPanel == 2 || hitScreen) {
            m_controlsIdleTime = 0.0f;
        } else {
            m_controlsIdleTime += in.DeltaSeconds;
        }
        float uiTargetAlpha = (m_uiIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
        float controlsTargetAlpha = (m_controlsIdleTime < kUiAutoHideSeconds) ? 1.0f : 0.0f;
        float fadeStep = in.DeltaSeconds / kUiFadeDuration;
        m_uiAlpha = MoveTowards(m_uiAlpha, uiTargetAlpha, fadeStep);
        m_controlsAlpha = MoveTowards(m_controlsAlpha, controlsTargetAlpha, fadeStep);

        // So despacha toque/hover para um painel que esteja de fato visivel (evita
        // "clique invisivel" em um painel escondido pelo auto-hide); a deteccao
        // geometrica acima continua sempre ativa para poder trazer o painel de volta.
        bool uiVisible = m_uiAlpha > 0.5f;
        bool controlsVisible = m_controlsAlpha > 0.5f;
        int dispatchHitPanel = 0;
        if (currentHitPanel == 1 && uiVisible) {
            dispatchHitPanel = 1;
        } else if (currentHitPanel == 2 && controlsVisible) {
            dispatchHitPanel = 2;
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
                // Fase 2: o painel de rede nao tem mais quad/dispatch proprio —
                // sua UI agora mora dentro do mesmo quad do Home/File Browser
                // (activePanel == 1), entao todo toque nesse painel (incluindo
                // as telas de Rede) roteia por "dispatchVRTouch", igual antes.
                const char* methodName = (activePanel == 1) ? "dispatchVRTouch" : "dispatchControlsVRTouch";
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

        // Cursor/reticle no ponto de acerto: o laser sozinho termina "no
        // vazio", o que dificulta mirar em botoes pequenos com precisao
        // (feedback de usuario em validacao real). `ovrBeamRenderer` (a
        // classe ja usada acima pro laser) nao tem uma API de "ponto"
        // dedicada, e uma 1a tentativa de simular um disco com UM segmento
        // curto ficou quase invisivel: o fragment shader parametrico do
        // beam (BeamRenderer.cpp, sem atlas de textura — o overload que
        // usamos) SEMPRE desvanece a opacidade de 1.0 no `StartPos` (UV.y=0)
        // ate 0.0 no `EndPos` (UV.y=1) — e o efeito de "laser sumindo ao
        // longe", mas aplicado a um segmento de 1cm isso rendeia metade
        // dele quase transparente, nao um disco solido. Correcao: DOIS
        // segmentos que COMPARTILHAM o mesmo StartPos (exatamente
        // `pointerEnd`, onde a opacidade e sempre 1.0 pelos dois), cada um
        // se estendendo um pouco pra um lado (ver `headUp` abaixo pro eixo
        // escolhido e por que nao pode ser `rayDir`) — a uniao das duas
        // metades opacas cobre o ponto de acerto de forma simetrica em vez
        // de so um lado dele. So desenhado quando o raio esta de fato sobre
        // um painel visivel e interativo (`dispatchHitPanel`).
        if (m_cursorDotHandle != OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE) {
            m_beamRenderer.RemoveBeam(m_cursorDotHandle);
            m_cursorDotHandle = OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE;
        }
        if (m_cursorDotHandle2 != OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE) {
            m_beamRenderer.RemoveBeam(m_cursorDotHandle2);
            m_cursorDotHandle2 = OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE;
        }
        if (dispatchHitPanel != 0) {
            // Vira "risco" fino em vez de disco quando o segmento aponta
            // quase na mesma direcao que a camera olha pra ele — o
            // billboard do BeamRenderer calcula a largura como
            // `beamDir.Cross(viewToCenter)` (ver BeamRenderer.cpp, Frame()),
            // e produto vetorial de dois vetores quase PARALELOS tende a
            // zero. `rayDir` (direcao do laser) e quase sempre proximo da
            // direcao em que a cabeca esta olhando (o usuario olha pra onde
            // aponta) — exatamente o caso degenerado. Usar o "para cima" da
            // cabeca como eixo dos 2 mini-segmentos em vez de `rayDir`
            // evita isso: `headUp` e sempre ~perpendicular a direcao de
            // visao por construcao, entao o produto vetorial nunca colapsa
            // no uso normal (so degeneraria se o usuario estivesse olhando
            // reto pra cima/baixo ao longo do proprio eixo up da cabeca pro
            // alvo, caso extremo que nao ocorre apontando pra um painel a
            // frente).
            OVR::Vector3f headUp = in.HeadPose.Rotation.Rotate(OVR::Vector3f(0.0f, 1.0f, 0.0f));
            const float kDotWidth = 0.02f;
            const float kDotHalfLength = 0.001f;
            OVR::Vector3f nearEnd = pointerEnd - headUp * kDotHalfLength;
            OVR::Vector3f farEnd = pointerEnd + headUp * kDotHalfLength;
            m_cursorDotHandle = m_beamRenderer.AddBeam(in, kDotWidth, pointerEnd, farEnd, OVR::Vector4f(0.0f, 1.0f, 1.0f, 1.0f));
            m_cursorDotHandle2 = m_beamRenderer.AddBeam(in, kDotWidth, pointerEnd, nearEnd, OVR::Vector4f(0.0f, 1.0f, 1.0f, 1.0f));
        }

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

        // Fase 2: o botao Menu (esquerdo) antes abria/fechava o quad de rede
        // dedicado (removido — Rede agora mora dentro do quad do Home). Decisao
        // de produto (ver relatorio da Fase 2): em vez de deixar Menu sem
        // binding, ele passa a fazer a MESMA coisa que B/Y (alterna a
        // visibilidade do quad unico do Home). E redundante com B/Y, mas
        // inofensivo — e evita "quebrar" um botao que usuarios podem ja ter o
        // habito de apertar para abrir alguma UI. Se isso se provar confuso em
        // uso real (headset fisico), a alternativa e nao dar bind nenhum a
        // Menu; ver TODO acima do dono do produto revisar.
        static bool prevMenu = false;
        bool currMenu = (in.AllButtons & OVRFW::ovrApplFrameIn::kButtonMenu) != 0;
        // T4.3: long-press no Menu = recenter (so faz sentido com a esfera
        // 360/180 ativa). Short-press continua fazendo o toggle de
        // visibilidade do painel Home, comportamento da Fase 2 documentado
        // acima — a unica mudanca e que agora isso so dispara no RELEASE, e
        // so se o long-press de recenter nao tiver disparado durante o hold.
        if (currMenu) {
            m_menuHoldTime += in.DeltaSeconds;
            if (!m_recenterFiredThisHold && m_menuHoldTime >= kRecenterHoldSeconds) {
                // Gira a cena inteira para que a direcao atual da cabeca vire a
                // nova "frente" do conteudo (T4.3: "util quando o conteudo
                // esta girado em relacao ao usuario"). Sinal do angulo nunca
                // validado em headset fisico (ver static constexpr
                // kRecenterHoldSeconds acima) — se o recenter girar pro lado
                // errado num teste real, e so inverter o sinal abaixo.
                OVR::Vector3f fwd = in.HeadPose.Rotation.Rotate(OVR::Vector3f(0.0f, 0.0f, -1.0f));
                m_sceneYawOffset = atan2f(fwd.x, -fwd.z);
                m_sceneTranslationOffset = in.HeadPose.Translation;
                m_sceneTranslationOffset.y = in.HeadPose.Translation.y - 1.5f; // Ajusta a altura da tela para os olhos
                m_recenterFiredThisHold = true;
                FireHaptic(RightHandPath, 0.5f, 30000000 /* 30ms */);
            }
        } else {
            if (prevMenu && !m_recenterFiredThisHold) {
                bool isCurrentlyVisible = m_uiIdleTime < kUiAutoHideSeconds;
                m_uiIdleTime = isCurrentlyVisible ? kUiAutoHideSeconds : 0.0f;
            }
            m_menuHoldTime = 0.0f;
            m_recenterFiredThisHold = false;
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
            if (eglDestroyImageKHR) {
                for (auto& entry : m_eglImageCache) eglDestroyImageKHR(eglGetCurrentDisplay(), entry.second);
            }
            m_eglImageCache.clear();
            m_eglImage = EGL_NO_IMAGE_KHR;
        } else if (buffer && buffer != m_lastBuffer) {
            m_lastBuffer = buffer;

            auto cached = m_eglImageCache.find(buffer);
            if (cached != m_eglImageCache.end()) {
                m_eglImage = cached->second;
            } else {
                EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffer);
                EGLint attribs[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
                m_eglImage = EGL_NO_IMAGE_KHR;
                if (eglCreateImageKHR) {
                    m_eglImage = eglCreateImageKHR(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attribs);
                    if (m_eglImage == EGL_NO_IMAGE_KHR) {
                        LOGI("VRPlayerApp: eglCreateImageKHR FAILED! Error: 0x%x", eglGetError());
                    }
                }
                if (m_eglImage != EGL_NO_IMAGE_KHR) {
                    if (m_eglImageCache.size() >= 6 && eglDestroyImageKHR) {
                        auto oldest = m_eglImageCache.begin();
                        eglDestroyImageKHR(eglGetCurrentDisplay(), oldest->second);
                        m_eglImageCache.erase(oldest);
                    }
                    m_eglImageCache[buffer] = m_eglImage;
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

        // T2.7: "o conteudo 360 E o ambiente" — a esfera e a tela plana sao
        // mutuamente exclusivas, nunca as duas ao mesmo tempo. Entre os 3
        // programas (mono, estereo-flat, esfera-mono-ou-estereo) so um e
        // desenhado por frame, escolhido pelo ScreenMode atual.
        bool sphereActive = IsSphereMode(m_screenMode);
        bool flatStereoActive = IsFlatStereoMode(m_screenMode);
        if (sphereActive) {
            // T2.4/T2.5: m_sphereProgram agora sabe recortar por olho
            // (uStereoLayout/uSwapEyes, setados em UpdateScreenModeUniforms)
            // — ver o fragment shader em SessionInit() pra convencao exata
            // de empacotamento (SBS/OU) e a ressalva honesta sobre Vr180SBS
            // nunca ter sido validado contra um arquivo real.
            m_sphereSurfaceDef.graphicsCommand.Textures[0].texture = m_textureId;
            m_sphereSurfaceDef.graphicsCommand.BindUniformTextures();
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(m_sphereTransform, &m_sphereSurfaceDef));
        } else if (flatStereoActive) {
            // T1.1/T1.2/T1.5: separacao real de olho pro quad SBS/OU (half e
            // full usam a MESMA matematica de UV — a diferenca entre half/full
            // e so a densidade de pixels por olho na fonte, nao afeta o corte
            // — ver T1.3 no doc). Mesma posicao/escala ajustavel do quad mono.
            m_stereoFlatSurfaceDef.graphicsCommand.Textures[0].texture = m_textureId;
            m_stereoFlatSurfaceDef.graphicsCommand.BindUniformTextures();
            OVR::Vector3f worldScreenPos = m_sceneTranslationOffset + OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(m_screenPosition);
            OVR::Matrix4f transform = OVR::Matrix4f::Translation(worldScreenPos) * OVR::Matrix4f::RotationY(m_sceneYawOffset) *
                OVR::Matrix4f::Scaling(m_screenScale.x, m_screenScale.y, 1.0f);
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(transform, &m_stereoFlatSurfaceDef));
        } else {
            // Flat2D: quad mono normal, posicao/escala ajustaveis pelo
            // usuario via thumbstick (T3.6).
            OVR::Vector3f worldScreenPos = m_sceneTranslationOffset + OVR::Matrix4f::RotationY(m_sceneYawOffset).Transform(m_screenPosition);
            OVR::Matrix4f transform = OVR::Matrix4f::Translation(worldScreenPos) * OVR::Matrix4f::RotationY(m_sceneYawOffset) *
                OVR::Matrix4f::Scaling(m_screenScale.x, m_screenScale.y, 1.0f);
            out.Surfaces.push_back(OVRFW::ovrDrawSurface(transform, &m_surfaceDef));
        }

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
        m_stereoFlatSurfaceDef.geo.Free();
        OVRFW::GlProgram::Free(m_stereoFlatProgram);
        m_sphereSurfaceDef.geo.Free();
        OVRFW::GlProgram::Free(m_sphereProgram);
    }

private:
    static constexpr float kUiAutoHideSeconds = 5.0f;
    static constexpr float kUiFadeDuration = 0.35f;

    // Posicao/tamanho da tela virtual, ajustaveis em runtime (T3.6)
    OVR::Vector3f m_screenPosition = OVR::Vector3f(0.0f, 1.5f, -2.0f);
    OVR::Vector2f m_screenScale = OVR::Vector2f(1.6f, 0.9f);

    // T1.1/T1.2/T1.5: quad SBS/OU com separacao real de olho — programa e
    // geometria proprios, separados de m_program (que continua servindo o
    // quad 2D mono E os paineis de UI/controles, que nunca podem ser
    // recortados por olho).
    OVRFW::GlProgram m_stereoFlatProgram;
    OVRFW::ovrSurfaceDef m_stereoFlatSurfaceDef;
    float m_flatStereoLayout = 0.0f; // uniform: 1=SBS, 2=OU (ver stereoFlatFragmentShader)

    // T2: esfera 360/180, mono OU estereo (T2.4/T2.5) — o mesmo programa/
    // geometria serve os dois casos, diferenciados pelos uniforms
    // uPolar180/uStereoLayout (ver UpdateScreenModeUniforms). Raio de 20m:
    // bem alem de qualquer painel de UI (que ficam a ~1.5-2.2m), dentro da
    // faixa near/far padrao do OVRFW.
    static constexpr float kSphereRadius = 20.0f;
    // T4.3: long-press no botao Menu = recenter. 0.6s e o limiar padrao de
    // "long press" do Android (ViewConfiguration.getLongPressTimeout()),
    // reaproveitado aqui por familiaridade — nunca validado em headset real
    // quanto a "sensacao" do timing (ver nota de N/A validation no doc).
    static constexpr float kRecenterHoldSeconds = 0.6f;
    OVRFW::GlProgram m_sphereProgram;
    OVRFW::ovrSurfaceDef m_sphereSurfaceDef;
    ScreenMode m_screenMode = ScreenMode::Flat2D;
    float m_uPolar180 = 0.0f; // uniform: 0=360 completo, 1=180 (ver fragment shader da esfera)
    float m_sphereStereoLayout = 0.0f; // uniform: 0=mono, 1=SBS, 2=OU (ver fragment shader da esfera)
    float m_swapEyesF = 0.0f; // T1.5: espelha get_swap_eyes(), compartilhado pelos 2 programas estereo
    float m_sharpness = 0.5f; // uniform CAS: 0=mais leve (peak -8), 1=mais forte (peak -5). So usado se kSharpenEnabled.
    float m_sceneYawOffset = 0.0f; // T4.3: recenter — offset de yaw aplicado a cena
    OVR::Vector3f m_sceneTranslationOffset = OVR::Vector3f(0.0f, 0.0f, 0.0f);
    OVR::Matrix4f m_sphereTransform;
    float m_menuHoldTime = 0.0f;
    bool m_recenterFiredThisHold = false;
    bool m_needsOsRecenter = false;

    // Auto-hide + billboard + alpha blending dos paineis de UI (T4.3/T4.5)
    float m_videoAlpha = 1.0f;
    float m_uiAlpha = 1.0f;
    float m_controlsAlpha = 1.0f;
    float m_uiIdleTime = 0.0f;
    float m_controlsIdleTime = 0.0f;
    OVR::Matrix4f m_uiTransform;
    OVR::Matrix4f m_controlsTransform;

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
    std::unordered_map<AHardwareBuffer*, EGLImageKHR> m_eglImageCache;
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
    OVRFW::ovrBeamRenderer::handle_t m_cursorDotHandle{OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE};
    OVRFW::ovrBeamRenderer::handle_t m_cursorDotHandle2{OVRFW::ovrBeamRenderer::INVALID_BEAM_HANDLE};
};

ENTRY_POINT(VRPlayerApp)
