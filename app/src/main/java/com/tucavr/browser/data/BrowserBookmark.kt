package com.tucavr.browser.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a web browsing bookmark/shortcut in tucaVR.
 */
@Entity(tableName = "browser_bookmarks")
data class BrowserBookmark(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "favicon_url")
    val faviconUrl: String? = null,

    @ColumnInfo(name = "display_order")
    val displayOrder: Int = 0,

    @ColumnInfo(name = "is_preset")
    val isPreset: Boolean = false,

    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis()
)
