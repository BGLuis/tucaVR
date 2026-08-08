package com.vrplayer

import android.app.NativeActivity
import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

import android.hardware.display.DisplayManager
import android.view.Surface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import com.vrplayer.history.PlaybackHistoryTracker
import com.vrplayer.navigation.PlaybackSource

class VRActivity : NativeActivity() {
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var presentation: VRPresentation? = null

    private var controlsVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var controlsPresentation: VRControlsPresentation? = null

    // Hook de teste (soak test via adb, ver scripts/soak-test.sh): permite disparar
    // playback sem controller, ex. `adb shell am start -n com.vrplayer/.VRActivity
    // -e video_path /sdcard/Movies/test.mp4`. Sem isso o soak test só mediria app
    // ocioso, não reprodução de vídeo de fato (o cenário que a DoD pede medir).
    // Atrasado pós-onResume porque a sessão OpenXR nativa (render loop em C++) é
    // inicializada de forma assíncrona pelo NativeActivity — chamar nativePlayVideo
    // cedo demais arrisca chamar antes da textura/surface estarem prontas.
    private var pendingAutoPlayPath: String? = null
    private var autoPlayDispatched = false
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // T9.1-T9.3: unico dono do historico de reproducao neste processo (mesmo
    // ciclo de vida da Activity). `by lazy` porque so e usado depois que a
    // Activity ja existe (primeiro uso real e dentro de playFile/playUrl/
    // playSmb, chamados via VRPresentation apos onCreate).
    val historyTracker: PlaybackHistoryTracker by lazy { PlaybackHistoryTracker(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                val uri = Uri.fromParts("package", packageName, null)
                intent.data = uri
                startActivity(intent)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }

        pendingAutoPlayPath = intent?.getStringExtra(EXTRA_AUTO_PLAY_PATH)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_AUTO_PLAY_PATH)?.let {
            pendingAutoPlayPath = it
            autoPlayDispatched = false
        }
    }

    override fun onResume() {
        super.onResume()
        presentation?.loadFiles()
        pendingAutoPlayPath?.let { path ->
            if (!autoPlayDispatched) {
                autoPlayDispatched = true
                autoPlayHandler.postDelayed({ playFile(path) }, AUTO_PLAY_DELAY_MS)
            }
        }
    }

    companion object {
        init {
            System.loadLibrary("vrplayer_native")
        }

        private const val PICK_VIDEO_REQUEST_CODE = 1001

        // Ver hook de auto-play em onCreate/onNewIntent/onResume.
        const val EXTRA_AUTO_PLAY_PATH = "video_path"
        private const val AUTO_PLAY_DELAY_MS = 3000L

        // T9.3: ver ressalva completa em `scheduleResumeSeek`.
        private const val RESUME_SEEK_DELAY_MS = 1500L

        @JvmStatic
        fun openFilePicker(activity: VRActivity) {
            activity.runOnUiThread {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                }
                activity.startActivityForResult(intent, PICK_VIDEO_REQUEST_CODE)
            }
        }

        @JvmStatic
        fun setupVirtualDisplay(activity: VRActivity, surface: Surface, width: Int, height: Int) {
            activity.runOnUiThread {
                val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                activity.virtualDisplay = displayManager.createVirtualDisplay(
                    "VR_UI_Display",
                    width, height, 160,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
                
                activity.virtualDisplay?.display?.let { display ->
                    activity.presentation = VRPresentation(activity, display, activity)
                    activity.presentation?.show()
                }
            }
        }

        private var lastDownTime: Long = 0

        @JvmStatic
        fun dispatchVRTouch(activity: VRActivity, x: Float, y: Float, action: Int) {
            activity.runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    lastDownTime = now
                }
                
                val downTime = if (lastDownTime == 0L) now else lastDownTime
                val event = android.view.MotionEvent.obtain(
                    downTime,
                    now,
                    action,
                    x * 1024f,
                    y * 1024f,
                    0
                )
                
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                
                if (action == 7) { // ACTION_HOVER_MOVE
                    activity.presentation?.dispatchGenericMotionEvent(event)
                } else {
                    activity.presentation?.dispatchTouchEvent(event)
                }
                
                event.recycle()
                
                if (action == android.view.MotionEvent.ACTION_UP) {
                    lastDownTime = 0L
                }
            }
        }

        @JvmStatic
        fun setupControlsVirtualDisplay(activity: VRActivity, surface: Surface, width: Int, height: Int) {
            activity.runOnUiThread {
                val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                activity.controlsVirtualDisplay = displayManager.createVirtualDisplay(
                    "VR_Controls_Display",
                    width, height, 160,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                )
                
                activity.controlsVirtualDisplay?.display?.let { display ->
                    activity.controlsPresentation = VRControlsPresentation(activity, display, activity) {
                        activity.nativeTogglePlayPause()
                    }
                    activity.controlsPresentation?.show()
                }
            }
        }

        private var lastControlsDownTime: Long = 0

        @JvmStatic
        fun dispatchControlsVRTouch(activity: VRActivity, x: Float, y: Float, action: Int) {
            activity.runOnUiThread {
                val now = android.os.SystemClock.uptimeMillis()
                if (action == android.view.MotionEvent.ACTION_DOWN) {
                    lastControlsDownTime = now
                }
                
                val downTime = if (lastControlsDownTime == 0L) now else lastControlsDownTime
                val event = android.view.MotionEvent.obtain(
                    downTime,
                    now,
                    action,
                    x * 1024f, // width
                    y * 256f,  // height
                    0
                )
                
                if (action == 7) {
                    activity.controlsPresentation?.dispatchGenericMotionEvent(event)
                } else {
                    activity.controlsPresentation?.dispatchTouchEvent(event)
                }
                
                event.recycle()
                
                if (action == android.view.MotionEvent.ACTION_UP) {
                    lastControlsDownTime = 0L
                }
            }
        }

        @JvmStatic
        fun updateMediaProgress(activity: VRActivity, currentSec: Float, totalSec: Float) {
            activity.runOnUiThread {
                activity.controlsPresentation?.updateProgress(currentSec, totalSec)
                // T9.2: mesmo hook que ja existia para a UI de controles —
                // chamado pelo C++ via JNI ~10x/segundo (ver
                // native/src/vr_player_app.cpp, `frameCount % 6 == 0` a
                // 60fps). O throttle de ~10s vive dentro do proprio
                // PlaybackHistoryTracker (PlaybackProgressThrottle) — aqui
                // so repassamos toda chamada, sem decidir throttle nesta
                // camada. Rodar dentro do mesmo runOnUiThread (em vez de
                // direto na thread JNI) mantem a mutacao do estado interno
                // do tracker (throttle/`current`) single-threaded, evitando
                // uma corrida de dados sem precisar de sincronizacao extra.
                activity.historyTracker.onProgress(currentSec, totalSec)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_VIDEO_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                processVideoUri(uri)
            }
        }
    }

    // T9.1-T9.3: os 3 entry points de playback (playFile/playUrl/playSmb) sao
    // exatamente onde path/titulo/fonte estao disponiveis para popular o
    // historico — chamam `historyTracker.startTracking` ANTES de iniciar a
    // reproducao nativa. `resumeAtMs` (T9.3, opcional): quando informado
    // pelo prompt "Retomar de XX:XX?" (ver VRPresentation.renderResumePrompt),
    // agenda um seek para essa posicao pouco depois de iniciar o playback.

    fun playFile(filePath: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val resolvedSize = if (sizeBytes > 0L) sizeBytes else runCatching { File(filePath).length() }.getOrDefault(0L)
        val source = PlaybackSource.LocalFile(filePath, resolvedSize)
        historyTracker.startTracking(source, title = File(filePath).name)
        nativePlayVideo(filePath)
        resumeAtMs?.let { scheduleResumeSeek(it) }
    }

    // T7: URL HTTP(S) reusa o mesmo entry point que arquivo local — o
    // Demuxer (Rust) despacha por esquema (ver rust/core/src/demuxer.rs).
    fun playUrl(url: String, resumeAtMs: Long? = null) {
        historyTracker.startTracking(PlaybackSource.Http(url), title = url)
        nativePlayVideo(url)
        resumeAtMs?.let { scheduleResumeSeek(it) }
    }

    // T6.4: playback SMB tem entry point JNI dedicado porque as credenciais
    // vao como parametros separados, nunca uma URI unica com senha embutida
    // cruzando a fronteira JNI (ver nota em rust/bridge/src/lib.rs).
    fun playSmb(server: com.vrplayer.network.SmbServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Smb(server, path, sizeBytes)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        nativePlaySmb(server.host, server.port, server.share, path, server.username, server.password, server.domain)
        resumeAtMs?.let { scheduleResumeSeek(it) }
    }

    /**
     * T9.3, ressalva honesta: nao ha nenhum callback do Rust/C++ avisando
     * "playback pronto para receber seek" (o unico sinal de progresso que
     * existe, `updateMediaProgress`, so comeca a chegar DEPOIS que o
     * playback ja carregou — ver T2.6 sobre `load_at`/threads assincronas).
     * Isto e um delay heuristico fixo, no mesmo espirito do
     * `AUTO_PLAY_DELAY_MS` ja usado pelo hook de soak-test acima, so que bem
     * mais curto (retomar uma midia ja tocando e mais leve que o cold-start
     * inteiro do app). NUNCA validado em headset real — se o playback
     * demorar mais que isso pra carregar (rede lenta em SMB/HTTP, por
     * exemplo), o seek pode disparar cedo demais e ser ignorado/incorreto.
     * Ver nota em T9.3 no PHASE-0.1-MVP.md.
     */
    private fun scheduleResumeSeek(resumeAtMs: Long) {
        autoPlayHandler.postDelayed({
            nativeSeekVideo(resumeAtMs / 1000f)
        }, RESUME_SEEK_DELAY_MS)
    }

    private fun processVideoUri(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                val fdPath = "/proc/self/fd/$fd"
                nativePlayVideo(fdPath)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    external fun nativePlayVideo(path: String)
    external fun nativeTogglePlayPause()
    external fun nativeSeekVideo(positionSeconds: Float)
    external fun nativeSetVolume(volume: Float)
    external fun nativeSetSpeed(speed: Float)
    external fun nativeCycleAudioTrack()

    // T6.4: playback SMB (credenciais como parametros separados, ver playSmb()).
    external fun nativePlaySmb(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String
    )

    // T6.1/T6.4: listagem SMB (bloqueante — SEMPRE chamar de uma coroutine em
    // Dispatchers.IO, nunca da UI thread). Retorno: linhas separadas por \n,
    // ou "ERROR:<mensagem>" (ver rust/bridge/src/lib.rs).
    external fun nativeSmbListShares(host: String, port: Int, username: String, password: String, domain: String): String
    external fun nativeSmbListDirectory(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        share: String,
        path: String
    ): String

    // T7.1: probe HEAD-based de URL HTTP(S) (bloqueante, mesma ressalva acima).
    // Retorno: "OK\t{seekable 0|1}\t{content_length ou -1}" ou "ERROR:<mensagem>".
    external fun nativeProbeHttpUrl(url: String): String
}
