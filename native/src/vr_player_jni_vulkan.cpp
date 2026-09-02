// vr_player_jni_vulkan.cpp
//
// Funções JNI do caminho Vulkan. Compiladas apenas quando
// VRPLAYER_GRAPHICS_API=VULKAN (guardadas por CMakeLists.txt).
//
// Lógica idêntica a vr_player_app.cpp:100-365 (caminho GLES):
// todas as chamadas delegam para o mesmo bridge Rust (libbridge.so).
// Os dois arquivos existem separados para não criar dependências cruzadas
// entre os dois caminhos (Estágio 6: dois caminhos em paralelo).

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <vector>
#include <string>
#include "debug_stats.h"
#include <atomic>
#include <cstdint>
#include <mutex>

extern std::string g_sessionId;
extern std::mutex g_sessionIdMutex;

static inline std::string get_current_session_id_vk() {
    std::lock_guard<std::mutex> lock(g_sessionIdMutex);
    return g_sessionId;
}

#define LOGI(fmt, ...) do { \
    std::string _sId = get_current_session_id_vk(); \
    __android_log_print(ANDROID_LOG_INFO, "VRPlayerJNI_VK", "[s:%s] " fmt, _sId.c_str(), ##__VA_ARGS__); \
} while (0)

#define LOGE(fmt, ...) do { \
    std::string _sId = get_current_session_id_vk(); \
    __android_log_print(ANDROID_LOG_ERROR, "VRPlayerJNI_VK", "[s:%s] " fmt, _sId.c_str(), ##__VA_ARGS__); \
} while (0)

