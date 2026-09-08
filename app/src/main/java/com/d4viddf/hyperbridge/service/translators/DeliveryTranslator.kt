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
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class DeliveryTranslator(context: Context, repo: ThemeRepository) : BaseTranslator(context, repo) {

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

    private val preferences = AppPreferences(context)
    fun translate(
        sbn: StatusBarNotification,
        effectiveTitle: String,
        effectiveText: String,
        picKey: String,
        config: IslandConfig,
        theme: HyperTheme?
    ): HyperIslandData {

        // 1. Resolve Theme Colors
        val themeColor = resolveColor(theme, sbn.packageName, "#EE4D2D") // Shopee orange-ish default

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
        // ETA kanan: regex dari teks extras saja (teks utuh, tanpa join antar-TextView).
        // Utamakan match berhuruf ("Tiba pada 11:32", "32 menit") di atas jam telanjang.
        // Catatan: ETA yang cuma ada di RemoteViews ("Tiba pada ..." Shopee) memang tidak
        // ditampilkan — harga 100% extras. Kanan kosong (""), bukan teks ngaco.
        val etaRegex = Regex(
            "\\d{1,2}[.:]\\d{2}\\s*-\\s*\\d{1,2}[.:]\\d{2}|tiba pada\\s+\\d{1,2}[.:]\\d{2}|\\d{1,2}:\\d{2}\\s*[–-]\\s*\\d{1,2}:\\d{2}|\\b\\d{1,2}:\\d{2}\\b|\\b\\d+\\s*menit\\b|\\b\\d+\\s*m\\b",
            RegexOption.IGNORE_CASE
        )
        fun pickEta(s: String): String? {
            val all = etaRegex.findAll(s).map { it.value }.toList()
            return all.firstOrNull { it.any(Char::isLetter) } ?: all.firstOrNull()
        }
        val eta = pickEta(text)
            ?: pickEta(title)
            ?: ""
        // Stage driver-resto-tujuan dari title+text extras (sumber kebenaran: RemoteViewsExtractor).
        val stageCorpus = "$title $text"
        val stage = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryStage(stageCorpus)
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-ETA pkg=${sbn.packageName} eta='$eta' stage=${stage ?: "-"} title='$title' text='$text'"
        )
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = max > 0
        val percent = if (hasProgress) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0

        // Persen garis dari sub-stage (sumber kebenaran: RemoteViewsExtractor).
        val progressPercent = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.deliveryPercent(stage, stageCorpus)
        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", title)
        builder.setEnableFloat(config.isFloat ?: false)
        builder.setShowNotification(config.isShowShade ?: true)
        builder.setIslandFirstFloat(config.isFloat ?: false)

        // 3. Pictures: logo ShopeeFood hardcode (drawable hasil dump order asli, stabil
        // antar order) untuk small island + cover. Nol ekstrak RemoteViews.
        val logoKey = "${picKey}_logo"
        builder.addPicture(squarePicture(logoKey, R.drawable.delivery_logo_food))
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
        // 6a. Cover persegi: banner + resto + ETA (gambar tidak kepotong lingkaran)
        builder.setCoverInfo(coverKey, title, text, eta)
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
        // 7. Island Layout: kiri banner + resto, kanan ETA; small island tetap logo
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(
                type = 1,
                picInfo = PicInfo(type = 1, pic = coverKey),
                textInfo = TextInfo(title, text)
            ),
            right = ImageTextInfoRight(
                type = 2,
                picInfo = PicInfo(type = 1, pic = "hidden_pixel"),
                textInfo = TextInfo(eta, "")
            )
        )
        builder.setSmallIsland(coverKey)
        builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = config.floatTimeout)
        builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }
}
