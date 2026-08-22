// Bridge Rust -> C++: API C ABI plana (extern "C"), consumida diretamente
// por native/src/vr_player_app.cpp. O Kotlin nunca chama este crate
// diretamente — ele so fala com o C++ via JNI (ver VRActivity.kt), que e
// quem precisa de acesso sincrono e de baixo overhead ao frame decodificado
// a cada frame do render loop OpenXR. Por isso um bridge Kotlin<->Rust via
// UniFFI (cogitado no ADR-002 original) nao se aplica aqui: nao ha nenhum
// caminho de chamada onde o Kotlin fale com o Rust sem passar pelo C++.
// Ver ADR-002 (revisado) em docs/REQUIREMENTS.md.

use std::os::raw::c_void;
use core::playback::PlaybackController;
use std::sync::{Arc, Mutex};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use once_cell::sync::Lazy;

// Nenhum backend de `log` (facade usada por `protocols`, ex.
// `log::warn!("SftpFileSource: reconectando...")`) era inicializado em
// lugar nenhum do workspace — achado nesta sessao (docs/NETWORK-IO-PERFORMANCE.md):
// sem `log::set_logger`, toda chamada `log::warn!`/`log::info!` e um no-op
// silencioso, entao os logs de diagnostico de rede adicionados nas sessoes
// anteriores nunca apareciam no logcat. Implementacao minima em vez de puxar
// a crate `android_logger` (que nao estava em nenhuma arvore de dependencia
// deste workspace ainda) — so redireciona pra `__android_log_print`, mesmo
// mecanismo que o resto do bridge ja usa.
struct AndroidLogger;

impl log::Log for AndroidLogger {
    fn enabled(&self, _metadata: &log::Metadata) -> bool {
        true
    }

    fn log(&self, record: &log::Record) {
        let prio = match record.level() {
            log::Level::Error => 6, // ANDROID_LOG_ERROR
            log::Level::Warn => 5,  // ANDROID_LOG_WARN
            log::Level::Info => 4,  // ANDROID_LOG_INFO
            log::Level::Debug => 3, // ANDROID_LOG_DEBUG
            log::Level::Trace => 2, // ANDROID_LOG_VERBOSE
        };
        unsafe { log(prio, &format!("[{}] {}", record.target(), record.args())); }
    }

    fn flush(&self) {}
}

static ANDROID_LOGGER: AndroidLogger = AndroidLogger;

static CONTROLLER: Lazy<Arc<Mutex<PlaybackController>>> = Lazy::new(|| {
    // Forcado aqui (nao num JNI_OnLoad — este crate nao tem um) porque
    // CONTROLLER e a primeira coisa que praticamente toda funcao extern "C"
    // deste bridge toca; qualquer thread de sessao (prefetch, demux, decode)
    // so nasce depois de load_at(), que ja exige o mutex de CONTROLLER —
    // ou seja, o logger sempre esta pronto antes de qualquer log::* rodar.
    let _ = log::set_logger(&ANDROID_LOGGER);
    log::set_max_level(log::LevelFilter::Info);
    // Sem isto, panic em thread de fundo morre silencioso: stderr nao vai pro logcat neste app.
    std::panic::set_hook(Box::new(|info| log::error!("PANIC: {info}")));
    Arc::new(Mutex::new(PlaybackController::new()))
});

// Erro do ultimo controller.load() que falhou (ex.: codec nao suportado). Antes so ia pro logcat,
// sem nenhum feedback pro usuario. Kotlin consome via take_last_playback_error() (polling).
static LAST_PLAYBACK_ERROR: Lazy<Mutex<Option<String>>> = Lazy::new(|| Mutex::new(None));

// Contador (nao bool) porque duas chamadas de carregamento podem se
// sobrepor (ex.: usuario solta o seek e arrasta de novo antes da primeira
// terminar) — com um bool simples, a primeira terminando derrubaria a flag
// enquanto a segunda ainda esta em andamento. So incrementado/decrementado
// por `spawn_loading`, nunca lido dentro do lock de CONTROLLER (para nao
// arriscar contencao/deadlock com ele).
static LOADING_COUNT: AtomicU32 = AtomicU32::new(0);

/// Roda `f` numa thread separada com o contador de loading incrementado
/// durante a execucao — usado em todo ponto que faz stop()+load()/seek()
/// (reconexao de rede + reprobe do demuxer podem levar segundos, ver
/// docs/NETWORK-IO-PERFORMANCE.md). UI consome via get_playback_is_loading()
/// (polling, mesmo padrao de get_video_progress) para mostrar um indicador
/// de carregamento em vez de a tela simplesmente parar de responder.
fn spawn_loading<F: FnOnce() + Send + 'static>(f: F) {
    LOADING_COUNT.fetch_add(1, Ordering::Relaxed);
    std::thread::spawn(move || {
        f();
        LOADING_COUNT.fetch_sub(1, Ordering::Relaxed);
    });
}

/// Debug/UI (docs/DEBUGGING.md): 1 enquanto ha pelo menos um load()/seek()
/// em andamento em background (ver spawn_loading acima), 0 caso contrario.
/// Nao toca o mutex de CONTROLLER — pode ser chamado a qualquer momento,
/// inclusive enquanto load()/seek() o segura, sem risco de bloquear.
#[no_mangle]
pub extern "C" fn get_playback_is_loading() -> u32 {
    (LOADING_COUNT.load(Ordering::Relaxed) > 0) as u32
}

/// UI (feedback de play/pause): estado real da sessao, nao um espelho
/// otimista do que o Kotlin acha que mandou — reage a qualquer caminho que
/// mude o play/pause (botao na tela, botao do controle VR, auto-pause por
/// perda de foco). try_lock (mesmo motivo de get_video_progress): chamada
/// periodica ~10Hz, nunca deve bloquear o render loop. Em contencao
/// (load()/seek() em andamento — get_playback_is_loading() ja sinaliza
/// isso), devolve 0: nao ha sessao tocando ainda de qualquer forma nesse
/// momento.
#[no_mangle]
pub extern "C" fn get_playback_is_playing() -> u32 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.is_playing() as u32,
        Err(_) => 0,
    }
}

// Nao chamada mais pelas funcoes de tira de scrub (ver comentario "Experimento"
// em smb_generate_thumbnail_strip/sftp_generate_thumbnail_strip) — mantida e
// nao removida de proposito, pra poder reverter rapido esse experimento se o
// crash de driver documentado voltar a acontecer em hardware real.
// try_lock (nao lock() — chamador nao deve travar); em contencao assume ativo (mais seguro).
#[allow(dead_code)]
fn playback_is_active() -> bool {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.is_playing(),
        Err(_) => true,
    }
}

// Feedback visual de acao desenhado pelo C++ sobre a tela de video (o flash do
// painel Kotlin so aparece com o painel visivel). Um AtomicU64 so: sequencia
// nos 32 bits altos, tipo nos baixos — snapshot atomico numa leitura.
// Tipo (DEVE casar com `enum class FeedbackKind` em native/src/vr_player_feedback_overlay.h):
//   0=nenhum, 1=play, 2=pause, 3=seek adiante, 4=seek atras
static FEEDBACK_EVENT: AtomicU64 = AtomicU64::new(0);
const FEEDBACK_PLAY: u32 = 1;
const FEEDBACK_PAUSE: u32 = 2;
const FEEDBACK_SEEK_FORWARD: u32 = 3;
const FEEDBACK_SEEK_BACKWARD: u32 = 4;

fn emit_playback_feedback(kind: u32) {
    let _ = FEEDBACK_EVENT.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |prev| {
        Some((((prev >> 32) + 1) << 32) | u64::from(kind))
    });
}