// Bridge Rust — mesmas funções do vr_player_app.cpp:48-88.
extern "C" {
    extern void set_session_id(const char* session_id);
    extern void start_video_playback(const char* path, float startTimeSec);
    extern void stop_video_playback();
    extern void toggle_play_pause();
    extern void seek_video_playback(float position_seconds);
    extern void set_video_volume(float volume);
    extern void set_playback_speed(float speed);
    extern void cycle_audio_track();
    extern uint32_t cycle_3d_mode();
    extern uint32_t get_3d_mode();
    extern void set_3d_mode(uint32_t mode);
    extern void set_screen_mode_override(int32_t mode);
    extern uint32_t toggle_swap_eyes();
    extern void set_keyboard_active(int active);
    // Fase 0.4 T5: Foveated Rendering (implementacao real em
    // vr_player_app_vulkan.cpp, ver ApplyFoveation).
    extern void set_foveation_enabled(uint32_t enabled);
    extern void set_foveation_mode(uint32_t mode);
    extern uint32_t get_foveation_mode();
    // Fase 0.3 Seção 2: Passthrough / Mixed Reality (implementacao real em
    // vr_player_app_vulkan.cpp, ver SetupPassthrough/UpdatePassthrough).
    extern void set_passthrough_enabled(uint32_t enabled);
    extern uint32_t get_passthrough_supported();
    extern void set_pause_on_exit(uint32_t enabled);
    extern uint32_t get_pause_on_exit();
    // Upscaling de vídeo (Vulkan-only, MQSR & SGSR1)
    extern void set_upscaling_mode(uint32_t mode);
    extern uint32_t get_upscaling_mode();
    // Fase 0.2 T14: Monitoramento Térmico (RNF-PERF-006).
    extern void set_thermal_level(uint32_t level);
    extern uint32_t get_thermal_level();
    extern void set_spatial_audio_mode(uint32_t mode);
    extern uint32_t get_spatial_audio_mode();
    extern void set_spatial_audio_head_tracking(uint32_t enabled);
    extern uint32_t get_spatial_audio_head_tracking();
    // T4.4: Screen-locked audio — speakers fixos relativos à tela
    extern void set_audio_screen_locked(uint32_t locked);
    extern uint32_t get_audio_screen_locked();
    extern void set_screen_orientation(float x, float y, float z, float w);
    // Legendas (SRT / WebVTT — Fase 0.2 T9.1-T9.6)
    extern void set_subtitle_track(int32_t track_index);
    extern int32_t get_subtitle_track();
    extern void set_subtitle_offset_ms(int64_t offset_ms);
    extern int64_t get_subtitle_offset_ms();
    extern uint32_t load_external_subtitle(const char* path);
    extern uint32_t get_subtitle_track_count();
    extern uint32_t get_active_subtitle_text(char* out_buf, size_t max_len);
    extern void set_preferred_subtitle_language(const char* lang); // T7.6
    extern char* take_last_playback_error();
    extern void free_rust_string(char* s);
    extern char* probe_http_url(const char* url);
    // SMB
    extern void start_smb_playback(const char* host, int32_t port, const char* share,
                                    const char* path, const char* username,
                                    const char* password, const char* domain, float startTimeSec);
    extern char* smb_list_shares(const char* host, int32_t port, const char* username,
                                  const char* password, const char* domain);
    extern char* smb_list_directory(const char* host, int32_t port, const char* username,
                                     const char* password, const char* domain,
                                     const char* share, const char* path);
    // FTP
    extern void start_ftp_playback(const char* host, int32_t port, const char* path,
                                    const char* username, const char* password, float startTimeSec);
    extern char* ftp_list_directory(const char* host, int32_t port, const char* username,
                                     const char* password, const char* path);
    // SFTP
    extern void start_sftp_playback(const char* host, int32_t port, const char* path,
                                     const char* username, const char* password,
                                     const char* private_key, float startTimeSec);
    extern char* sftp_list_directory(const char* host, int32_t port, const char* username,
                                      const char* password, const char* private_key,
                                      const char* path);
    // NFS
    extern void start_nfs_playback(const char* host, int32_t port, const char* export_path,
                                    const char* file_path, int32_t version, float startTimeSec);
    extern char* nfs_list_directory(const char* host, int32_t port, const char* export_path,
                                     const char* dir_path, int32_t version);
    extern char* nfs_list_exports(const char* host, int32_t port);
    // Descoberta Automática (mDNS + SSDP)
    extern char* discovery_scan_network(uint32_t timeout_ms);
    // DLNA
    extern char* dlna_get_device_description(const char* location);
    extern char* dlna_browse_directory(const char* control_url, const char* object_id, uint32_t start_index, uint32_t max_count);
    // HLS
    extern char* hls_probe_variants(const char* url);
    // Thumbnails de rede — mesmo contrato de vr_player_app.cpp.
    extern uint8_t* smb_generate_thumbnail(const char* host, int32_t port, const char* username,
                                            const char* password, const char* domain, const char* share,
                                            const char* path, uint32_t max_width, uint32_t max_height,
                                            uint64_t cancel_token,
                                            uint32_t* out_width, uint32_t* out_height, size_t* out_len);
    extern uint8_t* ftp_generate_thumbnail(const char* host, int32_t port, const char* username,
                                            const char* password, const char* path,
                                            uint32_t max_width, uint32_t max_height,
                                            uint64_t cancel_token,
                                            uint32_t* out_width, uint32_t* out_height, size_t* out_len);
    extern uint8_t* sftp_generate_thumbnail(const char* host, int32_t port, const char* username,
                                             const char* password, const char* private_key, const char* path,
                                             uint32_t max_width, uint32_t max_height,
                                             uint64_t cancel_token,
                                             uint32_t* out_width, uint32_t* out_height, size_t* out_len);
    extern void cancel_thumbnail_generation(uint64_t cancel_token);
    extern void free_rust_thumbnail_buffer(uint8_t* ptr, size_t len);
    // Preview de arrasto no seekbar (T-seek-ux) — mesmo contrato de
    // vr_player_app.cpp: trilha de thumbnails em vez de 1 so, ver
    // core::thumbnail::generate_strip em rust/bridge/src/lib.rs.
    extern uint8_t* smb_generate_thumbnail_strip(const char* host, int32_t port, const char* username,
                                                  const char* password, const char* domain, const char* share,
                                                  const char* path, float interval_secs,
                                                  uint32_t max_width, uint32_t max_height,
                                                  uint32_t* out_width, uint32_t* out_height,
                                                  size_t* out_count, size_t* out_len);
    extern uint8_t* sftp_generate_thumbnail_strip(const char* host, int32_t port, const char* username,
                                                   const char* password, const char* private_key, const char* path,
                                                   float interval_secs, uint32_t max_width, uint32_t max_height,
                                                   uint32_t* out_width, uint32_t* out_height,
                                                   size_t* out_count, size_t* out_len);
    extern void free_rust_thumbnail_strip(uint8_t* ptr, size_t len);
    // Interrompe uma geracao de tira em andamento — ver comentario em
    // rust/bridge/src/lib.rs::cancel_thumbnail_strip_generation.
    extern void cancel_thumbnail_strip_generation();
    // T13.1/T13.2: metadados de midia + selecao de trilha de audio — mesmo
    // contrato de vr_player_app.cpp (ver rust/media_logic/src/metadata_wire.rs).
    extern char* read_media_metadata(const char* path);
    extern char* smb_read_metadata(const char* host, int32_t port, const char* username,
                                    const char* password, const char* domain, const char* share,
                                    const char* path);
    extern char* ftp_read_metadata(const char* host, int32_t port, const char* username,
                                    const char* password, const char* path);
    extern char* sftp_read_metadata(const char* host, int32_t port, const char* username,
                                     const char* password, const char* private_key, const char* path);
    extern void set_desired_audio_track(uint32_t ordinal);
}

