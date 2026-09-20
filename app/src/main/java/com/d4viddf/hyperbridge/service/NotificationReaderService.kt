package com.d4viddf.hyperbridge.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.d4viddf.hyperbridge.MainActivity
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.db.AppDatabase
import com.d4viddf.hyperbridge.data.theme.RulesEngine
import com.d4viddf.hyperbridge.data.theme.ThemeRepository
import com.d4viddf.hyperbridge.data.widget.WidgetManager
import com.d4viddf.hyperbridge.models.ActiveIsland
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.models.IslandConfig
import com.d4viddf.hyperbridge.models.IslandLimitMode
import com.d4viddf.hyperbridge.models.NavContent
import com.d4viddf.hyperbridge.models.NotificationType
import com.d4viddf.hyperbridge.models.WidgetConfig
import com.d4viddf.hyperbridge.models.WidgetRenderMode
import com.d4viddf.hyperbridge.service.translators.CallTranslator
import com.d4viddf.hyperbridge.service.translators.LiveUpdateTranslator
import com.d4viddf.hyperbridge.service.translators.MediaTranslator
import com.d4viddf.hyperbridge.service.translators.MessageTranslator
import com.d4viddf.hyperbridge.service.translators.NavTranslator
import com.d4viddf.hyperbridge.service.translators.ProgressTranslator
import com.d4viddf.hyperbridge.service.translators.DeliveryTranslator
import com.d4viddf.hyperbridge.service.translators.DownloadTranslator
import com.d4viddf.hyperbridge.service.translators.StandardTranslator
import com.d4viddf.hyperbridge.service.translators.TimerTranslator
import com.d4viddf.hyperbridge.service.translators.WidgetTranslator
import com.d4viddf.hyperbridge.util.ShizukuManager
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class NotificationReaderService : NotificationListenerService() {

    companion object {
        const val ACTION_RELOAD_THEME = "com.d4viddf.hyperbridge.ACTION_RELOAD_THEME"
        const val ACTION_PERFORM_MIGRATION = "com.d4viddf.hyperbridge.ACTION_PERFORM_MIGRATION"
    }

    private val TAG = "HyperBridgeDebug"
    private val EXTRA_ORIGINAL_KEY = "hyper_original_key"
    private fun debugLogEnabled(): Boolean =
        if (::preferences.isInitialized) preferences.debugLoggingSync() else false

    // --- CHANNELS ---
    private val NOTIFICATION_CHANNEL_ID = "hyper_bridge_notification_channel"
    private val WIDGET_CHANNEL_ID = "hyper_bridge_widget_channel"
    private val LIVE_UPDATE_CHANNEL_ID = "hyper_bridge_live_update_channel"
    private val WATCH_RELAY_CHANNEL_ID = "hyper_bridge_watch_relay_channel"
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    // --- STATE & CONFIG ---
    private var allowedPackageSet: Set<String> = emptySet()
    private var currentMode = IslandLimitMode.MOST_RECENT
    private var appPriorityList = emptyList<String>()
    private var globalBlockedTerms: Set<String> = emptySet()
    
    private var isDndModeEnabled = false
    private var autoDetectDnd = false

    // --- CACHES ---
    private val recentlyRemovedKeys = ConcurrentHashMap<String, Long>()
    private val nativeIslands = ConcurrentHashMap.newKeySet<String>()
    private val activeIslands = ConcurrentHashMap<String, ActiveIsland>()
    private val activeTranslations = ConcurrentHashMap<String, Int>()
    private val reverseTranslations = ConcurrentHashMap<Int, String>()
    private val processingJobs = ConcurrentHashMap<String, Job>()
    // Rantai per-paket: notif satu paket diproses berurutan (bukan paralel).
    // Tanpa ini dua stage delivery (key A + key B) lolos dedup/collapse bersamaan
    // karena check-then-act tidak atomik -> double pill (bukti: logcat 07:24:19).
    private val processingChain = ConcurrentHashMap<String, Job>()
    // postTime ORIGINAAL (sbn.postTime) dari konten yang sedang tampil per tracked-key.
    // Dipakai gate freshness collapse agar stage lama tak menimpa stage baru.
    private val deliveryContentTime = ConcurrentHashMap<String, Long>()
    // Timer cek-tuntas berjangkar ETA: 1 job per pill delivery (custom path saja).
    private val deliveryEtaJobs = ConcurrentHashMap<String, Job>()
    private val timeoutJobs = ConcurrentHashMap<String, Job>()
    private val removalJobs = ConcurrentHashMap<String, Job>()
    // Pill yang di-dismiss (swipe user / TTL / ETA / tuntas): konten IDENTIK tidak
    // boleh muncul lagi bila notifnya lahir sebelum/saat dismiss (repost sync /
    // update Grab yang sama). Order BARU (postTime lebih baru, walau teksnya sama
    // persis) selalu lolos — live update tidak mati, reorder resto sama aman.
    private val dismissedContent = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val DISMISS_SUPPRESS_MAX_AGE_MS = 6 * 60 * 60_000L
    // Grace buat cancel+repost key-baru-konten-sama (Grab burst update<key> ganti
    // tiap post): 3 mnt setelah swipe, konten identik tetap gugur walau postTime baru.
    private val DISMISS_GRACE_MS = 3 * 60_000L
    private lateinit var permanentIslandManager: PermanentIslandManager
    private val intentionallyRemovedKeys = ConcurrentHashMap.newKeySet<String>()
    private val widgetUpdateDebouncer = ConcurrentHashMap<Int, Long>()
    private val dismissedWidgetIds = ConcurrentHashMap.newKeySet<Int>()
    private val activeWidgets = ConcurrentHashMap.newKeySet<Int>()
    private val appLabelCache = ConcurrentHashMap<String, String>()

    private val MAX_ISLANDS = 9
    private val WIDGET_ID_BASE = 9000
    // Negative so these ids can never hit the >= WIDGET_ID_BASE branch in onNotificationRemoved
    private val WATCH_RELAY_ID_BASE = -20000
    private var watchRelaySlot = 0
    private val STANDARD_ISLAND_TIMEOUT_MS = 60_000L

    private lateinit var preferences: AppPreferences

    // --- THEME ENGINE ---
    private lateinit var themeRepository: ThemeRepository
    private lateinit var rulesEngine: RulesEngine

    // Translators
    private lateinit var callTranslator: CallTranslator
    private lateinit var navTranslator: NavTranslator
    private lateinit var timerTranslator: TimerTranslator
    private lateinit var progressTranslator: ProgressTranslator
    private lateinit var downloadTranslator: DownloadTranslator
    private lateinit var standardTranslator: StandardTranslator
    private lateinit var messageTranslator: MessageTranslator
    private lateinit var mediaTranslator: MediaTranslator
    private lateinit var deliveryTranslator: DeliveryTranslator
    private lateinit var widgetTranslator: WidgetTranslator
    private lateinit var liveUpdateTranslator: LiveUpdateTranslator

    @Volatile
    private var isScreenOn = true

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_UNLOCKED) {
                WidgetManager.init(this@NotificationReaderService)
                syncNotifications(refresh = true)
            } else if (intent.action == Intent.ACTION_SCREEN_ON) {
                isScreenOn = true
                syncNotifications(refresh = true)
            } else if (intent.action == Intent.ACTION_SCREEN_OFF) {
                isScreenOn = false
            }
        }
    }

    private val islandClickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.d4viddf.hyperbridge.ISLAND_CLICKED") {
                val sbnKey = intent.getStringExtra("sbn_key")
                val bridgeId = intent.getIntExtra("bridge_id", -1)
                @Suppress("DEPRECATION")
                val originalIntent = intent.getParcelableExtra<PendingIntent>("original_intent")

                if (originalIntent != null) {
                    try {
                        originalIntent.send()
                    } catch (e: PendingIntent.CanceledException) {
                        Log.e("HyperBridge", "PendingIntent canceled", e)
                    }
                }

                if (sbnKey != null) {
                    cancelNotification(sbnKey)
                }

                if (bridgeId != -1) {
                    ShizukuManager.cancel(context, bridgeId)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onCreate() {
        super.onCreate()
        
        val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
        filter.addAction(Intent.ACTION_SCREEN_ON)
        filter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(systemReceiver, filter)
        
        val clickFilter = IntentFilter("com.d4viddf.hyperbridge.ISLAND_CLICKED")
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            islandClickReceiver,
            clickFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        
        preferences = AppPreferences(applicationContext)
        createChannels()

        // [INIT] Theme Engine
        themeRepository = ThemeRepository(this)
        rulesEngine = RulesEngine()

        // Pass ThemeRepository to Translators
        callTranslator = CallTranslator(this, themeRepository)
        navTranslator = NavTranslator(this, themeRepository)
        timerTranslator = TimerTranslator(this, themeRepository)
        progressTranslator = ProgressTranslator(this, themeRepository)
        downloadTranslator = DownloadTranslator(this, themeRepository)
        standardTranslator = StandardTranslator(this, themeRepository)
        messageTranslator = MessageTranslator(this, themeRepository)
        liveUpdateTranslator = LiveUpdateTranslator(this, themeRepository)

        mediaTranslator = MediaTranslator(this)
        deliveryTranslator = DeliveryTranslator(this, themeRepository)
        widgetTranslator = WidgetTranslator(this)

        val userManager = getSystemService(USER_SERVICE) as android.os.UserManager
        if (userManager.isUserUnlocked) {
            WidgetManager.init(this)
        }

        permanentIslandManager = PermanentIslandManager(this, serviceScope, preferences)

        serviceScope.launch { preferences.allowedPackagesFlow.collectLatest { allowedPackageSet = it } }
        serviceScope.launch { preferences.limitModeFlow.collectLatest { currentMode = it } }
        serviceScope.launch { preferences.appPriorityListFlow.collectLatest { appPriorityList = it } }
        serviceScope.launch { preferences.globalBlockedTermsFlow.collectLatest { globalBlockedTerms = it } }
        serviceScope.launch { preferences.isDndModeEnabledFlow.collectLatest { isDndModeEnabled = it } }
        serviceScope.launch { preferences.autoDetectDndFlow.collectLatest { autoDetectDnd = it } }

        // Listen for Theme Changes
        serviceScope.launch {
            preferences.activeThemeIdFlow.collectLatest { themeId ->
                if (debugLogEnabled()) Log.d(TAG, "Service detected theme change: $themeId")
                if (themeId != null) {
                    themeRepository.activateTheme(themeId)
                } else {
                    themeRepository.activateTheme("")
                }
            }
        }

        // --- WIDGET LISTENER ---
        serviceScope.launch {
            WidgetManager.widgetUpdates.collect { updatedId ->
                if (dismissedWidgetIds.contains(updatedId)) return@collect
                val savedIds = preferences.savedWidgetIdsFlow.first()
                if (savedIds.contains(updatedId)) {
                    val config = preferences.getWidgetConfigFlow(updatedId).first()
                    if (shouldProcessWidgetUpdate(updatedId, config)) {
                        launch(Dispatchers.Main) {
                            processSingleWidget(updatedId, config)
                        }
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_TEST_WIDGET") {
            val widgetId = intent.getIntExtra("WIDGET_ID", -1)
            if (widgetId != -1) {
                dismissedWidgetIds.remove(widgetId)
                serviceScope.launch(Dispatchers.Main) {
                    val config = preferences.getWidgetConfigFlow(widgetId).first()
                    processSingleWidget(widgetId, config)
                }
            }
        } else if (intent?.action == ACTION_RELOAD_THEME) {
            serviceScope.launch {
                val themeId = preferences.activeThemeIdFlow.first()
                if (themeId != null) {
                    if (debugLogEnabled()) Log.d(TAG, "Hot-reloading theme: $themeId")
                    themeRepository.activateTheme(themeId)
                }
            }
        } else if (intent?.action == ACTION_PERFORM_MIGRATION) {
            serviceScope.launch(Dispatchers.IO) {
                AppDatabase.performMigration(applicationContext) { progress ->
                    launch(Dispatchers.Main) {
                        showMigrationProgress(progress)
                    }
                }
            }
        }
        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showMigrationProgress(progress: Int) {
        val title = getString(R.string.migration_title)
        val message = if (progress >= 100) getString(R.string.migration_complete) else getString(R.string.migration_message)
        val bridgeId = "migration_update".hashCode()

        serviceScope.launch {
            val useNative = getEffectiveEngine(packageName)
            
            if (useNative) {
                val notificationBuilder = liveUpdateTranslator.translateToLiveUpdate(
                    sbn = null,
                    channelId = LIVE_UPDATE_CHANNEL_ID,
                    type = NotificationType.PROGRESS,
                    navRight = null,
                    config = null
                )
                notificationBuilder.setContentTitle(title)
                notificationBuilder.setContentText(message)
                notificationBuilder.setProgress(100, progress, progress < 0)
                notificationBuilder.setOngoing(progress in 0..99)
                notificationBuilder.setSmallIcon(R.drawable.ic_launcher_foreground)

                val notification = notificationBuilder.build()
                ShizukuManager.notify(this@NotificationReaderService, bridgeId, notification)
            } else {
                val builder = HyperIslandNotification.Builder(this@NotificationReaderService, "migration", title)
                builder.setProgressBar(progress, "#007AFF")
                builder.setChatInfo(title, message, "migration_icon", packageName)
                builder.setShowNotification(true)
                builder.setIslandFirstFloat(true)

                val data = HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())

                val notificationBuilder = NotificationCompat.Builder(this@NotificationReaderService, NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setOngoing(progress in 0..99)
                    .setProgress(100, progress, progress < 0)
                    .addExtras(data.resources)

                val notification = notificationBuilder.build()
                notification.extras.putString("miui.focus.param", data.jsonParam)

                ShizukuManager.notify(this@NotificationReaderService, bridgeId, notification)
            }

            if (progress >= 100) {
                delay(3000)
                NotificationManagerCompat.from(this@NotificationReaderService).cancel(bridgeId)
            }
        }
    }

    // =========================================================================
    //  EFFECTIVE BEHAVIOR RESOLUTION (Theme > App > Global)
    // =========================================================================

    private fun getEffectiveTypes(pkg: String): Set<String> {
        val themeOverride = themeRepository.activeTheme.value?.apps?.get(pkg)
        val rawTypes = if (themeOverride?.activeNotificationTypes != null) {
            themeOverride.activeNotificationTypes
        } else {
            val localPref = preferences.getAppConfigSync(pkg)
            localPref ?: preferences.getGlobalNotificationTypesSync()
        }

        // Fallback: if PROGRESS is enabled but DOWNLOAD is missing, implicitly enable DOWNLOAD
        return if (rawTypes.contains("PROGRESS") && !rawTypes.contains("DOWNLOAD")) {
            rawTypes + "DOWNLOAD"
        } else {
            rawTypes
        }
    }

    private fun getEffectiveEngine(pkg: String): Boolean {
        val activeTheme = themeRepository.activeTheme.value

        // 1. Theme App Override (Creator explicitly configured this app)
        val themeAppOverride = activeTheme?.apps?.get(pkg)?.useNativeLiveUpdates
        if (themeAppOverride != null) return themeAppOverride

        // 2. User App Override (User explicitly configured this app via Home Screen)
        val userAppOverride = preferences.getAppEnginePreferenceSync(pkg)
        if (userAppOverride != null) return userAppOverride

        // 3. Theme Global Override (Creator explicitly forced an engine for the whole theme)
        val themeGlobalOverride = activeTheme?.global?.useNativeLiveUpdates
        if (themeGlobalOverride != null) return themeGlobalOverride

        // 4. User Global Fallback (The main Engine Setting on the Home Screen!)
        return preferences.useNativeLiveUpdatesSync()
    }

    private fun getEffectiveNav(pkg: String): Pair<NavContent, NavContent> {
        return preferences.getEffectiveNavLayoutSync(pkg)
    }

    // =========================================================================
    //  NOTIFICATION REMOVAL LOGIC
    // =========================================================================

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn?.let {
            if (nativeIslands.remove(it.key)) {
                updatePermanentIsland()
            }

            val isOurApp = it.packageName == packageName
            val notifId = it.id
            val notifKey = it.key

            if (intentionallyRemovedKeys.remove(notifKey)) {
                return
            }

            recentlyRemovedKeys[notifKey] = System.currentTimeMillis()

            processingJobs[notifKey]?.cancel()
            processingJobs.remove(notifKey)

            timeoutJobs[notifKey]?.cancel()
            timeoutJobs.remove(notifKey)

            if (isOurApp) {
                // Only process user-initiated dismissals for our notifications. 
                // Ignore programmatic cancels (e.g., during updates or Shizuku workarounds).
                if (reason != REASON_CANCEL && reason != REASON_CANCEL_ALL) {
                    return
                }

                if (notifId >= WIDGET_ID_BASE) {
                    val widgetId = notifId - WIDGET_ID_BASE
                    dismissedWidgetIds.add(widgetId)
                    activeWidgets.remove(widgetId)
                    updatePermanentIsland()
                    return
                }

                var originalKey = reverseTranslations[notifId]
                if (originalKey == null) {
                    originalKey = it.notification.extras.getString(EXTRA_ORIGINAL_KEY)
                }

                if (originalKey != null) {
                    if (debugLogEnabled()) Log.d(TAG, "Our notification $notifId removed. Cleaning up cache for $originalKey")
                    // [FIX] We no longer kill the source notification when our Island is dismissed or timed out
                    try {
                        activeIslands[originalKey]?.deleteIntent?.send()
                    } catch (e: Exception) {
                        if (debugLogEnabled()) Log.e(TAG, "Error sending delete intent for original notification", e)
                    }
                    // Swipe user = jangan tampilkan konten identik 30 mnt (ori masih
                    // hidup -> sync/update Grab bakal me-repost; tanpa ini swipe sia-sia).
                    activeIslands[originalKey]?.let { noteDismissed(it.packageName, it.lastContentHash) }
                    cleanupCache(originalKey)
                }
                return
            }

            if (activeTranslations.containsKey(notifKey)) {
                val hyperId = activeTranslations[notifKey] ?: return

                val job = serviceScope.launch(Dispatchers.IO) {
                    val appConfig = preferences.getAppIslandConfigSync(sbn.packageName)
                    val globalConfig = preferences.getGlobalConfigSync()
                    val finalConfig = appConfig.mergeWith(globalConfig)

                    val islandType = activeIslands[notifKey]?.type
                    val forceDismiss = islandType == NotificationType.CALL || 
                                        islandType == NotificationType.MEDIA || 
                                        islandType == NotificationType.NAVIGATION ||
                                        islandType == NotificationType.DELIVERY

                    if (finalConfig.dismissWithOriginal == true || forceDismiss) {
                        // Debounce updates if the app canceled it programmatically
                        if (reason == REASON_APP_CANCEL) {
                            kotlinx.coroutines.delay(300)
                        }
                        try {
                            NotificationManagerCompat.from(this@NotificationReaderService).cancel(hyperId)
                        } catch (_: Exception) {}
                        activeIslands[notifKey]?.let { noteDismissed(it.packageName, it.lastContentHash) }
                        cleanupCache(notifKey)
                    }
                    removalJobs.remove(notifKey)
                }
                removalJobs[notifKey] = job
            }
        }
    }

    private fun cancelSourceNotification(targetKey: String) {
        try {
            val currentNotifications = try {
                activeNotifications
            } catch (_: Exception) {
                cancelNotification(targetKey)
                return
            }

            val targetSbn = currentNotifications.find { it.key == targetKey }
            cancelNotification(targetKey)

            if (targetSbn != null) {
                val groupKey = targetSbn.groupKey
                val pkg = targetSbn.packageName
                if (groupKey == null) return

                val remainingGroupMembers = currentNotifications.filter {
                    it.packageName == pkg &&
                            it.groupKey == groupKey &&
                            it.key != targetKey
                }

                if (remainingGroupMembers.size == 1) {
                    val survivor = remainingGroupMembers[0]
                    val isSummary = (survivor.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
                    if (isSummary) {
                        cancelNotification(survivor.key)
                    }
                }
            }
        } catch (e: Exception) {
            if (debugLogEnabled()) Log.e(TAG, "Error during smart dismissal", e)
        }
    }

    private fun cleanupCache(originalKey: String) {
        val hyperId = activeTranslations[originalKey]
        activeIslands.remove(originalKey)
        activeTranslations.remove(originalKey)
        deliveryContentTime.remove(originalKey)
        deliveryEtaJobs[originalKey]?.cancel()
        deliveryEtaJobs.remove(originalKey)
        timeoutJobs[originalKey]?.cancel()
        timeoutJobs.remove(originalKey)

        if (hyperId != null) {
            reverseTranslations.remove(hyperId)
        }
        updatePermanentIsland()
    }

    /** Bunuh semua pill DELIVERY milik satu paket (order tuntas / ganti key). */
    private fun dismissDeliveryPills(pkg: String) {
        val stale = activeIslands.entries.filter {
            it.value.type == NotificationType.DELIVERY && it.value.packageName == pkg
        }
        for ((staleKey, island) in stale) {
            try {
                NotificationManagerCompat.from(this).cancel(island.id)
            } catch (_: Exception) {}
            noteDismissed(island.packageName, island.lastContentHash)
            cleanupCache(staleKey)
        }
        if (stale.isNotEmpty() && debugLogEnabled()) Log.w(TAG, "DELIVERY-DISMISS ${stale.size} pill(s) pkg=$pkg")
    }

    /** Catat konten yang di-dismiss agar repost identik yang lebih tua gugur. */
    private fun noteDismissed(pkg: String, contentHash: Int) {
        val now = System.currentTimeMillis()
        dismissedContent.entries.removeIf { now - it.value.second > DISMISS_SUPPRESS_MAX_AGE_MS }
        dismissedContent[pkg] = contentHash to now
    }

    /** True bila konten ini adalah repost dari yang baru di-dismiss — jangan post ulang. */
    private fun isDismissSuppressed(pkg: String, contentHash: Int, postTime: Long): Boolean {
        val (hash, time) = dismissedContent[pkg] ?: return false
        val now = System.currentTimeMillis()
        if (now - time > DISMISS_SUPPRESS_MAX_AGE_MS) {
            dismissedContent.remove(pkg)
            return false
        }
        if (hash != contentHash) return false
        // Notif lama (reprocess sync) selalu gugur; notif baru gugur hanya dalam grace.
        return postTime <= time + 60_000L || now - time < DISMISS_GRACE_MS
    }

    // Pengecekan tuntas berjangkar ETA order itu sendiri (bukan angka tetap):
    // sekali saat ETA tiba, lalu per 10 menit (maks ~6 jam), lalu berhenti diam-diam.
    // Murah: 1 timer per order + 1 baca daftar notif per cek (tanpa inflate/polling app).
    // Kriteria tutup: 1) teks tuntas = bukti keras; 2) sunyi melewati ETA+grace 30 mnt
    // (order aktif pasti update — tiap update me-refresh deliveryContentTime).
    // Original yang hilang = bukan urusan cek ini (bisa removeOriginal by-design);
    // itu ranahnya onNotificationRemoved.
    private fun scheduleDeliveryEtaCheck(trackedKey: String, etaMin: Int) {
        deliveryEtaJobs[trackedKey]?.cancel()
        deliveryEtaJobs[trackedKey] = serviceScope.launch {
            try {
                delay(etaMin.coerceIn(1, 240) * 60_000L)
                repeat(36) {
                    val island = activeIslands[trackedKey]
                    if (island == null || island.type != NotificationType.DELIVERY) {
                        deliveryEtaJobs.remove(trackedKey)
                        return@launch
                    }
                    if (checkDeliveryFinishedSnapshot(trackedKey, island.packageName, etaMin)) {
                        deliveryEtaJobs.remove(trackedKey)
                        return@launch
                    }
                    delay(10 * 60_000L)
                }
                deliveryEtaJobs.remove(trackedKey)
            } catch (_: Exception) {
                deliveryEtaJobs.remove(trackedKey)
            }
        }
    }

    /** True bila pill ditutup (teks tuntas / sunyi). False = cek lagi 10 mnt. */
    private suspend fun checkDeliveryFinishedSnapshot(trackedKey: String, pkg: String, etaMin: Int): Boolean {
        val snapshot = try { activeNotifications?.toList() } catch (_: Exception) { null }
        val tracked = snapshot?.firstOrNull { it.key == trackedKey } ?: return false
        val ex = tracked.notification.extras
        val corpus = listOf(
            ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            ex.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        ).joinToString(" ")
        if (com.d4viddf.hyperbridge.util.RemoteViewsExtractor.isDeliveryFinished(corpus)) {
            if (debugLogEnabled()) Log.w(TAG, "DELIVERY-ETA-DONE dismiss key=$trackedKey pkg=$pkg")
            dismissDeliveryPills(pkg)
            return true
        }
        val lastUpdate = deliveryContentTime[trackedKey] ?: 0L
        if (lastUpdate > 0 && System.currentTimeMillis() - lastUpdate > (etaMin + 30) * 60_000L) {
            if (debugLogEnabled()) Log.w(TAG, "DELIVERY-ETA-SILENCE dismiss key=$trackedKey pkg=$pkg etaMin=$etaMin")
            dismissDeliveryPills(pkg)
            return true
        }
        return false
    }

    /** Korpus teks satu notif (title/text/big/sub/info) untuk cek marker tuntas. */
    private fun sbnCorpus(s: StatusBarNotification): String {
        val e = s.notification.extras
        return listOf(
            e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            e.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
        ).joinToString(" ")
    }

    /** "N menit" -> N. Selain itu (jam "20:25", kosong) -> null. */
    private fun etaMinutesOrNull(etaText: String?): Int? {
        val t = etaText?.trim().orEmpty()
        if (!t.endsWith("menit", ignoreCase = true)) return null
        return Regex("\\d+").find(t)?.value?.toIntOrNull()?.takeIf { it in 1..240 }
    }

    private fun handlePostNotificationSideEffects(originalKey: String, bridgeId: Int, config: IslandConfig, type: NotificationType, isLiveUpdate: Boolean, sbn: StatusBarNotification? = null, title: String = "", text: String = "") {
        // 1. Remove original if enabled (EXCEPT for Media)
        if (config.removeOriginalNotification == true && type != NotificationType.MEDIA && type != NotificationType.CALL) {
            // Companion apps (e.g. Mi Fitness) relay notifications to watches by listening like we do;
            // cancelling the original kills that relay, so post a silent short-lived copy they can forward.
            if (sbn != null && !isLiveUpdate && (type == NotificationType.MESSAGE || type == NotificationType.STANDARD)) {
                postWatchRelayNotification(sbn, title, text)
            }
            intentionallyRemovedKeys.add(originalKey)
            cancelNotification(originalKey)
        }

        // 2. Schedule timeout ONLY for Live Update notifications
        if (isLiveUpdate) {
            val timeoutSeconds = config.timeout ?: 0
            timeoutJobs[originalKey]?.cancel()
            if (timeoutSeconds > 0) {
                timeoutJobs[originalKey] = serviceScope.launch {
                    delay((timeoutSeconds * 1000L).milliseconds)
                    if (debugLogEnabled()) Log.d(TAG, "Timeout reached for $originalKey, removing translated notification $bridgeId")
                    NotificationManagerCompat.from(this@NotificationReaderService).cancel(bridgeId)
                    activeIslands[originalKey]?.let { noteDismissed(it.packageName, it.lastContentHash) }
                    cleanupCache(originalKey)
                    timeoutJobs.remove(originalKey)
                }
            }
        } else if (type == NotificationType.MESSAGE || type == NotificationType.STANDARD) {
            // HyperOS island-swipe only hides the island; the focus notification stays posted and no
            // removal callback fires, so an untimed island blocks the permanent island forever.
            timeoutJobs[originalKey]?.cancel()
            timeoutJobs[originalKey] = serviceScope.launch {
                delay(STANDARD_ISLAND_TIMEOUT_MS)
                if (debugLogEnabled()) Log.d(TAG, "Island TTL reached for $originalKey, removing translated notification $bridgeId")
                NotificationManagerCompat.from(this@NotificationReaderService).cancel(bridgeId)
                activeIslands[originalKey]?.let { noteDismissed(it.packageName, it.lastContentHash) }
                cleanupCache(originalKey)
                timeoutJobs.remove(originalKey)
            }
        }
    }

    private fun postWatchRelayNotification(sbn: StatusBarNotification, title: String, text: String) {
        try {
            val appLabel = getCachedAppLabel(sbn.packageName)
            val relayId = WATCH_RELAY_ID_BASE - (watchRelaySlot++ and 0x0F)
            val notification = NotificationCompat.Builder(this, WATCH_RELAY_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(if (title.isNotBlank()) "$appLabel · $title" else appLabel)
                .setContentText(text)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000L)
                .build()
            NotificationManagerCompat.from(this).notify(relayId, notification)
        } catch (e: Exception) {
            if (debugLogEnabled()) Log.e(TAG, "Error posting watch relay notification", e)
        }
    }

    private fun logStateChange(isLandscape: Boolean) {
        val orientation = if (isLandscape) "Landscape" else "Portrait"
        val isIslandExhibited = activeIslands.isNotEmpty() || activeWidgets.isNotEmpty() || nativeIslands.isNotEmpty() || permanentIslandManager.isIslandActive()
        val islandState = if (isIslandExhibited) "Showing Island" else "No Island"
        if (debugLogEnabled()) Log.d(TAG, "State: $orientation | $islandState")
    }

    private fun updatePermanentIsland() {
        // Tethering pill (android HOTSPOT) harus block permanent, Shopee voucher STANDARD bukan pill jangan block
        val pillCount = activeIslands.values.count { it.type != NotificationType.STANDARD && it.type != NotificationType.MESSAGE } + activeWidgets.size
        permanentIslandManager.onActiveNotificationsChanged(pillCount, nativeIslands.isNotEmpty())
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        logStateChange(isLandscape)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        permanentIslandManager.onOrientationChanged()
        logStateChange(newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
    }

    // =========================================================================
    //  STANDARD NOTIFICATION LOGIC
    // =========================================================================

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            if (it.packageName != packageName) {
                val extras = it.notification.extras
                var isNative = false
                if (extras != null) {
                    if (extras.containsKey("miui.focus.param") || extras.containsKey("miui.system.focus.param")) {
                        isNative = true
                    }
                    val template = extras.getString(Notification.EXTRA_TEMPLATE)
                    if (template == "androidx.media.app.NotificationCompat\$MediaStyle" ||
                        template == "android.app.Notification\$MediaStyle") {
                        isNative = true
                    }
                }
                // Universal pill HyperOS: semua ONGOING + tidak clearable (tethering HOTSPOT focusType=PARAMS, charging misound) dianggap pill
                // Tanpa hardcode package — pill = isOngoing && !isClearable (tethering ONGOING|CAN_COLORIZE, charging ONGOING|NO_CLEAR)
                // Shopee voucher AUTO_CANCEL clearable true jadi bukan pill, Shopee delivery ONGOING+AUTO_CANCEL clearable true juga bukan hasNative (tapi hitung via pillCount activeIslands)
                if (!isNative && it.isOngoing && !it.isClearable) {
                    isNative = true
                }
                if (isNative) {
                    if (nativeIslands.add(it.key)) updatePermanentIsland()
                } else {
                    if (nativeIslands.remove(it.key)) updatePermanentIsland()
                }
            }

            // REAL-clone test posts (own pkg + hyperbridge_real_clone): must enter the REAL pipeline
            // (detect -> translator -> mapping) so stage/ETA/layout are genuinely exercised.
            val isRealClone = it.packageName == packageName &&
                it.notification.extras.getBoolean(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_CLONE, false)
            if (shouldIgnore(it.packageName) && !isRealClone) {
                if (debugLogEnabled()) Log.w(TAG, "IGNORE pkg=${it.packageName}")
                return
            }
            // Shopee LIVE_ACTIVITY eligible bypass isAppAllowed jika user pernah aktifin shopee (atau auto-allow)
            val isShopeeLive = it.packageName == "com.shopee.id" && it.notification.extras.containsKey("extra_live_activity_id")
            if (!isAppAllowed(it.packageName) && !isRealClone) {
                if (isShopeeLive) {
                    if (debugLogEnabled()) Log.w(TAG, "BYPASS isAppAllowed for Shopee LIVE_ACTIVITY ${it.key} -> auto-allow")
                    serviceScope.launch { preferences.toggleApp(it.packageName, true) }
                } else {
                    if (debugLogEnabled()) Log.w(TAG, "BLOCKED isAppAllowed pkg=${it.packageName} allowed=$allowedPackageSet")
                    return
                }
            } else if (isRealClone && debugLogEnabled()) {
                Log.w(TAG, "BYPASS gates for REAL-CLONE ${it.key} stage=${it.notification.extras.getString(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_STAGE)}")
            }

            processingJobs[it.key]?.cancel()
            val prev = processingChain[it.packageName]
            val job = serviceScope.launch {
                // Tunggu giliran paket ini (serial, bukan paralel) agar dedup/collapse
                // melihat state terbaru. Batal prev = lanjut langsung; batal diri = stop.
                if (prev != null) {
                    try {
                        prev.join()
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                    }
                }
                val isJunk = isJunkNotification(it)
                // Shopee LIVE eligible jangan dianggap junk (voucher SUMMARY sudah di-filter di isJunk tapi live tetap eligible)
                // REAL-clone juga jangan dianggap junk: marker + liveId + title/text selalu non-empty.
                if (isJunk && !isShopeeLive && !isRealClone) {
                    if (debugLogEnabled()) Log.w(TAG, "JUNK skip ${it.key} pkg=${it.packageName}")
                    return@launch
                }
                if (isJunk && (isShopeeLive || isRealClone)) if (debugLogEnabled()) Log.w(TAG, "BYPASS junk for ${if (isRealClone) "REAL-CLONE" else "Shopee LIVE"} ${it.key}")
                processStandardNotification(it)
            }
            processingJobs[it.key] = job
            processingChain[it.packageName] = job
            job.invokeOnCompletion {
                processingJobs.remove(sbn.key)
                processingChain.remove(sbn.packageName, job)
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun processStandardNotification(rawSbn: StatusBarNotification) {
        val manager = getSystemService(NotificationManager::class.java)
        val isSystemDndActive = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val dndActive = isDndModeEnabled || (autoDetectDnd && isSystemDndActive)

        if (dndActive) {
            if (debugLogEnabled()) Log.d(TAG, "DND active. Skipping notification ${rawSbn.packageName}")
            return
        }

        val sbn = ensureValidSbn(rawSbn)

        try {
            val extras = sbn.notification.extras

            // [LOGIC] 1. Resolve Info 100% extras — tanpa baca contentView/RemoteViews.
            var effectiveTitle = resolveTitle(sbn)
            var effectiveText = resolveText(sbn.notification.extras)
            // Fallback tambahan: bigText/subText/infoText
            if (effectiveText.isEmpty()) {
                val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
                val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
                val info = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()
                effectiveText = big?.takeIf { it.isNotEmpty() } ?: sub?.takeIf { it.isNotEmpty() } ?: info ?: ""
            }
            // Grab live-activity RV-only: extras null semua -> judul generik (BUKAN label
            // app "Grab"); isi stage/ETA datang dari korpus RV via translator/async.
            if (effectiveTitle.isEmpty() && isGrabPipeline(sbn) &&
                extras.getBoolean("android.contains.customView", false)
            ) {
                effectiveTitle = getString(R.string.type_delivery)
            }

            // [LOGIC] 2. State Preservation
            val key = sbn.key
            val previous = activeIslands[key]

            if (effectiveTitle.isEmpty()) {
                if (previous != null && previous.title.isNotEmpty() && previous.title != sbn.packageName) {
                    effectiveTitle = previous.title
                } else {
                    val label = getCachedAppLabel(sbn.packageName)
                    effectiveTitle = if (label.isNotEmpty()) label else extras.getString(Notification.EXTRA_TEMPLATE)?.substringAfterLast('.') ?: sbn.packageName
                }
            }

            // [LOGIC] 3. Hard Stop — tapi jangan block Shopee LIVE_ACTIVITY eligible (punya liveId)
            // dan GrabFood live-activity (teks hanya di RV, extras null — dibaca async).
            val hasProgress = hasProgressNotification(sbn, effectiveTitle, effectiveText)
            val isShopeeLiveEligible = sbn.packageName == "com.shopee.id" && extras.containsKey("extra_live_activity_id")
            val isGrabLiveEligible = isGrabPipeline(sbn) &&
                extras.getBoolean("android.contains.customView", false) &&
                (sbn.notification.channelId?.contains("live_activity", ignoreCase = true) == true)
            if (effectiveTitle.isEmpty() && !hasProgress && !isShopeeLiveEligible && !isGrabLiveEligible) {
                if (debugLogEnabled()) Log.w(TAG, "HARD-STOP empty title for ${sbn.packageName} (not live)")
                return
            }

            val appBlockedTerms = preferences.getAppBlockedTermsSync(sbn.packageName)
            if (appBlockedTerms.isNotEmpty()) {
                val content = "$effectiveTitle $effectiveText"
                if (appBlockedTerms.any { term -> content.contains(term, ignoreCase = true) }) {
                    // Eligible Shopee delivery tetap lolos dari blockedTerms generic (voucher/promo sudah di-filter di detect)
                    if (!(isShopeeLiveEligible && appBlockedTerms.any { it.equals("shopee", true) })) {
                        if (debugLogEnabled()) Log.w(TAG, "BLOCKED by appBlockedTerms for ${sbn.packageName}: $content")
                        return
                    }
                }
            }

            // [LOGIC] 4. Theme & Rules Interception
            val activeTheme = themeRepository.activeTheme.value
            val ruleMatch = rulesEngine.match(sbn, effectiveTitle, effectiveText, activeTheme)

            val type = if (ruleMatch?.targetLayout != null) {
                try { NotificationType.valueOf(ruleMatch.targetLayout) }
                catch (_: Exception) { detectNotificationType(sbn) }
            } else {
                detectNotificationType(sbn)
            }
            // FAST-PATH dedup (DELIVERY custom-engine): 100% extras, tanpa sentuh contentView.
            // Burst update Shopee no-op dalam ms; yang berubah stage/teks/actions tetap full translate.
            // Native live-update path murah (tanpa inflate) jadi tidak perlu fast-path.
            var deliveryFastHash = 0
            if (type == NotificationType.DELIVERY && !getEffectiveEngine(sbn.packageName)) {
                deliveryFastHash = try {
                    var h = effectiveTitle.hashCode() * 31 + effectiveText.hashCode()
                    h = h * 31 + (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.hashCode() ?: 0)
                    h = h * 31 + (extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.hashCode() ?: 0)
                    h = h * 31 + extras.getInt(Notification.EXTRA_PROGRESS, 0) + extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                    h = h * 31 + (sbn.notification.actions?.joinToString { it.title?.toString() ?: "" }?.hashCode() ?: 0)
                    h
                } catch (_: Exception) { 0 }
                if (deliveryFastHash != 0 && previous != null && previous.type == NotificationType.DELIVERY &&
                    previous.fastHash == deliveryFastHash &&
                    rvFingerprint(sbn) == previous.rvHash
                ) return
            }
            // --- TEST vs REAL logging + simpan notif (untuk permanen/order) ---
            val isTestNotif = extras.getBoolean("hyperbridge_test", false)
            val isRealClonePost = extras.getBoolean(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_CLONE, false)
            if (isTestNotif) {
                Log.w("HyperBridgeTest", "TEST pkg=${sbn.packageName} type=$type title='$effectiveTitle' text='$effectiveText' ch=${sbn.notification.channelId} tpl=${extras.getString(Notification.EXTRA_TEMPLATE)} live=${extras.getString("extra_live_activity_id")} customView=${extras.getBoolean("android.contains.customView")}")
            } else {
                if (debugLogEnabled()) Log.w("HyperBridgeDebug", "REAL pkg=${sbn.packageName} ch=${sbn.notification.channelId} type=$type title='$effectiveTitle' text='$effectiveText' tpl=${extras.getString(Notification.EXTRA_TEMPLATE)} live=${extras.getString("extra_live_activity_id")} customView=${extras.getBoolean("android.contains.customView")}")
            }
            // Simpan ke history (50 terakhir, bedain isTest) — hormati toggle nonaktifin rekam REAL
            val shouldSave = isTestNotif || preferences.saveRealNotificationsSync()
            if (shouldSave) {
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val db = com.d4viddf.hyperbridge.data.db.AppDatabase.getDatabase(this@NotificationReaderService)
                        val entry = com.d4viddf.hyperbridge.data.db.SavedNotification(
                            packageName = sbn.packageName,
                            title = effectiveTitle,
                            text = effectiveText,
                            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                            channelId = sbn.notification.channelId,
                            template = extras.getString(Notification.EXTRA_TEMPLATE),
                            isTest = isTestNotif,
                            detectedType = type.name,
                            postTime = System.currentTimeMillis(),
                            extrasJson = "test=$isTestNotif;live=${extras.getString("extra_live_activity_id")};customView=${extras.getBoolean("android.contains.customView")};progress=${extras.getInt(Notification.EXTRA_PROGRESS, 0)}/${extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)};big=${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}"
                        )
                        db.savedNotificationDao().insert(entry)
                        db.savedNotificationDao().pruneKeepLatest()
                    } catch (e: Exception) { if (debugLogEnabled()) Log.e("HyperBridgeDebug", "save history failed", e) }
                }
            } else {
                if (debugLogEnabled()) Log.w("HyperBridgeDebug", "SKIP save REAL (toggle off) pkg=${sbn.packageName} type=$type")
            }

            // --- DELIVERY FINISHED (sebelum gate tipe) ---
            // Order tuntas harus membunuh pill walau notif tuntasnya bertipe lain
            // (rating/promo yang tipenya dimatikan user) atau DELIVERY-nya dimatikan.
            val deliveryCorpus = listOf(
                effectiveTitle, effectiveText,
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
                extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty()
            ).joinToString(" ")
            if (type == NotificationType.DELIVERY &&
                com.d4viddf.hyperbridge.util.RemoteViewsExtractor.isDeliveryFinished(deliveryCorpus)
            ) {
                dismissDeliveryPills(sbn.packageName)
                // Update same-key yang berubah jadi SELESAI tapi pill-nya belum ke-track
                // (bridgeId deterministik dari key): pastikan ikut dicancel.
                try {
                    NotificationManagerCompat.from(this@NotificationReaderService).cancel(sbn.key.hashCode())
                } catch (_: Exception) {}
                cleanupCache(key)
                if (debugLogEnabled()) Log.w(TAG, "DELIVERY-FINISHED dismiss pkg=${sbn.packageName} key=$key title='$effectiveTitle'")
                return
            }
            // SELESAI bertipe lain + pill delivery aktif: bunuh pill, notif lanjut normal.
            if (type != NotificationType.DELIVERY &&
                com.d4viddf.hyperbridge.util.RemoteViewsExtractor.isDeliveryFinishedStrong(deliveryCorpus) &&
                activeIslands.values.any { it.type == NotificationType.DELIVERY && it.packageName == sbn.packageName }
            ) {
                dismissDeliveryPills(sbn.packageName)
            }
            // Sinyal tuntas bisa diproses DULUAN (urutan newest-first): bila notif se-paket
            // lain ber-marker tuntas DAN lebih baru dari stage ini, postingan stage basi
            // ini gugur — dismiss + skip.
            // Syarat postTime: feedback/rating order LAMA (lebih tua) tidak boleh
            // membunuh pill order BARU (bukti: Feedback ...778 vs kitchen ...643).
            if (type == NotificationType.DELIVERY) {
                val finishedSibling = try {
                    activeNotifications?.filter { other ->
                        other.packageName == sbn.packageName && other.key != key &&
                            com.d4viddf.hyperbridge.util.RemoteViewsExtractor.isDeliveryFinishedStrong(sbnCorpus(other))
                    }?.maxByOrNull { it.postTime }
                } catch (_: Exception) { null }
                if (finishedSibling != null && finishedSibling.postTime >= sbn.postTime) {
                    dismissDeliveryPills(sbn.packageName)
                    try {
                        NotificationManagerCompat.from(this@NotificationReaderService).cancel(sbn.key.hashCode())
                    } catch (_: Exception) {}
                    cleanupCache(key)
                    if (debugLogEnabled()) Log.w(TAG, "DELIVERY-FINISHED-SIBLING dismiss pkg=${sbn.packageName} key=$key by=${finishedSibling.key}")
                    return
                }
            }

            // --- LAYERED TRIGGERS LOGIC — fallback: Shopee/Grab DELIVERY eligible auto-allow ---
            val effectiveTypes = getEffectiveTypes(sbn.packageName)
            if (!effectiveTypes.contains(type.name)) {
                val isShopeeDeliveryBypass = sbn.packageName == "com.shopee.id" && type == NotificationType.DELIVERY &&
                    (extras.containsKey("extra_live_activity_id") || sbn.notification.channelId?.contains("LIVE_ACTIVITY") == true)
                // Grab Transaction stage ("In the kitchen", "is here", ...) = order aktif,
                // auto-allow seperti Shopee live (promo GrabMore/Feedback/CALL sudah dikecualikan di detect).
                val isGrabDeliveryBypass = isGrabPipeline(sbn) && type == NotificationType.DELIVERY
                if (isShopeeDeliveryBypass || isGrabDeliveryBypass) {
                    if (debugLogEnabled()) Log.w(TAG, "BYPASS effectiveTypes for ${sbn.packageName} DELIVERY: $effectiveTypes -> force allow (auto-enable)")
                    serviceScope.launch { preferences.updateAppConfig(sbn.packageName, NotificationType.DELIVERY, true) }
                } else {
                    if (debugLogEnabled()) Log.w(TAG, "ABORTING: Type $type disabled by user/theme for ${sbn.packageName} effective=$effectiveTypes")
                    return
                }
            }

            // --- DELIVERY SINGLE-PILL DEDUP ---
            // Stage update via key baru selagi key lama masih hidup -> tanpa collapse ini
            // muncul double pill (satu stuck stage lama). Signature order yang sama
            // (liveId Shopee / grab:pkg) dikecualikan agar collapse mulus di bawah
            // (satu order = satu pill, reuse bridgeId) tidak rusak.
            // (Finished + stale-age sudah ditangani SEBELUM gate tipe di atas.)
            if (type == NotificationType.DELIVERY) {
                // Single-pill: stage update via key baru selagi key lama masih hidup
                // -> tanpa collapse ini muncul double pill (satu stuck stage lama).
                val incomingSig: String? = if (isGrabPipeline(sbn)) "grab:${sbn.packageName}"
                    else extras.getString("extra_live_activity_id")?.takeIf { it.isNotEmpty() }
                val dupes = activeIslands.entries.filter {
                    it.value.type == NotificationType.DELIVERY &&
                        it.value.packageName == sbn.packageName && it.key != key &&
                        !(incomingSig != null && it.value.subText == incomingSig)
                }
                for ((dupeKey, island) in dupes) {
                    try {
                        NotificationManagerCompat.from(this@NotificationReaderService).cancel(island.id)
                    } catch (_: Exception) {}
                    cleanupCache(dupeKey)
                    if (debugLogEnabled()) Log.w(TAG, "DELIVERY-DEDUP cancel $dupeKey keep $key")
                }
            }

            var effectiveKey = key
            removalJobs[effectiveKey]?.cancel()
            removalJobs.remove(effectiveKey)
            var isUpdate = activeIslands.containsKey(effectiveKey)
            var bridgeId = sbn.key.hashCode()

            // DELIVERY satu order = satu pill: update stage (key baru) menimpa island
            // order aktif yg sama (liveId Grab=nempel per-pkg, Shopee=liveId), bukan nambah pill.
            if (!isUpdate && type == NotificationType.DELIVERY) {
                val grabKey = if (isGrabPipeline(sbn)) "grab:${sbn.packageName}" else null
                val liveId = extras.getString("extra_live_activity_id")
                val orderSig = grabKey ?: liveId?.takeIf { it.isNotEmpty() }
                if (orderSig != null) {
                    val existingEntry = activeIslands.entries.find {
                        it.value.type == NotificationType.DELIVERY && it.value.packageName == sbn.packageName &&
                            it.value.subText == orderSig
                    }
                    if (existingEntry != null && existingEntry.key != key) {
                        // Freshness: jangan biarkan stage LAMA menimpa stage BARU.
                        // Shade/listing umumnya newest-first -> saat burst reprocess,
                        // key lama (postTime lebih kecil) datang belakangan dan harus skip.
                        val shownTime = deliveryContentTime[existingEntry.key]
                        if (shownTime != null && sbn.postTime < shownTime) {
                            if (debugLogEnabled()) Log.w(TAG, "DELIVERY-STALE skip key=$key (older than shown) pkg=${sbn.packageName}")
                            return
                        }
                        val oldKey = existingEntry.key
                        bridgeId = existingEntry.value.id
                        isUpdate = true

                        // Kunci LAMA dipertahankan (jangan pindah ke key baru): update stage
                        // Grab/Shopee ganti key tiap post — pindah key = island lama yatim.
                        activeTranslations.remove(oldKey)
                        timeoutJobs[oldKey]?.cancel()
                        timeoutJobs.remove(oldKey)
                        removalJobs[oldKey]?.cancel()
                        removalJobs.remove(oldKey)
                        // Hapus notif bridge lama BILA id berubah (jangan tumpuk dua pill).
                        if (existingEntry.value.id != bridgeId) {
                            try { NotificationManagerCompat.from(this).cancel(existingEntry.value.id) } catch (_: Exception) {}
                        }

                        effectiveKey = oldKey
                        activeTranslations[effectiveKey] = bridgeId
                        reverseTranslations[bridgeId] = effectiveKey
                    }
                }
            }

            if (!isUpdate && type == NotificationType.MESSAGE && sbn.groupKey != null) {
                val existingEntry = activeIslands.entries.find {
                    it.value.type == NotificationType.MESSAGE &&
                    it.value.packageName == sbn.packageName &&
                    it.value.groupKey == sbn.groupKey
                }

                if (existingEntry != null) {
                    val oldKey = existingEntry.key
                    bridgeId = existingEntry.value.id
                    effectiveKey = oldKey
                    isUpdate = true

                    activeIslands.remove(oldKey)
                    activeTranslations.remove(oldKey)
                    timeoutJobs[oldKey]?.cancel()
                    timeoutJobs.remove(oldKey)

                    effectiveKey = key
                    activeTranslations[effectiveKey] = bridgeId
                    reverseTranslations[bridgeId] = effectiveKey
                }
            }

            if (!isUpdate && (type == NotificationType.DOWNLOAD || type == NotificationType.PROGRESS)) {
                val existingEntries = activeIslands.entries.filter {
                    it.value.packageName == sbn.packageName &&
                    (it.value.type == NotificationType.DOWNLOAD || it.value.type == NotificationType.PROGRESS)
                }
                
                val existingEntry = if (existingEntries.size == 1) {
                    existingEntries.first()
                } else {
                    existingEntries.find { it.value.title == effectiveTitle }
                }

                if (existingEntry != null) {
                    val oldKey = existingEntry.key
                    bridgeId = existingEntry.value.id
                    effectiveKey = oldKey
                    isUpdate = true

                    activeIslands.remove(oldKey)
                    activeTranslations.remove(oldKey)
                    timeoutJobs[oldKey]?.cancel()
                    timeoutJobs.remove(oldKey)
                    removalJobs[oldKey]?.cancel()
                    removalJobs.remove(oldKey)

                    effectiveKey = key
                    activeTranslations[effectiveKey] = bridgeId
                    reverseTranslations[bridgeId] = effectiveKey
                }
            }

            if (!isUpdate && activeIslands.size >= MAX_ISLANDS) {
                handleLimitReached(type, sbn.packageName)
                if (activeIslands.size >= MAX_ISLANDS) return
            }

            val appIslandConfig = preferences.getAppIslandConfigSync(sbn.packageName)
            val globalConfig = preferences.getGlobalConfigSync()
            val finalConfig = appIslandConfig.mergeWith(globalConfig)
            val picKey = "pic_${bridgeId}"

            // --- LAYERED ENGINE LOGIC ---
            val useLiveUpdates = getEffectiveEngine(sbn.packageName)

            if (useLiveUpdates) {
                if (debugLogEnabled()) Log.i(TAG, " POSTING Native Live Update -> ID: $bridgeId, Type: $type")

                // [FIX] Fetch the user's custom layout so the Live Update can use it!
                val navLayout = if (type == NotificationType.NAVIGATION) getEffectiveNav(sbn.packageName) else null

                // [FIX] Pass the type and the right layout to the translator
                val builder = liveUpdateTranslator.translateToLiveUpdate(
                    sbn = sbn,
                    channelId = LIVE_UPDATE_CHANNEL_ID,
                    type = type,
                    navRight = navLayout?.second,
                    config = finalConfig
                )

                builder.extras.putString(EXTRA_ORIGINAL_KEY, sbn.key)

                val shouldAlertOnce = isUpdate && (type == NotificationType.PROGRESS || type == NotificationType.DOWNLOAD || type == NotificationType.MEDIA)
                builder.setOnlyAlertOnce(shouldAlertOnce)

                val hasPermission = com.d4viddf.hyperbridge.util.XiaomiNotificationHelper.hasFocusPermission(this)
                if (!hasPermission && com.d4viddf.hyperbridge.util.XiaomiNotificationHelper.isSupportIsland()) {
                    serviceScope.launch {
                        preferences.setFeaturedPermissionWarning(true)
                    }
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("open_troubleshoot", true)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.addAction(
                        android.R.drawable.ic_dialog_info,
                        getString(R.string.troubleshoot_featured_notification),
                        pendingIntent
                    )
                }

                val notification = builder.build()

                val actualProgress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
                val actualMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val isIndeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                val actionState = sbn.notification.actions?.joinToString { it.title?.toString() ?: "" } ?: ""

                val newContentHash = effectiveTitle.hashCode() * 31 +
                        effectiveText.hashCode() + actualProgress + actualMax +
                        isIndeterminate.hashCode() + actionState.hashCode()

                if (isUpdate && previous != null && previous.lastContentHash == newContentHash &&
                    (type != NotificationType.DELIVERY || rvFingerprint(sbn) == previous.rvHash)
                ) return

                // User/sistem baru saja dismiss konten identik -> jangan post ulang.
                // Test/clone dikecualikan agar replay stage di Test screen deterministik.
                if (!isTestNotif && !isRealClonePost && isDismissSuppressed(sbn.packageName, newContentHash, sbn.postTime)) {
                    if (debugLogEnabled()) Log.w(TAG, "SUPPRESSED-SWIPE skip pkg=${sbn.packageName} key=$key")
                    return
                }

                if (!shouldAlertOnce) {
                    ShizukuManager.notify(this, bridgeId, notification)
                } else {
                    NotificationManagerCompat.from(this).notify(bridgeId, notification)
                }

                activeTranslations[effectiveKey] = bridgeId
                reverseTranslations[bridgeId] = effectiveKey
                activeIslands[effectiveKey] = ActiveIsland(
                    id = bridgeId, type = type, postTime = System.currentTimeMillis(),
                    packageName = sbn.packageName, groupKey = sbn.groupKey, title = effectiveTitle, text = effectiveText,
                    subText = "LiveUpdate", lastContentHash = newContentHash, deleteIntent = sbn.notification.deleteIntent,
                    fastHash = deliveryFastHash,
                    rvHash = if (type == NotificationType.DELIVERY) rvFingerprint(sbn) else 0
                )
                if (type == NotificationType.DELIVERY) deliveryContentTime[effectiveKey] = sbn.postTime
                updatePermanentIsland()

                handlePostNotificationSideEffects(effectiveKey, bridgeId, finalConfig, type, true, sbn, effectiveTitle, effectiveText)
                return
            }

            // --- LAYERED CUSTOM ISLAND LOGIC ---
            val data: HyperIslandData = when (type) {
                NotificationType.CALL -> callTranslator.translate(sbn, picKey, finalConfig, activeTheme)
                NotificationType.NAVIGATION -> {
                    // --- LAYERED NAVIGATION LOGIC ---
                    val navLayout = getEffectiveNav(sbn.packageName)
                    navTranslator.translate(sbn, picKey, finalConfig, navLayout.first, navLayout.second, activeTheme)
                }
                NotificationType.TIMER -> timerTranslator.translate(sbn, picKey, finalConfig, activeTheme)
                NotificationType.PROGRESS -> progressTranslator.translate(sbn, effectiveTitle, picKey, finalConfig, activeTheme, isUpdate)
                NotificationType.DOWNLOAD -> downloadTranslator.translate(sbn, effectiveTitle, picKey, finalConfig, activeTheme, isUpdate)
                NotificationType.MEDIA -> mediaTranslator.translate(sbn, picKey, finalConfig)
                NotificationType.MESSAGE -> messageTranslator.translate(sbn, effectiveTitle, effectiveText, picKey, finalConfig, activeTheme)
                NotificationType.DELIVERY -> deliveryTranslator.translate(sbn, effectiveTitle, effectiveText, picKey, finalConfig, activeTheme)
                else -> standardTranslator.translate(sbn, effectiveTitle, effectiveText, picKey, finalConfig, activeTheme)
            }
            // jsonParam sudah mencakup seluruh output translate (100% extras) — tanpa signature RV.
            val newContentHash = data.jsonParam.hashCode()
            if (isUpdate && previous != null && previous.lastContentHash == newContentHash &&
                (type != NotificationType.DELIVERY || rvFingerprint(sbn) == previous.rvHash)
            ) return

            // User/sistem baru saja dismiss konten identik -> jangan post ulang.
            if (!isTestNotif && !isRealClonePost && isDismissSuppressed(sbn.packageName, newContentHash, sbn.postTime)) {
                if (debugLogEnabled()) Log.w(TAG, "SUPPRESSED-SWIPE skip pkg=${sbn.packageName} key=$key")
                return
            }

            kotlinx.coroutines.yield()

            val removedTime = recentlyRemovedKeys[rawSbn.key]
            // DELIVERY dikecualikan: Shopee update stage via cancel+repost key sama dalam ms;
            // gate 2s akan bunuh semua update (pill nempel di stage 1 selamanya).
            if (removedTime != null && System.currentTimeMillis() - removedTime < 2000 && type != NotificationType.DELIVERY) {
                if (debugLogEnabled()) Log.d(TAG, "Skipping post because notification was recently removed: ${rawSbn.key}")
                return
            }

            val shouldAlertOnce = isUpdate && (type == NotificationType.PROGRESS || type == NotificationType.DOWNLOAD || type == NotificationType.MEDIA)

            if (debugLogEnabled()) Log.i(TAG, " POSTING Island -> ID: $bridgeId, Type: $type, FinalTitle: '$effectiveTitle', FinalText: '$effectiveText'")
            postStandardNotification(sbn, bridgeId, data, shouldAlertOnce)

            // subText menyimpan signature order DELIVERY (liveId / grab:pkg) agar
            // stage berikutnya menimpa island yg sama (satu order = satu pill).
            val deliveryOrderSig = if (type == NotificationType.DELIVERY) {
                extras.getString("extra_live_activity_id")?.takeIf { it.isNotEmpty() }
                    ?: if (isGrabPipeline(sbn)) "grab:${sbn.packageName}" else ""
            } else ""
            activeIslands[effectiveKey] = ActiveIsland(
                id = bridgeId, type = type, postTime = System.currentTimeMillis(),
                packageName = sbn.packageName, groupKey = sbn.groupKey, title = effectiveTitle, text = effectiveText,
                subText = deliveryOrderSig, lastContentHash = newContentHash, deleteIntent = sbn.notification.deleteIntent,
                fastHash = deliveryFastHash,
                rvHash = if (type == NotificationType.DELIVERY) rvFingerprint(sbn) else 0
            )
            if (type == NotificationType.DELIVERY) deliveryContentTime[effectiveKey] = sbn.postTime
            // Cek-tuntas berjangkar ETA (tanpa ETA = fallback 45 mnt). Dijadwal ulang
            // bila async RV menemukan ETA asli. Custom path saja (native punya timeout sendiri).
            if (type == NotificationType.DELIVERY) {
                scheduleDeliveryEtaCheck(
                    effectiveKey,
                    etaMinutesOrNull(com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(deliveryCorpus)) ?: 45
                )
            }
            updatePermanentIsland()

            // --- DELIVERY ASYNC ETA (0ms pill) ---
            // Pill sudah muncul dari extras (kanan = ETA baru, atau pinjaman ETA terakhir
            // order yang sama bila stage ini tak bawa waktu). Cek gambar di background:
            // hanya update bila ketemu waktu BARU; miss = biarkan pill apa adanya.
            // GRAB: extras null semua — pill awal generik, isi penuh SELALU dari RV async.
            // ASYNC SKIP: notif teks biasa (contentView null, mis. Transaction) tak punya
            // RV — inflate pasti miss; jangan buang kerja background (bukti: corpus='null').
            if (type == NotificationType.DELIVERY && !getEffectiveEngine(sbn.packageName)) {
                val hasRv = sbn.notification.contentView != null || sbn.notification.bigContentView != null ||
                    sbn.notification.headsUpContentView != null
                val hasEta = data.jsonParam.contains("\"imageTextInfoRight\"") && !data.jsonParam.contains("\"imageTextInfoRight\":{\"type\":2,\"picInfo\":{\"type\":1,\"pic\":\"miui.focus.pic_hidden_pixel\"},\"textInfo\":{\"title\":\"\",\"content\":\"\"}}")
                // Fallback check lebih simple: jika eta kosong, json akan punya title:"" di right
                val isEtaEmpty = data.jsonParam.contains("\"textInfo\":{\"title\":\"\"") && data.jsonParam.contains("imageTextInfoRight")
                val isGrabRvOnly = isGrabPipeline(sbn) &&
                    effectiveTitle.isEmpty() && effectiveText.isEmpty()
                if ((isEtaEmpty || !hasEta || isGrabRvOnly) && hasRv) {
                    val sbnKey = sbn.key
                    val capturedSbn = sbn
                    val capturedPicKey = picKey
                    val capturedConfig = finalConfig
                    val capturedTheme = activeTheme
                    val capturedTitle = effectiveTitle
                    val capturedText = effectiveText
                    val capturedBridgeId = bridgeId
                    val capturedEffectiveKey = effectiveKey
                    serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                        try {
                            // Kecil delay biar pill settle, tapi tetap cepat (50ms)
                            kotlinx.coroutines.delay(80)
                            // Reflection dulu, fallback inflate (butuh context) bila reflection kosong.
                            // Tetap async setelah pill — pill tidak delay.
                            val rvCorpus: String? = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractRemoteViewsCorpusWithContext(applicationContext, capturedSbn)
                            val rvEta = rvCorpus?.let { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(it) }
                            // Jangan hidupkan lagi pill yang sudah di-dismiss
                            // (mis. SELESAI datang dalam 80ms jeda async).
                            if (!activeIslands.containsKey(capturedEffectiveKey)) return@launch
                            // GRAB RV-only: korpus RV = isi utama (stage/ETA/shade), update walau tanpa ETA.
                            // Shopee: hanya update bila ketemu waktu baru (pinjaman lama tetap tampil bila miss).
                            val isGrabUpdate = isGrabPipeline(capturedSbn) && !rvCorpus.isNullOrBlank()
                            if (!rvEta.isNullOrBlank() || isGrabUpdate) {
                                if (debugLogEnabled()) Log.w(TAG, "DELIVERY-ASYNC-ETA hit sbn=$sbnKey eta='$rvEta' corpus='${rvCorpus?.take(160)}'")
                                val updatedData = deliveryTranslator.translate(
                                    capturedSbn, capturedTitle, capturedText, capturedPicKey, capturedConfig, capturedTheme,
                                    forcedEta = rvEta, forcedRvCorpus = rvCorpus
                                )
                                // Update island yang sama — shouldAlertOnce=true agar tidak bunyi lagi
                                postStandardNotification(capturedSbn, capturedBridgeId, updatedData, true)
                                activeIslands[capturedEffectiveKey]?.let { old ->
                                    activeIslands[capturedEffectiveKey] = old.copy(
                                        lastContentHash = updatedData.jsonParam.hashCode(),
                                        rvHash = rvFingerprint(capturedSbn)
                                    )
                                }
                                // ETA asli ketemu -> jadwal ulang cek-tuntas dengan jangkar yang benar.
                                etaMinutesOrNull(rvEta)?.let { scheduleDeliveryEtaCheck(capturedEffectiveKey, it) }
                            } else {
                                if (debugLogEnabled()) Log.w(TAG, "DELIVERY-ASYNC-ETA miss sbn=$sbnKey corpus='${rvCorpus?.take(160) ?: "null"}'")
                            }
                        } catch (e: Exception) {
                            if (debugLogEnabled()) Log.e(TAG, "DELIVERY-ASYNC-ETA error", e)
                        }
                    }
                }
            }

            handlePostNotificationSideEffects(effectiveKey, bridgeId, finalConfig, type, false, sbn, effectiveTitle, effectiveText)

        } catch (e: Exception) {
            if (debugLogEnabled()) Log.e(TAG, "💥 Error processing standard notification", e)
        }
    }

    private fun isDownloadNotification(sbn: StatusBarNotification, title: String, text: String): Boolean {
        val pkg = sbn.packageName.lowercase()
        val titleLower = title.lowercase()
        val textLower = text.lowercase()
        val channelId = sbn.notification.channelId?.lowercase() ?: ""
        
        val isMatch = if (pkg.contains("download") || pkg.contains("downloader") || pkg.contains("chrome") || 
            pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("market") || 
            pkg.contains("vending") || pkg.contains("play.store") || pkg.contains("playstore") || 
            pkg.contains("store") || pkg.contains("fdroid") || pkg.contains("samsungapps") || 
            pkg.contains("mipicks") || pkg.contains("venezia") || pkg.contains("packageinstaller") || 
            pkg.contains("installer") || pkg.contains("gms") || channelId.contains("download") || 
            channelId.contains("install")) {
            true
        } else {
            val extras = sbn.notification.extras
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.lowercase() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.lowercase() ?: ""
            
            val downloadKeywords = listOf(
                // English
                "download", "install", "update", "updat", "upload", "transfer",
                // Spanish / Portuguese / Italian / French
                "descarg", "baix", "telecharg", "instal", "actuali", "carg", "subi", "transf",
                // German
                "laden", "gelad", "aktualis",
                // Polish
                "pobier", "pobran", "aktual",
                // Russian / Ukrainian
                "скач", "загруз", "устан", "обнов"
            )
            downloadKeywords.any { 
                titleLower.contains(it) || 
                textLower.contains(it) || 
                subText.contains(it) || 
                infoText.contains(it) 
            }
        }

        if (debugLogEnabled()) Log.d(TAG, "🔍 isDownloadNotification check: pkg=$pkg, channelId='$channelId', title='$title', text='$text', resolved=$isMatch")
        return isMatch
    }

    private fun hasProgressNotification(sbn: StatusBarNotification, title: String, text: String): Boolean {
        val extras = sbn.notification.extras
        val isDownload = isDownloadNotification(sbn, title, text)
        val isOngoing = (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        return extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) ||
                (isDownload && extractTextPercentage(title, text) != null) ||
                (isDownload && isOngoing)
    }

    private fun extractTextPercentage(title: String?, text: String?): Int? {
        val pattern = Regex("""\b(\d{1,3})\s*%""")
        val textMatch = text?.let { pattern.find(it) }
        val titleMatch = title?.let { pattern.find(it) }
        val match = textMatch ?: titleMatch
        if (match != null) {
            val value = match.groupValues[1].toIntOrNull()
            if (value != null && value in 0..100) {
                return value
            }
        }
        return null
    }

    private fun resolveTitle(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val bigTitle = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
        val pkg = sbn.packageName

        if ((title.isEmpty() || title.equals(pkg, ignoreCase = true)) && !bigTitle.isNullOrEmpty()) {
            return bigTitle
        }
        if (!title.isNullOrEmpty() && !title.equals(pkg, ignoreCase = true)) return title
        // Tanpa fallback RemoteViews (jalur 100% extras).
        if (title.equals(pkg, ignoreCase = true)) return ""
        return title
    }

    private fun resolveText(extras: Bundle): String {
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()

        if (!text.isNullOrEmpty()) return text
        if (!bigText.isNullOrEmpty()) return bigText
        if (!subText.isNullOrEmpty()) return subText
        if (!infoText.isNullOrEmpty()) return infoText
        return ""
    }

    private suspend fun ensureValidSbn(sbn: StatusBarNotification): StatusBarNotification {
        val extras = sbn.notification.extras
        val title = resolveTitle(sbn)
        val text = resolveText(extras)
        val hasProgress = hasProgressNotification(sbn, title, text)
        if (hasProgress) return sbn

        val pkg = sbn.packageName

        val isSuspicious = title.isEmpty() || text.equals(pkg, ignoreCase = true)

        if (isSuspicious) {
            delay(150.milliseconds)
            try {
                val activeList = activeNotifications
                val updatedSbn = activeList?.firstOrNull { it.key == sbn.key }
                if (updatedSbn != null) return updatedSbn
            } catch (_: Exception) { }
        }
        return sbn
    }

    private fun detectNotificationType(sbn: StatusBarNotification): NotificationType {
        val n = sbn.notification
        val extras = n.extras
        val template = extras.getString(Notification.EXTRA_TEMPLATE) ?: ""
        val channelId = n.channelId ?: ""
        val isCall = n.category == Notification.CATEGORY_CALL || template == "android.app.Notification\$CallStyle"
        val isNav = n.category == Notification.CATEGORY_NAVIGATION || sbn.packageName.let { it.contains("maps") || it.contains("waze") }
        val isTimer = (extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) || n.category == Notification.CATEGORY_ALARM) && n.`when` > 0
        val isMedia = template.contains("MediaStyle") || n.category == Notification.CATEGORY_TRANSPORT
        val isMessage = n.category == Notification.CATEGORY_MESSAGE || template == "android.app.Notification.MessagingStyle"
        // ShopeeFood confirmed NOT natively supported on HyperOS 3 -> HyperBridge handles it.
        // DELIVERY detected like any other built-in type: via the standard Android 16
        // ProgressStyle template (documented use cases: rideshare, delivery, navigation),
        // the same way MEDIA is detected via the MediaStyle template. Also via generic
        // Live Activity (extra_live_activity_id) which ShopeeFood uses
        // (SHOPEE_LIVE_ACTIVITY_ID, DecoratedCustomViewStyle). No package guessing.
        val isProgressStyle = template == "android.app.Notification\$ProgressStyle"
        val isLiveActivity = extras.containsKey("extra_live_activity_id")

        // --- SHOPEE ELIGIBLE CHECK (fallback chain: ambil semua data) ---
        // Primary: SHOPEE_LIVE_ACTIVITY_ID + liveId (observed: "Driver sedang menuju Resto" / shopee_food_orders_*)
        // Fallback 1: SHOPEE_FOOD_ID channel (exists but currently unused) + food keywords, bukan promo
        // Fallback 2: any SHOPEE channel + customView + food keywords (jika Shopee ganti implementasi)
        val isShopee = sbn.packageName == "com.shopee.id"
        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val rawBig = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val rawSub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        // Tanpa fallback RemoteViews (jalur 100% extras).
        val combined = "$rawTitle $rawText $rawBig $rawSub".lowercase()
        val hasFoodKeyword = combined.contains("driver") || combined.contains("resto") || combined.contains("pesanan") ||
            combined.contains("makanan") || combined.contains("momoyo") || combined.contains("sedang menuju") ||
            combined.contains("sedang disiapkan") || combined.contains("diantar") || combined.contains("mencari driver")
        val isPromo = combined.contains("voucher") || combined.contains("diskon") || combined.contains("promo") ||
            combined.contains("gratis") && combined.contains("ongkir") || combined.contains("9.9") || combined.contains("serba rp")
        val hasCustomView = extras.getBoolean("android.contains.customView", false)
        val isShopeeFoodEligible = isShopee && (
            (isLiveActivity && channelId.contains("LIVE_ACTIVITY", ignoreCase = true)) ||
                channelId.equals("SHOPEE_FOOD_ID", ignoreCase = true) && hasFoodKeyword && !isPromo ||
                (hasCustomView && channelId.contains("SHOPEE", ignoreCase = true) && hasFoodKeyword && !isPromo)
            )
        val isShopeeFoodFallback = isShopee && channelId.equals("SHOPEE_FOOD_ID", ignoreCase = true) && hasFoodKeyword && !isPromo
                // --- GRAB ELIGIBLE CHECK (observed 2026-09-17, order Burjo Titik Kumpul) ---
        // Dua jalur sejajar:
        //  (a) live_activity_channel_01 + customView, extras NULL — isi di RemoteViews (dibaca async).
        //  (b) channel Transaction + BigTextStyle, extras LENGKAP ("In the kitchen",
        //      "Burjo ... is preparing your order...") — stage Grab, 1:1 Shopee extras.
        // Promo ("Offers From Grab": GrabMore/Bintang Lima) + Feedback + CALL dikecualikan.
        // Operasional ("Photo upload successful" / "Thanks for helping out your driver!")
        // BUKAN stage — wajib pola status order, kata "driver" doang tidak cukup.
        val isGrab = isGrabPipeline(sbn)
        val isGrabLiveEligible = isGrab && hasCustomView &&
            (channelId.contains("live_activity", ignoreCase = true) || channelId.contains("grabfood", ignoreCase = true) || channelId.contains("food", ignoreCase = true))
        val isGrabPromoChannel = channelId.contains("offers", ignoreCase = true) ||
            channelId.contains("feedback", ignoreCase = true)
        val hasGrabStage = combined.contains("preparing your order") || combined.contains("in the kitchen") ||
            combined.contains("mencari driver") || combined.contains("finding driver") ||
            combined.contains("is here") || combined.contains("on the way") || combined.contains("on its way") ||
            combined.contains("arriving") || combined.contains("picked up") || combined.contains("heading to") ||
            combined.contains("heading your way") || combined.contains("delivered") || combined.contains("order complete")
        val isGrabPromo = combined.contains("grabmore") || combined.contains("diskon ongkir") ||
            combined.contains("bintang lima") || combined.contains("verdict") ||
            combined.contains("tell us what you think") || combined.contains("photo upload") ||
            combined.contains("thanks for helping")
        val isGrabFoodEligible = isGrabLiveEligible ||
            (isGrab && channelId.equals("Transaction", ignoreCase = true) && hasGrabStage && !isGrabPromo && !isGrabPromoChannel)

        val title = resolveTitle(sbn)
        val text = resolveText(extras)
        val isDownload = isDownloadNotification(sbn, title, text)
        val hasProgress = hasProgressNotification(sbn, title, text)

        // [DEBUG] Log delivery candidates so we can confirm the real package/type on-device (Log.w biar tidak di-strip proguard)
        if (sbn.packageName.contains("shopee") || sbn.packageName.contains("gojek") || sbn.packageName.contains("grab") || isProgressStyle || isLiveActivity || isShopeeFoodEligible) {
            if (debugLogEnabled()) Log.w(TAG, "DELIVERY-DEBUG pkg=${sbn.packageName} ch=$channelId cat=${n.category} tpl=$template title='$title' text='$text' big='$rawBig' progress=$hasProgress live=$isLiveActivity eligible=$isShopeeFoodEligible promo=$isPromo keys=${extras.keySet().joinToString()}")
        }
        return when {
            isCall -> NotificationType.CALL
            isNav -> NotificationType.NAVIGATION
            // ShopeFood delivery harus diutamakan sebelum MESSAGE agar tidak salah jadi chat
            isShopeeFoodEligible -> NotificationType.DELIVERY
            isShopeeFoodFallback -> NotificationType.DELIVERY
            // GrabFood: live-activity RV-only + Transaction ber-teks — tetap DELIVERY, isi dibaca async.
            isGrabFoodEligible -> NotificationType.DELIVERY
            isMessage -> NotificationType.MESSAGE
            isProgressStyle -> NotificationType.DELIVERY
            isLiveActivity -> NotificationType.DELIVERY
            isTimer -> NotificationType.TIMER
            isMedia -> NotificationType.MEDIA
            isMessage -> NotificationType.MESSAGE
            hasProgress -> {
                if (isDownload) {
                    NotificationType.DOWNLOAD
                } else {
                    NotificationType.PROGRESS
                }
            }
            else -> NotificationType.STANDARD
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postStandardNotification(sbn: StatusBarNotification, bridgeId: Int, data: HyperIslandData, shouldAlertOnce: Boolean) {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_went_wrong))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(shouldAlertOnce)

        val extras = Bundle()
        extras.putString(EXTRA_ORIGINAL_KEY, sbn.key)
        builder.addExtras(extras)
        builder.addExtras(data.resources)

        val hasPermission = com.d4viddf.hyperbridge.util.XiaomiNotificationHelper.hasFocusPermission(this)
        if (!hasPermission) {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("open_troubleshoot", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pendingIntent)
            builder.addAction(
                android.R.drawable.ic_dialog_info,
                getString(R.string.troubleshoot_featured_notification),
                pendingIntent
            )
        } else {
            sbn.notification.contentIntent?.let { originalIntent ->
                if (detectNotificationType(sbn) == NotificationType.MESSAGE) {
                    val clickIntent = Intent("com.d4viddf.hyperbridge.ISLAND_CLICKED").apply {
                        setPackage(packageName)
                        putExtra("sbn_key", sbn.key)
                        putExtra("bridge_id", bridgeId)
                        putExtra("original_intent", originalIntent)
                    }
                    val clickPendingIntent = PendingIntent.getBroadcast(
                        this,
                        bridgeId,
                        clickIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setContentIntent(clickPendingIntent)
                } else {
                    builder.setContentIntent(originalIntent)
                }
            }
        }

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", data.jsonParam)

        if (!shouldAlertOnce) {
            ShizukuManager.notifyWithCancel(this, bridgeId, notification)
        } else {
            NotificationManagerCompat.from(this).notify(bridgeId, notification)
        }

        activeTranslations[sbn.key] = bridgeId
        reverseTranslations[bridgeId] = sbn.key
    }

    // =========================================================================
    //  HELPERS & SETUP
    // =========================================================================

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val notifChannel = NotificationChannel(NOTIFICATION_CHANNEL_ID, getString(R.string.channel_active_islands), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        manager.createNotificationChannel(notifChannel)

        val widgetChannel = NotificationChannel(WIDGET_CHANNEL_ID, "Widgets Overlay", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        manager.createNotificationChannel(widgetChannel)

        val liveUpdateChannel = NotificationChannel(LIVE_UPDATE_CHANNEL_ID, getString(R.string.channel_live_updates), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        manager.createNotificationChannel(liveUpdateChannel)

        val watchRelayChannel = NotificationChannel(WATCH_RELAY_CHANNEL_ID, "Watch Relay", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); enableVibration(false); setShowBadge(false)
        }
        manager.createNotificationChannel(watchRelayChannel)
    }

    private fun shouldProcessWidgetUpdate(widgetId: Int, config: WidgetConfig): Boolean {
        val now = System.currentTimeMillis()
        val lastTime = widgetUpdateDebouncer[widgetId] ?: 0L
        val throttleTime = if (config.renderMode == WidgetRenderMode.SNAPSHOT) 1500L else 200L
        if (now - lastTime < throttleTime) return false
        widgetUpdateDebouncer[widgetId] = now
        return true
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private suspend fun processSingleWidget(widgetId: Int, config: WidgetConfig) {
        try {
            val data = widgetTranslator.translate(widgetId)
            postWidgetNotification(WIDGET_ID_BASE + widgetId, data)
            activeWidgets.add(widgetId)
            updatePermanentIsland()
        } catch (e: Exception) { if (debugLogEnabled()) Log.e(TAG, "Failed widget $widgetId", e) }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postWidgetNotification(notificationId: Int, data: HyperIslandData) {
        val builder = NotificationCompat.Builder(this, WIDGET_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Widget Overlay").setContentText(getString(R.string.widget_went_wrong))
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
            .setOnlyAlertOnce(true).addExtras(data.resources)

        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        builder.setContentIntent(pendingIntent)

        val notification = builder.build()
        notification.extras.putString("miui.focus.param", data.jsonParam)
        ShizukuManager.notify(this, notificationId, notification)
    }

    private fun handleLimitReached(newType: NotificationType, newPkg: String) {
        val oldest = activeIslands.minByOrNull { it.value.postTime } ?: return

        when (currentMode) {
            IslandLimitMode.FIRST_COME -> {
                // Ignore the new notification by removing it immediately (or simply returning, but returning here means the caller won't add it)
                // The logic in the caller says:
                // if (!isUpdate && activeIslands.size >= MAX_ISLANDS) {
                //    handleLimitReached(type, sbn.packageName)
                //    if (activeIslands.size >= MAX_ISLANDS) return
                // }
                // So if we do nothing here, the size remains >= MAX_ISLANDS, and the caller will return.
                return
            }
            IslandLimitMode.MOST_RECENT -> {
                NotificationManagerCompat.from(this).cancel(oldest.value.id)
                cleanupCache(oldest.key)
            }
            IslandLimitMode.PRIORITY -> {
                // Check if newPkg has higher priority than existing ones.
                // Priority is determined by its index in appPriorityList (lower index = higher priority).
                // If it's not in the list, it has the lowest priority (Int.MAX_VALUE).
                val newPriority = appPriorityList.indexOf(newPkg).let { if (it == -1) Int.MAX_VALUE else it }
                
                // Find the existing active island with the lowest priority (highest index value)
                val lowestPriorityIsland = activeIslands.maxByOrNull {
                    appPriorityList.indexOf(it.value.packageName).let { idx -> if (idx == -1) Int.MAX_VALUE else idx }
                }

                if (lowestPriorityIsland != null) {
                    val lowestPriority = appPriorityList.indexOf(lowestPriorityIsland.value.packageName).let { if (it == -1) Int.MAX_VALUE else it }
                    if (newPriority <= lowestPriority) {
                        // The new notification has equal or higher priority than the lowest existing one.
                        // Remove the lowest priority existing notification.
                        NotificationManagerCompat.from(this).cancel(lowestPriorityIsland.value.id)
                        cleanupCache(lowestPriorityIsland.key)
                    } else {
                        // The new notification has lower priority than all existing ones. Do nothing, which will ignore it.
                        return
                    }
                }
            }
        }
    }

    private fun isJunkNotification(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        val pkg = sbn.packageName

        // ShopeFood LIVE eligible tidak pernah junk — ambil semua data eligible
        if (pkg == "com.shopee.id" && extras.containsKey("extra_live_activity_id")) return false
        // GrabFood: live-activity RV-only + Transaction stage ("In the kitchen", "is here")
        // tidak pernah junk — teks Inggris, tak cocok pola junk Indonesia.
        if (isGrabPipeline(sbn)) {
            val ch = notification.channelId ?: ""
            if (ch.contains("live_activity", ignoreCase = true)) return false
            if (ch.equals("Transaction", ignoreCase = true)) {
                val t = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val b = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val c = "$t $b".lowercase()
                // Wajib pola status order (ketat, 1:1 detect) — "driver"/"your order" doang
                // tidak cukup (bukti: "Photo upload successful" nyasar).
                if (c.contains("preparing your order") || c.contains("in the kitchen") ||
                    c.contains("mencari driver") || c.contains("finding driver") ||
                    c.contains("is here") || c.contains("on the way") || c.contains("on its way") ||
                    c.contains("arriving") || c.contains("picked up") || c.contains("heading to") ||
                    c.contains("heading your way") || c.contains("delivered") || c.contains("order complete")) return false
            }
        }
        // ShopeeFood via RemoteViews juga eligible (fallback jika tanpa liveId tapi customView + food keyword)
        if (pkg == "com.shopee.id" && extras.getBoolean("android.contains.customView", false)) {
            val ch = notification.channelId ?: ""
            if (ch.contains("SHOPEE", ignoreCase = true)) {
                // cek promo vs food via RemoteViews fallback sudah di detect, tapi di sini jangan block
                return false
            }
        }

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

        val hasProgress = hasProgressNotification(sbn, title, text)
        val isSpecial = notification.category == Notification.CATEGORY_TRANSPORT || notification.category == Notification.CATEGORY_CALL ||
                notification.category == Notification.CATEGORY_NAVIGATION || extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        if (hasProgress || isSpecial) return false
        // Kosong = junk, tanpa intip RemoteViews (jalur 100% extras).
        if (title.isEmpty() && text.isEmpty()) {
            return true
        }
        if (title.equals(pkg, ignoreCase = true) || text.equals(pkg, ignoreCase = true)) return true
        if (globalBlockedTerms.any { "$title $text".contains(it, true) }) return true

        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val type = detectNotificationType(sbn)
            if (type != NotificationType.MESSAGE) return true
            if (text.isEmpty() || title.isEmpty()) return true
            // A summary with live children is a duplicate: messaging apps post the real
            // per-conversation notification plus an "N new messages" summary. With
            // "remove original notification" disabled the summary survives and would
            // become a second island. Only islandify a summary that stands alone
            // (some apps post only the summary).
            val group = notification.group
            if (group != null) {
                val hasLiveChild = try {
                    activeNotifications?.any {
                        it.packageName == pkg && it.key != sbn.key &&
                            (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                            it.notification.group == group
                    } == true
                } catch (_: Exception) { false }
                if (hasLiveChild) return true
            }
        }

        return false
    }

    private fun getCachedAppLabel(pkg: String): String = appLabelCache.getOrPut(pkg) {
        try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { "" }
    }

    private fun shouldIgnore(packageName: String): Boolean = packageName == this.packageName || packageName == "android" || packageName.contains("miui.notification")
    private fun isAppAllowed(packageName: String): Boolean = allowedPackageSet.contains(packageName)

    /** Jalur Grab 1:1 — paket Grab asli ATAU REAL-clone bertanda Grab (Test screen). */
    private fun isGrabPipeline(sbn: StatusBarNotification): Boolean {        if (sbn.packageName == "com.grabtaxi.passenger") return true
        if (sbn.packageName != packageName) return false
        val ex = sbn.notification.extras
        return ex.getBoolean(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_CLONE, false) &&
            ex.getString(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_PKG) == "com.grabtaxi.passenger"
    }

    /** Fingerprint isi RemoteViews (reflection 1-3ms, TANPA inflate) buat DELIVERY. */
    private fun rvFingerprint(sbn: StatusBarNotification): Int {
        return try {
            com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractRemoteViewsCorpus(sbn)?.hashCode() ?: 0
        } catch (_: Exception) { 0 }
    }

    private var syncJob: Job? = null

    override fun onListenerConnected() { 
        if (debugLogEnabled()) Log.i(TAG, "HyperBridge Service Connected")
        syncNotifications(refresh = true)
    }

    private fun syncNotifications(refresh: Boolean = false) {
        val now = System.currentTimeMillis()
        recentlyRemovedKeys.entries.removeIf { now - it.value > 10000 }

        serviceScope.launch(Dispatchers.IO) {
            try {
                val currentNotifications = activeNotifications ?: return@launch
                val systemNotificationKeys = currentNotifications.map { it.key }.toSet()

                var nativeChanged = false
                for (sbn in currentNotifications) {
                    if (sbn.packageName != packageName) {
                        val extras = sbn.notification.extras
                        var isNative = false
                        if (extras != null) {
                            if (extras.containsKey("miui.focus.param") || extras.containsKey("miui.system.focus.param")) {
                                isNative = true
                            }
                            val template = extras.getString(Notification.EXTRA_TEMPLATE)
                            if (template == "androidx.media.app.NotificationCompat\$MediaStyle" ||
                                template == "android.app.Notification\$MediaStyle") {
                                isNative = true
                            }
                        }
                        if (isNative) {
                            if (nativeIslands.add(sbn.key)) nativeChanged = true
                        } else {
                            if (nativeIslands.remove(sbn.key)) nativeChanged = true
                        }
                    }
                }
                val currentNatives = nativeIslands.toList()
                for (key in currentNatives) {
                    if (!systemNotificationKeys.contains(key)) {
                        if (nativeIslands.remove(key)) nativeChanged = true
                    }
                }
                if (nativeChanged) updatePermanentIsland()

                val currentKeys = currentNotifications.map { it.key }.toSet()
                
                val keysToRemove = mutableListOf<String>()
                for ((originalKey, activeIsland) in activeIslands) {
                    if (!currentKeys.contains(originalKey)) {
                        val appConfig = preferences.getAppIslandConfigSync(activeIsland.packageName)
                        val globalConfig = preferences.getGlobalConfigSync()
                        val finalConfig = appConfig.mergeWith(globalConfig)

                        val forceDismiss = activeIsland.type == NotificationType.CALL || 
                                           activeIsland.type == NotificationType.MEDIA || 
                                           activeIsland.type == NotificationType.NAVIGATION

                        // If the app intentionally removes the original notification, it's expected to be missing from currentKeys.
                        if (!forceDismiss && finalConfig.removeOriginalNotification == true) {
                            continue
                        }

                        if (finalConfig.dismissWithOriginal == true || forceDismiss) {
                            keysToRemove.add(originalKey)
                        }
                    }
                }

                for (key in keysToRemove) {
                    if (debugLogEnabled()) Log.d(TAG, "Sync: Found stuck notification $key, removing.")
                    val hyperId = activeTranslations[key]
                    if (hyperId != null) {
                        try {
                            NotificationManagerCompat.from(this@NotificationReaderService).cancel(hyperId)
                        } catch (_: Exception) {}
                    }
                    cleanupCache(key)
                }
                // Active non-native notifications that were never posted through us (service
                // freshly connected after install/reboot/process death): route them through
                // the normal posted pipeline so ongoing notifications (e.g. ShopeeFood order)
                // still get bridged. Dedup/contentHash/recentlyRemoved guards live inside
                // onNotificationPosted -> processStandardNotification; skip already-tracked
                // keys so the periodic tick stays a no-op when nothing is missing.
                val pendingSync = currentNotifications.filter { sbn ->
                    sbn.packageName != packageName &&
                        !nativeIslands.contains(sbn.key) &&
                        !activeIslands.containsKey(sbn.key)
                }
                for (sbn in pendingSync) {
                    try {
                        onNotificationPosted(sbn)
                    } catch (_: Exception) {}
                }

                // Bridged notifications we no longer track (e.g. left over from a service restart)
                // keep their island slot occupied forever, since island-swipe never removes them.
                for (sbn in currentNotifications) {
                    if (sbn.packageName != packageName) continue
                    val id = sbn.id
                    if (id == PermanentIslandManager.PERMANENT_BRIDGE_ID) continue
                    if (id >= WIDGET_ID_BASE) continue
                    if (id in (WATCH_RELAY_ID_BASE - 0x0F)..WATCH_RELAY_ID_BASE) continue
                    if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
                    if (reverseTranslations.containsKey(id)) continue
                    if (System.currentTimeMillis() - sbn.postTime < 5000) continue
                    if (debugLogEnabled()) Log.d(TAG, "Sync: Reaping orphan bridge notification $id")
                    try {
                        NotificationManagerCompat.from(this@NotificationReaderService).cancel(id)
                    } catch (_: Exception) {}
                }

                val islandPresent = currentNotifications.any {
                    it.packageName == packageName && it.id == PermanentIslandManager.PERMANENT_BRIDGE_ID
                }
                permanentIslandManager.reconcile(
                    activeIslands.size + activeWidgets.size,
                    nativeIslands.isNotEmpty(),
                    islandPresent,
                    refresh
                )
            } catch (e: Exception) {
                if (debugLogEnabled()) Log.e(TAG, "Error syncing notifications", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(systemReceiver)
        unregisterReceiver(islandClickReceiver)
        syncJob?.cancel()
        serviceScope.cancel() 
    }
}