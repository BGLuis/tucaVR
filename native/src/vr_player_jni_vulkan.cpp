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
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "VRPlayerJNI_VK", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VRPlayerJNI_VK", __VA_ARGS__)

// Bridge Rust — mesmas funções do vr_player_app.cpp:48-88.
extern "C" {
    extern void start_video_playback(const char* path, float startTimeSec);
    extern void toggle_play_pause();
    extern void seek_video_playback(float position_seconds);
    extern void set_video_volume(float volume);
    extern void set_playback_speed(float speed);
    extern void cycle_audio_track();
    extern uint32_t cycle_3d_mode();
    extern uint32_t get_3d_mode();
    extern void set_3d_mode(uint32_t mode);
    extern uint32_t toggle_swap_eyes();
    extern void set_keyboard_active(int active);
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
    // Thumbnails de rede — mesmo contrato de vr_player_app.cpp.
    extern uint8_t* smb_generate_thumbnail(const char* host, int32_t port, const char* username,
                                            const char* password, const char* domain, const char* share,
                                            const char* path, uint32_t max_width, uint32_t max_height,
                                            uint32_t* out_width, uint32_t* out_height, size_t* out_len);
    extern uint8_t* ftp_generate_thumbnail(const char* host, int32_t port, const char* username,
                                            const char* password, const char* path,
                                            uint32_t max_width, uint32_t max_height,
                                            uint32_t* out_width, uint32_t* out_height, size_t* out_len);
    extern uint8_t* sftp_generate_thumbnail(const char* host, int32_t port, const char* username,
                                             const char* password, const char* private_key, const char* path,
                                             uint32_t max_width, uint32_t max_height,
                                             uint32_t* out_width, uint32_t* out_height, size_t* out_len);
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
}

