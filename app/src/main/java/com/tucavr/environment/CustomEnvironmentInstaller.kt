package com.tucavr.environment

import com.tucavr.EnvironmentDefinition
import com.tucavr.EnvironmentRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

sealed interface InstallResult {
    data class Installed(val definition: EnvironmentDefinition) : InstallResult

    data class Rejected(val messageKey: String) : InstallResult

    data object Failed : InstallResult
}

class CustomEnvironmentInstaller(
    val environmentsRoot: File,
    private val dao: CustomEnvironmentDao,
    private val validate: (modelPath: String) -> String,
    private val maxModelBytes: Long = MAX_MODEL_BYTES,
    private val transcodeTextures: (modelPath: String) -> Int = { 0 },
    private val register: (EnvironmentDefinition) -> Unit = EnvironmentRegistry::register,
) {
    suspend fun install(
        name: String,
        source: InputStream,
    ): InstallResult {
        val id = UUID.randomUUID().toString()
        val dir = File(environmentsRoot, id)
        var installed = false
        try {
            if (!dir.mkdirs()) return InstallResult.Failed
            val model = File(dir, MODEL_FILE_NAME)
            source.use { input -> model.outputStream().use { copyBounded(input, it) } }

            val messageKey = validate(model.absolutePath)
            if (messageKey != VALID_MESSAGE_KEY) return InstallResult.Rejected(messageKey)

            runCatching { transcodeTextures(model.absolutePath) }

            val displayName = name.replace(LINE_BREAKS, " ").trim().ifEmpty { id }
            File(dir, CONFIG_FILE_NAME).writeText("id=$id\nname=$displayName\nmodel_file=${model.absolutePath}\n")
            val definition = loadDefinition(id) ?: return InstallResult.Failed
            dao.insert(CustomEnvironment(id = id, name = displayName))
            register(definition)
            installed = true
            return InstallResult.Installed(definition)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return InstallResult.Failed
        } finally {
            if (!installed) dir.deleteRecursively()
        }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
    ) {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            total += read
            if (total > maxModelBytes) throw IOException("model exceeds $maxModelBytes bytes")
            output.write(buffer, 0, read)
        }
    }

    suspend fun registerInstalled() {
        for (entry in dao.getAll()) {
            loadDefinition(entry.id)?.let(register)
        }
    }

    private fun loadDefinition(id: String): EnvironmentDefinition? {
        val config = File(environmentsRoot, "$id/$CONFIG_FILE_NAME")
        if (!config.isFile) return null
        return config.inputStream().use { EnvironmentRegistry.parseConfig(it) }
    }

    companion object {
        const val VALID_MESSAGE_KEY = "env_validate_ok"
        const val MAX_MODEL_BYTES = 256L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MODEL_FILE_NAME = "model.glb"
        private const val CONFIG_FILE_NAME = "config.ini"
        private val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
