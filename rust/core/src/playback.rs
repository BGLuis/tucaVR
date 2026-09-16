use crate::demuxer::Demuxer;
use crate::decoder::HwDecoder;
use crate::audio_decoder::AudioDecoder;
use audio::output::AudioOutput;
use crate::sync::SyncManager;
use crate::texture::TextureOutput;
use ndk::media::media_format::MediaFormat;
use std::sync::atomic::{AtomicI32, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

// P-05 (docs/reports/TRAVAMENTOS-POS-REINICIO-DO-HEADSET.md): tid das 3 threads do pipeline,
// reportado pra C++ registrar como thread critica ao runtime XR via
// xrSetAndroidApplicationThreadKHR (rust/bridge/src/lib.rs expoe getters que leem estas
// estaticas — C++ e o unico com acesso ao XrSession, entao o registro em si acontece la; aqui
// so publicamos o tid). 0 = a thread ainda nao subiu.
pub static DEMUX_THREAD_TID: AtomicI32 = AtomicI32::new(0);
pub static VIDEO_THREAD_TID: AtomicI32 = AtomicI32::new(0);
pub static AUDIO_THREAD_TID: AtomicI32 = AtomicI32::new(0);

/// RAM total do dispositivo em bytes, reportada uma vez pelo lado Kotlin
/// (`ActivityManager.MemoryInfo.totalMem`, ver `set_device_total_memory_bytes`
/// na bridge) — usada pelo `buffer_gate` para escalar o teto do buffer
/// profundo pausado (ver `media_logic::buffer_gate::paused_byte_ceiling_for_device`)
/// em vez de um numero fixo, entao um aparelho futuro com menos/mais RAM que o Quest 3
/// recebe automaticamente um teto proporcionalmente menor/maior (dentro dos
/// limites min/max do `buffer_gate`). `0` = ainda nao reportado (ex.: um
/// load_at() disparado antes do primeiro report, ou plataforma nao-Android
/// nos testes) — `paused_byte_ceiling_for_device` trata isso caindo no valor
/// fixo pre-existente.
pub static DEVICE_TOTAL_MEMORY_BYTES: AtomicU64 = AtomicU64::new(0);

const LATE_FRAME_RENDER_SKIP_SEC: f64 = 0.1;

/// Quando o proximo pacote de video ja nasce mais atrasado que isto em
/// relacao ao master_clock, pacotes nao-chave sao descartados sem decodificar
/// ate a proxima keyframe (ver video_thread). Sem isso, um atraso persistente
/// (decode mais lento que o exigido pela velocidade pedida, ex: 2x em 8K60,
/// onde o hardware ja decodifica perto do limite em 1x) nunca se recupera —
/// LATE_FRAME_RENDER_SKIP_SEC so descarta no render, sem reduzir o backlog,
/// entao o atraso so cresce e trava no ultimo frame renderizado pra sempre.
const CATCH_UP_SKIP_THRESHOLD_SEC: f64 = 0.5;

/// Quantas vezes seguidas a thread de demux tenta retomar perto da ultima
/// posicao de video conhecida (em vez de reiniciar do byte 0 do arquivo)
/// apos um erro de leitura, antes de cair pro ultimo recurso (seek pro
/// inicio). Achado desta sessao (analise de sessoes de telemetria com
/// stalls de dezenas de segundos via SFTP, docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md):
/// o codigo anterior reiniciava do ZERO em QUALQUER erro de leitura —
/// inclusive um soluco transitorio de rede plenamente normal (confirmado
/// via rust/protocols/src/bin/speed_test.rs: o link real oscila mas nunca
/// fica parado por segundos numa leitura sequencial simples) — e o loop da
/// thread de demux nao tinha backoff nenhum, entao uma rede ruim persistente
/// virava "reinicia do zero -> falha nas mesmas condicoes -> reinicia do
/// zero de novo" repetidamente, cada volta custando ate ~20s so no retry
/// interno do cliente SFTP (ver SFTP_READ_TIMEOUT/SFTP_CONNECT_TIMEOUT em
/// rust/protocols/src/sftp/mod.rs). Alem do freeze, isso fazia o video
/// voltar pro comeco de forma invisivel na telemetria (o seek(0,..) direto
/// no AVFormatContext nao passa pelo canal DemuxCommand::SeekTo, entao nem
/// seek_latency_ms nem a epoch da sessao refletiam o que aconteceu).
const READ_ERROR_MAX_RESUME_ATTEMPTS: u32 = 3;

/// Base e teto do backoff com jitter entre tentativas de retomada apos erro
/// de leitura (ver `media_logic::retry_backoff::backoff_with_jitter`). O
/// cliente de rede (SFTP/SMB/etc.) ja tem seu proprio retry/reconnect
/// interno com timeouts de varios segundos (ver comentario acima) — este
/// backoff extra e so uma protecao barata contra um erro que por algum
/// outro motivo volte instantaneamente (ex.: arquivo local corrompido), pra
/// nao girar a thread de demux num loop apertado sem ceder CPU. Substituiu
/// o antigo delay fixo de 400ms: sem jitter, sessoes diferentes se
/// recuperando do mesmo problema de rede (ex.: Wi-Fi caiu pra varios
/// clientes juntos) tendem a re-tentar em uníssono.
const READ_ERROR_BACKOFF_BASE: Duration = Duration::from_millis(400);
const READ_ERROR_BACKOFF_CAP: Duration = Duration::from_secs(5);

/// Alvos do buffer "estilo YouTube" (ver
/// `media_logic::buffer_gate::BufferTargets`) — valores aprovados para o
/// rollout inicial; ajustar depois com telemetria real de device (o CSV ja
/// expõe `video_q_depth`/`net_mbs`, ver `docs/DEBUGGING.md`).
const BUFFER_TARGETS: media_logic::buffer_gate::BufferTargets = media_logic::buffer_gate::BufferTargets {
    playing_local_sec: 2.0,
    playing_network_sec: 8.0,
    paused_target_sec: 25.0,
    resume_hysteresis: 0.8,
};

// Teto de bytes do buffer profundo pausado: nao e mais uma constante fixa
// aqui — ver `media_logic::buffer_gate::paused_byte_ceiling_for_device`,
// chamada no ponto de uso (dentro de `load_at`) com `DEVICE_TOTAL_MEMORY_BYTES`,
// pra escalar pela RAM real do aparelho em vez de um numero cravado pro
// Quest 3. So se aplica pausado (tocando, os alvos em segundos do
// BUFFER_TARGETS acima já mantêm o consumo de memória pequeno o bastante
// pra não precisar de teto separado): em conteúdo 8K/360° (100-150Mbps), os
// 25s de `paused_target_sec` cheios passariam de 300-450MB sem este teto.

/// Envia `item` no canal com timeout, checando `is_running` entre
/// tentativas — evita bloquear para sempre se o consumidor parou de
/// drenar o canal (ex: durante shutdown) sem ter sido explicitamente
/// desconectado ainda. Retorna `false` se abortou (is_running virou
/// false, ou o receiver foi derrubado).
fn try_send_until_stopped<T>(
    sender: &crossbeam_channel::Sender<T>,
    mut item: T,
    is_running: &Mutex<bool>,
) -> bool {
    loop {
        match sender.send_timeout(item, std::time::Duration::from_millis(100)) {
            Ok(()) => return true,
            Err(crossbeam_channel::SendTimeoutError::Timeout(returned)) => {
                item = returned;
                if !*is_running.lock().unwrap() {
                    return false;
                }
            }
            Err(crossbeam_channel::SendTimeoutError::Disconnected(_)) => return false,
        }
    }
}

/// Fonte de aleatoriedade barata para o jitter do backoff (ver
/// `media_logic::retry_backoff::backoff_with_jitter`) — não precisa
/// qualidade criptográfica, só evitar que sessões diferentes se recuperando
/// do mesmo problema de rede retentem exatamente no mesmo instante. Evita
/// puxar a crate `rand`, que não existe em nenhum `Cargo.toml` do workspace
/// hoje.
fn cheap_rand_unit() -> f64 {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.subsec_nanos())
        .unwrap_or(0);
    (nanos % 1_000_000_000) as f64 / 1_000_000_000.0
}

/// Subtrai `amount` de `counter` sem estourar por baixo (satura em 0) —
/// usado pelos contadores de bytes enfileirados (`video_bytes_queued`/
/// `audio_bytes_queued`, ver buffer_gate em `load_at`). Um `fetch_sub` cru
/// faria wraparound silencioso pra perto de `u64::MAX` se o consumidor
/// descontasse mais do que o produtor somou (ex.: corrida entre reset de
/// sessão e uma última leitura em voo) — um teto de bytes lido como
/// "quase infinito" travaria o gate de buffer pra sempre.
fn saturating_sub_u64(counter: &AtomicU64, amount: u64) {
    let mut current = counter.load(Ordering::Relaxed);
    loop {
        let new = current.saturating_sub(amount);
        match counter.compare_exchange_weak(current, new, Ordering::Relaxed, Ordering::Relaxed) {
            Ok(_) => break,
            Err(actual) => current = actual,
        }
    }
}

enum DemuxCommand {
    SeekTo(f64),
}

struct TaggedPacket {
    epoch: u64,
    packet: ffmpeg_next::Packet,
}

