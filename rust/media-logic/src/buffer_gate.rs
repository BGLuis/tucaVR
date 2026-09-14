//! Decide quando a thread de demux deve continuar lendo pacotes à frente do
//! ponteiro de reprodução ou esperar — a peça que faltava para o buffer de
//! leitura-antecipada deixar de ser um efeito colateral (a fila limitada por
//! CONTAGEM de pacotes em `core::playback`, ~90 vídeo/100 áudio, que varia de
//! ~1s a ~3,75s conforme o framerate) e virar uma estratégia deliberada,
//! medida em segundos à frente do relógio mestre — o mesmo eixo de controle
//! que o `minBufferMs`/`maxBufferMs` do ExoPlayer usa, em vez de um número
//! fixo de pacotes.
//!
//! Pausado, o alvo é bem maior (`paused_target_sec`) de propósito: sem
//! consumidor drenando a fila, não há motivo pra parar de carregar cedo — é
//! o comportamento "estilo YouTube" que o usuário pediu. Mas a fila guarda
//! pacotes COMPRIMIDOS (pré-decode), não frames YUV decodificados, e em
//! conteúdo 8K/360° (100-150Mbps) 30s cheios passariam de 375-560MB — por
//! isso `byte_ceiling` é uma condição independente que sempre pode cortar o
//! alvo em segundos mais cedo, nunca o contrário.
//!
//! Zero I/O, zero dependência de Android/ndk — testável com `cargo test -p
//! media-logic` num laptop qualquer.

/// Alvos de buffer, em segundos de mídia à frente do relógio mestre.
///
/// Os valores usados em produção (2.0 / 8.0 / 25.0 / 0.8) são os aprovados
/// para o rollout inicial (ver docs/reports — plano de buffer "estilo
/// YouTube"), não constantes fixas neste módulo: cada chamador decide os
/// próprios números, este tipo só carrega e valida a forma.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BufferTargets {
    /// Tocando, fonte local — releitura é quase grátis, não vale a pena
    /// hoardear muito à frente.
    pub playing_local_sec: f64,
    /// Tocando, fonte de rede — cobre solavancos de rede sem inflar demais a
    /// memória durante playback normal.
    pub playing_network_sec: f64,
    /// Pausado — alvo deliberadamente maior, o "carrega enquanto eu não
    /// estou vendo" do YouTube. Sempre subordinado a `byte_ceiling`.
    pub paused_target_sec: f64,
    /// Fração de `target` abaixo da qual a leitura é retomada depois de
    /// entrar em `Wait`. Sem histerese, o gate oscilaria Read/Wait a cada
    /// pacote assim que `buffered_sec` cruzasse o alvo (ver `next_gate_state`).
    pub resume_hysteresis: f64,
}

impl BufferTargets {
    fn target_for(&self, is_paused: bool, is_network_source: bool) -> f64 {
        if is_paused {
            self.paused_target_sec
        } else if is_network_source {
            self.playing_network_sec
        } else {
            self.playing_local_sec
        }
    }
}

/// Decisão do gate: ler mais um pacote ou esperar o consumidor drenar a fila.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GateState {
    Read,
    Wait,
}

/// Fração do total de RAM do dispositivo usada como teto do buffer profundo
/// pausado (ver `paused_byte_ceiling_for_device`) — 2,5% bate ~200MB nos 8GB
/// do Quest 3, a referência já aprovada e testada. Um eventual aparelho mais
/// barato/leve (menos RAM) recebe um teto proporcionalmente menor; um
/// eventual aparelho mais robusto (mais RAM) NÃO recebe um teto
/// proporcionalmente maior sem limite — mais RAM no aparelho não significa
/// que vale a pena guardar dezenas de segundos de pacotes comprimidos só
/// porque "sobra memória" (o compositor VR e o resto do app também competem
/// por ela) — daí o teto superior abaixo.
const PAUSED_BYTE_CEILING_FRACTION: f64 = 0.025;

/// Nunca menos que isto, mesmo num aparelho com pouquíssima RAM — abaixo
/// disso o buffer profundo pausado deixa de valer a pena (viraria só
/// alguns segundos de conteúdo de alto bitrate).
pub const PAUSED_BYTE_CEILING_MIN_BYTES: u64 = 128 * 1024 * 1024;

/// Nunca mais que isto, mesmo num aparelho com muita RAM — teto
/// "conservador" aprovado junto com o resto do buffer_gate.
pub const PAUSED_BYTE_CEILING_MAX_BYTES: u64 = 256 * 1024 * 1024;

