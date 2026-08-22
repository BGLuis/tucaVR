//! Cliente SOAP para ações UPnP ContentDirectory (T7.3).

use std::time::Duration;

/// Monta o payload XML do envelope SOAP para a ação `Browse`.
pub fn build_browse_envelope(object_id: &str, start_index: u32, max_count: u32) -> String {
    format!(
        r#"<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <ObjectID>{}</ObjectID>
      <BrowseFlag>BrowseDirectChildren</BrowseFlag>
      <Filter>*</Filter>
      <StartingIndex>{}</StartingIndex>
      <RequestedCount>{}</RequestedCount>
      <SortCriteria></SortCriteria>
    </u:Browse>
  </s:Body>
</s:Envelope>"#,
        escape_xml(object_id),
        start_index,
        max_count
    )
}

/// Escapa caracteres especiais XML em textos/atributos.
fn escape_xml(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

/// Desescapa entidades XML de dentro da tag <Result> SOAP.
pub fn unescape_xml(input: &str) -> String {
    input
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// Extrai o conteúdo dentro de `<Result>...</Result>` de uma resposta SOAP.
pub fn extract_result_from_soap_response(soap_xml: &str) -> Result<String, String> {
    let start_tag = "<Result>";
    let end_tag = "</Result>";

    let start_idx = soap_xml
        .find(start_tag)
        .or_else(|| soap_xml.find("<Result "))
        .and_then(|pos| {
            soap_xml[pos..].find('>').map(|offset| pos + offset + 1)
        })
        .ok_or_else(|| "Tag <Result> não encontrada na resposta SOAP do ContentDirectory".to_string())?;

    let end_idx = soap_xml[start_idx..]
        .find(end_tag)
        .map(|pos| start_idx + pos)
        .ok_or_else(|| "Tag </Result> não encontrada na resposta SOAP do ContentDirectory".to_string())?;

    let raw_result = &soap_xml[start_idx..end_idx];
    Ok(unescape_xml(raw_result))
}

/// Executa a ação SOAP `Browse` contra o `control_url` e retorna o XML DIDL-Lite.
pub fn execute_browse_soap(
    control_url: &str,
    object_id: &str,
    start_index: u32,
    max_count: u32,
) -> Result<String, String> {
    let envelope = build_browse_envelope(object_id, start_index, max_count);

    let client = reqwest::blocking::Client::builder()
        .timeout(Duration::from_secs(10))
        .build()
        .map_err(|e| e.to_string())?;

    let resp = client
        .post(control_url)
        .header("Content-Type", "text/xml; charset=\"utf-8\"")
        .header("SOAPAction", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
        .body(envelope)
        .send()
        .map_err(|e| format!("Falha na requisição SOAP para {control_url}: {e}"))?;

    if !resp.status().is_success() {
        return Err(format!("Ação SOAP Browse falhou com HTTP status {}", resp.status()));
    }

    let body = resp.text().map_err(|e| format!("Falha ao ler resposta SOAP: {e}"))?;
    extract_result_from_soap_response(&body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn browse_envelope_generation() {
        let env = build_browse_envelope("0", 0, 50);
        assert!(env.contains("<ObjectID>0</ObjectID>"));
        assert!(env.contains("<StartingIndex>0</StartingIndex>"));
        assert!(env.contains("<RequestedCount>50</RequestedCount>"));
        assert!(env.contains("urn:schemas-upnp-org:service:ContentDirectory:1"));
    }

    #[test]
    fn extract_and_unescape_soap_result() {
        let soap = r#"<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
      <Result>&lt;DIDL-Lite xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/&quot;&gt;&lt;container id=&quot;1&quot;&gt;&lt;dc:title&gt;Videos&lt;/dc:title&gt;&lt;/container&gt;&lt;/DIDL-Lite&gt;</Result>
      <NumberReturned>1</NumberReturned>
      <TotalMatches>1</TotalMatches>
      <UpdateID>1</UpdateID>
    </u:BrowseResponse>
  </s:Body>
</s:Envelope>"#;

        let result = extract_result_from_soap_response(soap).unwrap();
        assert!(result.starts_with("<DIDL-Lite"));
        assert!(result.contains("<container id=\"1\"><dc:title>Videos</dc:title></container>"));
    }
}
