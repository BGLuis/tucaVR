//! Parser de metadados DIDL-Lite XML para UPnP ContentDirectory (T7.3).

use quick_xml::events::Event;
use quick_xml::reader::Reader;

#[derive(Debug, Clone, PartialEq)]
pub struct DlnaItem {
    pub id: String,
    pub parent_id: Option<String>,
    pub title: String,
    pub is_container: bool,
    pub child_count: Option<u32>,
    pub res_url: Option<String>,
    pub size_bytes: Option<u64>,
    pub duration_sec: Option<f64>,
    pub resolution: Option<String>,
    pub album_art_url: Option<String>,
    pub upnp_class: Option<String>,
}

/// Converte strings de duração no formato `H:MM:SS[.mmm]` ou `MM:SS[.mmm]` para segundos totais.
pub fn parse_duration_to_seconds(duration_str: &str) -> Option<f64> {
    let parts: Vec<&str> = duration_str.trim().split(':').collect();
    match parts.len() {
        3 => {
            let hours: f64 = parts[0].parse().ok()?;
            let mins: f64 = parts[1].parse().ok()?;
            let secs: f64 = parts[2].parse().ok()?;
            Some(hours * 3600.0 + mins * 60.0 + secs)
        }
        2 => {
            let mins: f64 = parts[0].parse().ok()?;
            let secs: f64 = parts[1].parse().ok()?;
            Some(mins * 60.0 + secs)
        }
        1 => parts[0].parse().ok(),
        _ => None,
    }
}

