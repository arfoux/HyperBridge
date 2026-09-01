package com.d4viddf.hyperbridge.service.translators

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.theme.ThemeRepository
import com.d4viddf.hyperbridge.models.HyperIslandData
import com.d4viddf.hyperbridge.models.IslandConfig
import com.d4viddf.hyperbridge.models.theme.HyperTheme
import io.github.d4viddf.hyperisland_kit.HyperAction
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

class DeliveryTranslator(context: Context, repo: ThemeRepository) : BaseTranslator(context, repo) {

    fun translate(
        sbn: StatusBarNotification,
        effectiveTitle: String,
        effectiveText: String,
        picKey: String,
        config: IslandConfig,
        theme: HyperTheme?,
        useRemoteViews: Boolean = false
    ): HyperIslandData {

        // 1. Resolve Theme Colors
        val themeColor = resolveColor(theme, sbn.packageName, "#EE4D2D") // Shopee orange-ish default

        // 2. Parse Notification Content
        val extras = sbn.notification.extras

        // [DEBUG] Dump raw payload so we can refine the mapping without guessing
        android.util.Log.d(
            "HyperBridgeDebug",
            "DELIVERY-PAYLOAD pkg=${sbn.packageName} " +
                "title='${extras.getCharSequence(Notification.EXTRA_TITLE)}' " +
                "text='${extras.getCharSequence(Notification.EXTRA_TEXT)}' " +
                "big='${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)}' " +
                "sub='${extras.getCharSequence(Notification.EXTRA_SUB_TEXT)}' " +
                "progress=${extras.getInt(Notification.EXTRA_PROGRESS, 0)}/" +
                "${extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)} " +
                "tpl='${extras.getString(Notification.EXTRA_TEMPLATE)}'"
        )
        val title = effectiveTitle.ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.replace("\n", " ")?.trim()
                ?: context.getString(R.string.type_delivery)
        }
        val text = effectiveText.ifEmpty {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.replace("\n", " ")?.trim() ?: ""
        }

        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val hasProgress = max > 0
        val percent = if (hasProgress) ((current.toFloat() / max.toFloat()) * 100).toInt() else 0

        val builder = HyperIslandNotification.Builder(context, "bridge_${sbn.packageName}", title)
        builder.setEnableFloat(config.isFloat ?: false)
        builder.setShowNotification(config.isShowShade ?: true)
        builder.setIslandFirstFloat(config.isFloat ?: false)

        // Opsi RemoteViews 1:1 — big island pakai view asli, small island custom kiri/kanan per stage
        if (useRemoteViews) {
            val rv: RemoteViews? = sbn.notification.bigContentView ?: sbn.notification.contentView
            if (rv != null) {
                builder.setCustomIslandExpandRemoteView(rv)
                // Small island custom: headerIcon (logo) | DKRIUK, kanan per stage, body gaada
                val tinyRv = RemoteViews(context.packageName, R.layout.layout_delivery_tiny)
                // Logo headerIcon persegi panjang 239×44 (ambil app icon Shopee, scale fitCenter)
                try {
                    val drawable = context.packageManager.getApplicationIcon(sbn.packageName)
                    val bmp = drawable.toBitmap()
                    tinyRv.setImageViewBitmap(R.id.tiny_logo, bmp)
                } catch (_: Exception) { }
                // Resto dinamis dari text "DKRIUK ... sedang menyiapkan" (headerText: DKRIUK KS TUBUN + split | ada di custom view, bukan hardcode)
                val resto = Regex("""^(.+?)\s+sedang menyiapkan""").find(text)?.groupValues?.get(1)?.trim().orEmpty()
                if (resto.isNotEmpty()) tinyRv.setTextViewText(R.id.tiny_resto, resto)
                val rightText = when {
                    title.contains("Resto sedang menyiapkan") -> {
                        val m = Regex("""(\d+)\s*menit""").find(text)?.groupValues?.get(1) ?: "32"
                        "Menyiapkan • ${m}m"
                    }
                    title.contains("Pesananmu sedang dalam perjalanan") -> "Diantar • 20m"
                    title.contains("Selamat menikmati") -> "Selesai"
                    else -> "Delivery"
                }
                tinyRv.setTextViewText(R.id.tiny_right, rightText)
                builder.setCustomTinyRemoteView(tinyRv)
                builder.addPicture(resolveIcon(sbn, picKey))
                builder.addPicture(getTransparentPicture("hidden_pixel"))
                builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = config.floatTimeout)
                builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)
                return HyperIslandData(builder.buildCustomExtras(), builder.buildJsonParam())
            }
        }

        // 3. Picture (app icon / large icon)
        builder.addPicture(resolveIcon(sbn, picKey))
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
            pictureKey = picKey,
            actionKeys = actionKeys
        )

        // 6. Progress Bar (if delivery sends progress)
        if (hasProgress) {
            builder.setProgressBar(
                progress = percent,
                color = themeColor,
                picForwardKey = picKey,
                picEndKey = "hidden_pixel"
            )
        }

        // 7. Island Layout
        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(
                type = 1,
                picInfo = PicInfo(type = 1, pic = picKey),
                textInfo = TextInfo(title, text)
            )
        )
        builder.setSmallIsland(picKey)
        builder.setIslandConfig(highlightColor = themeColor, expandedTimeMs = config.floatTimeout)
        builder.setHideDeco(true).setReopen(true).setShowSmallIcon(true)

        return HyperIslandData(builder.buildResourceBundle(), builder.buildJsonParam())
    }
}
