package com.d4viddf.hyperbridge.util

import android.service.notification.StatusBarNotification
import android.widget.RemoteViews

/**
 * Stage/percent delivery dari teks extras (jalur produksi 100% extras, nol contentView).
 * Fallback RV untuk ETA: reflection mActions tanpa inflate — 1-3ms, tidak delay pill.
 */
object RemoteViewsExtractor {

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

    // ========================================================================
    //  REMOTEVIEWS FALLBACK (tanpa inflate) — dipakai setelah pill muncul
    //  agar pill tetap instan (0ms block). Reflection mActions = 1-3ms.
    // ========================================================================

    /** Ambil semua teks dari RemoteViews — coba reflection, fallback inflate (async, tetap 0ms pill). */
    fun extractRemoteViewsCorpus(sbn: StatusBarNotification): String? {
        return extractRemoteViewsCorpusInternal(sbn, null)
    }

    fun extractRemoteViewsCorpusWithContext(context: android.content.Context, sbn: StatusBarNotification): String? {
        return extractRemoteViewsCorpusInternal(sbn, context)
    }

    private fun extractRemoteViewsCorpusInternal(sbn: StatusBarNotification, context: android.content.Context?): String? {
        return try {
            val n = sbn.notification
            val candidates = listOfNotNull(n.bigContentView, n.contentView, n.headsUpContentView)
            if (candidates.isEmpty()) {
                android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT no RemoteViews for ${sbn.key}")
                return null
            }
            val sb = StringBuilder()
            for (rv in candidates) {
                var t: String? = null
                // 1. reflection mActions (cepat)
                t = extractTextFromRemoteViews(rv)
                if (t.isNullOrBlank() && context != null) {
                    // 2. fallback inflate — butuh context, dijalankan async setelah pill
                    t = extractTextViaInflate(context, rv)
                }
                if (!t.isNullOrBlank()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(t)
                }
            }
            val out = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (out.isEmpty()) {
                android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT empty corpus key=${sbn.key} rvCount=${candidates.size}")
                null
            } else out.take(2000)
        } catch (e: Exception) {
            android.util.Log.w("HyperBridgeDebug", "RV-EXTRACT exception ${e.message}")
            null
        }
    }

    private fun extractTextFromRemoteViews(rv: RemoteViews): String? {
        return try {
            // Hidden API di Android 13+ bisa block getDeclaredField — log biar tau
            val actionsField = try {
                RemoteViews::class.java.getDeclaredField("mActions")
            } catch (e: Exception) {
                android.util.Log.w("HyperBridgeDebug", "RV-REFLECT no mActions field ${e.message}")
                return null
            }
            actionsField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val actions = actionsField.get(rv) as? ArrayList<*> ?: run {
                android.util.Log.w("HyperBridgeDebug", "RV-REFLECT mActions null/empty")
                return null
            }
            if (actions.isEmpty()) {
                android.util.Log.w("HyperBridgeDebug", "RV-REFLECT actions empty")
                return null
            }
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
                android.util.Log.w("HyperBridgeDebug", "RV-REFLECT corpus empty actions=${actions.size}")
                null
            } else res
        } catch (e: Exception) {
            android.util.Log.w("HyperBridgeDebug", "RV-REFLECT exception ${e.message}")
            null
        }
    }

    private fun extractTextViaInflate(context: android.content.Context, rv: RemoteViews): String? {
        return try {
            val t0 = android.os.SystemClock.elapsedRealtime()
            // Inflate butuh parent — FrameLayout dummy. Harus di main thread untuk layout? coba langsung, fallback ke Handler jika fail.
            val parent = android.widget.FrameLayout(context)
            val view = try {
                rv.apply(context, parent)
            } catch (e: Exception) {
                android.util.Log.w("HyperBridgeDebug", "RV-INFLATE apply fail ${e.message}")
                return null
            }
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
            android.util.Log.w("HyperBridgeDebug", "RV-INFLATE ok dt=${dt}ms corpus='${out.take(180)}'")
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            android.util.Log.w("HyperBridgeDebug", "RV-INFLATE exception ${e.message}")
            null
        }
    }

    /** Regex ETA yang sama dengan DeliveryTranslator — dipakai untuk RV corpus. */
    private val etaRegexFallback = Regex(
        "\\d{1,2}[.:]\\d{2}\\s*-\\s*\\d{1,2}[.:]\\d{2}|tiba pada\\s+\\d{1,2}[.:]\\d{2}|\\d{1,2}:\\d{2}\\s*[–-]\\s*\\d{1,2}:\\d{2}|\\b\\d{1,2}:\\d{2}\\b|\\b\\d+\\s*menit\\b|\\b\\d+\\s*m\\b",
        RegexOption.IGNORE_CASE
    )

    fun extractEtaFromCorpus(corpus: String): String? {
        val all = etaRegexFallback.findAll(corpus).map { it.value }.toList()
        return all.firstOrNull { it.any(Char::isLetter) } ?: all.firstOrNull()
    }

    /** Convenience: langsung extract ETA dari RemoteViews sbn */
    fun extractEtaFromRemoteViews(sbn: StatusBarNotification): String? {
        val corpus = extractRemoteViewsCorpus(sbn) ?: return null
        return extractEtaFromCorpus(corpus)
    }
}
