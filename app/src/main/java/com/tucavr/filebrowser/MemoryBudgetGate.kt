package com.tucavr.filebrowser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gate adaptativo de orçamento de memória com barging limitado por prazo (anti-starvation).
 *
 * Controla a concorrência de decodificação de thumbnails de vídeo de acordo com o
 * custo de memória nativa estimado (f(largura, altura, bits) * K).
 *
 * @param totalBudgetBytes Orçamento total em bytes (padrão: 900 MiB).
 * @param starvationDeadlineMs Tempo limite de espera da cabeça da fila antes de travar barging (padrão: 2000 ms).
 * @param clock Provedor de timestamp em milissegundos para facilitar testes determinísticos na JVM.
 */
class MemoryBudgetGate(
    totalBudgetBytes: Long = DEFAULT_BUDGET_BYTES,
    private val starvationDeadlineMs: Long = DEFAULT_STARVATION_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val DEFAULT_BUDGET_BYTES = 900L * 1024L * 1024L // 900 MiB
        const val DEFAULT_STARVATION_DEADLINE_MS = 2000L // 2 segundos
        const val DEFAULT_K_MULTIPLIER = 10.0

        /**
         * Calcula o custo estimado de memória nativa para decodificação de um frame de vídeo.
         * Cobre o frame descompactado + buffer de referência (DPB) com decoder configurado para 1 thread.
         */
        fun calculateCostBytes(
            width: Int,
            height: Int,
            bitDepth: Int = 8,
            kMultiplier: Double = DEFAULT_K_MULTIPLIER
        ): Long {
            if (width <= 0 || height <= 0) {
                // Fallback seguro (1080p) se a resolução for desconhecida
                return (1920L * 1080L * 1.5 * kMultiplier).toLong()
            }
            val bytesPerPixel = if (bitDepth > 8) 3.0 else 1.5
            val frameBytes = (width.toLong() * height.toLong() * bytesPerPixel)
            return (frameBytes * kMultiplier).toLong()
        }
    }

    private val lock = Mutex()
    private var _totalBudget: Long = totalBudgetBytes.coerceAtLeast(1L)
    private var _usedBudget: Long = 0L
    private val waitQueue = mutableListOf<PendingRequest>()

    val totalBudgetBytes: Long get() = _totalBudget
    val usedBudgetBytes: Long get() = _usedBudget
    val availableBudgetBytes: Long get() = (_totalBudget - _usedBudget).coerceAtLeast(0L)
    val waitingCount: Int get() = waitQueue.size

    private class PendingRequest(
        val requestedBytes: Long,
        val queuedAt: Long,
        val deferred: CompletableDeferred<Unit> = CompletableDeferred()
    )

    /**
     * Atualiza o orçamento total, útil para responder a eventos de trimMemory do Android.
     */
    suspend fun setTotalBudget(newBudgetBytes: Long) {
        lock.withLock {
            _totalBudget = newBudgetBytes.coerceAtLeast(1L)
            dispatchWaiters()
        }
    }

    /**
     * Executa o bloco sob a cota de memória solicitada.
     * Clampa pedidos maiores que o orçamento total para evitar deadlock.
     */
    suspend fun <T> withBudget(requestedBytes: Long, block: suspend () -> T): T {
        val effectiveRequested = requestedBytes.coerceIn(1L, _totalBudget)
        acquire(effectiveRequested)
        return try {
            block()
        } finally {
            release(effectiveRequested)
        }
    }

    private suspend fun acquire(requested: Long) {
        val deferred: CompletableDeferred<Unit>? = lock.withLock {
            val now = clock()
            if (waitQueue.isEmpty() && _usedBudget + requested <= _totalBudget) {
                _usedBudget += requested
                null
            } else {
                val req = PendingRequest(requested, now)
                waitQueue.add(req)
                dispatchWaiters()
                if (req.deferred.isCompleted) {
                    null
                } else {
                    req.deferred
                }
            }
        }

        if (deferred != null) {
            try {
                deferred.await()
            } catch (e: Throwable) {
                lock.withLock {
                    val removed = waitQueue.removeAll { it.deferred === deferred }
                    if (!removed && deferred.isCompleted) {
                        // Já havia sido aprovado antes do cancelamento; devolve a cota
                        _usedBudget = (_usedBudget - requested).coerceAtLeast(0L)
                    }
                    dispatchWaiters()
                }
                throw e
            }
        }
    }

    private suspend fun release(requested: Long) {
        lock.withLock {
            _usedBudget = (_usedBudget - requested).coerceAtLeast(0L)
            dispatchWaiters()
        }
    }

    /**
     * Avalia a fila de espera e concede permits de acordo com a política de barging e anti-starvation.
     * Deve ser chamado sempre dentro de `lock.withLock`.
     */
    private fun dispatchWaiters() {
        val now = clock()

        while (waitQueue.isNotEmpty()) {
            val head = waitQueue.first()
            val headStarved = (now - head.queuedAt >= starvationDeadlineMs)

            if (headStarved) {
                // Cabeça expirou o prazo: modo estrito de anti-starvation.
                // Somente a cabeça pode ser admitida, assim que houver orçamento suficiente para ela.
                if (_usedBudget + head.requestedBytes <= _totalBudget) {
                    waitQueue.removeAt(0)
                    _usedBudget += head.requestedBytes
                    head.deferred.complete(Unit)
                } else {
                    // Cabeça ainda não cabe; ninguém mais pode furar a fila.
                    break
                }
            } else {
                // Prazo não venceu: barging permitido para qualquer requisição que caiba.
                var admittedIndex = -1
                for (i in waitQueue.indices) {
                    val item = waitQueue[i]
                    if (_usedBudget + item.requestedBytes <= _totalBudget) {
                        admittedIndex = i
                        break
                    }
                }

                if (admittedIndex != -1) {
                    val item = waitQueue.removeAt(admittedIndex)
                    _usedBudget += item.requestedBytes
                    item.deferred.complete(Unit)
                } else {
                    // Nenhum item cabe no momento
                    break
                }
            }
        }
    }
}
