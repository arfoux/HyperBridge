package com.d4viddf.hyperbridge.util

import android.service.notification.StatusBarNotification
import android.widget.RemoteViews
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stage/percent delivery dari teks extras (jalur produksi 100% extras, nol contentView).
 * Fallback RV untuk ETA: reflection mActions tanpa inflate — 1-3ms, tidak delay pill.
 */
object RemoteViewsExtractor {

    /**
     * Stage driver-resto-tujuan dari keyword status (tahap tertinggi menang).
     * "tiba pada HH:MM"/"estimasi tiba"/"tiba dalam" = ESTIMASI, bukan tiba.
     * Mendukung ID (Shopee) + EN (Grab: "In the kitchen", "is here", "on the way").
     * Sumber kebenaran tunggal — dipakai translator dan test.
     */
    fun deliveryStage(corpusRaw: String): Int? {
        val corpus = corpusRaw.lowercase()
        val etaEstimate = Regex("tiba\\s+pada\\s+\\d{1,2}[.:]\\d{2}|estimasi\\s+tiba|tiba\\s+dalam|arriving\\s+in|arriving\\s+at|eta\\s+\\d").containsMatchIn(corpus)
        return when {
            corpus.contains("selamat menikmati") || corpus.contains("sudah tiba") ||
                corpus.contains("telah tiba") || corpus.contains("selesai") ||
                corpus.contains("is here") || corpus.contains("delivered") ||
                corpus.contains("order complete") || corpus.contains("enjoy your meal") ||
                (corpus.contains("tiba") && !corpus.contains("hampir tiba") && !etaEstimate) -> 3
            corpus.contains("hampir tiba") || corpus.contains("menuju") ||
                corpus.contains("diantar") || corpus.contains("dalam perjalanan") ||
                corpus.contains("on the way") || corpus.contains("on its way") ||
                corpus.contains("arriving") || corpus.contains("picked up") ||
                corpus.contains("heading to") || corpus.contains("heading your way") -> 2
            corpus.contains("disiapkan") || corpus.contains("menyiapkan") ||
                corpus.contains("diproses") || corpus.contains("in the kitchen") ||
                (corpus.contains("mencari") && corpus.contains("driver")) ||
                corpus.contains("finding driver") ||
                corpus.contains("preparing your order") || corpus.contains("preparing") -> 1
            else -> null
        }
    }

    /**
     * Order selesai (bukan tiba-di-tujuan): pill harus di-dismiss, bukan di-post/update.
     * "tiba" (sudah/telah tiba, Grab "is here") = masih tampil 100% penuh;
     * "selesai"/"selamat menikmati"/"delivered" = order done -> dismiss agar tidak
     * nangkring + anti double-pill (key lama stage jalan + key baru stage selesai).
     * Sumber kebenaran tunggal — dipakai service sebelum post.
     */
    fun isDeliveryFinishedStrong(corpusRaw: String): Boolean {
        val c = corpusRaw.lowercase()
        return c.contains("selamat menikmati") ||
            c.contains("pastikan pesananmu sudah sesuai") ||
            c.contains("pesanan selesai") || c.contains("order selesai") ||
            c.contains("pesanan telah selesai") || c.contains("order telah selesai") ||
            c.contains("sudah diterima") || c.contains("pesanan diterima") ||
            c.contains("beri penilaian") || c.contains("berikan penilaian") ||
            c.contains("nilai pesanan") || c.contains("kasih rating") ||
            c.contains("enjoy your meal") || c.contains("enjoy your order") ||
            c.contains("did you enjoy your order") || c.contains("hear your feedback") ||
            c.contains("your feedback") ||
            c.contains("verdict") || c.contains("tell us what you think") ||
            c.contains("how would you rate") ||
            c.contains("delivered") ||
            c.contains("order complete") || c.contains("order completed") ||
            c.contains("rate your") || c.contains("how was your")
    }