// Captura de frame (nao implementada no caminho Vulkan ainda — funcao existe
// para evitar UnsatisfiedLinkError; o VkImage readback sera adicionado
// quando necessario).
static std::atomic<bool> g_captureRequested{false};
static std::string g_capturePath;
static std::mutex g_capturePathMutex;

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeRequestFrameCapture(JNIEnv* env, jobject, jstring path) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_capturePathMutex);
        g_capturePath = pathStr;
    }
    env->ReleaseStringUTFChars(path, pathStr);
    g_captureRequested = true;
    LOGI("nativeRequestFrameCapture: %s (VkImage readback pendente)", g_capturePath.c_str());
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

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeUpdateScrubOverlay(JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height) {
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
Java_com_vrplayer_VRActivity_nativeSetScrubOverlayVisible(JNIEnv* env, jobject, jboolean visible) {
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
Java_com_vrplayer_VRActivity_nativePlayVideo(JNIEnv* env, jobject, jstring path, jfloat startTimeSec) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    start_video_playback(pathStr, startTimeSec);
    env->ReleaseStringUTFChars(path, pathStr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeTogglePlayPause(JNIEnv*, jobject) {
    toggle_play_pause();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeTakeLastPlaybackError(JNIEnv* env, jobject) {
    char* rustStr = take_last_playback_error();
    if (!rustStr) return nullptr;
    jstring result = env->NewStringUTF(rustStr);
    free_rust_string(rustStr);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSeekVideo(JNIEnv*, jobject, jfloat positionSeconds) {
    seek_video_playback(positionSeconds);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetVolume(JNIEnv*, jobject, jfloat volume) {
    set_video_volume(volume);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetSpeed(JNIEnv*, jobject, jfloat speed) {
    set_playback_speed(speed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeCycleAudioTrack(JNIEnv*, jobject) {
    cycle_audio_track();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeCycle3DMode(JNIEnv*, jobject) {
    return (jint)cycle_3d_mode();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeGet3DMode(JNIEnv*, jobject) {
    return (jint)get_3d_mode();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetScreenMode(JNIEnv*, jobject, jint mode) {
    set_3d_mode((uint32_t)mode);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_vrplayer_VRActivity_nativeToggleSwapEyes(JNIEnv*, jobject) {
    return (jint)toggle_swap_eyes();
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativeSetKeyboardActive(JNIEnv*, jobject, jboolean active) {
    set_keyboard_active(active ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_vrplayer_VRActivity_nativePlaySmb(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeSmbListShares(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeSmbListDirectory(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativePlayFtp(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeFtpListDirectory(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativePlaySftp(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeSftpListDirectory(JNIEnv* env, jobject,
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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vrplayer_VRActivity_nativeSmbGenerateThumbnail(JNIEnv* env, jobject,
                                                         jstring host, jint port,
                                                         jstring username, jstring password,
                                                         jstring domain, jstring share, jstring path,
                                                         jint maxWidth, jint maxHeight) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* d = env->GetStringUTFChars(domain, nullptr);
    const char* sh = env->GetStringUTFChars(share, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = smb_generate_thumbnail(h, (int32_t)port, u, pw, d, sh, p,
                                            (uint32_t)maxWidth, (uint32_t)maxHeight, &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(domain, d);
    env->ReleaseStringUTFChars(share, sh);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vrplayer_VRActivity_nativeFtpGenerateThumbnail(JNIEnv* env, jobject,
                                                         jstring host, jint port,
                                                         jstring username, jstring password, jstring path,
                                                         jint maxWidth, jint maxHeight) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = ftp_generate_thumbnail(h, (int32_t)port, u, pw, p,
                                            (uint32_t)maxWidth, (uint32_t)maxHeight, &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vrplayer_VRActivity_nativeSftpGenerateThumbnail(JNIEnv* env, jobject,
                                                          jstring host, jint port,
                                                          jstring username, jstring password,
                                                          jstring privateKey, jstring path,
                                                          jint maxWidth, jint maxHeight) {
    const char* h = env->GetStringUTFChars(host, nullptr);
    const char* u = env->GetStringUTFChars(username, nullptr);
    const char* pw = env->GetStringUTFChars(password, nullptr);
    const char* k = env->GetStringUTFChars(privateKey, nullptr);
    const char* p = env->GetStringUTFChars(path, nullptr);
    uint32_t outWidth = 0, outHeight = 0;
    size_t outLen = 0;
    uint8_t* data = sftp_generate_thumbnail(h, (int32_t)port, u, pw, k, p,
                                             (uint32_t)maxWidth, (uint32_t)maxHeight, &outWidth, &outHeight, &outLen);
    env->ReleaseStringUTFChars(host, h);
    env->ReleaseStringUTFChars(username, u);
    env->ReleaseStringUTFChars(password, pw);
    env->ReleaseStringUTFChars(privateKey, k);
    env->ReleaseStringUTFChars(path, p);
    return RustThumbnailToJByteArrayAndFree(env, data, outLen);
}

// Preview de arrasto no seekbar (T-seek-ux) — mesma logica de
// nativeSmbGenerateThumbnail, devolve N frames concatenados em vez de 1.
// outWidth/outHeight/outCount descartados de proposito: o Kotlin ja sabe
// max_width/max_height (foi ele quem pediu) e calcula count como
// byteArray.size / (width*height*4).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_vrplayer_VRActivity_nativeSmbGenerateThumbnailStrip(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeSftpGenerateThumbnailStrip(JNIEnv* env, jobject,
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
Java_com_vrplayer_VRActivity_nativeCancelScrubStrip(JNIEnv* env, jobject) {
    cancel_thumbnail_strip_generation();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_vrplayer_VRActivity_nativeProbeHttpUrl(JNIEnv* env, jobject, jstring url) {
    const char* urlStr = env->GetStringUTFChars(url, nullptr);
    char* result = probe_http_url(urlStr);
    env->ReleaseStringUTFChars(url, urlStr);
    return RustStringToJStringAndFree(env, result);
}
