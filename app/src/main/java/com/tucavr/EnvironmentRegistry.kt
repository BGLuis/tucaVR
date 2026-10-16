package com.tucavr

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Metadata definition for a virtual environment, loaded dynamically from config.ini.
 */
data class EnvironmentDefinition(
    val id: String,
    val name: String,
    val screenPosX: Float = 0.0f,
    val screenPosY: Float = 1.5f,
    val screenPosZ: Float = -2.4f,
    val screenScaleX: Float = 2.8f,
    val screenScaleY: Float = 1.575f,
    val screenLocked: Boolean = false,
    val modelFile: String? = null,
    val skyboxFile: String? = null,
    val ambientAudio: String? = null,
    val ambientVolume: Float = 0.0f,
    val particlesEnabled: Boolean = false,
    val spawnPosX: Float = 0.0f,
    val spawnPosY: Float = 0.0f,
    val spawnPosZ: Float = 0.0f,
)

/**
 * Dynamic registry of available 3D virtual environments (Phase 0.5 §3 T3.5).
 */
object EnvironmentRegistry {
    private const val TAG = "EnvironmentRegistry"

    val PASSTHROUGH_DEFINITION =
        EnvironmentDefinition(
            id = EnvironmentStore.ENV_PASSTHROUGH,
            name = "Passthrough (MR)",
            screenPosX = 0.0f,
            screenPosY = 1.5f,
            screenPosZ = -2.4f,
            screenScaleX = 2.8f,
            screenScaleY = 1.575f,
            screenLocked = false,
            ambientVolume = 0.0f,
            particlesEnabled = false,
        )

    private val defaultDefinitions =
        listOf(
            EnvironmentDefinition(
                id = EnvironmentStore.ENV_VOID,
                name = "Void",
                screenPosX = 0.0f,
                screenPosY = 1.5f,
                screenPosZ = -2.4f,
                screenScaleX = 2.8f,
                screenScaleY = 1.575f,
                screenLocked = false,
                ambientVolume = 0.0f,
                particlesEnabled = false,
            ),
            EnvironmentDefinition(
                id = EnvironmentStore.ENV_SPACE,
                name = "Espaço Cósmico",
                screenPosX = 0.0f,
                screenPosY = 1.8f,
                screenPosZ = -3.2f,
                screenScaleX = 3.8f,
                screenScaleY = 2.1375f,
                screenLocked = true,
                modelFile = "space/model.glb",
                skyboxFile = "space/skybox.png",
                ambientAudio = "space/ambient.ogg",
                ambientVolume = 0.25f,
                particlesEnabled = true,
                spawnPosX = 0.0f,
                spawnPosY = 0.0f,
                spawnPosZ = 0.0f,
            ),
            EnvironmentDefinition(
                id = EnvironmentStore.ENV_CINEMA,
                name = "Cinema IMAX",
                screenPosX = 0.0f,
                screenPosY = 2.5f,
                screenPosZ = -7.5f,
                screenScaleX = 6.0f,
                screenScaleY = 3.375f,
                screenLocked = true,
                modelFile = "cinema/model.glb",
                ambientAudio = "cinema/ambient.ogg",
                ambientVolume = 0.15f,
                particlesEnabled = false,
            ),
            EnvironmentDefinition(
                id = EnvironmentStore.ENV_LIVING_ROOM,
                name = "Sala de Estar",
                screenPosX = 0.0f,
                screenPosY = 1.85f,
                screenPosZ = -2.8f,
                screenScaleX = 2.4f,
                screenScaleY = 1.35f,
                screenLocked = true,
                modelFile = "living_room/model.glb",
                ambientVolume = 0.0f,
                particlesEnabled = false,
            ),
            PASSTHROUGH_DEFINITION,
        )

    private val registry = LinkedHashMap<String, EnvironmentDefinition>()

    init {
        resetToDefaults()
        autoLoadFromHostFilesystemIfAvailable()
    }

