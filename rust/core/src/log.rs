//! Logging centralizado do Rust com tag estática e injeção do ID de sessão (N1 + N6).
//!
//! Evita alocações repetidas de `CString::new("VRPlayer_Rust")` e garante que todas
//! as linhas do Rust incluam o prefixo `[s:<session_id>]` para correlação com Kotlin e C++.

use std::ffi::CStr;
use std::sync::RwLock;

static TAG: &CStr = c"VRPlayer_Rust";
static SESSION_ID: RwLock<Option<String>> = RwLock::new(None);

/// Define o identificador de sessão ativo para correlacionar logs.
pub fn set_session_id(id: Option<String>) {
    if let Ok(mut lock) = SESSION_ID.write() {
        *lock = id.filter(|s| !s.trim().is_empty());
    }
}

/// Obtém o identificador de sessão atual.
pub fn get_session_id() -> Option<String> {
    SESSION_ID.read().ok().and_then(|lock| lock.clone())
}

/// Formata a mensagem com o prefixo da sessão e envia ao logcat Android.
pub fn log_android(level: i32, msg: &str) {
    let prefix = match get_session_id() {
        Some(ref id) => format!("[s:{id}] "),
        None => "[s:--------] ".to_string(),
    };

    let formatted = format!("{prefix}{msg}");
    if let Ok(c_msg) = std::ffi::CString::new(formatted) {
        unsafe {
            ndk_sys::__android_log_print(level, TAG.as_ptr(), c_msg.as_ptr());
        }
    }
}

#[macro_export]
macro_rules! log_debug {
    ($($arg:tt)*) => {
        $crate::log::log_android(3, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_info {
    ($($arg:tt)*) => {
        $crate::log::log_android(4, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_warn {
    ($($arg:tt)*) => {
        $crate::log::log_android(5, &format!($($arg)*))
    };
}

#[macro_export]
macro_rules! log_error {
    ($($arg:tt)*) => {
        $crate::log::log_android(6, &format!($($arg)*))
    };
}
