package com.tucavr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScreenTransformStoreTest {
    @Test
    fun testDefaultsMatchCinematicStandard() {
        assertEquals(0.0f, ScreenTransformStore.DEFAULT_POS_X, 0.001f)
        assertEquals(1.5f, ScreenTransformStore.DEFAULT_POS_Y, 0.001f)
        assertEquals(-2.4f, ScreenTransformStore.DEFAULT_POS_Z, 0.001f)
        assertEquals(2.8f, ScreenTransformStore.DEFAULT_SCALE_X, 0.001f)
        assertEquals(1.575f, ScreenTransformStore.DEFAULT_SCALE_Y, 0.001f)

        // Proporção exata 16:9
        assertEquals(16.0f / 9.0f, ScreenTransformStore.DEFAULT_SCALE_X / ScreenTransformStore.DEFAULT_SCALE_Y, 0.001f)
    }

    @Test
    fun testClampingLimitsSafety() {
        assertTrue(ScreenTransformStore.MIN_SCALE_X >= 0.5f)
        assertTrue(ScreenTransformStore.MAX_SCALE_X <= 10.0f)

        assertTrue(ScreenTransformStore.MIN_POS_Z < ScreenTransformStore.MAX_POS_Z)
        assertTrue(ScreenTransformStore.MAX_POS_Z <= -0.5f) // Nunca encostar no rosto do usuário
        assertTrue(ScreenTransformStore.MIN_POS_Y >= 0.2f) // Nunca afundar abaixo do chão
    }

    @Test
    fun testUncustomizedEnvironmentsReturnDefaults() {
        val prefs = FakeSharedPreferences()
        val store = ScreenTransformStore(prefs)

        val envs =
            listOf(
                EnvironmentStore.ENV_VOID,
                EnvironmentStore.ENV_CINEMA,
                EnvironmentStore.ENV_LIVING_ROOM,
                EnvironmentStore.ENV_SPACE,
                EnvironmentStore.ENV_PASSTHROUGH,
            )

        for (envId in envs) {
            val transform = store.get(envId)
            val expectedDefault = ScreenTransformStore.getDefaultTransform(envId)
            assertFalse("Ambiente $envId não deve estar customizado inicialmente", transform.isCustomized)
            assertEquals(expectedDefault.posX, transform.posX, 0.001f)
            assertEquals(expectedDefault.posY, transform.posY, 0.001f)
            assertEquals(expectedDefault.posZ, transform.posZ, 0.001f)
            assertEquals(expectedDefault.scaleX, transform.scaleX, 0.001f)
            assertEquals(expectedDefault.scaleY, transform.scaleY, 0.001f)
        }
    }

    @Test
    fun testCustomSavePerEnvironmentPreservedAcrossSwitches() {
        val prefs = FakeSharedPreferences()
        val store = ScreenTransformStore(prefs)

        // 1. Space começa com os valores padrão
        val initialSpace = store.get(EnvironmentStore.ENV_SPACE)
        assertFalse(initialSpace.isCustomized)
        assertEquals(0.0f, initialSpace.posX, 0.001f)
        assertEquals(1.8f, initialSpace.posY, 0.001f)
        assertEquals(-3.2f, initialSpace.posZ, 0.001f)

        // 2. Usuário move a tela em Space
        store.save(
            environmentId = EnvironmentStore.ENV_SPACE,
            posX = 0.5f,
            posY = 2.0f,
            posZ = -3.5f,
            scaleX = 4.0f,
            scaleY = 2.25f,
        )

        // 3. Usuário troca para Cinema (ambiente nunca customizado antes)
        val cinema = store.get(EnvironmentStore.ENV_CINEMA)
        assertFalse("Cinema não foi customizado e deve retornar isCustomized=false", cinema.isCustomized)
        assertEquals(0.0f, cinema.posX, 0.001f)
        assertEquals(2.5f, cinema.posY, 0.001f)
        assertEquals(-7.5f, cinema.posZ, 0.001f)
        assertEquals(6.0f, cinema.scaleX, 0.001f)
        assertEquals(3.375f, cinema.scaleY, 0.001f)

        // 4. Usuário troca de volta para Space -> customização deve estar preservada!
        val preservedSpace = store.get(EnvironmentStore.ENV_SPACE)
        assertTrue("Space deve manter isCustomized=true", preservedSpace.isCustomized)
        assertEquals(0.5f, preservedSpace.posX, 0.001f)
        assertEquals(2.0f, preservedSpace.posY, 0.001f)
        assertEquals(-3.5f, preservedSpace.posZ, 0.001f)
        assertEquals(4.0f, preservedSpace.scaleX, 0.001f)
        assertEquals(2.25f, preservedSpace.scaleY, 0.001f)
    }

    @Test
    fun testResetEnvironmentRestoresDefaultWithoutAffectingOtherEnvironments() {
        val prefs = FakeSharedPreferences()
        val store = ScreenTransformStore(prefs)

        // Customiza Space e Cinema
        store.save(EnvironmentStore.ENV_SPACE, 0.5f, 2.0f, -3.5f, 4.0f, 2.25f)
        store.save(EnvironmentStore.ENV_CINEMA, 0.2f, 2.7f, -8.0f, 6.4f, 3.6f)

        assertTrue(store.get(EnvironmentStore.ENV_SPACE).isCustomized)
        assertTrue(store.get(EnvironmentStore.ENV_CINEMA).isCustomized)

        // Reseta apenas Space
        store.reset(EnvironmentStore.ENV_SPACE)

        val resetSpace = store.get(EnvironmentStore.ENV_SPACE)
        assertFalse("Space deve voltar a isCustomized=false após reset", resetSpace.isCustomized)
        val defaultSpace = ScreenTransformStore.getDefaultTransform(EnvironmentStore.ENV_SPACE)
        assertEquals(defaultSpace.posX, resetSpace.posX, 0.001f)
        assertEquals(defaultSpace.posY, resetSpace.posY, 0.001f)
        assertEquals(defaultSpace.posZ, resetSpace.posZ, 0.001f)

        // Cinema deve continuar customizado
        val cinema = store.get(EnvironmentStore.ENV_CINEMA)
        assertTrue("Cinema deve continuar customizado", cinema.isCustomized)
        assertEquals(0.2f, cinema.posX, 0.001f)
        assertEquals(2.7f, cinema.posY, 0.001f)
    }

    @Test
    fun testClampingLimitsOnSave() {
        val prefs = FakeSharedPreferences()
        val store = ScreenTransformStore(prefs)

        // Tenta salvar valores extremos fora dos limites de conforto:
        // posX < MIN_POS_X (-4.0f), posY > MAX_POS_Y (3.5f), posZ > MAX_POS_Z (-0.8f), scaleX > MAX_SCALE_X (8.0f)
        store.save(
            environmentId = EnvironmentStore.ENV_VOID,
            posX = -10.0f,
            posY = 5.0f,
            posZ = 0.0f,
            scaleX = 15.0f,
            scaleY = 0.0f,
        )

        val transform = store.get(EnvironmentStore.ENV_VOID)
        assertEquals(ScreenTransformStore.MIN_POS_X, transform.posX, 0.001f)
        assertEquals(ScreenTransformStore.MAX_POS_Y, transform.posY, 0.001f)
        assertEquals(ScreenTransformStore.MAX_POS_Z, transform.posZ, 0.001f)
        assertEquals(ScreenTransformStore.MAX_SCALE_X, transform.scaleX, 0.001f)
        assertEquals(ScreenTransformStore.MAX_SCALE_X * (9.0f / 16.0f), transform.scaleY, 0.001f)
    }

    @Test
    fun testLegacyKeyFallbackForDefaultEnv() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putFloat("screen_pos_x", 0.3f)
            .putFloat("screen_pos_y", 1.7f)
            .putFloat("screen_pos_z", -2.6f)
            .putFloat("screen_scale_x", 3.0f)
            .putFloat("screen_scale_y", 1.6875f)
            .putBoolean("screen_is_customized", true)
            .apply()

        val store = ScreenTransformStore(prefs)
        val transform = store.get(EnvironmentStore.ENV_VOID)

        assertTrue("Deve recuperar a customização através das chaves legadas", transform.isCustomized)
        assertEquals(0.3f, transform.posX, 0.001f)
        assertEquals(1.7f, transform.posY, 0.001f)
        assertEquals(-2.6f, transform.posZ, 0.001f)
    }

    @Test
    fun testDefaultsMatchConfigIniFiles() {
        val envsDir = resolveAssetsEnvironmentsDir()

        val testEnvs = listOf("void", "cinema", "living_room", "space")
        for (envId in testEnvs) {
            val iniFile = File(envsDir, "$envId/config.ini")
            assertTrue("Arquivo config.ini deve existir: ${iniFile.absolutePath}", iniFile.isFile)

            val lines = iniFile.readLines()
            val screenPosStr =
                lines.find { it.startsWith("screen_pos=") || it.startsWith("screen_pos =") }
                    ?.substringAfter("=")?.trim()
            val screenScaleStr =
                lines.find { it.startsWith("screen_scale=") || it.startsWith("screen_scale =") }
                    ?.substringAfter("=")?.trim()

            assertTrue("screen_pos deve estar presente no config.ini de $envId", !screenPosStr.isNullOrBlank())
            assertTrue("screen_scale deve estar presente no config.ini de $envId", !screenScaleStr.isNullOrBlank())

            val posTokens = screenPosStr!!.split(",").map { it.trim().toFloat() }
            val scaleTokens = screenScaleStr!!.split(",").map { it.trim().toFloat() }

            val defaultTransform = ScreenTransformStore.getDefaultTransform(envId)

            assertEquals("posX em $envId", posTokens[0], defaultTransform.posX, 0.001f)
            assertEquals("posY em $envId", posTokens[1], defaultTransform.posY, 0.001f)
            assertEquals("posZ em $envId", posTokens[2], defaultTransform.posZ, 0.001f)
            assertEquals("scaleX em $envId", scaleTokens[0], defaultTransform.scaleX, 0.001f)
            assertEquals("scaleY em $envId", scaleTokens[1], defaultTransform.scaleY, 0.001f)
        }
    }

    private fun resolveAssetsEnvironmentsDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidateApp = File(userDir, "src/main/assets/environments")
        if (candidateApp.isDirectory) return candidateApp
        val candidateRoot = File(userDir, "app/src/main/assets/environments")
        if (candidateRoot.isDirectory) return candidateRoot
        error("Diretório de assets de environments não encontrado a partir de: ${userDir.absolutePath}")
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        override fun getAll(): Map<String, *> = HashMap(data)

        override fun getString(
            key: String?,
            defValue: String?,
        ): String? = data[key]?.toString() ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = (data[key] as? Set<String>)?.toMutableSet() ?: defValues

        override fun getInt(
            key: String?,
            defValue: Int,
        ): Int = (data[key] as? Number)?.toInt() ?: defValue

        override fun getLong(
            key: String?,
            defValue: Long,
        ): Long = (data[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(
            key: String?,
            defValue: Float,
        ): Float = (data[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(
            key: String?,
            defValue: Boolean,
        ): Boolean = (data[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        }

        private class FakeEditor(
            private val prefs: FakeSharedPreferences,
        ) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clearPending = false

            override fun putString(
                key: String?,
                value: String?,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(
                key: String?,
                values: MutableSet<String>?,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = values?.toSet()
                return this
            }

            override fun putInt(
                key: String?,
                value: Int,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(
                key: String?,
                value: Long,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(
                key: String?,
                value: Float,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(
                key: String?,
                value: Boolean,
            ): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = REMOVED
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearPending = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearPending) {
                    prefs.data.clear()
                }
                for ((k, v) in pending) {
                    if (v === REMOVED || v == null) {
                        prefs.data.remove(k)
                    } else {
                        prefs.data[k] = v
                    }
                }
            }

            companion object {
                private val REMOVED = Any()
            }
        }
    }
}
