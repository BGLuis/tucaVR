package com.tucavr.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenFormatCatalogTest {

    @Test
    fun testEntriesCoverAllIndicesSequentially() {
        assertEquals("Deve conter exatamente 10 modos", 10, ScreenFormatCatalog.entries.size)
        ScreenFormatCatalog.entries.forEachIndexed { expectedIndex, entry ->
            assertEquals("O índice na posição $expectedIndex deve ser $expectedIndex", expectedIndex, entry.index)
        }
    }

    @Test
    fun testGroupPartitioning() {
        val flat = ScreenFormatCatalog.getByGroup(ScreenFormatGroup.FLAT)
        val s360 = ScreenFormatCatalog.getByGroup(ScreenFormatGroup.SPHERICAL_360)
        val s180 = ScreenFormatCatalog.getByGroup(ScreenFormatGroup.SPHERICAL_180)

        assertEquals("Grupo Plano deve ter 5 modos (2D, SBS, SBS-half, OU, OU-half)", 5, flat.size)
        assertEquals("Grupo 360 deve ter 3 modos (360 mono, 360 SBS, 360 OU)", 3, s360.size)
        assertEquals("Grupo 180 deve ter 2 modos (180 mono, 180 SBS)", 2, s180.size)

        val totalGrouped = flat.size + s360.size + s180.size
        assertEquals("A soma de todos os grupos deve ser igual a 10", 10, totalGrouped)

        val allIndices = (flat + s360 + s180).map { it.index }.toSet()
        assertEquals("A união de todos os grupos deve conter todos os índices de 0 a 9", (0..9).toSet(), allIndices)
    }

    @Test
    fun testAllIconsAndLabelsAreValid() {
        ScreenFormatCatalog.entries.forEach { entry ->
            assertNotEquals("Modo ${entry.index} deve ter um labelResId válido", 0, entry.labelResId)
            assertNotEquals("Modo ${entry.index} deve ter um iconResId válido", 0, entry.iconResId)
        }
    }

    @Test
    fun testGetAndFallback() {
        assertEquals(0, ScreenFormatCatalog.get(0).index)
        assertEquals(9, ScreenFormatCatalog.get(9).index)
        // Fallback seguro para índices fora dos limites
        assertEquals(0, ScreenFormatCatalog.get(-1).index)
        assertEquals(0, ScreenFormatCatalog.get(100).index)
    }

    @Test
    fun testIsSpherical() {
        (0..4).forEach { mode ->
            assertTrue("Modo $mode deve ser plano", !ScreenFormatCatalog.isSpherical(mode))
        }
        (5..9).forEach { mode ->
            assertTrue("Modo $mode deve ser esférico", ScreenFormatCatalog.isSpherical(mode))
        }
    }
}
