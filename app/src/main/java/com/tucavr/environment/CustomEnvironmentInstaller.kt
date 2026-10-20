package com.tucavr.environment

import com.tucavr.EnvironmentDefinition
import com.tucavr.EnvironmentRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

sealed interface InstallResult {
    data class Installed(val definition: EnvironmentDefinition) : InstallResult

    data class Rejected(val messageKey: String) : InstallResult

    data object Failed : InstallResult
}

class CustomEnvironmentInstaller(
    val environmentsRoot: File,
    private val dao: CustomEnvironmentDao,
    private val validate: (modelPath: String) -> String,
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
            source.use { input -> model.outputStream().use { input.copyTo(it) } }

            val messageKey = validate(model.absolutePath)
            if (messageKey != VALID_MESSAGE_KEY) return InstallResult.Rejected(messageKey)

            val displayName = name.replace(LINE_BREAKS, " ").trim().ifEmpty { id }
            File(dir, CONFIG_FILE_NAME).writeText("id=$id\nname=$displayName\nmodel_file=${model.absolutePath}\n")
            dao.insert(CustomEnvironment(id = id, name = displayName))
            val definition = loadDefinition(id) ?: return InstallResult.Failed
            register(definition)
            installed = true
            return InstallResult.Installed(definition)
        } catch (e: IOException) {
            return InstallResult.Failed
        } finally {
            if (!installed) dir.deleteRecursively()
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
        private const val MODEL_FILE_NAME = "model.glb"
        private const val CONFIG_FILE_NAME = "config.ini"
        private val LINE_BREAKS = Regex("[\\r\\n]+")
    }
}
