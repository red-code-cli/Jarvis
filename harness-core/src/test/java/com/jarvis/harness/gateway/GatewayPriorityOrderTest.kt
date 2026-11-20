package com.jarvis.harness.gateway

import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayPriorityOrderTest {

    @Test
    fun encodeDropsBlanksAndDuplicates() {
        assertEquals("a,b", GatewayPriorityOrder.encode(listOf("a", " ", "b", "a")))
    }

    @Test
    fun decodeRoundTrips() {
        val order = listOf("openrouter", "deepseek", "custom")
        assertEquals(order, GatewayPriorityOrder.decode(GatewayPriorityOrder.encode(order)))
    }

    @Test
    fun moveReordersAndClamps() {
        val order = listOf("a", "b", "c")
        assertEquals(listOf("b", "a", "c"), GatewayPriorityOrder.move(order, 1, 0))
        assertEquals(listOf("a", "c", "b"), GatewayPriorityOrder.move(order, 1, 9))
        assertEquals(order, GatewayPriorityOrder.move(order, 5, 0))
    }

    @Test
    fun reconcileKeepsSavedOrderThenAppendsNewProviders() {
        val reconciled = GatewayPriorityOrder.reconcile(
            saved = listOf("deepseek", "openrouter"),
            available = listOf("openrouter", "deepseek", "new"),
        )
        assertEquals(listOf("deepseek", "openrouter", "new"), reconciled)
    }
}
