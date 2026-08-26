package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Especificação executável de invariantes para [FeatureFlags.Flag].
 * Garante unicidade de armazenamento em SharedPreferences e configurações
 * de segurança por padrão para o Quest 3.
 */
class FeatureFlagsTest {

    @Test
    fun `all feature flag keys are unique and non-empty`() {
        val flags = FeatureFlags.Flag.values()
        val keys = flags.map { it.key }

        // Invariante 1: nenhuma chave pode ser vazia
        assertTrue("Nenhuma chave de flag pode ser vazia", keys.all { it.isNotBlank() })

        // Invariante 2: chaves devem ser únicas para não colidir em SharedPreferences
        assertEquals(
            "Detectada colisão de chaves entre flags: ${keys.groupBy { it }.filter { it.value.size > 1 }.keys}",
            keys.toSet().size,
            keys.size
        )
    }

    @Test
    fun `all feature flag keys conform to snake_case naming`() {
        val snakeCaseRegex = Regex("^[a-z]+(_[a-z]+)*$")
        for (flag in FeatureFlags.Flag.values()) {
            assertTrue(
                "A chave '${flag.key}' da flag ${flag.name} deve seguir o padrão snake_case",
                flag.key.matches(snakeCaseRegex)
            )
        }
    }

    @Test
    fun `safety-critical flags have correct defaults for VR performance and safety`() {
        // Scrub preview gera picos de frame time ao arrastar seekbar em 8K, deve iniciar desabilitado
        org.junit.Assert.assertFalse("SCRUB_PREVIEW deve iniciar desabilitado por segurança de performance", FeatureFlags.Flag.SCRUB_PREVIEW.defaultEnabled)

        // Foveated rendering experimental, deve iniciar desabilitado
        org.junit.Assert.assertFalse("FOVEATED_RENDERING deve iniciar desabilitado", FeatureFlags.Flag.FOVEATED_RENDERING.defaultEnabled)

        // Pause on exit deve ser habilitado para pausar ao sair pro menu Meta / passthrough
        assertTrue("PAUSE_ON_EXIT deve vir habilitado por padrão", FeatureFlags.Flag.PAUSE_ON_EXIT.defaultEnabled)
    }
}
