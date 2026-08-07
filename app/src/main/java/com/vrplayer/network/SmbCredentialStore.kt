package com.vrplayer.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Um servidor SMB salvo (T6.4). `id` e uma chave estavel independente do
 * conteudo (gerada uma vez na criacao) — editar host/share depois nao perde
 * a identidade da entrada na lista.
 */
data class SmbServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val share: String,
    /** Vazio = guest/anonimo (T6.1). */
    val username: String,
    val password: String,
    val domain: String
) {
    val isGuest: Boolean get() = username.isEmpty()
}

/**
 * T6.4: guarda servidores SMB salvos em `EncryptedSharedPreferences`
 * (AES256-GCM via Jetpack Security / Android Keystore) — NUNCA texto plano.
 * Isso cobre o aviso explicito do doc (secao 6, "Credenciais"): a senha so
 * existe em claro na memoria do processo, nunca em disco.
 *
 * Tudo serializado como um unico JSON sob uma chave — simples e suficiente
 * para o numero de servidores que um usuario real teria (unidades a
 * dezenas, nao milhares); um servidor por linha em Room seria mais
 * "correto" para uma escala maior, mas seria over-engineering aqui.
 */
class SmbCredentialStore(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "smb_servers_encrypted",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun list(): List<SmbServer> {
        val raw = prefs.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Cria um id novo (`UUID`) para um servidor novo. */
    fun newId(): String = UUID.randomUUID().toString()

    /** Insere ou substitui (por `id`) um servidor salvo. */
    fun save(server: SmbServer) {
        val updated = list().filterNot { it.id == server.id } + server
        persist(updated)
    }

    fun remove(id: String) {
        persist(list().filterNot { it.id == id })
    }

    private fun persist(servers: List<SmbServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY_SERVERS, arr.toString()).apply()
    }

    private fun toJson(s: SmbServer) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("share", s.share)
        put("username", s.username)
        put("password", s.password)
        put("domain", s.domain)
    }

    private fun fromJson(o: JSONObject) = SmbServer(
        id = o.getString("id"),
        name = o.optString("name", o.optString("host", "")),
        host = o.getString("host"),
        port = o.optInt("port", 445),
        share = o.optString("share", ""),
        username = o.optString("username", ""),
        password = o.optString("password", ""),
        domain = o.optString("domain", "")
    )

    companion object {
        private const val KEY_SERVERS = "servers_json"
    }
}
