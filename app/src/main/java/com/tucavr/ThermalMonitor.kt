package com.tucavr

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * T14.1 (docs/phases/PHASE-0.2-3D-NETWORK.md, seção 14 / RNF-PERF-006):
 * Monitoramento térmico do Meta Quest 3 via [PowerManager.OnThermalStatusChangedListener].
 *
 * Mapeia os status térmicos do sistema operacional para [ThermalState] com
 * ações de mitigação adaptativas ([ThermalAction]) aplicadas no render pipeline C++,
 * no streaming Rust e na interface Kotlin.
 */
class ThermalMonitor(
    private val context: Context,
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)
) {
    companion object {
        private const val TAG = "ThermalMonitor"

        /**
         * Mapeia o status numérico de [PowerManager.THERMAL_STATUS_*] para [ThermalState].
         * Extraído como função pura para testes unitários isolados na JVM sem mocks de sistema.
         */
        fun mapStatusToState(status: Int): ThermalState = when (status) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT ->
                ThermalState(ThermalLevel.NORMAL, emptyList())

            PowerManager.THERMAL_STATUS_MODERATE ->
                ThermalState(
                    ThermalLevel.MODERATE,
                    listOf(
                        ThermalAction.SIMPLIFY_ENVIRONMENT,
                        ThermalAction.PAUSE_PREFETCH
                    )
                )

            PowerManager.THERMAL_STATUS_SEVERE ->
                ThermalState(
                    ThermalLevel.SEVERE,
                    listOf(
                        ThermalAction.REDUCE_RENDER_RESOLUTION,
                        ThermalAction.SIMPLIFY_ENVIRONMENT,
                        ThermalAction.LIMIT_FPS,
                        ThermalAction.WARN_USER
                    )
                )

            PowerManager.THERMAL_STATUS_CRITICAL ->
                ThermalState(
                    ThermalLevel.CRITICAL,
                    listOf(
                        ThermalAction.PAUSE_PLAYBACK,
                        ThermalAction.WARN_USER
                    )
                )

            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN ->
                ThermalState(
                    ThermalLevel.SHUTDOWN,
                    listOf(
                        ThermalAction.PAUSE_PLAYBACK,
                        ThermalAction.WARN_USER
                    )
                )

            else -> ThermalState(ThermalLevel.NORMAL, emptyList())
        }
    }

    enum class ThermalLevel(val rawLevel: Int) {
        NORMAL(0),
        LIGHT(1),
        MODERATE(2),
        SEVERE(3),
        CRITICAL(4),
        SHUTDOWN(5)
    }

    enum class ThermalAction {
        REDUCE_RENDER_RESOLUTION, // Baixar resolução do swapchain/viewport (ex.: 0.8x)
        SIMPLIFY_ENVIRONMENT, // Desligar filtros custosos (CAS) ou elevar foveation
        REDUCE_DECODE_RESOLUTION, // Pedir variante de menor qualidade (HLS)
        LIMIT_FPS, // Reduzir de 90fps para 72fps
        PAUSE_PREFETCH, // Parar prefetch de rede em background
        WARN_USER, // Notificar usuário na interface
        PAUSE_PLAYBACK // Pausar decodificação imediatamente
    }

    data class ThermalState(
        val level: ThermalLevel,
        val actions: List<ThermalAction>
    )

    private val listeners = CopyOnWriteArrayList<(ThermalState) -> Unit>()
    private var systemListener: PowerManager.OnThermalStatusChangedListener? = null

    @Volatile
    var currentState: ThermalState = ThermalState(ThermalLevel.NORMAL, emptyList())
        private set

    /**
     * Inicia o monitoramento de status térmico do dispositivo.
     */
    fun startMonitoring(
        executor: Executor = context.mainExecutor,
        callback: (ThermalState) -> Unit
    ) {
        listeners.add(callback)

        // Dispara o estado atual imediatamente para o novo listener
        val initialStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                powerManager.currentThermalStatus
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao obter status termico inicial: ${e.message}")
                PowerManager.THERMAL_STATUS_NONE
            }
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }
        val initialState = mapStatusToState(initialStatus)
        currentState = initialState
        callback(initialState)

        if (systemListener == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                val state = mapStatusToState(status)
                currentState = state
                Log.i(TAG, "Status termico alterado: $status -> level=${state.level}, actions=${state.actions}")
                for (cb in listeners) {
                    cb(state)
                }
            }
            try {
                powerManager.addThermalStatusListener(executor, listener)
                systemListener = listener
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao registrar listener termico no PowerManager: ${e.message}", e)
            }
        }
    }

    /**
     * Remove um callback ou encerra o monitoramento térmico do sistema.
     */
    fun stopMonitoring(callback: ((ThermalState) -> Unit)? = null) {
        if (callback != null) {
            listeners.remove(callback)
        } else {
            listeners.clear()
        }

        if (listeners.isEmpty() && systemListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && powerManager != null) {
            try {
                powerManager.removeThermalStatusListener(systemListener!!)
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao remover listener termico: ${e.message}")
            }
            systemListener = null
        }
    }

    /**
     * Permite simular ou forçar um status térmico para fins de depuração/testes (ex.: broadcast ADB).
     */
    fun simulateThermalStatus(status: Int) {
        val state = mapStatusToState(status)
        currentState = state
        Log.i(TAG, "Simulando status termico: $status -> level=${state.level}, actions=${state.actions}")
        for (cb in listeners) {
            cb(state)
        }
    }
}
