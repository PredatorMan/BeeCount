package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentRecognitionEngineTest {
    private val engine = PaymentRecognitionEngine()

    @Test
    fun `recognizes conservative WeChat payment success candidate`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.WECHAT_PACKAGE,
                "支付成功",
                "￥12.50",
                "商户",
                "测试商店",
                "支付方式",
                "零钱",
            ),
        )

        assertNotNull(result)
        assertEquals("12.5", result?.amount)
        assertEquals("测试商店", result?.merchant)
        assertEquals("测试商店", result?.note)
        assertEquals("零钱", result?.paymentMethod)
        assertEquals("expense", result?.transactionType)
        assertEquals("wechat_payment_success_v1", result?.ruleId)
    }

    @Test
    fun `recognizes Alipay success candidate`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                "交易成功",
                "25元",
                "收款方：测试便利店",
            ),
        )

        assertNotNull(result)
        assertEquals("25", result?.amount)
        assertEquals("测试便利店", result?.merchant)
        assertEquals("alipay_payment_success_v1", result?.ruleId)
    }

    @Test
    fun `does not recognize success words without amount`() {
        assertNull(
            engine.recognize(
                snapshot(AccessibilityBillingPreferences.WECHAT_PACKAGE, "支付成功", "完成"),
            ),
        )
    }

    @Test
    fun `recognizes standalone amount only beside a currency node`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.WECHAT_PACKAGE,
                "支付成功",
                "￥",
                "12.30",
            ),
        )

        assertEquals("12.3", result?.amount)
    }

    @Test
    fun `rejects unrelated amount far from success anchor`() {
        val packageName = AccessibilityBillingPreferences.WECHAT_PACKAGE
        val result = engine.recognize(
            AccessibilityPageSnapshot(
                packageName = packageName,
                activityName = "example.PaymentResultActivity",
                appVersion = "1.0",
                nodes = listOf(
                    node(index = 0, text = "支付成功", top = 0),
                    node(index = 1, text = "￥88.00", top = 3000),
                ),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `excludes unsafe and non-final payment pages`() {
        val excludedStates = listOf(
            "支付失败",
            "支付处理中",
            "待支付",
            "支付已取消",
            "退款中",
            "请输入支付密码",
            "确认付款",
        )

        excludedStates.forEach { excludedState ->
            val result = engine.recognize(
                snapshot(
                    AccessibilityBillingPreferences.WECHAT_PACKAGE,
                    "支付成功",
                    "￥18.00",
                    excludedState,
                ),
            )
            assertNull("Expected page containing '$excludedState' to be rejected", result)
        }
    }

    @Test
    fun `allows payment success page with bill details action`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.WECHAT_PACKAGE,
                "支付成功",
                "￥18.00",
                "查看账单详情",
            ),
        )

        assertNotNull(result)
    }

    @Test
    fun `recognizes historical bill detail page`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.WECHAT_PACKAGE,
                "账单详情",
                "支付成功",
                "￥18.00",
                "商户：测试商店",
                "支付时间",
                "2024年1月2日 08:09:10",
            ),
        )

        assertNotNull(result)
        assertEquals("测试商店", result?.merchant)
        assertEquals("2024年1月2日 08:09:10", result?.transactionTime)
    }

    @Test
    fun `infers merchant from Alipay merchant expense success sequence`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                "1688平台商家",
                "支出59元",
                "支付成功",
                "交易时间",
                "2024年1月2日 08:09:10",
            ),
        )

        assertNotNull(result)
        assertEquals("59", result?.amount)
        assertEquals("1688平台商家", result?.merchant)
        assertEquals("1688平台商家", result?.note)
        assertEquals("2024年1月2日 08:09:10", result?.transactionTime)
    }

    @Test
    fun `disabling auto note preserves recognized merchant`() {
        val recognized = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                "1688平台商家",
                "支出59元",
                "支付成功",
            ),
        )!!

        val withoutNote = recognized.withAutoExtractNote(enabled = false)

        assertEquals("1688平台商家", withoutNote.merchant)
        assertNull(withoutNote.note)
        assertEquals("1688平台商家", recognized.note)
        assertEquals("1688平台商家", recognized.toMap()["note"])
    }

    @Test
    fun `page fingerprint is stable and includes amount merchant and transaction time`() {
        fun recognize(amount: String, merchant: String, transactionTime: String): PaymentRecognition {
            return engine.recognize(
                snapshot(
                    AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                    "交易详情",
                    merchant,
                    "支出${amount}元",
                    "支付成功",
                    "交易时间：$transactionTime",
                ),
            )!!
        }

        val baseline = recognize("59.00", "1688平台商家", "2024年1月2日 08:09:10")
        val repeated = recognize("59.00", "1688平台商家", "2024年1月2日 08:09:10")

        assertEquals(baseline.pageFingerprint, repeated.pageFingerprint)
        assertNotEquals(baseline.pageFingerprint, recognize("60.00", "1688平台商家", "2024年1月2日 08:09:10").pageFingerprint)
        assertNotEquals(baseline.pageFingerprint, recognize("59.00", "另一商家", "2024年1月2日 08:09:10").pageFingerprint)
        assertNotEquals(baseline.pageFingerprint, recognize("59.00", "1688平台商家", "2024年1月3日 08:09:10").pageFingerprint)
    }

    @Test
    fun `ignores unsupported package`() {
        assertNull(engine.recognize(snapshot("example.unsupported", "支付成功", "￥8.00")))
    }

    private fun snapshot(packageName: String, vararg texts: String): AccessibilityPageSnapshot {
        return AccessibilityPageSnapshot(
            packageName = packageName,
            activityName = "example.PaymentResultActivity",
            appVersion = "1.0",
            nodes = texts.mapIndexed { index, text ->
                node(index = index, text = text, top = index * 100)
            },
        )
    }

    private fun node(index: Int, text: String, top: Int): NormalizedNode = NormalizedNode(
        index = index,
        parentIndex = null,
        depth = 0,
        text = text,
        contentDescription = null,
        viewId = "example:id/node_$index",
        className = "android.widget.TextView",
        bounds = NodeBounds(0, top, 500, top + 100),
    )
}
