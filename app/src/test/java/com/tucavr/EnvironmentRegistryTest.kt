package com.tucavr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class EnvironmentRegistryTest {
    @Before
    fun setUp() {
        EnvironmentRegistry.resetToDefaults()
    }

    @Test
    fun testParseConfigIniFromString() {
        val iniContent =
            """
            # Sample Environment Config
            id = custom_env
            name = Custom Environment
            screen_pos = 0.5, 2.0, -3.5
            screen_scale = 4.0, 2.25
            screen_locked = true
            model_file = custom/model.glb
            skybox_file = custom/skybox.png
            ambient_audio = custom/ambient.ogg
            ambient_volume = 0.45
            particles_enabled = true
            spawn_pos = 1.0, 0.5, -2.0
            """.trimIndent()

        val def = EnvironmentRegistry.parseConfig(ByteArrayInputStream(iniContent.toByteArray(Charsets.UTF_8)))

        assertEquals("custom_env", def.id)
        assertEquals("Custom Environment", def.name)
        assertEquals(0.5f, def.screenPosX, 0.001f)
        assertEquals(2.0f, def.screenPosY, 0.001f)
        assertEquals(-3.5f, def.screenPosZ, 0.001f)
        assertEquals(4.0f, def.screenScaleX, 0.001f)
        assertEquals(2.25f, def.screenScaleY, 0.001f)
        assertTrue(def.screenLocked)
        assertEquals("custom/model.glb", def.modelFile)
        assertEquals("custom/skybox.png", def.skyboxFile)
        assertEquals("custom/ambient.ogg", def.ambientAudio)
        assertEquals(0.45f, def.ambientVolume, 0.001f)
        assertTrue(def.particlesEnabled)
        assertEquals(1.0f, def.spawnPosX, 0.001f)
        assertEquals(0.5f, def.spawnPosY, 0.001f)
        assertEquals(-2.0f, def.spawnPosZ, 0.001f)
    }

    @Test
    fun testParseConfigIniDefaults() {
        val emptyContent = "# Empty config\n"
        val def = EnvironmentRegistry.parseConfig(ByteArrayInputStream(emptyContent.toByteArray(Charsets.UTF_8)))

        assertEquals("unknown", def.id)
        assertEquals("Unknown", def.name)
        assertEquals(0.0f, def.screenPosX, 0.001f)
        assertEquals(1.5f, def.screenPosY, 0.001f)
        assertEquals(-2.4f, def.screenPosZ, 0.001f)
        assertEquals(2.8f, def.screenScaleX, 0.001f)
        assertEquals(1.575f, def.screenScaleY, 0.001f)
        assertFalse(def.screenLocked)
        assertEquals(null, def.modelFile)
        assertEquals(null, def.skyboxFile)
        assertEquals(null, def.ambientAudio)
        assertEquals(0.0f, def.ambientVolume, 0.001f)
        assertFalse(def.particlesEnabled)
        assertEquals(0.0f, def.spawnPosX, 0.001f)
        assertEquals(0.0f, def.spawnPosY, 0.001f)
        assertEquals(0.0f, def.spawnPosZ, 0.001f)
    }

    @Test
    fun testParseBooleanVariations() {
        val trueCases = listOf("true", "TRUE", "1", "yes", "YES")
        for (v in trueCases) {
            val ini = "screen_locked = $v\nparticles_enabled = $v"
            val def = EnvironmentRegistry.parseConfig(ByteArrayInputStream(ini.toByteArray(Charsets.UTF_8)))
            assertTrue("Expected true for value '$v'", def.screenLocked)
            assertTrue("Expected true for particles '$v'", def.particlesEnabled)
        }

        val falseCases = listOf("false", "FALSE", "0", "no", "NO", "other")
        for (v in falseCases) {
            val ini = "screen_locked = $v\nparticles_enabled = $v"
            val def = EnvironmentRegistry.parseConfig(ByteArrayInputStream(ini.toByteArray(Charsets.UTF_8)))
            assertFalse("Expected false for value '$v'", def.screenLocked)
            assertFalse("Expected false for particles '$v'", def.particlesEnabled)
        }
    }

    @Test
    fun testDefaultEnvironmentsPresent() {
        assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_VOID))
        assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_SPACE))
        assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_CINEMA))
        assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_LIVING_ROOM))
        assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_PASSTHROUGH))

        val space = EnvironmentRegistry.get(EnvironmentStore.ENV_SPACE)
        assertNotNull(space)
        assertEquals("space", space?.id)
        assertEquals(0.25f, space?.ambientVolume ?: 0f, 0.001f)
        assertTrue(space?.particlesEnabled == true)

        val cinema = EnvironmentRegistry.get(EnvironmentStore.ENV_CINEMA)
        assertNotNull(cinema)
        assertEquals(0.15f, cinema?.ambientVolume ?: 0f, 0.001f)
    }

    @Test
    fun testPassthroughDefinition() {
        val pt = EnvironmentRegistry.get(EnvironmentStore.ENV_PASSTHROUGH)
        assertNotNull(pt)
        assertEquals(EnvironmentStore.ENV_PASSTHROUGH, pt?.id)
        assertEquals(0.0f, pt?.ambientVolume ?: 1f, 0.001f)
        assertFalse(pt?.particlesEnabled ?: true)
    }

    @Test
    fun testLoadFromDirectoryMatchesAssets() {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidateApp = File(userDir, "src/main/assets/environments")
        val candidateRoot = File(userDir, "app/src/main/assets/environments")
        val envDir = if (candidateApp.isDirectory) candidateApp else candidateRoot

        if (envDir.isDirectory) {
            EnvironmentRegistry.loadFromDirectory(envDir)

            val voidEnv = EnvironmentRegistry.get("void")
            assertNotNull(voidEnv)
            assertEquals("void", voidEnv?.id)

            val spaceEnv = EnvironmentRegistry.get("space")
            assertNotNull(spaceEnv)
            assertEquals("space", spaceEnv?.id)
            assertEquals(0.25f, spaceEnv?.ambientVolume ?: 0f, 0.001f)
            assertTrue(spaceEnv?.particlesEnabled == true)

            val cinemaEnv = EnvironmentRegistry.get("cinema")
            assertNotNull(cinemaEnv)
            assertEquals("cinema", cinemaEnv?.id)
            assertEquals(0.15f, cinemaEnv?.ambientVolume ?: 0f, 0.001f)

            val livingRoomEnv = EnvironmentRegistry.get("living_room")
            assertNotNull(livingRoomEnv)
            assertEquals("living_room", livingRoomEnv?.id)

            assertTrue(EnvironmentRegistry.contains(EnvironmentStore.ENV_PASSTHROUGH))
        }
    }

    @Test
    fun testAliasResolution() {
        assertEquals("space", EnvironmentRegistry.get("espaco")?.id)
        assertEquals("space", EnvironmentRegistry.get("env_espaco")?.id)
        assertEquals("space", EnvironmentRegistry.get("env_space")?.id)
        assertEquals("living_room", EnvironmentRegistry.get("sala")?.id)
        assertEquals("living_room", EnvironmentRegistry.get("env_sala")?.id)
        assertEquals("living_room", EnvironmentRegistry.get("env_living_room")?.id)
        assertEquals("cinema", EnvironmentRegistry.get("env_cinema")?.id)
        assertEquals("void", EnvironmentRegistry.get("env_void")?.id)

        assertTrue(EnvironmentRegistry.contains("espaco"))
        assertTrue(EnvironmentRegistry.contains("env_espaco"))
        assertTrue(EnvironmentRegistry.contains("sala"))
        assertFalse(EnvironmentRegistry.contains("non_existent_env"))
    }
}
