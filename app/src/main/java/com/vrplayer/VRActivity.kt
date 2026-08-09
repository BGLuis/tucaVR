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
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.vrplayer.history.PlaybackHistoryTracker
import com.vrplayer.navigation.PlaybackSource

class VRActivity : NativeActivity() {
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var presentation: VRPresentation? = null

    private var controlsVirtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var controlsPresentation: VRControlsPresentation? = null

    // ==================== TECLADO NATIVO (ver VRPresentation.buildVoidEditText) ====================
    // `nativeKeyboardProxy` e um EditText REAL, anexado direto na janela
    // desta Activity (`addContentView`, nao numa VirtualDisplay) — e o unico
    // jeito de um EditText deste app receber o IME de verdade (ver
    // raciocinio completo em VRPresentation.kt, secao "TECLADO NATIVO").
    // Apps `com.oculus.vr.mode=vr_only` (ver AndroidManifest.xml) nao
    // compositam Views 2D extras da Activity no headset — so a superficie
    // OpenXR e mostrada — entao este proxy nunca aparece visualmente pro
    // usuario; ele so precisa EXISTIR com foco real de janela pra o Horizon
    // OS (com `oculus.software.overlay_keyboard` declarado no manifest)
    // mostrar o teclado do sistema como overlay 3D. NUNCA validado em
    // headset real (sem hardware disponivel nesta sessao) — o mecanismo em
    // si e o mesmo da amostra oficial
    // sdk/meta-openxr-sdk/Samples/XrSamples/XrOverlayKeyboard/java/.../MainActivity.java
    // (inclusive o delay de 300ms antes de focar, "pra garantir que a view
    // ja esta anexada a janela" — copiado de la, nao um numero arbitrario).
    private lateinit var nativeKeyboardProxy: EditText
    private var keyboardMirrorWatcher: TextWatcher? = null
    private var keyboardMirrorTarget: EditText? = null

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

