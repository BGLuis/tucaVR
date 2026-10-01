package com.tucavr.browser.ui

import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.browser.state.MediaCommand
import com.tucavr.browser.state.WebBrowserEvent
import com.tucavr.browser.state.WebBrowserUiState
import com.tucavr.designsystem.KeyboardBinding
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

@Composable
fun WebBrowserScreen(
    state: WebBrowserUiState,
    mediaCommands: SharedFlow<MediaCommand>,
    geckoSession: GeckoSession? = null,
    onShowNativeKeyboard: ((KeyboardBinding) -> Unit)? = null,
    onHideNativeKeyboard: (() -> Unit)? = null,
    onCloseBrowser: (() -> Unit)? = null,
    onToggleGeometry: ((Boolean) -> Unit)? = null,
    onEvent: (WebBrowserEvent) -> Unit,
    updateWebViewState: (
        url: String?,
        isLoading: Boolean?,
        loadingProgress: Int?,
        pageTitle: String?,
        canGoBack: Boolean?,
        canGoForward: Boolean?,
        isFullScreen: Boolean?,
        errorMessage: String?
    ) -> Unit
) {
    val isPreviewMode = LocalInspectionMode.current
    var geckoViewRef by remember { mutableStateOf<GeckoView?>(null) }

    // Horizon OS native keyboard binding for sending text input to the web page
    val webKeyboardBinding = remember(geckoViewRef) {
        object : KeyboardBinding {
            private var webText = ""
            override fun currentText(): CharSequence = webText
            override fun onKeyboardText(text: CharSequence, selection: Int) {
                val view = geckoViewRef ?: return
                val newStr = text.toString()
                if (newStr.length > webText.length) {
                    val added = newStr.substring(webText.length)
                    for (c in added) {
                        val downEvent = KeyEvent(
                            android.os.SystemClock.uptimeMillis(),
                            c.toString(),
                            0,
                            0
                        )
                        view.dispatchKeyEvent(downEvent)
                    }
                } else if (newStr.length < webText.length) {
                    val diff = webText.length - newStr.length
                    for (i in 0 until diff) {
                        val delDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                        val delUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                        view.dispatchKeyEvent(delDown)
                        view.dispatchKeyEvent(delUp)
                    }
                }
                webText = newStr
            }

            override fun onImeAction(actionId: Int) {
                val view = geckoViewRef ?: return
                val enterDown = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                val enterUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                view.dispatchKeyEvent(enterDown)
                view.dispatchKeyEvent(enterUp)
                onHideNativeKeyboard?.invoke()
            }

            override val inputType: Int = InputType.TYPE_CLASS_TEXT
            override val imeOptions: Int = EditorInfo.IME_ACTION_DONE
        }
    }

    // Configure GeckoSession delegates to update state, fullscreen, and native keyboard
    DisposableEffect(geckoSession) {
        val session = geckoSession ?: return@DisposableEffect onDispose {}

        val navDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                permissions: List<GeckoSession.PermissionDelegate.ContentPermission>
            ) {
                updateWebViewState(url, null, null, null, null, null, null, null)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                updateWebViewState(null, null, null, null, canGoBack, null, null, null)
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                updateWebViewState(null, null, null, null, null, canGoForward, null, null)
            }
        }

        val progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                updateWebViewState(url, true, 0, null, null, null, null, null)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                updateWebViewState(
                    null,
                    false,
                    100,
                    null,
                    null,
                    null,
                    null,
                    if (!success) "Failed to load page" else null
                )
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                updateWebViewState(null, null, progress, null, null, null, null, null)
            }
        }

        val contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                updateWebViewState(null, null, null, title, null, null, null, null)
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                updateWebViewState(null, null, null, null, null, null, fullScreen, null)
            }
        }

        session.navigationDelegate = navDelegate
        session.progressDelegate = progressDelegate
        session.contentDelegate = contentDelegate

        session.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun showSoftInput(session: GeckoSession) {
                onShowNativeKeyboard?.invoke(webKeyboardBinding)
            }

            override fun hideSoftInput(session: GeckoSession) {
                onHideNativeKeyboard?.invoke()
            }
        })

        onDispose {
            if (session.navigationDelegate === navDelegate) session.navigationDelegate = null
            if (session.progressDelegate === progressDelegate) session.progressDelegate = null
            if (session.contentDelegate === contentDelegate) session.contentDelegate = null
            session.textInput.setDelegate(null)
        }
    }

    // Listen for cross-presentation media commands and inject JavaScript into GeckoSession
    LaunchedEffect(mediaCommands, geckoSession) {
        mediaCommands.collectLatest { command ->
            val session = geckoSession ?: return@collectLatest
            val jsCode = when (command) {
                is MediaCommand.Play -> "const v = document.querySelector('video'); if(v) v.play();"
                is MediaCommand.Pause -> "const v = document.querySelector('video'); if(v) v.pause();"
                is MediaCommand.ToggleMute -> "const v = document.querySelector('video'); if(v) v.muted = !v.muted;"
                is MediaCommand.Skip -> "const v = document.querySelector('video'); if(v) v.currentTime += ${command.seconds};"
            }
            session.loadUri("javascript:$jsCode")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hide the top bar in fullscreen mode
            if (!state.isFullScreen) {
                BrowserToolbar(
                    state = state,
                    onEvent = { event ->
                        when (event) {
                            is WebBrowserEvent.GoBack -> geckoSession?.goBack()
                            is WebBrowserEvent.GoForward -> geckoSession?.goForward()
                            is WebBrowserEvent.Reload -> geckoSession?.reload()
                            is WebBrowserEvent.CloseBrowser -> {
                                onCloseBrowser?.invoke()
                                onEvent(event)
                            }
                            is WebBrowserEvent.ToggleGeometry -> {
                                onEvent(event)
                                onToggleGeometry?.invoke(!state.isCurvedGeometry)
                            }
                            is WebBrowserEvent.OnUrlSubmitted -> {
                                onEvent(event)
                                val targetUrl = if (event.url.startsWith("http://") || event.url.startsWith("https://") || event.url.startsWith("about:")) {
                                    event.url
                                } else {
                                    "https://${event.url}"
                                }
                                geckoSession?.loadUri(targetUrl)
                            }
                            else -> onEvent(event)
                        }
                    }
                )
            }

            // Error message
            state.errorMessage?.let { error ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFB00020))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Error loading page: $error",
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(onClick = { onEvent(WebBrowserEvent.ClearError) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = Color.White
                        )
                    }
                }
            }

            // Main content: shortcuts panel (about:blank) or GeckoView viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (state.url == "about:blank" || state.url.isBlank()) {
                    ShortcutsPanel(
                        bookmarks = state.bookmarks,
                        onBookmarkClick = { bookmark ->
                            onEvent(WebBrowserEvent.OnUrlSubmitted(bookmark.url))
                            geckoSession?.loadUri(bookmark.url)
                        },
                        onAddBookmarkClick = {
                            onEvent(WebBrowserEvent.OpenAddBookmarkModal)
                        },
                        onEditBookmarkClick = { bookmark ->
                            onEvent(WebBrowserEvent.OpenEditBookmarkModal(bookmark))
                        },
                        onDeleteBookmarkClick = { bookmark ->
                            onEvent(WebBrowserEvent.DeleteBookmark(bookmark))
                        }
                    )
                } else if (isPreviewMode) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1A1A1A)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "GeckoView Engine (Widevine DRM Enabled)",
                                color = Color(0xFFFF6B00),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "URL: ${state.url}",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                } else {
                    AndroidView(
                        factory = { context ->
                            GeckoView(context).apply {
                                layoutParams = FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                isFocusable = true
                                isFocusableInTouchMode = true
                                geckoSession?.let { setSession(it) }
                                geckoViewRef = this
                            }
                        },
                        update = { geckoView ->
                            geckoViewRef = geckoView
                            geckoSession?.let { session ->
                                geckoView.setSession(session)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Floating button to exit fullscreen mode
                if (state.isFullScreen) {
                    Button(
                        onClick = { geckoSession?.exitFullScreen() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xCC1E1E1E),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    ) {
                        Text("✕ Exit Fullscreen")
                    }
                }
            }
        }

        // Add/edit bookmarks modal
        if (state.showEditBookmarkModal) {
            EditBookmarkModal(
                bookmark = state.editingBookmark,
                onDismiss = { onEvent(WebBrowserEvent.DismissBookmarkModal) },
                onSave = { title, url ->
                    onEvent(WebBrowserEvent.SaveBookmark(title, url))
                }
            )
        }
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
fun WebBrowserScreenPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        WebBrowserScreen(
            state = WebBrowserUiState(
                url = "https://www.youtube.com",
                isLoading = true,
                loadingProgress = 65,
                pageTitle = "VR Browser Example",
                canGoBack = true,
                canGoForward = false,
                isBookmarked = true,
                isCurvedGeometry = true,
                bookmarks = listOf(
                    BrowserBookmark(id = 1, title = "YouTube", url = "https://www.youtube.com"),
                    BrowserBookmark(id = 2, title = "Google", url = "https://www.google.com")
                )
            ),
            mediaCommands = MutableSharedFlow(),
            geckoSession = null,
            onEvent = {},
            updateWebViewState = { _, _, _, _, _, _, _, _ -> }
        )
    }
}
