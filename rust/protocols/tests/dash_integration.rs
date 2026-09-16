//! Teste de integracao DASH (Fase 0.4 Secao 2, achado R-04 de
//! docs/reports/PHASE-0.4-08-VERIFICACAO-PROFUNDA.md) contra manifestos MPD reais, gerados por
//! ffmpeg e servidos por um nginx real em Docker — nao mocks `httpmock`. Cobre os dois modos de
//! endereçamento que `rust/protocols/src/dash` suporta:
//! - `template/manifest.mpd`: `SegmentTemplate` com `$Number$` (video com 3 segmentos + audio).
//! - `singlefile/manifest.mpd`: `SegmentBase` de segmento único (achado R-02 — antes deste fix,
//!   representações `SegmentBase` baixavam só o segmento de inicialização e paravam).
//!
//! `#[ignore]` — depende do ambiente Docker + ffmpeg gerando as fixtures. Use
//! `./scripts/test-network-protocols.sh` na raiz do repo.
use protocols::dash::DashStreamSource;
use std::io::Read;

fn env_or(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn base_url() -> String {
    env_or("VRPLAYER_TEST_HTTP_URL_BASE", "http://127.0.0.1:18080")
}

/// Lê a fonte inteira até EOF, devolvendo o total de bytes recebidos.
fn read_all(source: &mut DashStreamSource) -> usize {
    let mut total = 0usize;
    let mut buf = vec![0u8; 16 * 1024];
    loop {
        let n = source.read(&mut buf).expect("read não deveria falhar contra o servidor real");
        if n == 0 {
            break;
        }
        total += n;
    }
    total
}

/// SegmentTemplate com $Number$ contra um MPD real (DoD "MPD com SegmentTemplate reproduz").
#[test]
#[ignore]
fn dash_segment_template_reads_init_and_all_segments_from_real_server() {
    let mpd_url = format!("{}/dash/template/manifest.mpd", base_url());
    let mut source = DashStreamSource::open(&mpd_url).expect("open deveria funcionar contra o MPD real");

    assert!(!source.representations().is_empty());
    assert!(source.total_duration() > 0.0);

    let total = read_all(&mut source);
    // init segment (algumas centenas de bytes) + pelo menos 3 segmentos de vídeo de ~15-20KB
    // cada (seg_duration=2s sobre um clipe de 6s) — bem mais que só o init sozinho.
    assert!(total > 20_000, "esperava vários segmentos de mídia, só recebeu {total} bytes");
}

/// SegmentBase de arquivo único contra um MPD real (achado R-02): antes do fix,
/// `ensure_buffer` não tinha branch para representações sem `SegmentTemplate` e a leitura
/// parava logo após o segmento de inicialização, sem baixar nenhum dado de mídia.
#[test]
#[ignore]
fn dash_segment_base_downloads_media_from_real_server() {
    let mpd_url = format!("{}/dash/singlefile/manifest.mpd", base_url());
    let mut source = DashStreamSource::open(&mpd_url).expect("open deveria funcionar contra o MPD SegmentBase real");

    assert!(!source.representations().is_empty());

    let total = read_all(&mut source);
    // O fixture concatena o init segment (algumas centenas de bytes) com os mesmos blocos de
    // vídeo do template acima — se R-02 tivesse regredido, `total` seria só o tamanho do
    // segmento de inicialização (algumas centenas de bytes) e nada mais.
    assert!(
        total > 20_000,
        "SegmentBase deveria ter baixado o arquivo de mídia inteiro (init + blocos), só recebeu {total} bytes — R-02 pode ter regredido"
    );
}