/// Usado quando o total de RAM do dispositivo ainda não foi reportado pelo
/// lado Kotlin (ex.: um load_at() disparado antes do primeiro
/// `set_device_total_memory_bytes`) — mesmo valor fixo que existia antes
/// desta função, calibrado para os 8GB do Quest 3.
const PAUSED_BYTE_CEILING_FALLBACK_BYTES: u64 = 200 * 1024 * 1024;

/// Teto de bytes do buffer profundo pausado, escalado pela RAM total do
/// dispositivo em vez de um número fixo — dispositivos futuros com menos
/// memória (ex.: uma variante mais barata) automaticamente recebem um teto
/// menor; com mais memória (ex.: uma variante "Pro"), o teto fica limitado
/// em `PAUSED_BYTE_CEILING_MAX_BYTES` em vez de crescer sem fim. `0` (RAM
/// desconhecida) cai no valor fixo pré-existente, calibrado para o Quest 3.
pub fn paused_byte_ceiling_for_device(total_memory_bytes: u64) -> u64 {
    if total_memory_bytes == 0 {
        return PAUSED_BYTE_CEILING_FALLBACK_BYTES;
    }
    let target = (total_memory_bytes as f64 * PAUSED_BYTE_CEILING_FRACTION) as u64;
    target.clamp(PAUSED_BYTE_CEILING_MIN_BYTES, PAUSED_BYTE_CEILING_MAX_BYTES)
}

/// Duração de mídia já enfileirada à frente do que está sendo consumido —
/// simplesmente a distância entre o timestamp do último pacote de vídeo
/// enfileirado com sucesso e a posição real de consumo (relógio mestre do
/// `SyncManager`). O chamador (`core::playback`) usa o DTS do pacote aqui,
/// não o PTS: pacotes saem do demuxer em ORDEM DE DECODE, e com B-frames o
/// PTS não é monotônico nessa ordem — usar PTS fazia essa distância oscilar
/// erraticamente. Nunca negativa: um timestamp momentaneamente atrás do
/// relógio (ex.: logo após um seek, antes do primeiro pacote pousar) conta
/// como "nada bufferizado", não como buffer negativo.
pub fn buffered_seconds(last_enqueued_pts_sec: f64, consumed_pos_sec: f64) -> f64 {
    (last_enqueued_pts_sec - consumed_pos_sec).max(0.0)
}