/// UI: ultimo evento de feedback. A sequencia (32 bits altos) muda a cada
/// disparo, e so o que o C++ precisa pra reiniciar a animacao — nao ha
/// relogio compartilhado entre Rust e o render loop. Nunca bloqueia.
#[no_mangle]
pub extern "C" fn get_playback_feedback_event() -> u64 {
    FEEDBACK_EVENT.load(Ordering::Relaxed)
}

// T1.4/T1.5/T2: modo de exibicao 3D (2D/SBS/OU/360/180) e swap-eyes. Isto e
// puro ESTADO DE APRESENTACAO — nao afeta o Demuxer/PlaybackController (o
// video decodificado e sempre o mesmo frame RGBA; o que muda e SO como
// vr_player_app.cpp escolhe geometria/shader/UV pra desenhar aquele frame).
// Por isso fica aqui como atomics simples em vez de dentro de
// PlaybackController: nao ha necessidade de serializar acesso com o resto do
// estado de playback, e VRPlayerApp::Update() (C++) faz polling barato disto
// a cada frame, exatamente como ja faz com get_video_volume/get_playback_speed.
//
// Codificacao numerica (DEVE casar exatamente com `enum class ScreenMode` em
// native/src/vr_player_app.cpp e com `modeLabelResIds` em
// VRControlsPresentation.kt, que so faz um lookup posicional nesta lista sem
// duplicar os nomes):
//   0=2D, 1=SBS, 2=SBS half, 3=OU, 4=OU half,
//   5=360 mono, 6=180 mono, 7=360 SBS, 8=360 OU, 9=180 SBS
// T2.4/T2.5 (estereo 360/180): a pesquisa sobre como este pipeline OVRFW
// sinaliza "qual olho" pro shader (ver vr_player_app.cpp) achou a resposta —
// o framework ja seta um uniform `ViewID`/`VIEW_ID` (0/1) em toda chamada de
// desenho quando multiview esta desligado (esta neste app), sem precisar de
// GL_OVR_multiview2 nem de um contador manual do lado app. Com isso, a
// separacao real de olho para SBS/OU (flat E esferico) esta implementada —
// ver os shaders em vr_player_app.cpp::SessionInit(). Vr180 OU nao foi
// adicionado ao ciclo (nenhum exemplo real encontrado desse empacotamento;
// trivial de acrescentar depois, o shader ja suporta via uStereoLayout=2 +
// uPolar180=1 juntos).
static SCREEN_MODE: AtomicU32 = AtomicU32::new(0);
const SCREEN_MODE_COUNT: u32 = 10;
static SWAP_EYES: AtomicBool = AtomicBool::new(false);

// Bug reportado em validacao real de headset: o painel "Adicionar servidor"
// (e qualquer outro com EditText) ficava transparente durante a digitacao,
// porque o teclado nativo do Meta Quest (VRActivity.showNativeKeyboardFor)
// e um overlay do SISTEMA, fora do controle deste app — o raio do
// controller aponta pro teclado, nao mais pro quad do painel, entao o
// auto-hide por inatividade (`m_uiIdleTime`, vr_player_app.cpp) contava
// isso como "usuario nao esta olhando pro painel" e comecava o fade out
// depois de kUiAutoHideSeconds, mesmo com o usuario digitando ativamente.
// `KEYBOARD_ACTIVE` e setado pelo Kotlin exatamente no mesmo momento em que
// o teclado nativo abre/fecha (ver VRActivity.showNativeKeyboardFor/
// hideNativeKeyboard) e checado pelo render loop C++ a cada frame pra
// suprimir o auto-hide enquanto for true.
static KEYBOARD_ACTIVE: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "C" fn set_keyboard_active(active: u32) {
    KEYBOARD_ACTIVE.store(active != 0, Ordering::Relaxed);
}

#[no_mangle]
pub extern "C" fn get_keyboard_active() -> u32 {
    KEYBOARD_ACTIVE.load(Ordering::Relaxed) as u32
}

// Fase 0.4 T5: Foveated Rendering (fixo, via extensao OpenXR XR_FB_foveation
// no caminho Vulkan — ver native/src/vr_player_app_vulkan.cpp). Default OFF:
// feature nunca validada em headset real nesta sessao, ligar e acao
// explicita do usuario na tela de Configuracoes (Kotlin). So tem efeito real
// no caminho Vulkan (padrao de build); no caminho GLES o valor e aceito e
// guardado aqui, mas nada le/aplica — OVRFW::XrApp nao expoe o XrSwapchain
// necessario pra xrUpdateSwapchainFB.
static FOVEATION_ENABLED: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub extern "C" fn set_foveation_enabled(enabled: u32) {
    FOVEATION_ENABLED.store(enabled != 0, Ordering::Relaxed);
}

/// Polling de baixa frequencia (~1x/s, nao a cada frame) pelo loop principal
/// do caminho Vulkan — aplicar via xrUpdateSwapchainFB nao e uma operacao de
/// frame, ver comentario em ApplyFoveation.
#[no_mangle]
pub extern "C" fn get_foveation_enabled() -> u32 {
    FOVEATION_ENABLED.load(Ordering::Relaxed) as u32
}

// Fase 0.2 T14: Monitoramento Térmico (RNF-PERF-006).
// Guarda o nível térmico atual enviado pelo Kotlin ThermalMonitor (via JNI/C++).
// 0=NORMAL, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=CRITICAL, 5=SHUTDOWN.
// C++ Vulkan e GLES fazem polling (~1Hz) para aplicar ações no pipeline de render
// (escala de resolução do viewport, refresh rate 72Hz, foveation boost).
static THERMAL_LEVEL: AtomicU32 = AtomicU32::new(0);

#[no_mangle]
pub extern "C" fn set_thermal_level(level: u32) {
    THERMAL_LEVEL.store(level, Ordering::Relaxed);
    // Nível >= 2 (MODERATE) ativa mitigação no prefetch de rede (T14.1/T14.2 PAUSE_PREFETCH)
    protocols::prefetch::set_thermal_throttle_active(level >= 2);
}

#[no_mangle]
pub extern "C" fn get_thermal_level() -> u32 {
    THERMAL_LEVEL.load(Ordering::Relaxed)
}

/// T1.4: avanca pro proximo modo do ciclo (chamado pelo botao "🧊" do painel
/// de controles). Retorna o novo modo, pra o Kotlin nao precisar de uma
/// chamada extra so pra ler de volta o que acabou de escrever.
#[no_mangle]
pub extern "C" fn cycle_3d_mode() -> u32 {
    let next = (SCREEN_MODE.load(Ordering::Relaxed) + 1) % SCREEN_MODE_COUNT;
    SCREEN_MODE.store(next, Ordering::Relaxed);
    next
}

/// Polling barato pelo render loop (C++, a cada frame) — ver
/// VRPlayerApp::Update() em vr_player_app.cpp.
#[no_mangle]
pub extern "C" fn get_3d_mode() -> u32 {
    SCREEN_MODE.load(Ordering::Relaxed)
}

#[no_mangle]
pub extern "C" fn set_3d_mode(mode: u32) {
    if mode < SCREEN_MODE_COUNT {
        SCREEN_MODE.store(mode, Ordering::Relaxed);
    }
}

static SCREEN_MODE_OVERRIDE: AtomicU32 = AtomicU32::new(u32::MAX);

/// Seta um override explicito para o modo 3D do proximo video (ou u32::MAX / valor negativo
/// para "sem override / usar auto-deteccao"). Chamado pelo Kotlin ANTES de disparar o play.
#[no_mangle]
pub extern "C" fn set_screen_mode_override(mode: i32) {
    if mode < 0 || mode >= SCREEN_MODE_COUNT as i32 {
        SCREEN_MODE_OVERRIDE.store(u32::MAX, Ordering::Relaxed);
    } else {
        SCREEN_MODE_OVERRIDE.store(mode as u32, Ordering::Relaxed);
    }
}

