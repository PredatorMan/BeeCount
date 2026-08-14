package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Test

class PaymentPageSessionGateTest {
    private val show = PaymentPageSessionGate.Decision.SHOW_OVERLAY
    private val none = PaymentPageSessionGate.Decision.NONE

    @Test
    fun `bill remains showable until the overlay is dismissed`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        assertEquals(show, gate.observe(bill("bill-a")))
    }

    @Test
    fun `cancel or save suppresses the current bill through unknown observations`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.Unknown))
        assertEquals(none, gate.observe(bill("bill-a")))
    }

    @Test
    fun `scrolling after cancellation keeps the current bill suppressed`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(bill("bill-a")))
        assertEquals(none, gate.observe(bill("bill-a")))
    }

    @Test
    fun `one non bill observation does not end the current session`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.NonBill))
        assertEquals(none, gate.observe(bill("bill-a")))
    }

    @Test
    fun `unknown between confirmed non bill observations preserves pending confirmation`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.NonBill))
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.Unknown))
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.NonBill))
        assertEquals(show, gate.observe(bill("bill-a")))
    }

    @Test
    fun `two confirmed non bill observations allow historical bill to show again`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.NonBill))
        assertEquals(none, gate.observe(PaymentPageSessionGate.Observation.NonBill))
        assertEquals(show, gate.observe(bill("bill-a")))
    }

    @Test
    fun `different bill can show without first observing a non bill page`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        assertEquals(show, gate.observe(bill("bill-b")))
        gate.suppressCurrentPage()
        assertEquals(none, gate.observe(bill("bill-b")))
    }

    @Test
    fun `reset clears suppression when monitoring explicitly stops`() {
        val gate = PaymentPageSessionGate()

        assertEquals(show, gate.observe(bill("bill-a")))
        gate.suppressCurrentPage()
        gate.reset()

        assertEquals(show, gate.observe(bill("bill-a")))
    }

    private fun bill(pageKey: String) = PaymentPageSessionGate.Observation.Bill(pageKey)
}