/// Estado e threads de uma unica "geracao" de playback (um load_at()).
/// Cada load_at() cria uma sessao nova com suas proprias flags
/// is_running/is_playing, em vez de reaproveitar as flags da sessao
/// anterior. Isso evita o bug em que stop() seguido rapidamente de um
/// novo load_at() (como em seek()) reativa a mesma flag antes das
/// threads antigas notarem que deveriam parar, fazendo com que threads
/// de gerações diferentes fiquem escrevendo ao mesmo tempo na mesma
/// textura/saida de audio compartilhadas.
struct PlaybackSession {
    // Flags is_playing/is_running independentes desta geracao — ver
    // `media_logic::session::Generation` para o contrato testado
    // (rust/media-logic/src/session.rs) que este struct agora usa em vez de
    // reimplementar a mesma bookkeeping na mao.
    generation: media_logic::session::Generation,
    demux_thread: Option<thread::JoinHandle<()>>,
    video_thread: Option<thread::JoinHandle<()>>,
    audio_thread: Option<thread::JoinHandle<()>>,
    command_tx: crossbeam_channel::Sender<DemuxCommand>,
}

impl PlaybackSession {
    fn is_playing(&self) -> bool {
        self.generation.is_playing()
    }

    fn set_playing(&self, playing: bool) {
        self.generation.set_playing(playing);
    }

    /// Sinaliza todas as threads da sessao para pararem e espera
    /// (join) elas de fato terminarem antes de retornar. Isso
    /// substitui o antigo `sleep(150ms)` "no chute" por uma garantia
    /// real de que nenhuma thread da geracao antiga segue viva.
    fn stop_and_join(mut self) {
        let handles: Vec<_> = [
            self.demux_thread.take(),
            self.video_thread.take(),
            self.audio_thread.take(),
        ]
        .into_iter()
        .flatten()
        .collect();

        // Generation::stop_and_join sinaliza is_running=false, is_playing=true
        // (acorda threads paradas no sleep(50ms) do ramo "pausado") e so
        // entao faz join() de cada handle.
        self.generation.stop_and_join(handles);
    }
}

pub struct PlaybackController {
    texture_output: Arc<Mutex<TextureOutput>>,
    audio_output: Arc<Mutex<Option<AudioOutput>>>,
    sync_manager: Arc<SyncManager>,
    session: Option<PlaybackSession>,
    current_path: Option<String>,
    duration: f64,
    // Persistido entre load_at() (o AudioOutput e recriado a cada troca de
    // video, mas o volume escolhido pelo usuario deve sobreviver a isso).
    volume: f32,
    // f32 (bits) compartilhado com o SyncManager e com a thread de audio
    // da sessao atual, para poder mudar a velocidade em tempo real sem
    // precisar recriar a sessao inteira.
    speed_bits: Arc<AtomicU32>,
    // Posicao ordinal da trilha de audio desejada (0 = primeira). Aplicada
    // no proximo load_at() — trocar de trilha ainda exige um reload, como
    // seek() ja faz.
    desired_audio_track: usize,
    audio_track_count: usize,
    detected_screen_mode: u32,
    // T-HDR: transfer characteristic (EOTF) do stream de video atual,
    // detectada uma vez em load_at() a partir do que o FFmpeg reporta (ver
    // media_logic::color) — exposta pro C++ (bridge) escolher o pipeline
    // Vulkan certo (BT.709/SDR vs. BT.2020+tonemap pra PQ/HLG). SDR e o
    // default seguro antes do primeiro load_at() ou se o container nao
    // informa nada (mesma suposicao implicita que o pipeline sempre teve).
    is_hdr: bool,
    auto_paused: bool,
    // Instrumentacao (docs/DEBUGGING.md), capturada em load_at() antes de
    // mover o Demuxer/HwDecoder pra dentro das threads de sessao — ver
    // getters no fim do impl. `None`/valores zerados antes do primeiro load.
    network_stats: Option<Arc<protocols::prefetch::PrefetchStats>>,
    video_queue: Option<crossbeam_channel::Sender<TaggedPacket>>,
    // F4 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): audio_tx sempre existiu (ver
    // load_at), mas so video_queue guardava o clone pra observabilidade — a fila de audio
    // era invisivel de fora, sem jeito de saber se o gargalo era decode/consumo de audio.
    audio_queue: Option<crossbeam_channel::Sender<TaggedPacket>>,
    frames_output: Option<Arc<std::sync::atomic::AtomicU64>>,
    frames_dropped: Option<Arc<std::sync::atomic::AtomicU64>>,
    // F4: decode_packet() descartava o Result inteiro (`let _ = ...`) — sem isto, um erro de
    // decode persistente era indistinguivel de "sem erro nenhum" na telemetria. Mesmo padrao
    // de frames_output/frames_dropped acima: vem de HwDecoder::metrics(), reatribuido a cada
    // load_at() (contador por sessao, nao cumulativo entre arquivos).
    decode_errors: Option<Arc<std::sync::atomic::AtomicU64>>,
    // F4: pacote corrompido/invalido descartado silenciosamente pelo demuxer (ver demuxer.rs).
    // Mesmo padrao de network_stats acima: vem de Demuxer::corrupt_packets, por sessao.
    demux_corrupt_packets: Option<Arc<std::sync::atomic::AtomicU64>>,
    // Conexao de rede reaproveitavel entre seeks no mesmo path (so SFTP por
    // enquanto) — ver `crate::demuxer::ConnectionCache`.
    connection_cache: crate::demuxer::ConnectionCache,
    seek_started_at: Arc<Mutex<Option<Instant>>>,
    seek_latency_ms: Arc<AtomicU32>,
    // F4 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md): fases do load_at() — antes so
    // logadas (log_info!), agora tambem consultaveis sem grep no logcat. Mesmo padrao de
    // seek_latency_ms acima: sobrevivem a troca de sessao, sobrescritas a cada load_at().
    load_phase_demux_open_ms: Arc<AtomicU32>,
    load_phase_decoder_ready_ms: Arc<AtomicU32>,
    load_phase_audio_ready_ms: Arc<AtomicU32>,
    av_drift_ms: Arc<AtomicI32>,
    // "Buffer estilo YouTube" (ver rust/media-logic/src/buffer_gate.rs): DTS
    // (bits de f64, convertido em segundos) do ultimo pacote de video que a
    // thread de demux enfileirou com sucesso, publicado a cada pacote — DTS
    // e nao PTS de proposito, porque pacotes saem do demuxer em ORDEM DE
    // DECODE e com B-frames o PTS nao e monotonico nessa ordem (ver
    // comentario no ponto de escrita, dentro de load_at). Usado tanto pelo
    // proprio gate (calcular buffered_sec) quanto pelo getter exposto na
    // bridge (get_buffered_ahead_sec). Resetado em load_at()/seek()/EOF/
    // erro pra posicao de destino, senao um valor de sessao/epoca anterior
    // faria o gate achar (por um instante) que ja ha buffer de sobra e
    // travar a leitura logo apos abrir/buscar/perder-e-retomar.
    last_enqueued_video_dts_bits: Arc<AtomicU64>,
    // Bytes fisicamente enfileirados (pacotes comprimidos, pre-decode) nos
    // canais video_tx/audio_tx agora — teto independente do alvo em
    // segundos do buffer_gate (ver paused_byte_ceiling_for_device, escalado
    // por DEVICE_TOTAL_MEMORY_BYTES), pra nao estourar memoria em conteudo
    // 8K/360 mesmo bufferizando bem a frente pausado.
    video_bytes_queued: Arc<AtomicU64>,
    audio_bytes_queued: Arc<AtomicU64>,
    // T-decode-present-split: quantos frames o MediaCodec ja decodificou
    // mas a video_thread ainda nao liberou/mostrou (fila `pending` local
    // ao loop, ver comentario la) — publicado a cada volta do loop, so
    // pra debug (docs/DEBUGGING.md). Pendurando perto do maximo do pool de
    // output buffers do hardware de forma sustentada = apresentacao nao
    // esta acompanhando o decode.
    video_present_pending: Arc<AtomicU32>,
    // Legendas (SRT / WebVTT / ASS / PGS — Fase 0.2/0.3)
    subtitle_entries: Option<Vec<media_logic::subtitle::SubtitleEntry>>,
    ass_subtitle: Option<media_logic::subtitle_ass::AssSubtitle>,
    pgs_subtitles: Option<Vec<media_logic::subtitle_pgs::PgsSubtitle>>,
    selected_subtitle_track: i32,
    subtitle_offset_ms: i64,
    available_subtitle_tracks: Vec<crate::subtitle_loader::SubtitleTrackInfo>,
    // T7.6 — idioma do sistema (ex.: "pt-BR"), usado para auto-selecionar uma
    // faixa embutida quando o usuário não escolheu nenhuma.
    preferred_subtitle_language: Option<String>,
}

impl PlaybackController {
    pub fn new() -> Self {
        let speed_bits = Arc::new(AtomicU32::new(1.0f32.to_bits()));
        Self {
            texture_output: Arc::new(Mutex::new(TextureOutput::new())),
            audio_output: Arc::new(Mutex::new(None)),
            sync_manager: Arc::new(SyncManager::new(speed_bits.clone(), 0.0)),
            session: None,
            current_path: None,
            duration: 0.0,
            volume: 1.0,
            speed_bits,
            desired_audio_track: 0,
            audio_track_count: 0,
            detected_screen_mode: 0,
            is_hdr: false,
            auto_paused: false,
            network_stats: None,
            video_queue: None,
            audio_queue: None,
            frames_output: None,
            frames_dropped: None,
            decode_errors: None,
            demux_corrupt_packets: None,
            connection_cache: crate::demuxer::ConnectionCache::default(),
            seek_started_at: Arc::new(Mutex::new(None)),
            seek_latency_ms: Arc::new(AtomicU32::new(0)),
            load_phase_demux_open_ms: Arc::new(AtomicU32::new(0)),
            load_phase_decoder_ready_ms: Arc::new(AtomicU32::new(0)),
            load_phase_audio_ready_ms: Arc::new(AtomicU32::new(0)),
            av_drift_ms: Arc::new(AtomicI32::new(0)),
            last_enqueued_video_dts_bits: Arc::new(AtomicU64::new(0.0f64.to_bits())),
            video_bytes_queued: Arc::new(AtomicU64::new(0)),
            audio_bytes_queued: Arc::new(AtomicU64::new(0)),
            video_present_pending: Arc::new(AtomicU32::new(0)),
            subtitle_entries: None,
            ass_subtitle: None,
            pgs_subtitles: None,
            selected_subtitle_track: -1,
            subtitle_offset_ms: 0,
            available_subtitle_tracks: Vec::new(),
            preferred_subtitle_language: None,
        }
    }

