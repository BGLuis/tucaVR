package com.vrplayer.network

import android.content.Context
import com.vrplayer.history.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
 * T11.1 / T6.4: Adaptador de persistencia FTP respaldado pelo Room (`saved_servers`)
 * e [ServerCredentialStore] (AES256-GCM via Android Keystore).
 */
class FtpCredentialStore(
    private val dao: SavedServerDao,
    private val credentials: ServerCredentialStore
) {
    constructor(context: Context) : this(
        AppDatabase.getInstance(context).savedServerDao(),
        ServerCredentialStore(context)
    )

    fun list(): List<FtpServer> = runBlocking(Dispatchers.IO) {
        try {
            dao.getByProtocol(ServerProtocol.FTP).map { saved ->
                val password = credentials.getPassword(saved.id)
                saved.toFtpServer(password = password)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun save(server: FtpServer) {
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