/// Aplica o modo 3D apos o carregamento de video: se houver override valido, usa-o;
/// caso contrario, usa o modo detectado pelo controller.
fn apply_screen_mode_after_load(controller: &core::playback::PlaybackController) {
    let ovr = SCREEN_MODE_OVERRIDE.load(Ordering::Relaxed);
    let final_mode = if ovr < SCREEN_MODE_COUNT {
        ovr
    } else {
        controller.detected_screen_mode()
    };
    set_3d_mode(final_mode);
}

/// T9 (historico): ao carregar um arquivo novo, o modo 3D da reproducao
/// anterior nao deve vazar pro proximo video — chamado pelo `load()` da
/// sessao de playback (ver nota em `start_video_playback`/`start_smb_playback`
/// abaixo). Sempre volta pra 2D; auto-deteccao (quando estiver ligada ao
/// bridge, ver docs/phases/PHASE-0.2-3D-NETWORK.md T3.4 "cache de deteccao")
/// fica para uma sessao futura.
///
/// SWAP_EYES deliberadamente NAO e resetado aqui: reportado que o usuario
/// precisava reativar "swap eyes" a cada video, porque o conteudo dele e
/// consistentemente invertido na mesma direcao — a preferencia agora e
/// "sticky" (global, sobrevive entre videos) em vez de por-arquivo.
#[no_mangle]
pub extern "C" fn reset_3d_mode() {
    SCREEN_MODE.store(0, Ordering::Relaxed);
}

/// T1.5: "swap eyes" — inverte qual metade do frame SBS/OU vai pro olho
/// esquerdo/direito. Cuidado do doc (secao 1, CAUTION "Olho trocado causa
/// nausea"): fica facil de alcancar (botao dedicado, nao escondido num menu)
/// justamente por isso.
///
/// Retorna `u32` (0/1) em vez de `bool` — nenhuma outra funcao deste bridge
/// cruza a fronteira C ABI com `bool` (ver as outras funcoes acima, todas
/// f32/i32/u32), mantido assim de proposito para nao introduzir um tipo cujo
/// layout C ABI e menos obviamente estavel entre rustc/NDK clang.
#[no_mangle]
pub extern "C" fn toggle_swap_eyes() -> u32 {
    let next = !SWAP_EYES.load(Ordering::Relaxed);
    SWAP_EYES.store(next, Ordering::Relaxed);
    next as u32
}

#[no_mangle]
pub extern "C" fn get_swap_eyes() -> u32 {
    SWAP_EYES.load(Ordering::Relaxed) as u32
}

#[no_mangle]
pub extern "C" fn get_current_video_frame() -> *mut c_void {
    // try_lock (nao lock!): chamada incondicional a cada frame pelo render
    // loop (C++, GLES e Vulkan). `load()`/`load_at()` (playback.rs) segura
    // este mesmo mutex durante TODO o carregamento — abrir a
    // conexao/arquivo, sondar o demuxer, configurar o MediaCodec — o que
    // pode levar segundos de verdade em rede (SFTP/SMB) ou arquivos
    // grandes. Com `lock()` bloqueante aqui, o render loop inteiro
    // (renderizacao E processamento de input, tudo na mesma thread)
    // travava ate o load() terminar — bug reportado em teste real de
    // hardware ("trava a interface toda ao dar play", pior em SFTP e em
    // arquivos maiores, exatamente o padrao de demora do load()). Se o
    // lock estiver ocupado, so devolve null — o render loop ja trata isso
    // como "sem frame ainda" (cai no quad solido de fallback), sem travar.
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_current_frame(),
        Err(_) => std::ptr::null_mut(),
    }
}

// Cache do ultimo valor lido de cada contador de instrumentacao — usado
// quando CONTROLLER.try_lock() falha por contencao (load()/seek() em
// andamento). Achado desta sessao (docs/NETWORK-IO-PERFORMANCE.md): as
// versoes anteriores destas funcoes devolviam 0 nesse caso, e o C++ calcula
// um DELTA em uint64_t contra a ultima amostra — 0 produzia um delta negativo
// que "dava a volta" (wraparound unsigned) e um pico de decFps absurdo na
// amostra seguinte. Devolver o ultimo valor conhecido faz o delta dessa
// amostra ser 0 (sem progresso observado), o que e a resposta correta —
// "nao sei" nao e "zero frames".
static LAST_FRAMES_DECODED: AtomicU64 = AtomicU64::new(0);
static LAST_FRAMES_OUTPUT: AtomicU64 = AtomicU64::new(0);
static LAST_FRAMES_DROPPED: AtomicU64 = AtomicU64::new(0);
static LAST_NETWORK_BYTES: AtomicU64 = AtomicU64::new(0);

/// Debug (docs/DEBUGGING.md): total de frames REALMENTE decodificados pela
/// thread de decode, independente de quantas vezes o C++ chama
/// get_current_video_frame() acima pra observar isso. O C++ amostra este
/// contador ao longo do tempo e calcula um delta (frames/segundo real do
/// decode) pra comparar contra o vidFps que ele mesmo calcula por polling —
/// se os dois nao baterem, o gargalo esta no lado do consumo (import/cache
/// Vulkan, taxa de polling do render loop), nao no decode em si. try_lock
/// pelo mesmo motivo de get_current_video_frame() acima (chamada automatica
/// e periodica, nunca deve bloquear o render loop); em contencao devolve o
/// ultimo valor conhecido (ver LAST_FRAMES_DECODED acima), nao 0.
#[no_mangle]
pub extern "C" fn get_video_frames_decoded_count() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => {
            let v = controller.get_frames_decoded_count();
            LAST_FRAMES_DECODED.store(v, Ordering::Relaxed);
            v
        }
        Err(_) => LAST_FRAMES_DECODED.load(Ordering::Relaxed),
    }
}

/// Debug (docs/DEBUGGING.md): total de buffers de saida que o MediaCodec
/// REALMENTE desenfileirou, independente do callback de sync decidir
/// renderizar ou descartar por atraso — ver PlaybackController::get_frames_output_count.
/// Diferente de get_video_frames_decoded_count() (que so conta frames
/// RENDERIZADOS): a investigacao desta sessao achou que os dois numeros
/// batendo nao prova que o decode esta saudavel, porque decoded_count ja e
/// filtrado pelo mesmo descarte por atraso — este contador nao e.
#[no_mangle]
pub extern "C" fn get_video_frames_output_count() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => {
            let v = controller.get_frames_output_count();
            LAST_FRAMES_OUTPUT.store(v, Ordering::Relaxed);
            v
        }
        Err(_) => LAST_FRAMES_OUTPUT.load(Ordering::Relaxed),
    }
}

/// Debug (docs/DEBUGGING.md): frames que o MediaCodec produziu mas foram
/// descartados por chegarem atrasados (LATE_FRAME_RENDER_SKIP_SEC).
#[no_mangle]
pub extern "C" fn get_video_frames_dropped_count() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => {
            let v = controller.get_frames_dropped_count();
            LAST_FRAMES_DROPPED.store(v, Ordering::Relaxed);
            v
        }
        Err(_) => LAST_FRAMES_DROPPED.load(Ordering::Relaxed),
    }
}

/// Debug (docs/DEBUGGING.md): quantos pacotes de video estao bufferizados
/// entre a thread de demux e a de decode agora — sem cache-on-contencao
/// porque um valor "errado por um frame" aqui nao produz nenhum artefato
/// tipo wraparound (nao e usado pra calcular delta), so um numero levemente
/// atrasado.
#[no_mangle]
pub extern "C" fn get_video_queue_depth() -> u32 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_video_queue_depth(),
        Err(_) => 0,
    }
}

