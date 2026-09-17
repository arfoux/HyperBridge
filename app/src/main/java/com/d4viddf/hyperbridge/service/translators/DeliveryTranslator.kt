package com.d4viddf.hyperbridge.service.translators

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.theme.ThemeRepository
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.models.IslandConfig
import com.d4viddf.hyperbridge.models.theme.HyperTheme
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.CircularProgressInfo
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.ProgressTextInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class DeliveryTranslator(context: Context, repo: ThemeRepository) : BaseTranslator(context, repo) {

    companion object {
        /** ETA terakhir per order (key = liveId order, fallback pkg). Dipakai saat stage baru tak bawa waktu. */
        private val lastEtaByOrder = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Order terakhir per package — agar repost tanpa liveId tetap nempel ke order yang sama. */
        private val lastOrderByPkg = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** Cache ikon app Grab (PackageManager) — dibaca sekali per proses. */
        private var grabIconCached = false
        private var grabIconBitmap: Bitmap? = null
        /** Kunci order selesai: stage 3 / "selamat menikmati" menghapus ingatan ETA order itu. */
        private fun isFinishedStage(stage: Int?, corpus: String): Boolean {
            if (stage != null && stage >= 3) return true
            val c = corpus.lowercase()
            return c.contains("selamat menikmati")
        }
    }

    /** Ganjal wide (motor 88x44) ke kanvas persegi transparan agar utuh, tidak lonjong/crop. */
    private fun squarePicture(key: String, resId: Int): HyperPicture {
        return try {
            val drawable = ContextCompat.getDrawable(context, resId)
                ?: return getDrawablePicture(key, resId)
            val src = drawable.toBitmap()
            if (src.width == src.height) return HyperPicture(key, src)
            val size = maxOf(src.width, src.height)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(out)
            canvas.drawBitmap(src, ((size - src.width) / 2f), ((size - src.height) / 2f), null)
            HyperPicture(key, out)
        } catch (_: Exception) {
            getDrawablePicture(key, resId)
        }
    }

    /** Ikon app resmi Grab (PackageManager, cache per proses) — pill Grab live-activity
     * tak bawa largeIcon sama sekali; smallIcon-nya resource internal yg tak terbaca.
     * Shopee tak tersentuh: Shopee tak bawa largeIcon -> tetap logo ShopeeFood hardcode. */
    private fun grabAppIcon(): Bitmap? {
        if (grabIconCached) return grabIconBitmap
        grabIconCached = true
        grabIconBitmap = try {
            val d = context.packageManager.getApplicationIcon("com.grabtaxi.passenger")
            d.toBitmap()
        } catch (_: Exception) { null }
        return grabIconBitmap
    }

    private val preferences = AppPreferences(context)
    fun translate(
        sbn: StatusBarNotification,
        effectiveTitle: String,
        effectiveText: String,
        picKey: String,
        config: IslandConfig,
        theme: HyperTheme?,
        forcedEta: String? = null,
        forcedRvCorpus: String? = null
    ): HyperIslandData {

        // 1. Resolve Theme Colors — hijau Grab untuk GrabFood, oranye Shopee default.
        val themeColor = if (sbn.packageName == "com.grabtaxi.passenger") "#00B14F"
            else resolveColor(theme, sbn.packageName, "#EE4D2D") // Shopee orange-ish default

        // 2. Parse Notification Content — fallback chain: ambil semua data eligible
        val extras = sbn.notification.extras

        // [DEBUG] Dump raw payload so we can refine the mapping without guessing (Log.w biar kebaca di release)
        if (preferences.debugLoggingSync()) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-PAYLOAD pkg=${sbn.packageName} ch=${sbn.notification.channelId} " +
                "title='${extras.getCharSequence(Notification.EXTRA_TITLE)}' " +
                "text='${extras.getCharSequence(Notification.EXTRA_TEXT)}' " +
                "big='${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}' " +
                "sub='${extras.getCharSequence(Notification.EXTRA_SUB_TEXT)}' " +
                "info='${extras.getCharSequence(Notification.EXTRA_INFO_TEXT)}' " +
                "progress=${extras.getInt(Notification.EXTRA_PROGRESS, 0)}/" +
                "${extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)} " +
                "live='${extras.getString("extra_live_activity_id")}' " +
                "tpl='${extras.getString(Notification.EXTRA_TEMPLATE)}' custom=${extras.getBoolean("android.contains.customView")} " +
                "keys=${extras.keySet().joinToString()}"
        )
        val debug = preferences.debugLoggingSync()
        // 100% EXTRAS: tidak ada baca contentView/RemoteViews di jalur produksi.
        // Terbukti live: title+text extras selalu ada per stage; ETA/resto/banner hanya di RV
        // dan sengaja tidak dipakai demi kecepatan + nol inflate.
        // Fallback extras-only: title -> bigTitle -> type_delivery (tanpa RemoteViews)
        var title = effectiveTitle.ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (title.isEmpty()) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (title.isEmpty()) {
            title = context.getString(R.string.type_delivery)
        }
        var text = effectiveText.ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        // ETA kanan: menit tertulis menang; kalau cuma range/jam, hitung menit dari data yang ada.
        // Sumber tunggal: RemoteViewsExtractor.extractEtaFromCorpus (0ms, tanpa inflate di jalur pill).
        val etaCorpus = listOf(
            text,
            title,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty(),
        )
        var eta = forcedEta?.takeIf { it.isNotBlank() }
            ?: etaCorpus.firstNotNullOfOrNull {
                runCatching { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(it) }.getOrNull()?.takeIf(String::isNotBlank)
            }
            ?: ""
        // Fallback RV sync HANYA jika forcedRvCorpus disediakan (jalur async post-pill).
        // Jalur utama (pill awal) 0ms: tidak sentuh RemoteViews sama sekali.
        if (eta.isEmpty() && forcedRvCorpus != null) {
            eta = runCatching { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(forcedRvCorpus) }.getOrNull() ?: ""
        }
        // Legacy sync fallback dimatikan untuk 0ms pill.
        // if (eta.isEmpty() && extras.getBoolean("android.contains.customView", false)) {
        //     val t0 = android.os.SystemClock.elapsedRealtime()
        //     val rvCorpus = try { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractRemoteViewsCorpus(sbn) } catch (_: Exception) { null }
        //     val rvEta = if (!rvCorpus.isNullOrBlank()) runCatching { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(rvCorpus) }.getOrNull() else null
        //     if (!rvEta.isNullOrEmpty()) eta = rvEta
        //     if (debug) android.util.Log.w("HyperBridgeDebug", "DELIVERY-ETA-RV pkg=${sbn.packageName} rvEta='${rvEta ?: ""}' dt=${android.os.SystemClock.elapsedRealtime() - t0}ms")
        // }
        // Grab: title/text extras NULL semua — isi hanya di RemoteViews (customView).
        // Jalur RV disediakan via forcedRvCorpus oleh caller async (NotificationReaderService);
        // di sini title/text/RV digabung jadi satu korpus agar stage+ETA tetap kep baca.
        val rvExtra = forcedRvCorpus.orEmpty()
        if (title.isEmpty() && text.isEmpty() && rvExtra.isNotBlank()) {
            // Judul pill/shade generik; detail stage tetap dari korpus RV via stage/ETA di bawah.
            title = context.getString(R.string.type_delivery)
            text = rvExtra.take(160)
        }
        // Stage driver-resto-tujuan dari title+text extras (+RV Grab bila ada,
        // sumber kebenaran: RemoteViewsExtractor).
        val stageCorpus = "$title $text $rvExtra"
        val stage = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryStage(stageCorpus)
        // Ingat ETA per order: stage baru tanpa waktu pakai ETA terakhir order yang sama,
        // sampai ada waktu baru (ganti) atau stage selesai (hapus).
        val liveId = extras.getString("extra_live_activity_id").orEmpty()
        var orderKey = liveId.ifEmpty {
            // Repost tanpa liveId tetap nempel ke order terakhir dari pkg ini.
            lastOrderByPkg[sbn.packageName].orEmpty()
        }
        if (liveId.isNotEmpty()) lastOrderByPkg[sbn.packageName] = liveId
        // Grab tak punya liveId — satu live-activity aktif per pkg, kunci per pkg.
        if (orderKey.isEmpty() && sbn.packageName == "com.grabtaxi.passenger") {
            orderKey = "grab:${sbn.packageName}"
        }
        if (orderKey.isNotEmpty() && isFinishedStage(stage, stageCorpus)) {
            lastEtaByOrder.remove(orderKey)
        }
        if (eta.isNotEmpty() && orderKey.isNotEmpty()) {
            lastEtaByOrder[orderKey] = eta
        }
        val shownEta = eta.ifEmpty { orderKey.ifEmpty { null }?.let { lastEtaByOrder[it] }.orEmpty() }
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-ETA pkg=${sbn.packageName} eta='$eta' shown='$shownEta' stage=${stage ?: "-"} title='$title' text='$text'"
        )
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = max > 0
        val percent = if (hasProgress) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0

        // Persen garis dari sub-stage (sumber kebenaran: RemoteViewsExtractor).
        val progressPercent = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryPercent(stage, stageCorpus)
        // Pill pendek: ticker = ETA ringkas ("14mnt"), fallback judul bila ETA kosong.
        // ETA kosong = ETA terakhir order yang sama (sampai ada waktu baru / stage selesai).
        // Judul+teks lengkap tetap tampil di shade via setBaseInfo di bawah.
        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", shownEta.replace(" menit", "mnt").ifEmpty { title })
        builder.setEnableFloat(config.isFloat ?: false)
        builder.setShowNotification(config.isShowShade ?: true)
        builder.setIslandFirstFloat(config.isFloat ?: false)

        // 3. Pictures: logo resto ASLI per order bila ada (Grab Transaction largeIcon
        // 98x98 logo Burjo dst; Shopee largeIcon), fallback logo ShopeeFood hardcode.
        // Nol inflate — largeIcon sudah tersedia di extras.
        // GRAB LIVE-ACTIVITY: tak bawa largeIcon SAMA SEKALI (null di dump) — pakai
        // IKON APP RESMI Grab dari PackageManager (bukan logo Shopee!), cache per proses.
        // Shopee tak bawa largeIcon -> grabAppIcon() null -> tetap logo ShopeeFood hardcode.
        // Grab live-activity selalu null -> ikon app Grab resmi. Grab Transaction bawa
        // largeIcon logo resto -> logo resto asli (kiri pill + cover shade).
        val logoKey = "${picKey}_logo"
        val isGrab = sbn.packageName == "com.grabtaxi.passenger"
        val orderLogo = sbn.notification.getLargeIcon()?.let { loadIconBitmap(it, sbn.packageName) }
            ?: (if (isGrab) grabAppIcon() else null)
        if (orderLogo != null) {
            builder.addPicture(HyperPicture(logoKey, orderLogo))
        } else {
            builder.addPicture(squarePicture(logoKey, R.drawable.delivery_logo_food))
        }
        val isRealClonePost = extras.getBoolean(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_CLONE, false)
        val coverKey = if (isRealClonePost) {
            val testBanner = try {
                com.d4viddf.hyperbridge.util.TestNotificationHelper.loadTestBanner(context)
            } catch (_: Exception) { null }
            if (testBanner != null) {
                builder.addPicture(HyperPicture(picKey, testBanner))
                picKey
            } else logoKey
        } else {
            logoKey
        }
        builder.addPicture(getTransparentPicture("hidden_pixel"))

        // 4. Actions (up to 3)
        val rawActions = sbn.notification.actions ?: emptyArray()
        val actionKeys = mutableListOf<String>()
        rawActions.take(3).forEachIndexed { index, action ->
            val uniqueKey = "delivery_act_${sbn.key.hashCode()}_$index"
            val hyperAction = HyperAction(
                key = uniqueKey,
                title = action.title?.toString() ?: "",
                icon = null,
                pendingIntent = action.actionIntent,
                actionIntentType = 1,
                actionBgColor = null,
                titleColor = "#FFFFFF"
            )
            builder.addAction(hyperAction)
            actionKeys.add(uniqueKey)
        }
        // 5. Shade Layout (Standard Template)
        builder.setBaseInfo(
            type = 1,
            title = title,
            content = text,
            pictureKey = coverKey,
            actionKeys = actionKeys
        )
        // 6. Cover dihapus: penyebab long-pill di island. Shade tetap informatif via setBaseInfo di atas.
        // 6b. Progress oranye: setStepProgress (bila sistem render) + progress bar
        // gaya taksi/delivery dengan IKON ASLI pengirim (driver bergerak di garis).
        // Percent dari stage: 1->33, 2->66, 3->100 (penuh seperti ori saat tiba).
        if (stage != null) {
            builder.setStepProgress(stage, 3, themeColor)
            // Ikon garis hardcode (drawable hasil dump, stabil antar order) — nol inflate.
            builder.addPicture(squarePicture("delivery_prog_driver", R.drawable.delivery_icon_driver))
            builder.addPicture(squarePicture("delivery_prog_stage", R.drawable.delivery_icon_stage))
            builder.addPicture(squarePicture("delivery_prog_destination", R.drawable.delivery_icon_pin))
            builder.setProgressBar(
                progress = progressPercent ?: ((stage * 100) / 3),
                color = themeColor,
                picForwardKey = "delivery_prog_driver",
                picMiddleKey = "delivery_prog_stage",
                picEndKey = "delivery_prog_destination"
            )
        } else if (hasProgress) {
            builder.setProgressBar(
                progress = percent,
                color = themeColor,
                picForwardKey = coverKey,
                picEndKey = "hidden_pixel"
            )
        }
        // 7. Island SESAK-ASSET: kiri logo + judul stage, kanan ETA ringkas,
        // + lingkaran progres stage di kanan (pola resmi kit: Template 7 Upload).
        // Pill tetap pendek (tanpa teks panjang); shade tetap lengkap via setBaseInfo.
        // eta dari extractEtaFromCorpus selalu format "N menit" -> padatkan jadi "Nmnt".
        // Kosong = pinjam ETA terakhir order yang sama (shownEta).
        val etaShort = shownEta.replace(" menit", "mnt")
        val islandPct = progressPercent ?: percent.takeIf { hasProgress } ?: ((stage ?: 0) * 100 / 3)
        // Judul stage ringkas buat kiri pill (tanpa teks panjang resto).
        // Grab tak bawa judul ("Food & Delivery" generik) — pakai kanan generik juga.
        val isGrabGeneric = sbn.packageName == "com.grabtaxi.passenger" && title == context.getString(R.string.type_delivery)
        val stageTitle = if (isGrabGeneric) "" else title.take(24)
        builder.addPicture(squarePicture("delivery_mini_motor", R.drawable.delivery_icon_driver))
        // Kiri pill = logo resto asli bila ada (orderLogo), sonst motor.
        // Kanan = pin tujuan; lingkaran progres nempel di kiri (pola kit Template 7).
        val leftPicKey = if (orderLogo != null) logoKey else "delivery_mini_motor"
        // Aset garis 3-ikon didaftarkan ulang sebagai aset island (sudah ada di shade).
        builder.addPicture(squarePicture("delivery_island_stage", R.drawable.delivery_icon_stage))
        builder.addPicture(squarePicture("delivery_island_pin", R.drawable.delivery_icon_pin))
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(
                type = 1,
                picInfo = PicInfo(type = 1, pic = leftPicKey),
                textInfo = TextInfo(stageTitle, etaShort.ifEmpty { null })
            ),
            right = ImageTextInfoRight(
                type = 2,
                picInfo = PicInfo(type = 1, pic = "delivery_island_pin"),
                textInfo = TextInfo(etaShort, null)
            ),
            progressText = ProgressTextInfo(
                progressInfo = CircularProgressInfo(
                    progress = islandPct.coerceIn(0, 100),
                    colorReach = themeColor,
                    isCCW = true
                ),
                textInfo = null
            )
        )
        builder.setSmallIslandCircularProgress(leftPicKey, islandPct.coerceIn(0, 100), themeColor, isCCW = true)
        builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = config.floatTimeout)
        builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }
}
