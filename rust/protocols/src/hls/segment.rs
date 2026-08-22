//! Download e descriptografia de segmentos HLS (T8.2 / RFC 8216).

use super::playlist::{HlsKey, HlsSegment};
use aes::cipher::{BlockDecrypt, KeyInit};
use std::collections::HashMap;
use std::time::{Duration, Instant};

/// Baixa os bytes brutos de um segmento HLS via HTTP/HTTPS, aplicando range headers e descriptografia AES-128 se necessário.
pub fn fetch_segment(
    client: &reqwest::blocking::Client,
    segment: &HlsSegment,
    key_cache: &mut HashMap<String, Vec<u8>>,
) -> Result<(Vec<u8>, Duration), String> {
    let start_time = Instant::now();
    let mut req = client.get(&segment.url);

    if let Some((offset, length)) = segment.byte_range {
        let end = offset + length - 1;
        req = req.header("Range", format!("bytes={offset}-{end}"));
    }

    let resp = req
        .send()
        .map_err(|e| format!("Falha ao baixar segmento HLS ({}): {e}", segment.url))?;

    if !resp.status().is_success() && resp.status().as_u16() != 206 {
        return Err(format!("Erro HTTP {} ao baixar segmento HLS", resp.status()));
    }

    let raw_bytes = resp
        .bytes()
        .map_err(|e| format!("Falha ao ler corpo do segmento: {e}"))?
        .to_vec();

    let download_duration = start_time.elapsed();

    // Se o segmento for criptografado com AES-128
    if let Some(ref key_info) = segment.key
        && key_info.method == "AES-128"
    {
        let key_bytes = get_or_fetch_key(client, key_info, key_cache)?;
        let iv = resolve_iv(key_info, segment.index);
        let decrypted = decrypt_aes128_cbc(&key_bytes, &iv, &raw_bytes)?;
        return Ok((decrypted, download_duration));
    }

    Ok((raw_bytes, download_duration))
}

fn get_or_fetch_key(
    client: &reqwest::blocking::Client,
    key_info: &HlsKey,
    key_cache: &mut HashMap<String, Vec<u8>>,
) -> Result<[u8; 16], String> {
    let uri = key_info
        .uri
        .as_ref()
        .ok_or_else(|| "URI da chave AES-128 ausente no HLS".to_string())?;

    if let Some(cached) = key_cache.get(uri)
        && cached.len() == 16
    {
        let mut out = [0u8; 16];
        out.copy_from_slice(cached);
        return Ok(out);
    }

    let resp = client
        .get(uri)
        .send()
        .map_err(|e| format!("Falha ao baixar chave AES-128 ({uri}): {e}"))?;

    let bytes = resp
        .bytes()
        .map_err(|e| format!("Falha ao ler bytes da chave AES-128: {e}"))?
        .to_vec();

    if bytes.len() != 16 {
        return Err(format!("Tamanho inválido para chave AES-128: {} bytes (esperado: 16)", bytes.len()));
    }

    key_cache.insert(uri.clone(), bytes.clone());
    let mut out = [0u8; 16];
    out.copy_from_slice(&bytes);
    Ok(out)
}

fn resolve_iv(key_info: &HlsKey, sequence_number: usize) -> [u8; 16] {
    if let Some(iv) = key_info.iv {
        return iv;
    }

    // Se o IV for omitido, RFC 8216 dita que o IV é o número de sequência como 16-byte integer big-endian
    let mut iv = [0u8; 16];
    let seq_bytes = (sequence_number as u64).to_be_bytes();
    iv[8..16].copy_from_slice(&seq_bytes);
    iv
}

/// Descriptografa blocos em modo AES-128-CBC com unpadding PKCS#7.
pub fn decrypt_aes128_cbc(key: &[u8; 16], iv: &[u8; 16], ciphertext: &[u8]) -> Result<Vec<u8>, String> {
    if ciphertext.is_empty() {
        return Ok(Vec::new());
    }

    if !ciphertext.len().is_multiple_of(16) {
        return Err(format!(
            "Ciphertext HLS AES-128 não é múltiplo de 16 bytes: {} bytes",
            ciphertext.len()
        ));
    }

    let cipher = aes::Aes128::new(key.into());
    let mut decrypted = Vec::with_capacity(ciphertext.len());
    let mut prev_block = *iv;

    for chunk in ciphertext.chunks_exact(16) {
        let mut block = aes::Block::clone_from_slice(chunk);
        cipher.decrypt_block(&mut block);
        for i in 0..16 {
            block[i] ^= prev_block[i];
        }
        prev_block.copy_from_slice(chunk);
        decrypted.extend_from_slice(&block);
    }

    // PKCS#7 unpadding
    if let Some(&pad_len) = decrypted.last() {
        let pad_len = pad_len as usize;
        if pad_len > 0 && pad_len <= 16 && pad_len <= decrypted.len() {
            let mut valid_pad = true;
            for &b in &decrypted[decrypted.len() - pad_len..] {
                if b != pad_len as u8 {
                    valid_pad = false;
                    break;
                }
            }
            if valid_pad {
                decrypted.truncate(decrypted.len() - pad_len);
            }
        }
    }

    Ok(decrypted)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn aes128_cbc_roundtrip() {
        use aes::cipher::BlockEncrypt;

        let key = [0x2b, 0x7e, 0x15, 0x16, 0x28, 0xae, 0xd2, 0xa6, 0xab, 0xf7, 0x15, 0x88, 0x09, 0xcf, 0x4f, 0x3c];
        let iv = [0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f];
        let plaintext = b"Hello, HLS Streaming on Meta Quest 3!";

        // PKCS#7 padding
        let pad_len = 16 - (plaintext.len() % 16);
        let mut padded = plaintext.to_vec();
        padded.extend(std::iter::repeat(pad_len as u8).take(pad_len));

        // Encripta
        let cipher = aes::Aes128::new((&key).into());
        let mut ciphertext = Vec::with_capacity(padded.len());
        let mut prev = iv;

        for chunk in padded.chunks_exact(16) {
            let mut block = aes::Block::clone_from_slice(chunk);
            for i in 0..16 {
                block[i] ^= prev[i];
            }
            cipher.encrypt_block(&mut block);
            prev.copy_from_slice(&block);
            ciphertext.extend_from_slice(&block);
        }

        // Decripta
        let decrypted = decrypt_aes128_cbc(&key, &iv, &ciphertext).unwrap();
        assert_eq!(decrypted, plaintext);
    }
}
