package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentPageSessionGateTest {
    @Test
    fun `repeated events during one bill-page stay are suppressed`() {
        val gate = PaymentPageSessionGate()

        assertTrue(gate.onRecognizedPage())
        assertFalse(gate.onRecognizedPage())
        assertFalse(gate.onRecognizedPage())
    }

    @Test
    fun `opening the same bill after leaving starts a new session`() {
        val gate = PaymentPageSessionGate()

        assertTrue(gate.onRecognizedPage())
        gate.onPageAbsent()
        assertTrue(gate.onRecognizedPage())
    }
}