/// Debug (docs/DEBUGGING.md): bytes recebidos da rede pelo PrefetchReader da
/// fonte atual, soma cumulativa (0 para arquivo local/`http://` puro). O C++
/// amostra isto ao longo do tempo e calcula MB/s, mesmo padrao de decFps.
#[no_mangle]
pub extern "C" fn get_network_bytes_read() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => {
            let v = controller.get_network_bytes_read();
            LAST_NETWORK_BYTES.store(v, Ordering::Relaxed);
            v
        }
        Err(_) => LAST_NETWORK_BYTES.load(Ordering::Relaxed),
    }
}

/// Debug (docs/DEBUGGING.md): duracao do ULTIMO fetch de bloco completo do
/// PrefetchReader, em ms — diferencia throughput estavel de media baixa por
/// stalls pontuais. Sem cache-on-contencao: um valor levemente atrasado nao
/// produz artefato (nao e usado em calculo de delta).
#[no_mangle]
pub extern "C" fn get_network_last_block_fetch_ms() -> f32 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_network_last_block_fetch_ms(),
        Err(_) => 0.0,
    }
}

/// Debug (docs/DEBUGGING.md): blocos buscados e descartados por seek do
/// PrefetchReader — ver PlaybackController::get_network_blocks_discarded.
#[no_mangle]
pub extern "C" fn get_network_blocks_fetched() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_network_blocks_fetched(),
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "C" fn get_network_blocks_discarded() -> u64 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_network_blocks_discarded(),
        Err(_) => 0,
    }
}

// Debug (docs/DEBUGGING.md): duracao do ultimo seek concluido, em ms.
#[no_mangle]
pub extern "C" fn get_last_seek_latency_ms() -> u32 {
    match CONTROLLER.try_lock() {
        Ok(controller) => controller.get_last_seek_latency_ms(),
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "C" fn toggle_play_pause() {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.toggle_play_pause();
        emit_playback_feedback(if controller.is_playing() {
            FEEDBACK_PLAY
        } else {
            FEEDBACK_PAUSE
        });
    }
}

#[no_mangle]
pub extern "C" fn on_app_focus_lost() {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.on_focus_lost();
    }
}

#[no_mangle]
pub extern "C" fn on_app_focus_gained() {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.on_focus_gained();
    }
}

unsafe fn log(level: i32, msg: &str) {
    let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
    let msg = std::ffi::CString::new(msg).unwrap();
    ndk_sys::__android_log_print(level, tag.as_ptr(), msg.as_ptr());
}

/// Le uma `*const c_char` vinda do JNI. `None` so em ponteiro nulo ou UTF-8
/// invalido — string vazia (`""`, usada para "sem usuario/senha" = guest no
/// SMB) e um `Some("")` valido, nao `None`.
unsafe fn cstr_to_string(ptr: *const std::os::raw::c_char) -> Option<String> {
    if ptr.is_null() {
        return None;
    }
    unsafe { std::ffi::CStr::from_ptr(ptr) }.to_str().ok().map(|s| s.to_string())
}

/// Converte uma `String` Rust numa `*mut c_char` que o Kotlin le via JNI e
/// DEVE devolver com `free_rust_string` (ver essa fn). Usado pelas funcoes
/// de listagem SMB e pelo probe de HTTP — todas retornam uma unica linha de
/// texto (erro) ou multiplas linhas separadas por `\n` (listagens).
fn string_to_c_char(s: String) -> *mut std::os::raw::c_char {
    // Um NUL embutido so poderia vir de um nome de arquivo/share ridiculo;
    // CString::new falharia nesse caso — troca por um placeholder em vez de
    // propagar o erro (essa funcao nao tem como retornar Result para o C ABI).
    std::ffi::CString::new(s)
        .unwrap_or_else(|_| std::ffi::CString::new("ERROR:nome invalido (NUL embutido)").unwrap())
        .into_raw()
}

/// Libera uma string retornada por `smb_list_shares`, `smb_list_directory`,
/// `probe_http_url` ou `read_media_metadata`/`smb_read_metadata`/
/// `ftp_read_metadata`/`sftp_read_metadata`. Toda `*mut c_char` que este
/// bridge retorna (exceto via parametros `out`) precisa passar por aqui —
/// sem isso, vaza memoria do lado Rust a cada chamada.
#[no_mangle]
pub extern "C" fn free_rust_string(ptr: *mut std::os::raw::c_char) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        drop(std::ffi::CString::from_raw(ptr));
    }
}

fn set_last_playback_error(msg: String) {
    if let Ok(mut slot) = LAST_PLAYBACK_ERROR.lock() {
        *slot = Some(msg);
    }
}

/// Consome (le e limpa) o erro do ultimo load() que falhou, ou nulo se nao houve nenhum desde
/// a ultima chamada. Kotlin faz polling disto pra mostrar um Toast — antes o erro so ia pro logcat.
/// Retorno (se nao nulo) precisa ser liberado com `free_rust_string`.
#[no_mangle]
pub extern "C" fn take_last_playback_error() -> *mut std::os::raw::c_char {
    let taken = LAST_PLAYBACK_ERROR.lock().ok().and_then(|mut slot| slot.take());
    match taken {
        Some(msg) => string_to_c_char(msg),
        None => std::ptr::null_mut(),
    }
}

/// T6.4: inicia playback de um arquivo via SMB. Recebe host/share/caminho/
/// credenciais como parametros SEPARADOS (nunca uma URI unica com a senha
/// embutida cruzando a fronteira JNI) — o Kotlin resolve as credenciais
/// salvas em `EncryptedSharedPreferences` (ver `SmbCredentialStore.kt`) e
/// chama isto na hora de tocar, sem nunca montar uma string
/// `smb://user:pass@host/...` em nenhum lugar persistido (doc, secao 6,
/// aviso "Credenciais"). A URI interna com credenciais (ver
/// `protocols::smb::uri`) so existe depois disso, dentro da memoria do
/// processo Rust (`PlaybackController::current_path`), nunca logada por
/// inteiro nem devolvida ao Kotlin.
#[no_mangle]
pub extern "C" fn start_smb_playback(
    host: *const std::os::raw::c_char,
    port: i32,
    share: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
    start_time_sec: f32,
) {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return };
        let share = match cstr_to_string(share) { Some(s) => s, None => return };
        let path = match cstr_to_string(path) { Some(s) => s, None => return };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share,
            path,
            username,
            password,
            domain,
        }
    };

    spawn_loading(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading SMB video: {}", protocols::smb::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load_at(&internal_uri, f64::from(start_time_sec)) {
                unsafe { log(6, &format!("Error loading SMB video: {:?}", e)); }
                set_last_playback_error(format!("{:?}", e));
            } else {
                apply_screen_mode_after_load(&controller);
                unsafe { log(4, "SMB video loaded successfully!"); }
            }
        }
    });
}

