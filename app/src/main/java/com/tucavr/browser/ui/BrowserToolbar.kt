package com.tucavr.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PanoramaHorizontal
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tucavr.R
import com.tucavr.browser.state.WebBrowserEvent
import com.tucavr.browser.state.WebBrowserUiState

@Composable
fun BrowserToolbar(
    state: WebBrowserUiState,
    onEvent: (WebBrowserEvent) -> Unit,
) {
    var urlTextFieldValue by remember(state.url) { mutableStateOf(state.url) }
    val focusManager = LocalFocusManager.current

    Surface(
        color = BrowserColors.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Back
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.GoBack) },
                    enabled = state.canGoBack,
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.webbrowser_btn_back),
                        tint = if (state.canGoBack) BrowserColors.text else BrowserColors.textSecondary,
                    )
                }

                // Forward
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.GoForward) },
                    enabled = state.canGoForward,
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = stringResource(R.string.webbrowser_btn_forward),
                        tint = if (state.canGoForward) BrowserColors.text else BrowserColors.textSecondary,
                    )
                }

                // Reload
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.Reload) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.webbrowser_btn_reload),
                        tint = BrowserColors.text,
                    )
                }

                // Home
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.GoHome) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = stringResource(R.string.webbrowser_btn_home),
                        tint = BrowserColors.text,
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // URL input field
                OutlinedTextField(
                    value = urlTextFieldValue,
                    onValueChange = { urlTextFieldValue = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.webbrowser_hint_url), color = BrowserColors.textSecondary) },
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            focusedTextColor = BrowserColors.text,
                            unfocusedTextColor = BrowserColors.text,
                            focusedBorderColor = BrowserColors.accent,
                            unfocusedBorderColor = BrowserColors.border,
                            focusedContainerColor = BrowserColors.surfaceAlt,
                            unfocusedContainerColor = BrowserColors.surfaceAlt,
                        ),
                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Go,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onGo = {
                                focusManager.clearFocus()
                                onEvent(WebBrowserEvent.OnUrlSubmitted(urlTextFieldValue))
                            },
                        ),
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Bookmark button
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.ToggleBookmark) },
                ) {
                    Icon(
                        imageVector = if (state.isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription =
                            if (state.isBookmarked) {
                                stringResource(R.string.webbrowser_btn_remove_bookmark)
                            } else {
                                stringResource(R.string.webbrowser_btn_add_bookmark)
                            },
                        tint = if (state.isBookmarked) BrowserColors.accent else BrowserColors.text,
                    )
                }

                // VR curved geometry button
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.ToggleGeometry) },
                ) {
                    Icon(
                        imageVector = Icons.Default.PanoramaHorizontal,
                        contentDescription = stringResource(R.string.webbrowser_btn_toggle_curved),
                        tint = if (state.isCurvedGeometry) BrowserColors.accent else BrowserColors.text,
                    )
                }

                // Close button
                IconButton(
                    onClick = { onEvent(WebBrowserEvent.CloseBrowser) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.webbrowser_btn_close),
                        tint = BrowserColors.text,
                    )
                }
            }

            if (state.isLoading) {
                LinearProgressIndicator(
                    progress = state.loadingProgress / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = BrowserColors.accent,
                    trackColor = BrowserColors.surfaceAlt,
                )
            }
        }
    }
}

@Preview(widthDp = 1280)
@Composable
fun BrowserToolbarPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        BrowserToolbar(
            state =
                WebBrowserUiState(
                    url = "https://www.youtube.com",
                    isLoading = true,
                    loadingProgress = 70,
                    canGoBack = true,
                    canGoForward = false,
                    isBookmarked = true,
                    isCurvedGeometry = true,
                ),
            onEvent = {},
        )
    }
}
