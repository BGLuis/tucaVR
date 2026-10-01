package com.tucavr.browser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tucavr.R
import com.tucavr.browser.data.BrowserBookmark
import com.tucavr.designsystem.VoidButton
import com.tucavr.designsystem.VoidButtonStyle
import com.tucavr.designsystem.VoidIconButton
import com.tucavr.designsystem.VoidText
import com.tucavr.designsystem.VoidTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Visual quick links and bookmarks panel (home screen) for the VR browser.
 *
 * Displays bookmark cards with asynchronous favicon loading and action buttons
 * for reordering, editing, and deleting entries.
 */
class ShortcutsPanelView(
    context: Context,
    private val onBookmarkClicked: (BrowserBookmark) -> Unit,
    private val onAddBookmarkClicked: () -> Unit,
    private val onEditBookmarkClicked: (BrowserBookmark) -> Unit,
    private val onDeleteBookmarkClicked: (BrowserBookmark) -> Unit,
    private val onReorderRequested: (fromIndex: Int, toIndex: Int) -> Unit
) : ScrollView(context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val faviconCache = ConcurrentHashMap<String, Bitmap>()

    private lateinit var shortcutsGrid: GridLayout

    private fun dpToPx(dp: Float): Int = VoidTheme.dpToPx(context, dp)

    init {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.parseColor("#121212"))
        isFillViewport = true

        buildUi()
    }

    private fun buildUi() {
        val innerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(20f), dpToPx(24f), dpToPx(24f))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Home / shortcuts header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16f))
        }

        val titleTextLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val mainTitle = VoidText.title(context, "Quick Links & Bookmarks", sizeSp = 24f)
        val subTitle = VoidText.mono(context, "Access your favorite streaming services or add new shortcuts in VR", sizeSp = 13f, secondary = true)

        titleTextLayout.addView(mainTitle)
        titleTextLayout.addView(subTitle)

        val addShortcutButton = VoidButton(context, VoidButtonStyle.PRIMARY, isCompact = true).apply {
            text = "+ New Bookmark"
            setOnClickListener { onAddBookmarkClicked() }
        }

        headerLayout.addView(titleTextLayout)
        headerLayout.addView(addShortcutButton)

        innerContainer.addView(headerLayout)

        // Card grid
        shortcutsGrid = GridLayout(context).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        innerContainer.addView(shortcutsGrid)
        addView(innerContainer)
    }

    fun updateBookmarks(bookmarks: List<BrowserBookmark>) {
        shortcutsGrid.removeAllViews()

        bookmarks.forEachIndexed { index, bookmark ->
            val cardView = buildShortcutCard(bookmark, index, bookmarks.size)
            shortcutsGrid.addView(cardView)
        }
    }

    private fun buildShortcutCard(bookmark: BrowserBookmark, index: Int, totalCount: Int): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(14f), dpToPx(14f), dpToPx(14f), dpToPx(14f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = VoidTheme.dp(context, 12f)
                setStroke(
                    VoidTheme.dpToPx(context, 1f),
                    if (bookmark.isPreset) VoidTheme.colorAccent else Color.parseColor("#333333")
                )
            }
            val params = GridLayout.LayoutParams().apply {
                width = dpToPx(276f)
                height = dpToPx(150f)
                setMargins(dpToPx(8f), dpToPx(8f), dpToPx(8f), dpToPx(8f))
            }
            layoutParams = params
            isClickable = true
            isFocusable = true

            setOnClickListener {
                onBookmarkClicked(bookmark)
            }
        }

        // Card header row (favicon, title, and badge)
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Site icon / favicon view
        val faviconContainer = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(28f), dpToPx(28f)).apply {
                marginEnd = dpToPx(10f)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        loadFaviconInto(bookmark, faviconContainer)

        headerRow.addView(faviconContainer)

        val titleView = VoidText.body(context, bookmark.title, sizeSp = 16f).apply {
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleView)

        if (bookmark.isPreset) {
            val presetBadge = TextView(context).apply {
                text = "DEFAULT"
                textSize = 10f
                setTextColor(Color.BLACK)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
                background = GradientDrawable().apply {
                    setColor(VoidTheme.colorAccent)
                    cornerRadius = VoidTheme.dp(context, 4f)
                }
            }
            headerRow.addView(presetBadge)
        }

        card.addView(headerRow)

        // URL display
        val urlView = VoidText.mono(context, bookmark.url, sizeSp = 12f, secondary = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dpToPx(4f), 0, 0)
        }
        card.addView(urlView)

        // Spacer to push the action buttons to the bottom of the card
        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        card.addView(spacer)

        // Card action bar (move left, move right, edit, delete)
        val actionsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Move left (left arrow using ic_browser_back)
        val moveLeftBtn = VoidIconButton(context, R.drawable.ic_browser_back, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            contentDescription = "Move left"
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(6f) }
            isEnabled = index > 0
            alpha = if (index > 0) 1.0f else 0.3f
            setOnClickListener {
                onReorderRequested(index, index - 1)
            }
        }

        // Move right (right arrow using ic_browser_forward)
        val moveRightBtn = VoidIconButton(context, R.drawable.ic_browser_forward, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            contentDescription = "Move right"
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(12f) }
            isEnabled = index < totalCount - 1
            alpha = if (index < totalCount - 1) 1.0f else 0.3f
            setOnClickListener {
                onReorderRequested(index, index + 1)
            }
        }

        val actionSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }

        // Edit (using ic_edit)
        val editBtn = VoidIconButton(context, R.drawable.ic_edit, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            contentDescription = "Edit bookmark"
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f)).apply { marginEnd = dpToPx(6f) }
            setOnClickListener {
                onEditBookmarkClicked(bookmark)
            }
        }

        // Delete (using ic_delete)
        val deleteBtn = VoidIconButton(context, R.drawable.ic_delete, VoidButtonStyle.SECONDARY, isCircular = false).apply {
            contentDescription = "Delete bookmark"
            layoutParams = LinearLayout.LayoutParams(dpToPx(32f), dpToPx(32f))
            setOnClickListener {
                onDeleteBookmarkClicked(bookmark)
            }
        }

        actionsRow.addView(moveLeftBtn)
        actionsRow.addView(moveRightBtn)
        actionsRow.addView(actionSpacer)
        actionsRow.addView(editBtn)
        actionsRow.addView(deleteBtn)

        card.addView(actionsRow)

        return card
    }

    private fun loadFaviconInto(bookmark: BrowserBookmark, imageView: ImageView) {
        val host = extractDomainHost(bookmark.url)
        val cached = faviconCache[host]
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        // Temporary initial fallback: bookmark icon or a badge with the domain initial
        imageView.setImageResource(R.drawable.ic_browser_bookmark)

        scope.launch(Dispatchers.IO) {
            val bitmap = fetchFaviconBitmap(bookmark.faviconUrl, host)
            if (bitmap != null) {
                faviconCache[host] = bitmap
                withContext(Dispatchers.Main) {
                    imageView.setImageBitmap(bitmap)
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

    private fun fetchFaviconBitmap(explicitFaviconUrl: String?, host: String): Bitmap? {
        val urlsToTry = mutableListOf<String>()
        if (!explicitFaviconUrl.isNullToBlank()) {
            urlsToTry.add(explicitFaviconUrl!!)
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
                    if (bmp != null) return bmp
                }
            } catch (_: Throwable) {
                // Try the next fallback URL
            }
        }
        return null
    }

    private fun String?.isNullToBlank(): Boolean = this == null || this.isBlank()
}