    /// Erro retornado (se houver) precisa ser reportado pelo chamador via
    /// `set_last_playback_error` (ver `bridge::seek_video_playback`) — sem
    /// isso, uma falha aqui era inteiramente silenciosa: antes do frame
    /// congelado (T-seek-ux) isso pelo menos aparecia como tela preta
    /// obviamente quebrada; agora fica esperando atras do ultimo frame,
    /// parecendo "travado" em vez de "erro", se ninguem repassar o erro.
    pub fn seek(&mut self, position_sec: f64) -> Result<(), String> {
        let Some(path) = self.current_path.clone() else {
            return Ok(());
        };

        *self.seek_started_at.lock().unwrap() = Some(Instant::now());

        if let Some(session) = &self.session {
            if session.command_tx.send(DemuxCommand::SeekTo(position_sec)).is_ok() {
                return Ok(());
            }
        }

        let was_playing = self.is_playing();
        self.stop();
        self.load_at(&path, position_sec).map_err(|e| e.to_string())?;
        if !was_playing {
            self.pause();
        }
        Ok(())
    }

    pub fn load(&mut self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        self.load_at(path, 0.0)
    }

    pub fn load_at(&mut self, path: &str, start_time: f64) -> Result<(), Box<dyn std::error::Error>> {
        let load_started_at = Instant::now();
        {
            let mut marker = self.seek_started_at.lock().unwrap();
            if marker.is_none() {
                *marker = Some(load_started_at);
            }
        }

        // Garante que a geracao anterior (se houver) esta totalmente
        // parada antes de tocar em qualquer estado compartilhado.
        if let Some(session) = self.session.take() {
            session.stop_and_join();
        }

        self.current_path = Some(path.to_string());
        let mut demuxer = Demuxer::open(path, Some(&mut self.connection_cache)).map_err(|e| e.to_string())?;
        crate::log_info!("load_at: demux_open={}ms", load_started_at.elapsed().as_millis());
        self.load_phase_demux_open_ms.store(load_started_at.elapsed().as_millis().min(u128::from(u32::MAX)) as u32, Ordering::Relaxed);
        self.network_stats = demuxer.network_stats.clone();
        self.demux_corrupt_packets = Some(demuxer.corrupt_packets.clone());
        demuxer.select_audio_track(self.desired_audio_track);
        self.audio_track_count = demuxer.audio_streams.len();
        self.duration = demuxer.input_context.duration() as f64 / 1_000_000.0;

        let video_idx = demuxer.video_stream_index.ok_or("No video stream")?;
        let mut width = 1920;
        let mut height = 1080;
        let mut codec_id = ffmpeg_next::codec::Id::None;
        let mut video_fps = 0.0f32;
        let mut transfer_function = media_logic::color::TransferFunction::Sdr;
        if let Some(stream) = demuxer.input_context.stream(video_idx) {
            codec_id = stream.parameters().id();
            let avg_fps = stream.avg_frame_rate();
            if avg_fps.denominator() > 0 {
                video_fps = avg_fps.numerator() as f32 / avg_fps.denominator() as f32;
            }
            if let Ok(decoder) = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters()) {
                if let Ok(video_decoder_ctx) = decoder.decoder().video() {
                    width = video_decoder_ctx.width() as u32;
                    height = video_decoder_ctx.height() as u32;
                    // T-HDR: le a transfer characteristic (EOTF) que o
                    // container/codec declarou pra este stream — ver
                    // media_logic::color pro porque do numero cru em vez
                    // do tipo do ffmpeg-next (media-logic nao depende dele).
                    let trc: ffmpeg_next::ffi::AVColorTransferCharacteristic =
                        video_decoder_ctx.color_transfer_characteristic().into();
                    transfer_function = media_logic::color::from_avcol_trc(trc as i32);
                }
            }
        }
        self.is_hdr = transfer_function.is_hdr();

        let (fmt3d, _) = crate::format3d_detect::detect(&demuxer, path, width, height);
        self.detected_screen_mode = fmt3d.to_screen_mode_index();

        // Descoberta de legendas sidecar (.srt/.vtt) e trilhas embutidas (T9.1, T9.6)
        self.available_subtitle_tracks = crate::subtitle_loader::probe_sidecar_subtitles(path);
        for (i, &stream_idx) in demuxer.subtitle_streams.iter().enumerate() {
            let (lang, title) = if let Some(stream) = demuxer.input_context.stream(stream_idx) {
                let meta = stream.metadata();
                (
                    meta.get("language").unwrap_or_default().to_string(),
                    meta.get("title").unwrap_or_default().to_string(),
                )
            } else {
                (String::new(), String::new())
            };
            self.available_subtitle_tracks.push(crate::subtitle_loader::SubtitleTrackInfo {
                title: if title.is_empty() { format!("Track {}", i + 1) } else { title },
                language: lang,
                is_external: false,
                source_path: None,
                stream_index: Some(stream_idx),
            });
        }
        if self.selected_subtitle_track >= 0
            && (self.selected_subtitle_track as usize) < self.available_subtitle_tracks.len()
        {
            self.load_subtitle_track(self.selected_subtitle_track as usize);
        } else if self.selected_subtitle_track < 0 && !self.available_subtitle_tracks.is_empty() {
            // T7.6 — nenhuma faixa escolhida pelo usuário: auto-selecionar pela
            // correspondência com o idioma do sistema, se houver.
            if let Some(lang) = &self.preferred_subtitle_language {
                let langs: Vec<String> = self
                    .available_subtitle_tracks
                    .iter()
                    .map(|t| t.language.clone())
                    .collect();
                if let Some(idx) = media_logic::subtitle::match_subtitle_language(&langs, lang.as_str()) {
                    self.selected_subtitle_track = idx as i32;
                    self.load_subtitle_track(idx);
                }
            }
        }

        // O Quest 3 (MediaCodec) exige o mime correto do decoder de hardware;
        // usar "video/avc" para um stream HEVC falha ou decodifica lixo.
        // VP9/AV1 nao usam framing NAL (avcC/hvcC) — os pacotes ja vem no
        // formato bruto que o MediaCodec espera, converter pra Annex-B
        // corromperia os dados.
        let (mime, video_is_nal_based) = crate::decoder::mime_for_codec_id(codec_id)?;

        let mut tex = self.texture_output.lock().unwrap();
        // Allocate with exact size of the video
        tex.allocate(width, height).map_err(|e| e.to_string())?;
        let window = tex.get_window();
        drop(tex);

        if start_time > 0.0 {
            let target_ts = (start_time * 1000000.0) as i64;
            let _ = demuxer.input_context.seek(target_ts, ..);
        }

        let mut format = MediaFormat::new();
        format.set_str("mime", mime);
        format.set_i32("width", width as i32);
        format.set_i32("height", height as i32);

        // F3 (T1.1): max-input-size dimensionado para 8K/4K para evitar estouro de buffer de entrada
        let max_dim = width.max(height);
        let max_input_size = if max_dim >= 7000 {
            4 * 1024 * 1024 // 4 MB para 8K
        } else if max_dim >= 3800 {
            2 * 1024 * 1024 // 2 MB para 4K
        } else {
            1024 * 1024 // 1 MB para 1080p
        };
        format.set_i32("max-input-size", max_input_size);
        // Prioridade de decodificação realtime (0 = realtime / baixa latência)
        format.set_i32("priority", 0);

        // Torna explicito pro MediaCodec o espaco de cor do conteudo SDR
        // que este app foi feito pra tocar (HD/4K/8K reais sao BT.709, nao
        // BT.601 de SD) — antes disso a plataforma tinha que adivinhar, e o
        // caminho Vulkan (CreateYcbcrAndVideoPipeline) tinha essa mesma
        // suposicao errada hardcoded como BT.601 (ver commit que trocou pra
        // BT.709 la). Valores de android.media.MediaFormat:
        // COLOR_STANDARD_BT709=1, COLOR_STANDARD_BT2020=6,
        // COLOR_RANGE_LIMITED=2, COLOR_TRANSFER_SDR_VIDEO=3,
        // COLOR_TRANSFER_ST2084=6, COLOR_TRANSFER_HLG=7.
        format.set_i32("color-range", 2);
        match transfer_function {
            media_logic::color::TransferFunction::Sdr => {
                format.set_i32("color-standard", 1);
                format.set_i32("color-transfer", 3);
            }
            media_logic::color::TransferFunction::Pq => {
                format.set_i32("color-standard", 6);
                format.set_i32("color-transfer", 6);
            }
            media_logic::color::TransferFunction::Hlg => {
                format.set_i32("color-standard", 6);
                format.set_i32("color-transfer", 7);
            }
        }

        if max_dim >= 7000 && video_fps > 30.0 {
            crate::log_warn!(
                "Aviso de capacidade 8K: Conteúdo {}x{} a {:.1} fps detectado. Hardware móvel XR2 opera no limite de throughput de decode.",
                width, height, video_fps
            );
        }

        let video_decoder = HwDecoder::new_configured_and_started(mime, &format, window.as_ref()).map_err(|e| e.to_string())?;
        let (frames_output, frames_dropped, decode_errors) = video_decoder.metrics();
        self.frames_output = Some(frames_output);
        self.frames_dropped = Some(frames_dropped);
        self.decode_errors = Some(decode_errors);
        crate::log_info!("load_at: decoder_ready={}ms", load_started_at.elapsed().as_millis());
        self.load_phase_decoder_ready_ms.store(load_started_at.elapsed().as_millis().min(u128::from(u32::MAX)) as u32, Ordering::Relaxed);