// Captura de frame no caminho Vulkan (coordenada com loop de render em vr_player_app_vulkan.cpp).
extern std::atomic<bool> g_captureRequested;
extern std::string g_capturePath;
extern std::mutex g_capturePathMutex;

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSessionId(JNIEnv* env, jobject, jstring jSessionId) {
    if (jSessionId) {
        const char* s = env->GetStringUTFChars(jSessionId, nullptr);
        {
            std::lock_guard<std::mutex> lock(g_sessionIdMutex);
            g_sessionId = (s && s[0] != '\0') ? s : "--------";
        }
        set_session_id(s);
        env->ReleaseStringUTFChars(jSessionId, s);
    } else {
        {
            std::lock_guard<std::mutex> lock(g_sessionIdMutex);
            g_sessionId = "--------";
        }
        set_session_id("");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeRequestFrameCapture(JNIEnv* env, jobject, jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_capturePathMutex);
        g_capturePath = pathStr;
    }
    env->ReleaseStringUTFChars(path, pathStr);
    g_captureRequested = true;
    LOGI("nativeRequestFrameCapture: %s", g_capturePath.c_str());
}

// Preview de arrasto sobre o quad do video — estado compartilhado com o loop
// de render em vr_player_app_vulkan.cpp (extern, nao static: unica excecao a
// separacao "JNI so delega pro bridge Rust" do topo deste arquivo — os bytes
// crus do preview nao tem por que passar pelo Rust so pra voltar pro C++ sem
// nenhum processamento; ver comentario simetrico em vr_player_app_vulkan.cpp
// junto da definicao). So tipos simples cruzam a fronteira (atomic/vector),
// nenhum tipo de Vulkan/OpenXR — mantem este arquivo leve de incluir.
extern std::atomic<bool> g_scrubOverlayDirty;
extern std::atomic<bool> g_scrubOverlayVisible;
extern std::vector<uint8_t> g_scrubOverlayRgba;
extern uint32_t g_scrubOverlayWidth;
extern uint32_t g_scrubOverlayHeight;
extern std::mutex g_scrubOverlayMutex;
extern std::atomic<bool> g_requestUiPanelVisible;
extern std::atomic<bool> g_requestControlsPanelVisible;
extern std::atomic<bool> g_stopVideoRequested;
extern std::atomic<bool> g_modalPanelActive;
extern std::atomic<bool> g_modalPanelShowRequested;
extern std::atomic<bool> g_modalPanelHideRequested;

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeStopVideo(JNIEnv*, jobject) {
    stop_video_playback();
    g_stopVideoRequested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeRequestUiPanelVisible(JNIEnv*, jobject) {
    g_requestUiPanelVisible.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeRequestControlsPanelVisible(JNIEnv*, jobject) {
    g_requestControlsPanelVisible.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeShowModalPanel(JNIEnv*, jobject) {
    g_modalPanelShowRequested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeHideModalPanel(JNIEnv*, jobject) {
    g_modalPanelHideRequested.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tucavr_VRActivity_nativeIsModalActive(JNIEnv*, jobject) {
    return g_modalPanelActive.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeUpdateScrubOverlay(JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height) {
    jsize len = env->GetArrayLength(rgba);
    std::vector<uint8_t> buf(static_cast<size_t>(len));
    env->GetByteArrayRegion(rgba, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    {
        std::lock_guard<std::mutex> lock(g_scrubOverlayMutex);
        g_scrubOverlayRgba = std::move(buf);
        g_scrubOverlayWidth = static_cast<uint32_t>(width);
        g_scrubOverlayHeight = static_cast<uint32_t>(height);
    }
    g_scrubOverlayDirty = true;
    static bool logged = false;
    if (!logged) {
        logged = true;
        LOGI("nativeUpdateScrubOverlay: primeira chamada, %dx%d, %d bytes", width, height, (int)len);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetScrubOverlayVisible(JNIEnv* env, jobject, jboolean visible) {
    g_scrubOverlayVisible = (visible == JNI_TRUE);
    LOGI("nativeSetScrubOverlayVisible: %d", (int)(visible == JNI_TRUE));
}

// Helper: converte char* alocado pelo Rust para jstring e libera o original.
static jstring RustStringToJStringAndFree(JNIEnv* env, char* rustStr) {
    if (!rustStr) return env->NewStringUTF("ERROR:null");
    jstring result = env->NewStringUTF(rustStr);
    free_rust_string(rustStr);
    return result;
}

// Helper: converte o buffer RGBA alocado pelo Rust para jbyteArray e libera
// o original — mesma logica de vr_player_app.cpp.
static jbyteArray RustThumbnailToJByteArrayAndFree(JNIEnv* env, uint8_t* data, size_t len) {
    if (!data) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)len);
    env->SetByteArrayRegion(result, 0, (jsize)len, reinterpret_cast<jbyte*>(data));
    free_rust_thumbnail_buffer(data, len);
    return result;
}

// Mesma logica acima, pro alocador da trilha de thumbnails (free_rust_thumbnail_strip
// em vez de free_rust_thumbnail_buffer — capacidades diferentes do lado Rust,
// nao da pra misturar).
static jbyteArray RustThumbnailStripToJByteArrayAndFree(JNIEnv* env, uint8_t* data, size_t len) {
    if (!data) return nullptr;
    jbyteArray result = env->NewByteArray((jsize)len);
    env->SetByteArrayRegion(result, 0, (jsize)len, reinterpret_cast<jbyte*>(data));
    free_rust_thumbnail_strip(data, len);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativePlayVideo(JNIEnv* env, jobject, jstring path, jfloat startTimeSec) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    start_video_playback(pathStr, startTimeSec);
    env->ReleaseStringUTFChars(path, pathStr);
    g_requestControlsPanelVisible.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeTogglePlayPause(JNIEnv*, jobject) {
    toggle_play_pause();
    g_requestControlsPanelVisible.store(true);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeTakeLastPlaybackError(JNIEnv* env, jobject) {
    char* rustStr = take_last_playback_error();
    if (!rustStr) return nullptr;
    jstring result = env->NewStringUTF(rustStr);
    free_rust_string(rustStr);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSeekVideo(JNIEnv*, jobject, jfloat positionSeconds) {
    seek_video_playback(positionSeconds);
    g_requestControlsPanelVisible.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetVolume(JNIEnv*, jobject, jfloat volume) {
    set_video_volume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSpeed(JNIEnv*, jobject, jfloat speed) {
    set_playback_speed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeCycleAudioTrack(JNIEnv*, jobject) {
    cycle_audio_track();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeCycle3DMode(JNIEnv*, jobject) {
    return (jint)cycle_3d_mode();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeGet3DMode(JNIEnv*, jobject) {
    return (jint)get_3d_mode();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetScreenMode(JNIEnv*, jobject, jint mode) {
    set_3d_mode((uint32_t)mode);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetScreenModeOverride(JNIEnv*, jobject, jint mode) {
    set_screen_mode_override((int32_t)mode);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeToggleSwapEyes(JNIEnv*, jobject) {
    return (jint)toggle_swap_eyes();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetKeyboardActive(JNIEnv*, jobject, jboolean active) {
    set_keyboard_active(active ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetFoveationEnabled(JNIEnv*, jobject, jboolean enabled) {
    set_foveation_enabled(enabled ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetFoveationMode(JNIEnv*, jobject, jint mode) {
    set_foveation_mode(static_cast<uint32_t>(mode));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeGetFoveationMode(JNIEnv*, jobject) {
    return static_cast<jint>(get_foveation_mode());
}

// Fase 0.3 Seção 2: Passthrough / Mixed Reality.
extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetPassthroughEnabled(JNIEnv*, jobject, jboolean enabled) {
    set_passthrough_enabled(enabled ? 1 : 0);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tucavr_VRActivity_nativeIsPassthroughSupported(JNIEnv*, jobject) {
    return get_passthrough_supported() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetPauseOnExit(JNIEnv*, jobject, jboolean enabled) {
    set_pause_on_exit(enabled ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetUpscalingMode(JNIEnv*, jobject, jint mode) {
    set_upscaling_mode(static_cast<uint32_t>(mode < 0 ? 0 : mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetThermalLevel(JNIEnv*, jobject, jint level) {
    set_thermal_level(static_cast<uint32_t>(level));
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetDebugStatsEnabled(JNIEnv*, jobject, jboolean enabled) {
    g_debugStatsEnabled.store(enabled, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativePlaySmb(JNIEnv* env, jobject,
                                            jstring host, jint port, jstring share,
                                            jstring path, jstring username,
                                            jstring password, jstring domain, jfloat startTimeSec) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    start_smb_playback(h, (int32_t)port, sh, p, u, pw, d, startTimeSec);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeSmbListShares(JNIEnv* env, jobject,
                                                   jstring host, jint port,
                                                   jstring username, jstring password,
                                                   jstring domain) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    char* result = smb_list_shares(h, (int32_t)port, u, pw, d);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeSmbListDirectory(JNIEnv* env, jobject,
                                                      jstring host, jint port,
                                                      jstring username, jstring password,
                                                      jstring domain, jstring share,
                                                      jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = smb_list_directory(h, (int32_t)port, u, pw, d, sh, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativePlayFtp(JNIEnv* env, jobject,
                                            jstring host, jint port, jstring path,
                                            jstring username, jstring password, jfloat startTimeSec) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    start_ftp_playback(h, (int32_t)port, p, u, pw, startTimeSec);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeFtpListDirectory(JNIEnv* env, jobject,
                                                      jstring host, jint port,
                                                      jstring username, jstring password,
                                                      jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = ftp_list_directory(h, (int32_t)port, u, pw, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativePlaySftp(JNIEnv* env, jobject,
                                             jstring host, jint port, jstring path,
                                             jstring username, jstring password,
                                             jstring privateKey, jfloat startTimeSec) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    start_sftp_playback(h, (int32_t)port, p, u, pw, k, startTimeSec);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(path, p);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeSftpListDirectory(JNIEnv* env, jobject,
                                                       jstring host, jint port,
                                                       jstring username, jstring password,
                                                       jstring privateKey, jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = sftp_list_directory(h, (int32_t)port, u, pw, k, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativePlayNfs(JNIEnv* env, jobject,
                                            jstring host, jint port, jstring exportPath,
                                            jstring filePath, jint version, jfloat startTimeSec) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* ep = env->GetStringUTFChars(exportPath, nullptr);
    const char* fp = env->GetStringUTFChars(filePath, nullptr);
    start_nfs_playback(h, (int32_t)port, ep, fp, (int32_t)version, startTimeSec);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(exportPath, ep);
    env->ReleaseStringUTFChars(filePath, fp);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeNfsListDirectory(JNIEnv* env, jobject,
                                                     jstring host, jint port,
                                                     jstring exportPath, jstring dirPath,
                                                     jint version) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* ep = env->GetStringUTFChars(exportPath, nullptr);
    const char* dp = env->GetStringUTFChars(dirPath, nullptr);
    char* result = nfs_list_directory(h, (int32_t)port, ep, dp, (int32_t)version);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(exportPath, ep);
    env->ReleaseStringUTFChars(dirPath, dp);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeNfsListExports(JNIEnv* env, jobject,
                                                  jstring host, jint port) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    char* result = nfs_list_exports(h, (int32_t)port);
    env->ReleaseStringUTFChars(host, h);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeDiscoveryScan(JNIEnv* env, jobject,
                                                 jint timeoutMs) {
    char* result = discovery_scan_network((uint32_t)timeoutMs);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tucavr_VRActivity_nativeSmbGenerateThumbnail(JNIEnv* env, jobject,
                                                         jstring host, jint port,
                                                         jstring username, jstring password,
                                                         jstring domain, jstring share, jstring path,
                                                         jint maxWidth, jint maxHeight,
                                                         jlong cancelToken) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = smb_generate_thumbnail(h, (int32_t)port, u, pw, d, sh, p,
                                            (uint32_t)maxWidth, (uint32_t)maxHeight, (uint64_t)cancelToken,
                                            &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tucavr_VRActivity_nativeFtpGenerateThumbnail(JNIEnv* env, jobject,
                                                         jstring host, jint port,
                                                         jstring username, jstring password, jstring path,
                                                         jint maxWidth, jint maxHeight,
                                                         jlong cancelToken) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = ftp_generate_thumbnail(h, (int32_t)port, u, pw, p,
                                            (uint32_t)maxWidth, (uint32_t)maxHeight, (uint64_t)cancelToken,
                                            &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tucavr_VRActivity_nativeSftpGenerateThumbnail(JNIEnv* env, jobject,
                                                          jstring host, jint port,
                                                          jstring username, jstring password,
                                                          jstring privateKey, jstring path,
                                                          jint maxWidth, jint maxHeight,
                                                          jlong cancelToken) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = sftp_generate_thumbnail(h, (int32_t)port, u, pw, k, p,
                                             (uint32_t)maxWidth, (uint32_t)maxHeight, (uint64_t)cancelToken,
                                             &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeCancelThumbnailGeneration(JNIEnv* env, jobject, jlong cancelToken) {
    cancel_thumbnail_generation((uint64_t)cancelToken);
}

// Preview de arrasto no seekbar (T-seek-ux) — mesma logica de
// nativeSmbGenerateThumbnail, devolve N frames concatenados em vez de 1.
// outWidth/outHeight/outCount descartados de proposito: o Kotlin ja sabe
// max_width/max_height (foi ele quem pediu) e calcula count como
// byteArray.size / (width*height*4).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tucavr_VRActivity_nativeSmbGenerateThumbnailStrip(JNIEnv* env, jobject,
                                                              jstring host, jint port,
                                                              jstring username, jstring password,
                                                              jstring domain, jstring share, jstring path,
                                                              jfloat intervalSeconds,
                                                              jint maxWidth, jint maxHeight) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outCount = 0, outLen = 0;
    uint8_t* data = smb_generate_thumbnail_strip(h, (int32_t)port, u, pw, d, sh, p, (float)intervalSeconds,
                                                  (uint32_t)maxWidth, (uint32_t)maxHeight,
                                                  &outWidth, &outHeight, &outCount, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailStripToJByteArrayAndFree(env, data, outLen);
}

// Mesma logica de nativeSmbGenerateThumbnailStrip, para um arquivo num
// servidor SFTP.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_tucavr_VRActivity_nativeSftpGenerateThumbnailStrip(JNIEnv* env, jobject,
                                                               jstring host, jint port,
                                                               jstring username, jstring password,
                                                               jstring privateKey, jstring path,
                                                               jfloat intervalSeconds,
                                                               jint maxWidth, jint maxHeight) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outCount = 0, outLen = 0;
    uint8_t* data = sftp_generate_thumbnail_strip(h, (int32_t)port, u, pw, k, p, (float)intervalSeconds,
                                                   (uint32_t)maxWidth, (uint32_t)maxHeight,
                                                   &outWidth, &outHeight, &outCount, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailStripToJByteArrayAndFree(env, data, outLen);
}

// Interrompe uma geracao de tira de scrub ja em andamento (chamadas acima sao
// sincronas/bloqueantes — um Job.cancel() do lado Kotlin nao as interrompe).
extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeCancelScrubStrip(JNIEnv* env, jobject) {
    cancel_thumbnail_strip_generation();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeProbeHttpUrl(JNIEnv* env, jobject, jstring url) {
    const char* urlStr = env->GetStringUTFChars(url, nullptr);
    char* result = probe_http_url(urlStr);
    env->ReleaseStringUTFChars(url, urlStr);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeDlnaGetDevice(JNIEnv* env, jobject, jstring location) {
    const char* locStr = env->GetStringUTFChars(location, nullptr);
    char* result = dlna_get_device_description(locStr);
    env->ReleaseStringUTFChars(location, locStr);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeDlnaBrowse(JNIEnv* env, jobject, jstring controlUrl,
                                              jstring objectId, jint startIndex, jint maxCount) {
    const char* ctrlStr = env->GetStringUTFChars(controlUrl, nullptr);
    const char* objStr = env->GetStringUTFChars(objectId, nullptr);

    char* result = dlna_browse_directory(ctrlStr, objStr, (uint32_t)startIndex, (uint32_t)maxCount);

    env->ReleaseStringUTFChars(controlUrl, ctrlStr);
    env->ReleaseStringUTFChars(objectId, objStr);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeHlsProbeVariants(JNIEnv* env, jobject, jstring url) {
    const char* urlStr = env->GetStringUTFChars(url, nullptr);
    char* result = hls_probe_variants(urlStr);
    env->ReleaseStringUTFChars(url, urlStr);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeReadMediaMetadata(JNIEnv* env, jobject, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = read_media_metadata(p);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeSmbReadMetadata(JNIEnv* env, jobject,
                                                    jstring host, jint port,
                                                    jstring username, jstring password,
                                                    jstring domain, jstring share,
                                                    jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = smb_read_metadata(h, (int32_t)port, u, pw, d, sh, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeFtpReadMetadata(JNIEnv* env, jobject,
                                                    jstring host, jint port,
                                                    jstring username, jstring password,
                                                    jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = ftp_read_metadata(h, (int32_t)port, u, pw, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tucavr_VRActivity_nativeSftpReadMetadata(JNIEnv* env, jobject,
                                                     jstring host, jint port,
                                                     jstring username, jstring password,
                                                     jstring privateKey, jstring path) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    char* result = sftp_read_metadata(h, (int32_t)port, u, pw, k, p);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
    env->ReleaseStringUTFChars(path, p);
    return RustStringToJStringAndFree(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetAudioTrack(JNIEnv* env, jobject, jint ordinal) {
    set_desired_audio_track((uint32_t)(ordinal < 0 ? 0 : ordinal));
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSpatialAudioMode(JNIEnv* env, jobject, jint mode) {
    set_spatial_audio_mode((uint32_t)(mode < 0 ? 0 : mode));
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSpatialAudioHeadTracking(JNIEnv* env, jobject, jboolean enabled) {
    set_spatial_audio_head_tracking(enabled ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetAudioScreenLocked(JNIEnv* env, jobject, jboolean locked) {
    set_audio_screen_locked(locked ? 1 : 0);
}

// ============================================================================
// Métodos JNI de Legendas (SRT / WebVTT — Fase 0.2 T9.1-T9.6)
// ============================================================================

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSubtitleTrack(JNIEnv* env, jobject, jint trackIndex) {
    set_subtitle_track((int32_t)trackIndex);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeGetSubtitleTrack(JNIEnv* env, jobject) {
    return (jint)get_subtitle_track();
}

extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetSubtitleOffsetMs(JNIEnv* env, jobject, jlong offsetMs) {
    set_subtitle_offset_ms((int64_t)offsetMs);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_tucavr_VRActivity_nativeGetSubtitleOffsetMs(JNIEnv* env, jobject) {
    return (jlong)get_subtitle_offset_ms();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tucavr_VRActivity_nativeLoadExternalSubtitle(JNIEnv* env, jobject, jstring path) {
    if (!path) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t count = load_external_subtitle(p);
    env->ReleaseStringUTFChars(path, p);
    return count > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_tucavr_VRActivity_nativeGetSubtitleTrackCount(JNIEnv* env, jobject) {
    return (jint)get_subtitle_track_count();
}

// T7.6: idioma do sistema (BCP-47, ex.: "pt-BR") para auto-seleção de faixa
// de legenda embutida no próximo load.
extern "C" JNIEXPORT void JNICALL
Java_com_tucavr_VRActivity_nativeSetPreferredSubtitleLanguage(JNIEnv* env, jobject, jstring lang) {
    if (!lang) {
        set_preferred_subtitle_language("");
        return;
    }
    const char* l = env->GetStringUTFChars(lang, nullptr);
    set_preferred_subtitle_language(l ? l : "");
    if (l) env->ReleaseStringUTFChars(lang, l);
}
