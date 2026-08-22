package com.vrplayer.network

import android.content.Context
import com.vrplayer.history.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * T11.1 / T6.4: Adaptador de persistencia SMB respaldado pelo Room (`saved_servers`)
 * e [ServerCredentialStore] (AES256-GCM via Android Keystore).
 * A senha so existe em claro na memoria do processo, nunca em disco no Room.
 */
class SmbCredentialStore(
    private val dao: SavedServerDao,
    private val credentials: ServerCredentialStore
) {
    constructor(context: Context) : this(
        AppDatabase.getInstance(context).savedServerDao(),
        ServerCredentialStore(context)
    )

    fun list(): List<SmbServer> = runBlocking(Dispatchers.IO) {
        try {
            dao.getByProtocol(ServerProtocol.SMB).map { saved ->
                val password = credentials.getPassword(saved.id)
                saved.toSmbServer(password = password)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Cria um id novo (`UUID`) para um servidor novo. */
    fun newId(): String = UUID.randomUUID().toString()

    /** Insere ou substitui (por `id`) um servidor salvo. */
    fun save(server: SmbServer) {
        runBlocking(Dispatchers.IO) {
            try {
                dao.insert(server.toSavedServer())
                credentials.saveCredentials(server.id, password = server.password)
            } catch (e: Exception) {
                // Log / ignore
            }
        }
    }

    fun remove(id: String) {
        runBlocking(Dispatchers.IO) {
            try {
                dao.delete(id)
                credentials.removeCredentials(id)
            } catch (e: Exception) {
                // Log / ignore
            }
        }
    }
}
