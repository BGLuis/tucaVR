package com.tucavr.browser.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tucavr.browser.data.BrowserBookmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private val faviconCache = ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun ShortcutsPanel(
    bookmarks: List<BrowserBookmark>,
    onBookmarkClick: (BrowserBookmark) -> Unit,
    onAddBookmarkClick: () -> Unit,
    onEditBookmarkClick: (BrowserBookmark) -> Unit,
    onDeleteBookmarkClick: (BrowserBookmark) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(24.dp)
    ) {
        Text(
            text = "Shortcuts & Bookmarks",
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 240.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(bookmarks, key = { it.id }) { bookmark ->
                BookmarkCard(
                    bookmark = bookmark,
                    onClick = { onBookmarkClick(bookmark) },
                    onEdit = { onEditBookmarkClick(bookmark) },
                    onDelete = { onDeleteBookmarkClick(bookmark) }
                )
            }

            item {
                AddBookmarkCard(onClick = onAddBookmarkClick)
            }
        }
    }
}

@Composable
fun BookmarkCard(
    bookmark: BrowserBookmark,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Site favicon with an asynchronous loading strategy
                FaviconImage(
                    bookmark = bookmark,
                    modifier = Modifier.size(36.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = bookmark.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                IconButton(onClick = onEdit, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit bookmark",
                        tint = Color.LightGray,
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete bookmark",
                        tint = Color(0xFFFF5252),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Text(
                text = bookmark.url,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun FaviconImage(
    bookmark: BrowserBookmark,
    modifier: Modifier = Modifier
) {
    val isPreview = LocalInspectionMode.current
    val host = remember(bookmark.url) { extractDomainHost(bookmark.url) }
    var bitmap by remember(bookmark.url, bookmark.faviconUrl) { mutableStateOf<ImageBitmap?>(faviconCache[host]) }

    LaunchedEffect(bookmark.url, bookmark.faviconUrl) {
        if (isPreview || bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            fetchFaviconBitmap(bookmark.faviconUrl, host)
        }
        if (loaded != null) {
            faviconCache[host] = loaded
            bitmap = loaded
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = bookmark.title,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(CircleShape)
        )
    } else {
        // Fall back to an avatar with the site's initial
        val initial = bookmark.title.trim().take(1).uppercase().ifEmpty { "W" }
        val avatarBgColor = getAvatarColor(bookmark.title, bookmark.url)

        Box(
            modifier = modifier
                .background(avatarBgColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initial,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
fun AddBookmarkCard(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF2A2A2A)
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color(0xFFFF6B00)
                )
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text(
                    text = "Add Shortcut",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun extractDomainHost(urlStr: String): String {
    return try {
        val uri = URI(urlStr)
        uri.host ?: urlStr
    } catch (e: Exception) {
        urlStr
    }
}

private fun fetchFaviconBitmap(explicitFaviconUrl: String?, host: String): ImageBitmap? {
    val urlsToTry = mutableListOf<String>()
    if (!explicitFaviconUrl.isNullOrBlank()) {
        urlsToTry.add(explicitFaviconUrl)
    }
    if (host.isNotBlank() && host != "about:blank") {
        urlsToTry.add("https://www.google.com/s2/favicons?domain=$host&sz=128")
    }

    for (urlStr in urlsToTry) {
        try {
            val conn = URL(urlStr).openConnection()
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.getInputStream().use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    return bmp.asImageBitmap()
                }
            }
        } catch (_: Throwable) {
            // Try the next URL
        }
    }
    return null
}

private fun getAvatarColor(title: String, url: String): Color {
    val lower = (title + url).lowercase()
    return when {
        lower.contains("youtube") -> Color(0xFFFF0000)
        lower.contains("google") -> Color(0xFF4285F4)
        lower.contains("twitch") -> Color(0xFF9146FF)
        lower.contains("netflix") -> Color(0xFFE50914)
        lower.contains("github") -> Color(0xFF24292E)
        lower.contains("reddit") -> Color(0xFFFF4500)
        else -> Color(0xFFFF6B00)
    }
}

@Preview(widthDp = 1280, heightDp = 720)
@Composable
fun ShortcutsPanelPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        ShortcutsPanel(
            bookmarks = listOf(
                BrowserBookmark(id = 1, title = "YouTube", url = "https://www.youtube.com"),
                BrowserBookmark(id = 2, title = "Google", url = "https://www.google.com"),
                BrowserBookmark(id = 3, title = "Twitch", url = "https://www.twitch.tv"),
                BrowserBookmark(id = 4, title = "Netflix", url = "https://www.netflix.com")
            ),
            onBookmarkClick = {},
            onAddBookmarkClick = {},
            onEditBookmarkClick = {},
            onDeleteBookmarkClick = {}
        )
    }
}
