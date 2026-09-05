package com.d4viddf.hyperbridge.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fallback extractor untuk custom RemoteViews seperti ShopeeFood.
 * ShopeeFood menggunakan DecoratedCustomViewStyle dengan RemoteViews (0x7f0d040f)
 * di mana title/text di extras terbatas (26/58 char) tapi detail lengkap ada di RemoteViews.
 * Ekstraksi via reflection mActions -> ReflectionAction.setText
 */
object RemoteViewsExtractor {

    private const val TAG = "HyperBridgeRemote"

    /** Banner dianggap persegi panjang (landscape) bila w/h melebihi rasio ini. */
    private const val WIDE_ASPECT = 1.2f

    /**
     * Dump TOTAL RemoteViews untuk diagnosa 100%: semua mActions (method@view +
     * ringkasan value) + seluruh hierarki hasil inflate (kelas, nama id,
     * visibility, teks aktual, drawable, ukuran terukur). Di-chunk per ~3000
     * char agar muat batas baris logcat. Best-effort, tidak pernah throw.
     */
    fun dumpRemoteViewsFull(
        appContext: Context,
        packageName: String,
        pkg: String,
        vararg views: RemoteViews?
    ) {
        try {
            val senderCtx = try {
                appContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Exception) { null }
            fun resName(id: Int): String {
                if (id == 0 || senderCtx == null) return "id=$id"
                return try {
                    senderCtx.resources.getResourceEntryName(id)
                } catch (_: Exception) { "id=$id" }
            }
            val lines = mutableListOf<String>()
            val labels = arrayOf("content", "big", "headsUp")
            views.forEachIndexed { vi, rv ->
                if (rv == null) return@forEachIndexed
                val label = labels.getOrElse(vi) { "v$vi" }
                lines.add("[$label layout=${resName(rv.layoutId)}]")
                // (a) semua actions
                try {
                    val field = RemoteViews::class.java.getDeclaredField("mActions")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    val actions = field.get(rv) as? ArrayList<*> ?: emptyList<Any>()
                    for (action in actions) {
                        if (action == null) continue
                        try {
                            val cls = action.javaClass
                            val method = readActionField(action, "methodName") as? String
                                ?: readActionField(action, "mMethodName") as? String
                                ?: cls.simpleName ?: "?"
                            val viewId = (readActionField(action, "viewId") as? Int)
                                ?: (readActionField(action, "mViewId") as? Int)
                                ?: 0
                            val value = readActionField(action, "value")
                                ?: readActionField(action, "bitmap")
                            val detail = when (value) {
                                is CharSequence -> "'${value.toString().take(80)}'"
                                is Bitmap -> "bitmap ${value.width}x${value.height}"
                                is Int -> if (method.contains("esource", true) || method.contains("iew", true)) "res ${resName(value)}" else "$value"
                                is Boolean -> "$value"
                                is Icon -> "icon type=${value.type}"
                                is Uri -> "uri $value"
                                null -> "null"
                                else -> value.javaClass.simpleName
                            }
                            lines.add("[$label] $method@${resName(viewId)}=$detail")
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                // (b) hierarki inflate: measure + walk
                try {
                    if (senderCtx != null) {
                        val root = try {
                            rv.apply(senderCtx, null)
                        } catch (e: Exception) {
                            lines.add("[$label] inflate FAILED ${e.javaClass.simpleName}")
                            null
                        } ?: return@forEachIndexed
                        val dm = appContext.resources.displayMetrics
                        val wSpec = android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels, android.view.View.MeasureSpec.AT_MOST)
                        val hSpec = android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels, android.view.View.MeasureSpec.AT_MOST)
                        root.measure(wSpec, hSpec)
                        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                        walkViews(root, lines, label, senderCtx)
                    }
                } catch (_: Exception) {}
            }
            // chunk ~3000 char per baris log
            val chunks = mutableListOf<String>()
            val cur = StringBuilder()
            for (ln in lines) {
                if (cur.length + ln.length + 3 > 3000) {
                    chunks.add(cur.toString())
                    cur.clear()
                }
                cur.append(ln).append(" || ")
            }
            if (cur.isNotEmpty()) chunks.add(cur.toString())
            chunks.forEachIndexed { i, c ->
                android.util.Log.w("HyperBridgeDebug", "DELIVERY-RV-DUMP pkg=$pkg ${i + 1}/${chunks.size} $c")
            }
        } catch (_: Exception) {}
    }

    private fun walkViews(
        view: android.view.View,
        lines: MutableList<String>,
        label: String,
        senderCtx: Context
    ) {
        try {
            val cls = view.javaClass.simpleName
            val idName = try {
                if (view.id != 0 && view.id != -1) senderCtx.resources.getResourceEntryName(view.id) else "-"
            } catch (_: Exception) { "?" }
            val vis = when (view.visibility) {
                android.view.View.VISIBLE -> "V"
                android.view.View.INVISIBLE -> "I"
                else -> "G"
            }
            val extra = when (view) {
                is android.widget.TextView -> " text='${view.text?.toString()?.take(60)}'"
                is android.widget.ImageView -> {
                    val d = try { view.drawable } catch (_: Exception) { null }
                    val ds = if (d != null) " drawable=${d.javaClass.simpleName} ${d.intrinsicWidth}x${d.intrinsicHeight}" else " drawable=null"
                    val bg = try { view.background } catch (_: Exception) { null }
                    val bs = if (bg != null) " bg=${bg.javaClass.simpleName} ${bg.intrinsicWidth}x${bg.intrinsicHeight}" else ""
                    ds + bs
                }
                is android.widget.ProgressBar -> " progress=${try { view.progress } catch (_: Exception) { "?" }}/${try { view.max } catch (_: Exception) { "?" }}"
                else -> ""
            }
            lines.add("[$label] <$cls id=$idName vis=$vis ${view.measuredWidth}x${view.measuredHeight}$extra>")
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        walkViews(view.getChildAt(i), lines, label, senderCtx)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Inventarisasi SEMUA aksi gambar di RemoteViews (debug): method + viewId +
     * nama resource + ukuran, agar ketahuan elemen mana yang jadi banner.
     * Best-effort, tidak pernah throw.
     */
    fun dumpImageActions(
        appContext: Context,
        packageName: String,
        vararg views: RemoteViews?
    ): String {
        val out = mutableListOf<String>()
        val senderCtx = try {
            appContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Exception) { null }
        fun resName(id: Int): String {
            if (id == 0 || senderCtx == null) return "id=$id"
            return try {
                senderCtx.resources.getResourceEntryName(id)
            } catch (_: Exception) { "id=$id" }
        }
        for (rv in views) {
            if (rv == null) continue
            try {
                val field = RemoteViews::class.java.getDeclaredField("mActions")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val actions = field.get(rv) as? ArrayList<*> ?: continue
                for (action in actions) {
                    if (action == null) continue
                    try {
                        val cls = action.javaClass
                        val method = readActionField(action, "methodName") as? String
                            ?: if ((cls.simpleName ?: "").contains("BitmapReflectionAction")) "setImageViewBitmap"
                            else continue
                        if (!method.startsWith("setImageView") && !method.startsWith("setImage")) continue
                        val viewId = (readActionField(action, "viewId") as? Int) ?: 0
                        val value = readActionField(action, "value")
                            ?: readActionField(action, "bitmap")
                        val detail = when (value) {
                            is Bitmap -> "bitmap ${value.width}x${value.height}"
                            is Int -> "res ${resName(value)}"
                            is Icon -> "icon type=${value.type}"
                            is Uri -> "uri $value"
                            else -> "?"
                        }
                        out.add("$method@${resName(viewId)} $detail")
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        return out.distinct().joinToString(" | ")
    }

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

    /**
     * Ambil banner persegi panjang dari custom RemoteViews (mis. ShopeeFood 0x7f0d040f).
     *
     * (1) Reflection mActions: BitmapReflectionAction/setImageViewBitmap (field bitmap/value),
     * setImageViewResource (resolve via package context pengirim), setImageViewIcon/setImageIcon
     * (Icon), setImageViewUri/Uri (ContentResolver best-effort).
     * (2) Fallback inflate RemoteViews via createPackageContext(packageName) lalu telusuri
     * ImageView dan render drawable-nya ke bitmap.
     * (3) Hanya kembalikan bitmap aspek lebar w/h > 1.2 terbesar, selain itu null.
     */
    fun extractBannerBitmap(
        appContext: Context,
        packageName: String,
        vararg views: RemoteViews?
    ): Bitmap? {
        return try {
            val candidates = mutableListOf<Bitmap>()
            for (rv in views) {
                if (rv == null) continue
                try {
                    collectReflectionBitmaps(appContext, packageName, rv, candidates)
                } catch (_: Exception) {}
            }
            for (rv in views) {
                if (rv == null) continue
                try {
                    collectInflatedBitmaps(appContext, packageName, rv, candidates)
                } catch (_: Exception) {}
            }
            candidates
                .filter { !it.isRecycled && it.width > 0 && it.height > 0 }
                .filter { it.width.toFloat() / it.height.toFloat() > WIDE_ASPECT }
                .maxByOrNull { it.width * it.height }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- (1) reflection mActions ----------

    private fun collectReflectionBitmaps(
        appContext: Context,
        packageName: String,
        rv: RemoteViews,
        out: MutableList<Bitmap>
    ) {
        val actions = try {
            val field = RemoteViews::class.java.getDeclaredField("mActions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (field.get(rv) as? ArrayList<*>) ?: return
        } catch (_: Exception) {
            return
        }
        for (action in actions) {
            if (action == null) continue
            try {
                val cls = action.javaClass
                val simpleName = cls.simpleName ?: ""
                val methodName = readActionField(action, "methodName") as? String
                    ?: if (simpleName.contains("BitmapReflectionAction")) "setImageViewBitmap"
                    else continue
                when (methodName) {
                    "setImageViewBitmap", "setImageBitmap" -> {
                        val bmp = (readActionField(action, "bitmap") as? Bitmap)
                            ?: (readActionField(action, "value") as? Bitmap)
                        if (bmp != null && !bmp.isRecycled) out.add(bmp)
                    }
                    "setImageViewResource" -> {
                        val resId = (readActionField(action, "value") as? Int)
                            ?: (readActionField(action, "resId") as? Int)
                            ?: (readActionField(action, "resID") as? Int)
                            ?: 0
                        if (resId != 0) {
                            bitmapFromSenderRes(appContext, packageName, resId)?.let { out.add(it) }
                        }
                    }
                    "setImageViewIcon", "setImageIcon" -> {
                        val icon = (readActionField(action, "value") as? Icon)
                            ?: (readActionField(action, "icon") as? Icon)
                        if (icon != null) {
                            bitmapFromIcon(appContext, packageName, icon)?.let { out.add(it) }
                        }
                    }
                    "setImageViewUri", "setImageUri" -> {
                        val uri = (readActionField(action, "value") as? Uri)
                            ?: (readActionField(action, "uri") as? Uri)
                        if (uri != null) {
                            bitmapFromUri(appContext, uri)?.let { out.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun readActionField(action: Any, name: String): Any? {
        return try {
            var cls: Class<*>? = action.javaClass
            while (cls != null && cls != Any::class.java) {
                try {
                    val f = cls.getDeclaredField(name)
                    f.isAccessible = true
                    return f.get(action)
                } catch (_: NoSuchFieldException) {
                    cls = cls.superclass
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapFromSenderRes(appContext: Context, packageName: String, resId: Int): Bitmap? {
        return try {
            val senderCtx = appContext.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val drawable = ContextCompat.getDrawable(senderCtx, resId) ?: return null
            drawableToBitmap(drawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapFromIcon(appContext: Context, packageName: String, icon: Icon): Bitmap? {
        // Icon milik aplikasi pengirim; coba sender context dulu, fallback app context.
        val drawable = try {
            val senderCtx = appContext.createPackageContext(
                packageName,
                Context.CONTEXT_IGNORE_SECURITY
            )
            try {
                icon.loadDrawable(senderCtx)
            } catch (_: Exception) {
                icon.loadDrawable(appContext)
            }
        } catch (_: Exception) {
            try {
                icon.loadDrawable(appContext)
            } catch (_: Exception) {
                null
            }
        } ?: return null
        return try {
            drawableToBitmap(drawable)
        } catch (_: Exception) {
            null
        }
    }

    private fun bitmapFromUri(appContext: Context, uri: Uri): Bitmap? {
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- (2) fallback inflate ----------

    private fun collectInflatedBitmaps(
        appContext: Context,
        packageName: String,
        rv: RemoteViews,
        out: MutableList<Bitmap>
    ) {
        val senderCtx = try {
            appContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        } catch (_: Exception) {
            return
        }
        val root = try {
            rv.apply(senderCtx, null)
        } catch (_: Exception) {
            return
        } ?: return
        collectImageViewBitmaps(root, out)
    }

    private fun collectImageViewBitmaps(view: android.view.View, out: MutableList<Bitmap>) {        try {
            if (view is ImageView) {
                try {
                    val drawable = view.drawable
                    if (drawable != null) {
                        drawableToBitmap(drawable)?.let { out.add(it) }
                    }
                } catch (_: Exception) {}
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        collectImageViewBitmaps(view.getChildAt(i), out)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Kumpulkan bitmap ImageView beserta NAMA resource id-nya (mis. iv_stage_icon,
     * iv_driver_icon, iv_destination_icon) dari hasil inflate. Untuk progress bar
     * yang pakai ikon asli pengirim. Best-effort, tidak pernah throw.
     */
    fun extractNamedIconBitmaps(
        appContext: Context,
        packageName: String,
        vararg views: RemoteViews?
    ): Map<String, Bitmap> {
        val out = linkedMapOf<String, Bitmap>()
        try {
            val senderCtx = try {
                appContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
            } catch (_: Exception) { return out }
            for (rv in views) {
                if (rv == null) continue
                try {
                    val root = rv.apply(senderCtx, null) ?: continue
                    collectNamedBitmaps(root, senderCtx, out)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return out
    }

    private fun collectNamedBitmaps(
        view: android.view.View,
        senderCtx: Context,
        out: MutableMap<String, Bitmap>
    ) {
        try {
            if (view is ImageView) {
                try {
                    val name = try {
                        if (view.id != 0 && view.id != -1) senderCtx.resources.getResourceEntryName(view.id) else null
                    } catch (_: Exception) { null } ?: return
                    if (!out.containsKey(name)) {
                        val drawable = try { view.drawable } catch (_: Exception) { null }
                        if (drawable != null) {
                            drawableToBitmap(drawable)?.let { out[name] = it }
                        }
                    }
                } catch (_: Exception) {}
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    try {
                        collectNamedBitmaps(view.getChildAt(i), senderCtx, out)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable) {
                val bmp = drawable.bitmap
                if (bmp != null && !bmp.isRecycled) return bmp
            }
            val w = drawable.intrinsicWidth
            val h = drawable.intrinsicHeight
            if (w <= 0 || h <= 0) return null
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bmp
        } catch (_: Exception) {
            null
        }
    }
}
