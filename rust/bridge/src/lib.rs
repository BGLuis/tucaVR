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
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use once_cell::sync::Lazy;

static CONTROLLER: Lazy<Arc<Mutex<PlaybackController>>> = Lazy::new(|| {
    Arc::new(Mutex::new(PlaybackController::new()))
});

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

/// T9 (historico): ao carregar um arquivo novo, o modo 3D da reproducao
/// anterior nao deve vazar pro proximo video — chamado pelo `load()` da
/// sessao de playback (ver nota em `start_video_playback`/`start_smb_playback`
/// abaixo). Sempre volta pra 2D; auto-deteccao (quando estiver ligada ao
/// bridge, ver docs/phases/PHASE-0.2-3D-NETWORK.md T3.4 "cache de deteccao")
/// e swap-eyes por-arquivo ficam para uma sessao futura.
#[no_mangle]
pub extern "C" fn reset_3d_mode() {
    SCREEN_MODE.store(0, Ordering::Relaxed);
    SWAP_EYES.store(false, Ordering::Relaxed);
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
    if let Ok(controller) = CONTROLLER.lock() {
        controller.get_current_frame()
    } else {
        std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "C" fn toggle_play_pause() {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.toggle_play_pause();
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

/// Libera uma string retornada por `smb_list_shares`, `smb_list_directory`
/// ou `probe_http_url`. Toda `*mut c_char` que este bridge retorna (exceto
/// via parametros `out`) precisa passar por aqui — sem isso, vaza memoria do
/// lado Rust a cada chamada.
#[no_mangle]
pub extern "C" fn free_rust_string(ptr: *mut std::os::raw::c_char) {
    if ptr.is_null() {
        return;
    }
    unsafe {
        drop(std::ffi::CString::from_raw(ptr));
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

    std::thread::spawn(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading SMB video: {}", protocols::smb::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load(&internal_uri) {
                unsafe { log(6, &format!("Error loading SMB video: {:?}", e)); }
            } else {
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

    std::thread::spawn(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading FTP video: {}", protocols::ftp::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load(&internal_uri) {
                unsafe { log(6, &format!("Error loading FTP video: {:?}", e)); }
            } else {
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
        return;
    }

    std::thread::spawn(move || {
        let internal_uri = target.to_internal();
        unsafe { log(4, &format!("Loading SFTP video: {}", protocols::sftp::redact(&internal_uri))); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load(&internal_uri) {
                unsafe { log(6, &format!("Error loading SFTP video: {:?}", e)); }
            } else {
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
pub extern "C" fn start_video_playback(path: *const std::os::raw::c_char) {
    if path.is_null() { return; }
    let c_str = unsafe { std::ffi::CStr::from_ptr(path) };
    let path_str = match c_str.to_str() {
        Ok(s) => s.to_string(),
        Err(_) => return,
    };

    std::thread::spawn(move || {
        unsafe { log(4, &format!("Loading video: {}", path_str)); }

        reset_3d_mode();
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.stop();
            if let Err(e) = controller.load(&path_str) {
                unsafe { log(6, &format!("Error loading video: {:?}", e)); }
            } else {
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
    std::thread::spawn(move || {
        if let Ok(mut controller) = CONTROLLER.lock() {
            controller.seek(position as f64);
        }
    });
}

#[no_mangle]
pub extern "C" fn set_video_volume(volume: f32) {
    if let Ok(mut controller) = CONTROLLER.lock() {
        controller.set_volume(volume);
    }
}

#[no_mangle]
pub extern "C" fn get_video_volume() -> f32 {
    if let Ok(controller) = CONTROLLER.lock() {
        controller.get_volume()
    } else {
        1.0
    }
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
    std::thread::spawn(move || {
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

#[no_mangle]
pub extern "C" fn get_video_progress(current: *mut f32, total: *mut f32) {
    if let Ok(controller) = CONTROLLER.lock() {
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

