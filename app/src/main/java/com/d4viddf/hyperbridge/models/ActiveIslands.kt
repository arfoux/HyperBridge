package com.d4viddf.hyperbridge.models

data class ActiveIsland(
    val id: Int,
    val type: NotificationType,
    val postTime: Long,
    val packageName: String,
    val groupKey: String?,
    // Content Diffing Fields
    val title: String,
    val text: String,
    val subText: String,
    // Used for Deduplication
    val lastContentHash: Int,
    val deleteIntent: android.app.PendingIntent? = null,
    // Cheap pre-translate signature (DELIVERY): title/text/RV-texts/image-actions/progress/actions.
    // Lets burst updates no-op in ms instead of starving behind full RV inflate.
    val fastHash: Int = 0,
    // Fingerprint RemoteViews (reflection 1-3ms, tanpa inflate): Grab update stage
    // via RV sementara extras statis — tanpa ini update RV-only selalu di-skip dedup
    // dan pill cuma berubah pas toggle layar (reprocess paksa).
    val rvHash: Int = 0
)