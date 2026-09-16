package com.tucavr.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Testes unitários para [DiskSpaceManager] (Fase 0.4 Seção 4 / T4.6).
 * Valida o cálculo de buffer de segurança de 2 GB, verificação de espaço livre e formatação de bytes.
 */
class DiskSpaceManagerTest {

    private val twoGb = 2_000_000_000L
    private val dummyDir = File("/tmp")

    @Test
    fun `hasSufficientSpace returns true when available exceeds required plus safety buffer`() {
        val manager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 5L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )
        // 2 GB arquivo + 2 GB buffer = 4 GB necessários <= 5 GB disponíveis
        assertTrue(manager.hasSufficientSpace(dummyDir, 2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `hasSufficientSpace returns false when available is less than required plus safety buffer`() {
        val manager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 3L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )
        // 2 GB arquivo + 2 GB buffer = 4 GB necessários > 3 GB disponíveis
        assertFalse(manager.hasSufficientSpace(dummyDir, 2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `hasSufficientSpace returns false when available is below safety buffer even for small file`() {
        val manager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 1L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )
        // Apenas 1 GB disponível, menor que o buffer mínimo de 2 GB
        assertFalse(manager.hasSufficientSpace(dummyDir, 1024L))
    }

    @Test
    fun `isLowStorage returns true when available is below warning threshold`() {
        val manager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = twoGb - 1, totalBytes = 64L * 1024 * 1024 * 1024) }
        )
        assertTrue(manager.isLowStorage(dummyDir))
    }

    @Test
    fun `isLowStorage returns false when available is above warning threshold`() {
        val manager = DiskSpaceManager(
            spaceProvider = { DiskSpaceInfo(availableBytes = 10L * 1024 * 1024 * 1024, totalBytes = 64L * 1024 * 1024 * 1024) }
        )
        assertFalse(manager.isLowStorage(dummyDir))
    }

    @Test
    fun `formatBytes formats sizes into readable units`() {
        assertEquals("0.0 MB", DiskSpaceManager.formatBytes(500L))
        assertEquals("10.0 MB", DiskSpaceManager.formatBytes(10L * 1024 * 1024))
        assertEquals("4.0 GB", DiskSpaceManager.formatBytes(4L * 1024 * 1024 * 1024))
    }
}
