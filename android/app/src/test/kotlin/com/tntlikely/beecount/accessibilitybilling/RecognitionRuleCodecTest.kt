package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRuleCodecTest {
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
    fun `remote new apps require explicit user enablement`() {
        val enabledNewApp = RecognitionRuleCodec.parse(
            validRulesJson(rulesVersion = 2, defaultEnabled = true),
        )
        val disabledNewApp = RecognitionRuleCodec.parse(
            validRulesJson(rulesVersion = 2, defaultEnabled = false),
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
    fun `built in rules remain available offline`() {
        val builtIn = BuiltInRecognitionRules.value

        assertNotNull(builtIn.findApp(AccessibilityBillingPreferences.WECHAT_PACKAGE))
        assertNotNull(builtIn.findApp(AccessibilityBillingPreferences.ALIPAY_PACKAGE))
        assertEquals(2, builtIn.apps.size)
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
