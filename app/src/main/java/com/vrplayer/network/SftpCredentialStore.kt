package com.vrplayer.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Um servidor SFTP salvo (T6.4/T6.2) — mesmo desenho de [FtpServer], com
 * [privateKey] a mais: conteudo PEM da chave privada (NAO um caminho de
 * arquivo — ver `rust/protocols/src/sftp/uri.rs`), `null` quando a
 * autenticacao usada e por senha. SFTP nao tem convencao de "anonimo" (ao
 * contrario de FTP/SMB): exatamente um dos dois (senha OU chave) deve estar
 * presente — `rust/bridge/src/lib.rs::start_sftp_playback`/
 * `sftp_list_directory` rejeitam explicitamente se nenhum dos dois estiver.
 */
data class SftpServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val privateKey: String?
) {
    val usesKeyAuth: Boolean get() = !privateKey.isNullOrEmpty()
}

/**
 * T6.4: guarda servidores SFTP salvos em `EncryptedSharedPreferences` — mesmo
 * mecanismo de [SmbCredentialStore]/[FtpCredentialStore] (ver esses arquivos
 * pro raciocinio completo). A chave privada PEM e um segredo tao sensivel
 * quanto uma senha (as vezes mais — pode dar acesso a MULTIPLOS servidores
 * se reusada) e recebe exatamente o mesmo tratamento aqui: nunca texto
 * plano em disco, nunca logada (ver `protocols::sftp::redact` do lado Rust).
 */
class SftpCredentialStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "sftp_servers_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun list(): List<SftpServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun save(server: SftpServer) {
        val updated = list().filterNot { it.id == server.id } + server
        persist(updated)
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    private fun persist(servers: List<SftpServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun toJson(s: SftpServer) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("username", s.username)
        put("password", s.password)
        put("privateKey", s.privateKey ?: "")
    }

    private fun fromJson(o: JSONObject) = SftpServer(
        id = o.getString("id"),
        name = o.optString("name", o.optString("host", "")),
        host = o.getString("host"),
        port = o.optInt("port", 22),
        username = o.optString("username", ""),
        password = o.optString("password", ""),
        privateKey = o.optString("privateKey", "").ifEmpty { null }
    )

    companion object {
        private const val KEY_SERVERS = "servers_json"
    }
}