/// T6.1/T6.4: lista os shares de um servidor. Tambem serve como "testar
/// conexao"/"status de conexao" da UI — se isto tem sucesso, autenticacao e
/// conectividade estao OK. Chamada BLOQUEANTE (I/O de rede sincrono) —
/// Kotlin deve chamar isto de uma coroutine em `Dispatchers.IO`, nunca da UI
/// thread. Retorna as shares separadas por `\n`, ou `"ERROR:<mensagem>"`.
#[no_mangle]
pub extern "C" fn smb_list_shares(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".into()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share: String::new(),
            path: String::new(),
            username,
            password,
            domain,
        }
    };

    match protocols::smb::list_shares(&target) {
        Ok(shares) => string_to_c_char(shares.join("\n")),
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T6.1/T6.4: lista um diretorio dentro de um share (navegacao do file
/// browser SMB). `path` vazio = raiz do share. Chamada BLOQUEANTE, mesma
/// ressalva de `smb_list_shares`. Cada linha do retorno e
/// `nome\t{0|1}\ttamanho` (1 = diretorio), ou `"ERROR:<mensagem>"`.
#[no_mangle]
pub extern "C" fn smb_list_directory(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
    share: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".into()) };
        let share = match cstr_to_string(share) { Some(s) => s, None => return string_to_c_char("ERROR:share invalido".into()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share,
            path: String::new(),
            username,
            password,
            domain,
        }
    };
    let path = unsafe { cstr_to_string(path).unwrap_or_default() };

    match protocols::smb::list_directory(&target, &path) {
        Ok(entries) => {
            let lines: Vec<String> = entries
                .into_iter()
                .map(|e| format!("{}\t{}\t{}", e.name, if e.is_dir { 1 } else { 0 }, e.size))
                .collect();
            string_to_c_char(lines.join("\n"))
        }
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T6.4: inicia playback de um arquivo via FTP. Mesma logica de
/// `start_smb_playback` (credenciais como parametros separados, nunca uma
/// URI unica cruzando o JNI) — ver esse comentario para a justificativa
/// completa, identica aqui.
#[no_mangle]
pub extern "C" fn start_ftp_playback(
    host: *const std::os::raw::c_char,
    port: i32,
    path: *const std::os::raw::c_char,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    start_time_sec: f32,
) {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return };
        let path = match cstr_to_string(path) { Some(s) => s, None => return };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        protocols::ftp::FtpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
        }
    };

    spawn_loading(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading FTP video: {}", protocols::ftp::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load_at(&internal_uri, f64::from(start_time_sec)) {
                unsafe { log(6, &format!("Error loading FTP video: {:?}", e)); }
                set_last_playback_error(format!("{:?}", e));
            } else {
                apply_screen_mode_after_load(&controller);
                unsafe { log(4, "FTP video loaded successfully!"); }
            }
        }
    });
}

/// T6.1/T6.4: lista um diretorio num servidor FTP (tambem serve de "testar
/// conexao"). Chamada BLOQUEANTE, mesma ressalva de `smb_list_shares` —
/// Kotlin SEMPRE de `Dispatchers.IO`. Cada linha do retorno e
/// `nome\t{0|1}\ttamanho` (1 = diretorio), ou `"ERROR:<mensagem>"`.
#[no_mangle]
pub extern "C" fn ftp_list_directory(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".into()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        protocols::ftp::FtpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path: String::new(),
            username,
            password,
        }
    };
    let path = unsafe { cstr_to_string(path).unwrap_or_default() };

    match protocols::ftp::list_directory(&target, &path) {
        Ok(entries) => {
            let lines: Vec<String> = entries
                .into_iter()
                .map(|e| format!("{}\t{}\t{}", e.name, if e.is_dir { 1 } else { 0 }, e.size))
                .collect();
            string_to_c_char(lines.join("\n"))
        }
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T6.4: inicia playback de um arquivo via SFTP. Mesma logica de
/// `start_ftp_playback`/`start_smb_playback` (credenciais como parametros
/// separados). `private_key` e o CONTEUDO PEM (nao um caminho de arquivo —
/// ver `protocols::sftp::uri`), ponteiro nulo ou string vazia = autenticacao
/// por senha em vez de chave.
#[no_mangle]
pub extern "C" fn start_sftp_playback(
    host: *const std::os::raw::c_char,
    port: i32,
    path: *const std::os::raw::c_char,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    private_key: *const std::os::raw::c_char,
    start_time_sec: f32,
) {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return };
        let path = match cstr_to_string(path) { Some(s) => s, None => return };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let private_key = cstr_to_string(private_key).filter(|s| !s.is_empty());
        protocols::sftp::SftpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
            private_key,
        }
    };
    if let Err(e) = target.validate() {
        unsafe { log(6, &format!("Error loading SFTP video: {e}")); }
        set_last_playback_error(e);
        return;
    }

    spawn_loading(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading SFTP video: {}", protocols::sftp::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load_at(&internal_uri, f64::from(start_time_sec)) {
                unsafe { log(6, &format!("Error loading SFTP video: {:?}", e)); }
                set_last_playback_error(format!("{:?}", e));
            } else {
                apply_screen_mode_after_load(&controller);
                unsafe { log(4, "SFTP video loaded successfully!"); }
            }
        }
    });
}

/// T6.2/T6.4: lista um diretorio num servidor SFTP (tambem serve de "testar
/// conexao"). Chamada BLOQUEANTE, mesma ressalva de `smb_list_shares`.
/// Mesmo formato de retorno de `ftp_list_directory`.
#[no_mangle]
pub extern "C" fn sftp_list_directory(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    private_key: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".into()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let private_key = cstr_to_string(private_key).filter(|s| !s.is_empty());
        protocols::sftp::SftpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path: String::new(),
            username,
            password,
            private_key,
        }
    };
    if let Err(e) = target.validate() {
        return string_to_c_char(format!("ERROR:{e}"));
    }
    let path = unsafe { cstr_to_string(path).unwrap_or_default() };

    match protocols::sftp::list_directory(&target, &path) {
        Ok(entries) => {
            let lines: Vec<String> = entries
                .into_iter()
                .map(|e| format!("{}\t{}\t{}", e.name, if e.is_dir { 1 } else { 0 }, e.size))
                .collect();
            string_to_c_char(lines.join("\n"))
        }
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T5.1/T5.4: Inicia playback de vídeo a partir de um compartilhamento NFS.
#[no_mangle]
pub extern "C" fn start_nfs_playback(
    host: *const std::os::raw::c_char,
    port: i32,
    export_path: *const std::os::raw::c_char,
    file_path: *const std::os::raw::c_char,
    version: i32,
    start_time_sec: f32,
) {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return };
        let export_path = match cstr_to_string(export_path) { Some(s) => s, None => return };
        let file_path = match cstr_to_string(file_path) { Some(s) => s, None => return };
        protocols::nfs::NfsTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            export_path,
            file_path,
            version: version.clamp(3, 4) as u8,
        }
    };

    spawn_loading(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading NFS video: {}", protocols::nfs::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load_at(&internal_uri, f64::from(start_time_sec)) {
                unsafe { log(6, &format!("Error loading NFS video: {:?}", e)); }
                set_last_playback_error(format!("{:?}", e));
            } else {
                apply_screen_mode_after_load(&controller);
                unsafe { log(4, "NFS video loaded successfully!"); }
            }
        }
    });
}

/// T5.2/T5.4: Lista arquivos e pastas num servidor NFS. Chamada BLOQUEANTE.
#[no_mangle]
pub extern "C" fn nfs_list_directory(
    host: *const std::os::raw::c_char,
    port: i32,
    export_path: *const std::os::raw::c_char,
    dir_path: *const std::os::raw::c_char,
    version: i32,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".into()) };
        let export_path = match cstr_to_string(export_path) { Some(s) => s, None => return string_to_c_char("ERROR:export_path invalido".into()) };
        protocols::nfs::NfsTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            export_path,
            file_path: String::new(),
            version: version.clamp(3, 4) as u8,
        }
    };
    let dir_path = unsafe { cstr_to_string(dir_path).unwrap_or_default() };

    match protocols::nfs::list_directory(&target, &dir_path) {
        Ok(entries) => {
            let lines: Vec<String> = entries
                .into_iter()
                .map(|e| format!("{}\t{}\t{}", e.name, if e.is_dir { 1 } else { 0 }, e.size))
                .collect();
            string_to_c_char(lines.join("\n"))
        }
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T5.2/T5.4: Lista exports disponíveis num servidor NFS. Chamada BLOQUEANTE.
#[no_mangle]
pub extern "C" fn nfs_list_exports(
    host: *const std::os::raw::c_char,
    port: i32,
) -> *mut std::os::raw::c_char {
    let host = match unsafe { cstr_to_string(host) } {
        Some(s) => s,
        None => return string_to_c_char("ERROR:host invalido".into()),
    };
    let port = port.clamp(1, u16::MAX as i32) as u16;

    match protocols::nfs::list_exports(&host, port) {
        Ok(exports) => string_to_c_char(exports.join("\n")),
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace('\n', " "))),
    }
}

