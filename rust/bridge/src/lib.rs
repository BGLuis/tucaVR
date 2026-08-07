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
use once_cell::sync::Lazy;

static CONTROLLER: Lazy<Arc<Mutex<PlaybackController>>> = Lazy::new(|| {
    Arc::new(Mutex::new(PlaybackController::new()))
});

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

