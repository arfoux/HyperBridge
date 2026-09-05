package com.d4viddf.hyperbridge.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.d4viddf.hyperbridge.MainActivity
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.models.NotificationType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Helper untuk Test Pill semua kategori.
 * Semua notif test di-mark dengan extra "hyperbridge_test"=true agar log bisa bedain REAL vs TEST.
 * Data diambil dari yang sekarang (Shopee 723022255) untuk DELIVERY clone persis + logo.
 */
object TestNotificationHelper {

    const val EXTRA_TEST = "hyperbridge_test"
    const val EXTRA_TEST_TYPE = "hyperbridge_test_type"
    /** Marker for DELIVERY REAL-clone posts: must pass onNotificationPosted gates (never hyperbridge_test). */
    const val EXTRA_REAL_CLONE = "hyperbridge_real_clone"
    const val EXTRA_REAL_STAGE = "hyperbridge_real_stage"

    /** Same channel id as the real ShopeeFood live activity (DELIVERY_VERIFY pattern). */
    const val REAL_CHANNEL_ID = "SHOPEE_LIVE_ACTIVITY_ID"
    private const val REAL_BASE_ID = 91000

    private const val TEST_CHANNEL_ID = "hyperbridge_test_channel"
    private const val TEST_BASE_ID = 90000

    /** 4 observable ShopeeFood stages, text cloned 1:1 from real notifs (see DELIVERY_VERIFY pattern). */
    enum class DeliveryStage(val title: String, val text: String, val liveId: String) {
        DISIAPKAN(
            "Resto sedang menyiapkan pesananmu",
            "DKRIUK KS TUBUN \u2022 Estimasi tiba dalam 32 menit",
            "shopee_food_orders_disiapkan"
        ),
        MENUJU(
            "Driver sedang menuju Resto",
            "Driver sedang menuju ke Resto \u2022 Tiba pada 19:20",
            "shopee_food_orders_menuju"
        ),
        TIBA(
            "Pesananmu sudah tiba!",
            "Terima kasih sudah memesan \u2022 Selamat menikmati!",
            "shopee_food_orders_tiba"
        ),
        SELESAI(
            "Selamat menikmati!",
            "Jangan lupa beri nilai untuk pesananmu",
            "shopee_food_orders_selesai"
        );
    }

