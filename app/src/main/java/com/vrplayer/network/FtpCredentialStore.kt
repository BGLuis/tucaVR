package com.vrplayer.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Um servidor FTP salvo (T6.4/T6.1) — mesmo desenho de [SmbServer], sem os
 * campos que so fazem sentido pra SMB (`share`/`domain`).
 */
data class FtpServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Vazio = login anonimo (T6.1). */
    val username: String,
    val password: String
) {
    val isAnonymous: Boolean get() = username.isEmpty()
}

/**
 * T6.4: guarda servidores FTP salvos em `EncryptedSharedPreferences` — mesmo
 * mecanismo/motivo de [SmbCredentialStore] (AES256-GCM via Android Keystore,
 * NUNCA texto plano; ver esse arquivo pro raciocinio completo). Chave
 * separada (`ftp_servers_encrypted`) pra nao misturar os dois formatos no
 * mesmo blob JSON.
 *
 * Cuidado adicional que SMB nao tem (doc, secao 6, aviso "FTP senha em texto
 * claro"): a senha aqui protege o armazenamento em disco, mas ainda trafega
 * em claro na rede a cada conexao — isso e uma limitacao do protocolo FTP em
 * si, nao desta classe. A UI (T6.4, quando existir a tela de conexao) deve
 * deixar isso visivel pro usuario, nao so confiar na criptografia em repouso.
 */
class FtpCredentialStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "ftp_servers_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun list(): List<FtpServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun save(server: FtpServer) {
        val updated = list().filterNot { it.id == server.id } + server
        persist(updated)
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    private fun persist(servers: List<FtpServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun toJson(s: FtpServer) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("username", s.username)
        put("password", s.password)
    }

    private fun fromJson(o: JSONObject) = FtpServer(
        id = o.getString("id"),
        name = o.optString("name", o.optString("host", "")),
        host = o.getString("host"),
        port = o.optInt("port", 21),
        username = o.optString("username", ""),
        password = o.optString("password", "")
    )

    companion object {
        private const val KEY_SERVERS = "servers_json"
    }
}
