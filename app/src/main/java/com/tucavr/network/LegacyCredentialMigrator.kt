package com.tucavr.network

import android.content.Context
import android.util.Log

/**
 * T11.1: Migrador de primeiro boot — importa servidores e credenciais salvos
 * nos 3 stores legados (SharedPreferences) para a tabela Room `saved_servers`
 * e para o novo `ServerCredentialStore`.
 */
class LegacyCredentialMigrator(
    private val context: Context,
    private val savedServerDao: SavedServerDao,
    private val credentialStore: ServerCredentialStore
) {

    suspend fun migrateIfNeeded() {
        val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return
        }

        try {
            // 1. Migra servidores SMB
            val smbStore = SmbCredentialStore(context)
            for (smb in smbStore.list()) {
                val saved = smb.toSavedServer()
                savedServerDao.insert(saved)
                if (smb.password.isNotEmpty()) {
                    credentialStore.saveCredentials(smb.id, password = smb.password)
                }
            }

            // 2. Migra servidores FTP
            val ftpStore = FtpCredentialStore(context)
            for (ftp in ftpStore.list()) {
                val saved = ftp.toSavedServer()
                savedServerDao.insert(saved)
                if (ftp.password.isNotEmpty()) {
                    credentialStore.saveCredentials(ftp.id, password = ftp.password)
                }
            }

            // 3. Migra servidores SFTP
            val sftpStore = SftpCredentialStore(context)
            for (sftp in sftpStore.list()) {
                val saved = sftp.toSavedServer()
                savedServerDao.insert(saved)
                credentialStore.saveCredentials(
                    sftp.id,
                    password = sftp.password,
                    privateKey = sftp.privateKey
                )
            }

            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            Log.i(TAG, "Migracao de servidores legados concluida com sucesso")
        } catch (e: Exception) {
            Log.e(TAG, "Erro durante a migracao de servidores legados", e)
        }
    }

    companion object {
        private const val TAG = "LegacyCredMigrator"
        private const val MIGRATION_PREFS = "vrplayer_migration_prefs"
        private const val KEY_MIGRATED = "legacy_servers_migrated_v2"
    }
}
