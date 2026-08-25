package com.tucavr.network

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * T11.1: Entidade Room unificada para servidores salvos de todos os protocolos.
 * Senhas e chaves privadas nunca sao salvas em texto plano aqui; elas residem
 * exclusivamente no [ServerCredentialStore] (EncryptedSharedPreferences / Android Keystore).
 */
@Entity(tableName = "saved_servers")
data class SavedServer(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: ServerProtocol,
    val host: String,
    val port: Int,
    /** Share SMB, export path NFS, ou subdiretorio base */
    val path: String = "",
    val username: String = "",
    /** Dominio Windows/Active Directory (apenas SMB) */
    val domain: String = "",
    val isAutoDiscovered: Boolean = false,
    val lastConnectedAt: Long? = null,
    val iconUrl: String? = null,
    /** Metadados extras em JSON (ex: versao NFS, opcoes de montagem, flags) */
    val extraJson: String? = null
) {
    val isGuest: Boolean get() = username.isEmpty()
}

/** Conversores de compatibilidade para interoperar suavemente com modelos legados */
fun SmbServer.toSavedServer(): SavedServer = SavedServer(
    id = id,
    name = name,
    protocol = ServerProtocol.SMB,
    host = host,
    port = port,
    path = share,
    username = username,
    domain = domain
)

fun SavedServer.toSmbServer(password: String = ""): SmbServer = SmbServer(
    id = id,
    name = name,
    host = host,
    port = port,
    share = path,
    username = username,
    password = password,
    domain = domain
)

fun FtpServer.toSavedServer(): SavedServer = SavedServer(
    id = id,
    name = name,
    protocol = ServerProtocol.FTP,
    host = host,
    port = port,
    username = username
)

fun SavedServer.toFtpServer(password: String = ""): FtpServer = FtpServer(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    password = password
)

fun SftpServer.toSavedServer(): SavedServer = SavedServer(
    id = id,
    name = name,
    protocol = ServerProtocol.SFTP,
    host = host,
    port = port,
    username = username
)

fun SavedServer.toSftpServer(password: String = "", privateKey: String? = null): SftpServer = SftpServer(
    id = id,
    name = name,
    host = host,
    port = port,
    username = username,
    password = password,
    privateKey = privateKey
)
