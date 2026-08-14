package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityBillingTransactionCoordinatorTest {
    @Test
    fun `saved transaction is broadcast to every active Flutter engine`() {
        val firstEvents = mutableListOf<Pair<Int, Int>>()
        val secondEvents = mutableListOf<Pair<Int, Int>>()
        val first = AccessibilityBillingTransactionCoordinator.Listener { ledgerId, transactionId ->
            firstEvents += ledgerId to transactionId
        }
        val second = AccessibilityBillingTransactionCoordinator.Listener { ledgerId, transactionId ->
            secondEvents += ledgerId to transactionId
        }

        AccessibilityBillingTransactionCoordinator.addListener(first)
        AccessibilityBillingTransactionCoordinator.addListener(second)
        try {
            AccessibilityBillingTransactionCoordinator.notifySaved(
                ledgerId = 7,
                transactionId = 42,
            )

            assertEquals(listOf(7 to 42), firstEvents)
            assertEquals(listOf(7 to 42), secondEvents)
        } finally {
            AccessibilityBillingTransactionCoordinator.removeListener(first)
            AccessibilityBillingTransactionCoordinator.removeListener(second)
        }
    }

    @Test
    fun `closed Flutter engine no longer receives saved transactions`() {
        val events = mutableListOf<Pair<Int, Int>>()
        val listener = AccessibilityBillingTransactionCoordinator.Listener { ledgerId, transactionId ->
            events += ledgerId to transactionId
        }

        AccessibilityBillingTransactionCoordinator.addListener(listener)
        AccessibilityBillingTransactionCoordinator.removeListener(listener)
        AccessibilityBillingTransactionCoordinator.notifySaved(
            ledgerId = 2,
            transactionId = 9,
        )

        assertEquals(emptyList<Pair<Int, Int>>(), events)
    }
}
