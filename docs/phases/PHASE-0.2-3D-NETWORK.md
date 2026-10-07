# Phase 0.2 — 3D & Network

> **Objective**: Add 3D/VR playback (SBS, OU, 360°, 180°), additional network protocols (NFS, FTP, SFTP, DLNA, HLS), subtitles, library search, and thermal monitoring.  
> **Prerequisite**: Phase 0.1 complete and stable.  
> **Expected Outcome**: The user can watch immersive VR media from any common local or network source, with subtitles and fluid navigation.

---

## 📋 Table of Contents

1. [3D Playback — Side-by-Side and Over/Under](#1-3d-playback--side-by-side-and-overunder)
2. [360° and 180° VR Playback](#2-360-and-180-vr-playback)
3. [3D Format Auto-Detection](#3-3d-format-auto-detection)
4. [Head Tracking for 360° Content](#4-head-tracking-for-360-content)
5. [NFS Protocol](#5-nfs-protocol)
6. [FTP and SFTP Protocols](#6-ftp-and-sftp-protocols)
7. [DLNA/UPnP](#7-dlnaupnp)
8. [HLS Streaming](#8-hls-streaming)
9. [Subtitles (SRT, VTT)](#9-subtitles-srt-vtt)
10. [Automatic Server Discovery](#10-automatic-server-discovery)
11. [Connection Management](#11-connection-management)
12. [Library Search and Filters](#12-library-search-and-filters)
13. [Media Metadata](#13-media-metadata)
14. [Thermal Monitoring](#14-thermal-monitoring)
15. [Cross-Cutting Concerns of Phase 0.2](#15-cross-cutting-concerns-of-phase-02)
16. [Definition of Done — v0.2](#16-definition-of-done--v02)

---

## 1. 3D Playback — Side-by-Side and Over/Under

### Concept

Stereoscopic 3D media packages both eyes into a single container frame:

```
Side-by-Side (SBS):                Over/Under (OU):
┌────────┬────────┐               ┌────────────────┐
│  Left  │ Right  │               │   Left Eye     │
│  Eye   │  Eye   │               ├────────────────┤
└────────┴────────┘               │   Right Eye    │
                                  └────────────────┘

Half SBS: each half has half horizontal resolution
Full SBS: each half maintains full resolution (video has 2x width)
```

### Tasks

- [x] **T1.1** — Implement SBS separation shader (C++/GLSL):
  - Multiview vertex/fragment shader mapping left half to view 0 and right half to view 1
- [x] **T1.2** — Implement OU separation shader (C++/GLSL):
  - Multiview vertex/fragment shader mapping top half to view 0 and bottom half to view 1
- [x] **T1.3** — Support Half and Full variants:
  - Quad aspect ratio calculation adjusted for Half vs Full SBS/OU
- [x] **T1.4** — UI format mode selection:
  - Mode selector cycle in `ScreenFormatModal.kt` (`2D`, `SBS`, `OU`, etc.)
  - Format preference persisted per media entry
- [x] **T1.5** — Swap eyes toggle: invert left/right views for inverted media

### ⚠️ Pitfalls and Considerations

> [!CAUTION]
> **Swapped eyes cause nausea**: If the left eye receives the right image, depth perception is inverted. Always provide a convenient "Swap Eyes" toggle.

> [!WARNING]
> **Half SBS aspect ratio**: A 1920x1080 Half SBS video has an apparent 16:9 container, but each eye is 960x1080. The 3D quad must render at 16:9, calculating display aspect correctly.

---

## 2. 360° and 180° VR Playback

### Concept

Equirectangular projections mapped onto inside-out sphere or hemisphere geometry:

```
360° Equirectangular:                180° VR:
    ┌────────────────────┐           ┌──────────┐
    │ 360° projection on │           │ Frontal  │
    │ 2:1 texture        │           │ hemi-    │
    └────────────────────┘           │ sphere   │
    → mapped on inverted             └──────────┘
      sphere geometry
```

### Tasks

- [x] **T2.1** — Generate UV sphere geometry (C++):
  - Inverted normals (inside-out rendering), 64 segments × 32 rings minimum
- [x] **T2.2** — Implement equirectangular shader:
  - 2:1 texture wrapping with `REPEAT` on U and `CLAMP_TO_EDGE` on V
- [x] **T2.3** — Implement 180° VR hemisphere projection
- [x] **T2.4** — Implement stereoscopic 360° (SBS and OU)
- [x] **T2.5** — Implement stereoscopic 180° VR
- [x] **T2.6** — Center sphere at head pose position
- [x] **T2.7** — Disable virtual screen and environment geometry during 360°/180° playback

---

## 3. 3D Format Auto-Detection

### Architecture

Automatically detect container projection tags and filename naming patterns.

### Tasks

- [x] **T3.1** — Container metadata detection (`rust/core/src/format3d_detect.rs`):
  - MP4 `st3d`/`sv3d` boxes, MKV `StereoMode`/`Projection` elements, WebM side data
- [x] **T3.2** — Filename regex pattern fallback (`rust/media-logic/src/format3d.rs`):
  - Patterns: `_sbs`, `_hsbs`, `_ou`, `_360`, `_180`, `_vr180`, etc.
- [x] **T3.3** — Resolution and aspect ratio heuristics
- [x] **T3.4** — Manual confirmation / override UI modal (`ScreenFormatModal.kt`)

---

## 4. Head Tracking for 360° Content

### Architecture

Update the orientation of 360° spheres based on real-time OpenXR head tracking.

### Tasks

- [x] **T4.1** — Query head pose per frame via OpenXR:
  > Implemented: `vr_player_app_vulkan.cpp:7228` passes head orientation quaternion to `rust/bridge/src/lib.rs:2319` (`set_head_pose_orientation`).
- [x] **T4.2** — Apply rotation to view camera inside 360° sphere:
  > Implemented in Vulkan rendering pipeline using OpenXR view matrices.
- [x] **T4.3** — Implement recenter action:
  > Implemented via controller Menu button long-press (`vr_player_app_vulkan.cpp:1058`).
- [x] **T4.4** — Support 3DoF vs 6DoF handling for spherical media:
  > Implemented: translation is locked for infinite-depth 360° spheres to prevent visual distortion.

---

## 5. NFS Protocol

### Architecture

Connect to Linux NAS NFS exports using pure-Rust network client.

### Tasks

- [x] **T5.1** — Pure-Rust NFS client implementation (`rust/protocols/src/nfs/`):
  - Mount export and parse NFS URIs (`nfs/uri.rs`)
- [x] **T5.2** — Implement directory listing and file streaming via `RangeSource`
- [x] **T5.3** — Integrate with FFmpeg demuxer via `PrefetchReader` (`rust/core/src/demuxer.rs`)
- [x] **T5.4** — NFS configuration UI (`NetworkNfsScreen.kt`)

---

## 6. FTP and SFTP Protocols

### Architecture

Connect to FTP and SFTP servers with credential storage and streaming seeks.

### Tasks

- [x] **T6.1** — Implement FTP client in Rust:
  - Passive mode (`PASV`), authentication, `REST` + `RETR` offset seeking
- [x] **T6.2** — Implement pure-Rust SFTP client (`russh` / `russh-sftp`):
  - Password and PEM key authentication without native C dependencies
- [x] **T6.3** — Integrate FTP/SFTP with FFmpeg via custom stream I/O
- [x] **T6.4** — Connection management UI (`NetworkFtpScreen.kt`, `NetworkSftpScreen.kt`, secure credential storage)

---

## 7. DLNA/UPnP

### Architecture

Discover local network media servers and browse content directories via UPnP/DLNA.

### Tasks

- [x] **T7.1** — SSDP Discovery in Rust (`rust/protocols/src/discovery/ssdp.rs`):
  - Multicast `M-SEARCH` scanning for `MediaServer:1`
- [x] **T7.2** — Parse Device Description XML (`rust/protocols/src/dlna/device.rs`)
- [x] **T7.3** — ContentDirectory SOAP client and DIDL-Lite parser (`rust/protocols/src/dlna/soap.rs`, `didl.rs`)
- [x] **T7.4** — Integrate stream resource URLs into HTTP playback pipeline
- [x] **T7.5** — DLNA browsing UI (`NetworkDlnaScreen.kt`)
- [x] **T7.6** — Lightweight pure-Rust design without heavy C dependencies

---

## 8. HLS Streaming

### Architecture

Support HTTP Live Streaming (HLS) with adaptive quality switching.

### Tasks

- [x] **T8.1** — M3U8 playlist parser in Rust (`rust/protocols/src/hls/playlist.rs`)
- [x] **T8.2** — Segment downloader with prefetching (`rust/protocols/src/hls/stream.rs`)
- [x] **T8.3** — Integration with FFmpeg stream I/O
- [x] **T8.4** — Adaptive Bitrate (ABR) selection based on throughput (`rust/protocols/src/hls/abr.rs`)
- [x] **T8.5** — Seeking support within HLS streams
- [x] **T8.6** — UI quality indicator and stream selection

---

## 9. Subtitles (SRT, VTT)

### Architecture

Parse and render subtitle tracks synchronized with audio/video clocks.

### Tasks

- [x] **T9.1** — SRT parser in Rust (`rust/media-logic/src/subtitle.rs`)
- [x] **T9.2** — WebVTT parser in Rust
- [x] **T9.3** — VR text rendering with font atlas and Vulkan shaders (`subtitle.vert`, `subtitle.frag`, `subtitle_layout.h`)
- [x] **T9.4** — PTS clock synchronization and timing offsets
- [x] **T9.5** — Automatic character encoding detection (UTF-8, Latin1, Windows-1252)
- [x] **T9.6** — Subtitle track selection UI (`SubtitleSelectionModal.kt`)

---

## 10. Automatic Server Discovery

### Architecture

Unified discovery screen aggregating services across SMB, DLNA, and mDNS.

### Tasks

- [x] **T10.1** — Discovery manager coordinator (`NetworkDiscoveryScreen.kt`)
- [x] **T10.2** — mDNS/DNS-SD implementation (`rust/protocols/src/discovery/mdns.rs`)
- [x] **T10.3** — Aggregated local service scanning
- [x] **T10.4** — Unified discovery UI (`NetworkDiscoveryScreen.kt`)

---

## 11. Connection Management

### Architecture

Save and manage verified server connections with encrypted credentials.

### Tasks

- [x] **T11.1** — Room database `SavedServer` entity and DAO
- [x] **T11.2** — Full CRUD management in UI (`NetworkHomeScreen.kt`)
- [x] **T11.3** — Secure credential storage via `EncryptedSharedPreferences`
- [x] **T11.4** — Server connection testing and visual status badges

---

## 12. Library Search and Filters

### Architecture

Search and filter across media library files and directory hierarchies.

### Tasks

- [x] **T12.1** — Implement text search:
  > Implemented: `MediaFilterEngine.matchesSearch` and `findHighlightRanges` tested in `MediaFilterEngineTest.kt`; integrated into `VoidSearchBar.kt`.
- [x] **T12.2** — Implement filters:
  > Implemented: filters for media type (video/audio/image), 3D format, and date ranges in `MediaFilterEngine.kt`.
- [x] **T12.3** — Sorting by Name, Date, Size, Type (`MediaSorter.kt`)
- [x] **T12.4** — "Continue Watching" filtering for partially played media

---

## 13. Media Metadata

### Architecture

Extract technical file and container metadata using FFmpeg.

### Tasks

- [x] **T13.1** — Extract media metadata via FFmpeg (`rust/core/src/metadata.rs`, `metadata_wire.rs`)
- [x] **T13.2** — File details modal and audio track selector (`FileDetailScreen.kt`)
- [x] **T13.3** — Asynchronous thumbnail generator with disk cache

---

## 14. Thermal Monitoring

### Architecture

Proactively monitor Quest 3 thermal states to prevent aggressive device throttling.

### Tasks

- [x] **T14.1** — Implement `ThermalMonitor` with `PowerManager` thermal status callbacks (`ThermalMonitor.kt`)
- [x] **T14.2** — Dispatch thermal mitigation actions into Vulkan render pipeline (`vr_player_app_vulkan.cpp`)
- [x] **T14.3** — Visual thermal indicators and warnings in UI (`PlayerScreen.kt`)

---

## 15. Cross-Cutting Concerns of Phase 0.2

> [!IMPORTANT]
> **Room Database Migrations**: Preserve user history and saved servers across version upgrades using incremental Room migrations.

> [!WARNING]
> **8K 360° VRAM pressure**: 8K uncompressed frames require substantial memory. Monitor allocation and apply texture downscaling when memory pressure rises.

---

## 16. Definition of Done — v0.2

- [x] SBS and OU 3D video playback with correct depth separation
- [x] 360° mono and stereo video rendering on UV sphere with head tracking
- [x] 180° VR video rendering on hemisphere
- [x] Swap eyes toggle operational across 3D modes
- [x] 3D format auto-detection with manual override
- [x] NFS, FTP, SFTP network protocol browsing and streaming
- [x] DLNA media server discovery and content playback
- [x] HLS adaptive streaming support
- [x] SRT and WebVTT subtitle rendering with encoding detection
- [x] Automated server discovery via SSDP and mDNS
- [x] Saved servers persistence with encrypted credentials
- [x] Library search and filtering engine
- [x] Thermal monitoring with graceful pipeline mitigation
- [x] Host unit tests and protocol integration test suites passing

---

*Phase 0.2 — Estimate: 8-14 weeks for an experienced solo developer*