/// T10.1: Varredura de servidores na rede local (mDNS + SSDP). Chamada BLOQUEANTE.
/// Retorna linhas separadas por '\n': "PROTOCOL\tNAME\tHOST\tPORT\tPATH"
#[no_mangle]
pub extern "C" fn discovery_scan_network(timeout_ms: u32) -> *mut std::os::raw::c_char {
    let servers = protocols::discovery::scan_local_network(timeout_ms as u64);
    let lines: Vec<String> = servers
        .into_iter()
        .map(|s| format!("{}\t{}\t{}\t{}\t{}", s.protocol, s.name, s.host, s.port, s.path))
        .collect();
    string_to_c_char(lines.join("\n"))
}

/// T7.1: probe HEAD-based de uma URL HTTP(S) — descobre ANTES de tocar se o
/// servidor suporta range requests (necessario pra seek) e o tamanho do
/// arquivo, para a UI poder avisar o usuario (doc, secao 7, aviso
/// explicito). Chamada BLOQUEANTE (rede sincrona) — chamar de
/// `Dispatchers.IO`. Retorno: `"OK\t{seekable 0|1}\t{content_length ou -1}"`
/// ou `"ERROR:<mensagem>"`.
#[no_mangle]
pub extern "C" fn probe_http_url(url: *const std::os::raw::c_char) -> *mut std::os::raw::c_char {
    let url = match unsafe { cstr_to_string(url) } {
        Some(s) => s,
        None => return string_to_c_char("ERROR:URL invalida".into()),
    };
    let caps = protocols::http::probe(&url);
    if !caps.reachable {
        return string_to_c_char(format!(
            "ERROR:{}",
            caps.error.unwrap_or_else(|| format!("HTTP {}", caps.status)).replace('\n', " ")
        ));
    }
    string_to_c_char(format!(
        "OK\t{}\t{}",
        if caps.seekable { 1 } else { 0 },
        caps.content_length.map(|v| v as i64).unwrap_or(-1)
    ))
}

/// `start_video_playback`/`seek_video_playback`/`cycle_audio_track` sao
/// chamados via JNI a partir da UI thread do Android (botoes/seekbar).
/// `PlaybackController::stop()`/`load()`/`seek()` agora fazem `join()`
/// de verdade nas threads da sessao anterior (ver playback.rs) — mesmo
/// com timeouts internos para evitar bloqueio indefinido, isso ainda
/// pode levar alguns instantes. Rodar essa logica direto na UI thread
/// travaria o app inteiro (ANR) enquanto espera. Por isso essas 3
/// chamadas so despacham o trabalho para uma thread separada e retornam
/// imediatamente.
#[no_mangle]
pub extern "C" fn start_video_playback(path: *const std::os::raw::c_char, start_time_sec: f32) {
    if path.is_null() { return; }
    let c_str = unsafe { std::ffi::CStr::from_ptr(path) };
    let path_str = match c_str.to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return,
    };

    spawn_loading(move || {
        unsafe { log(4, &format!("Loading video: {}", path_str)); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load_at(&path_str, f64::from(start_time_sec)) {
                unsafe { log(6, &format!("Error loading video: {:?}", e)); }
                set_last_playback_error(format!("{:?}", e));
            } else {
                apply_screen_mode_after_load(&controller);
                unsafe { log(4, "Video loaded successfully!"); }
            }
        }
    });
}

#[no_mangle]
pub extern "C" fn stop_video_playback() {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.stop();
    }
}

#[no_mangle]
pub extern "C" fn seek_video_playback(position: f32) {
    // Direcao do icone tem que sair ANTES do spawn_loading: depois do seek a
    // posicao corrente ja e a de destino. try_lock pra nunca atrasar a UI
    // thread — sem posicao conhecida o overlay so nao dispara.
    if let Ok(controller) = CONTROLLER.try_lock() {
        let (current, _) = controller.get_progress();
        emit_playback_feedback(if f64::from(position) >= current {
            FEEDBACK_SEEK_FORWARD
        } else {
            FEEDBACK_SEEK_BACKWARD
        });
    }

    spawn_loading(move || {
        if let Ok(mut controller) = CONTROLLER.lock() {
            if let Err(e) = controller.seek(position as f64) {
                unsafe { log(6, &format!("Error seeking video: {e}")); }
                set_last_playback_error(e);
            }
        }
    });
}

// Espelho lock-free: chamado todo frame pelo render loop (try_lock, nunca lock()); em
// contencao o controller so reaplica no proximo frame.
static VOLUME_BITS: AtomicU32 = AtomicU32::new(0x3F800000); // 1.0f32

#[no_mangle]
pub extern "C" fn set_video_volume(volume: f32) {
    VOLUME_BITS.store(volume.clamp(0.0, 1.0).to_bits(), Ordering::Relaxed);
    if let Ok(mut controller) = CONTROLLER.try_lock() {
        controller.set_volume(volume);
    }
}

#[no_mangle]
pub extern "C" fn get_video_volume() -> f32 {
    f32::from_bits(VOLUME_BITS.load(Ordering::Relaxed))
}

#[no_mangle]
pub extern "C" fn set_playback_speed(speed: f32) {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.set_speed(speed);
    }
}

#[no_mangle]
pub extern "C" fn get_playback_speed() -> f32 {
    if let Ok(controller) = CONTROLLER.lock() {
        controller.get_speed()
    } else {
        1.0
    }
}

#[no_mangle]
pub extern "C" fn cycle_audio_track() {
    spawn_loading(move || {
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.cycle_audio_track();
        }
    });
}

#[no_mangle]
pub extern "C" fn get_audio_track_count() -> u32 {
    if let Ok(controller) = CONTROLLER.lock() {
        controller.audio_track_count() as u32
    } else {
        0
    }
}

/// Seleciona a trilha de audio desejada pro PROXIMO `load_at()` — nao troca
/// a trilha ao vivo (ver `PlaybackController::select_audio_track`). Uso
/// pretendido: a tela de detalhe do arquivo chama isto antes de tocar.
/// `desired_audio_track` e estado GLOBAL do controller — o chamador DEVE
/// chamar isto sempre antes de tocar (inclusive `ordinal = 0`), senao a
/// escolha feita num arquivo vaza silenciosamente pro proximo.
#[no_mangle]
pub extern "C" fn set_desired_audio_track(ordinal: u32) {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.select_audio_track(ordinal as usize);
    }
}

/// Atualiza a orientação da cabeça (quaternion x, y, z, w) a cada frame do OpenXR (~90Hz).
/// Lock-free, chamado diretamente pelo render loop C++.
#[no_mangle]
pub extern "C" fn set_head_pose_orientation(x: f32, y: f32, z: f32, w: f32) {
    media_logic::spatial_audio::set_global_head_orientation(x, y, z, w);
}

/// Configura o modo de processamento espacial:
/// 0 = DirectStereo (pass-through), 1 = VirtualizedBinaural (HRTF 3D), 2 = SimpleDownmix.
#[no_mangle]
pub extern "C" fn set_spatial_audio_mode(mode: u32) {
    media_logic::spatial_audio::set_global_spatial_mode(media_logic::spatial_audio::SpatialAudioMode::from(mode));
}

#[no_mangle]
pub extern "C" fn get_spatial_audio_mode() -> u32 {
    media_logic::spatial_audio::get_global_spatial_mode() as u32
}

/// Ativa ou desativa rastreamento de cabeça (head tracking) no áudio espacial.
#[no_mangle]
pub extern "C" fn set_spatial_audio_head_tracking(enabled: u32) {
    media_logic::spatial_audio::set_global_head_tracking_enabled(enabled != 0);
}

