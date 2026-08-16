use crate::demuxer::Demuxer;
use crate::decoder::HwDecoder;
use crate::audio_decoder::AudioDecoder;
use audio::output::AudioOutput;
use crate::sync::SyncManager;
use crate::texture::TextureOutput;
use ndk::media::media_format::MediaFormat;
use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;

const LATE_FRAME_RENDER_SKIP_SEC: f64 = 0.1;

/// Quando o proximo pacote de video ja nasce mais atrasado que isto em
/// relacao ao master_clock, pacotes nao-chave sao descartados sem decodificar
/// ate a proxima keyframe (ver video_thread). Sem isso, um atraso persistente
/// (decode mais lento que o exigido pela velocidade pedida, ex: 2x em 8K60,
/// onde o hardware ja decodifica perto do limite em 1x) nunca se recupera —
/// LATE_FRAME_RENDER_SKIP_SEC so descarta no render, sem reduzir o backlog,
/// entao o atraso so cresce e trava no ultimo frame renderizado pra sempre.
const CATCH_UP_SKIP_THRESHOLD_SEC: f64 = 0.5;

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
    auto_paused: bool,
    // Instrumentacao (docs/DEBUGGING.md), capturada em load_at() antes de
    // mover o Demuxer/HwDecoder pra dentro das threads de sessao — ver
    // getters no fim do impl. `None`/valores zerados antes do primeiro load.
    network_stats: Option<Arc<protocols::prefetch::PrefetchStats>>,
    video_queue: Option<crossbeam_channel::Sender<ffmpeg_next::Packet>>,
    frames_output: Option<Arc<std::sync::atomic::AtomicU64>>,
    frames_dropped: Option<Arc<std::sync::atomic::AtomicU64>>,
    // Conexao de rede reaproveitavel entre seeks no mesmo path (so SFTP por
    // enquanto) — ver `crate::demuxer::ConnectionCache`.
    connection_cache: crate::demuxer::ConnectionCache,
}

impl PlaybackController {
    pub fn new() -> Self {
        let speed_bits = Arc::new(AtomicU32::new(1.0f32.to_bits()));
        Self {
            texture_output: Arc::new(Mutex::new(TextureOutput::new())),
            audio_output: Arc::new(Mutex::new(None)),
            sync_manager: Arc::new(SyncManager::new(speed_bits.clone())),
            session: None,
            current_path: None,
            duration: 0.0,
            volume: 1.0,
            speed_bits,
            desired_audio_track: 0,
            audio_track_count: 0,
            auto_paused: false,
            network_stats: None,
            video_queue: None,
            frames_output: None,
            frames_dropped: None,
            connection_cache: crate::demuxer::ConnectionCache::default(),
        }
    }

    /// Erro retornado (se houver) precisa ser reportado pelo chamador via
    /// `set_last_playback_error` (ver `bridge::seek_video_playback`) — sem
    /// isso, uma falha aqui era inteiramente silenciosa: antes do frame
    /// congelado (T-seek-ux) isso pelo menos aparecia como tela preta
    /// obviamente quebrada; agora fica esperando atras do ultimo frame,
    /// parecendo "travado" em vez de "erro", se ninguem repassar o erro.
    pub fn seek(&mut self, position_sec: f64) -> Result<(), String> {
        if let Some(path) = self.current_path.clone() {
            // load_at() sempre cria uma Generation nova com is_playing=true
            // (ver media_logic::session::Generation::new) — sem isto, um
            // seek durante pausa despausava sozinho, e o indicador de
            // play/pause da UI (get_playback_is_playing) mentiria logo
            // depois de qualquer seek com o video pausado.
            let was_playing = self.is_playing();
            self.stop();
            self.load_at(&path, position_sec).map_err(|e| e.to_string())?;
            if !was_playing {
                self.pause();
            }
        }
        Ok(())
    }

    pub fn load(&mut self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        self.load_at(path, 0.0)
    }

