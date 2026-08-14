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
    fun `recognizes paid Alipay historical bill without success status text`() {
        val result = engine.recognize(
            snapshot(
                AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                "账单详情",
                "支出134.8元",
                "等待确认收货",
                "支付时间",
                "2026-08-13 16:29:17",
                "付款方式",
                "中国银行储蓄卡(5912)",
                "交易详情",
            ),
        )

        assertNotNull(result)
        assertEquals("134.8", result?.amount)
        assertEquals("expense", result?.transactionType)
        assertEquals("alipay_historical_bill_expense_v3", result?.ruleId)
    }

    @Test
    fun `Alipay historical detail requires explicit direction and a reliable field`() {
        assertNull(
            engine.recognize(
                snapshot(
                    AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                    "账单详情",
                    "134.8元",
                    "支付时间",
                ),
            ),
        )
        assertNull(
            engine.recognize(
                snapshot(
                    AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                    "账单详情",
                    "支出134.8元",
                ),
            ),
        )
    }

    @Test
    fun `Alipay historical bill rejects unpaid failed closed and refund states`() {
        listOf("等待付款", "支付失败", "交易关闭", "退款中").forEach { unsafeState ->
            assertNull(
                "Expected historical page containing '$unsafeState' to be rejected",
                engine.recognize(
                    snapshot(
                        AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                        "账单详情",
                        "支出134.8元",
                        "支付时间",
                        unsafeState,
                    ),
                ),
            )
        }
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

    @Test
    fun `classifies partially loaded bill detail as candidate`() {
        val rules = BuiltInRecognitionRules.value.copy(
            apps = BuiltInRecognitionRules.value.apps.map { app ->
                if (app.packageName == AccessibilityBillingPreferences.ALIPAY_PACKAGE) {
                    app.copy(pageCandidateAnchors = listOf("账单详情", "交易详情"))
                } else {
                    app
                }
            },
        )
        val state = PaymentRecognitionEngine { rules }.classify(
            snapshot(AccessibilityBillingPreferences.ALIPAY_PACKAGE, "账单详情", "加载中"),
        )

        assertEquals(PaymentRecognitionEngine.PageState.BILL_CANDIDATE, state)
    }

    @Test
    fun `classifies readable unrelated page as non bill`() {
        val state = engine.classify(
            snapshot(AccessibilityBillingPreferences.ALIPAY_PACKAGE, "支付宝首页", "扫一扫"),
        )

        assertEquals(PaymentRecognitionEngine.PageState.NON_BILL_PAGE, state)
    }

    @Test
    fun `classifies empty slow loading webview as bill candidate`() {
        val state = engine.classify(
            snapshot(AccessibilityBillingPreferences.ALIPAY_PACKAGE),
        )

        assertEquals(PaymentRecognitionEngine.PageState.BILL_CANDIDATE, state)
    }

    @Test
    fun `classifies loading placeholder as bill candidate`() {
        val state = engine.classify(
            snapshot(AccessibilityBillingPreferences.ALIPAY_PACKAGE, "加载中"),
        )

        assertEquals(PaymentRecognitionEngine.PageState.BILL_CANDIDATE, state)
    }

    @Test
    fun `loading label inside a readable unrelated page does not block departure`() {
        val state = engine.classify(
            snapshot(
                AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                "支付宝首页",
                "扫一扫",
                "收付款",
                "加载中",
            ),
        )

        assertEquals(PaymentRecognitionEngine.PageState.NON_BILL_PAGE, state)
    }

    @Test
    fun `v2 scopes extraction to a unique transaction container`() {
        val scopedEngine = PaymentRecognitionEngine { scopedRuleSet() }
        val result = scopedEngine.recognize(
            treeSnapshot(
                node(0, "订单详情", 0, parentIndex = null, viewId = "shop:id/detail_page"),
                node(1, null, 100, parentIndex = 0, viewId = "shop:id/order_card"),
                node(2, "交易完成", 200, parentIndex = 1),
                node(3, "商品单价", 300, parentIndex = 1),
                node(4, "¥6.02", 400, parentIndex = 1),
                node(5, "实付款", 500, parentIndex = 1),
                node(6, "¥17.96", 600, parentIndex = 1),
                node(7, "示例商店", 700, parentIndex = 1, viewId = "shop:id/merchant"),
                node(8, "¥4699", 800, parentIndex = 0),
            ),
        )

        assertNotNull(result)
        assertEquals("17.96", result?.amount)
        assertEquals("示例商店", result?.merchant)
    }

    @Test
    fun `v2 rejects more than one matching transaction container`() {
        val scopedEngine = PaymentRecognitionEngine { scopedRuleSet() }
        val result = scopedEngine.recognize(
            treeSnapshot(
                node(0, "全部订单", 0, parentIndex = null, viewId = "shop:id/detail_page"),
                node(1, null, 100, parentIndex = 0, viewId = "shop:id/order_card"),
                node(2, "交易完成", 200, parentIndex = 1),
                node(3, "实付款", 300, parentIndex = 1),
                node(4, "¥17.96", 400, parentIndex = 1),
                node(5, null, 500, parentIndex = 0, viewId = "shop:id/order_card"),
                node(6, "交易完成", 600, parentIndex = 5),
                node(7, "实付款", 700, parentIndex = 5),
                node(8, "¥224.05", 800, parentIndex = 5),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `v2 rejects ambiguous relative amount candidates`() {
        val scopedEngine = PaymentRecognitionEngine { scopedRuleSet() }
        val result = scopedEngine.recognize(
            treeSnapshot(
                node(0, "订单详情", 0, parentIndex = null, viewId = "shop:id/detail_page"),
                node(1, null, 100, parentIndex = 0, viewId = "shop:id/order_card"),
                node(2, "交易完成", 200, parentIndex = 1),
                node(3, "实付款", 300, parentIndex = 1),
                node(4, "¥17.96", 400, parentIndex = 1),
                node(5, "¥18.96", 500, parentIndex = 1),
            ),
        )

        assertNull(result)
    }

    @Test
    fun `v2 can derive its container from an anchor ancestor`() {
        val base = scopedRuleSet()
        val pageRule = base.apps.single().pageRules.single()
        val anchorScoped = base.copy(
            apps = listOf(
                base.apps.single().copy(
                    pageRules = listOf(
                        pageRule.copy(
                            scope = ContainerScopeRule(
                                anchor = NodeSelector(textEquals = listOf("交易完成")),
                                ancestorLevels = 1,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = PaymentRecognitionEngine { anchorScoped }.recognize(
            treeSnapshot(
                node(0, "订单详情", 0, parentIndex = null, viewId = "shop:id/detail_page"),
                node(1, null, 100, parentIndex = 0, viewId = "shop:id/order_card"),
                node(2, "交易完成", 200, parentIndex = 1),
                node(3, "实付款", 300, parentIndex = 1),
                node(4, "¥17.96", 400, parentIndex = 1),
                node(5, "示例商店", 500, parentIndex = 1, viewId = "shop:id/merchant"),
            ),
        )

        assertEquals("17.96", result?.amount)
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

    private fun treeSnapshot(vararg nodes: NormalizedNode): AccessibilityPageSnapshot =
        AccessibilityPageSnapshot("com.example.shop", "OrderActivity", "1.0", nodes.toList())

    private fun scopedRuleSet(): RecognitionRuleSet = RecognitionRuleSet(
        schemaVersion = 2,
        rulesVersion = 2,
        apps = listOf(
            AppRecognitionRule(
                id = "example_shop",
                packageName = "com.example.shop",
                displayName = "示例商城",
                defaultEnabled = false,
                activityIncludes = emptyList(),
                pageRules = listOf(
                    PageRecognitionRule(
                        id = "order_detail_v2",
                        transactionType = "expense",
                        requiredAnchors = emptyList(),
                        anyAnchors = emptyList(),
                        excludedAnchors = emptyList(),
                        amount = AmountExtractionRule(
                            regexes = listOf("¥([0-9]+(?:\\.[0-9]{1,2})?)"),
                            node = RelativeNodeRule(
                                selector = NodeSelector(textRegexes = listOf("¥[0-9]+(?:\\.[0-9]{1,2})?")),
                                relativeTo = NodeSelector(textEquals = listOf("实付款")),
                                relation = "followingSibling",
                            ),
                        ),
                        merchant = FieldExtractionRule(
                            node = RelativeNodeRule(
                                selector = NodeSelector(viewIdContains = listOf("merchant")),
                            ),
                        ),
                        note = FieldExtractionRule(fallbackToMerchant = true),
                        paymentMethod = FieldExtractionRule(),
                        transactionTime = FieldExtractionRule(),
                        orderId = FieldExtractionRule(),
                        pageMatch = PageNodeMatchRule(
                            all = listOf(NodeSelector(viewIdEquals = listOf("shop:id/detail_page"))),
                            any = listOf(NodeSelector(textEquals = listOf("交易完成"))),
                            none = listOf(NodeSelector(textContains = listOf("交易关闭"))),
                        ),
                        scope = ContainerScopeRule(
                            selector = NodeSelector(viewIdEquals = listOf("shop:id/order_card")),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun node(
        index: Int,
        text: String?,
        top: Int,
        parentIndex: Int? = null,
        viewId: String = "example:id/node_$index",
    ): NormalizedNode = NormalizedNode(
        index = index,
        parentIndex = parentIndex,
        depth = 0,
        text = text,
        contentDescription = null,
        viewId = viewId,
        className = "android.widget.TextView",
        bounds = NodeBounds(0, top, 500, top + 100),
    )
}
