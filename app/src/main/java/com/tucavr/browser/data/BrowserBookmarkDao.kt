package com.tucavr.browser.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for CRUD and reordering operations on browser bookmarks.
 */
@Dao
interface BrowserBookmarkDao {

    @Query("SELECT * FROM browser_bookmarks ORDER BY display_order ASC, id ASC")
    fun getAllBookmarksFlow(): Flow<List<BrowserBookmark>>

    @Query("SELECT * FROM browser_bookmarks ORDER BY display_order ASC, id ASC")
    suspend fun getAllBookmarks(): List<BrowserBookmark>

    @Query("SELECT * FROM browser_bookmarks WHERE id = :id LIMIT 1")
    suspend fun getBookmarkById(id: Long): BrowserBookmark?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BrowserBookmark): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookmarks: List<BrowserBookmark>)

    @Update
    suspend fun updateBookmark(bookmark: BrowserBookmark)

    @Delete
    suspend fun deleteBookmark(bookmark: BrowserBookmark)

    @Query("DELETE FROM browser_bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE browser_bookmarks SET display_order = :order WHERE id = :id")
    suspend fun updateDisplayOrder(id: Long, order: Int)

    @Transaction
    suspend fun reorderBookmarks(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateDisplayOrder(id, index)
        }
    }
}