        // Ver bloco de comentario acima de `nativeKeyboardProxy`.
        nativeKeyboardProxy = EditText(this)
        addContentView(nativeKeyboardProxy, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // KEYBOARD_ACTIVE (ver rust/bridge/src/lib.rs): em vez de uma flag
        // manual setada "na mao" em showNativeKeyboardFor/hideNativeKeyboard
        // (primeira versao desta correcao — fragil, porque o teclado pode
        // fechar por um caminho que NAO passa por `hideNativeKeyboard`, ex.
        // o usuario aperta o botao de fechar do proprio teclado nativo, e a
        // flag ficaria presa em "ativo" pra sempre), usa a visibilidade REAL
        // do IME que o proprio Android/sistema reporta via
        // `WindowInsetsCompat.Type.ime()`. Isso e disparado toda vez que a
        // visibilidade do IME muda de verdade, seja por que caminho for —
        // fonte da verdade unica, nunca dessincroniza.
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            nativeSetKeyboardActive(insets.isVisible(WindowInsetsCompat.Type.ime()))
            insets
        }
    }

    /**
     * Chamado por `VRPresentation.buildVoidEditText` quando um campo de
     * texto do painel VR ganha foco. Foca o [nativeKeyboardProxy] (EditText
     * real, ver comentario na declaracao) pra abrir o teclado nativo do
     * Meta Quest, pre-preenchido com o texto atual de [target], e liga um
     * `TextWatcher` que espelha cada mudanca de volta pra [target] — o
     * usuario ve o texto aparecer no campo do painel VR normalmente, mesmo
     * digitando num EditText que fisicamente vive noutra janela.
     */
    fun showNativeKeyboardFor(target: EditText) {
        // `nativeSetKeyboardActive` NAO e chamado aqui de proposito — o
        // listener de WindowInsets em onCreate ja cobre isso a partir da
        // visibilidade real do IME (ver comentario la pro raciocinio
        // completo de por que isso e mais confiavel que setar na mao).
        keyboardMirrorWatcher?.let { nativeKeyboardProxy.removeTextChangedListener(it) }

        keyboardMirrorTarget = target
        nativeKeyboardProxy.setText(target.text)
        nativeKeyboardProxy.setSelection(nativeKeyboardProxy.text.length)

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val current = keyboardMirrorTarget ?: return
                val text = s.toString()
                if (current.text.toString() != text) {
                    current.setText(text)
                    val sel = nativeKeyboardProxy.selectionStart.coerceIn(0, current.text.length)
                    current.setSelection(sel)
                }
            }
        }
        keyboardMirrorWatcher = watcher
        nativeKeyboardProxy.addTextChangedListener(watcher)

        // Delay de 300ms: mesmo valor usado pela amostra oficial (ver
        // comentario na declaracao de `nativeKeyboardProxy`) — sem ele, o
        // `requestFocus()` pode disparar antes da view estar de fato anexada
        // a janela (especialmente na primeira chamada, logo apos onCreate).
        nativeKeyboardProxy.postDelayed({
            nativeKeyboardProxy.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(nativeKeyboardProxy, InputMethodManager.SHOW_IMPLICIT)
        }, 300L)
    }

    /** Contraparte de [showNativeKeyboardFor] — chamado ao perder foco ou trocar de tela. */
    fun hideNativeKeyboard() {
        keyboardMirrorWatcher?.let { nativeKeyboardProxy.removeTextChangedListener(it) }
        keyboardMirrorWatcher = null
        keyboardMirrorTarget = null
        nativeKeyboardProxy.postDelayed({
            nativeKeyboardProxy.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(nativeKeyboardProxy.windowToken, 0)
        }, 300L)
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

    // T6.4: mesma logica de playSmb acima, so que sem `share`/`domain` (FTP
    // nao tem esses conceitos).
    fun playFtp(server: com.vrplayer.network.FtpServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Ftp(server, path, sizeBytes)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        nativePlayFtp(server.host, server.port, path, server.username, server.password)
        resumeAtMs?.let { scheduleResumeSeek(it) }
    }

    // T6.4: mesma logica acima, com `privateKey` (conteudo PEM, ver
    // `com.vrplayer.network.SftpServer`) no lugar de `share`/`domain`.
    fun playSftp(server: com.vrplayer.network.SftpServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Sftp(server, path, sizeBytes)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        nativePlaySftp(server.host, server.port, path, server.username, server.password, server.privateKey ?: "")
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

    // T1.4/T1.5: modo de exibicao 3D (ver ScreenMode em
    // native/src/vr_player_app.cpp e a codificacao em rust/bridge/src/lib.rs)
    // e swap-eyes. Chamadas baratas (so um atomic no lado Rust, sem I/O) —
    // seguro chamar direto da UI thread, sem coroutine/Dispatchers.IO.
    external fun nativeCycle3DMode(): Int
    external fun nativeGet3DMode(): Int
    external fun nativeToggleSwapEyes(): Int

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

    // T6.4: playback FTP (credenciais como parametros separados, ver playFtp()).
    external fun nativePlayFtp(host: String, port: Int, path: String, username: String, password: String)

    // T6.1/T6.4: listagem FTP (bloqueante — SEMPRE de Dispatchers.IO).
    // Retorno: linhas separadas por \n, ou "ERROR:<mensagem>".
    external fun nativeFtpListDirectory(host: String, port: Int, username: String, password: String, path: String): String

    // T6.4: playback SFTP. `privateKey`: conteudo PEM da chave privada (nao
    // um caminho de arquivo, ver rust/protocols/src/sftp/uri.rs), string
    // vazia = autenticacao por senha.
    external fun nativePlaySftp(host: String, port: Int, path: String, username: String, password: String, privateKey: String)

    // T6.2/T6.4: listagem SFTP (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeSftpListDirectory(host: String, port: Int, username: String, password: String, privateKey: String, path: String): String

    // Bug de auto-hide durante digitacao (ver showNativeKeyboardFor/
    // hideNativeKeyboard abaixo, e KEYBOARD_ACTIVE em rust/bridge/src/lib.rs):
    // avisa o render loop C++ pra suprimir o fade-out do painel enquanto o
    // teclado nativo estiver aberto.
    external fun nativeSetKeyboardActive(active: Boolean)
}
