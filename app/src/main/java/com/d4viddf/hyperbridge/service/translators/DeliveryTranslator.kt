package com.d4viddf.hyperbridge.service.translators

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
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
        // RV-FULL: kumpulkan SEMUA teks RemoteViews sekali, pakai ulang di bawah
        val debug = preferences.debugLoggingSync()
        val rvAll = (
            com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractTexts(sbn.notification.contentView, debug) +
                com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractTexts(sbn.notification.bigContentView, debug) +
                com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractTexts(sbn.notification.headsUpContentView, debug)
            ).distinct()
        // [DEBUG] RV-FULL berisi semua teks RemoteViews agar 100% data expand terbaca
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-RV-FULL pkg=${sbn.packageName} n=${rvAll.size} " +
                "texts=[${rvAll.joinToString(" | ")}]"
        )
        // [DEBUG] dump total hierarki + aksi (di-chunk): pemetaan menyusul setelah data ada
        if (debug) com.d4viddf.hyperbridge.util.RemoteViewsExtractor.dumpRemoteViewsFull(
            context,
            sbn.packageName,
            sbn.packageName,
            sbn.notification.contentView,
            sbn.notification.bigContentView,
            sbn.notification.headsUpContentView
        )
        // Fallback eligible: title -> bigTitle -> RemoteViews -> appLabel -> type_delivery
        var title = effectiveTitle.ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (title.isEmpty()) {
            title = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }
        if (title.isEmpty()) {
            try {
                val (rvTitle, _) = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractBestTitleText(sbn.notification.contentView, sbn.notification.bigContentView, debug)
                if (!rvTitle.isNullOrEmpty()) title = rvTitle
            } catch (_: Exception) {}
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
        if (text.isEmpty()) {
            try {
                val (_, rvText) = com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractBestTitleText(sbn.notification.contentView, sbn.notification.bigContentView, debug)
                if (!rvText.isNullOrEmpty()) text = rvText
                // jika masih kosong, ambil semua texts dari RemoteViews gabung
                if (text.isEmpty()) {
                    val distinct = rvAll.filterNot { it == title }
                    if (distinct.isNotEmpty()) text = distinct.joinToString(" • ").take(180)
                }
            } catch (_: Exception) {}
        }
        // ETA kanan: regex dari teks RemoteViews dulu, fallback teks biasa
        val etaRegex = Regex(
            "tiba pada\\s+\\d{1,2}:\\d{2}|\\d{1,2}:\\d{2}\\s*[–-]\\s*\\d{1,2}:\\d{2}|\\b\\d{1,2}:\\d{2}\\b|\\b\\d+\\s*menit\\b|\\b\\d+\\s*m\\b",
            RegexOption.IGNORE_CASE
        )
        val eta = etaRegex.find(rvAll.joinToString(" • "))?.value
            ?: etaRegex.find(text)?.value
            ?: etaRegex.find(title)?.value
            ?: text.ifEmpty { title }
        // Stage driver-resto-tujuan dari keyword status (tahap tertinggi menang).
        // Hati-hati: "tiba pada HH:MM" = ESTIMASI (bukan tiba beneran), "hampir tiba" = masih jalan.
        val stageCorpus = (title + " " + text + " " + rvAll.joinToString(" ")).lowercase()
        val etaEstimate = Regex("tiba\\s+pada\\s+\\d{1,2}:\\d{2}").containsMatchIn(stageCorpus)
        val stage = when {
            stageCorpus.contains("selamat menikmati") || stageCorpus.contains("sudah tiba") ||
                stageCorpus.contains("telah tiba") || stageCorpus.contains("selesai") ||
                (stageCorpus.contains("tiba") && !stageCorpus.contains("hampir tiba") && !etaEstimate) -> 3
            stageCorpus.contains("hampir tiba") || stageCorpus.contains("menuju") ||
                stageCorpus.contains("diantar") || stageCorpus.contains("dalam perjalanan") -> 2
            stageCorpus.contains("disiapkan") || stageCorpus.contains("menyiapkan") ||
                stageCorpus.contains("diproses") -> 1
            else -> null
        }
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-ETA pkg=${sbn.packageName} eta='$eta' stage=${stage ?: "-"}"
        )

        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = max > 0
        val percent = if (hasProgress) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0

        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", title)
        builder.setEnableFloat(config.isFloat ?: false)
        builder.setShowNotification(config.isShowShade ?: true)
        builder.setIslandFirstFloat(config.isFloat ?: false)

        // 3. Pictures: banner persegi untuk cover, logo untuk small island (tetap logo).
        val logoKey = "${picKey}_logo"
        builder.addPicture(resolveIcon(sbn, logoKey))
        val banner = try {
            com.d4viddf.hyperbridge.util.RemoteViewsExtractor.extractBannerBitmap(
                context,
                sbn.packageName,
                sbn.notification.contentView,
                sbn.notification.bigContentView,
                sbn.notification.headsUpContentView
            )
        } catch (_: Exception) { null }
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-BANNER pkg=${sbn.packageName} " +
                (if (banner != null) "found=${banner.width}x${banner.height}" else "null->logo")
        )
        if (debug) android.util.Log.w(
            "HyperBridgeDebug",
            "DELIVERY-BANNER-ACTIONS pkg=${sbn.packageName} " +
                com.d4viddf.hyperbridge.util.RemoteViewsExtractor.dumpImageActions(
                    context,
                    sbn.packageName,
                    sbn.notification.contentView,
                    sbn.notification.bigContentView,
                    sbn.notification.headsUpContentView
                )
        )
        // coverKey = banner bila ada, else logo (shade + big island kiri pakai ini)
        val coverKey = if (banner != null) {
            builder.addPicture(HyperPicture(picKey, banner))
            picKey
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
        // 6b. Progress oranye: 3-titik driver-resto-tujuan bila stage ketemu,
        // fallback progress bar bila notif kirim progress.
        if (stage != null) {
            builder.setStepProgress(stage, 3, themeColor)
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
                textInfo = TextInfo(eta, "")
            )
        )
        builder.setSmallIsland(coverKey)
        builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = config.floatTimeout)
        builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }
}
