package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityBillingDiagnosticsTest {
    @Test
    fun `redacts long identifiers but keeps amounts and short labels`() {
        assertEquals(
            "订单号 [已脱敏:5678]，金额 ￥12.50",
            AccessibilityBillingDiagnostics.redactText(
                "订单号 1234 5678，金额 ￥12.50",
            ),
        )
        assertEquals("尾号 1234", AccessibilityBillingDiagnostics.redactText("尾号 1234"))
    }
}
