package com.d4viddf.hyperbridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_notifications")
data class SavedNotification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String,
    val text: String,
    val bigText: String?,
    val subText: String?,
    val channelId: String?,
    val template: String?,
    val isTest: Boolean,
    val detectedType: String,
    val postTime: Long,
    val extrasJson: String
)
