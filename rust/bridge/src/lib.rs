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

