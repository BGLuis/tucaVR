package com.vrplayer.filebrowser

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class ViewMode {
    LIST,
    GRID
}

data class FolderConfig(
    val sortBy: SortBy = SortBy.NAME,
    val ascending: Boolean = true,
    val viewMode: ViewMode = ViewMode.GRID
)

/**
 * Gerencia a persistência de configurações de exibição (modo Grid/Lista e ordenação)
 * com escopo Global e escopo específico Por Pasta (via arquivo oculto `.vrplayer_folder_config.json`
 * e espelhamento em SharedPreferences).
 */
class FolderConfigStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vrplayer_folder_configs", Context.MODE_PRIVATE)

    companion object {
        const val CONFIG_FILENAME = ".vrplayer_folder_config.json"
        private const val KEY_GLOBAL_SORT_BY = "global_sort_by"
        private const val KEY_GLOBAL_ASCENDING = "global_ascending"
        private const val KEY_GLOBAL_VIEW_MODE = "global_view_mode"
    }

    /**
     * Retorna a configuração global padrão.
     */
    fun getGlobalConfig(): FolderConfig {
        val sortName = prefs.getString(KEY_GLOBAL_SORT_BY, SortBy.NAME.name) ?: SortBy.NAME.name
        val ascending = prefs.getBoolean(KEY_GLOBAL_ASCENDING, true)
        val viewModeName = prefs.getString(KEY_GLOBAL_VIEW_MODE, ViewMode.GRID.name) ?: ViewMode.GRID.name

        val sortBy = runCatching { SortBy.valueOf(sortName) }.getOrDefault(SortBy.NAME)
        val viewMode = runCatching { ViewMode.valueOf(viewModeName) }.getOrDefault(ViewMode.GRID)

        return FolderConfig(sortBy, ascending, viewMode)
    }

    /**
     * Salva a configuração global padrão.
     */
    fun saveGlobalConfig(config: FolderConfig) {
        prefs.edit()
            .putString(KEY_GLOBAL_SORT_BY, config.sortBy.name)
            .putBoolean(KEY_GLOBAL_ASCENDING, config.ascending)
            .putString(KEY_GLOBAL_VIEW_MODE, config.viewMode.name)
            .apply()
    }

    /**
     * Obtém a configuração específica da pasta ou fallback para a global.
     */
    fun getConfigFor(folderPath: String?): FolderConfig {
        if (folderPath.isNullOrBlank()) {
            return getGlobalConfig()
        }

        // 1. Tentar ler do arquivo oculto local se existir
        val localConfigFile = File(folderPath, CONFIG_FILENAME)
        if (localConfigFile.exists() && localConfigFile.canRead()) {
            val fromFile = readFromFile(localConfigFile)
            if (fromFile != null) return fromFile
        }

        // 2. Tentar ler do SharedPreferences (cache de pastas locais/rede)
        val cacheKey = "folder_" + sha256(folderPath)
        val cachedJson = prefs.getString(cacheKey, null)
        if (cachedJson != null) {
            val fromCache = parseJson(cachedJson)
            if (fromCache != null) return fromCache
        }

        // 3. Fallback para configuração global
        return getGlobalConfig()
    }

    /**
     * Salva a configuração de uma pasta específica no arquivo oculto e no SharedPreferences.
     */
    fun saveConfigFor(folderPath: String?, config: FolderConfig) {
        if (folderPath.isNullOrBlank()) {
            saveGlobalConfig(config)
            return
        }

        val jsonStr = toJson(config)

        // 1. Gravar no SharedPreferences
        val cacheKey = "folder_" + sha256(folderPath)
        prefs.edit().putString(cacheKey, jsonStr).apply()

        // 2. Gravar no arquivo oculto se for diretório local gravável
        try {
            val dir = File(folderPath)
            if (dir.exists() && dir.isDirectory && dir.canWrite()) {
                val configFile = File(dir, CONFIG_FILENAME)
                configFile.writeText(jsonStr)
            }
        } catch (_: Exception) {
            // Ignora falha de I/O em pastas somente leitura; SharedPreferences já garantiu o salvamento
        }
    }

    internal fun readFromFile(file: File): FolderConfig? {
        return try {
            val text = file.readText()
            parseJson(text)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseJson(jsonStr: String): FolderConfig? {
        if (!jsonStr.contains("{") || !jsonStr.contains("}")) return null

        val sortByStr = Regex("\"sortBy\"\\s*:\\s*\"([A-Za-z_]+)\"").find(jsonStr)?.groupValues?.get(1)
        val ascendingStr = Regex("\"ascending\"\\s*:\\s*(true|false)").find(jsonStr)?.groupValues?.get(1)
        val viewModeStr = Regex("\"viewMode\"\\s*:\\s*\"([A-Za-z_]+)\"").find(jsonStr)?.groupValues?.get(1)

        val sortBy = sortByStr?.let { runCatching { SortBy.valueOf(it) }.getOrNull() } ?: SortBy.NAME
        val ascending = ascendingStr?.toBooleanStrictOrNull() ?: true
        val viewMode = viewModeStr?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() } ?: ViewMode.GRID

        return FolderConfig(sortBy, ascending, viewMode)
    }

    internal fun toJson(config: FolderConfig): String {
        return """{"sortBy":"${config.sortBy.name}","ascending":${config.ascending},"viewMode":"${config.viewMode.name}"}"""
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