#[no_mangle]
pub extern "C" fn get_spatial_audio_head_tracking() -> u32 {
    media_logic::spatial_audio::get_global_head_tracking_enabled() as u32
}

/// Libera o buffer retornado por `smb_generate_thumbnail`/
/// `ftp_generate_thumbnail`/`sftp_generate_thumbnail`. `len` precisa ser
/// exatamente o valor escrito em `out_len` por essas chamadas — o buffer foi
/// alocado com essa capacidade exata (ver `write_thumbnail` abaixo), entao
/// reconstruir o `Vec` com um `len` diferente e undefined behavior.
#[no_mangle]
pub extern "C" fn free_rust_thumbnail_buffer(ptr: *mut u8, len: usize) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        drop(Vec::from_raw_parts(ptr, len, len));
    }
}

/// Converte o resultado de `core::thumbnail::generate` num ponteiro cru RGBA
/// (ou nulo em qualquer falha) + as dimensoes/tamanho via out-params —
/// mesmo padrao dos out-params de `get_video_progress` abaixo, escolhido em
/// vez de um struct `#[repr(C)]` pra manter a fronteira C ABI so com tipos
/// primitivos (mesma razao documentada em `toggle_swap_eyes` sobre `bool`).
fn write_thumbnail(
    image: Option<core::thumbnail::ThumbnailImage>,
    out_width: *mut u32,
    out_height: *mut u32,
    out_len: *mut usize,
) -> *mut u8 {
    let (ptr, width, height, len) = match image {
        Some(img) => {
            let mut buf = img.rgba;
            buf.shrink_to_fit();
            let len = buf.len();
            let ptr = buf.as_mut_ptr();
            std::mem::forget(buf);
            (ptr, img.width, img.height, len)
        }
        None => (std::ptr::null_mut(), 0, 0, 0),
    };
    unsafe {
        if !out_width.is_null() { *out_width = width; }
        if !out_height.is_null() { *out_height = height; }
        if !out_len.is_null() { *out_len = len; }
    }
    ptr
}

/// Gera um thumbnail (RGBA cru, `max_width * max_height * 4` bytes) de um
/// video num share SMB, decodificando UM frame por software — ver
/// `core::thumbnail::generate` para os detalhes (seek de 1s pra dentro,
/// limite de pacotes, sem MediaCodec). Chamada BLOQUEANTE (rede + decode
/// sincronos) — Kotlin SEMPRE de `Dispatchers.IO`, uma por item de video
/// visivel na listagem (mesmo cuidado de "nunca gerar pra pasta inteira de
/// uma vez" do `ThumbnailGenerator` local). Retorna nulo em qualquer falha
/// (sem popup de erro — mesmo contrato de silencio do lado local); o
/// ponteiro retornado (se nao nulo) DEVE ser liberado com
/// `free_rust_thumbnail_buffer`.
#[no_mangle]
pub extern "C" fn smb_generate_thumbnail(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
    share: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    max_width: u32,
    max_height: u32,
    out_width: *mut u32,
    out_height: *mut u32,
    out_len: *mut usize,
) -> *mut u8 {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let share = match cstr_to_string(share) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share,
            path,
            username,
            password,
            domain,
        }
    };
    let internal_uri = target.to_internal();
    let image = core::thumbnail::generate(&internal_uri, max_width, max_height);
    write_thumbnail(image, out_width, out_height, out_len)
}

/// Mesma logica de `smb_generate_thumbnail`, para um arquivo num servidor
/// FTP. Ver esse comentario para a justificativa completa (identica aqui).
#[no_mangle]
pub extern "C" fn ftp_generate_thumbnail(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    max_width: u32,
    max_height: u32,
    out_width: *mut u32,
    out_height: *mut u32,
    out_len: *mut usize,
) -> *mut u8 {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        protocols::ftp::FtpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
        }
    };
    let internal_uri = target.to_internal();
    let image = core::thumbnail::generate(&internal_uri, max_width, max_height);
    write_thumbnail(image, out_width, out_height, out_len)
}

/// Mesma logica de `smb_generate_thumbnail`, para um arquivo num servidor
/// SFTP. `private_key` e o CONTEUDO PEM (nao um caminho de arquivo — mesma
/// convencao de `start_sftp_playback`), ponteiro nulo ou string vazia =
/// autenticacao por senha em vez de chave.
#[no_mangle]
pub extern "C" fn sftp_generate_thumbnail(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    private_key: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    max_width: u32,
    max_height: u32,
    out_width: *mut u32,
    out_height: *mut u32,
    out_len: *mut usize,
) -> *mut u8 {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return write_thumbnail(None, out_width, out_height, out_len) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let private_key = cstr_to_string(private_key).filter(|s| !s.is_empty());
        protocols::sftp::SftpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
            private_key,
        }
    };
    if target.validate().is_err() {
        return write_thumbnail(None, out_width, out_height, out_len);
    }
    let internal_uri = target.to_internal();
    let image = core::thumbnail::generate(&internal_uri, max_width, max_height);
    write_thumbnail(image, out_width, out_height, out_len)
}

/// Libera o buffer retornado por `smb_generate_thumbnail_strip`/
/// `sftp_generate_thumbnail_strip`. Mesma ressalva de
/// `free_rust_thumbnail_buffer`: `len` precisa ser exatamente o `out_len`
/// devolvido por essas chamadas.
#[no_mangle]
pub extern "C" fn free_rust_thumbnail_strip(ptr: *mut u8, len: usize) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        drop(Vec::from_raw_parts(ptr, len, len));
    }
}

/// Como `write_thumbnail`, mas pra `core::thumbnail::ThumbnailStrip` —
/// `out_count` e o numero de frames concatenados no buffer (a posicao do
/// frame `i` e sempre `(i+1) * interval_secs`, o Kotlin ja sabe
/// `interval_secs` porque foi ele quem pediu).
fn write_thumbnail_strip(
    strip: Option<core::thumbnail::ThumbnailStrip>,
    out_width: *mut u32,
    out_height: *mut u32,
    out_count: *mut usize,
    out_len: *mut usize,
) -> *mut u8 {
    let (ptr, width, height, count, len) = match strip {
        Some(s) => {
            let mut buf = s.rgba;
            buf.shrink_to_fit();
            let len = buf.len();
            let ptr = buf.as_mut_ptr();
            std::mem::forget(buf);
            (ptr, s.width, s.height, s.count, len)
        }
        None => (std::ptr::null_mut(), 0, 0, 0, 0),
    };
    unsafe {
        if !out_width.is_null() { *out_width = width; }
        if !out_height.is_null() { *out_height = height; }
        if !out_count.is_null() { *out_count = count; }
        if !out_len.is_null() { *out_len = len; }
    }
    ptr
}

/// Gera uma trilha de thumbnails (preview de arrasto no seekbar, T-seek-ux)
/// de um video num share SMB — ver `core::thumbnail::generate_strip`.
/// Mesmas ressalvas de `smb_generate_thumbnail` (chamada BLOQUEANTE, so de
/// `Dispatchers.IO`, retorna nulo em qualquer falha); o ponteiro retornado
/// (se nao nulo) DEVE ser liberado com `free_rust_thumbnail_strip`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn smb_generate_thumbnail_strip(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
    share: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    interval_secs: f32,
    max_width: u32,
    max_height: u32,
    out_width: *mut u32,
    out_height: *mut u32,
    out_count: *mut usize,
    out_len: *mut usize,
) -> *mut u8 {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return write_thumbnail_strip(None, out_width, out_height, out_count, out_len) };
        let share = match cstr_to_string(share) { Some(s) => s, None => return write_thumbnail_strip(None, out_width, out_height, out_count, out_len) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return write_thumbnail_strip(None, out_width, out_height, out_count, out_len) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share,
            path,
            username,
            password,
            domain,
        }
    };
    // Experimento (docs/SEEK-NETWORK-STUTTER-NEXT-SESSION.md): antes, `playback_is_active()`
    // recusava a tira inteira se o playback estivesse ativo (duas sessoes MediaCodec
    // concorrentes derrubavam o driver de video). Agora serializa so a JANELA de
    // setup (new+configure+start) via HwDecoder::new_configured_and_started
    // (SESSION_SETUP_LOCK em core/src/decoder.rs), deixando o resto da decodificacao
    // rodar concorrente com o playback principal — precisa validar em hardware real
    // que isso de fato evita o crash antigo antes de considerar definitivo.
    let internal_uri = target.to_internal();
    let strip = core::thumbnail::generate_strip(&internal_uri, interval_secs as f64, max_width, max_height);
    write_thumbnail_strip(strip, out_width, out_height, out_count, out_len)
}

