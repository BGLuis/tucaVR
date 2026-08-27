//! Formatador de telemetria e utilitários de sanitização de CSV (N2 do plano de telemetria).

#[derive(Debug, Clone, PartialEq)]
pub struct TelemetryRecord {
    pub timestamp_ms: u64,
    pub session_id: String,
    pub elapsed_s: f64,
    pub backend: String,
    pub screen_mode: String,
    pub video_status: String,
    pub video_fps: f32,
    pub decoded_fps: f32,
    pub output_fps: f32,
    pub dropped_fps: f32,
    pub jitter_ms: f32,
    pub net_mbs: f32,
    pub video_q_depth: u32,
    pub seek_ms: u32,
    pub smoothed_fps: f32,
    pub frame_ms: f32,
    pub stutter_count: u32,
    pub freeze_count: u32,
    pub thermal_level: u32,
    pub scale: f32,
    pub source_type: String,
    pub source_path: String,
}

pub const CSV_HEADER: &str = "timestamp_ms,session_id,elapsed_s,backend,screen_mode,video_status,video_fps,decoded_fps,output_fps,dropped_fps,jitter_ms,net_mbs,video_q_depth,seek_ms,smoothed_fps,frame_ms,stutter_count,freeze_count,thermal_level,scale,source_type,source_redacted";

/// Redige senhas ou credenciais de URLs / caminhos SMB / FTP / SFTP / HTTP
pub fn redact_source(raw: &str) -> String {
    if raw.is_empty() {
        return String::new();
    }

    // Se tiver esquema com formato user:pass@host
    if let Some(scheme_pos) = raw.find("://") {
        let (scheme, rest) = raw.split_at(scheme_pos + 3);
        // O delimitador entre credenciais e host é o último '@' antes da barra de caminho
        let authority = rest.split('/').next().unwrap_or(rest);
        if let Some(at_pos) = authority.rfind('@') {
            let path_part = &rest[authority.len()..];
            let host_part = &authority[at_pos + 1..];
            let user_pass = &authority[..at_pos];
            let user = user_pass.split(':').next().unwrap_or("");
            return if user.is_empty() {
                format!("{scheme}***@{host_part}{path_part}")
            } else {
                format!("{scheme}{user}:***@{host_part}{path_part}")
            };
        }
    }

    raw.to_string()
}

/// Escapa caracteres especiais para CSV
fn sanitize_csv_field(s: &str) -> String {
    if s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r') {
        let escaped = s.replace('"', "\"\"");
        format!("\"{escaped}\"")
    } else {
        s.to_string()
    }
}

pub fn format_csv_row(rec: &TelemetryRecord) -> String {
    let redacted_source = redact_source(&rec.source_path);
    format!(
        "{},{},{:.2},{},{},{},{:.1},{:.1},{:.1},{:.1},{:.1},{:.2},{},{},{:.1},{:.1},{},{},{},{:.2},{},{}",
        rec.timestamp_ms,
        sanitize_csv_field(&rec.session_id),
        rec.elapsed_s,
        sanitize_csv_field(&rec.backend),
        sanitize_csv_field(&rec.screen_mode),
        sanitize_csv_field(&rec.video_status),
        rec.video_fps,
        rec.decoded_fps,
        rec.output_fps,
        rec.dropped_fps,
        rec.jitter_ms,
        rec.net_mbs,
        rec.video_q_depth,
        rec.seek_ms,
        rec.smoothed_fps,
        rec.frame_ms,
        rec.stutter_count,
        rec.freeze_count,
        rec.thermal_level,
        rec.scale,
        sanitize_csv_field(&rec.source_type),
        sanitize_csv_field(&redacted_source),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_csv_header_fields_match_row_columns() {
        let rec = TelemetryRecord {
            timestamp_ms: 1700000000000,
            session_id: "a1b2c3d4".into(),
            elapsed_s: 12.34,
            backend: "VULKAN".into(),
            screen_mode: "2D".into(),
            video_status: "ativo".into(),
            video_fps: 60.0,
            decoded_fps: 59.8,
            output_fps: 59.8,
            dropped_fps: 0.0,
            jitter_ms: 1.2,
            net_mbs: 15.4,
            video_q_depth: 8,
            seek_ms: 45,
            smoothed_fps: 90.0,
            frame_ms: 11.1,
            stutter_count: 0,
            freeze_count: 0,
            thermal_level: 1,
            scale: 1.0,
            source_type: "LocalFile".into(),
            source_path: "/sdcard/Movies/clip.mp4".into(),
        };

        let header_count = CSV_HEADER.split(',').count();
        let row = format_csv_row(&rec);
        let row_count = row.split(',').count();

        assert_eq!(header_count, row_count, "Header and row must have matching column counts");
    }

    #[test]
    fn test_redact_source_credentials() {
        assert_eq!(
            redact_source("smb://alice:secret_pass@192.168.1.100/share/video.mkv"),
            "smb://alice:***@192.168.1.100/share/video.mkv"
        );
        assert_eq!(
            redact_source("ftp://bob:p@ssw0rd!@files.local:21/movie.mp4"),
            "ftp://bob:***@files.local:21/movie.mp4"
        );
        assert_eq!(
            redact_source("sftp://keyuser:privkey@vault:22/secure.mp4"),
            "sftp://keyuser:***@vault:22/secure.mp4"
        );
        assert_eq!(
            redact_source("http://example.com/video.mp4"),
            "http://example.com/video.mp4"
        );
        assert_eq!(
            redact_source("/sdcard/Movies/clip.mp4"),
            "/sdcard/Movies/clip.mp4"
        );
    }
}
