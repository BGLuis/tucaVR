package com.tucavr.filebrowser

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MemoryBudgetGateTest {

    @Test
    fun testCostCalculation() {
        val cost8k = MemoryBudgetGate.calculateCostBytes(7680, 3840, 8, 10.0)
        assertEquals(442_368_000L, cost8k) // ~421.8 MiB

        val cost4k = MemoryBudgetGate.calculateCostBytes(3840, 2160, 8, 10.0)
        assertEquals(124_416_000L, cost4k) // ~118.6 MiB

        val cost1080p = MemoryBudgetGate.calculateCostBytes(1920, 1080, 8, 10.0)
        assertEquals(31_104_000L, cost1080p) // ~29.6 MiB

        // 10 bits dobra os bytes por pixel em relação a 8 bits (3.0 vs 1.5)
        val cost1080p10bit = MemoryBudgetGate.calculateCostBytes(1920, 1080, 10, 10.0)
        assertEquals(cost1080p * 2, cost1080p10bit)

        // Dimensões zeradas usam fallback de 1080p
        val fallback = MemoryBudgetGate.calculateCostBytes(0, 0, 8, 10.0)
        assertEquals(cost1080p, fallback)
    }

    @Test
    fun testSingleJobAcquireAndRelease() = runBlocking {
        val gate = MemoryBudgetGate(totalBudgetBytes = 1000L)
        assertEquals(1000L, gate.availableBudgetBytes)
        assertEquals(0L, gate.usedBudgetBytes)

        val result = gate.withBudget(400L) {
            assertEquals(600L, gate.availableBudgetBytes)
            assertEquals(400L, gate.usedBudgetBytes)
            "sucesso"
        }

        assertEquals("sucesso", result)
        assertEquals(1000L, gate.availableBudgetBytes)
        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testMultipleConcurrentJobsWithinBudget() = runBlocking {
        // Orçamento de 900 MiB: 1x 8K (~422 MB) + 3x 4K (~118 MB cada = ~356 MB) = ~778 MB <= 900 MB
        val budget = 900L * 1024L * 1024L
        val gate = MemoryBudgetGate(totalBudgetBytes = budget)

        val cost8k = MemoryBudgetGate.calculateCostBytes(7680, 3840, 8, 10.0)
        val cost4k = MemoryBudgetGate.calculateCostBytes(3840, 2160, 8, 10.0)

        val activeCount = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val gateReleaseTrigger = CompletableDeferred<Unit>()

        val jobs = listOf(
            async(Dispatchers.Default) {
                gate.withBudget(cost8k) {
                    val count = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, count) }
                    gateReleaseTrigger.await()
                    activeCount.decrementAndGet()
                }
            },
            async(Dispatchers.Default) {
                gate.withBudget(cost4k) {
                    val count = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, count) }
                    gateReleaseTrigger.await()
                    activeCount.decrementAndGet()
                }
            },
            async(Dispatchers.Default) {
                gate.withBudget(cost4k) {
                    val count = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, count) }
                    gateReleaseTrigger.await()
                    activeCount.decrementAndGet()
                }
            },
            async(Dispatchers.Default) {
                gate.withBudget(cost4k) {
                    val count = activeCount.incrementAndGet()
                    maxConcurrent.updateAndGet { maxOf(it, count) }
                    gateReleaseTrigger.await()
                    activeCount.decrementAndGet()
                }
            }
        )

        // Aguarda todos os 4 jobs estarem executando concorrentemente
        while (activeCount.get() < 4) {
            delay(10)
        }

        assertEquals(4, maxConcurrent.get())
        gateReleaseTrigger.complete(Unit)
        jobs.awaitAll()

        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testOversizedJobClampedAndRunsWithoutDeadlock() = runBlocking {
        val gate = MemoryBudgetGate(totalBudgetBytes = 500L)

        // Pedido de 1200L em orçamento de 500L é clampado para 500L e roda com exclusividade
        val executed = AtomicBoolean(false)
        gate.withBudget(1200L) {
            assertEquals(500L, gate.usedBudgetBytes)
            assertEquals(0L, gate.availableBudgetBytes)
            executed.set(true)
        }

        assertTrue(executed.get())
        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testBargingBeforeStarvationDeadline() = runBlocking {
        var currentTime = 0L
        val gate = MemoryBudgetGate(
            totalBudgetBytes = 1000L,
            starvationDeadlineMs = 2000L,
            clock = { currentTime }
        )

        val jobAStarted = CompletableDeferred<Unit>()
        val jobAFinish = CompletableDeferred<Unit>()

        // Job A consome 700L (sobram 300L)
        val jobA = async(Dispatchers.Default) {
            gate.withBudget(700L) {
                jobAStarted.complete(Unit)
                jobAFinish.await()
            }
        }

        jobAStarted.await()
        assertEquals(700L, gate.usedBudgetBytes)

        // Job B pede 600L (não cabe, vai para a cabeça da fila de espera em t=0)
        currentTime = 0L
        val jobBStarted = AtomicBoolean(false)
        val jobB = async(Dispatchers.Default) {
            gate.withBudget(600L) {
                jobBStarted.set(true)
            }
        }

        // Dá tempo para B enfileirar
        delay(50)
        assertEquals(1, gate.waitingCount)
        assertFalse(jobBStarted.get())

        // Em t=500ms (< 2000ms prazo), Job C pede 200L (cabe nos 300L disponíveis)
        currentTime = 500L
        val jobCExecuted = AtomicBoolean(false)
        gate.withBudget(200L) {
            // Job C fura a fila (barging) com sucesso!
            jobCExecuted.set(true)
            assertEquals(900L, gate.usedBudgetBytes)
        }

        assertTrue(jobCExecuted.get())
        assertFalse(jobBStarted.get())

        // Libera Job A
        jobAFinish.complete(Unit)
        jobA.await()

        // Job B agora consegue executar
        jobB.await()
        assertTrue(jobBStarted.get())
        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testAntiStarvationBlocksBargingAfterDeadline() = runBlocking {
        var currentTime = 0L
        val gate = MemoryBudgetGate(
            totalBudgetBytes = 1000L,
            starvationDeadlineMs = 2000L,
            clock = { currentTime }
        )

        val jobAStarted = CompletableDeferred<Unit>()
        val jobAFinish = CompletableDeferred<Unit>()

        // Job A consome 700L (restam 300L)
        val jobA = async(Dispatchers.Default) {
            gate.withBudget(700L) {
                jobAStarted.complete(Unit)
                jobAFinish.await()
            }
        }

        jobAStarted.await()

        // Job B (cabeça) pede 600L em t=0 e enfileira
        currentTime = 0L
        val jobBStarted = AtomicBoolean(false)
        val jobB = async(Dispatchers.Default) {
            gate.withBudget(600L) {
                jobBStarted.set(true)
            }
        }

        delay(50)
        assertEquals(1, gate.waitingCount)

        // Relógio avança para t=2500ms (>= 2000ms deadline da cabeça)
        currentTime = 2500L

        // Job C chega pedindo 200L (que caberia se barging fosse permitido, mas agora está BLOQUEADO)
        val jobCStarted = AtomicBoolean(false)
        val jobC = async(Dispatchers.Default) {
            gate.withBudget(200L) {
                jobCStarted.set(true)
            }
        }

        delay(50)
        // Ambos na fila de espera, Job C NÃO furou a fila
        assertEquals(2, gate.waitingCount)
        assertFalse(jobBStarted.get())
        assertFalse(jobCStarted.get())

        // Libera Job A
        jobAFinish.complete(Unit)
        jobA.await()

        // Job B deve terminar primeiro
        jobB.await()
        assertTrue(jobBStarted.get())

        // Em seguida Job C executa
        jobC.await()
        assertTrue(jobCStarted.get())

        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testCancellationReleasesBudgetAndWakesWaiters() = runBlocking {
        val gate = MemoryBudgetGate(totalBudgetBytes = 500L)

        val jobAStarted = CompletableDeferred<Unit>()
        val jobAFinish = CompletableDeferred<Unit>()

        val jobA = launch(Dispatchers.Default) {
            gate.withBudget(500L) {
                jobAStarted.complete(Unit)
                jobAFinish.await()
            }
        }

        jobAStarted.await()

        val jobBStarted = AtomicBoolean(false)
        val jobB = async(Dispatchers.Default) {
            gate.withBudget(500L) {
                jobBStarted.set(true)
            }
        }

        delay(50)
        assertEquals(1, gate.waitingCount)

        // Cancela Job A
        jobA.cancel()

        // Job B deve ser acordado e rodar
        jobB.await()
        assertTrue(jobBStarted.get())
        assertEquals(0L, gate.usedBudgetBytes)
    }

    @Test
    fun testExceptionSafetyReleasesBudget() = runBlocking {
        val gate = MemoryBudgetGate(totalBudgetBytes = 500L)

        try {
            gate.withBudget(400L) {
                throw IllegalStateException("Erro simulado")
            }
        } catch (e: IllegalStateException) {
            // Esperado
        }

        assertEquals(0L, gate.usedBudgetBytes)
        assertEquals(500L, gate.availableBudgetBytes)
    }

    @Test
    fun testConcurrencyStress() = runBlocking {
        val gate = MemoryBudgetGate(totalBudgetBytes = 1000L)
        val counter = AtomicInteger(0)

        val jobs = (1..50).map { i ->
            async(Dispatchers.Default) {
                val cost = (50L + (i % 5) * 100L) // 50 a 450
                gate.withBudget(cost) {
                    delay(5)
                    counter.incrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertEquals(50, counter.get())
        assertEquals(0L, gate.usedBudgetBytes)
    }
}
