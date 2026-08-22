//! Módulo UPnP / DLNA (T7.1 - T7.6).
//!
//! Fornece descoberta de servidor via SSDP, leitura de Device Description XML,
//! execução de ações SOAP Browse no ContentDirectory e parse de DIDL-Lite.

pub mod device;
pub mod didl;
pub mod soap;

pub use device::{fetch_device_description, parse_device_description, resolve_url, DlnaDevice};
pub use didl::{parse_didl_lite, parse_duration_to_seconds, DlnaItem};
pub use soap::{build_browse_envelope, execute_browse_soap, extract_result_from_soap_response};

/// Navega no diretório de conteúdo (ContentDirectory) de um servidor DLNA via SOAP Browse.
pub fn browse_directory(
    control_url: &str,
    object_id: &str,
    start_index: u32,
    max_count: u32,
) -> Result<Vec<DlnaItem>, String> {
    let didl_xml = execute_browse_soap(control_url, object_id, start_index, max_count)?;
    parse_didl_lite(&didl_xml)
}
