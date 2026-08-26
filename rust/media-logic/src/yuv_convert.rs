//! Conversao YUV 4:2:0 -> RGBA com downscale por amostragem do vizinho mais
//! proximo, usada pela trilha de scrub por decode de hardware
//! (`core::thumbnail::generate_strip_hw`, ver `docs/SEEK-UX-NEXT-STEPS.md`
//! item 1). Pura por construcao (so bytes/indices, sem MediaCodec/Android)
//! para poder ser testada com `cargo test -p media-logic` sem hardware —
//! o layout de bytes de um buffer YUV de MediaCodec e facil de errar
//! (stride vs largura, ordem U/V) e dificil de depurar so em dispositivo.
//!
//! Downscale por vizinho mais proximo (nao bilinear): a saida e minuscula
//! (dezenas de pixels) para um preview de arrasto, entao qualidade de
//! reamostragem nao importa — simplicidade > fidelidade aqui.

/// Layout de plano do buffer de saida do MediaCodec quando configurado sem
/// Surface (`HwDecoder::configure(&format, None)`). So os dois formatos
/// "flat" documentados pelo Android sao suportados — `COLOR_FormatYUV420Flexible`
/// exigiria a API `Image`/`MediaImage` (planos com stride proprio reportado
/// em runtime), que a API crua de `AMediaCodec` (usada por este projeto via
/// `ndk::media::media_codec`) nao expoe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum YuvLayout {
    /// `COLOR_FormatYUV420Planar` (19): Y inteiro, depois U inteiro, depois
    /// V inteiro, cada um em seu proprio plano.
    Planar,
    /// `COLOR_FormatYUV420SemiPlanar` (21) / NV12: Y inteiro, depois U e V
    /// intercalados byte a byte (par = U, impar = V) num unico plano.
    SemiPlanar,
}

impl YuvLayout {
    /// `None` para qualquer color-format fora dos dois listados acima —
    /// formatos proprietarios/tiled de alguns decoders de hardware nao tem
    /// layout flat legivel por CPU, e tentar interpretar como se fosse
    /// produziria lixo silencioso em vez de falhar visivelmente.
    pub fn from_android_color_format(color_format: i32) -> Option<Self> {
        match color_format {
            19 => Some(YuvLayout::Planar),
            21 => Some(YuvLayout::SemiPlanar),
            _ => None,
        }
    }
}

/// Planos crus do buffer de saida do MediaCodec. `u`/`v`/`u_stride`/`v_stride`
/// tem significado diferente conforme `YuvLayout`: em `SemiPlanar`, `u` e o
/// plano UV intercalado inteiro e `v`/`v_stride` sao ignorados.
pub struct YuvPlanes<'a> {
    pub y: &'a [u8],
    pub y_stride: usize,
    pub u: &'a [u8],
    pub u_stride: usize,
    pub v: &'a [u8],
    pub v_stride: usize,
}

fn yuv_to_rgb(y: u8, u: u8, v: u8) -> [u8; 3] {
    // BT.601 faixa cheia — aproximacao deliberada (nao differentiates
    // limited-range 16-235 do stream original): saida e um preview de
    // arrasto minusculo, nao precisa de precisao de cor de referencia.
    let y = y as f32;
    let u = u as f32 - 128.0;
    let v = v as f32 - 128.0;
    let r = y + 1.402 * v;
    let g = y - 0.344136 * u - 0.714136 * v;
    let b = y + 1.772 * u;
    [
        r.clamp(0.0, 255.0) as u8,
        g.clamp(0.0, 255.0) as u8,
        b.clamp(0.0, 255.0) as u8,
    ]
}

