package com.d4viddf.hyperbridge.util

/**
 * Stage/percent delivery dari teks extras (jalur produksi 100% extras, nol contentView).
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

}
