package com.d4viddf.hyperbridge.util

import android.widget.RemoteViews
import android.util.Log

/**
 * Teks + stage/percent delivery dari extras (jalur produksi 100% extras, nol inflate).
 * extractTexts/BestTitleText tersisa sebagai fallback generik saat extras kosong
 * (bukan Shopee live) + dipakai deteksi. Ekstraksi via reflection mActions.
 */
object RemoteViewsExtractor {

    fun extractTexts(remoteViews: RemoteViews?, debugLogging: Boolean = true): List<String> {
        if (remoteViews == null) return emptyList()
        return try {
            val field = RemoteViews::class.java.getDeclaredField("mActions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val actions = field.get(remoteViews) as? ArrayList<*> ?: return emptyList()
            val out = mutableListOf<String>()
            for (action in actions) {
                if (action == null) continue
                val cls = action.javaClass
                // ReflectionAction is inner class
                if (!cls.simpleName.contains("ReflectionAction")) continue
                try {
                    val methodNameField = cls.getDeclaredField("methodName")
                    methodNameField.isAccessible = true
                    val methodName = methodNameField.get(action) as? String ?: continue
                    if (methodName != "setText" && methodName != "setCharSequence") continue

                    val valueField = cls.getDeclaredField("value")
                    valueField.isAccessible = true
                    val value = valueField.get(action)
                    val str = when (value) {
                        is CharSequence -> value.toString().trim()
                        is String -> value.trim()
                        else -> null
                    }
                    if (!str.isNullOrEmpty() && str.length >= 2) {
                        // filter noise like single chars
                        out.add(str.replace("\n", " ").trim())
                    }
                } catch (_: Exception) {}
            }
            // dedupe preserving order, filter promo noise? keep all eligible
            out.distinct().filter { it.length in 2..200 }
        } catch (e: Exception) {
            if (debugLogging) Log.w(TAG, "extract failed", e)
            emptyList()
        }
    }

    fun extractBestTitleText(remoteViews: RemoteViews?, bigRemoteViews: RemoteViews?, debugLogging: Boolean = true): Pair<String?, String?> {
        val all = mutableListOf<String>()
        all.addAll(extractTexts(remoteViews, debugLogging))
        all.addAll(extractTexts(bigRemoteViews, debugLogging))
        if (all.isEmpty()) return null to null
        // Shopee custom view biasanya: [0]=title (header status), [1]=content, [2]=extra.
        // Title WAJIB ambil urutan pertama — min-length malah nyomot label timeline
        // ("Driver sedang menuju resto") padahal header-nya ("Resto sedang menyiapkan pesananmu").
        val filtered = all.filterNot { it.equals("null", true) }
        if (filtered.isEmpty()) return null to null
        // Title = elemen pertama (header), Text = string terpanjang yang beda dari title
        val title = filtered.firstOrNull()?.takeIf { it.length >= 3 }
        val text = filtered.filterNot { it == title }.maxByOrNull { it.length }?.takeIf { it.length >= 4 }
            ?: filtered.getOrNull(1)
        // jika title == text, split
        return if (title == text && filtered.size >= 2) {
            filtered[0] to filtered[1]
        } else {
            title to text
        }
    }




    /**
     * Stage driver-resto-tujuan dari keyword status (tahap tertinggi menang).
     * "tiba pada HH:MM"/"estimasi tiba"/"tiba dalam" = ESTIMASI, bukan tiba.
     * Sumber kebenaran tunggal — dipakai translator dan test.
     */
    fun deliveryStage(corpusRaw: String): Int? {
        val corpus = corpusRaw.lowercase()
        val etaEstimate = Regex("tiba\\s+pada\\s+\\d{1,2}[.:]\\d{2}|estimasi\\s+tiba|tiba\\s+dalam").containsMatchIn(corpus)
        return when {
            corpus.contains("selamat menikmati") || corpus.contains("sudah tiba") ||
                corpus.contains("telah tiba") || corpus.contains("selesai") ||
                (corpus.contains("tiba") && !corpus.contains("hampir tiba") && !etaEstimate) -> 3
            corpus.contains("hampir tiba") || corpus.contains("menuju") ||
                corpus.contains("diantar") || corpus.contains("dalam perjalanan") -> 2
            corpus.contains("disiapkan") || corpus.contains("menyiapkan") ||
                corpus.contains("diproses") -> 1
            else -> null
        }
    }

    /** Persen garis dari sub-stage: menuju resto masih awal (2/5), tiba = penuh. */
    fun deliveryPercent(stage: Int?, corpusRaw: String): Int? {
        if (stage == null) return null
        val lowerAll = corpusRaw.lowercase()
        return when {
            lowerAll.contains("selamat menikmati") || lowerAll.contains("sudah tiba") ||
                lowerAll.contains("telah tiba") || lowerAll.contains("selesai") -> 100
            lowerAll.contains("hampir tiba") || lowerAll.contains("menuju lokasi") ||
                lowerAll.contains("diantar") || lowerAll.contains("dalam perjalanan") -> 70
            lowerAll.contains("menuju resto") || lowerAll.contains("menuju ke resto") ||
                lowerAll.contains("menuju") -> 35
            lowerAll.contains("disiapkan") || lowerAll.contains("menyiapkan") ||
                lowerAll.contains("diproses") -> 15
            else -> (stage * 100) / 3
        }
    }

    /**
     * Meta level notifikasi (di luar RemoteViews): flags, category, group,
     * timeout, actions, channel, visibility. Best-effort, tidak pernah throw.
     */
    fun dumpNotificationMeta(sbn: android.service.notification.StatusBarNotification): String {
        return try {
            val n = sbn.notification
            val acts = (n.actions ?: emptyArray()).joinToString("|") {
                "${it.title}"
            }
            "key=${sbn.key} id=${sbn.id} flags=${n.flags} cat=${n.category} " +
                "group=${n.group} sortKey=${n.sortKey} timeout=${n.timeoutAfter} " +
                "ongoing=${0 != (n.flags and android.app.Notification.FLAG_ONGOING_EVENT)} " +
                "autoCancel=${0 != (n.flags and android.app.Notification.FLAG_AUTO_CANCEL)} " +
                "vis=${n.visibility} badgeIcon=${n.badgeIconType} actions=[$acts] " +
                "when=${n.`when`} number=${n.number}"
        } catch (_: Exception) { "meta-unavailable" }
    }

}
