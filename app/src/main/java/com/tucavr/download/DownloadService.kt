package com.tucavr.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tucavr.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Foreground Service do Android para manter downloads ativos mesmo quando o headset
 * estiver suspenso ou fora da cabeça do usuário (Fase 0.4 Seção 4 / Cuidados e Armadilhas).
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "tucavr_downloads_channel"
        const val NOTIFICATION_ID = 1001
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null
    private lateinit var repository: DownloadRepository
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildNotification(
            title = getString(R.string.downloads_notification_title),
            text = getString(R.string.downloads_status_downloading),
            progress = 0
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startMonitoringLoop()
        return START_NOT_STICKY
    }

    private fun startMonitoringLoop() {
        if (syncJob?.isActive == true) return

        syncJob = serviceScope.launch {
            while (isActive) {
                val hasActive = repository.syncActiveDownloads()
                if (!hasActive) {
                    break
                }

                val activeList = repository.listActive()
                if (activeList.isNotEmpty()) {
                    val first = activeList.first()
                    val stats = repository.getStats(first.id)
                    val percent = if (first.totalBytes > 0) {
                        val dl = stats?.downloadedBytes ?: first.downloadedBytes
                        ((dl.toDouble() / first.totalBytes.toDouble()) * 100).toInt().coerceIn(0, 100)
                    } else 0

                    val speedStr = stats?.let {
                        val mb = it.speedBps.toDouble() / (1024.0 * 1024.0)
                        String.format(Locale.US, "%.1f MB/s", mb)
                    } ?: ""

                    val text = if (speedStr.isNotEmpty()) {
                        "${first.displayName} ($percent% • $speedStr)"
                    } else {
                        "${first.displayName} ($percent%)"
                    }

                    val updatedNotification = buildNotification(
                        title = getString(R.string.downloads_notification_title),
                        text = text,
                        progress = percent
                    )
                    notificationManager.notify(NOTIFICATION_ID, updatedNotification)
                }

                delay(1000)
            }

            // Encerra o serviço quando a fila estiver vazia/pausada
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.downloads_title)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progresso de downloads offline"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
