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
        // Stage beku 1,5 jam (order lama) -> jangan bangun pill saat proses start.
        assertTrue(isUntrackedStaleStage(tracked = false, postTime = now - 90 * 60_000L, now = now))
        // Batas ambang: 44 menit masih lolos, 46 menit sudah gugur.
        assertFalse(isUntrackedStaleStage(tracked = false, postTime = now - 44 * 60_000L, now = now))
        assertTrue(isUntrackedStaleStage(tracked = false, postTime = now - 46 * 60_000L, now = now))
        // Order yang sedang jalan (pill-nya hidup) tidak boleh ikut digate.
        assertFalse(isUntrackedStaleStage(tracked = true, postTime = now - 90 * 60_000L, now = now))
    }
}