    @Synchronized
    fun resetToDefaults() {
        registry.clear()
        for (def in defaultDefinitions) {
            registry[def.id] = def
        }
    }

    @Synchronized
    fun getAll(): List<EnvironmentDefinition> = registry.values.toList()

    @Synchronized
    fun get(id: String): EnvironmentDefinition? {
        val direct = registry[id]
        if (direct != null) return direct

        val normalized = normalizeId(id)
        return registry[normalized]
    }

    @Synchronized
    fun contains(id: String): Boolean = get(id) != null

    fun normalizeId(id: String): String {
        val clean = id.trim().lowercase().removePrefix("env_")
        return when (clean) {
            "espaco" -> EnvironmentStore.ENV_SPACE
            "sala" -> EnvironmentStore.ENV_LIVING_ROOM
            else -> clean
        }
    }

    @Synchronized
    fun register(definition: EnvironmentDefinition) {
        registry[definition.id] = definition
    }

    /**
     * Parses a config.ini stream into an EnvironmentDefinition.
     */
    fun parseConfig(inputStream: InputStream): EnvironmentDefinition {
        var id = "unknown"
        var name = "Unknown"
        var posX = 0.0f
        var posY = 1.5f
        var posZ = -2.4f
        var scaleX = 2.8f
        var scaleY = 1.575f
        var locked = false
        var modelFile: String? = null
        var skyboxFile: String? = null
        var ambientAudio: String? = null
        var ambientVolume = 0.0f
        var particlesEnabled = false
        var spawnX = 0.0f
        var spawnY = 0.0f
        var spawnZ = 0.0f

        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                val eqIdx = line.indexOf('=')
                if (eqIdx <= 0) continue

                val key = line.substring(0, eqIdx).trim()
                val value = line.substring(eqIdx + 1).trim()

                when (key) {
                    "id" -> id = value
                    "name" -> name = value
                    "screen_pos" -> {
                        val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                        if (parts.size >= 3) {
                            posX = parts[0]
                            posY = parts[1]
                            posZ = parts[2]
                        }
                    }
                    "screen_scale" -> {
                        val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                        if (parts.size >= 2) {
                            scaleX = parts[0]
                            scaleY = parts[1]
                        }
                    }
                    "screen_locked" -> {
                        locked = value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
                    }
                    "model_file" -> modelFile = value.ifEmpty { null }
                    "skybox_file" -> skyboxFile = value.ifEmpty { null }
                    "ambient_audio" -> ambientAudio = value.ifEmpty { null }
                    "ambient_volume" -> ambientVolume = value.toFloatOrNull() ?: 0.0f
                    "particles_enabled" -> {
                        particlesEnabled =
                            value.equals("true", ignoreCase = true) || value == "1" || value.equals("yes", ignoreCase = true)
                    }
                    "spawn_pos" -> {
                        val parts = value.split(',').mapNotNull { it.trim().toFloatOrNull() }
                        if (parts.size >= 3) {
                            spawnX = parts[0]
                            spawnY = parts[1]
                            spawnZ = parts[2]
                        }
                    }
                }
            }
        }

        return EnvironmentDefinition(
            id = id,
            name = name,
            screenPosX = posX,
            screenPosY = posY,
            screenPosZ = posZ,
            screenScaleX = scaleX,
            screenScaleY = scaleY,
            screenLocked = locked,
            modelFile = modelFile,
            skyboxFile = skyboxFile,
            ambientAudio = ambientAudio,
            ambientVolume = ambientVolume,
            particlesEnabled = particlesEnabled,
            spawnPosX = spawnX,
            spawnPosY = spawnY,
            spawnPosZ = spawnZ,
        )
    }

    /**
     * Loads environments from Android assets directory `environments/`.
     */
    @Synchronized
    fun loadFromAssets(assets: AssetManager) {
        try {
            val envDirs = assets.list("environments") ?: return
            val preferredOrder = listOf("void", "space", "cinema", "living_room")
            val sortedDirs =
                envDirs.sortedWith(
                    Comparator { a, b ->
                        val idxA = preferredOrder.indexOf(a)
                        val idxB = preferredOrder.indexOf(b)
                        when {
                            idxA != -1 && idxB != -1 -> idxA.compareTo(idxB)
                            idxA != -1 -> -1
                            idxB != -1 -> 1
                            else -> a.compareTo(b)
                        }
                    },
                )

            val parsedDefs = mutableListOf<EnvironmentDefinition>()
            for (dir in sortedDirs) {
                val configPath = "environments/$dir/config.ini"
                try {
                    assets.open(configPath).use { stream ->
                        val def = parseConfig(stream)
                        parsedDefs.add(def)
                    }
                } catch (e: Exception) {
                    // Ignore non-environment directories
                }
            }

            if (parsedDefs.isNotEmpty()) {
                registry.clear()
                for (def in parsedDefs) {
                    registry[def.id] = def
                }
                // Always preserve virtual passthrough
                registry[PASSTHROUGH_DEFINITION.id] = PASSTHROUGH_DEFINITION
                Log.i(TAG, "Loaded ${registry.size} environments from assets")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load environments from assets: ${e.message}")
        }
    }

    /**
     * Loads environments from a local filesystem directory (e.g. during host JVM tests).
     */
    @Synchronized
    fun loadFromDirectory(directory: File) {
        if (!directory.isDirectory) return
        val preferredOrder = listOf("void", "space", "cinema", "living_room")
        val dirs = directory.listFiles() ?: return
        val sortedDirs =
            dirs.filter { it.isDirectory }.sortedWith(
                Comparator { a, b ->
                    val idxA = preferredOrder.indexOf(a.name)
                    val idxB = preferredOrder.indexOf(b.name)
                    when {
                        idxA != -1 && idxB != -1 -> idxA.compareTo(idxB)
                        idxA != -1 -> -1
                        idxB != -1 -> 1
                        else -> a.name.compareTo(b.name)
                    }
                },
            )

        val parsedDefs = mutableListOf<EnvironmentDefinition>()
        for (dir in sortedDirs) {
            val iniFile = File(dir, "config.ini")
            if (iniFile.isFile) {
                iniFile.inputStream().use { stream ->
                    parsedDefs.add(parseConfig(stream))
                }
            }
        }

        if (parsedDefs.isNotEmpty()) {
            registry.clear()
            for (def in parsedDefs) {
                registry[def.id] = def
            }
            registry[PASSTHROUGH_DEFINITION.id] = PASSTHROUGH_DEFINITION
        }
    }

    /**
     * Resolves the icon drawable resource for an environment with safe fallback.
     */
    fun resolveIcon(
        context: Context,
        envId: String,
    ): Int {
        if (envId == EnvironmentStore.ENV_PASSTHROUGH) {
            return R.drawable.icon_eye_dashed
        }
        val directName = "env_$envId"
        val resId = context.resources.getIdentifier(directName, "drawable", context.packageName)
        if (resId != 0) return resId

        val legacyAlias =
            when (envId) {
                EnvironmentStore.ENV_SPACE -> "env_espaco"
                EnvironmentStore.ENV_LIVING_ROOM -> "env_sala"
                else -> null
            }
        if (legacyAlias != null) {
            val legacyId = context.resources.getIdentifier(legacyAlias, "drawable", context.packageName)
            if (legacyId != 0) return legacyId
        }

        return R.drawable.icon_environment
    }

    private fun autoLoadFromHostFilesystemIfAvailable() {
        try {
            val userDir = File(System.getProperty("user.dir") ?: ".")
            val candidateApp = File(userDir, "src/main/assets/environments")
            if (candidateApp.isDirectory) {
                loadFromDirectory(candidateApp)
                return
            }
            val candidateRoot = File(userDir, "app/src/main/assets/environments")
            if (candidateRoot.isDirectory) {
                loadFromDirectory(candidateRoot)
            }
        } catch (_: Exception) {
            // Ignored on Android runtime
        }
    }
}