        let mut sps_pps = None;
        if let Some(ed) = demuxer.get_video_extradata() {
            sps_pps = match codec_id {
                ffmpeg_next::codec::Id::HEVC => crate::hevc::extract_vps_sps_pps(&ed),
                ffmpeg_next::codec::Id::H264 => crate::h264::extract_sps_pps(&ed),
                ffmpeg_next::codec::Id::AV1 => crate::av1::extract_config_obus(&ed),
                // VP9 nao tem SPS/PPS/config OBU em banda: cada keyframe ja
                // carrega dimensao/perfil no proprio bitstream.
                _ => None,
            };
        }

        let mut audio_decoder = demuxer
            .audio_stream_index
            .and_then(|idx| AudioDecoder::new(&demuxer.input_context, idx).ok());

        let mut audio_out = None;
        if audio_decoder.is_some() {
            if let Ok(mut out) = AudioOutput::new() {
                let _ = out.start();
                audio_out = Some(out);
            }
        }
        crate::log_info!("load_at: audio_ready={}ms", load_started_at.elapsed().as_millis());
        self.load_phase_audio_ready_ms.store(load_started_at.elapsed().as_millis().min(u128::from(u32::MAX)) as u32, Ordering::Relaxed);

        if let Ok(mut out_guard) = self.audio_output.lock() {
            // Reaplica o volume persistido (o AudioOutput e recriado a cada load).
            if let Some(out) = &audio_out {
                out.set_volume(self.volume);
            }
            *out_guard = audio_out;
        }

        let mut video_time_base = 0.0;
        if let Some(stream) = demuxer.input_context.stream(video_idx) {
            let tb = stream.time_base();
            video_time_base = f64::from(tb);
        }

        let mut audio_time_base = 0.0;
        if let Some(a_idx) = demuxer.audio_stream_index {
            if let Some(stream) = demuxer.input_context.stream(a_idx) {
                let tb = stream.time_base();
                audio_time_base = f64::from(tb);
            }
        }

        let texture_output_clone = self.texture_output.clone();
        let audio_output_clone = self.audio_output.clone();

        // Sempre parte de um SyncManager limpo por geracao: mais simples
        // e mais previsivel do que tentar "resetar" o anterior.
        // start() precisa de &mut, entao e chamado antes de mover o
        // SyncManager para dentro do Arc (compartilhado com as threads).
        let mut sync_manager = SyncManager::new(self.speed_bits.clone(), start_time);
        sync_manager.start();
        let sync_manager = Arc::new(sync_manager);
        self.sync_manager = sync_manager.clone();
        if start_time > 0.0 {
            self.sync_manager.update_master_clock(start_time);
        }
        // Reseta o estado do buffer_gate para o novo ponto de partida —
        // sem isso, um PTS/contador de bytes deixado pela sessao anterior
        // faria o gate (calculado a partir de last_enqueued_video_dts_bits)
        // achar que ja ha buffer de sobra e travar a demux logo na abertura.
        self.last_enqueued_video_dts_bits.store(start_time.to_bits(), Ordering::Relaxed);
        self.video_bytes_queued.store(0, Ordering::Relaxed);
        self.audio_bytes_queued.store(0, Ordering::Relaxed);

        // Backstop de seguranca, nao mais o mecanismo principal de controle de
        // buffer (isso agora e o buffer_gate por duracao/bytes, ver abaixo) —
        // so evita que um bug no gate consuma memoria sem limite. Dimensionado
        // generosamente (~5s a 60fps) pra nao ser o fator limitante antes do
        // teto de bytes (paused_byte_ceiling_for_device) entrar em acao.
        let (video_tx, video_rx) = crossbeam_channel::bounded::<TaggedPacket>(300);
        let (audio_tx, audio_rx) = crossbeam_channel::bounded::<TaggedPacket>(300);
        // Clone do Sender so pra poder consultar profundidade (`len()`) de
        // fora da thread de demux — nao envia nada por este handle, ver
        // get_video_queue_depth().
        self.video_queue = Some(video_tx.clone());
        // F4: mesmo motivo do video_queue acima — a fila de audio era invisivel de fora
        // antes disto, ver get_audio_queue_depth().
        self.audio_queue = Some(audio_tx.clone());

        let (command_tx, command_rx) = crossbeam_channel::unbounded::<DemuxCommand>();

        // Epoca da sessao — so a thread de demux escreve (dentro do tratamento
        // de DemuxCommand::SeekTo); video/audio usam pra descartar pacotes de
        // uma epoca ja superada e pra saber quando rearmar o pre-roll (ver TaggedPacket).
        let epoch: Arc<AtomicU64> = Arc::new(AtomicU64::new(0));
        let epoch_d = epoch.clone();
        let epoch_v = epoch.clone();
        let epoch_a = epoch.clone();

        let seek_started_at_v = self.seek_started_at.clone();
        let seek_latency_v = self.seek_latency_ms.clone();
        let av_drift_v = self.av_drift_ms.clone();
        let last_enqueued_video_dts_bits_d = self.last_enqueued_video_dts_bits.clone();
        let video_bytes_queued_d = self.video_bytes_queued.clone();
        let video_bytes_queued_v = self.video_bytes_queued.clone();
        let audio_bytes_queued_d = self.audio_bytes_queued.clone();
        let audio_bytes_queued_a = self.audio_bytes_queued.clone();

        // Flags desta geracao: nao sao compartilhadas com nenhuma
        // sessao anterior ou futura (ver media_logic::session::Generation e
        // seus testes em rust/media-logic/src/session.rs).
        let generation = media_logic::session::Generation::new();
        let is_playing = generation.is_playing_handle();
        let is_running = generation.is_running_handle();

        let is_playing_v = is_playing.clone();
        let is_playing_a = is_playing.clone();
        let is_playing_d = is_playing.clone();

        let is_running_v = is_running.clone();
        let is_running_a = is_running.clone();
        let is_running_d = is_running.clone();

