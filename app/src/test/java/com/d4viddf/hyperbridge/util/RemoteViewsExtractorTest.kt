package com.d4viddf.hyperbridge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests (no device needed) for the single source of truth
 * used by DeliveryTranslator + NotificationReaderService.
 * Run: gradlew :app:testDebugUnitTest --offline
 */
class RemoteViewsExtractorTest {

    @Test
    fun deliveryStage_kitchenIdAndEn_isOne() {
        assertEquals(1, RemoteViewsExtractor.deliveryStage("Burjo Titik Kumpul sedang disiapkan"))
        assertEquals(1, RemoteViewsExtractor.deliveryStage("In the kitchen"))
        assertEquals(1, RemoteViewsExtractor.deliveryStage("Burjo is preparing your order. Tap to see details."))
    }

    @Test
    fun deliveryStage_searchingDriver_isOne() {
        assertEquals(1, RemoteViewsExtractor.deliveryStage("Kami sedang mencarikan driver untuk pesananmu"))
        assertEquals(1, RemoteViewsExtractor.deliveryStage("Mencari driver untuk pesananmu di Burjo"))
        assertEquals(1, RemoteViewsExtractor.deliveryStage("Finding driver for your order"))
    }

    @Test
    fun deliveryStage_onTheWay_isTwo() {
        assertEquals(2, RemoteViewsExtractor.deliveryStage("Driver sedang menuju lokasimu"))
        assertEquals(2, RemoteViewsExtractor.deliveryStage("Your order is on the way to you"))
        assertEquals(2, RemoteViewsExtractor.deliveryStage("Driver sedang menuju Resto"))
    }

    @Test
    fun deliveryStage_arrived_isThree_butEtaEstimateIsNot() {
        assertEquals(3, RemoteViewsExtractor.deliveryStage("Your order is here!"))
        assertEquals(3, RemoteViewsExtractor.deliveryStage("Driver sudah tiba di lokasimu"))
        assertEquals(3, RemoteViewsExtractor.deliveryStage("Selamat menikmati!"))
        // Estimasi ("Tiba pada ...") bukan tiba — tidak boleh stage 3 via kata tiba.
        assertFalse(RemoteViewsExtractor.deliveryStage("Tiba pada 20:25") == 3)
    }

    @Test
    fun deliveryStage_promoAndOperational_isNull() {
        assertNull(RemoteViewsExtractor.deliveryStage("Ada diskon s.d. 50% di GrabMart!"))
        assertNull(RemoteViewsExtractor.deliveryStage("Photo upload successful"))
        assertNull(RemoteViewsExtractor.deliveryStage("Get ready to scan the driver's QR to pay."))
    }

    @Test
    fun deliveryPercent_searching_isTen() {
        assertEquals(10, RemoteViewsExtractor.deliveryPercent(1, "Mencari driver untuk pesananmu"))
        assertEquals(35, RemoteViewsExtractor.deliveryPercent(2, "Driver sedang menuju Resto"))
        assertEquals(100, RemoteViewsExtractor.deliveryPercent(3, "Your order is here!"))
        assertNull(RemoteViewsExtractor.deliveryPercent(null, "apa pun"))
    }

    @Test
    fun finishedStrong_feedbackTrue_butArrivedFalse() {
        assertTrue(
            RemoteViewsExtractor.isDeliveryFinishedStrong(
                "Did You Enjoy Your Order? We would love to hear your feedback"
            )
        )
        assertTrue(RemoteViewsExtractor.isDeliveryFinishedStrong("Selamat menikmati! Pastikan pesananmu sudah sesuai"))
        assertTrue(RemoteViewsExtractor.isDeliveryFinishedStrong("Beri penilaian untuk pesanan ini"))
        // Varian Grab baru: "What's Your Verdict?" (order tuntas juga harus dismiss).
        assertTrue(
            RemoteViewsExtractor.isDeliveryFinishedStrong(
                "What's Your Verdict? Tell us what you think about Burjo Titik Kumpul - Tembalang."
            )
        )
        // Tiba-di-tujuan BUKAN tuntas — pill harus tetap tampil 100%.
        assertFalse(RemoteViewsExtractor.isDeliveryFinishedStrong("Your order is here!"))
        assertFalse(RemoteViewsExtractor.isDeliveryFinishedStrong("Driver sudah tiba"))
        assertFalse(RemoteViewsExtractor.isDeliveryFinishedStrong("Your order is on the way to you"))
    }

    @Test
    fun finishedLoose_addsGenericSelesai() {
        assertTrue(RemoteViewsExtractor.isDeliveryFinished("Pesanan selesai"))
        assertFalse(RemoteViewsExtractor.isDeliveryFinished("In the kitchen"))
    }

    @Test
    fun eta_rangeWithDots_isDuration() {
        // "07.25 - 07.40" harusnya 15 menit (durasi), BUKAN sisa-ke-ujung.
        assertEquals("15 menit", RemoteViewsExtractor.extractEtaFromCorpus("Tiba 07.25 - 07.40"))
        assertEquals("15 menit", RemoteViewsExtractor.extractEtaFromCorpus("07.25 - 07.40"))
        assertEquals("10 menit", RemoteViewsExtractor.extractEtaFromCorpus("08:52 - 09:02"))
    }

    @Test
    fun eta_minutesWin() {
        assertEquals("25 menit", RemoteViewsExtractor.extractEtaFromCorpus("Driver tiba, 25 menit lagi"))
        assertEquals("10 menit", RemoteViewsExtractor.extractEtaFromCorpus("Tiba dalam 10"))
        assertNull(RemoteViewsExtractor.extractEtaFromCorpus("Burjo Titik Kumpul - Tembalang is here!"))
    }

    @Test
    fun etaRaw_returnsFirstRawMatch() {
        assertEquals("07.25 - 07.40", RemoteViewsExtractor.extractEtaRaw("Tiba 07.25 - 07.40"))
        assertEquals("25 menit", RemoteViewsExtractor.extractEtaRaw("Driver tiba, 25 menit lagi"))
        assertNull(RemoteViewsExtractor.extractEtaRaw("Burjo Titik Kumpul - Tembalang is here!"))
    }

    @Test
    fun resto_realGrabSamples() {
        // Sampel REAL logcat 2026-09-22 (Burjo Titik Kumpul - Tembalang).
        assertEquals(
            "Burjo Titik Kumpul - Tembalang",
            RemoteViewsExtractor.extractRestoName(
                "Your order from Burjo Titik Kumpul - Tembalang is on the way to you. " +
                    "Provide your floor or unit number to your driver if applicable."
            )
        )
        assertEquals(
            "Burjo Titik Kumpul - Tembalang",
            RemoteViewsExtractor.extractRestoName(
                "Burjo Titik Kumpul - Tembalang is preparing your order. Tap to see details."
            )
        )
        assertEquals(
            "Burjo Titik Kumpul - Tembalang",
            RemoteViewsExtractor.extractRestoName(
                "Your order from Burjo Titik Kumpul - Tembalang is here! If there are any " +
                    "issues with your order, share it with us within 12 hours upon receiving it."
            )
        )
        // Tanpa pola resto -> null (perilaku lama tak berubah).
        assertNull(RemoteViewsExtractor.extractRestoName("Your order is on the way to you"))
        assertNull(RemoteViewsExtractor.extractRestoName("Ada diskon s.d. 50% di GrabMart!"))
        assertNull(RemoteViewsExtractor.extractRestoName("Food & Delivery"))
    }
}
