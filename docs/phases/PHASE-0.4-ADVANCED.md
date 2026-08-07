# Fase 0.4 — Advanced

> **Objetivo**: Implementar funcionalidades avançadas — suporte a conteúdo 8K com qualidade adaptativa, DASH streaming, protocolo WebDAV, download offline, foveated rendering, e projeções avançadas (Cubemap, EAC).  
> **Pré-requisito**: Fase 0.3 completa e estável.  
> **Resultado esperado**: O player suporta conteúdo da mais alta qualidade disponível (8K VR), streaming adaptativo de múltiplas fontes, e usa técnicas avançadas de rendering para manter performance mesmo nos cenários mais pesados.

---

## 📋 Índice

1. [Suporte 8K com Adaptive Quality](#1-suporte-8k-com-adaptive-quality)
2. [DASH Streaming](#2-dash-streaming)
3. [Protocolo WebDAV](#3-protocolo-webdav)
4. [Download Offline](#4-download-offline)
5. [Foveated Rendering](#5-foveated-rendering)
6. [Projeções Avançadas — Cubemap e EAC](#6-projeções-avançadas--cubemap-e-eac)
7. [Cuidados Transversais da Fase 0.4](#7-cuidados-transversais-da-fase-04)

---

## 1. Suporte 8K com Adaptive Quality

### Conceito

Conteúdo VR 360° de alta qualidade requer resolução 8K (7680×3840 ou 7680×7680 para stereo) para ter densidade de pixels aceitável. O Quest 3 consegue decodificar 8K HEVC, mas o orçamento de GPU e térmico é apertadíssimo.

```
Resolução efetiva por olho em conteúdo 360° SBS:

4K (3840×1920) SBS → 1920×1920 por olho → ~10 PPD* → Qualidade baixa
6K (5760×2880) SBS → 2880×2880 por olho → ~15 PPD  → Qualidade aceitável
8K (7680×3840) SBS → 3840×3840 por olho → ~20 PPD  → Qualidade boa

*PPD = Pixels Per Degree (Quest 3 display: ~25 PPD)
```

### Tarefas

- [ ] **T1.1** — Implementar **8K HEVC decode** via MediaCodec:
  ```rust
  // Capacidades do XR2 Gen 2 para HEVC:
  // - 8K@30fps (7680×4320) decode: Main/Main10 profile
  // - 4K@120fps decode
  // - Surface mode OBRIGATÓRIO (sem buffer mode para 8K)
  
  fn create_8k_decoder() -> Result<MediaCodecDecoder> {
      let format = AMediaFormat_new();
      AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/hevc");
      AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, 7680);
      AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, 3840);
      AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024); // 4MB
      
      // Configurar prioridade de performance
      AMediaFormat_setInt32(format, "priority", 0); // 0 = realtime priority
      
      let codec = AMediaCodec_createDecoderByType("video/hevc");
      AMediaCodec_configure(codec, format, surface, null, 0);
      AMediaCodec_start(codec);
      
      Ok(MediaCodecDecoder { codec, format })
  }
  ```
- [ ] **T1.2** — Implementar **Adaptive Quality Manager**:
  ```rust
  /// Gerencia qualidade dinâmica baseado em métricas de runtime
  struct AdaptiveQualityManager {
      current_level: QualityLevel,
      thermal_state: ThermalState,
      frame_stats: FrameStats,
      decode_stats: DecodeStats,
  }
  
  #[derive(Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
  enum QualityLevel {
      Ultra,      // 8K nativo, ambiente completo, Ambisonics
      High,       // 8K nativo, ambiente simplificado, stereo audio
      Medium,     // 6K (downscale de 8K), void ambiente, stereo
      Low,        // 4K (downscale de 8K), void, stereo, 72fps
      Emergency,  // 4K, void, stereo, 72fps, reduced render resolution
  }
  
  impl AdaptiveQualityManager {
      fn evaluate(&mut self) -> QualityAction {
          // Critérios de degradação (qualquer um trigger):
          let should_degrade = 
              self.thermal_state >= ThermalState::Moderate
              || self.frame_stats.dropped_frames_last_sec > 3
              || self.frame_stats.avg_frame_time_ms > 12.0  // >12ms @ 90fps
              || self.decode_stats.avg_decode_time_ms > 30.0; // decoder struggling
          
          // Critérios de upgrade (TODOS devem ser verdade por 30s):
          let should_upgrade =
              self.thermal_state <= ThermalState::Light
              && self.frame_stats.dropped_frames_last_30s == 0
              && self.frame_stats.avg_frame_time_ms < 9.0
              && self.decode_stats.avg_decode_time_ms < 20.0;
          
          if should_degrade && self.current_level > QualityLevel::Emergency {
              self.current_level = self.current_level.lower();
              QualityAction::Degrade(self.current_level)
          } else if should_upgrade && self.current_level < QualityLevel::Ultra {
              self.current_level = self.current_level.higher();
              QualityAction::Upgrade(self.current_level)
          } else {
              QualityAction::Maintain
          }
      }
  }
  ```
- [ ] **T1.3** — Implementar **downscale de textura GPU**:
  - Para níveis Medium/Low: renderizar o frame 8K em uma textura intermediária de resolução menor
  - Usar mipmap ou render-to-texture com resolução reduzida
  - Isso economiza bandwidth de GPU (fragmento do shader lê textura menor)
  ```cpp
  // Downscale via render-to-texture
  void downscaleTexture(GLuint srcTexture, int srcW, int srcH,
                         GLuint dstTexture, int dstW, int dstH) {
      // Bind FBO com dstTexture como attachment
      glBindFramebuffer(GL_FRAMEBUFFER, downscaleFBO);
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, 
                              GL_TEXTURE_2D, dstTexture, 0);
      glViewport(0, 0, dstW, dstH);
      
      // Renderizar quad fullscreen com srcTexture
      // O hardware faz bilinear filtering no downscale
      glUseProgram(blitShader);
      glBindTexture(GL_TEXTURE_EXTERNAL_OES, srcTexture);
      drawFullscreenQuad();
  }
  ```
- [ ] **T1.4** — Implementar **métricas de performance** (C++):
  ```cpp
  struct FrameStats {
      float avgFrameTimeMs = 0;
      int droppedFramesLastSec = 0;
      int droppedFramesLast30s = 0;
      float gpuUtilization = 0;  // Via OVR Performance API
      
      void recordFrame(float frameTimeMs) {
          // Exponential moving average
          avgFrameTimeMs = avgFrameTimeMs * 0.95f + frameTimeMs * 0.05f;
          
          // Detectar dropped frame (>14ms @ 72fps, >11ms @ 90fps)
          float targetMs = 1000.0f / targetFps;
          if (frameTimeMs > targetMs * 1.2f) { // 20% margem
              droppedFramesLastSec++;
          }
      }
  };
  
  struct DecodeStats {
      float avgDecodeTimeMs = 0;
      int decodeQueueDepth = 0;   // Quantos frames pendentes no decoder
      bool decoderStarved = false; // Decoder não está recebendo dados rápido o suficiente
  };
  ```
- [ ] **T1.5** — **HUD de debug** (toggle via menu oculto):
  - FPS, frame time, GPU %, CPU %
  - Decode time, buffer queue depth
  - Thermal level, quality level
  - RAM usage
  - Network throughput (para streaming)
- [ ] **T1.6** — **Feedback ao usuário** sobre qualidade adaptativa:
  - Ícone discreto indicando nível de qualidade atual
  - Notificação ao mudar de nível: "Qualidade reduzida — dispositivo aquecendo"
  - Opção para forçar nível específico (com aviso de possível superaquecimento)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **8K HEVC consome TODO o bandwidth de memória do XR2 Gen 2**: Um frame 8K descompactado (NV12) é ~44MB. A 30fps, isso é ~1.3GB/s só de dados de vídeo passando pelo barramento. O Quest 3 tem ~51GB/s de bandwidth de memória — o vídeo sozinho consome ~2.5% do bandwidth total. Somado ao rendering VR (~30-40%), ambiente (~10-15%), UI (~5%), sobra pouco. Monitore bandwidth com OVR Metrics Tool.

> [!CAUTION]
> **NÃO tente 8K@60fps**: O Quest 3 NÃO consegue decodificar 8K HEVC a 60fps. O máximo é 8K@30fps. Se o conteúdo é 8K@60fps, faça frame doubling (repeat cada frame) ou downscale para 4K para reproduzir a 60fps. Detecte o framerate antes de iniciar e avise o usuário.

> [!WARNING]
> **Downgrade deve ser rápido, upgrade deve ser lento**: Quando performance cai, degrade a qualidade IMEDIATAMENTE (em 1-2 frames). Quando performance melhora, aguarde 30 SEGUNDOS estáveis antes de upgrade. Isso evita oscilação (flapping) entre níveis de qualidade que é pior que ficar num nível estável mais baixo.

> [!WARNING]
> **8K + passthrough = impossível**: O custo de passthrough + decodificação 8K + rendering excede o orçamento do Quest 3. Ao reproduzir conteúdo 8K, force ambiente void e desabilite passthrough. Informe o usuário.

> [!IMPORTANT]
> **Teste de duração longa**: 8K causa aquecimento rápido. Teste sessões de ≥ 60 minutos com conteúdo 8K para verificar que o adaptive quality mantém a experiência estável sem shutdown térmico.

---

## 2. DASH Streaming

### Conceito

DASH (Dynamic Adaptive Streaming over HTTP) é o padrão ISO para streaming adaptativo. Complementa o HLS (Apple) como protocolo de streaming.

```
DASH MPD (Media Presentation Description):

MPD
├── Period (segmento temporal)
│   ├── AdaptationSet (tipo de mídia: vídeo, áudio)
│   │   ├── Representation (qualidade 1: 1080p, 2Mbps)
│   │   │   └── SegmentTemplate: segment_$Number$.m4s
│   │   ├── Representation (qualidade 2: 4K, 8Mbps)
│   │   │   └── SegmentTemplate: segment_$Number$.m4s
│   │   └── Representation (qualidade 3: 8K, 30Mbps)
│   │       └── SegmentTemplate: segment_$Number$.m4s
│   └── AdaptationSet (áudio)
│       ├── Representation (AAC stereo, 128kbps)
│       └── Representation (AAC 5.1, 384kbps)
```

### Tarefas

- [ ] **T2.1** — Implementar parser **MPD (XML)** no Rust:
  ```rust
  use quick_xml::de::from_str;
  use serde::Deserialize;
  
  #[derive(Deserialize)]
  struct MPD {
      #[serde(rename = "Period")]
      periods: Vec<Period>,
      #[serde(rename = "@mediaPresentationDuration")]
      duration: Option<String>,  // ISO 8601 duration: "PT1H30M"
      #[serde(rename = "@type")]
      mpd_type: Option<String>,  // "static" (VOD) ou "dynamic" (live)
  }
  
  #[derive(Deserialize)]
  struct Period {
      #[serde(rename = "AdaptationSet")]
      adaptation_sets: Vec<AdaptationSet>,
  }
  
  #[derive(Deserialize)]
  struct AdaptationSet {
      #[serde(rename = "@mimeType")]
      mime_type: String,          // "video/mp4" ou "audio/mp4"
      #[serde(rename = "@contentType")]
      content_type: Option<String>, // "video" ou "audio"
      #[serde(rename = "Representation")]
      representations: Vec<Representation>,
      #[serde(rename = "SegmentTemplate")]
      segment_template: Option<SegmentTemplate>,
  }
  
  #[derive(Deserialize)]
  struct Representation {
      #[serde(rename = "@id")]
      id: String,
      #[serde(rename = "@bandwidth")]
      bandwidth: u64,             // bits/s
      #[serde(rename = "@width")]
      width: Option<u32>,
      #[serde(rename = "@height")]
      height: Option<u32>,
      #[serde(rename = "@codecs")]
      codecs: Option<String>,     // "avc1.64001f", "hev1.1.6.L150"
      #[serde(rename = "SegmentTemplate")]
      segment_template: Option<SegmentTemplate>,
      #[serde(rename = "BaseURL")]
      base_url: Option<String>,
  }
  
  #[derive(Deserialize)]
  struct SegmentTemplate {
      #[serde(rename = "@initialization")]
      initialization: Option<String>, // "init_$RepresentationID$.m4s"
      #[serde(rename = "@media")]
      media: String,                  // "segment_$Number$.m4s"
      #[serde(rename = "@timescale")]
      timescale: Option<u64>,
      #[serde(rename = "@duration")]
      duration: Option<u64>,          // Em unidades de timescale
      #[serde(rename = "@startNumber")]
      start_number: Option<u64>,
  }
  ```
- [ ] **T2.2** — Implementar **segment downloader** (Rust):
  - Download de initialization segment (moov/sidx)
  - Download de media segments sequenciais
  - Prefetch de 2-3 segments à frente
  - Byte-range requests quando SegmentBase é usado em vez de SegmentTemplate
  ```rust
  struct DashDownloader {
      client: reqwest::Client,
      mpd: MPD,
      current_period: usize,
      current_video_repr: usize,
      current_audio_repr: usize,
      current_segment: u64,
      buffer: SegmentBuffer,
  }
  
  impl DashDownloader {
      async fn download_next_segment(&mut self) -> Result<Segment> {
          let repr = &self.get_current_video_repr();
          let template = repr.segment_template.as_ref()
              .or(self.get_adaptation_set_template())?;
          
          // Construir URL do segment
          let url = template.media
              .replace("$Number$", &self.current_segment.to_string())
              .replace("$RepresentationID$", &repr.id);
          
          let response = self.client.get(&url).send().await?;
          let data = response.bytes().await?;
          
          self.current_segment += 1;
          Ok(Segment { data, index: self.current_segment - 1 })
      }
  }
  ```
- [ ] **T2.3** — Implementar **adaptive bitrate selection** para DASH:
  ```rust
  struct DashABRController {
      bandwidth_history: VecDeque<BandwidthSample>,
      current_repr_index: usize,
      representations: Vec<RepresentationInfo>,
  }
  
  impl DashABRController {
      fn select_representation(&mut self, measured_bandwidth_bps: u64) -> usize {
          self.bandwidth_history.push_back(BandwidthSample {
              bandwidth: measured_bandwidth_bps,
              timestamp: Instant::now(),
          });
          
          // Manter últimos 10 samples
          while self.bandwidth_history.len() > 10 {
              self.bandwidth_history.pop_front();
          }
          
          // Estimar bandwidth conservadoramente (percentil 20)
          let mut sorted: Vec<u64> = self.bandwidth_history.iter()
              .map(|s| s.bandwidth).collect();
          sorted.sort();
          let estimated_bw = sorted[sorted.len() / 5]; // P20
          
          // Selecionar a maior qualidade que cabe em 80% do bandwidth estimado
          let safe_bw = (estimated_bw as f64 * 0.8) as u64;
          
          self.representations.iter()
              .rposition(|r| r.bandwidth <= safe_bw)
              .unwrap_or(0) // Fallback para menor qualidade
      }
  }
  ```
- [ ] **T2.4** — Integrar segments com **FFmpeg demuxer**:
  - Opção A: FFmpeg nativo suporta DASH via `avformat_open_input("url.mpd")` — mais simples
  - Opção B: Custom segment download → pipe segments como fMP4 ao demuxer FFmpeg
  - Recomendação: FFmpeg nativo para DASH VOD. Custom download se precisa de mais controle sobre ABR.
- [ ] **T2.5** — **Seek em DASH**:
  - Calcular segment e offset dentro do segment baseado no timestamp
  - Descartar buffer, baixar segment correto
  - Se SegmentTimeline: buscar pelo `@t` (timestamp) mais próximo
  - Se SegmentTemplate com duration: `segment_number = timestamp * timescale / duration`
- [ ] **T2.6** — UI para DASH:
  - Indicador de qualidade/bitrate atual
  - Opção manual de qualidade (Auto / forçar resolução específica)
  - Buffer status (visualizar quanto está em buffer)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **DASH MPD é extremamente variável**: Cada implementação de servidor gera MPD diferente. Há três modos de endereçamento de segments:
> 1. **SegmentTemplate com $Number$** — mais comum, mais simples
> 2. **SegmentTimeline** — timestamps explícitos, necessário para live
> 3. **SegmentBase com byte ranges** — um único arquivo, seek via byte range
> 4. **SegmentList** — URLs explícitas para cada segment
>
> Implemente pelo menos SegmentTemplate e SegmentBase na v0.4. SegmentTimeline fica para live (não priorizado).

> [!WARNING]
> **Troca de representation durante playback**: Ao mudar de qualidade (ex: 1080p → 4K), o próximo segment terá resolução diferente. O MediaCodec PODE precisar de reset (flush + reconfigure) ou pode aceitar adaptive playback. Verifique via `FEATURE_AdaptivePlayback` no codec:
> ```kotlin
> val format = MediaFormat.createVideoFormat("video/hevc", 7680, 3840)
> format.setFeatureEnabled(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback, true)
> ```

> [!WARNING]
> **Initialization segment é obrigatório**: Antes de reproduzir qualquer media segment, o decoder PRECISA receber o initialization segment (contém moov atom com codec config). Se mudar de representation, precisa enviar novo init segment.

> [!IMPORTANT]
> **DASH vs HLS**: Na prática, muitos servidores de mídia pessoais NÃO geram DASH. HLS é mais comum em servidores pessoais. DASH é mais importante para conteúdo profissional e CDNs. Priorize HLS (já implementado na v0.2) e trate DASH como complemento.

> [!NOTE]
> **Crate `dash-mpd`**: Existe uma crate Rust `dash-mpd` que parseia MPD. Considere usar em vez de implementar parser custom. Porém, verifique se suporta todos os modos necessários.

---

## 3. Protocolo WebDAV

### O que fazer

Conectar a servidores WebDAV para navegar e reproduzir mídia. WebDAV é HTTP extensão para gerenciamento de arquivos — suportado por muitos NAS e serviços.

### Tarefas

- [ ] **T3.1** — Implementar cliente **WebDAV** no Rust:
  ```rust
  use reqwest::Client;
  
  struct WebDavClient {
      client: Client,
      base_url: String,
      auth: Option<(String, String)>, // user, password
  }
  
  impl WebDavClient {
      /// Listar conteúdo de um diretório (PROPFIND)
      async fn list_dir(&self, path: &str) -> Result<Vec<DavEntry>> {
          let url = format!("{}/{}", self.base_url, path);
          let body = r#"<?xml version="1.0" encoding="utf-8"?>
              <D:propfind xmlns:D="DAV:">
                  <D:prop>
                      <D:resourcetype/>
                      <D:getcontentlength/>
                      <D:getlastmodified/>
                      <D:getcontenttype/>
                      <D:displayname/>
                  </D:prop>
              </D:propfind>"#;
          
          let response = self.client
              .request(reqwest::Method::from_bytes(b"PROPFIND")?, &url)
              .header("Depth", "1")
              .header("Content-Type", "application/xml")
              .basic_auth(&self.auth.0, Some(&self.auth.1))
              .body(body)
              .send()
              .await?;
          
          // Parse XML multistatus response
          let xml = response.text().await?;
          parse_multistatus_xml(&xml)
      }
      
      /// Ler arquivo com range (para streaming/seek)
      async fn read_range(&self, path: &str, start: u64, end: u64) -> Result<Bytes> {
          let url = format!("{}/{}", self.base_url, path);
          let response = self.client
              .get(&url)
              .header("Range", format!("bytes={}-{}", start, end))
              .basic_auth(&self.auth.0, Some(&self.auth.1))
              .send()
              .await?;
          
          Ok(response.bytes().await?)
      }
  }
  
  struct DavEntry {
      href: String,
      is_collection: bool,  // Diretório
      content_length: Option<u64>,
      last_modified: Option<String>,
      content_type: Option<String>,
      display_name: Option<String>,
  }
  ```
- [ ] **T3.2** — Integrar WebDAV com pipeline de playback:
  - WebDAV suporta range requests nativamente (é HTTP)
  - Implementar custom I/O para FFmpeg usando `read_range()`
  - Buffer de 4-8MB para smooth streaming
- [ ] **T3.3** — Integrar com **discovery automático**:
  - mDNS: `_webdav._tcp.local`
  - Adicionar tipo WebDAV ao `SavedServer` schema
- [ ] **T3.4** — UI para configurar conexão WebDAV:
  - URL base (ex: `https://nas.local:5006/webdav`)
  - Usuário + Senha
  - Toggle HTTPS / aceitar certificado self-signed

### ⚠️ Cuidados e Armadilhas

> [!WARNING]
> **WebDAV PROPFIND response é XML verboso**: A resposta de um diretório com muitos arquivos pode ter centenas de KB de XML. Parse incrementalmente com streaming XML parser (`quick-xml` em modo reader) para evitar picos de memória.

> [!WARNING]
> **Encoding de paths**: WebDAV herda as complexidades de encoding de URLs. Nomes de arquivo com espaços, acentos, ou caracteres especiais devem ser percent-encoded. Use `percent_encoding` crate:
> ```rust
> use percent_encoding::{utf8_percent_encode, NON_ALPHANUMERIC};
> let encoded = utf8_percent_encode("meu vídeo (final).mkv", NON_ALPHANUMERIC);
> ```

> [!IMPORTANT]
> **HTTPS com self-signed certificates**: Muitos NAS usam HTTPS com certificados self-signed. O `reqwest` rejeita por padrão. Ofereça opção ao usuário de aceitar:
> ```rust
> let client = reqwest::Client::builder()
>     .danger_accept_invalid_certs(accept_self_signed) // APENAS se o usuário aceitar
>     .build()?;
> ```

> [!NOTE]
> **WebDAV é essencialmente HTTP com extras**: Se o HTTP playback da v0.1 funciona bem, WebDAV para streaming é quase idêntico (GET com Range headers). A parte complexa é apenas o browsing de diretórios via PROPFIND.

---

## 4. Download Offline

### O que fazer

Permitir ao usuário baixar mídia de servidores remotos para reprodução offline no armazenamento do Quest 3.

### Tarefas

- [ ] **T4.1** — Implementar **download manager** (Rust):
  ```rust
  struct DownloadManager {
      active_downloads: Vec<DownloadTask>,
      download_dir: PathBuf,
      max_concurrent: usize,   // Máximo de downloads simultâneos (2-3)
  }
  
  struct DownloadTask {
      id: Uuid,
      source_uri: String,
      source_type: SourceType,  // SMB, NFS, FTP, SFTP, HTTP, WebDAV
      dest_path: PathBuf,
      total_bytes: u64,
      downloaded_bytes: AtomicU64,
      state: AtomicDownloadState,
      speed_bps: AtomicU64,     // Velocidade atual
      created_at: Instant,
  }
  
  #[derive(Clone, Copy)]
  enum DownloadState {
      Queued,
      Downloading,
      Paused,
      Completed,
      Failed(DownloadError),
      Cancelled,
  }
  
  impl DownloadManager {
      async fn start_download(&mut self, source: &str, source_type: SourceType) -> Result<Uuid> {
          let task_id = Uuid::new_v4();
          
          // Determinar tamanho total
          let total_bytes = match source_type {
              SourceType::Http => self.get_http_content_length(source).await?,
              SourceType::Smb => self.get_smb_file_size(source).await?,
              SourceType::Sftp => self.get_sftp_file_size(source).await?,
              // ...
          };
          
          // Determinar caminho de destino
          let filename = extract_filename(source);
          let dest = self.download_dir.join(&filename);
          
          let task = DownloadTask {
              id: task_id,
              source_uri: source.to_string(),
              source_type,
              dest_path: dest,
              total_bytes,
              downloaded_bytes: AtomicU64::new(0),
              state: AtomicDownloadState::new(DownloadState::Queued),
              speed_bps: AtomicU64::new(0),
              created_at: Instant::now(),
          };
          
          self.active_downloads.push(task);
          self.schedule_next();
          
          Ok(task_id)
      }
      
      async fn download_with_resume(&self, task: &DownloadTask) -> Result<()> {
          let mut file = OpenOptions::new()
              .create(true).append(true)
              .open(&task.dest_path)?;
          
          let existing_bytes = file.metadata()?.len();
          
          // Resumir do ponto onde parou
          match task.source_type {
              SourceType::Http => {
                  let resp = self.client.get(&task.source_uri)
                      .header("Range", format!("bytes={}-", existing_bytes))
                      .send().await?;
                  
                  let mut stream = resp.bytes_stream();
                  while let Some(chunk) = stream.try_next().await? {
                      file.write_all(&chunk)?;
                      task.downloaded_bytes.fetch_add(chunk.len() as u64, Ordering::Relaxed);
                  }
              }
              // Implementar resume para cada protocolo...
              _ => { /* ... */ }
          }
          
          Ok(())
      }
  }
  ```
- [ ] **T4.2** — Implementar **resumo de download interrompido**:
  - Salvar progresso periodicamente (bytes baixados)
  - Se o app fechar/crashar, retomar do último byte ao reiniciar
  - Verificar integridade após download (comparar tamanho)
- [ ] **T4.3** — Implementar **fila de downloads**:
  - Máximo 2-3 downloads simultâneos (economizar bandwidth para playback)
  - Prioridade: manual (drag to top) ou FIFO
  - Pausar/Retomar/Cancelar individual
- [ ] **T4.4** — **Persistência** da fila de downloads:
  ```kotlin
  @Entity
  data class Download(
      @PrimaryKey val id: String,           // UUID
      val sourceUri: String,
      val sourceType: SourceType,
      val destinationPath: String,
      val totalBytes: Long,
      val downloadedBytes: Long,
      val state: DownloadState,
      val createdAt: Instant,
      val completedAt: Instant?,
      val errorMessage: String?,
      val serverInfo: String?,              // JSON com credenciais do servidor
  )
  ```
- [ ] **T4.5** — UI de **gerenciador de downloads**:
  - Lista de downloads com progresso (%, MB baixados, velocidade, ETA)
  - Barra de progresso visual
  - Botões: Pausar, Retomar, Cancelar, Retry
  - Notificação quando download completa
- [ ] **T4.6** — **Gestão de espaço em disco**:
  - Mostrar espaço livre do Quest 3
  - Aviso quando espaço < 2GB
  - Opção de limpar downloads antigos
- [ ] **T4.7** — Integrar downloads com **biblioteca**:
  - Arquivos baixados aparecem automaticamente na biblioteca local
  - Link entre download e arquivo local (para saber a origem)

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Download em background no Android**: Quando o app vai para background (usuário tira o headset), o Android pode matar o processo. Use `ForegroundService` com notificação para manter downloads vivos:
> ```kotlin
> class DownloadService : Service() {
>     override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
>         val notification = createNotification("Baixando vídeo...")
>         startForeground(NOTIFICATION_ID, notification)
>         // Iniciar download em coroutine
>         return START_STICKY
>     }
> }
> ```

> [!WARNING]
> **Espaço em disco do Quest 3**: O Quest 3 tem 128GB ou 512GB, mas o sistema usa ~30GB. Com vídeos 4K de 2h (~20GB cada), o espaço acaba rápido. SEMPRE verifique espaço disponível antes de iniciar download e avise o usuário.

> [!WARNING]
> **Downloads de SMB/NFS são sequenciais**: Ao contrário de HTTP (que suporta range requests para resume), SMB e NFS fazem read sequencial. Se o download for interrompido, pode ser necessário re-baixar desde o início dependendo do protocolo. Implemente resume via offset read quando o protocolo suportar.

> [!IMPORTANT]
> **Não faça download durante playback de rede**: Se o usuário está assistindo um vídeo via SMB e também baixando outro arquivo do mesmo servidor, ambos competem por bandwidth. Opção: pausar downloads automaticamente durante playback de rede, ou limitar bandwidth do download a 50%.

> [!NOTE]
> **Verificação de integridade**: Servidores que suportam `Content-MD5` ou `ETag` permitem verificar se o download está completo e íntegro. Use quando disponível.

---

## 5. Foveated Rendering

### Conceito

Foveated rendering reduz a resolução da imagem na periferia do campo de visão (onde o olho humano tem baixa acuidade), economizando GPU significativamente.

```
Resolução por região do campo de visão:

    ┌─────────────────────────────┐
    │  ░░░░░░░░░░░░░░░░░░░░░░░░  │  ← Periferia: resolução BAIXA (25-50%)
    │  ░░░░░▒▒▒▒▒▒▒▒▒▒▒▒░░░░░░  │
    │  ░░░░▒▒▒▒▓▓▓▓▓▓▒▒▒▒░░░░░  │  ← Meia-periferia: resolução MÉDIA (50-75%)
    │  ░░░░▒▒▒▓▓████▓▓▒▒▒░░░░░  │
    │  ░░░░▒▒▒▓▓████▓▓▒▒▒░░░░░  │  ← Centro (fóvea): resolução COMPLETA (100%)
    │  ░░░░▒▒▒▒▓▓▓▓▓▓▒▒▒▒░░░░░  │
    │  ░░░░░▒▒▒▒▒▒▒▒▒▒▒▒░░░░░░  │
    │  ░░░░░░░░░░░░░░░░░░░░░░░░  │
    └─────────────────────────────┘
    
Fixed Foveated Rendering (FFR): Centro fixo da lente
Eye-Tracked Foveated Rendering: Centro segue o olhar (v0.5)
```

### Tarefas

- [ ] **T5.1** — Habilitar **Fixed Foveated Rendering (FFR)** via Meta SDK:
  ```cpp
  // Meta OpenXR extension para FFR
  // XR_FB_foveation + XR_FB_foveation_configuration
  
  // Criar perfil de foveação
  XrFoveationProfileCreateInfoFB profileCreateInfo = {
      XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB
  };
  
  XrFoveationLevelProfileCreateInfoFB levelProfile = {
      XR_TYPE_FOVEATION_LEVEL_PROFILE_CREATE_INFO_FB
  };
  levelProfile.level = XR_FOVEATION_LEVEL_HIGH_FB;  // Alta redução na periferia
  levelProfile.verticalOffset = 0.0f;
  levelProfile.dynamic = XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB;  // Adapta ao desempenho
  
  profileCreateInfo.next = &levelProfile;
  
  XrFoveationProfileFB foveationProfile;
  xrCreateFoveationProfileFB(session, &profileCreateInfo, &foveationProfile);
  
  // Aplicar ao swapchain
  XrSwapchainStateFoveationFB foveationState = {
      XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB
  };
  foveationState.profile = foveationProfile;
  xrUpdateSwapchainFB(swapchain, (XrSwapchainStateBaseHeaderFB*)&foveationState);
  ```
- [ ] **T5.2** — Configurar **níveis de foveação** adaptáveis:
  ```cpp
  enum FoveationLevel {
      OFF,       // Sem foveation — máxima qualidade (caro)
      LOW,       // Redução sutil na borda extrema (~10% economia GPU)
      MEDIUM,    // Redução moderada (~20% economia GPU)
      HIGH,      // Redução agressiva na periferia (~35% economia GPU)
      HIGH_TOP,  // HIGH + redução extra no topo (onde geralmente não se olha)
  };
  
  // Integrar com AdaptiveQualityManager:
  void applyFoveation(FoveationLevel level) {
      XrFoveationLevelFB xrLevel;
      switch (level) {
          case OFF:       xrLevel = XR_FOVEATION_LEVEL_NONE_FB; break;
          case LOW:       xrLevel = XR_FOVEATION_LEVEL_LOW_FB; break;
          case MEDIUM:    xrLevel = XR_FOVEATION_LEVEL_MEDIUM_FB; break;
          case HIGH:
          case HIGH_TOP:  xrLevel = XR_FOVEATION_LEVEL_HIGH_FB; break;
      }
      // Atualizar profile...
  }
  ```
- [ ] **T5.3** — Integrar foveation com **Adaptive Quality Manager**:
  - Nível `Ultra`: foveation OFF (qualidade máxima)
  - Nível `High`: foveation LOW
  - Nível `Medium`: foveation MEDIUM
  - Nível `Low`: foveation HIGH
  - Nível `Emergency`: foveation HIGH_TOP + dynamic
- [ ] **T5.4** — **Foveated decode** (avançado — investigar viabilidade):
  - Conceito: decodificar o vídeo em resolução total apenas no centro do campo de visão, e em resolução reduzida na periferia
  - Isso requer suporte do formato de vídeo (HEVC tiles) ou pós-processamento
  - Para v0.4: investigar viabilidade, implementar se prático
  ```
  Foveated Decode (conceito):
  
  ┌─────────────┐     ┌─────────────┐
  │ ░░░░░░░░░░░ │     │ Low-res     │ ← Tile periférico: decode em 1/4 res
  │ ░░█████░░░░ │  =  │ ┌───────┐   │
  │ ░░█████░░░░ │     │ │Hi-res │   │ ← Tile central: decode em full res
  │ ░░░░░░░░░░░ │     │ └───────┘   │
  └─────────────┘     └─────────────┘
  ```
- [ ] **T5.5** — Opção de **desabilitar foveation** no menu de settings:
  - Alguns usuários preferem qualidade uniforme
  - Mostrar impacto estimado: "Foveation ALTA: +35% performance, -20% qualidade periférica"

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **FFR no Quest é aplicado PELO RUNTIME, não pelo app**: No Quest 3, o FFR é implementado no compositor do Meta OpenXR runtime. Seu app não precisa (e não deve) implementar foveation manualmente no shader. Use APENAS a API de `XR_FB_foveation`. Se tentar fazer foveation manual, o resultado será foveation dupla (aplicada duas vezes).

> [!WARNING]
> **Foveation em conteúdo de vídeo vs. ambiente**: A foveation funciona melhor em ambientes 3D renderizados (onde a geometria periférica tem menos detalhe). Para a TEXTURA de vídeo na tela virtual, a foveation pode causar artefatos visíveis se a tela ocupa grande parte do FOV. O runtime geralmente é inteligente sobre isso, mas monitore visualmente.

> [!WARNING]
> **Dynamic foveation pode causar pulsação visual**: `XR_FOVEATION_DYNAMIC_LEVEL_ENABLED_FB` faz o runtime ajustar o nível de foveation frame-a-frame baseado na carga de GPU. Se a carga oscila, a qualidade periférica "pulsa". Se isso for perceptível, use nível fixo em vez de dinâmico.

> [!IMPORTANT]
> **Teste de qualidade visual**: Foveation em nível HIGH reduz significativamente a qualidade na periferia. Peça a múltiplos testadores para avaliar se a redução é aceitável. Pessoas com visão periférica mais sensível podem notar e se incomodar.

> [!NOTE]
> **Economia real de GPU**: No Quest 3, FFR nível HIGH economiza ~30-40% de fragment shading, que é tipicamente o gargalo principal. Isso se traduz em ~20-30% mais headroom para conteúdo pesado como ambientes 3D + 8K vídeo.

---

## 6. Projeções Avançadas — Cubemap e EAC

### Conceito

Além de equirectangular (padrão), existem projeções que distribuem pixels mais eficientemente:

```
Equirectangular:              Cubemap:                   EAC (Equi-Angular Cubemap):
┌──────────────────┐         ┌───┬───┬───┐              ┌───┬───┬───┐
│  ████████████████│         │ T │ B │ F │              │ T │ B │ F │
│  ████████████████│         ├───┼───┼───┤              ├───┼───┼───┤
│  ████████████████│         │ Bk│ L │ R │              │ Bk│ L │ R │
└──────────────────┘         └───┴───┴───┘              └───┴───┴───┘
                             6 faces de um cubo          Distribuição angular
                                                         uniforme nos cubemaps
                                                         (melhor qualidade nos
                                                         cantos)

Equirectangular: Distorção nos polos, desperdício de pixels
Cubemap: Sem distorção polar, mas distorção nos cantos das faces
EAC: Otimizado por Google para YouTube VR, distribuição uniforme
```

### Tarefas

- [ ] **T6.1** — Implementar renderização de **Cubemap projetado na esfera**:
  ```cpp
  // Cubemap layout: 6 faces organizadas em uma textura 2D
  // Layouts comuns:
  //   3x2: [Right, Left, Top, Bottom, Front, Back]
  //   6x1: [R, L, T, B, F, Bk] horizontal
  //   1x6: vertical
  //   Cross: formato de cruz (Google, FB)
  
  struct CubemapLayout {
      enum Type { LAYOUT_3x2, LAYOUT_6x1, LAYOUT_1x6, LAYOUT_CROSS };
      Type type;
      
      // Retorna UV rect para cada face
      Rect getFaceRect(CubeFace face) const {
          switch (type) {
              case LAYOUT_3x2:
                  // Row 0: Right(0,0) Left(1,0) Top(2,0)
                  // Row 1: Bottom(0,1) Front(1,1) Back(2,1)
                  static const Rect rects[6] = {
                      {0.0f, 0.0f, 1.0f/3, 0.5f},      // Right
                      {1.0f/3, 0.0f, 2.0f/3, 0.5f},     // Left
                      {2.0f/3, 0.0f, 1.0f, 0.5f},        // Top
                      {0.0f, 0.5f, 1.0f/3, 1.0f},        // Bottom
                      {1.0f/3, 0.5f, 2.0f/3, 1.0f},      // Front
                      {2.0f/3, 0.5f, 1.0f, 1.0f},         // Back
                  };
                  return rects[(int)face];
              // ... outros layouts
          }
      }
  };
  ```
- [ ] **T6.2** — Implementar shader **Cubemap sampling**:
  ```glsl
  // Fragment shader para cubemap projection
  uniform sampler2D videoTexture;  // Textura 2D com cubemap layout
  uniform int cubemapLayout;        // 0=3x2, 1=6x1, etc.
  
  in vec3 vWorldDirection;  // Direção no mundo (da esfera)
  
  // Converter direção 3D para face do cubo + UV 2D
  void cubemapDirection(vec3 dir, out int face, out vec2 uv) {
      vec3 absDir = abs(dir);
      
      if (absDir.x >= absDir.y && absDir.x >= absDir.z) {
          face = dir.x > 0.0 ? 0 : 1;  // Right or Left
          uv = vec2(-dir.z, -dir.y) / absDir.x;
      } else if (absDir.y >= absDir.x && absDir.y >= absDir.z) {
          face = dir.y > 0.0 ? 2 : 3;  // Top or Bottom
          uv = vec2(dir.x, dir.z) / absDir.y;
      } else {
          face = dir.z > 0.0 ? 4 : 5;  // Front or Back
          uv = vec2(dir.x, -dir.y) / absDir.z;
      }
      
      uv = uv * 0.5 + 0.5;  // [-1,1] → [0,1]
  }
  
  void main() {
      int face;
      vec2 faceUV;
      cubemapDirection(normalize(vWorldDirection), face, faceUV);
      
      // Mapear face UV para posição na textura 2D (baseado no layout)
      vec4 faceRect = getFaceRect(face, cubemapLayout);
      vec2 texUV = mix(faceRect.xy, faceRect.zw, faceUV);
      
      fragColor = texture(videoTexture, texUV);
  }
  ```
- [ ] **T6.3** — Implementar **EAC (Equi-Angular Cubemap)**:
  ```glsl
  // EAC aplica uma curva tangente ao mapeamento UV para distribuir
  // pixels uniformemente angularmente (em vez de linearmente)
  
  vec2 eacTransform(vec2 uv) {
      // Transformação EAC: atan → distribuição angular uniforme
      return vec2(
          (2.0 / PI) * atan(2.0 * uv.x - 1.0) + 0.5,
          (2.0 / PI) * atan(2.0 * uv.y - 1.0) + 0.5
      );
  }
  
  vec2 eacInverseTransform(vec2 uv) {
      // Inversa: para sampling da textura EAC
      return vec2(
          0.5 * (tan(PI * (uv.x - 0.5)) + 1.0),
          0.5 * (tan(PI * (uv.y - 0.5)) + 1.0)
      );
  }
  
  // No fragment shader, após obter faceUV:
  vec2 eacUV = eacInverseTransform(faceUV);
  vec2 texUV = mix(faceRect.xy, faceRect.zw, eacUV);
  ```
- [ ] **T6.4** — Detectar tipo de **projeção por metadados**:
  ```rust
  // MP4 sv3d box contém informação de projeção:
  // - Equirectangular: projection_type = 0
  // - Cubemap: projection_type = 1
  // - EAC: projection_type = 2 (ou custom Google extension)
  
  // MKV Projection element:
  // - ProjectionType: 0=rectangular, 1=equirectangular, 2=cubemap
  
  fn detect_projection(metadata: &MediaMetadata) -> Projection {
      if let Some(proj_type) = metadata.get("ProjectionType") {
          match proj_type {
              0 => Projection::Rectangular,
              1 => Projection::Equirectangular,
              2 => Projection::Cubemap,
              _ => Projection::Unknown,
          }
      } else {
          // Heurísticas:
          // - Aspect ratio 2:1 → equirectangular
          // - Aspect ratio 3:2 com faces visíveis → cubemap 3x2
          // - Aspect ratio 6:1 → cubemap 6x1
          heuristic_detect(metadata)
      }
  }
  ```
- [ ] **T6.5** — Suportar **cubemap stereo**:
  - Cubemap SBS: 6 faces para olho esquerdo + 6 faces para olho direito (12 faces total)
  - Layout varia: pode ser 6x2 ou duas seções 3x2
  - Detectar por metadados + heurística de resolução
- [ ] **T6.6** — UI para **seleção manual de projeção**:
  - Dropdown: Equirectangular / Cubemap 3x2 / Cubemap 6x1 / EAC / Flat
  - Override manual quando auto-detecção falha
  - Persistir escolha por arquivo

### ⚠️ Cuidados e Armadilhas

> [!CAUTION]
> **Cubemap seams (costuras)**: Na junção entre faces do cubemap, filtros de textura bilinear podem amostrar pixels da face adjacente, causando linhas visíveis. Soluções:
> 1. Adicionar 1-2 pixels de padding em cada face (replicar borda da face vizinha)
> 2. Usar filtering clamp-to-edge por face
> 3. Pré-processar a textura para incluir padding
>
> ```glsl
> // Clamp UV para evitar bleeding nas bordas
> faceUV = clamp(faceUV, 0.5 / faceSize, 1.0 - 0.5 / faceSize);
> ```

> [!WARNING]
> **EAC tangent singularity**: A função `tan()` no EAC tem singularidades em ±π/2 (bordas das faces). Clampe o input para evitar valores infinitos:
> ```glsl
> float safeAtan(float x) {
>     return atan(clamp(x, -1000.0, 1000.0));  // Evitar infinito
> }
> ```

> [!WARNING]
> **Cubemap layouts não são padronizados**: Cada plataforma/criador pode usar layout diferente. YouTube usa EAC específico. Facebook/Meta usa cubemap com rotação. Câmeras Insta360 usam layout próprio. Suporte os layouts mais comuns (3x2, 6x1, cross) e ofereça configuração manual.

> [!IMPORTANT]
> **Qualidade EAC vs Equirectangular**: EAC tem ~25% menos pixels que equirectangular para a mesma qualidade percebida (distribuição mais uniforme). Mas a decodificação consome a mesma quantidade de recursos. A vantagem está no tamanho do arquivo (menor bitrate para mesma qualidade).

> [!NOTE]
> **Conteúdo EAC é raro fora do YouTube**: A maioria do conteúdo VR disponível para download usa equirectangular. EAC é predominantemente usado no streaming do YouTube VR. Priorize equirectangular e cubemap; EAC é "nice to have".

---

## 7. Cuidados Transversais da Fase 0.4

### Complexidade Acumulada

> [!CAUTION]
> **Neste ponto o projeto tem 4 camadas de código interagindo**:
> 1. Kotlin (app logic, persistência, rede alto nível)
> 2. Rust (decodificação, protocolos, streaming)
> 3. C++ (OpenXR, rendering, shaders)
> 4. GLSL (shaders de projeção, foveation, PBR)
>
> A complexidade de debugging é multiplicativa. Invista em:
> - **Logging estruturado** em todas as camadas com correlation IDs
> - **HUD de debug** que mostra estado de cada camada em tempo real
> - **Crash reporting** com stack traces cross-language (Kotlin ↔ Rust ↔ C++)

### Storage Management

> [!IMPORTANT]
> **A feature de download offline + vídeos 8K podem esgotar o storage rapidamente**:
> - 1 vídeo 8K de 30min ≈ 15-30GB
> - Quest 3 128GB modelo: ~90GB usáveis
> - 3-5 vídeos 8K = storage cheio
>
> Implemente:
> 1. Verificação de espaço ANTES de iniciar download
> 2. Aviso quando espaço < 10%
> 3. Sugestão de limpeza de downloads antigos
> 4. Opção de "stream only" (não baixar, reproduzir direto da rede)

### Testes de Compatibilidade

> [!IMPORTANT]
> **A fase 0.4 é onde diferenças entre headsets ficam mais evidentes**:
>
> | Feature | Quest 3 | Quest 3S | Quest Pro | Quest 2 |
> |---------|---------|----------|-----------|---------|
> | 8K HEVC HW | ✅ 30fps | ✅ 30fps | ❌ 4K max | ❌ 4K max |
> | FFR | ✅ High | ✅ High | ✅ High | ✅ Medium max |
> | Foveated decode | ❌ N/A | ❌ N/A | ❌ N/A | ❌ N/A |
> | GPU budget para 8K+env | Apertado | Muito apertado | Insuficiente | Insuficiente |
>
> Para Quest 2 e Quest Pro: limite resolução a 4K e force adaptive quality MEDIUM como máximo.

### Migrations de Banco de Dados

> [!WARNING]
> **4ª migração do Room**: v0.4 adiciona tabela `Download`. Garanta que a migration chain está intacta: v0.1 → v0.2 → v0.3 → v0.4. Teste fresh install E upgrade de cada versão anterior.

### Performance do Shader de Projeção

> [!NOTE]
> **Cubemap sampling com `atan()` e `tan()` é ~2-3x mais caro que equirectangular sampling simples**. Para EAC, ainda mais caro. No Quest 3, isso importa quando o fragment shader é o gargalo. Monitore GPU fragment shading time com OVR Metrics Tool. Se necessário, pré-compute um lookup table em textura para evitar `atan/tan` no shader.

---

## Definição de Pronto (Definition of Done) — v0.4

### 8K + Adaptive Quality
- [ ] Vídeo 8K HEVC (7680×3840) reproduz no Quest 3 a 30fps sem drops
- [ ] Adaptive Quality Manager degrada qualidade em < 2 frames quando FPS cai
- [ ] Adaptive Quality Manager não faz upgrade sem 30s de estabilidade
- [ ] HUD de debug mostra todas as métricas em tempo real
- [ ] Sessão de 60 minutos com 8K sem crash ou shutdown térmico
- [ ] 8K + void ambiente: ≥ 72 FPS sustentado
- [ ] 8K + ambiente Cinema: adaptive quality degrada para void automaticamente

### DASH Streaming
- [ ] MPD com SegmentTemplate parseia e reproduz corretamente
- [ ] MPD com SegmentBase (byte-range) parseia e reproduz corretamente
- [ ] Adaptive bitrate selection muda de qualidade sem glitch visual
- [ ] Seek em stream DASH funciona com < 3s de delay
- [ ] Pelo menos 2 servidores DASH testados (ex: dash.js reference streams)

### WebDAV
- [ ] PROPFIND lista diretórios corretamente
- [ ] Streaming de vídeo via WebDAV com seek funciona
- [ ] HTTPS com certificado válido funciona
- [ ] HTTPS com certificado self-signed funciona (com confirmação do usuário)
- [ ] Credenciais salvas com criptografia

### Download Offline
- [ ] Download de arquivo HTTP completa e é reproduzível
- [ ] Download de arquivo SMB completa e é reproduzível
- [ ] Pause/Resume de download funciona (não re-baixa dados existentes)
- [ ] Fila de downloads respeita limite de concorrência
- [ ] Download continua em background (com ForegroundService)
- [ ] Aviso de espaço em disco antes de download que ultrapassaria limite
- [ ] Arquivos baixados aparecem na biblioteca local automaticamente

### Foveated Rendering
- [ ] FFR nível LOW ativo sem artefatos visíveis no centro
- [ ] FFR nível HIGH reduz GPU usage em ≥ 20%
- [ ] Integração com Adaptive Quality: foveation aumenta automaticamente sob stress
- [ ] Toggle on/off funciona no menu de configurações

### Projeções Avançadas
- [ ] Cubemap 3x2 renderiza sem costuras visíveis
- [ ] Cubemap 6x1 renderiza corretamente
- [ ] EAC renderiza com distribuição angular correta
- [ ] Auto-detecção de projeção acerta em ≥ 70% dos arquivos com metadados
- [ ] Override manual de projeção funciona
- [ ] Cubemap stereo (SBS) renderiza com profundidade correta

### Geral
- [ ] Nenhuma regressão nos testes das fases 0.1, 0.2 e 0.3
- [ ] Migração de banco v0.3 → v0.4 preserva todos os dados
- [ ] Fresh install funciona sem erros
- [ ] Quest 2 / Quest Pro: features 8K gracefully degradam para 4K

---

*Fase 0.4 — Estimativa: 8-12 semanas para desenvolvedor solo experiente*  
*Esta fase pode ser parcialmente paralelizada: DASH e WebDAV são independentes de Foveated Rendering e projeções.*