        // Thread 1: Demuxer
        let sync_d = sync_manager.clone();
        let demux_thread = thread::spawn(move || {
            DEMUX_THREAD_TID.store(unsafe { libc::gettid() }, Ordering::Relaxed);
            // Ultima posicao de video (em segundos) que efetivamente saiu do
            // demuxer com sucesso — alvo da retomada apos erro de leitura
            // (ver READ_ERROR_MAX_RESUME_ATTEMPTS). Comeca em start_time
            // porque e de la que esta sessao de fato partiu.
            let mut last_good_pos_sec: f64 = start_time.max(0.0);
            let mut consecutive_read_errors: u32 = 0;
            // Fonte de rede (PrefetchReader por tras) vs. local/http:// puro —
            // decide qual alvo do BUFFER_TARGETS o gate usa (ver
            // media_logic::buffer_gate::BufferTargets).
            let is_network_source = demuxer.network_stats.is_some();
            let mut buffer_gate_state = media_logic::buffer_gate::GateState::Read;
            loop {
                if !*is_running_d.lock().unwrap() { break; }

                if let Ok(DemuxCommand::SeekTo(target_sec)) = command_rx.try_recv() {
                    let target_ts = (target_sec * 1_000_000.0) as i64;
                    let _ = demuxer.input_context.seek(target_ts, ..);
                    epoch_d.fetch_add(1, Ordering::SeqCst);
                    // Placeholder ate a thread de video/audio "pousar" e corrigir o
                    // clock pra posicao real (ver PrerollState::take_landing) — so
                    // evita a barra de progresso piscar um valor velho nesse meio-tempo.
                    sync_d.update_master_clock(target_sec);
                    // Seek deliberado do usuario: a posicao de retomada apos um
                    // eventual erro de leitura passa a ser aqui, nao onde a sessao
                    // estava antes, e o orcamento de tentativas "perto da posicao
                    // atual" recomeca do zero.
                    last_good_pos_sec = target_sec.max(0.0);
                    consecutive_read_errors = 0;
                    // Sem isto, buffered_seconds() compararia o PTS bufferizado
                    // ANTES do seek com a posicao nova — apos um seek pra tras,
                    // pareceria que ja ha buffer de sobra e o gate travaria a
                    // leitura bem na hora em que precisa de pacotes novos.
                    last_enqueued_video_dts_bits_d.store(target_sec.to_bits(), Ordering::Relaxed);
                    buffer_gate_state = media_logic::buffer_gate::GateState::Read;
                }

                let buffered_sec = media_logic::buffer_gate::buffered_seconds(
                    f64::from_bits(last_enqueued_video_dts_bits_d.load(Ordering::Relaxed)),
                    sync_d.get_master_clock(),
                );
                let bytes_queued = video_bytes_queued_d.load(Ordering::Relaxed)
                    + audio_bytes_queued_d.load(Ordering::Relaxed);
                let is_paused = !*is_playing_d.lock().unwrap();
                let byte_ceiling = if is_paused {
                    media_logic::buffer_gate::paused_byte_ceiling_for_device(
                        DEVICE_TOTAL_MEMORY_BYTES.load(Ordering::Relaxed),
                    )
                } else {
                    u64::MAX
                };
                buffer_gate_state = media_logic::buffer_gate::next_gate_state(
                    buffer_gate_state,
                    buffered_sec,
                    bytes_queued,
                    &BUFFER_TARGETS,
                    byte_ceiling,
                    is_paused,
                    is_network_source,
                );
                if buffer_gate_state == media_logic::buffer_gate::GateState::Wait {
                    // Nao consome CPU/rede à toa: o PrefetchReader por tras do
                    // demuxer.read_packet() so avanca quando este chega a ser
                    // chamado, entao esperar aqui tambem pausa o read-ahead de
                    // rede de fato, nao so o empacotamento.
                    thread::sleep(std::time::Duration::from_millis(50));
                    continue;
                }

                let current_epoch = epoch_d.load(Ordering::SeqCst);
                match demuxer.read_packet() {
                    crate::demuxer::ReadPacketOutcome::Packet(idx, packet) => {
                        consecutive_read_errors = 0;
                        let packet_len = packet.data().map(|d| d.len()).unwrap_or(0) as u64;
                        if idx == video_idx {
                            if let Some(pts) = packet.pts() {
                                last_good_pos_sec = (pts as f64 * video_time_base).max(0.0);
                            }
                            // DTS, nao PTS: pacotes saem do demuxer em ORDEM DE
                            // DECODE, e com B-frames o PTS de pacotes sucessivos
                            // NAO e monotonico nessa ordem (ex.: I,P,B,B em
                            // ordem de decode tem PTS fora de ordem — o P vem
                            // antes dos B na fila mas sua PTS e posterior). Usar
                            // PTS aqui fazia buffered_seconds() oscilar
                            // erraticamente (ora parecendo que ja ha buffer de
                            // sobra, ora que nao ha nada), o gate abrir/fechar
                            // sem previsibilidade, e o playback engasgar e
                            // "pular" tentando se recuperar (ver
                            // CATCH_UP_SKIP_THRESHOLD_SEC) — bug real observado
                            // em video com B-frames apos o rollout do buffer_gate.
                            // DTS e monotonico na mesma ordem em que os pacotes
                            // de fato atravessam a fila, entao mede
                            // corretamente "quanto ja foi demuxado a frente do
                            // consumo". Cai para PTS so se o container nao
                            // populou DTS (streams sem B-frames costumam ter
                            // DTS==PTS de qualquer forma).
                            if let Some(dts) = packet.dts().or_else(|| packet.pts()) {
                                let buffer_pos_sec = (dts as f64 * video_time_base).max(0.0);
                                last_enqueued_video_dts_bits_d.store(buffer_pos_sec.to_bits(), Ordering::Relaxed);
                            }
                            video_bytes_queued_d.fetch_add(packet_len, Ordering::Relaxed);
                            if !try_send_until_stopped(&video_tx, TaggedPacket { epoch: current_epoch, packet }, &is_running_d) { break; }
                        } else if demuxer.audio_stream_index == Some(idx) {
                            audio_bytes_queued_d.fetch_add(packet_len, Ordering::Relaxed);
                            if !try_send_until_stopped(&audio_tx, TaggedPacket { epoch: current_epoch, packet }, &is_running_d) { break; }
                        }
                    }
                    crate::demuxer::ReadPacketOutcome::Eof => {
                        let _ = demuxer.input_context.seek(0, 0..1);
                        sync_d.reset();
                        last_good_pos_sec = 0.0;
                        consecutive_read_errors = 0;
                        last_enqueued_video_dts_bits_d.store(0.0f64.to_bits(), Ordering::Relaxed);
                        buffer_gate_state = media_logic::buffer_gate::GateState::Read;
                    }
                    crate::demuxer::ReadPacketOutcome::Error(e) => {
                        // Distinguido de EOF nesta sessao
                        // (docs/NETWORK-IO-PERFORMANCE.md). O AVFormatContext
                        // realmente parece entrar num estado de erro "sticky"
                        // apos uma falha de I/O — um seek (pra qualquer lugar)
                        // e o jeito testado de desempacar isso, entao mantemos
                        // o seek, mas agora tentamos retomar PERTO de onde a
                        // sessao estava (last_good_pos_sec) em vez de sempre
                        // saltar pro byte 0 — ver READ_ERROR_MAX_RESUME_ATTEMPTS
                        // sobre por que reiniciar do zero em QUALQUER soluco de
                        // rede transitorio era o comportamento anterior (achado
                        // desta sessao, confirmado com rust/protocols/src/bin/speed_test.rs).
                        consecutive_read_errors += 1;
                        if consecutive_read_errors <= READ_ERROR_MAX_RESUME_ATTEMPTS {
                            crate::log_warn!(
                                "Demuxer: erro de leitura ({e}), retomando perto de {:.1}s (tentativa {}/{})",
                                last_good_pos_sec, consecutive_read_errors, READ_ERROR_MAX_RESUME_ATTEMPTS
                            );
                            let target_ts = (last_good_pos_sec * 1_000_000.0) as i64;
                            let _ = demuxer.input_context.seek(target_ts, ..);
                            epoch_d.fetch_add(1, Ordering::SeqCst);
                            sync_d.update_master_clock(last_good_pos_sec);
                            last_enqueued_video_dts_bits_d.store(last_good_pos_sec.to_bits(), Ordering::Relaxed);
                            buffer_gate_state = media_logic::buffer_gate::GateState::Read;
                            thread::sleep(media_logic::retry_backoff::backoff_with_jitter(
                                consecutive_read_errors,
                                READ_ERROR_BACKOFF_BASE,
                                READ_ERROR_BACKOFF_CAP,
                                cheap_rand_unit(),
                            ));
                        } else {
                            crate::log_warn!(
                                "Demuxer: erro de leitura persistente apos {consecutive_read_errors} tentativas ({e}), reiniciando do inicio"
                            );
                            let _ = demuxer.input_context.seek(0, 0..1);
                            epoch_d.fetch_add(1, Ordering::SeqCst);
                            sync_d.reset();
                            last_good_pos_sec = 0.0;
                            consecutive_read_errors = 0;
                            last_enqueued_video_dts_bits_d.store(0.0f64.to_bits(), Ordering::Relaxed);
                            buffer_gate_state = media_logic::buffer_gate::GateState::Read;
                        }
                    }
                }
            }
        });