    /** Varian longgar khusus korpus DELIVERY (tambah "selesai"/"completed" generik). */
    fun isDeliveryFinished(corpusRaw: String): Boolean {
        if (isDeliveryFinishedStrong(corpusRaw)) return true
        val c = corpusRaw.lowercase()
        return c.contains("selesai") || c.contains("completed")
    }

    /** Persen garis dari sub-stage: menuju resto masih awal (2/5), tiba = penuh. */
    fun deliveryPercent(stage: Int?, corpusRaw: String): Int? {
        if (stage == null) return null
        val lowerAll = corpusRaw.lowercase()
        return when {
            lowerAll.contains("selamat menikmati") || lowerAll.contains("sudah tiba") ||
                lowerAll.contains("telah tiba") || lowerAll.contains("selesai") ||
                lowerAll.contains("is here") || lowerAll.contains("delivered") -> 100
            // Awal order (cari driver) — persen kecil, pill muncul sejak awal.
            (lowerAll.contains("mencari") && lowerAll.contains("driver")) ||
                lowerAll.contains("finding driver") -> 10
            lowerAll.contains("hampir tiba") || lowerAll.contains("menuju lokasi") ||
                lowerAll.contains("diantar") || lowerAll.contains("dalam perjalanan") ||
                lowerAll.contains("on the way") || lowerAll.contains("on its way") ||
                lowerAll.contains("arriving") || lowerAll.contains("picked up") -> 70
            lowerAll.contains("menuju resto") || lowerAll.contains("menuju ke resto") ||
                lowerAll.contains("menuju") -> 35
            lowerAll.contains("disiapkan") || lowerAll.contains("menyiapkan") ||
                lowerAll.contains("diproses") || lowerAll.contains("in the kitchen") ||
                lowerAll.contains("preparing") -> 15
            else -> (stage * 100) / 3
        }
    }

    // ========================================================================
    //  REMOTEVIEWS FALLBACK (tanpa inflate) — dipakai setelah pill muncul
    //  agar pill tetap instan (0ms block). Reflection mActions = 1-3ms.
    // ========================================================================

    /** Ambil semua teks dari RemoteViews — coba reflection, fallback inflate (async, tetap 0ms pill). */
    fun extractRemoteViewsCorpus(sbn: StatusBarNotification, debug: Boolean = false): String? {
        return extractRemoteViewsCorpusInternal(sbn, null, debug)
    }

    fun extractRemoteViewsCorpusWithContext(context: android.content.Context, sbn: StatusBarNotification, debug: Boolean = false): String? {
        return extractRemoteViewsCorpusInternal(sbn, context, debug)
    }

