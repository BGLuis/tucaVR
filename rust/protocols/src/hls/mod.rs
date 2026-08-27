//! Módulo HLS Streaming (T8.1 - T8.6).
//!
//! Fornece parser RFC 8216 para playlists Master e Media, Adaptive Bitrate (ABR)
//! e stream source bufferizado para integração com o demuxer FFmpeg.

pub mod abr;
pub mod playlist;
pub mod segment;
pub mod stream;

pub use abr::AdaptiveBitrateManager;
pub use playlist::{
    fetch_and_probe_variants, parse_playlist, HlsKey, HlsMasterPlaylist, HlsMediaPlaylist, HlsPlaylist, HlsSegment,
    HlsVariant,
};
pub use segment::{decrypt_aes128_cbc, fetch_segment};
pub use stream::{HlsStreamSource, SharedHlsStreamSource};
