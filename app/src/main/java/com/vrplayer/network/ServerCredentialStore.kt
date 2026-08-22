package com.vrplayer.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * T11.1 / T6.4: Armazenamento seguro de senhas e chaves privadas indexadas por `serverId`.
 * Utiliza AES256-GCM via Android Keystore (Jetpack Security).
 * As credenciais NUNCA sao salvas em texto plano e nunca entram no banco Room.
 */
class ServerCredentialStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Salva a senha e/ou chave privada (PEM) associadas ao [serverId].
     */
    fun saveCredentials(serverId: String, password: String = "", privateKey: String? = null) {
        val json = JSONObject().apply {
            put("password", password)
            if (!privateKey.isNullOrEmpty()) {
                put("privateKey", privateKey)
            }
        }
        prefs.edit().putString(serverId, json.toString()).apply()
    }

    /**
     * Retorna a senha associada ao [serverId], ou string vazia se nao houver.
     */
    fun getPassword(serverId: String): String {
        val raw = prefs.getString(serverId, null) ?: return ""
        return try {
            JSONObject(raw).optString("password", "")
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Retorna a chave privada PEM associada ao [serverId], ou null se nao houver.
     */
    fun getPrivateKey(serverId: String): String? {
        val raw = prefs.getString(serverId, null) ?: return null
        return try {
            val key = JSONObject(raw).optString("privateKey", "")
            if (key.isNotEmpty()) key else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Remove as credenciais associadas ao [serverId].
     */
    fun removeCredentials(serverId: String) {
        prefs.edit().remove(serverId).apply()
    }

    companion object {
        private const val PREFS_FILE_NAME = "server_credentials_encrypted"
    }
}
