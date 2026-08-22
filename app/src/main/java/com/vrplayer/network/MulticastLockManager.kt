package com.vrplayer.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * T10.1 / T10.2: Gerencia a aquisicao e liberacao pontual do [WifiManager.MulticastLock].
 * No Android/Quest, pacotes multicast sao descartados pelo driver Wi-Fi para economia
 * de bateria a menos que um MulticastLock esteja explicitamente ativo.
 * Deve ser adquirido APENAS durante a tela de Descoberta e liberado imediatamente ao sair.
 */
class MulticastLockManager(context: Context) {

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun acquire() {
        if (multicastLock == null) {
            multicastLock = wifiManager?.createMulticastLock(LOCK_TAG)?.apply {
                setReferenceCounted(false)
            }
        }
        multicastLock?.let { lock ->
            if (!lock.isHeld) {
                try {
                    lock.acquire()
                    Log.d(TAG, "MulticastLock adquirido para varredura de rede")
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao adquirir MulticastLock: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    fun release() {
        multicastLock?.let { lock ->
            if (lock.isHeld) {
                try {
                    lock.release()
                    Log.d(TAG, "MulticastLock liberado com sucesso")
                } catch (e: Exception) {
                    Log.w(TAG, "Falha ao liberar MulticastLock: ${e.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "MulticastLockManager"
        private const val LOCK_TAG = "vrplayer_discovery_multicast_lock"
    }
}