/// `None` se qualquer amostra calculada cair fora dos slices fornecidos —
/// acontece se `src_width`/`src_height` nao baterem com o que os planos
/// realmente contem (ex: `stride`/`slice-height` lidos errado do
/// `MediaFormat`). Preferimos falhar a silencio a arriscar um
/// out-of-bounds/panic lendo memoria de um buffer de hardware.
pub fn yuv420_to_rgba_scaled(
    planes: &YuvPlanes,
    layout: YuvLayout,
    src_width: u32,
    src_height: u32,
    dst_width: u32,
    dst_height: u32,
) -> Option<Vec<u8>> {
    if src_width == 0 || src_height == 0 || dst_width == 0 || dst_height == 0 {
        return None;
    }

    let mut rgba = vec![0u8; (dst_width * dst_height * 4) as usize];

    for dy in 0..dst_height {
        let src_y = (dy as u64 * src_height as u64 / dst_height as u64) as u32;
        let chroma_y = src_y / 2;
        for dx in 0..dst_width {
            let src_x = (dx as u64 * src_width as u64 / dst_width as u64) as u32;
            let chroma_x = src_x / 2;

            let y_idx = src_y as usize * planes.y_stride + src_x as usize;
            let y_val = *planes.y.get(y_idx)?;

            let (u_val, v_val) = match layout {
                YuvLayout::Planar => {
                    let u_idx = chroma_y as usize * planes.u_stride + chroma_x as usize;
                    let v_idx = chroma_y as usize * planes.v_stride + chroma_x as usize;
                    (*planes.u.get(u_idx)?, *planes.v.get(v_idx)?)
                }
                YuvLayout::SemiPlanar => {
                    let base = chroma_y as usize * planes.u_stride + chroma_x as usize * 2;
                    (*planes.u.get(base)?, *planes.u.get(base + 1)?)
                }
            };

            let [r, g, b] = yuv_to_rgb(y_val, u_val, v_val);
            let out_idx = (dy as usize * dst_width as usize + dx as usize) * 4;
            rgba[out_idx] = r;
            rgba[out_idx + 1] = g;
            rgba[out_idx + 2] = b;
            rgba[out_idx + 3] = 255;
        }
    }

    Some(rgba)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid_planar(y: u8, u: u8, v: u8, width: u32, height: u32) -> (Vec<u8>, Vec<u8>, Vec<u8>) {
        let cw = (width / 2) as usize;
        let ch = (height / 2) as usize;
        (
            vec![y; (width * height) as usize],
            vec![u; cw * ch],
            vec![v; cw * ch],
        )
    }

    #[test]
    fn unsupported_color_format_returns_none() {
        assert_eq!(YuvLayout::from_android_color_format(0x7f420888), None);
        assert_eq!(YuvLayout::from_android_color_format(19), Some(YuvLayout::Planar));
        assert_eq!(YuvLayout::from_android_color_format(21), Some(YuvLayout::SemiPlanar));
    }

    #[test]
    fn solid_white_frame_converts_to_near_white_rgb() {
        let (y, u, v) = solid_planar(235, 128, 128, 4, 4);
        let planes = YuvPlanes { y: &y, y_stride: 4, u: &u, u_stride: 2, v: &v, v_stride: 2 };
        let rgba = yuv420_to_rgba_scaled(&planes, YuvLayout::Planar, 4, 4, 2, 2).unwrap();
        for px in rgba.as_chunks::<4>().0 {
            assert!(px[0] > 220 && px[1] > 220 && px[2] > 220, "pixel nao ficou proximo de branco: {:?}", px);
            assert_eq!(px[3], 255);
        }
    }

    #[test]
    fn solid_black_frame_converts_to_near_black_rgb() {
        let (y, u, v) = solid_planar(16, 128, 128, 4, 4);
        let planes = YuvPlanes { y: &y, y_stride: 4, u: &u, u_stride: 2, v: &v, v_stride: 2 };
        let rgba = yuv420_to_rgba_scaled(&planes, YuvLayout::Planar, 4, 4, 2, 2).unwrap();
        for px in rgba.as_chunks::<4>().0 {
            assert!(px[0] < 30 && px[1] < 30 && px[2] < 30, "pixel nao ficou proximo de preto: {:?}", px);
        }
    }

    #[test]
    fn semi_planar_interleaved_uv_matches_planar_equivalent() {
        let (y, u, v) = solid_planar(180, 90, 200, 4, 4);
        let planar = YuvPlanes { y: &y, y_stride: 4, u: &u, u_stride: 2, v: &v, v_stride: 2 };
        let planar_rgba = yuv420_to_rgba_scaled(&planar, YuvLayout::Planar, 4, 4, 4, 4).unwrap();

        let mut uv = Vec::with_capacity(u.len() * 2);
        for i in 0..u.len() {
            uv.push(u[i]);
            uv.push(v[i]);
        }
        let semi = YuvPlanes { y: &y, y_stride: 4, u: &uv, u_stride: 4, v: &[], v_stride: 0 };
        let semi_rgba = yuv420_to_rgba_scaled(&semi, YuvLayout::SemiPlanar, 4, 4, 4, 4).unwrap();

        assert_eq!(planar_rgba, semi_rgba);
    }

    #[test]
    fn stride_padding_beyond_width_is_ignored() {
        // y_stride (6) maior que src_width (4): linhas tem 2 bytes de lixo
        // no fim que NAO devem ser lidos como parte da imagem.
        let width = 4u32;
        let height = 2u32;
        let stride = 6usize;
        let mut y = vec![0u8; stride * height as usize];
        for row in 0..height as usize {
            for col in 0..width as usize {
                y[row * stride + col] = 235;
            }
            // padding de lixo no fim da linha
            y[row * stride + 4] = 0;
            y[row * stride + 5] = 0;
        }
        let u = vec![128u8; 2];
        let v = vec![128u8; 2];
        let planes = YuvPlanes { y: &y, y_stride: stride, u: &u, u_stride: 2, v: &v, v_stride: 2 };
        let rgba = yuv420_to_rgba_scaled(&planes, YuvLayout::Planar, width, height, width, height).unwrap();
        for px in rgba.as_chunks::<4>().0 {
            assert!(px[0] > 220, "leu padding de stride como pixel de imagem: {:?}", px);
        }
    }

    #[test]
    fn out_of_bounds_sample_returns_none_instead_of_panicking() {
        let y = vec![235u8; 4];
        let u = vec![128u8; 1];
        let v = vec![128u8; 1];
        // src_height=4 mas y so tem 4 bytes no total (cabe 1 linha) —
        // qualquer linha alem da primeira estoura o slice.
        let planes = YuvPlanes { y: &y, y_stride: 4, u: &u, u_stride: 1, v: &v, v_stride: 1 };
        assert_eq!(yuv420_to_rgba_scaled(&planes, YuvLayout::Planar, 4, 4, 4, 4), None);
    }
}
