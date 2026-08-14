package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRuleCodecTest {
    @Test
    fun `parses schema v2 selectors scope and relative extraction`() {
        val rules = RecognitionRuleCodec.parse(containerRulesJson())
        val rule = rules.findApp("com.example.shop")!!.pageRules.single()

        assertEquals(2, rules.schemaVersion)
        assertEquals("order_card", rule.scope?.selector?.viewIdContains?.single())
        assertEquals("followingSibling", rule.amount.node?.relation)
        assertTrue(rule.pageMatch.none.single().textContains.contains("交易关闭"))
    }

    @Test
    fun `rejects empty selectors and relative relation without reference`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(containerRulesJson().replace(
                "\"viewIdContains\": [\"order_card\"]",
                "\"viewIdContains\": []",
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(containerRulesJson().replace(
                "\"relativeTo\": {\"textEquals\": [\"实付款\"]},",
                "",
            ))
        }
    }

    @Test
    fun `parses declarative rule and recognizes a dynamically adapted app`() {
        val rules = RecognitionRuleCodec.parse(validRulesJson(rulesVersion = 2))
        val app = rules.findApp("com.example.wallet")!!
        val engine = PaymentRecognitionEngine { rules }
        val result = engine.recognize(
            snapshot(
                packageName = app.packageName,
                activityName = "com.example.wallet.PaymentDetailActivity",
                "交易完成",
                "商户=示例咖啡",
                "备注：早餐",
                "银行卡(1234)",
                "金额 ¥18.50",
            ),
        )

        assertEquals("示例钱包", app.displayName)
        assertTrue(app.defaultEnabled)
        assertNotNull(result)
        assertEquals("18.5", result?.amount)
        assertEquals("示例咖啡", result?.merchant)
        assertEquals("早餐", result?.note)
        assertEquals("银行卡(1234)", result?.paymentMethod)
        assertEquals("expense", result?.transactionType)
    }

    @Test
    fun `parses app page candidate anchors for slow loading classification`() {
        val raw = validRulesJson(rulesVersion = 2).replace(
            "\"activityIncludes\": [\"PaymentDetail\"],",
            "\"activityIncludes\": [\"PaymentDetail\"], " +
                "\"pageCandidateAnchors\": [\"账单详情\", \"交易详情\"],",
        )
        val app = RecognitionRuleCodec.parse(raw).findApp("com.example.wallet")!!

        assertEquals(listOf("账单详情", "交易详情"), app.pageCandidateAnchors)
    }

    @Test
    fun `activity include and excluded anchors are enforced`() {
        val rules = RecognitionRuleCodec.parse(validRulesJson(rulesVersion = 2))
        val engine = PaymentRecognitionEngine { rules }

        assertNull(
            engine.recognize(
                snapshot("com.example.wallet", "com.example.wallet.HomeActivity", "交易完成", "¥18.50"),
            ),
        )
        assertNull(
            engine.recognize(
                snapshot(
                    "com.example.wallet",
                    "com.example.wallet.PaymentDetailActivity",
                    "交易完成",
                    "风险阻止",
                    "¥18.50",
                ),
            ),
        )
    }

    @Test
    fun `rejects unsupported schema invalid regex and version downgrade`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(validRulesJson(schemaVersion = 99))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(validRulesJson(amountRegex = "("))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(validRulesJson(amountRegex = "(a+)+"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(validRulesJson(amountRegex = "(a)\\1"))
        }

        val active = RecognitionRuleCodec.parse(validRulesJson(rulesVersion = 3))
        val older = RecognitionRuleCodec.parse(validRulesJson(rulesVersion = 2))
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validate(older, active)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validate(active, active)
        }
    }

    @Test
    fun `update policy accepts supported legacy schema`() {
        val legacy = withBuiltInApps(
            RecognitionRuleCodec.parse(
                validRulesJson(schemaVersion = 1, rulesVersion = 7, defaultEnabled = false),
            ),
        )

        RecognitionRuleUpdatePolicy.validate(legacy, BuiltInRecognitionRules.value)
        RecognitionRuleUpdatePolicy.validateCached(legacy, BuiltInRecognitionRules.value)
    }

    @Test
    fun `rejects excessively nested JSON`() {
        val deeplyNested = "[".repeat(70) + "0" + "]".repeat(70)

        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleCodec.parse(deeplyNested)
        }
    }

    @Test
    fun `cached rules cannot be older than built in rules`() {
        val older = RecognitionRuleCodec.parse(validRulesJson(rulesVersion = 1))
        val newerBuiltIn = BuiltInRecognitionRules.value.copy(rulesVersion = 2)

        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validateCached(older, newerBuiltIn)
        }
    }

    @Test
    fun `cached rules equal to built in version are discarded`() {
        val cached = RecognitionRuleCodec.parse(
            validRulesJson(rulesVersion = BuiltInRecognitionRules.value.rulesVersion),
        )

        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validateCached(cached, BuiltInRecognitionRules.value)
        }
    }

    @Test
    fun `remote new apps require explicit user enablement`() {
        val enabledNewApp = withBuiltInApps(
            RecognitionRuleCodec.parse(
                validRulesJson(rulesVersion = 7, defaultEnabled = true),
            ),
        )
        val disabledNewApp = withBuiltInApps(
            RecognitionRuleCodec.parse(
                validRulesJson(rulesVersion = 7, defaultEnabled = false),
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validate(
                enabledNewApp,
                BuiltInRecognitionRules.value,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecognitionRuleUpdatePolicy.validateCached(
                enabledNewApp,
                BuiltInRecognitionRules.value,
            )
        }
        RecognitionRuleUpdatePolicy.validate(
            disabledNewApp,
            BuiltInRecognitionRules.value,
        )
        RecognitionRuleUpdatePolicy.validateCached(
            disabledNewApp,
            BuiltInRecognitionRules.value,
        )
    }

    @Test
    fun `remote rules cannot remove or default disable built in apps`() {
        val builtIn = BuiltInRecognitionRules.value
        val nextVersion = builtIn.rulesVersion + 1
        val missingAlipay = builtIn.copy(
            rulesVersion = nextVersion,
            apps = builtIn.apps.filterNot {
                it.packageName == AccessibilityBillingPreferences.ALIPAY_PACKAGE
            },
        )
        val disabledAlipay = builtIn.copy(
            rulesVersion = nextVersion,
            apps = builtIn.apps.map { app ->
                if (app.packageName == AccessibilityBillingPreferences.ALIPAY_PACKAGE) {
                    app.copy(defaultEnabled = false)
                } else {
                    app
                }
            },
        )

        listOf(missingAlipay, disabledAlipay).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                RecognitionRuleUpdatePolicy.validate(candidate, builtIn)
            }
            assertThrows(IllegalArgumentException::class.java) {
                RecognitionRuleUpdatePolicy.validateCached(candidate, builtIn)
            }
        }
    }

    @Test
    fun `built in rules remain available offline`() {
        val builtIn = BuiltInRecognitionRules.value

        assertNotNull(builtIn.findApp(AccessibilityBillingPreferences.WECHAT_PACKAGE))
        assertNotNull(builtIn.findApp(AccessibilityBillingPreferences.ALIPAY_PACKAGE))
        assertEquals(2, builtIn.apps.size)
        assertEquals(6, builtIn.rulesVersion)
        assertEquals(
            "alipay_historical_bill_expense_v3",
            builtIn.findApp(AccessibilityBillingPreferences.ALIPAY_PACKAGE)?.pageRules?.first()?.id,
        )
    }

    private fun validRulesJson(
        schemaVersion: Int = 1,
        rulesVersion: Int = 1,
        amountRegex: String = "[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)",
        defaultEnabled: Boolean = true,
    ): String = """
        {
          "schemaVersion": $schemaVersion,
          "rulesVersion": $rulesVersion,
          "apps": [{
            "id": "example_wallet",
            "packageName": "com.example.wallet",
            "displayName": "示例钱包",
            "defaultEnabled": $defaultEnabled,
            "activityIncludes": ["PaymentDetail"],
            "rules": [{
              "id": "example_expense_v1",
              "transactionType": "expense",
              "requiredAnchors": ["交易完成"],
              "anyAnchors": [],
              "excludedAnchors": ["风险阻止"],
              "amount": {
                "labels": ["金额"],
                "regexes": [${jsonString(amountRegex)}],
                "currencyLabels": ["¥", "￥"],
                "standaloneRegex": "([0-9]{1,7}(?:\\.[0-9]{1,2})?)",
                "maxAnchorDistancePx": 1800,
                "maxCurrencyDistancePx": 300
              },
              "merchant": {
                "regexes": ["商户=(.+)"],
                "beforeAmountNodes": 2
              },
              "note": {
                "labels": ["备注"],
                "fallbackToMerchant": true
              },
              "paymentMethod": {
                "regexes": ["(银行卡\\([0-9]{4}\\))"]
              },
              "transactionTime": {"labels": ["交易时间"]},
              "orderId": {"labels": ["交易号"]}
            }]
          }]
        }
    """.trimIndent()

    private fun withBuiltInApps(candidate: RecognitionRuleSet): RecognitionRuleSet = candidate.copy(
        apps = BuiltInRecognitionRules.value.apps + candidate.apps,
    )

    private fun containerRulesJson(): String = """
        {
          "schemaVersion": 2,
          "rulesVersion": 10,
          "apps": [{
            "id": "example_shop",
            "packageName": "com.example.shop",
            "displayName": "示例商城",
            "defaultEnabled": false,
            "activityIncludes": [],
            "rules": [{
              "id": "order_detail_v2",
              "transactionType": "expense",
              "requiredAnchors": [],
              "anyAnchors": [],
              "excludedAnchors": [],
              "pageMatch": {
                "all": [{"viewIdEquals": ["com.example.shop:id/order_detail"]}],
                "none": [{"textContains": ["交易关闭"]}]
              },
              "scope": {"selector": {"viewIdContains": ["order_card"]}},
              "amount": {
                "regexes": ["¥([0-9]+(?:\\.[0-9]{1,2})?)"],
                "node": {
                  "selector": {"textRegexes": ["¥[0-9]+(?:\\.[0-9]{1,2})?"]},
                  "relativeTo": {"textEquals": ["实付款"]},
                  "relation": "followingSibling"
                }
              },
              "merchant": {}, "note": {}, "paymentMethod": {},
              "transactionTime": {}, "orderId": {}
            }]
          }]
        }
    """.trimIndent()

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                else -> append(char)
            }
        }
        append('"')
    }

    private fun snapshot(
        packageName: String,
        activityName: String,
        vararg texts: String,
    ): AccessibilityPageSnapshot = AccessibilityPageSnapshot(
        packageName = packageName,
        activityName = activityName,
        appVersion = "1.0",
        nodes = texts.mapIndexed { index, text ->
            NormalizedNode(
                index = index,
                parentIndex = null,
                depth = 0,
                text = text,
                contentDescription = null,
                viewId = null,
                className = "android.widget.TextView",
                bounds = NodeBounds(0, index * 100, 500, index * 100 + 80),
            )
        },
    )
}
