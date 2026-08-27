package com.tucavr.network

import android.content.Context
import com.tucavr.history.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID

/**
 * Um servidor SFTP salvo (T6.4/T6.2) — mesmo desenho de [FtpServer], com
 * [privateKey] a mais: conteudo PEM da chave privada (NAO um caminho de
 * arquivo — ver `rust/protocols/src/sftp/uri.rs`), `null` quando a
 * autenticacao usada e por senha.
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
 * T11.1 / T6.4: Adaptador de persistencia SFTP respaldado pelo Room (`saved_servers`)
 * e [ServerCredentialStore] (AES256-GCM via Android Keystore).
 */
class SftpCredentialStore(
    private val dao: SavedServerDao,
    private val credentials: ServerCredentialStore
) {
    constructor(context: Context) : this(
        AppDatabase.getInstance(context).savedServerDao(),
        ServerCredentialStore(context)
    )

    fun list(): List<SftpServer> = runBlocking(Dispatchers.IO) {
        try {
            dao.getByProtocol(ServerProtocol.SFTP).map { saved ->
                val password = credentials.getPassword(saved.id)
                val privateKey = credentials.getPrivateKey(saved.id)
                saved.toSftpServer(password = password, privateKey = privateKey)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun save(server: SftpServer) {
        runBlocking(Dispatchers.IO) {
            try {
                dao.insert(server.toSavedServer())
                credentials.saveCredentials(
                    server.id,
                    password = server.password,
                    privateKey = server.privateKey
                )
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