    private fun extractRemoteViewsCorpusInternal(sbn: StatusBarNotification, context: android.content.Context?, debug: Boolean = false): String? {
        return try {
            val n = sbn.notification
            val candidates = listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView)
            if (candidates.isEmpty()) {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT no RemoteViews for ${sbn.key}")
                return null
            }
            val sb = StringBuilder()
            for ((idx, rv) in candidates.withIndex()) {
                var t: String? = null
                // 1. reflection mActions (cepat)
                t = extractTextFromRemoteViews(rv, debug)
                val reflectBlank = t.isNullOrBlank()
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-CANDIDATE idx=$idx key=${sbn.key} hasContext=${context != null} reflectBlank=$reflectBlank")
                if (reflectBlank && context != null) {
                    // 2. fallback inflate — butuh context, dijalankan async setelah pill
                    t = extractTextViaInflate(context, rv, debug)
                }
                if (!t.isNullOrBlank()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(t)
                }
            }
            val out = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (out.isEmpty()) {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT empty corpus key=${sbn.key} rvCount=${candidates.size}")
                null
            } else out.take(2000)
        } catch (e: Exception) {
            if (debug) android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT exception ${e.message}")
            null
        }
    }

    private fun extractTextFromRemoteViews(rv: RemoteViews, debug: Boolean = false): String? {
        return try {
            // Hidden API di Android 13+ bisa block getDeclaredField — log biar tau
            val actionsField = try {
                RemoteViews::class.java.getDeclaredField("mActions")
            } catch (e: Exception) {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-REFLECT no mActions field ${e.message}")
                return null
            }
            actionsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val actions = actionsField.get(rv) as? ArrayList<*> ?: run {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-REFLECT mActions null/empty")
                return null
            }
            if (actions.isEmpty()) {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-REFLECT actions empty")
                return null
            }
            // Dump struktur action sekali — biar tau methodName/field apa yang dipakai pengirim (mis. Shopee customView)
            if (debug) try {
                val seen = actions.take(40).mapNotNull { a ->
                    if (a == null) return@mapNotNull null
                    try {
                        val c = a.javaClass
                        val mf = runCatching { c.getDeclaredField("methodName") }.getOrNull()
                            ?: runCatching { c.superclass?.getDeclaredField("methodName") }.getOrNull()
                        mf?.let { it.isAccessible = true; "${c.simpleName}:${it.get(a)}" } ?: c.simpleName
                    } catch (_: Exception) { a.javaClass.simpleName }
                }.groupingBy { it }.eachCount()
                val sampleFields = try {
                    val a0 = actions.firstOrNull { it != null }
                    if (a0 == null) "" else {
                        val c0 = (a0 as Any).javaClass
                        val all = mutableListOf<java.lang.reflect.Field>()
                        all.addAll(c0.declaredFields.toList())
                        (a0 as Any).javaClass.superclass?.declaredFields?.let { all.addAll(it.toList()) }
                        " sample[${c0.simpleName}]=" + all.joinToString(",") { "${it.name}:${it.type.simpleName}" }.take(300)
                    }
                } catch (_: Exception) { "" }
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-ACTIONS n=${actions.size} methods=$seen$sampleFields")
            } catch (_: Exception) {}
            val sb = StringBuilder()
            for (action in actions) {
                if (action == null) continue
                try {
                    val clazz = action.javaClass
                    val methodNameField = runCatching { clazz.getDeclaredField("methodName") }.getOrNull()
                        ?: runCatching { clazz.superclass?.getDeclaredField("methodName") }.getOrNull() ?: continue
                    methodNameField.isAccessible = true
                    val methodName = methodNameField.get(action) as? String ?: continue
                    if (methodName != "setText" && methodName != "setTextViewText" && methodName != "setChronometer" && !methodName.contains("Text", true)) continue
                    var value: CharSequence? = null
                    val candidateFields = mutableListOf<java.lang.reflect.Field>()
                    candidateFields.addAll(clazz.declaredFields.toList())
                    clazz.superclass?.declaredFields?.let { candidateFields.addAll(it.toList()) }
                    for (f in candidateFields) {
                        if (f.name == "methodName" || f.name == "viewId" || f.name == "type" || f.name == "mViewId") continue
                        if (!CharSequence::class.java.isAssignableFrom(f.type) && f.type != String::class.java && f.type != Any::class.java) continue
                        f.isAccessible = true
                        val v = f.get(action) as? CharSequence ?: continue
                        val s = v.toString().trim()
                        if (s.isEmpty() || s.length > 300) continue
                        if (s == methodName) continue
                        // filter package name yang keikut (jarang)
                        if (s.matches(Regex("[a-z]+\\.[a-z.]+"))) continue
                        value = v
                        break
                    }
                    if (value != null) {
                        val s = value.toString().replace("\n", " ").trim()
                        if (s.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append(" ")
                            sb.append(s)
                        }
                    }
                } catch (_: Exception) { continue }
            }
            val res = sb.toString().trim()
            if (res.isEmpty()) {
                if (debug) android.util.Log.w("HyperBridgeDebug", "RV-REFLECT corpus empty actions=${actions.size}")
                null
            } else res
        } catch (e: Exception) {
            if (debug) android.util.Log.w("HyperBridgeDebug", "RV-REFLECT exception ${e.message}")
            null
        }
    }

    private fun extractTextViaInflate(context: android.content.Context, rv: RemoteViews, debug: Boolean = false): String? {
        return try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val onMain = Looper.getMainLooper().isCurrentThread
            if (debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE start thread=${Thread.currentThread().name} onMain=$onMain")
            // rv.apply wajib di main thread di sebagian ROM — lewat main Handler + latch bila dari worker.
            // Satu context (applicationContext) di semua cabang biar tema Drawable konsisten.
            val appCtx = context.applicationContext
            val view: android.view.View? = if (onMain) {
                try {
                    rv.apply(appCtx, android.widget.FrameLayout(appCtx))
                } catch (e: Exception) {
                    if (debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE apply fail ${e.message}")
                    null
                }
            } else {
                val latch = CountDownLatch(1)
                var res: android.view.View? = null
                var err: String? = null
                Handler(Looper.getMainLooper()).post {
                    try {
                        res = rv.apply(appCtx, android.widget.FrameLayout(appCtx))
                    } catch (e: Exception) {
                        err = e.message
                    } finally {
                        latch.countDown()
                    }
                }
                val done = try { latch.await(3, TimeUnit.SECONDS) } catch (_: Exception) { false }
                if (!done && debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE main-timeout")
                if (err != null && debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE apply fail $err")
                res
            }
            if (view == null) return null
            val sb = StringBuilder()
            fun traverse(v: android.view.View) {
                try {
                    if (v is android.widget.TextView) {
                        val t = v.text?.toString()?.trim()
                        if (!t.isNullOrEmpty() && t.length < 500) {
                            if (sb.isNotEmpty()) sb.append(" ")
                            sb.append(t.replace("\n", " ").trim())
                        }
                    } else if (v is android.view.ViewGroup) {
                        for (i in 0 until v.childCount) traverse(v.getChildAt(i))
                    }
                } catch (_: Exception) {}
            }
            traverse(view)
            // Bersihkan view agar tidak leak
            try { (view.parent as? android.view.ViewGroup)?.removeView(view) } catch (_: Exception) {}
            val dt = android.os.SystemClock.elapsedRealtime() - t0
            val out = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE ok dt=${dt}ms corpus='${out.take(180)}'")
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            if (debug) android.util.Log.w("HyperBridgeDebug", "RV-INFLATE exception ${e.message}")
            null
        }
    }

    /** Regex ETA tunggal — menit menang atas range jam. Dipakai semua jalur. */
    private val etaRegexFallback = Regex(
        "\\b\\d+\\s*menit\\b|\\b\\d+\\s*m\\b|tiba\\s+dalam\\s+\\d+|estimasi\\s+\\d+|\\d{1,2}[.:]\\d{2}\\s*-\\s*\\d{1,2}[.:]\\d{2}|tiba pada\\s+\\d{1,2}[.:]\\d{2}|\\d{1,2}:\\d{2}\\s*[–-]\\s*\\d{1,2}:\\d{2}|\\b\\d{1,2}:\\d{2}\\b",
        RegexOption.IGNORE_CASE
    )
    private val minuteRegex = Regex("\\b\\d+\\s*menit\\b|\\b\\d+\\s*m\\b|tiba\\s+dalam\\s+\\d+|estimasi\\s+\\d+", RegexOption.IGNORE_CASE)
    private val rangeRegex = Regex("\\d{1,2}[.:]\\d{2}\\s*-\\s*\\d{1,2}[.:]\\d{2}|\\d{1,2}:\\d{2}\\s*[–-]\\s*\\d{1,2}:\\d{2}", RegexOption.IGNORE_CASE)
    private val singleTimeRegex = Regex("\\d{1,2}[.:]\\d{2}")

    fun extractEtaFromCorpus(corpus: String): String? {
        val all = etaRegexFallback.findAll(corpus).map { it.value }.toList()
        if (all.isEmpty()) return null
        // 1. Menit tertulis menang.
        val minuteMatch = all.firstOrNull { it.matches(minuteRegex) }
        if (minuteMatch != null) {
            val n = Regex("\\d+").find(minuteMatch)?.value?.toIntOrNull()
            if (n != null) return "$n menit"
            return minuteMatch
        }
        // 2. Range jam ("07.25 - 07.40") -> durasi window ("15 menit").
        //    Durasi stabil & sesuai ekspektasi; sisa-ke-ujung menipu saat window masih jauh.
        val rangeMatch = all.firstOrNull { it.matches(rangeRegex) }
        if (rangeMatch != null) {
            val dur = parseTimeRangeDuration(rangeMatch)
            if (dur != null && dur in 1..180) return "$dur menit"
            return null
        }
        // 3. Jam tunggal ("Tiba pada 20:25" / "20:25") -> sisa menit bila masuk akal.
        val singleMatch = all.firstOrNull() ?: return null
        val t = singleTimeRegex.find(singleMatch)?.value?.let { parseTimePart(it.replace(".", ":")) }
        if (t != null) {
            val rem = minutesUntil(t)
            if (rem in 1..180) return "$rem menit"
        }
        return singleMatch
    }

    /** Menit sisa dari sekarang ke target (menit-of-day). Wrap midnight. */
    private fun minutesUntil(targetMinOfDay: Int): Int {
        return try {
            val cal = java.util.Calendar.getInstance()
            val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            var diff = targetMinOfDay - now
            if (diff <= 0) diff += 24 * 60
            diff
        } catch (_: Exception) { -1 }
    }

    private fun parseRangeEnd(range: String): Int? {
        val normalized = range.replace("–", "-").replace("—", "-")
        val parts = normalized.split("\\s*-\\s*".toRegex())
        if (parts.size != 2) return null
        return parseTimePart(parts[1].trim().replace(".", ":"))
    }

    /** Durasi range dalam menit (ujung - awal), mis. "08:52 - 09:02" -> 10. */
    private fun parseTimeRangeDuration(timeRange: String): Int? {
        val normalized = timeRange.replace("–", "-").replace("—", "-")
        val parts = normalized.split("\\s*-\\s*".toRegex())
        if (parts.size != 2) return null
        val start = parseTimePart(parts[0].trim().replace(".", ":"))
        val end = parseTimePart(parts[1].trim().replace(".", ":"))
        if (start == null || end == null) return null
        val diff = end - start
        return if (diff < 0) diff + 24 * 60 else diff
    }

    // Alias lama biar pemanggil lama tetap kompilasi.
    private fun parseTimeRangeToMinutes(timeRange: String): Int? = parseTimeRangeDuration(timeRange)

    private fun parseTimePart(timePart: String): Int? {
        val timeComponents = timePart.split(":")
        if (timeComponents.size != 2) return null
        val hours = timeComponents[0].toIntOrNull()
        val minutes = timeComponents[1].toIntOrNull()
        if (hours == null || minutes == null) return null
        if (hours !in 0..23 || minutes !in 0..59) return null
        return hours * 60 + minutes
    }

    /** Substring ETA mentah pertama ("07.25 - 07.40" / "Tiba pada 20:25" / "15 menit") — untuk big island. */
    fun extractEtaRaw(corpus: String): String? {
        return etaRegexFallback.find(corpus)?.value?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Convenience: langsung extract ETA dari RemoteViews sbn */
    fun extractEtaFromRemoteViews(sbn: StatusBarNotification): String? {
        val corpus = extractRemoteViewsCorpus(sbn) ?: return null
        return extractEtaFromCorpus(corpus)
    }
}
