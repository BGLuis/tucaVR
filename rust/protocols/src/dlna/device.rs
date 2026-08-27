//! Parser e cliente de Device Description XML para servidores UPnP/DLNA (T7.2).

use quick_xml::events::Event;
use quick_xml::reader::Reader;
use std::time::Duration;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DlnaDevice {
    pub location: String,
    pub friendly_name: String,
    pub model_name: Option<String>,
    pub icon_url: Option<String>,
    pub control_url: String,
}

/// Resolve uma URL relativa contra uma URL base HTTP/HTTPS.
pub fn resolve_url(base: &str, relative: &str) -> String {
    let rel = relative.trim();
    if rel.starts_with("http://") || rel.starts_with("https://") {
        return rel.to_string();
    }

    if let Some(pos) = base.find("://") {
        let scheme_and_after = &base[pos + 3..];
        let host_end = scheme_and_after.find('/').unwrap_or(scheme_and_after.len());
        let origin = &base[..pos + 3 + host_end];

        if rel.starts_with('/') {
            format!("{origin}{rel}")
        } else {
            let last_slash = base.rfind('/').unwrap_or(base.len());
            let dir = &base[..last_slash];
            format!("{dir}/{rel}")
        }
    } else {
        relative.to_string()
    }
}

/// Faz o parse do XML de descrição do dispositivo UPnP (Device Description XML).
pub fn parse_device_description(xml: &str, location: &str) -> Result<DlnaDevice, String> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut friendly_name: Option<String> = None;
    let mut model_name: Option<String> = None;
    let mut icon_url: Option<String> = None;
    let mut control_url: Option<String> = None;

    let mut in_service = false;
    let mut is_content_directory = false;
    let mut current_control_url: Option<String> = None;
    let mut current_tag = String::new();

    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_name = name.split(':').next_back().unwrap_or(&name).to_string();
                current_tag = local_name.clone();

                if local_name.eq_ignore_ascii_case("service") {
                    in_service = true;
                    is_content_directory = false;
                    current_control_url = None;
                }
            }
            Ok(Event::End(ref e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_name = name.split(':').next_back().unwrap_or(&name).to_string();

                if local_name.eq_ignore_ascii_case("service") {
                    if is_content_directory && control_url.is_none() {
                        control_url = current_control_url.take();
                    }
                    in_service = false;
                    is_content_directory = false;
                }
                current_tag.clear();
            }
            Ok(Event::Text(ref e)) => {
                let text = e.unescape().map_err(|err| err.to_string())?.to_string();
                if in_service {
                    if current_tag.eq_ignore_ascii_case("serviceType") {
                        if text.contains("urn:schemas-upnp-org:service:ContentDirectory:")
                            || text.contains("ContentDirectory")
                        {
                            is_content_directory = true;
                        }
                    } else if current_tag.eq_ignore_ascii_case("controlURL") {
                        current_control_url = Some(text);
                    }
                } else {
                    if current_tag.eq_ignore_ascii_case("friendlyName") && friendly_name.is_none() {
                        friendly_name = Some(text);
                    } else if current_tag.eq_ignore_ascii_case("modelName") && model_name.is_none() {
                        model_name = Some(text);
                    } else if current_tag.eq_ignore_ascii_case("url") && icon_url.is_none() {
                        icon_url = Some(resolve_url(location, &text));
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("Erro ao parsear Device Description XML: {e}")),
            _ => {}
        }
        buf.clear();
    }

    let friendly_name = friendly_name.unwrap_or_else(|| "DLNA Media Server".to_string());
    let control_url_raw = control_url.ok_or_else(|| "Serviço ContentDirectory não encontrado no dispositivo DLNA".to_string())?;
    let resolved_control_url = resolve_url(location, &control_url_raw);

    Ok(DlnaDevice {
        location: location.to_string(),
        friendly_name,
        model_name,
        icon_url,
        control_url: resolved_control_url,
    })
}

/// Baixa e faz parse do Device Description XML a partir da URL LOCATION.
pub fn fetch_device_description(location_url: &str) -> Result<DlnaDevice, String> {
    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(5))
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .get(location_url)
        .send()
        .map_err(|e| format!("Falha ao conectar ao servidor DLNA ({location_url}): {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("Servidor DLNA retornou HTTP status {}", resp.status()));
    }

    let body = resp.text().map_err(|e| format!("Falha ao ler resposta XML: {e}"))?;
    parse_device_description(&body, location_url)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_DEVICE_XML: &str = r#"<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
    <friendlyName>Home Media Server (MiniDLNA)</friendlyName>
    <manufacturer>Justin Maggard</manufacturer>
    <modelName>Windows Media Connect compatible</modelName>
    <modelNumber>1.0</modelNumber>
    <iconList>
      <icon>
        <mimetype>image/png</mimetype>
        <width>48</width>
        <height>48</height>
        <depth>24</depth>
        <url>/icons/sm.png</url>
      </icon>
    </iconList>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId>
        <controlURL>/ctl/ContentDir</controlURL>
        <eventSubURL>/evt/ContentDir</eventSubURL>
        <SCPDURL>/ContentDir.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/ctl/ConnMgr</controlURL>
        <eventSubURL>/evt/ConnMgr</eventSubURL>
        <SCPDURL>/ConnMgr.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>"#;

    #[test]
    fn parse_valid_device_xml() {
        let dev = parse_device_description(SAMPLE_DEVICE_XML, "http://192.168.1.100:8200/rootDesc.xml").unwrap();
        assert_eq!(dev.friendly_name, "Home Media Server (MiniDLNA)");
        assert_eq!(dev.model_name, Some("Windows Media Connect compatible".to_string()));
        assert_eq!(dev.control_url, "http://192.168.1.100:8200/ctl/ContentDir");
        assert_eq!(dev.icon_url, Some("http://192.168.1.100:8200/icons/sm.png".to_string()));
    }

    #[test]
    fn resolve_relative_urls() {
        let base = "http://10.0.0.5:8080/desc/root.xml";
        assert_eq!(resolve_url(base, "/control"), "http://10.0.0.5:8080/control");
        assert_eq!(resolve_url(base, "control"), "http://10.0.0.5:8080/desc/control");
        assert_eq!(resolve_url(base, "http://other/ctrl"), "http://other/ctrl");
    }
}