/// Próximo estado do gate. `byte_ceiling` é uma condição independente do
/// alvo em segundos — o que disparar primeiro vence, nas duas direções: até
/// bytes livres, o alvo em segundos manda; ao bater o teto de bytes, para de
/// ler mesmo que o alvo em segundos ainda não tenha sido atingido.
pub fn next_gate_state(
    prev: GateState,
    buffered_sec: f64,
    bytes_queued: u64,
    targets: &BufferTargets,
    byte_ceiling: u64,
    is_paused: bool,
    is_network_source: bool,
) -> GateState {
    if bytes_queued >= byte_ceiling {
        return GateState::Wait;
    }

    let target = targets.target_for(is_paused, is_network_source);
    let resume_below = target * targets.resume_hysteresis;

    match prev {
        GateState::Read if buffered_sec >= target => GateState::Wait,
        GateState::Wait if buffered_sec < resume_below => GateState::Read,
        other => other,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn targets() -> BufferTargets {
        BufferTargets {
            playing_local_sec: 2.0,
            playing_network_sec: 8.0,
            paused_target_sec: 25.0,
            resume_hysteresis: 0.8,
        }
    }

    #[test]
    fn buffered_seconds_is_the_gap_between_enqueued_pts_and_consumed_position() {
        assert_eq!(buffered_seconds(10.0, 4.0), 6.0);
    }

    #[test]
    fn buffered_seconds_never_goes_negative() {
        assert_eq!(buffered_seconds(1.0, 5.0), 0.0);
    }

    #[test]
    fn picks_playing_local_target_when_not_paused_and_not_network() {
        let t = targets();
        assert_eq!(t.target_for(false, false), 2.0);
    }

    #[test]
    fn picks_playing_network_target_when_not_paused_and_network() {
        let t = targets();
        assert_eq!(t.target_for(false, true), 8.0);
    }

    #[test]
    fn paused_target_wins_regardless_of_source_kind() {
        let t = targets();
        assert_eq!(t.target_for(true, false), 25.0);
        assert_eq!(t.target_for(true, true), 25.0);
    }

    #[test]
    fn keeps_reading_below_target() {
        let t = targets();
        let state = next_gate_state(GateState::Read, 1.0, 0, &t, u64::MAX, false, false);
        assert_eq!(state, GateState::Read);
    }

    #[test]
    fn switches_to_wait_once_target_is_reached() {
        let t = targets();
        let state = next_gate_state(GateState::Read, 2.0, 0, &t, u64::MAX, false, false);
        assert_eq!(state, GateState::Wait);
    }

    #[test]
    fn does_not_flap_immediately_after_crossing_the_target() {
        // Regressao que a histerese existe pra evitar: sem ela, um buffered_sec
        // logo abaixo do alvo (ex.: consumidor drenou um pacotinho) já
        // retomaria a leitura, que por sua vez passaria do alvo de novo no
        // pacote seguinte — Read/Wait alternando a cada iteracao.
        let t = targets();
        let mut state = GateState::Read;
        state = next_gate_state(state, 2.0, 0, &t, u64::MAX, false, false); // bate o alvo -> Wait
        assert_eq!(state, GateState::Wait);
        state = next_gate_state(state, 1.99, 0, &t, u64::MAX, false, false); // caiu pouquinho, mas acima da banda
        assert_eq!(state, GateState::Wait);
    }

    #[test]
    fn resumes_reading_only_once_below_the_hysteresis_band() {
        let t = targets(); // resume_hysteresis = 0.8 -> banda em 1.6s (80% de 2.0s)
        let mut state = GateState::Wait;
        state = next_gate_state(state, 1.61, 0, &t, u64::MAX, false, false);
        assert_eq!(state, GateState::Wait, "ainda acima da banda de retomada");
        state = next_gate_state(state, 1.59, 0, &t, u64::MAX, false, false);
        assert_eq!(state, GateState::Read, "abaixo da banda, deve retomar a leitura");
    }

    #[test]
    fn byte_ceiling_forces_wait_even_if_duration_target_not_reached() {
        let t = targets();
        let state = next_gate_state(GateState::Read, 0.5, 300, &t, 200, true, true);
        assert_eq!(state, GateState::Wait, "teto de bytes deve cortar antes do alvo em segundos");
    }

    #[test]
    fn duration_target_still_applies_when_byte_ceiling_not_reached() {
        let t = targets();
        let state = next_gate_state(GateState::Read, 26.0, 50, &t, 200 * 1024 * 1024, true, true);
        assert_eq!(state, GateState::Wait, "alvo em segundos deve valer mesmo com bytes livres");
    }

    #[test]
    fn paused_network_source_reads_deep_into_the_paused_target_when_bytes_allow() {
        let t = targets();
        // 20s ainda abaixo do alvo pausado de 25s, e bem abaixo do teto de bytes.
        let state = next_gate_state(GateState::Read, 20.0, 1024, &t, 200 * 1024 * 1024, true, true);
        assert_eq!(state, GateState::Read, "pausado deveria continuar bufferizando alem do alvo de rede tocando");
    }

    #[test]
    fn unknown_device_memory_falls_back_to_the_pre_existing_fixed_ceiling() {
        assert_eq!(paused_byte_ceiling_for_device(0), 200 * 1024 * 1024);
    }

    #[test]
    fn quest3_class_device_lands_near_the_pre_existing_200mb_reference() {
        let eight_gb = 8 * 1024 * 1024 * 1024;
        let ceiling = paused_byte_ceiling_for_device(eight_gb);
        // 2.5% de 8GiB = ~204.8MB (2.5% de 8GB decimal e que dava exatos
        // 200MB — nao e a mesma conta com GiB) — dentro de 10MB da
        // referencia de 200MB que ja estava aprovada, o que importa aqui.
        let ten_mb = 10 * 1024 * 1024;
        assert!((ceiling as i64 - 200 * 1024 * 1024).abs() < ten_mb, "esperava ~200MB, obteve {ceiling}");
    }

    #[test]
    fn lower_memory_device_gets_a_smaller_ceiling_but_never_below_the_floor() {
        let four_gb = 4u64 * 1024 * 1024 * 1024;
        // 2.5% de 4GB = ~100MB, abaixo do piso de 128MB -> deve ser grampeado pro piso.
        assert_eq!(paused_byte_ceiling_for_device(four_gb), PAUSED_BYTE_CEILING_MIN_BYTES);
    }

    #[test]
    fn higher_memory_device_is_capped_instead_of_scaling_unbounded() {
        let sixteen_gb = 16u64 * 1024 * 1024 * 1024;
        // 2.5% de 16GB = ~429MB, bem acima do teto de 256MB -> deve ser grampeado pro teto.
        assert_eq!(paused_byte_ceiling_for_device(sixteen_gb), PAUSED_BYTE_CEILING_MAX_BYTES);
    }

    #[test]
    fn ceiling_scales_monotonically_with_device_memory_within_the_bounds() {
        let six_gb = 6u64 * 1024 * 1024 * 1024;
        let ten_gb = 10u64 * 1024 * 1024 * 1024;
        assert!(paused_byte_ceiling_for_device(six_gb) < paused_byte_ceiling_for_device(ten_gb));
    }
}