        // Thread 2: Video Decoder
        let sync_v = sync_manager.clone();
        let video_present_pending_v = self.video_present_pending.clone();
        let video_thread = thread::spawn(move || {
            VIDEO_THREAD_TID.store(unsafe { libc::gettid() }, Ordering::Relaxed);
            if let Some(sps) = sps_pps {
                let _ = video_decoder.decode_packet(&sps, 0, 2, |_| true, || {}, || true);
            }

            let mut current_epoch: u64 = 0;
            let mut preroll = media_logic::preroll::PrerollState::idle();
            preroll.begin();
            let mut catchup_packets: u32 = 0;
            // Marca troca de epoca (seek) pra detectar fila de video vazia logo em seguida (rede lenta).
            let mut last_epoch_change_at: Option<std::time::Instant> = None;
            // Frames que o MediaCodec ja decodificou mas ainda NAO foram
            // liberados/mostrados — seguram lugar no proprio pool de
            // buffers de saida do codec (nao sao copias). Existir isto e o
            // que permite continuar alimentando o proximo pacote de
            // entrada (abaixo) em vez de ficar preso num sleep unico
            // esperando a hora de mostrar o frame anterior — o sleep de
            // sincronismo (passo 2) agora so espera em fatias curtas
            // (PRESENT_WAIT_SLICE), voltando sempre pro passo de
            // alimentar entrada/drenar mais saida. Sempre tratada em
            // ordem FIFO, igual a ordem de apresentacao que o proprio
            // MediaCodec ja garante.
            let mut pending: std::collections::VecDeque<ndk::media::media_codec::OutputBuffer<'_>> =
                std::collections::VecDeque::new();
            const PRESENT_WAIT_SLICE: std::time::Duration = std::time::Duration::from_millis(10);

            // Libera (mostrando ou descartando) o frame mais antigo
            // pendente, atualizando a mesma telemetria (av_drift_ms,
            // acquire_latest_buffer) que o caminho antigo atualizava
            // inline no sync_callback.
            macro_rules! release_pending_front {
                ($render:expr) => {
                    if let Some(buf) = pending.pop_front() {
                        let pts_sec = buf.info().presentation_time_us() as f64 / 1_000_000.0;
                        let _ = video_decoder.release_output(buf, $render);
                        if $render {
                            if let Ok(mut tex) = texture_output_clone.lock() {
                                let _ = tex.acquire_latest_buffer();
                            }
                        }
                        let master_clock = sync_v.get_master_clock();
                        let inst_drift_ms = ((master_clock - pts_sec) * 1000.0).clamp(i32::MIN as f64, i32::MAX as f64);
                        let prev_drift_ms = av_drift_v.load(Ordering::Relaxed) as f64;
                        let smoothed_drift_ms = prev_drift_ms * 0.9 + inst_drift_ms * 0.1;
                        av_drift_v.store(
                            smoothed_drift_ms.clamp(i32::MIN as f64, i32::MAX as f64) as i32,
                            Ordering::Relaxed
                        );
                    }
                };
            }

            loop {
                if !*is_running_v.lock().unwrap() { break; }

                if !*is_playing_v.lock().unwrap() && !preroll.is_awaiting_landing() {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                    continue;
                }

                // 1. Drena tudo que o MediaCodec ja tiver pronto na saida,
                //    sem liberar nada ainda (try_dequeue_output nao
                //    bloqueia; para sozinho quando nao ha mais nada pronto
                //    agora — o teto de profundidade vem so do pool de
                //    buffers de saida do proprio hardware).
                while let Some(buf) = video_decoder.try_dequeue_output() {
                    pending.push_back(buf);
                }
                video_present_pending_v.store(pending.len() as u32, Ordering::Relaxed);

                // 2. Decide o que fazer com o frame mais antigo pendente,
                //    sem travar o loop nisso: so espera em fatias curtas
                //    (volta sempre pro passo 3 pra manter o decode
                //    avancando em vez de dormir o atraso inteiro de uma
                //    vez so).
                if let Some(front) = pending.front() {
                    let pts_sec = front.info().presentation_time_us() as f64 / 1_000_000.0;
                    let is_landing = preroll.is_awaiting_landing();
                    let master_clock = sync_v.get_master_clock();
                    match media_logic::frame_timing::decide_frame_action(
                        pts_sec, master_clock, is_landing, LATE_FRAME_RENDER_SKIP_SEC,
                    ) {
                        media_logic::frame_timing::FrameAction::WaitThenRender(d) => {
                            std::thread::sleep(d.min(PRESENT_WAIT_SLICE));
                        }
                        media_logic::frame_timing::FrameAction::Land => {
                            preroll.take_landing();
                            sync_v.update_master_clock(pts_sec);
                            release_pending_front!(true);
                        }
                        media_logic::frame_timing::FrameAction::RenderNow => {
                            release_pending_front!(true);
                        }
                        media_logic::frame_timing::FrameAction::Drop => {
                            release_pending_front!(false);
                        }
                    }
                }

                // 3. Alimenta o proximo pacote de entrada — timeout curto
                //    se ja ha algo pendente aguardando a hora certa, pra
                //    voltar logo ao passo 2; timeout normal (50ms) se nao
                //    ha nada pendente (nada a perder esperando mais).
                let recv_timeout = if pending.is_empty() {
                    std::time::Duration::from_millis(50)
                } else {
                    std::time::Duration::from_millis(5)
                };
                match video_rx.recv_timeout(recv_timeout) {
                Ok(tagged) => {
                    // Descontado incondicionalmente, mesmo se o pacote for de
                    // uma epoca velha e descartado logo abaixo — ele saiu
                    // fisicamente do canal de qualquer jeito, entao o gate de
                    // buffer (rodando na thread de demux) precisa saber.
                    let packet_len = tagged.packet.data().map(|d| d.len()).unwrap_or(0) as u64;
                    saturating_sub_u64(&video_bytes_queued_v, packet_len);
                    let latest_epoch = epoch_v.load(Ordering::SeqCst);
                    if tagged.epoch < latest_epoch {
                        continue;
                    }
                    if tagged.epoch != current_epoch {
                        current_epoch = tagged.epoch;
                        let _ = video_decoder.flush();
                        // Pos-flush, qualquer OutputBuffer ainda pendente
                        // aponta pra um indice que o MediaCodec ja
                        // invalidou/reclamou internamente — descartar SEM
                        // chamar release_output neles (seria usar um
                        // handle morto; flush() ja cuida de liberar esses
                        // slots do lado do codec).
                        pending.clear();
                        preroll.begin();
                        last_epoch_change_at = Some(std::time::Instant::now());
                    }
                    let packet = tagged.packet;

                    let was_active = preroll.is_active();
                    if was_active {
                        catchup_packets += 1;
                    }

                    let pts = packet.pts().unwrap_or(0);
                    let pts_sec = pts as f64 * video_time_base;
                    let lag = sync_v.get_master_clock() - pts_sec;
                    if !was_active && lag > CATCH_UP_SKIP_THRESHOLD_SEC && packet.is_key() {
                        crate::log_info!("Video catch-up: retomando decode na keyframe (lag era {lag:.2}s)");
                    }
                    if preroll.should_skip_packet(packet.is_key(), lag, CATCH_UP_SKIP_THRESHOLD_SEC) {
                        continue;
                    }

                    if let Some(data) = packet.data() {
                        let frame_data = if video_is_nal_based {
                            crate::nal::convert_avcc_to_annexb(data)
                        } else {
                            data.to_vec()
                        };
                        // MediaCodec espera presentationTimeUs expressamente em MICROSSEGUNDOS (us).
                        // Em conteineres como Matroska (MKV), video_time_base e 1/1000 (ms).
                        // Converter pts para microssegundos reais evita que drivers como Qualcomm C2
                        // interpretem o delta de ~42ms como 42us e superestimem o framerate para 23809 fps.
                        let pts_us = (pts_sec * 1_000_000.0).max(0.0) as i64;
                        let _ = video_decoder.feed_input(
                            &frame_data,
                            pts_us,
                            0,
                            // Sob pressao (entrada cheia porque estamos
                            // segurando saida pendente demais): libera o
                            // mais antigo cedo como valvula de alivio, em
                            // vez de deixar o decode travar de verdade.
                            // Substitui o antigo
                            // `release_output_frames_with_sync` chamado
                            // aqui pelo mesmo motivo.
                            || release_pending_front!(true),
                            || *is_running_v.lock().unwrap()
                        );
                        if was_active && !preroll.is_active() {
                            if let Some(start) = seek_started_at_v.lock().unwrap().take() {
                                let ms = start.elapsed().as_millis().min(u128::from(u32::MAX)) as u32;
                                seek_latency_v.store(ms, Ordering::Relaxed);
                                crate::log_info!("seek: landing={ms}ms catchup_packets={catchup_packets}");
                            }
                            catchup_packets = 0;
                        }
                    }
                }
                Err(crossbeam_channel::RecvTimeoutError::Timeout) => {
                    if let Some(t) = last_epoch_change_at {
                        if t.elapsed() < std::time::Duration::from_secs(3) {
                            crate::log_warn!(
                                "seek: fila de video vazia {}ms apos epoca mudar",
                                t.elapsed().as_millis()
                            );
                        }
                    }
                }
                Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {}
                }
            }
        });

        // Thread 3: Audio Decoder
        let sync_a = sync_manager.clone();
        let speed_bits_a = self.speed_bits.clone();

        let mut audio_sender = None;
        if let Ok(out_guard) = audio_output_clone.lock() {
            if let Some(out) = out_guard.as_ref() {
                audio_sender = Some(out.get_sender());
            }
        }