/// Faz o parse do fragmento XML DIDL-Lite extraído da resposta SOAP do ContentDirectory.
pub fn parse_didl_lite(xml: &str) -> Result<Vec<DlnaItem>, String> {
    let mut reader = Reader::from_str(xml);
    reader.config_mut().trim_text(true);

    let mut items = Vec::new();

    let mut in_container = false;
    let mut in_item = false;
    let mut current_tag = String::new();

    // Campos temporários do elemento atual
    let mut current_id = String::new();
    let mut current_parent_id: Option<String> = None;
    let mut current_title: Option<String> = None;
    let mut current_child_count: Option<u32> = None;
    let mut current_res_url: Option<String> = None;
    let mut current_size: Option<u64> = None;
    let mut current_duration_sec: Option<f64> = None;
    let mut current_resolution: Option<String> = None;
    let mut current_album_art: Option<String> = None;
    let mut current_upnp_class: Option<String> = None;

    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_name = name.split(':').next_back().unwrap_or(&name).to_string();
                current_tag = local_name.clone();

                if local_name.eq_ignore_ascii_case("container") {
                    in_container = true;
                    in_item = false;
                    current_id.clear();
                    current_parent_id = None;
                    current_title = None;
                    current_child_count = None;
                    current_res_url = None;
                    current_size = None;
                    current_duration_sec = None;
                    current_resolution = None;
                    current_album_art = None;
                    current_upnp_class = None;

                    for attr in e.attributes().flatten() {
                        let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                        let val = attr.unescape_value().unwrap_or_default().to_string();
                        if key.eq_ignore_ascii_case("id") {
                            current_id = val;
                        } else if key.eq_ignore_ascii_case("parentID") {
                            current_parent_id = Some(val);
                        } else if key.eq_ignore_ascii_case("childCount") {
                            current_child_count = val.parse().ok();
                        }
                    }
                } else if local_name.eq_ignore_ascii_case("item") {
                    in_item = true;
                    in_container = false;
                    current_id.clear();
                    current_parent_id = None;
                    current_title = None;
                    current_child_count = None;
                    current_res_url = None;
                    current_size = None;
                    current_duration_sec = None;
                    current_resolution = None;
                    current_album_art = None;
                    current_upnp_class = None;

                    for attr in e.attributes().flatten() {
                        let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                        let val = attr.unescape_value().unwrap_or_default().to_string();
                        if key.eq_ignore_ascii_case("id") {
                            current_id = val;
                        } else if key.eq_ignore_ascii_case("parentID") {
                            current_parent_id = Some(val);
                        }
                    }
                } else if local_name.eq_ignore_ascii_case("res") && in_item {
                    for attr in e.attributes().flatten() {
                        let key = String::from_utf8_lossy(attr.key.as_ref()).to_string();
                        let val = attr.unescape_value().unwrap_or_default().to_string();
                        if key.eq_ignore_ascii_case("size") && current_size.is_none() {
                            current_size = val.parse().ok();
                        } else if key.eq_ignore_ascii_case("duration") && current_duration_sec.is_none() {
                            current_duration_sec = parse_duration_to_seconds(&val);
                        } else if key.eq_ignore_ascii_case("resolution") && current_resolution.is_none() {
                            current_resolution = Some(val);
                        }
                    }
                }
            }
            Ok(Event::End(ref e)) => {
                let name = String::from_utf8_lossy(e.name().as_ref()).to_string();
                let local_name = name.split(':').next_back().unwrap_or(&name).to_string();

                if local_name.eq_ignore_ascii_case("container") && in_container {
                    items.push(DlnaItem {
                        id: if current_id.is_empty() { "0".to_string() } else { current_id.clone() },
                        parent_id: current_parent_id.take(),
                        title: current_title.take().unwrap_or_else(|| "Pasta".to_string()),
                        is_container: true,
                        child_count: current_child_count.take(),
                        res_url: None,
                        size_bytes: None,
                        duration_sec: None,
                        resolution: None,
                        album_art_url: current_album_art.take(),
                        upnp_class: current_upnp_class.take(),
                    });
                    in_container = false;
                } else if local_name.eq_ignore_ascii_case("item") && in_item {
                    items.push(DlnaItem {
                        id: current_id.clone(),
                        parent_id: current_parent_id.take(),
                        title: current_title.take().unwrap_or_else(|| "Vídeo".to_string()),
                        is_container: false,
                        child_count: None,
                        res_url: current_res_url.take(),
                        size_bytes: current_size.take(),
                        duration_sec: current_duration_sec.take(),
                        resolution: current_resolution.take(),
                        album_art_url: current_album_art.take(),
                        upnp_class: current_upnp_class.take(),
                    });
                    in_item = false;
                }
                current_tag.clear();
            }
            Ok(Event::Text(ref e)) => {
                let text = e.unescape().map_err(|err| err.to_string())?.to_string();
                if in_container || in_item {
                    if current_tag.eq_ignore_ascii_case("title") && current_title.is_none() {
                        current_title = Some(text);
                    } else if (current_tag.eq_ignore_ascii_case("albumArtURI") || current_tag.eq_ignore_ascii_case("icon"))
                        && current_album_art.is_none()
                    {
                        current_album_art = Some(text);
                    } else if current_tag.eq_ignore_ascii_case("class") && current_upnp_class.is_none() {
                        current_upnp_class = Some(text);
                    } else if current_tag.eq_ignore_ascii_case("res") && in_item && current_res_url.is_none() {
                        current_res_url = Some(text);
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(e) => return Err(format!("Erro ao parsear DIDL-Lite XML: {e}")),
            _ => {}
        }
        buf.clear();
    }

    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE_DIDL: &str = r#"<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
  <container id="1$1" parentID="1" childCount="3" restricted="1">
    <dc:title>Movies</dc:title>
    <upnp:class>object.container.storageFolder</upnp:class>
    <upnp:albumArtURI>http://192.168.1.100:8200/icons/movies.jpg</upnp:albumArtURI>
  </container>
  <item id="1$2$42" parentID="1$1" restricted="1">
    <dc:title>Big Buck Bunny 4K.mp4</dc:title>
    <upnp:class>object.item.videoItem</upnp:class>
    <res protocolInfo="http-get:*:video/mp4:*" size="1073741824" duration="00:09:56.467" resolution="3840x2160">http://192.168.1.100:8200/MediaItems/42.mp4</res>
    <upnp:albumArtURI>http://192.168.1.100:8200/Thumbnails/42.jpg</upnp:albumArtURI>
  </item>
</DIDL-Lite>"#;

    #[test]
    fn parse_valid_didl_lite() {
        let items = parse_didl_lite(SAMPLE_DIDL).unwrap();
        assert_eq!(items.len(), 2);

        let container = &items[0];
        assert_eq!(container.id, "1$1");
        assert_eq!(container.title, "Movies");
        assert!(container.is_container);
        assert_eq!(container.child_count, Some(3));
        assert_eq!(container.album_art_url, Some("http://192.168.1.100:8200/icons/movies.jpg".to_string()));

        let video = &items[1];
        assert_eq!(video.id, "1$2$42");
        assert_eq!(video.title, "Big Buck Bunny 4K.mp4");
        assert!(!video.is_container);
        assert_eq!(video.size_bytes, Some(1073741824));
        assert_eq!(video.duration_sec, Some(596.467));
        assert_eq!(video.resolution, Some("3840x2160".to_string()));
        assert_eq!(video.res_url, Some("http://192.168.1.100:8200/MediaItems/42.mp4".to_string()));
    }

    #[test]
    fn duration_parsing() {
        assert_eq!(parse_duration_to_seconds("01:23:45.500"), Some(3600.0 + 23.0 * 60.0 + 45.5));
        assert_eq!(parse_duration_to_seconds("05:30"), Some(330.0));
        assert_eq!(parse_duration_to_seconds("120"), Some(120.0));
    }
}
