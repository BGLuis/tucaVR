package com.tucavr.debug

/**
 * F5 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, seção 2.3): atribuição de estágio do
 * pipeline a partir de sinais que já trafegam no wire — resolve a pergunta "onde travou" sem
 * reconstruir a leitura à mão (o que a telemetria anterior exigia, e o que levou o
 * QualityController a culpar DROPPED_FRAMES num caso onde o gargalo era outro, ver §1.2).
 *
 * Puro (sem Context/View/native) de propósito: testável com JUnit puro na JVM.
 */
enum class BottleneckStage {
    /** Vídeo não está estagnado (frame_gap_ms dentro do normal) — nada a atribuir. */
    NONE,
    /** Fila de vídeo praticamente vazia durante o estagno: o demux não está entregando
     * pacotes novos — rede lenta/caída, ou fonte remota travada. */
    NETWORK,
    /** Fila de vídeo praticamente cheia durante o estagno: pacotes chegaram, mas o
     * consumo (decode/sync/apresentação) parou de drenar — exatamente o padrão medido na
     * sessão real de §1.2 (`video_q_depth = 90` pelos 34,5s inteiros do stall). Sinal
     * atual não distingue decode/sync/render especificamente — granularidade além disto
     * exigiria contadores por estágio que ainda não existem no wire. */
    PRESENTATION
}

object BottleneckStageAnalyzer {
    /** Mesmo limiar de stall de vídeo do C++ (kVideoStallThresholdMs, vr_player_app_vulkan.cpp). */
    const val STALL_THRESHOLD_MS = 500f

    /** Capacidade real do canal de vídeo é 90 pacotes (rust/core/src/playback.rs,
     * `crossbeam_channel::bounded::<TaggedPacket>(90)`) — usa uma fração alta em vez de
     * exigir exatamente 90 para não fazer jitter de +-1 pacote mudar o diagnóstico. */
    const val QUEUE_FULL_THRESHOLD = 80
    const val QUEUE_EMPTY_THRESHOLD = 5

    fun analyze(stats: NativeDebugStats): BottleneckStage {
        val isStalled = stats.frameGapMs > STALL_THRESHOLD_MS
        if (!isStalled) return BottleneckStage.NONE

        return when {
            stats.queueDepth <= QUEUE_EMPTY_THRESHOLD -> BottleneckStage.NETWORK
            stats.queueDepth >= QUEUE_FULL_THRESHOLD -> BottleneckStage.PRESENTATION
            // Entre os dois limiares: o stall acabou de começar e a fila ainda está
            // drenando/enchendo — não há sinal forte o bastante pra apontar um lado ainda.
            else -> BottleneckStage.NONE
        }
    }
}
