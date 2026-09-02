// Parser do box `av1C` (AV1 Codec ISO Media File Format Binding), extradata
// que o FFmpeg expõe em AVCodecParameters.extradata para streams AV1
// extraídos de MP4/MKV/WebM. Reexporta a implementação pura de `media_logic::av1`
// para manter compatibilidade e permitir testes unitários no host via `cargo test -p media-logic`.
pub use media_logic::av1::extract_config_obus;
