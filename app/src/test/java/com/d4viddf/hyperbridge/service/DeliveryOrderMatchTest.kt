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
}
