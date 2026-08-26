package com.tucavr

import android.app.ActivityManager
import android.app.NativeActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

import android.hardware.display.DisplayManager
import android.view.Surface
import android.content.pm.ApplicationInfo
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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.tucavr.debug.DebugTelemetryExporter
import com.tucavr.debug.VRLog
import com.tucavr.designsystem.KeyboardBinding
import com.tucavr.history.AppDatabase
import com.tucavr.history.PlaybackHistoryTracker
import com.tucavr.history.historyKey
import com.tucavr.navigation.PlaybackSource
import com.tucavr.network.Format3DPreferenceStore
import com.tucavr.network.LegacyCredentialMigrator
import com.tucavr.network.ServerCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    private var keyboardMirrorTarget: KeyboardBinding? = null
    private var pendingHideRunnable: Runnable? = null

    // Hook de teste (soak test via adb, ver scripts/soak-test.sh): permite disparar
    // playback sem controller, ex. `adb shell am start -n com.tucavr/.VRActivity
    // -e video_path /sdcard/Movies/test.mp4`. Sem isso o soak test só mediria app
    // ocioso, não reprodução de vídeo de fato (o cenário que a DoD pede medir).
    // Atrasado pós-onResume porque a sessão OpenXR nativa (render loop em C++) é
    // inicializada de forma assíncrona pelo NativeActivity — chamar nativePlayVideo
    // cedo demais arrisca chamar antes da textura/surface estarem prontas.
    private var pendingAutoPlayPath: String? = null
    private var pendingAutoPlayScreenMode: Int = -1
    private var autoPlayDispatched = false
    private val autoPlayHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Poll do erro de load() que falhou (codec nao suportado, etc.) — ver nativeTakeLastPlaybackError.
    private val playbackErrorPoll = object : Runnable {
        override fun run() {
            nativeTakeLastPlaybackError()?.let { Toast.makeText(this@VRActivity, it, Toast.LENGTH_LONG).show() }
            autoPlayHandler.postDelayed(this, PLAYBACK_ERROR_POLL_MS)
        }
    }

    // T9.1-T9.3: unico dono do historico de reproducao neste processo (mesmo
    // ciclo de vida da Activity). `by lazy` porque so e usado depois que a
    // Activity ja existe (primeiro uso real e dentro de playFile/playUrl/
    // playSmb, chamados via VRPresentation apos onCreate).
    val historyTracker: PlaybackHistoryTracker by lazy { PlaybackHistoryTracker(this) }
    val format3dStore: Format3DPreferenceStore by lazy { Format3DPreferenceStore(this) }
    val thermalMonitor: ThermalMonitor by lazy { ThermalMonitor(this) }

    private val thermalCallback: (ThermalMonitor.ThermalState) -> Unit = { state ->
        onThermalStateChanged(state)
    }

    private fun onThermalStateChanged(state: ThermalMonitor.ThermalState) {
        nativeSetThermalLevel(state.level.rawLevel)
        controlsPresentation?.onThermalStateChanged(state)

        if (state.actions.contains(ThermalMonitor.ThermalAction.PAUSE_PLAYBACK)) {
            // T14.1/T14.2: Em nível crítico/shutdown, pausa a reprodução imediatamente para resfriamento
            nativeTogglePlayPause()
            Toast.makeText(this, getString(R.string.thermal_critical_pause), Toast.LENGTH_LONG).show()
        } else if (state.actions.contains(ThermalMonitor.ThermalAction.WARN_USER) && state.level == ThermalMonitor.ThermalLevel.SEVERE) {
            Toast.makeText(this, getString(R.string.thermal_warning_reducing_quality), Toast.LENGTH_SHORT).show()
        }
    }

    // Fonte da reproducao atual (T-seek-ux) — setado nos 5 entry points de
    // playback abaixo. `PlaybackHistoryTracker.getCurrent()` ja guarda algo
    // parecido, mas ja convertido pro registro achatado do Room (perde
    // host/credenciais); isto aqui e o `PlaybackSource` original, usado
    // pra saber SE/COMO gerar a trilha de thumbnails de preview de arrasto
    // (so SMB/SFTP tem isso, ver NetworkThumbnailGenerator.getScrubStrip).
    var currentPlaybackSource: PlaybackSource? = null
        private set

    // Ferramenta de debug (ver docs/DEBUGGING.md): troca o ScreenMode de um
    // video JA tocando sem precisar reiniciar o app/relançar o intent, ex.
    // `adb shell am broadcast -a com.tucavr.debug.SET_SCREEN_MODE --ei mode 6`
    // (6 = Sphere180, ver a ordem do enum ScreenMode em vr_player_app.cpp/
    // vr_player_app_vulkan.cpp). So registrado se o app estiver debuggable
    // (`FLAG_DEBUGGABLE` — verdadeiro pro build `debug` do Gradle por
    // padrao) — nunca ativo num APK de release.
    private var debugReceiver: BroadcastReceiver? = null

    // Fonte unica pra "isto e um build debug" — usado tanto pro receiver
    // acima quanto pro HUD de debug (ver updateDebugHud/VRControlsPresentation).
    val isDebuggable: Boolean by lazy {
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun registerDebugReceiverIfDebuggable() {
        if (!isDebuggable) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_DEBUG_SET_SCREEN_MODE -> {
                        val mode = intent.getIntExtra(EXTRA_SCREEN_MODE, -1)
                        if (mode >= 0) nativeSetScreenMode(mode)
                    }
                    ACTION_DEBUG_CYCLE_SCREEN_MODE -> nativeCycle3DMode()
                    ACTION_DEBUG_SET_THERMAL_STATUS -> {
                        val status = intent.getIntExtra(EXTRA_THERMAL_STATUS, -1)
                        if (status >= 0) thermalMonitor.simulateThermalStatus(status)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_DEBUG_SET_SCREEN_MODE)
            addAction(ACTION_DEBUG_CYCLE_SCREEN_MODE)
            addAction(ACTION_DEBUG_SET_THERMAL_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        debugReceiver = receiver
    }

    /**
     * Carimba nome + icone + cor na `ActivityManager.TaskDescription` da task.
     *
     * Por que isso existe: o app roda como `vr_only` / [NativeActivity], sem
     * nenhuma janela 2D. As superficies do sistema que listam apps em execucao
     * (recents do Android, e por tabela os paineis do Horizon OS montados em
     * cima dela) leem o rotulo da *task*, nao o `android:label` do pacote —
     * e sem TaskDescription a task de um app sem janela 2D pode acabar
     * renderizada sem nome nenhum ao sair do app. Setar isso explicitamente
     * remove a dependencia de qualquer fallback implicito.
     *
     * O icone so pode ser passado como resource id a partir da API 28 e via
     * `Builder` a partir da 33; abaixo disso resta o construtor so-com-rotulo,
     * que ainda garante a parte que importa aqui (o nome).
     */
    private fun applyTaskDescription() {
        val label = getString(R.string.app_name)
        // setPrimaryColor exige cor 100% opaca — colorBackground e #121212 (alpha FF).
        val primaryColor = com.tucavr.designsystem.VoidTheme.colorBackground
        val description = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ActivityManager.TaskDescription.Builder()
                    .setLabel(label)
                    .setIcon(R.mipmap.ic_launcher)
                    .setPrimaryColor(primaryColor)
                    .build()

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label, R.mipmap.ic_launcher, primaryColor)

            else ->
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label)
        }
        setTaskDescription(description)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.tucavr.designsystem.VoidTheme.init(this)

        applyTaskDescription()

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
        pendingAutoPlayScreenMode = intent?.getIntExtra(EXTRA_SCREEN_MODE, -1) ?: -1

        // T11.1: Migra credenciais legadas para o Room / ServerCredentialStore se necessario
        CoroutineScope(Dispatchers.IO).launch {
            LegacyCredentialMigrator(
                this@VRActivity,
                AppDatabase.getInstance(this@VRActivity).savedServerDao(),
                ServerCredentialStore(this@VRActivity)
            ).migrateIfNeeded()
        }

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

        registerDebugReceiverIfDebuggable()

        // Fase 0.4 T5: empurra o valor persistido do toggle de Foveated
        // Rendering pro Rust — o C++ (caminho Vulkan) le isso na
        // inicializacao do XrInstance/swapchains. Trocas em runtime (tela de
        // Configuracoes) empurram de novo na hora, ver SettingsScreen.kt.
        nativeSetFoveationEnabled(FeatureFlags.isEnabled(this, FeatureFlags.Flag.FOVEATED_RENDERING))

        // Fase 0.3 Seção 3/4: empurra valores persistidos de Áudio Espacial e Head Tracking pro nativo
        val spatialAudio = FeatureFlags.isEnabled(this, FeatureFlags.Flag.SPATIAL_AUDIO)
        nativeSetSpatialAudioMode(if (spatialAudio) 1 else 0)
        nativeSetSpatialAudioHeadTracking(FeatureFlags.isEnabled(this, FeatureFlags.Flag.SPATIAL_HEAD_TRACKING))

        // N6: Captura de crashes não tratados para arquivo de diagnóstico com session ID
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sid = currentSessionId ?: "--------"
            VRLog.e("Crash nao capturado na thread ${thread.name} (sessao $sid)", throwable)
            try {
                val debugDir = getExternalFilesDir("debug")
                if (debugDir != null) {
                    if (!debugDir.exists()) debugDir.mkdirs()
                    val timeStr = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    val crashFile = File(debugDir, "crash-$sid-$timeStr.txt")
                    java.io.PrintWriter(java.io.FileWriter(crashFile)).use { writer ->
                        writer.println("Session ID: $sid")
                        writer.println("Timestamp: ${System.currentTimeMillis()}")
                        writer.println("Thread: ${thread.name} (ID: ${thread.id})")
                        writer.println("Current Source: $currentPlaybackSource")
                        writer.println("StackTrace:")
                        throwable.printStackTrace(writer)
                    }
                }
            } catch (_: Exception) {}
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onDestroy() {
        DebugTelemetryExporter.onSessionEnded()
        debugReceiver?.let { unregisterReceiver(it) }
        debugReceiver = null
        super.onDestroy()
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
    /**
     * Chamado por [VRPresentation] quando um [KeyboardBinding] (campo de texto do painel VR)
     * ganha foco. Foca o [nativeKeyboardProxy] para abrir o teclado nativo do Meta Quest,
     * pre-preenchido com o texto atual do [target], herda o `inputType` e `imeOptions`,
     * e liga um [TextWatcher] que espelha as alterações de volta para o [target].
     */
    fun showNativeKeyboardFor(target: KeyboardBinding) {
        // Cancela qualquer fechamento de teclado pendente (evita corrida ao alternar entre campos)
        pendingHideRunnable?.let {
            nativeKeyboardProxy.removeCallbacks(it)
            pendingHideRunnable = null
        }

        keyboardMirrorWatcher?.let { nativeKeyboardProxy.removeTextChangedListener(it) }

        keyboardMirrorTarget = target
        nativeKeyboardProxy.inputType = target.inputType
        nativeKeyboardProxy.imeOptions = target.imeOptions

        val currentText = target.currentText()
        nativeKeyboardProxy.setText(currentText)
        nativeKeyboardProxy.setSelection(nativeKeyboardProxy.text.length)

        nativeKeyboardProxy.setOnEditorActionListener { _, actionId, _ ->
            keyboardMirrorTarget?.onImeAction(actionId)
            true
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val current = keyboardMirrorTarget ?: return
                val text = s.toString()
                if (current.currentText().toString() != text) {
                    val sel = nativeKeyboardProxy.selectionStart.coerceIn(0, text.length)
                    current.onKeyboardText(text, sel)
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

    /**
     * Sincroniza o texto do proxy da Activity quando o campo do painel sofre alteração
     * programática (ex.: colar, limpar, autocorreção).
     */
    fun syncKeyboardText(binding: KeyboardBinding) {
        if (keyboardMirrorTarget === binding) {
            val text = binding.currentText().toString()
            if (nativeKeyboardProxy.text.toString() != text) {
                keyboardMirrorWatcher?.let { nativeKeyboardProxy.removeTextChangedListener(it) }
                nativeKeyboardProxy.setText(text)
                nativeKeyboardProxy.setSelection(text.length)
                keyboardMirrorWatcher?.let { nativeKeyboardProxy.addTextChangedListener(it) }
            }
        }
    }

    /** Contraparte de [showNativeKeyboardFor] — chamado ao perder foco ou trocar de tela. */
    fun hideNativeKeyboard() {
        keyboardMirrorWatcher?.let { nativeKeyboardProxy.removeTextChangedListener(it) }
        keyboardMirrorWatcher = null
        keyboardMirrorTarget = null

        val runnable = object : Runnable {
            override fun run() {
                nativeKeyboardProxy.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(nativeKeyboardProxy.windowToken, 0)
                if (pendingHideRunnable === this) {
                    pendingHideRunnable = null
                }
            }
        }
        pendingHideRunnable = runnable
        nativeKeyboardProxy.postDelayed(runnable, 300L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_AUTO_PLAY_PATH)?.let {
            pendingAutoPlayPath = it
            pendingAutoPlayScreenMode = intent.getIntExtra(EXTRA_SCREEN_MODE, -1)
            autoPlayDispatched = false
        }
        intent.getStringExtra(EXTRA_CAPTURE_PATH)?.let { nativeRequestFrameCapture(it) }
    }

    override fun onResume() {
        super.onResume()
        presentation?.loadFiles()
        thermalMonitor.startMonitoring(callback = thermalCallback)
        pendingAutoPlayPath?.let { path ->
            if (!autoPlayDispatched) {
                autoPlayDispatched = true
                val mode = pendingAutoPlayScreenMode
                autoPlayHandler.postDelayed({
                    playFile(path)
                    if (mode >= 0) nativeSetScreenMode(mode)
                }, AUTO_PLAY_DELAY_MS)
            }
        }
        autoPlayHandler.post(playbackErrorPoll)
    }

    override fun onPause() {
        super.onPause()
        thermalMonitor.stopMonitoring(thermalCallback)
        autoPlayHandler.removeCallbacks(playbackErrorPoll)
    }

    companion object {
        init {
            System.loadLibrary("vrplayer_native")
        }

        private const val PICK_VIDEO_REQUEST_CODE = 1001

        // Ver hook de auto-play em onCreate/onNewIntent/onResume.
        const val EXTRA_AUTO_PLAY_PATH = "video_path"
        const val EXTRA_SCREEN_MODE = "screen_mode"
        const val EXTRA_CAPTURE_PATH = "capture_path"

        // Ver registerDebugReceiverIfDebuggable — troca o ScreenMode ou simula status termico
        // de um video ja tocando via adb, sem relançar o intent/autoplay acima.
        const val ACTION_DEBUG_SET_SCREEN_MODE = "com.tucavr.debug.SET_SCREEN_MODE"
        const val ACTION_DEBUG_CYCLE_SCREEN_MODE = "com.tucavr.debug.CYCLE_SCREEN_MODE"
        const val ACTION_DEBUG_SET_THERMAL_STATUS = "com.tucavr.debug.SET_THERMAL_STATUS"
        const val EXTRA_THERMAL_STATUS = "status"
        private const val AUTO_PLAY_DELAY_MS = 3000L
        private const val PLAYBACK_ERROR_POLL_MS = 1000L

        // Dimensões nominais dos VirtualDisplays de UI e Controles (devem bater com o nativo C++).
        const val UI_DISPLAY_WIDTH = 1024
        const val UI_DISPLAY_HEIGHT = 768
        const val CONTROLS_DISPLAY_WIDTH = 1582
        const val CONTROLS_DISPLAY_HEIGHT = 800

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
                    x * UI_DISPLAY_WIDTH.toFloat(),
                    y * UI_DISPLAY_HEIGHT.toFloat(),
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
                    x * CONTROLS_DISPLAY_WIDTH.toFloat(),
                    y * CONTROLS_DISPLAY_HEIGHT.toFloat(),
                    0
                )
                
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                
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

        /**
         * Feedback de loading/play-pause — mesmo padrao/cadencia de
         * updateMediaProgress (chamado ~10x/s pelo C++ via JNI), mas SEM o
         * gate de `total > 0`: precisa dar sinal ja no primeiro load, antes
         * de qualquer duracao ser conhecida (ver comentario no C++). Estado
         * real da sessao Rust, nao um espelho otimista de cliques no
         * Kotlin — reage tambem a play/pause disparado pelo botao do
         * controle VR (ver toggle_video_state em vr_player_app.cpp).
         */
        @JvmStatic
        fun updateMediaState(activity: VRActivity, isLoading: Boolean, isPlaying: Boolean) {
            activity.runOnUiThread {
                activity.controlsPresentation?.updateMediaState(isLoading, isPlaying)
            }
        }

        // HUD de debug (ver docs/DEBUGGING.md): mesmo throttle/JNI pattern
        // de updateMediaProgress acima, so que o texto ja vem formatado do
        // lado nativo (ScreenMode/stereoLayout/polar180/swapEyes/estado do
        // frame de video) — sem necessidade de strings de recurso/i18n,
        // este texto e puramente de diagnostico e so aparece em builds
        // debuggable (`activity.isDebuggable`, checado do lado Kotlin em vez
        // de condicionalmente compilar o lado nativo — mais simples e sem
        // custo real, ver comentario em VRControlsPresentation.updateDebugHud).
        @JvmStatic
        fun updateDebugHud(activity: VRActivity, text: String) {
            val sid = activity.currentSessionId ?: "--------"
            DebugTelemetryExporter.recordHudSample(
                context = activity,
                sessionId = sid,
                hudText = text,
                source = activity.currentPlaybackSource
            )

            if (!activity.isDebuggable) return
            activity.runOnUiThread {
                activity.controlsPresentation?.updateDebugHud(text)
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

    @Volatile
    var currentSessionId: String? = null
        private set

    private fun startSession(source: PlaybackSource) {
        val sessionId = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
        currentSessionId = sessionId
        VRLog.activeSessionId = sessionId
        VRLog.i("Iniciando sessao de reproducao $sessionId para $source")
        nativeSetSessionId(sessionId)
    }

    // T9.1-T9.3: os 3 entry points de playback (playFile/playUrl/playSmb) sao
    // exatamente onde path/titulo/fonte estao disponiveis para popular o
    // historico — chamam `historyTracker.startTracking` ANTES de iniciar a
    // reproducao nativa. `resumeAtMs` (T9.3, opcional, do prompt "Retomar de
    // XX:XX?" — ver VRPresentation.renderResumePrompt) vai direto como
    // posicao inicial pro load nativo (rust/core/src/playback.rs::load_at ja
    // faz seek + pre-roll durante a ABERTURA), em vez do antigo padrao de
    // carregar do zero e soh depois agendar um segundo nativeSeekVideo com
    // delay fixo — isso fazia DOIS carregamentos completos por resume (mais
    // lento) e, se o usuario trocasse de video antes do delay expirar, o
    // seek atrasado disparava contra a sessao NOVA (ja de outro video).

    private fun applyFormat3dOverride(source: PlaybackSource) {
        val cached = format3dStore.get(source.historyKey())
        nativeSetScreenModeOverride(cached ?: -1)
    }

    private fun resolveSourceTitle(src: PlaybackSource): String = when (src) {
        is PlaybackSource.LocalFile -> File(src.path).name
        is PlaybackSource.Http -> src.url
        is PlaybackSource.Smb -> src.path.substringAfterLast("/")
        is PlaybackSource.Ftp -> src.path.substringAfterLast("/")
        is PlaybackSource.Sftp -> src.path.substringAfterLast("/")
        is PlaybackSource.Nfs -> src.path.substringAfterLast("/")
        is PlaybackSource.Dlna -> src.title
    }

    fun playFile(filePath: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val resolvedSize = if (sizeBytes > 0L) sizeBytes else runCatching { File(filePath).length() }.getOrDefault(0L)
        val source = PlaybackSource.LocalFile(filePath, resolvedSize)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = File(filePath).name)
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlayVideo(filePath, (resumeAtMs ?: 0L) / 1000f)
    }

    // T7: URL HTTP(S) reusa o mesmo entry point que arquivo local — o
    // Demuxer (Rust) despacha por esquema (ver rust/core/src/demuxer.rs).
    fun playUrl(url: String, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Http(url)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = url)
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlayVideo(url, (resumeAtMs ?: 0L) / 1000f)
    }

    // T6.4: playback SMB tem entry point JNI dedicado porque as credenciais
    // vao como parametros separados, nunca uma URI unica com senha embutida
    // cruzando a fronteira JNI (ver nota em rust/bridge/src/lib.rs).
    fun playSmb(server: com.tucavr.network.SmbServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Smb(server, path, sizeBytes)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlaySmb(server.host, server.port, server.share, path, server.username, server.password, server.domain, (resumeAtMs ?: 0L) / 1000f)
    }

    // T6.4: mesma logica de playSmb acima, so que sem `share`/`domain` (FTP
    // nao tem esses conceitos).
    fun playFtp(server: com.tucavr.network.FtpServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Ftp(server, path, sizeBytes)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlayFtp(server.host, server.port, path, server.username, server.password, (resumeAtMs ?: 0L) / 1000f)
    }

    // T6.4: mesma logica acima, com `privateKey` (conteudo PEM, ver
    // `com.tucavr.network.SftpServer`) no lugar de `share`/`domain`.
    fun playSftp(server: com.tucavr.network.SftpServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Sftp(server, path, sizeBytes)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlaySftp(server.host, server.port, path, server.username, server.password, server.privateKey ?: "", (resumeAtMs ?: 0L) / 1000f)
    }

    // T5.4: playback NFS
    fun playNfs(server: com.tucavr.network.SavedServer, path: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Nfs(server, path, sizeBytes)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = path.substringAfterLast('/'))
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlayNfs(server.host, server.port, server.path, path, 3, (resumeAtMs ?: 0L) / 1000f)
    }

    // T7.4: playback DLNA
    fun playDlna(server: com.tucavr.network.SavedServer, title: String, url: String, sizeBytes: Long = 0L, resumeAtMs: Long? = null) {
        val source = PlaybackSource.Dlna(server, title, url, sizeBytes)
        currentPlaybackSource = source
        startSession(source)
        historyTracker.startTracking(source, title = title)
        controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
        applyFormat3dOverride(source)
        nativePlayVideo(url, (resumeAtMs ?: 0L) / 1000f)
    }

    private fun processVideoUri(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val fd = pfd.detachFd()
                val fdPath = "/proc/self/fd/$fd"
                val source = PlaybackSource.LocalFile(fdPath, 0L)
                currentPlaybackSource = source
                controlsPresentation?.updateTitle(currentPlaybackSource?.let { resolveSourceTitle(it) } ?: "Desconhecido")
                applyFormat3dOverride(source)
                nativePlayVideo(fdPath, 0f)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    external fun nativePlayVideo(path: String, startTimeSec: Float)
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
    external fun nativeSetScreenMode(mode: Int)
    external fun nativeSetScreenModeOverride(mode: Int)
    external fun nativeToggleSwapEyes(): Int
    external fun nativeRequestUiPanelVisible()
    external fun nativeRequestFrameCapture(path: String)
    external fun nativeTakeLastPlaybackError(): String?

    /**
     * Acorda o quad de UI (VRPresentation) e exibe o modal de formato de tela (T3.4).
     */
    fun openScreenFormatModal() {
        runOnUiThread {
            nativeRequestUiPanelVisible()
            presentation?.showScreenFormatModal()
        }
    }

    // T6.4: playback SMB (credenciais como parametros separados, ver playSmb()).
    external fun nativePlaySmb(
        host: String,
        port: Int,
        share: String,
        path: String,
        username: String,
        password: String,
        domain: String,
        startTimeSec: Float
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
    external fun nativePlayFtp(host: String, port: Int, path: String, username: String, password: String, startTimeSec: Float)

    // T6.1/T6.4: listagem FTP (bloqueante — SEMPRE de Dispatchers.IO).
    // Retorno: linhas separadas por \n, ou "ERROR:<mensagem>".
    external fun nativeFtpListDirectory(host: String, port: Int, username: String, password: String, path: String): String

    // T6.4: playback SFTP. `privateKey`: conteudo PEM da chave privada (nao
    // um caminho de arquivo, ver rust/protocols/src/sftp/uri.rs), string
    // vazia = autenticacao por senha.
    external fun nativePlaySftp(host: String, port: Int, path: String, username: String, password: String, privateKey: String, startTimeSec: Float)

    // T6.2/T6.4: listagem SFTP (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeSftpListDirectory(host: String, port: Int, username: String, password: String, privateKey: String, path: String): String

    // T5.1/T5.4: playback NFS
    external fun nativePlayNfs(host: String, port: Int, exportPath: String, filePath: String, version: Int, startTimeSec: Float)

    // T5.2/T5.4: listagem de diretório NFS (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeNfsListDirectory(host: String, port: Int, exportPath: String, dirPath: String, version: Int): String

    // T5.2/T5.4: listagem de exports NFS (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeNfsListExports(host: String, port: Int): String

    // T10.1: Varredura de servidores na rede local (bloqueante — SEMPRE de Dispatchers.IO).
    // Retorno: linhas separadas por \n, cada uma com "PROTOCOL\tNAME\tHOST\tPORT\tPATH"
    external fun nativeDiscoveryScan(timeoutMs: Int): String

    // T7.2: Device Description UPnP/DLNA (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeDlnaGetDevice(location: String): String

    // T7.3: Browse ContentDirectory UPnP/DLNA (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeDlnaBrowse(controlUrl: String, objectId: String, startIndex: Int, maxCount: Int): String

    // T8.1/T8.6: Probe de variantes HLS (bloqueante — SEMPRE de Dispatchers.IO).
    external fun nativeHlsProbeVariants(url: String): String

    // T9: thumbnail de arquivo de video num share/servidor de rede — decode
    // de UM frame por software do lado Rust (core::thumbnail::generate, ver
    // rust/bridge/src/lib.rs). Bloqueante (rede + decode sincronos) — SEMPRE
    // de Dispatchers.IO, nunca da UI thread (ver NetworkThumbnailGenerator.kt).
    // Retorno: RGBA cru (maxWidth*maxHeight*4 bytes, sem padding de linha) ou
    // null em qualquer falha (sem faixa de video, arquivo inacessivel, etc.).
    external fun nativeSmbGenerateThumbnail(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        share: String,
        path: String,
        maxWidth: Int,
        maxHeight: Int
    ): ByteArray?

    external fun nativeFtpGenerateThumbnail(
        host: String,
        port: Int,
        username: String,
        password: String,
        path: String,
        maxWidth: Int,
        maxHeight: Int
    ): ByteArray?

    external fun nativeSftpGenerateThumbnail(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String,
        path: String,
        maxWidth: Int,
        maxHeight: Int
    ): ByteArray?

    // Preview de arrasto no seekbar (T-seek-ux): mesma logica das duas
    // funcoes acima, so que devolve N frames concatenados (um a cada
    // intervalSeconds) em vez de 1 — ver core::thumbnail::generate_strip.
    // Retorno: RGBA cru, byteArray.size / (maxWidth*maxHeight*4) frames na
    // ordem do arquivo (posicao do frame i = (i+1)*intervalSeconds), ou
    // null em qualquer falha. So SMB/SFTP tem geracao de thumbnail hoje.
    external fun nativeSmbGenerateThumbnailStrip(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        share: String,
        path: String,
        intervalSeconds: Float,
        maxWidth: Int,
        maxHeight: Int
    ): ByteArray?

    external fun nativeSftpGenerateThumbnailStrip(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String,
        path: String,
        intervalSeconds: Float,
        maxWidth: Int,
        maxHeight: Int
    ): ByteArray?

    // Interrompe uma geracao de tira em andamento (as duas funcoes acima sao
    // bloqueantes/sincronas do lado Rust — cancelar a Job do Kotlin nao as
    // interrompe). Ver VRControlsPresentation.stopScrubPreview().
    external fun nativeCancelScrubStrip()

    // Preview de arrasto renderizado direto sobre o quad do video (T-seek-ux)
    // em modos planos (2D/SBS/OU) — ver VRControlsPresentation.updateScrubPreview.
    // Nao coberto: 360/180 (esfera, sem quad pra sobrepor); nesses modos o
    // painel pequeno de sempre (scrubPreview ImageView) continua sendo usado.
    external fun nativeUpdateScrubOverlay(rgba: ByteArray, width: Int, height: Int)
    external fun nativeSetScrubOverlayVisible(visible: Boolean)

    // Bug de auto-hide durante digitacao (ver showNativeKeyboardFor/
    // hideNativeKeyboard abaixo, e KEYBOARD_ACTIVE em rust/bridge/src/lib.rs):
    // avisa o render loop C++ pra suprimir o fade-out do painel enquanto o
    // teclado nativo estiver aberto.
    external fun nativeSetKeyboardActive(active: Boolean)

    // Fase 0.4 T5: Foveated Rendering. So tem efeito real no caminho Vulkan
    // (padrao de build); GLES aceita a chamada mas nao aplica — ver
    // comentario em native/src/vr_player_app.cpp. Chamada barata (so um
    // atomic no lado Rust), segura direto da UI thread.
    external fun nativeSetFoveationEnabled(enabled: Boolean)

    // T13.1: metadados de midia (container/duracao/bitrate/trilhas) pra tela
    // de detalhe do arquivo — bloqueante (probe de container, rede se remoto),
    // SEMPRE de Dispatchers.IO. Retorno: string na gramatica de
    // rust/media-logic/src/metadata_wire.rs, ou "ERROR:<mensagem>" (ver
    // MediaMetadataReader.parse).
    external fun nativeReadMediaMetadata(path: String): String

    external fun nativeSmbReadMetadata(
        host: String,
        port: Int,
        username: String,
        password: String,
        domain: String,
        share: String,
        path: String
    ): String

    external fun nativeFtpReadMetadata(host: String, port: Int, username: String, password: String, path: String): String

    external fun nativeSftpReadMetadata(
        host: String,
        port: Int,
        username: String,
        password: String,
        privateKey: String,
        path: String
    ): String

    // T13.2: seleciona a trilha de audio pro PROXIMO load — so tem efeito
    // combinada com um play logo em seguida (ver nota em rust/bridge/src/lib.rs
    // sobre select_audio_track). Chamada barata (so grava um campo), segura
    // direto da UI thread.
    external fun nativeSetAudioTrack(ordinal: Int)
    external fun nativeSetSpatialAudioMode(mode: Int)
    external fun nativeSetSpatialAudioHeadTracking(enabled: Boolean)

    // Legendas (SRT / WebVTT — Fase 0.2 T9.1-T9.6)
    external fun nativeSetSubtitleTrack(trackIndex: Int)
    external fun nativeGetSubtitleTrack(): Int
    external fun nativeSetSubtitleOffsetMs(offsetMs: Long)
    external fun nativeGetSubtitleOffsetMs(): Long
    external fun nativeLoadExternalSubtitle(path: String): Boolean
    external fun nativeGetSubtitleTrackCount(): Int

    // T14.1/T14.2: Notifica o pipeline de render nativo (C++/Rust) sobre o nível térmico atual
    external fun nativeSetThermalLevel(level: Int)

    // N1: Propaga o identificador de sessão ativo para C++ e Rust
    external fun nativeSetSessionId(sessionId: String)
}