        let audio_thread = thread::spawn(move || {
            AUDIO_THREAD_TID.store(unsafe { libc::gettid() }, Ordering::Relaxed);
            let mut applied_speed = 1.0f32;
            let layout = audio_decoder
                .as_ref()
                .map(|ad| ad.channel_layout())
                .unwrap_or(media_logic::spatial_audio::AudioChannelLayout::Stereo);
            let mut spatial_processor =
                media_logic::spatial_audio::SpatialAudioProcessor::new(layout, 48000.0);
            let mut binaural_buffer = Vec::with_capacity(4096);

            // Reconstruir o resampler descarta o historico interno do filtro
            // FIR, o que gera um pequeno estalo/transiente a cada troca. O
            // slider de velocidade dispara onProgressChanged (e portanto
            // set_speed) dezenas de vezes por segundo enquanto o usuario
            // arrasta — sem essa espera minima entre reconstrucoes, isso
            // vira uma sequencia de estalos que soa como chiado/estatica.
            let mut last_speed_change = std::time::Instant::now()
                .checked_sub(std::time::Duration::from_secs(1))
                .unwrap_or_else(std::time::Instant::now);
            const MIN_SPEED_CHANGE_INTERVAL: std::time::Duration = std::time::Duration::from_millis(200);

            loop {
                if !*is_running_a.lock().unwrap() { break; }

                if !*is_playing_a.lock().unwrap() {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                    continue;
                }

                if let Ok(tagged) = audio_rx.recv_timeout(std::time::Duration::from_millis(50)) {
                    let packet_len = tagged.packet.data().map(|d| d.len()).unwrap_or(0) as u64;
                    saturating_sub_u64(&audio_bytes_queued_a, packet_len);
                    if tagged.epoch < epoch_a.load(Ordering::SeqCst) {
                        continue;
                    }
                    let packet = tagged.packet;

                    if let Some(ref mut ad) = audio_decoder {
                        let desired_speed = f32::from_bits(speed_bits_a.load(Ordering::Relaxed));
                        if (desired_speed - applied_speed).abs() > 0.01
                            && last_speed_change.elapsed() >= MIN_SPEED_CHANGE_INTERVAL
                        {
                            if ad.set_speed(desired_speed).is_ok() {
                                applied_speed = desired_speed;
                                last_speed_change = std::time::Instant::now();
                            }
                        }

                        if let Ok(samples) = ad.decode(&packet) {
                            if !samples.is_empty() {
                                let pts = packet.pts().unwrap_or(0);
                                let pts_sec = pts as f64 * audio_time_base;
                                sync_a.update_audio_pts(pts_sec);

                                let head_rot = media_logic::spatial_audio::get_global_head_orientation();
                                let spatial_mode = media_logic::spatial_audio::get_global_spatial_mode();
                                let head_tracking = media_logic::spatial_audio::get_global_head_tracking_enabled();

                                spatial_processor.set_mode(spatial_mode);
                                spatial_processor.set_head_tracking_enabled(head_tracking);
                                spatial_processor.process(&samples, head_rot, &mut binaural_buffer);

                                if let Some(sender) = &audio_sender {
                                    for &sample in &binaural_buffer {
                                        if !try_send_until_stopped(sender, sample, &is_running_a) {
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        });

        self.session = Some(PlaybackSession {
            generation,
            demux_thread: Some(demux_thread),
            video_thread: Some(video_thread),
            audio_thread: Some(audio_thread),
            command_tx,
        });

        Ok(())
    }

    /// Aplica play/pause tanto ao estado das threads de decodificacao
    /// quanto ao SyncManager e ao stream de audio (Oboe) — os tres
    /// precisam ficar em sincronia, senao o audio continua tocando
    /// sozinho enquanto o video pausa (ou vice-versa).
    fn set_playing(&mut self, playing: bool) {
        if let Some(session) = &self.session {
            session.set_playing(playing);
        }

        if playing {
            self.sync_manager.resume();
        } else {
            self.sync_manager.pause();
        }

        if let Ok(mut ao) = self.audio_output.lock() {
            if let Some(audio) = ao.as_mut() {
                if playing {
                    let _ = audio.start();
                } else {
                    let _ = audio.pause();
                }
            }
        }
    }

    pub fn play(&mut self) {
        self.set_playing(true);
    }

    /// Estado real da sessao atual (nao um espelho otimista do lado
    /// Kotlin) — usado pela UI pra reagir a QUALQUER caminho que mude o
    /// play/pause (botao na tela, botao do controle VR, auto-pause por
    /// perda de foco), nao so o clique direto no botao. Ver
    /// get_playback_is_playing em rust/bridge/src/lib.rs.
    pub fn is_playing(&self) -> bool {
        self.session.as_ref().map(|s| s.is_playing()).unwrap_or(false)
    }

    /// Volume vai de 0.0 (mudo) a 1.0 (100%); valores fora do range sao
    /// truncados. Persiste entre trocas de video (ver campo `volume`).
    pub fn set_volume(&mut self, volume: f32) {
        self.volume = media_logic::playback_params::clamp_volume(volume);
        if let Ok(ao) = self.audio_output.lock() {
            if let Some(audio) = ao.as_ref() {
                audio.set_volume(self.volume);
            }
        }
    }

    /// F4: contador cumulativo de underruns de audio (ver AudioOutput::get_underrun_count) —
    /// 0 se ainda nao ha AudioOutput (antes do primeiro load_at(), ou lock contestado).
    pub fn get_audio_underrun_count(&self) -> u64 {
        if let Ok(ao) = self.audio_output.lock() {
            if let Some(audio) = ao.as_ref() {
                return audio.get_underrun_count();
            }
        }
        0
    }

    pub fn get_volume(&self) -> f32 {
        self.volume
    }

    /// 0.5x a 2.0x. Efeito em tempo real (nao precisa de reload): a
    /// thread de audio da sessao atual detecta a mudanca e reconstroi o
    /// resampler (ver AudioDecoder::set_speed). Sem preservacao de pitch
    /// (efeito "fita acelerada/desacelerada") — time-stretching de
    /// qualidade fica fora do escopo do MVP. Para arquivos sem trilha de
    /// audio, o SyncManager usa a mesma velocidade no fallback de
    /// wall-clock, entao o video tambem acompanha corretamente.
    pub fn set_speed(&mut self, speed: f32) {
        let clamped = media_logic::playback_params::clamp_speed(speed);
        self.speed_bits.store(clamped.to_bits(), Ordering::Relaxed);
    }

    pub fn get_speed(&self) -> f32 {
        f32::from_bits(self.speed_bits.load(Ordering::Relaxed))
    }

    /// Numero de trilhas de audio do arquivo carregado atualmente (0 se
    /// nada foi carregado ainda ou o arquivo nao tem audio).
    pub fn audio_track_count(&self) -> usize {
        self.audio_track_count
    }

    /// Seleciona a trilha de audio pela posicao ordinal (0 = primeira).
    /// So tem efeito no PROXIMO load_at() — nao troca a trilha "ao vivo"
    /// no meio da reproducao (isso exigiria recriar o AudioDecoder e o
    /// AudioOutput sem interromper a sessao). Combine com seek() ou
    /// cycle_audio_track() para aplicar imediatamente.
    pub fn select_audio_track(&mut self, ordinal: usize) {
        self.desired_audio_track = ordinal;
    }

    /// Avanca para a proxima trilha de audio (com wrap-around) e recarrega
    /// o video na posicao atual para aplicar a troca. Usa stop()+load_at()
    /// direto (nao seek()): so um reload reconfigura o AudioDecoder pro stream novo.
    pub fn cycle_audio_track(&mut self) {
        if self.audio_track_count <= 1 {
            return;
        }
        self.desired_audio_track = (self.desired_audio_track + 1) % self.audio_track_count;
        let (current_position, _) = self.get_progress();
        let Some(path) = self.current_path.clone() else { return };
        let was_playing = self.is_playing();
        self.stop();
        // Erro descartado de proposito: cycle_audio_track() nao tem
        // caminho de reporte pro usuario (diferente de seek_video_playback,
        // ver bridge/lib.rs) — trocar de trilha e uma acao secundaria, uma
        // falha aqui nao deveria virar um erro de playback "principal".
        let _ = self.load_at(&path, current_position);
        if !was_playing {
            self.pause();
        }
    }

    pub fn pause(&mut self) {
        self.set_playing(false);
    }

    pub fn on_focus_lost(&mut self) {
        if self.session.as_ref().map(|s| s.is_playing()).unwrap_or(false) {
            self.auto_paused = true;
            self.pause();
        }
    }

    pub fn on_focus_gained(&mut self) {
        if self.auto_paused {
            self.auto_paused = false;
            self.play();
        }
    }

    pub fn get_current_frame(&self) -> *mut std::os::raw::c_void {
        if let Ok(tex) = self.texture_output.lock() {
            // Note: acquire_latest_buffer is called in decoding loop thread,
            // but we can also just return current_buffer here.
            if let Some(buffer) = tex.current_buffer.as_ref() {
                return buffer.as_ptr() as *mut std::os::raw::c_void;
            }
        }
        std::ptr::null_mut()
    }

    /// Debug (docs/DEBUGGING.md) — ver comentario em TextureOutput::frames_decoded.
    /// Conta frames APRESENTADOS (renderizados), nao frames que o MediaCodec
    /// produziu — ver get_frames_output_count() pra essa distincao.
    pub fn get_frames_decoded_count(&self) -> u64 {
        self.texture_output.lock().map(|tex| tex.frames_decoded).unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — total de buffers de saida que o
    /// MediaCodec realmente desenfileirou, independente do callback de sync
    /// decidir renderizar ou descartar. Ground truth do throughput real do
    /// decoder — ver HwDecoder::metrics().
    pub fn get_frames_output_count(&self) -> u64 {
        self.frames_output.as_ref().map(|a| a.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — frames que o MediaCodec produziu mas o
    /// callback de sync descartou por atraso (LATE_FRAME_RENDER_SKIP_SEC).
    pub fn get_frames_dropped_count(&self) -> u64 {
        self.frames_dropped.as_ref().map(|a| a.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — quantos pacotes de video estao
    /// bufferizados entre a thread de demux e a de decode agora. Perto de 0
    /// de forma sustentada = a thread de demux nao esta acompanhando o
    /// consumo (rede lenta ou travada); alto e estavel = normal.
    pub fn get_video_queue_depth(&self) -> u32 {
        self.video_queue.as_ref().map(|s| s.len() as u32).unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — quantos frames o MediaCodec ja
    /// decodificou mas a video_thread ainda nao liberou/mostrou (ver
    /// comentario em `video_present_pending`). Pendurando perto do maximo
    /// do pool de output buffers do hardware de forma sustentada =
    /// apresentacao nao esta acompanhando o decode.
    pub fn get_video_presentation_pending(&self) -> u32 {
        self.video_present_pending.load(Ordering::Relaxed)
    }

    /// F4: espelho de get_video_queue_depth() para a fila de áudio — antes invisível de
    /// fora (só video_queue guardava o clone do Sender pra observabilidade).
    pub fn get_audio_queue_depth(&self) -> u32 {
        self.audio_queue.as_ref().map(|s| s.len() as u32).unwrap_or(0)
    }

    /// "Buffer estilo YouTube" — segundos de vídeo já enfileirados à frente
    /// do ponteiro de reprodução real (relógio mestre do `SyncManager`), a
    /// mesma grandeza que o `buffer_gate` usa internamente pra decidir
    /// quando parar de ler. Exposto pra alimentar o indicador visual
    /// (`secondaryProgress` do `SeekBar`, ver `VRControlsPresentation.kt`) —
    /// 0.0 antes do primeiro `load_at()` ou logo após um seek, até o
    /// primeiro pacote pousar.
    pub fn get_buffered_ahead_sec(&self) -> f32 {
        media_logic::buffer_gate::buffered_seconds(
            f64::from_bits(self.last_enqueued_video_dts_bits.load(Ordering::Relaxed)),
            self.sync_manager.get_master_clock(),
        ) as f32
    }

    /// F4: contador de erros de decode (ver HwDecoder::decode_packet) — por sessão, 0 antes
    /// do primeiro load_at().
    pub fn get_decode_error_count(&self) -> u64 {
        self.decode_errors
            .as_ref()
            .map(|c| c.load(Ordering::Relaxed))
            .unwrap_or(0)
    }

    /// F4: contador de pacotes corrompidos descartados pelo demuxer (ver Demuxer::read_packet)
    /// — por sessão, 0 antes do primeiro load_at().
    pub fn get_demux_corrupt_packet_count(&self) -> u64 {
        self.demux_corrupt_packets
            .as_ref()
            .map(|c| c.load(Ordering::Relaxed))
            .unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — bytes recebidos da rede pelo
    /// PrefetchReader da fonte atual, soma cumulativa. 0 para arquivo local
    /// ou `http://` puro (sem PrefetchReader envolvido). O C++ amostra isto
    /// ao longo do tempo e calcula MB/s, mesmo padrao de decFps.
    pub fn get_network_bytes_read(&self) -> u64 {
        self.network_stats.as_ref().map(|s| s.bytes_fetched.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// Debug (docs/DEBUGGING.md) — duracao do ULTIMO fetch de bloco completo
    /// do PrefetchReader, em ms. Diferencia throughput ESTAVEL (todo bloco de
    /// 12MB leva ~o mesmo tempo) de throughput em MEDIA baixa por causa de
    /// stalls/retries pontuais (alguns blocos rapidos, outros muito lentos) —
    /// ver docs/NETWORK-IO-PERFORMANCE.md.
    pub fn get_network_last_block_fetch_ms(&self) -> f32 {
        self.network_stats.as_ref().map(|s| s.last_fetch_us.load(Ordering::Relaxed) as f32 / 1000.0).unwrap_or(0.0)
    }

    /// Debug (docs/DEBUGGING.md) — quantos blocos foram buscados no total
    /// (sucesso ou erro) e quantos desses foram descartados por um seek real
    /// antes de serem consumidos. Alto `discarded` relativo a `fetched`
    /// durante playback estavel (nao logo apos abrir o arquivo) indica
    /// acesso menos sequencial do que o esperado.
    pub fn get_network_blocks_fetched(&self) -> u64 {
        self.network_stats.as_ref().map(|s| s.blocks_fetched.load(Ordering::Relaxed)).unwrap_or(0)
    }

    pub fn get_network_blocks_discarded(&self) -> u64 {
        self.network_stats.as_ref().map(|s| s.blocks_discarded.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// F4: falhas de fetch do PrefetchReader (io::Error do RangeSource) — antes so logadas.
    pub fn get_network_fetch_failures(&self) -> u64 {
        self.network_stats.as_ref().map(|s| s.fetch_failures.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// F4: blocos especulativos consecutivos desde o ultimo seek nao-sequencial — alto = leitura
    /// sequencial confirmada, 0 = acabou de sofrer um seek (ver PrefetchReader::sequential_streak).
    pub fn get_network_sequential_streak(&self) -> u32 {
        self.network_stats.as_ref().map(|s| s.sequential_streak.load(Ordering::Relaxed)).unwrap_or(0)
    }

    /// F4: 1 se o read-ahead especulativo esta suspenso por throttle termico (T14.1/T14.2), 0
    /// caso contrario ou antes do primeiro load_at().
    pub fn get_network_throttled(&self) -> u32 {
        self.network_stats.as_ref().map(|s| s.throttled.load(Ordering::Relaxed)).unwrap_or(0)
    }

    // Duracao do ultimo seek concluido (pedido -> pre-roll terminou), em ms. 0 antes do primeiro.
    pub fn get_last_seek_latency_ms(&self) -> u32 {
        self.seek_latency_ms.load(Ordering::Relaxed)
    }

    /// F4: fases do load_at() — ver comentario no campo. Todos 0 antes do primeiro load_at().
    pub fn get_load_phase_demux_open_ms(&self) -> u32 {
        self.load_phase_demux_open_ms.load(Ordering::Relaxed)
    }

    pub fn get_load_phase_decoder_ready_ms(&self) -> u32 {
        self.load_phase_decoder_ready_ms.load(Ordering::Relaxed)
    }

    pub fn get_load_phase_audio_ready_ms(&self) -> u32 {
        self.load_phase_audio_ready_ms.load(Ordering::Relaxed)
    }

    // Drift A/V (master_clock - video_pts) do ultimo frame decodificado, em ms.
    pub fn get_last_av_drift_ms(&self) -> f32 {
        self.av_drift_ms.load(Ordering::Relaxed) as f32
    }

    pub fn toggle_play_pause(&mut self) {
        let currently_playing = self.session.as_ref().map(|s| s.is_playing()).unwrap_or(false);
        let new_state = !currently_playing;
        self.set_playing(new_state);

        crate::log_info!("{}", if new_state { "Resumed playback" } else { "Paused playback" });
    }

    /// Para a geracao atual de threads e espera (join) elas
    /// terminarem de verdade antes de retornar — garante que um
    /// load_at() subsequente (ex: seek()) nunca rode concorrentemente
    /// com threads de uma geracao anterior.
    pub fn stop(&mut self) {
        if let Some(session) = self.session.take() {
            session.stop_and_join();
        }
        self.sync_manager.pause();

        let mut audio_output = self.audio_output.lock().unwrap();
        if let Some(mut output) = audio_output.take() {
            let _ = output.stop();
        }

        if let Ok(mut tex) = self.texture_output.lock() {
            tex.clear();
        }
        self.duration = 0.0;
        self.current_path = None;
        self.auto_paused = false;
    }

    pub fn get_progress(&self) -> (f64, f64) {
        (self.sync_manager.get_master_clock(), self.duration)
    }

    pub fn detected_screen_mode(&self) -> u32 {
        self.detected_screen_mode
    }

    /// T-HDR: true se o video atual foi detectado como PQ/HLG (ver
    /// media_logic::color) — consumido pelo C++ (bridge) pra escolher o
    /// pipeline de cor Vulkan certo. false antes do primeiro load_at() ou
    /// para conteudo SDR comum.
    pub fn is_hdr(&self) -> bool {
        self.is_hdr
    }

    pub fn set_subtitle_track(&mut self, track: i32) {
        self.selected_subtitle_track = track;
        if track < 0 || (track as usize) >= self.available_subtitle_tracks.len() {
            self.subtitle_entries = None;
            self.ass_subtitle = None;
            self.pgs_subtitles = None;
        } else {
            self.load_subtitle_track(track as usize);
        }
    }

    pub fn get_subtitle_track(&self) -> i32 {
        self.selected_subtitle_track
    }

    pub fn set_subtitle_offset_ms(&mut self, offset_ms: i64) {
        self.subtitle_offset_ms = offset_ms;
    }

    pub fn get_subtitle_offset_ms(&self) -> i64 {
        self.subtitle_offset_ms
    }

    pub fn get_subtitle_track_count(&self) -> usize {
        self.available_subtitle_tracks.len()
    }

    pub fn load_external_subtitle(&mut self, path: &str) -> Result<u32, String> {
        let loaded = crate::subtitle_loader::load_subtitle_from_path(path)?;
        let count = match &loaded {
            crate::subtitle_loader::LoadedSubtitle::Text(entries) => entries.len() as u32,
            crate::subtitle_loader::LoadedSubtitle::Ass(ass) => ass.events.len() as u32,
            crate::subtitle_loader::LoadedSubtitle::Pgs(pgs) => pgs.len() as u32,
        };
        self.apply_loaded_subtitle(loaded);
        let idx = self.available_subtitle_tracks.len();
        self.available_subtitle_tracks.push(crate::subtitle_loader::SubtitleTrackInfo {
            title: std::path::Path::new(path).file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_else(|| "External".into()),
            language: String::new(),
            is_external: true,
            source_path: Some(path.to_string()),
            stream_index: None,
        });
        self.selected_subtitle_track = idx as i32;
        Ok(count)
    }

    pub fn set_preferred_subtitle_language(&mut self, lang: &str) {
        let lang = lang.trim();
        self.preferred_subtitle_language =
            if lang.is_empty() { None } else { Some(lang.to_string()) };
    }

    fn apply_loaded_subtitle(&mut self, loaded: crate::subtitle_loader::LoadedSubtitle) {
        match loaded {
            crate::subtitle_loader::LoadedSubtitle::Text(entries) => {
                self.subtitle_entries = Some(entries);
                self.ass_subtitle = None;
                self.pgs_subtitles = None;
            }
            crate::subtitle_loader::LoadedSubtitle::Ass(ass) => {
                self.subtitle_entries = Some(ass.to_subtitle_entries());
                self.ass_subtitle = Some(ass);
                self.pgs_subtitles = None;
            }
            crate::subtitle_loader::LoadedSubtitle::Pgs(pgs) => {
                self.subtitle_entries = None;
                self.ass_subtitle = None;
                self.pgs_subtitles = Some(pgs);
            }
        }
    }

    fn load_subtitle_track(&mut self, idx: usize) {
        let (is_external, source_path, stream_index) =
            match self.available_subtitle_tracks.get(idx) {
                Some(t) => (t.is_external, t.source_path.clone(), t.stream_index),
                None => return,
            };

        if is_external {
            let Some(path) = source_path else { return };
            match crate::subtitle_loader::load_subtitle_from_path(&path) {
                Ok(loaded) => self.apply_loaded_subtitle(loaded),
                Err(e) => {
                    crate::log_info!("load_subtitle_track: faixa externa falhou: {e}");
                    self.subtitle_entries = None;
                    self.ass_subtitle = None;
                    self.pgs_subtitles = None;
                }
            }
        } else if let (Some(stream_index), Some(path)) = (stream_index, self.current_path.clone()) {
            match crate::subtitle_loader::load_embedded_subtitle(&path, stream_index) {
                Ok(loaded) => self.apply_loaded_subtitle(loaded),
                Err(e) => {
                    crate::log_info!("load_subtitle_track: faixa embutida falhou: {e}");
                    self.subtitle_entries = None;
                    self.ass_subtitle = None;
                    self.pgs_subtitles = None;
                }
            }
        }
    }

    pub fn get_active_subtitle_text(&self) -> Option<String> {
        let entries = self.subtitle_entries.as_ref()?;
        let current_pts_sec = self.sync_manager.get_master_clock();
        let current_pts_ms = (current_pts_sec * 1000.0) as i64;
        media_logic::subtitle::find_active_cue(entries, current_pts_ms, self.subtitle_offset_ms).map(|c| c.text.clone())
    }

    pub fn get_active_pgs(&self) -> Option<&media_logic::subtitle_pgs::PgsSubtitle> {
        let pgs = self.pgs_subtitles.as_ref()?;
        let current_pts_sec = self.sync_manager.get_master_clock();
        let current_pts_ms = (current_pts_sec * 1000.0) as i64;
        media_logic::subtitle_pgs::find_active_pgs(pgs, current_pts_ms, self.subtitle_offset_ms)
    }

    pub fn get_active_ass_event(&self) -> Option<(&media_logic::subtitle_ass::AssEvent, &media_logic::subtitle_ass::AssScriptInfo)> {
        let ass = self.ass_subtitle.as_ref()?;
        let current_pts_sec = self.sync_manager.get_master_clock();
        let current_pts_ms = (current_pts_sec * 1000.0) as i64;
        let event = media_logic::subtitle_ass::find_active_ass_event(ass, current_pts_ms, self.subtitle_offset_ms)?;
        Some((event, &ass.script_info))
    }
}
