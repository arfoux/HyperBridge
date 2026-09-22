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

    /** Motor hijau Grab (hardcode dari APK Grab ic_grabnow_bike, 1:1 Shopee delivery_icon_driver).
     * Dipakai untuk SEMUA aset motor Grab: kiri pill, garis progres, small island.
     * Shopee tak tersentuh (tetap delivery_icon_driver oranye). */
    private fun grabBikePicture(key: String): HyperPicture {
        return squarePicture(key, R.drawable.grab_icon_bike)
    }

    private val preferences = AppPreferences(context)

    /** Jalur Grab 1:1 — paket Grab asli ATAU REAL-clone bertanda Grab (Test screen). */
    private fun isGrabPipeline(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == "com.grabtaxi.passenger") return true
        if (sbn.packageName != context.packageName) return false
        val ex = sbn.notification.extras
        return ex.getBoolean(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_CLONE, false) &&
            ex.getString(com.d4viddf.hyperbridge.util.TestNotificationHelper.EXTRA_REAL_PKG) == "com.grabtaxi.passenger"
    }
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
        val themeColor = if (isGrabPipeline(sbn)) "#00B14F"
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
        // ETA: olahan ("15 menit", range -> durasi) buat pill; MENTAH ("Tiba 07.25 - 07.40")
        // buat big island. Sumber tunggal: RemoteViewsExtractor (0ms, tanpa inflate di jalur pill).
        val etaCorpus = listOf(
            text,
            title,
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString().orEmpty(),
        )
        var eta = forcedEta?.takeIf { it.isNotBlank() } ?: ""
        if (eta.isEmpty()) {
            for (src in etaCorpus) {
                val e = runCatching { com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractEtaFromCorpus(src) }.getOrNull()?.takeIf(String::isNotBlank)
                if (e != null) {
                    eta = e
                    break
                }
            }
        }
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
        val keywordStage = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryStage(stageCorpus)
        // Nama resto (Grab EN) + label waktu mentah — dipakai shade (detail) agar
        // pill tetap minimal. Contoh REAL: "Your order from Burjo Titik Kumpul -
        // Tembalang is on the way to you."
        val restoShort = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractRestoName(stageCorpus)
            ?.substringBefore(" - ")?.trim()?.takeIf { it.isNotEmpty() }
        // Ori bawa angka progress (mis. garis 50%) tapi teks masih stage awal
        // ("In the kitchen"): stage pill = tertinggi keyword vs angka — pill tak ketinggalan.
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = max > 0
        val percent = if (hasProgress) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0
        val impliedStage = if (hasProgress) when {
            percent >= 67 -> 3
            percent >= 34 -> 2
            else -> 1
        } else null
        val stage = maxOf(keywordStage ?: 0, impliedStage ?: 0).takeIf { it > 0 }
        // Ingat ETA per order: stage baru tanpa waktu pakai ETA terakhir order yang sama,
        // sampai ada waktu baru (ganti) atau stage selesai (hapus).
        val liveId = extras.getString("extra_live_activity_id").orEmpty()
        var orderKey = liveId.ifEmpty {
            // Repost tanpa liveId tetap nempel ke order terakhir dari pkg ini.
            lastOrderByPkg[sbn.packageName].orEmpty()
        }
        if (liveId.isNotEmpty()) lastOrderByPkg[sbn.packageName] = liveId
        // Grab tak punya liveId — satu live-activity aktif per pkg, kunci per pkg.
        if (orderKey.isEmpty() && isGrabPipeline(sbn)) {
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

        // Persen garis: angka ori menang bila ada; fallback sub-stage keyword.
        val progressPercent = if (hasProgress) percent
            else com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryPercent(stage, stageCorpus)
        // Pill pendek: ticker = ETA ringkas ("14mnt"), fallback judul bila ETA kosong.
        // ETA kosong = ETA terakhir order yang sama (sampai ada waktu baru / stage selesai).
        // Judul+teks lengkap tetap tampil di shade via setBaseInfo di bawah.
        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", shownEta.replace(" menit", "mnt").ifEmpty { title })
        builder.setEnableFloat(config.isFloat ?: false)
        builder.setShowNotification(config.isShowShade ?: true)
        builder.setIslandFirstFloat(config.isFloat ?: false)

        // 3. Pictures: Grab SELALU motor hijau hardcode (tanpa load largeIcon —
        // hemat decode bitmap per update). Shopee: logo ShopeeFood hardcode
        // (largeIcon Shopee tak pernah ada di dump, jadi Shopee juga hardcode).
        val logoKey = "${picKey}_logo"
        val isGrab = isGrabPipeline(sbn)
        if (isGrab) {
            builder.addPicture(grabBikePicture(logoKey))
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
            // Grab: judul resto; Shopee: judul extras. Isi = teks apa adanya.
            title = if (isGrab) restoShort ?: title else title,
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
            // Grab pakai motor hijau sendiri (grab_icon_bike), Shopee motor oranye.
            val progDriverKey = "delivery_prog_driver"
            if (isGrab) {
                builder.addPicture(grabBikePicture(progDriverKey))
            } else {
                builder.addPicture(squarePicture(progDriverKey, R.drawable.delivery_icon_driver))
            }
            builder.addPicture(squarePicture("delivery_prog_stage", R.drawable.delivery_icon_stage))
            builder.addPicture(squarePicture("delivery_prog_destination", R.drawable.delivery_icon_pin))
            builder.setProgressBar(
                progress = progressPercent ?: ((stage * 100) / 3),
                color = themeColor,
                picForwardKey = progDriverKey,
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
        // 7. Belah 2 per aplikasi (spek final): kiri = logo + JUDUL PENUH +
        //    baris 2 kosong; kanan = ETA 1 baris TANPA pic; ring = progres +
        //    warna tema (oranye Shopee, hijau Grab).
        // eta dari extractEtaFromCorpus selalu format "N menit" -> padatkan jadi "Nmnt".
        // Kosong = pinjam ETA terakhir order yang sama (shownEta).
        val etaShort = shownEta.replace(" menit", "mnt")
        val islandPct = progressPercent ?: percent.takeIf { hasProgress } ?: ((stage ?: 0) * 100 / 3)
        val bigTitle = if (isGrab) restoShort ?: title else title
        // Kiri = logo motor per aplikasi + judul penuh, baris 2 kosong.
        // Kanan = ETA 1 baris tanpa pic. Ring nempel di kiri.
        val leftPicKey = if (isGrab) "delivery_mini_bike" else "delivery_mini_motor"
        if (isGrab) {
            builder.addPicture(grabBikePicture("delivery_mini_bike"))
        } else {
            builder.addPicture(squarePicture("delivery_mini_motor", R.drawable.delivery_icon_driver))
        }
        // Aset garis 3-ikon didaftarkan ulang sebagai aset island (sudah ada di shade).
        builder.addPicture(squarePicture("delivery_island_stage", R.drawable.delivery_icon_stage))
        builder.addPicture(squarePicture("delivery_island_pin", R.drawable.delivery_icon_pin))
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(
                type = 1,
                picInfo = PicInfo(type = 1, pic = leftPicKey),
                textInfo = TextInfo(bigTitle, "")
            ),
            right = ImageTextInfoRight(
                type = 2,
                textInfo = TextInfo(etaShort, "")
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