/// Mesma logica de `smb_generate_thumbnail_strip`, para um arquivo num
/// servidor SFTP — `private_key` segue a mesma convencao de
/// `sftp_generate_thumbnail` (conteudo PEM, nao caminho de arquivo).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "C" fn sftp_generate_thumbnail_strip(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    private_key: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
    interval_secs: f32,
    max_width: u32,
    max_height: u32,
    out_width: *mut u32,
    out_height: *mut u32,
    out_count: *mut usize,
    out_len: *mut usize,
) -> *mut u8 {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return write_thumbnail_strip(None, out_width, out_height, out_count, out_len) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return write_thumbnail_strip(None, out_width, out_height, out_count, out_len) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let private_key = cstr_to_string(private_key).filter(|s| !s.is_empty());
        protocols::sftp::SftpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
            private_key,
        }
    };
    if target.validate().is_err() {
        return write_thumbnail_strip(None, out_width, out_height, out_count, out_len);
    }
    // Ver comentario equivalente em smb_generate_thumbnail_strip acima — experimento,
    // playback_is_active() nao gate mais aqui, SESSION_SETUP_LOCK assume esse papel.
    let internal_uri = target.to_internal();
    let strip = core::thumbnail::generate_strip(&internal_uri, interval_secs as f64, max_width, max_height);
    write_thumbnail_strip(strip, out_width, out_height, out_count, out_len)
}

/// Interrompe uma geracao de tira de scrub em andamento (`sftp_generate_thumbnail_strip`/
/// `smb_generate_thumbnail_strip`) — essas chamadas sao sincronas/bloqueantes
/// e um Job.cancel() do lado Kotlin nao as interrompe (ver
/// docs/SEEK-NETWORK-STUTTER-NEXT-SESSION.md). Chamar quando o arrasto do
/// tracker termina (`stopScrubPreview`), pra nao deixar uma geracao orfa
/// competindo por banda/decoder com o playback principal.
#[no_mangle]
pub extern "C" fn cancel_thumbnail_strip_generation() {
    core::thumbnail::cancel_strip_generation();
}

/// Roda `core::metadata::extract` e serializa o resultado (ver
/// `media_logic::metadata_wire`) numa string liberada com `free_rust_string`,
/// ou `"ERROR:<msg>"` em qualquer falha — mesmo contrato de erro de
/// `smb_list_directory`/`probe_http_url`. Reusado pelas 4 funcoes de
/// metadados abaixo (local e as 3 de rede) — so o marshalling de credenciais
/// difere entre elas, ver justificativa em `smb_generate_thumbnail`.
fn metadata_to_c_char(internal_uri: &str) -> *mut std::os::raw::c_char {
    match core::metadata::extract(internal_uri) {
        Ok(meta) => string_to_c_char(media_logic::metadata_wire::encode(&meta)),
        Err(e) => string_to_c_char(format!("ERROR:{}", e.replace(['\n', '\t'], " "))),
    }
}

/// T13.1: extrai metadados de midia (container, duracao, bitrate, trilhas)
/// de um arquivo local ou de uma URL http(s):// direta — `core::metadata::extract`
/// despacha por esquema do mesmo jeito que o playback, entao uma unica funcao
/// cobre os dois casos. Chamada BLOQUEANTE (probe de container, sem decodificar
/// frame nenhum) — Kotlin SEMPRE de `Dispatchers.IO`. Retorno DEVE ser
/// liberado com `free_rust_string`.
#[no_mangle]
pub extern "C" fn read_media_metadata(path: *const std::os::raw::c_char) -> *mut std::os::raw::c_char {
    let path = match unsafe { cstr_to_string(path) } {
        Some(s) => s,
        None => return string_to_c_char("ERROR:caminho invalido".to_string()),
    };
    metadata_to_c_char(&path)
}

/// Mesma logica de `read_media_metadata`, para um arquivo num share SMB —
/// credenciais como parametros separados, mesma justificativa de
/// `smb_generate_thumbnail`.
#[no_mangle]
pub extern "C" fn smb_read_metadata(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    domain: *const std::os::raw::c_char,
    share: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".to_string()) };
        let share = match cstr_to_string(share) { Some(s) => s, None => return string_to_c_char("ERROR:share invalido".to_string()) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return string_to_c_char("ERROR:caminho invalido".to_string()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let domain = cstr_to_string(domain).unwrap_or_default();
        protocols::smb::SmbTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            share,
            path,
            username,
            password,
            domain,
        }
    };
    metadata_to_c_char(&target.to_internal())
}

/// Mesma logica de `read_media_metadata`, para um arquivo num servidor FTP.
#[no_mangle]
pub extern "C" fn ftp_read_metadata(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".to_string()) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return string_to_c_char("ERROR:caminho invalido".to_string()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        protocols::ftp::FtpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
        }
    };
    metadata_to_c_char(&target.to_internal())
}

/// Mesma logica de `read_media_metadata`, para um arquivo num servidor SFTP.
/// `private_key` e o CONTEUDO PEM (nao um caminho), mesma convencao de
/// `sftp_generate_thumbnail`.
#[no_mangle]
pub extern "C" fn sftp_read_metadata(
    host: *const std::os::raw::c_char,
    port: i32,
    username: *const std::os::raw::c_char,
    password: *const std::os::raw::c_char,
    private_key: *const std::os::raw::c_char,
    path: *const std::os::raw::c_char,
) -> *mut std::os::raw::c_char {
    let target = unsafe {
        let host = match cstr_to_string(host) { Some(s) => s, None => return string_to_c_char("ERROR:host invalido".to_string()) };
        let path = match cstr_to_string(path) { Some(s) => s, None => return string_to_c_char("ERROR:caminho invalido".to_string()) };
        let username = cstr_to_string(username).unwrap_or_default();
        let password = cstr_to_string(password).unwrap_or_default();
        let private_key = cstr_to_string(private_key).filter(|s| !s.is_empty());
        protocols::sftp::SftpTarget {
            host,
            port: port.clamp(1, u16::MAX as i32) as u16,
            path,
            username,
            password,
            private_key,
        }
    };
    if target.validate().is_err() {
        return string_to_c_char("ERROR:credenciais SFTP invalidas".to_string());
    }
    metadata_to_c_char(&target.to_internal())
}

#[no_mangle]
pub extern "C" fn get_video_progress(current: *mut f32, total: *mut f32) {
    // try_lock — mesmo motivo de get_current_video_frame() acima (chamada
    // periodica automatica do render loop, ~10Hz, nao uma acao explicita do
    // usuario). Se o lock estiver ocupado (load() em andamento), so deixa
    // current/total como estavam (o C++ ja cacheia o ultimo valor lido) em
    // vez de bloquear a thread de render.
    if let Ok(controller) = CONTROLLER.try_lock() {
        let (c, t) = controller.get_progress();
        unsafe {
            if !current.is_null() {
                *current = c as f32;
            }
            if !total.is_null() {
                *total = t as f32;
            }
        }
    }
}

