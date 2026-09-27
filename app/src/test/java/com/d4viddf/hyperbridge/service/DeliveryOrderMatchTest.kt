package com.d4viddf.hyperbridge.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryOrderMatchTest {

    @Test
    fun missingIdentityCannotBlockAFinishSignal() {
        // Rating/feedback Shopee tidak punya extra_live_activity_id: kalau identity
        // kosong diartikan "order lain", pill-nya tidak akan pernah tertutup.
        assertTrue(deliveryOrderMatches(null, null))
        assertTrue(deliveryOrderMatches(null, "shopee_food_orders_1"))
        // Restart / FINISHED-dulu: deliveryOrderIdentity kosong walau pill hidup.
        assertTrue(deliveryOrderMatches("shopee_food_orders_1", null))
    }

    @Test
    fun knownIdentitiesNarrowTheMatch() {
        assertTrue(deliveryOrderMatches("shopee_food_orders_1", "shopee_food_orders_1"))
        assertTrue(deliveryOrderMatches("grab:com.grabtaxi.passenger:kopi kenangan", "grab:com.grabtaxi.passenger:kopi kenangan"))
    }

    @Test
    fun differentKnownIdentitiesAreDifferentOrders() {
        assertFalse(deliveryOrderMatches("shopee_food_orders_1", "shopee_food_orders_2"))
        assertFalse(deliveryOrderMatches("grab:com.grabtaxi.passenger:kopi", "grab:com.grabtaxi.passenger:ayam"))
    }

    @Test
    fun frozenUntrackedStageCannotRebuildAPill() {
        val now = 1_800_000_000_000L
        // Semua stage paket ini beku > 30 mnt (order lama) -> jangan bangun pill.
        assertTrue(isUntrackedStaleStage(false, now - 90 * 60_000L, now, freshestPackageStage = 0L))
        assertTrue(isUntrackedStaleStage(false, now - 90 * 60_000L, now, freshestPackageStage = now - 60 * 60_000L))
        // Ada stage DELIVERY lain di paket yang masih hidup -> order ini belum
        // tentu selesai, stage beku tetap boleh rebuild pill-nya.
        assertFalse(isUntrackedStaleStage(false, now - 90 * 60_000L, now, freshestPackageStage = now - 2 * 60_000L))
        // Order yang sedang jalan (pill-nya hidup) tidak boleh ikut digate.
        assertFalse(isUntrackedStaleStage(true, now - 90 * 60_000L, now, freshestPackageStage = 0L))
        // Stage baru tetap lolos walau stage lain di paket beku.
        assertFalse(isUntrackedStaleStage(false, now - 2 * 60_000L, now, freshestPackageStage = 0L))
    }
}