    pub fn load_at(&mut self, path: &str, start_time: f64) -> Result<(), Box<dyn std::error::Error>> {
        // Garante que a geracao anterior (se houver) esta totalmente
        // parada antes de tocar em qualquer estado compartilhado.
        if let Some(session) = self.session.take() {
            session.stop_and_join();
        }

        self.current_path = Some(path.to_string());
        let mut demuxer = Demuxer::open(path, Some(&mut self.connection_cache)).map_err(|e| e.to_string())?;
        self.network_stats = demuxer.network_stats.clone();
        demuxer.select_audio_track(self.desired_audio_track);
        self.audio_track_count = demuxer.audio_streams.len();
        self.duration = demuxer.input_context.duration() as f64 / 1_000_000.0;

        let video_idx = demuxer.video_stream_index.ok_or("No video stream")?;
        let mut width = 1920;
        let mut height = 1080;
        let mut codec_id = ffmpeg_next::codec::Id::None;
        if let Some(stream) = demuxer.input_context.stream(video_idx) {
            codec_id = stream.parameters().id();
            if let Ok(decoder) = ffmpeg_next::codec::context::Context::from_parameters(stream.parameters()) {
                if let Ok(video_decoder_ctx) = decoder.decoder().video() {
                    width = video_decoder_ctx.width() as u32;
                    height = video_decoder_ctx.height() as u32;
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

        let mut video_decoder = HwDecoder::new(mime).map_err(|e| e.to_string())?;
        let (frames_output, frames_dropped) = video_decoder.metrics();
        self.frames_output = Some(frames_output);
        self.frames_dropped = Some(frames_dropped);

        let mut format = MediaFormat::new();
        format.set_str("mime", mime);
        format.set_i32("width", width as i32);
        format.set_i32("height", height as i32);

        video_decoder.configure(&format, window.as_ref()).map_err(|e| e.to_string())?;
        video_decoder.start().map_err(|e| e.to_string())?;

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
        let mut sync_manager = SyncManager::new(self.speed_bits.clone());
        sync_manager.start();
        let sync_manager = Arc::new(sync_manager);
        self.sync_manager = sync_manager.clone();
        if start_time > 0.0 {
            self.sync_manager.update_master_clock(start_time);
        }

        // 90 (~1.5s a 60fps) em vez de 30 (~0.5s): absorve stalls de rede maiores antes de faltar pacote pro decoder.
        let (video_tx, video_rx) = crossbeam_channel::bounded::<ffmpeg_next::Packet>(90);
        let (audio_tx, audio_rx) = crossbeam_channel::bounded::<ffmpeg_next::Packet>(100);
        // Clone do Sender so pra poder consultar profundidade (`len()`) de
        // fora da thread de demux — nao envia nada por este handle, ver
        // get_video_queue_depth().
        self.video_queue = Some(video_tx.clone());

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
            loop {
                if !*is_running_d.lock().unwrap() { break; }

                if !*is_playing_d.lock().unwrap() {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                    continue;
                }

                match demuxer.read_packet() {
                    crate::demuxer::ReadPacketOutcome::Packet(idx, packet) => {
                        if idx == video_idx {
                            if !try_send_until_stopped(&video_tx, packet, &is_running_d) { break; }
                        } else if demuxer.audio_stream_index == Some(idx) {
                            if !try_send_until_stopped(&audio_tx, packet, &is_running_d) { break; }
                        }
                    }
                    crate::demuxer::ReadPacketOutcome::Eof => {
                        let _ = demuxer.input_context.seek(0, 0..1);
                        sync_d.reset();
                    }
                    crate::demuxer::ReadPacketOutcome::Error(e) => {
                        // Distinguido de EOF nesta sessao
                        // (docs/NETWORK-IO-PERFORMANCE.md) — antes disto uma
                        // falha de rede virava um restart silencioso e
                        // indistinguivel de EOF. Mantem a mesma recuperacao
                        // (seek pro inicio) por ora: e o unico caminho
                        // testado hoje pra "o AVFormatContext esta num
                        // estado de erro sticky, precisa recomecar" — so que
                        // agora loga em vez de esconder.
                        unsafe {
                            let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
                            let msg = std::ffi::CString::new(format!("Demuxer: erro de leitura ({e}), reiniciando do inicio")).unwrap();
                            ndk_sys::__android_log_print(5, tag.as_ptr(), msg.as_ptr()); // 5 = ANDROID_LOG_WARN
                        }
                        let _ = demuxer.input_context.seek(0, 0..1);
                        sync_d.reset();
                    }
                }
            }
        });

        // Thread 2: Video Decoder
        let sync_v = sync_manager.clone();
        let video_thread = thread::spawn(move || {
            if let Some(sps) = sps_pps {
                let _ = video_decoder.decode_packet(&sps, 0, 2, |_| true, || {}, || true);
            }

            loop {
                if !*is_running_v.lock().unwrap() { break; }

                if !*is_playing_v.lock().unwrap() {
                    std::thread::sleep(std::time::Duration::from_millis(50));
                    continue;
                }

                if let Ok(packet) = video_rx.recv_timeout(std::time::Duration::from_millis(50)) {
                    let pts = packet.pts().unwrap_or(0);
                    let pts_sec = pts as f64 * video_time_base;
                    let lag = sync_v.get_master_clock() - pts_sec;
                    if lag > CATCH_UP_SKIP_THRESHOLD_SEC {
                        if packet.is_key() {
                            unsafe {
                                let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
                                let msg = std::ffi::CString::new(format!("Video catch-up: retomando decode na keyframe (lag era {lag:.2}s)")).unwrap();
                                ndk_sys::__android_log_print(4, tag.as_ptr(), msg.as_ptr());
                            }
                        } else {
                            continue;
                        }
                    }

                    if let Some(data) = packet.data() {
                        let frame_data = if video_is_nal_based {
                            crate::nal::convert_avcc_to_annexb(data)
                        } else {
                            data.to_vec()
                        };
                        let _ = video_decoder.decode_packet(
                            &frame_data,
                            pts,
                            0,
                            |out_pts| {
                                let pts_sec = out_pts as f64 * video_time_base;
                                let master_clock = sync_v.get_master_clock();
                                let delay = pts_sec - master_clock;
                                if delay > 0.0 && delay < 1.0 {
                                    std::thread::sleep(std::time::Duration::from_secs_f64(delay));
                                }
                                delay > -LATE_FRAME_RENDER_SKIP_SEC
                            },
                            || {
                                if let Ok(mut tex) = texture_output_clone.lock() {
                                    let _ = tex.acquire_latest_buffer();
                                }
                            },
                            || *is_running_v.lock().unwrap()
                        );
                    }
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
            let mut applied_speed = 1.0f32;
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

                if let Ok(packet) = audio_rx.recv_timeout(std::time::Duration::from_millis(50)) {
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

                                if let Some(sender) = &audio_sender {
                                    for &sample in &samples {
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
    /// o video na posicao atual para aplicar a troca.
    pub fn cycle_audio_track(&mut self) {
        if self.audio_track_count <= 1 {
            return;
        }
        self.desired_audio_track = (self.desired_audio_track + 1) % self.audio_track_count;
        let (current_position, _) = self.get_progress();
        // Erro descartado de proposito: cycle_audio_track() nao tem
        // caminho de reporte pro usuario (diferente de seek_video_playback,
        // ver bridge/lib.rs) — trocar de trilha e uma acao secundaria, uma
        // falha aqui nao deveria virar um erro de playback "principal".
        let _ = self.seek(current_position);
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

    pub fn toggle_play_pause(&mut self) {
        let currently_playing = self.session.as_ref().map(|s| s.is_playing()).unwrap_or(false);
        let new_state = !currently_playing;
        self.set_playing(new_state);

        unsafe {
            let tag = std::ffi::CString::new("VRPlayer_Rust").unwrap();
            let msg = std::ffi::CString::new(if new_state { "Resumed playback" } else { "Paused playback" }).unwrap();
            ndk_sys::__android_log_print(4, tag.as_ptr(), msg.as_ptr());
        }
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
    }

    pub fn get_progress(&self) -> (f64, f64) {
        (self.sync_manager.get_master_clock(), self.duration)
    }
}