    private fun ensureTestChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(TEST_CHANNEL_ID, "HyperBridge Test", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Test pills for all categories"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(ch)
    }
    private fun ensureRealChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(REAL_CHANNEL_ID, "Shopee Live Activity (test clone)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "REAL-pipeline clone of ShopeeFood live activity for stage testing"
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * Post DELIVERY REAL clone via NotificationManager (bukan bypass HyperIsland).
     * Pola 1:1 notif ShopeeFood asli: NotificationCompat.Builder(context, "SHOPEE_LIVE_ACTIVITY_ID")
     * + extra "extra_live_activity_id"=shopee_food_orders_* + DecoratedCustomViewStyle + title/text per stage.
     * Masuk onNotificationPosted REAL (detect -> DeliveryTranslator -> mapping), tercatat sebagai REAL di log/history.
     *
     * JUJUR: banner/gambar promo ShopeeFood asli tidak bisa difabrikasi — bitmap itu milik app Shopee dan
     * hanya ada di RemoteViews notif real. Clone ini pakai fallback logo; yang diuji 1:1 adalah
     * channel, liveId, template style, judul/teks/stage/ETA, dan alur detect+translator+mapping.
     */
    fun postRealDeliveryClone(context: Context, stage: DeliveryStage) {
        ensureRealChannel(context)
        val id = REAL_BASE_ID + stage.ordinal
        val builder = NotificationCompat.Builder(context, REAL_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(stage.title)
            .setContentText(stage.text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(dummyPendingIntent(context, 9100 + stage.ordinal))
        // extra liveId WAJIB sebelum build agar terbaca detectNotificationType (isLiveActivity -> DELIVERY)
        builder.addExtras(Bundle().apply {
            putString("extra_live_activity_id", stage.liveId)
            putBoolean(EXTRA_REAL_CLONE, true)
            putString(EXTRA_REAL_STAGE, stage.name)
        })
        val notif = builder.build()
        // addExtras kadang tidak survive build di semua versi compat — tulis ulang langsung ke extras final
        notif.extras.putString("extra_live_activity_id", stage.liveId)
        notif.extras.putBoolean(EXTRA_REAL_CLONE, true)
        notif.extras.putString(EXTRA_REAL_STAGE, stage.name)
        context.getSystemService(NotificationManager::class.java).notify(id, notif)
        android.util.Log.w("HyperBridgeTest", "POSTED REAL-CLONE stage=${stage.name} id=$id live=${stage.liveId} ch=$REAL_CHANNEL_ID")
    }

    fun cancelRealClones(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        DeliveryStage.entries.forEach { nm.cancel(REAL_BASE_ID + it.ordinal) }
        android.util.Log.w("HyperBridgeTest", "CANCELED REAL-CLONES")
    }

    /**
     * Post TEST pill langsung via HyperIsland (bypass NotificationListenerService.shouldIgnore).
     * Jadi test notif tidak perlu lewat pkg allow-list, tetap muncul pill dan log TEST vs REAL jelas.
     */
    fun postTest(context: Context, type: NotificationType) {
        ensureTestChannel(context)
        val isDeliveryShopeeClone = type == NotificationType.DELIVERY
        val id = TEST_BASE_ID + type.ordinal

        // Direct pill via HyperIsland — bypass shouldIgnore (pkg hyperbridge)
        try {
            val title: String
            val text: String
            val channelIdForLog: String
            val templateForLog: String
            when (type) {
                NotificationType.STANDARD -> { title = "Test Standard — Order Update"; text = "Pesanan kamu sedang diproses di dapur • Estimasi 15 menit"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "Standard" }
                NotificationType.MESSAGE -> { title = "MOMOYO Ice Cream - Rembang"; text = "Driver: Pesanan dalam perjalanan ya kak!"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "MessagingStyle" }
                NotificationType.PROGRESS -> { title = "Test Progress — File Sync"; text = "Syncing 45%"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "Progress 45/100" }
                NotificationType.DOWNLOAD -> { title = "Test Download — HyperBridge"; text = "Downloading 62%"; channelIdForLog = "download_test_channel"; templateForLog = "Download 62/100" }
                NotificationType.MEDIA -> { title = "Test Media — Poweramp"; text = "MOMOYO - Rembang • Playing"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "MediaStyle" }
                NotificationType.NAVIGATION -> { title = "Jl. Pemuda No. 12"; text = "500 m • Belok kanan • 12:34"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "Navigation" }
                NotificationType.CALL -> { title = "Test Call — Driver"; text = "Incoming call from Driver ShopeeFood"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "CallStyle" }
                NotificationType.TIMER -> { title = "Test Timer — 5 min"; text = "Timer running • 05:00"; channelIdForLog = TEST_CHANNEL_ID; templateForLog = "Chronometer" }
                NotificationType.DELIVERY -> { title = "Driver sedang menuju Resto"; text = "Driver sedang menuju ke Resto - MOMOYO Ice Cream - Rembang"; channelIdForLog = "SHOPEE_LIVE_ACTIVITY_ID"; templateForLog = "DecoratedCustomViewStyle + liveId" }
            }

            // Build HyperIsland pill directly (pakai Shopee logo untuk DELIVERY clone persis)
            val hyperContext = context
            val picKey = "test_${type.name.lowercase()}_${System.currentTimeMillis() % 10000}"
            val builder = io.github.d4viddf.hyperisland_kit.HyperIslandNotification.Builder(hyperContext, "test_${type.name.lowercase()}", title)
            builder.setEnableFloat(true)
            builder.setShowNotification(true)
            builder.setIslandFirstFloat(true)
            // Icon: Shopee untuk DELIVERY, launcher untuk lain
            val iconBmp = try {
                if (isDeliveryShopeeClone) {
                    val shopeeCtx = context.createPackageContext("com.shopee.id", 0)
                    val d = shopeeCtx.packageManager.getApplicationIcon("com.shopee.id")
                    (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: createFallbackBitmap(context)
                } else {
                    val d = context.packageManager.getApplicationIcon(context.packageName)
                    (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: createFallbackBitmap(context)
                }
            } catch (_: Exception) { createFallbackBitmap(context) }
            val hyperPic = io.github.d4viddf.hyperisland_kit.HyperPicture(picKey, iconBmp)
            builder.addPicture(hyperPic)
            builder.addPicture(io.github.d4viddf.hyperisland_kit.HyperPicture("hidden_pixel", android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)))

            val themeColor = if (isDeliveryShopeeClone) "#EE4D2D" else "#007AFF"
            // Shade + Island
            builder.setBaseInfo(type = 1, title = title, content = text, pictureKey = picKey, actionKeys = emptyList())
            builder.setBigIslandInfo(
                left = io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft(
                    type = 1,
                    picInfo = io.github.d4viddf.hyperisland_kit.models.PicInfo(type = 1, pic = picKey),
                    textInfo = io.github.d4viddf.hyperisland_kit.models.TextInfo(title, text)
                )
            )
            builder.setSmallIsland(picKey)
            builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = 10)
            builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)

            if (type == NotificationType.PROGRESS || type == NotificationType.DOWNLOAD) {
                val prog = if (type == NotificationType.PROGRESS) 45 else 62
                builder.setProgressBar(progress = prog, color = themeColor, picForwardKey = picKey, picEndKey = "hidden_pixel")
            }

            val resBundle = builder.buildResourceBundle()
            val json = builder.buildJsonParam()

            // Post via HyperBridge channel with miui.focus.param — ini yang trigger pill di HyperOS
            val nm = NotificationManagerCompat.from(context)
            val notifBuilder = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .addExtras(resBundle)
            val notif = notifBuilder.build()
            notif.extras.putString("miui.focus.param", json)
            // Mark as TEST for logging
            notif.extras.putBoolean(EXTRA_TEST, true)
            notif.extras.putString(EXTRA_TEST_TYPE, type.name)

            nm.notify(id, notif)

            // Save to history as TEST
            saveTestToHistory(context, type, title, text, channelIdForLog, templateForLog)

            android.util.Log.w("HyperBridgeTest", "POSTED TEST PILL type=${type.name} id=$id title='$title' live=${if (isDeliveryShopeeClone) "shopee_food_orders_test_clone" else "-"} ch=$channelIdForLog tpl=$templateForLog")
        } catch (e: Exception) {
            android.util.Log.e("HyperBridgeTest", "postTest failed for $type", e)
            // Fallback: post via old NotificationManager path (akan di-ignore tapi tetap log)
            fallbackPostTest(context, type)
        }
    }

    private fun createFallbackBitmap(context: Context): android.graphics.Bitmap {
        return try {
            val d = context.packageManager.getApplicationIcon(context.packageName)
            (d as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }

    private fun fallbackPostTest(context: Context, type: NotificationType) {
        ensureTestChannel(context)
        val nm = NotificationManagerCompat.from(context)
        val id = TEST_BASE_ID + type.ordinal
        val builder = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Test ${type.name}")
            .setContentText("Fallback for ${type.name}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        val extras = Bundle().apply { putBoolean(EXTRA_TEST, true); putString(EXTRA_TEST_TYPE, type.name) }
        builder.addExtras(extras)
        val notif = builder.build()
        notif.extras.putBoolean(EXTRA_TEST, true)
        notif.extras.putString(EXTRA_TEST_TYPE, type.name)
        nm.notify(id, notif)
    }

    private fun saveTestToHistory(context: Context, type: NotificationType, title: String, text: String, channelId: String, template: String) {
        try {
            val db = com.d4viddf.hyperbridge.data.db.AppDatabase.getDatabase(context)
            val entry = com.d4viddf.hyperbridge.data.db.SavedNotification(
                packageName = context.packageName,
                title = title,
                text = text,
                bigText = null,
                subText = null,
                channelId = channelId,
                template = template,
                isTest = true,
                detectedType = type.name,
                postTime = System.currentTimeMillis(),
                extrasJson = "hyperbridge_test=true;type=${type.name}"
            )
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                db.savedNotificationDao().insert(entry)
                // prune keep 50
                val all = db.savedNotificationDao().getRecentSync()
                if (all.size > 50) {
                    val cutoff = all.last().postTime
                    db.savedNotificationDao().pruneBefore(cutoff)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("HyperBridgeTest", "saveTest failed", e)
        }
    }

    fun cancelTest(context: Context, type: NotificationType) {
        NotificationManagerCompat.from(context).cancel(TEST_BASE_ID + type.ordinal)
        android.util.Log.w("HyperBridgeTest", "CANCELED TEST type=${type.name}")
    }

    fun cancelAllTests(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        NotificationType.entries.forEach { nm.cancel(TEST_BASE_ID + it.ordinal) }
        android.util.Log.w("HyperBridgeTest", "CANCELED ALL TESTS")
    }

    private fun dummyPendingIntent(context: Context, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
