package com.d4viddf.hyperbridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedNotificationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: SavedNotification)

    @Query("SELECT * FROM saved_notifications ORDER BY postTime DESC LIMIT 50")
    fun getRecentFlow(): Flow<List<SavedNotification>>

    @Query("SELECT * FROM saved_notifications ORDER BY postTime DESC LIMIT 50")
    suspend fun getRecentSync(): List<SavedNotification>

    @Query("DELETE FROM saved_notifications WHERE postTime < :before")
    suspend fun pruneBefore(before: Long)

    @Query("DELETE FROM saved_notifications")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM saved_notifications")
    suspend fun count(): Int
}
