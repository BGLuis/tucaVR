package com.tucavr.network

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.tucavr.R

/**
 * Protocolos de rede suportados para servidores salvos e descoberta automática.
 */
enum class ServerProtocol {
    SMB,
    FTP,
    SFTP,
    NFS,
    DLNA,
    WEBDAV
}

/**
 * Icone/label centralizados por protocolo, usados tanto pela lista unificada de
 * servidores salvos ([com.tucavr.screens.NetworkHomeScreen]) quanto pela tela de
 * descoberta automatica ([com.tucavr.screens.NetworkDiscoveryScreen]) - antes
 * duplicado em ambos os arquivos.
 */
@get:DrawableRes
val ServerProtocol.iconRes: Int
    get() = when (this) {
        ServerProtocol.SMB, ServerProtocol.NFS -> R.drawable.ic_storage
        ServerProtocol.FTP -> R.drawable.ic_broadcast
        ServerProtocol.SFTP -> R.drawable.ic_lock
        ServerProtocol.DLNA -> R.drawable.ic_movie
        ServerProtocol.WEBDAV -> R.drawable.ic_link
    }

@get:StringRes
val ServerProtocol.labelRes: Int
    get() = when (this) {
        ServerProtocol.SMB -> R.string.network_tab_smb
        ServerProtocol.FTP -> R.string.network_tab_ftp
        ServerProtocol.SFTP -> R.string.network_tab_sftp
        ServerProtocol.NFS -> R.string.network_tab_nfs
        ServerProtocol.DLNA -> R.string.network_tab_dlna
        ServerProtocol.WEBDAV -> R.string.network_tab_webdav
    }
