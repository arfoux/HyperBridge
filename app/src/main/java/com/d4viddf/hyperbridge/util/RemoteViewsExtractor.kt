package com.d4viddf.hyperbridge.util

import android.widget.RemoteViews
import android.util.Log

/**
 * Fallback extractor untuk custom RemoteViews seperti ShopeeFood.
 * ShopeeFood menggunakan DecoratedCustomViewStyle dengan RemoteViews (0x7f0d040f)
 * di mana title/text di extras terbatas (26/58 char) tapi detail lengkap ada di RemoteViews.
 * Ekstraksi via reflection mActions -> ReflectionAction.setText
 */
object RemoteViewsExtractor {

    private const val TAG = "HyperBridgeRemote"

    fun extractTexts(remoteViews: RemoteViews?): List<String> {
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
            Log.w(TAG, "extract failed", e)
            emptyList()
        }
    }

    fun extractBestTitleText(remoteViews: RemoteViews?, bigRemoteViews: RemoteViews?): Pair<String?, String?> {
        val all = mutableListOf<String>()
        all.addAll(extractTexts(remoteViews))
        all.addAll(extractTexts(bigRemoteViews))
        if (all.isEmpty()) return null to null
        // Shopee custom view biasanya: [0]=title, [1]=content, [2]=extra
        // Fallback: ambil 2 string terpanjang / paling relevan yang tidak mengandung promo generic
        val filtered = all.filterNot { it.equals("null", true) }
        if (filtered.isEmpty()) return null to null
        // Title = shortest distinct that looks like header, Text = longest
        val title = filtered.minByOrNull { it.length }?.takeIf { it.length >= 3 }
        val text = filtered.maxByOrNull { it.length }?.takeIf { it.length >= 4 }
        // jika title == text, split
        return if (title == text && filtered.size >= 2) {
            filtered[0] to filtered[1]
        } else {
            title to text
        }
    }
}
