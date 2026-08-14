package com.tntlikely.beecount.accessibilitybilling

internal data class RecognitionRuleSet(
    val schemaVersion: Int,
    val rulesVersion: Int,
    val apps: List<AppRecognitionRule>,
) {
    fun findApp(packageName: String): AppRecognitionRule? =
        apps.firstOrNull { it.packageName == packageName }
}

internal data class AppRecognitionRule(
    val id: String,
    val packageName: String,
    val displayName: String,
    val defaultEnabled: Boolean,
    val activityIncludes: List<String>,
    val pageCandidateAnchors: List<String> = emptyList(),
    val pageRules: List<PageRecognitionRule>,
)

internal data class PageRecognitionRule(
    val id: String,
    val transactionType: String,
    val requiredAnchors: List<String>,
    val anyAnchors: List<String>,
    val excludedAnchors: List<String>,
    val amount: AmountExtractionRule,
    val merchant: FieldExtractionRule,
    val note: FieldExtractionRule,
    val paymentMethod: FieldExtractionRule,
    val transactionTime: FieldExtractionRule,
    val orderId: FieldExtractionRule,
    val pageMatch: PageNodeMatchRule = PageNodeMatchRule(),
    val scope: ContainerScopeRule? = null,
)

internal data class PageNodeMatchRule(
    val all: List<NodeSelector> = emptyList(),
    val any: List<NodeSelector> = emptyList(),
    val none: List<NodeSelector> = emptyList(),
)

internal data class NodeSelector(
    val textEquals: List<String> = emptyList(),
    val textContains: List<String> = emptyList(),
    val textRegexes: List<String> = emptyList(),
    val descriptionEquals: List<String> = emptyList(),
    val descriptionContains: List<String> = emptyList(),
    val descriptionRegexes: List<String> = emptyList(),
    val viewIdEquals: List<String> = emptyList(),
    val viewIdContains: List<String> = emptyList(),
    val viewIdRegexes: List<String> = emptyList(),
    val classNameEquals: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = listOf(
        textEquals,
        textContains,
        textRegexes,
        descriptionEquals,
        descriptionContains,
        descriptionRegexes,
        viewIdEquals,
        viewIdContains,
        viewIdRegexes,
        classNameEquals,
    ).all { it.isEmpty() }
}

internal data class ContainerScopeRule(
    val selector: NodeSelector? = null,
    val anchor: NodeSelector? = null,
    val ancestorLevels: Int = 0,
)

internal data class RelativeNodeRule(
    val selector: NodeSelector,
    val relativeTo: NodeSelector? = null,
    val relation: String = "any",
    val requireUnique: Boolean = true,
)

internal data class AmountExtractionRule(
    val labels: List<String> = emptyList(),
    val regexes: List<String>,
    val currencyLabels: List<String> = listOf("¥", "￥", "人民币"),
    val standaloneRegex: String? = null,
    val maxAnchorDistancePx: Int = 1800,
    val maxCurrencyDistancePx: Int = 300,
    val node: RelativeNodeRule? = null,
)

internal data class FieldExtractionRule(
    val labels: List<String> = emptyList(),
    val regexes: List<String> = emptyList(),
    val beforeAmountNodes: Int = 0,
    val afterAmountNodes: Int = 0,
    val fallbackToMerchant: Boolean = false,
    val node: RelativeNodeRule? = null,
)

internal object BuiltInRecognitionRules {
    private val unsafe = listOf(
        "支付失败", "付款失败", "交易失败",
        "支付处理中", "付款处理中", "交易处理中", "处理中",
        "等待支付", "等待付款", "等待买家付款", "待支付", "待付款", "未支付", "未付款",
        "支付已取消", "付款已取消", "已取消", "交易关闭", "已关闭",
        "退款成功", "退款中", "退款处理中", "已退款",
        "输入支付密码", "请输入支付密码", "支付密码", "验证支付密码", "确认付款", "立即付款",
    )
    private val amount = AmountExtractionRule(
        labels = listOf("金额", "支付金额", "付款金额", "交易金额"),
        regexes = listOf(
            "[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)",
            "([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元",
        ),
        standaloneRegex = "([0-9]{1,7}(?:\\.[0-9]{1,2})?)",
    )
    private val merchant = FieldExtractionRule(
        labels = listOf("商户", "商家", "收款方", "收款人", "交易对象", "商品"),
    )
    private val note = FieldExtractionRule(
        labels = listOf("商品说明", "收款方备注", "付款说明", "备注"),
        fallbackToMerchant = true,
    )
    private val paymentMethod = FieldExtractionRule(
        labels = listOf("付款方式", "支付方式", "扣款方式"),
    )
    private val transactionTime = FieldExtractionRule(
        labels = listOf("支付时间", "付款时间", "交易时间"),
    )
    private val orderId = FieldExtractionRule(
        labels = listOf("订单号", "交易号", "商户单号"),
    )

    val value = RecognitionRuleSet(
        schemaVersion = RecognitionRuleCodec.SUPPORTED_SCHEMA_VERSION,
        rulesVersion = 6,
        apps = listOf(
            app(
                id = "wechat",
                packageName = AccessibilityBillingPreferences.WECHAT_PACKAGE,
                displayName = "微信",
                successAnchors = listOf("支付成功", "付款成功"),
            ),
            app(
                id = "alipay",
                packageName = AccessibilityBillingPreferences.ALIPAY_PACKAGE,
                displayName = "支付宝",
                successAnchors = listOf("支付成功", "付款成功", "交易成功"),
                merchant = merchant.copy(beforeAmountNodes = 3),
                leadingRules = alipayHistoricalRules(),
            ),
        ),
    )

    private fun alipayHistoricalRules(): List<PageRecognitionRule> = listOf(
        PageRecognitionRule(
            id = "alipay_historical_bill_expense_v3",
            transactionType = "expense",
            requiredAnchors = listOf("账单详情"),
            anyAnchors = listOf(
                "支付时间", "付款时间", "交易时间",
                "付款方式", "支付方式", "扣款方式",
                "交易详情", "订单号", "交易号",
            ),
            excludedAnchors = unsafe,
            amount = AmountExtractionRule(
                regexes = listOf(
                    "^支出\\s*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元$",
                ),
            ),
            merchant = merchant.copy(beforeAmountNodes = 3),
            note = note,
            paymentMethod = paymentMethod,
            transactionTime = transactionTime,
            orderId = orderId,
        ),
        PageRecognitionRule(
            id = "alipay_historical_bill_income_v3",
            transactionType = "income",
            requiredAnchors = listOf("账单详情"),
            anyAnchors = listOf(
                "收款时间", "入账时间", "到账时间", "交易时间",
                "付款方", "付款人", "交易详情", "订单号", "交易号",
            ),
            excludedAnchors = unsafe,
            amount = AmountExtractionRule(
                regexes = listOf(
                    "^收入\\s*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)\\s*元$",
                ),
            ),
            merchant = FieldExtractionRule(
                labels = listOf("付款方", "付款人", "交易对象"),
                beforeAmountNodes = 3,
            ),
            note = FieldExtractionRule(
                labels = listOf("商品说明", "付款方备注", "备注"),
                fallbackToMerchant = true,
            ),
            paymentMethod = FieldExtractionRule(),
            transactionTime = FieldExtractionRule(
                labels = listOf("收款时间", "入账时间", "到账时间", "交易时间"),
            ),
            orderId = orderId,
        ),
    )

    private fun app(
        id: String,
        packageName: String,
        displayName: String,
        successAnchors: List<String>,
        merchant: FieldExtractionRule = this.merchant,
        leadingRules: List<PageRecognitionRule> = emptyList(),
    ): AppRecognitionRule = AppRecognitionRule(
        id = id,
        packageName = packageName,
        displayName = displayName,
        defaultEnabled = true,
        activityIncludes = emptyList(),
        pageCandidateAnchors = listOf("账单详情", "交易详情", "支付时间", "付款方式"),
        pageRules = leadingRules + listOf(
            PageRecognitionRule(
                id = "${id}_income_success_v1",
                transactionType = "income",
                requiredAnchors = emptyList(),
                anyAnchors = listOf("收款成功"),
                excludedAnchors = unsafe,
                amount = amount,
                merchant = merchant,
                note = note,
                paymentMethod = paymentMethod,
                transactionTime = transactionTime,
                orderId = orderId,
            ),
            PageRecognitionRule(
                id = "${id}_payment_success_v1",
                transactionType = "expense",
                requiredAnchors = emptyList(),
                anyAnchors = successAnchors,
                excludedAnchors = unsafe,
                amount = amount,
                merchant = merchant,
                note = note,
                paymentMethod = paymentMethod,
                transactionTime = transactionTime,
                orderId = orderId,
            ),
        ),
    )
}

internal object RecognitionRuleCodec {
    const val SUPPORTED_SCHEMA_VERSION = 2
    private const val MAX_APPS = 50
    private const val MAX_RULES_PER_APP = 20
    private const val MAX_LIST_ITEMS = 50
    private const val MAX_TEXT_LENGTH = 300
    private const val MAX_REGEX_LENGTH = 500
    private const val MAX_REGEXES_PER_FIELD = 10
    private val packagePattern = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    fun parse(raw: String): RecognitionRuleSet {
        val root = StrictJsonParser.parse(raw).asObject("root")
        val schemaVersion = root.requirePositiveInt("schemaVersion")
        require(schemaVersion in 1..SUPPORTED_SCHEMA_VERSION) { "Unsupported schemaVersion: $schemaVersion" }
        val rulesVersion = root.requirePositiveInt("rulesVersion")
        val appArray = root.requireArray("apps")
        require(appArray.size in 1..MAX_APPS) { "apps must contain 1..$MAX_APPS items" }
        val apps = appArray.mapIndexed { index, value -> parseApp(value.asObject("apps[$index]")) }
        require(apps.map { it.packageName }.distinct().size == apps.size) { "Duplicate packageName" }
        require(apps.map { it.id }.distinct().size == apps.size) { "Duplicate app id" }
        return RecognitionRuleSet(schemaVersion, rulesVersion, apps)
    }

    private fun parseApp(json: Map<String, Any?>): AppRecognitionRule {
        val id = json.requireText("id")
        val packageName = json.requireText("packageName")
        require(packagePattern.matches(packageName)) { "Invalid packageName: $packageName" }
        val pageRuleArray = json.requireArray("rules")
        require(pageRuleArray.size in 1..MAX_RULES_PER_APP) { "Invalid rules count for $packageName" }
        val pageRules = pageRuleArray.mapIndexed { index, value ->
            parsePageRule(value.asObject("rules[$index]"))
        }
        require(pageRules.map { it.id }.distinct().size == pageRules.size) { "Duplicate rule id in $packageName" }
        return AppRecognitionRule(
            id = id,
            packageName = packageName,
            displayName = json.requireText("displayName"),
            defaultEnabled = json.optionalBoolean("defaultEnabled", true),
            activityIncludes = json.stringList("activityIncludes"),
            pageCandidateAnchors = json.stringList("pageCandidateAnchors"),
            pageRules = pageRules,
        )
    }

    private fun parsePageRule(json: Map<String, Any?>): PageRecognitionRule {
        val transactionType = json.requireText("transactionType")
        require(transactionType == "expense" || transactionType == "income") {
            "transactionType must be expense or income"
        }
        val required = json.stringList("requiredAnchors")
        val any = json.stringList("anyAnchors")
        val pageMatch = parsePageMatch(json.optionalObject("pageMatch"))
        require(required.isNotEmpty() || any.isNotEmpty() || pageMatch.all.isNotEmpty() || pageMatch.any.isNotEmpty()) {
            "A page rule needs anchors or pageMatch.all/pageMatch.any"
        }
        return PageRecognitionRule(
            id = json.requireText("id"),
            transactionType = transactionType,
            requiredAnchors = required,
            anyAnchors = any,
            excludedAnchors = json.stringList("excludedAnchors"),
            amount = parseAmount(json.requireObject("amount")),
            merchant = parseField(json.optionalObject("merchant")),
            note = parseField(json.optionalObject("note")),
            paymentMethod = parseField(json.optionalObject("paymentMethod")),
            transactionTime = parseField(json.optionalObject("transactionTime")),
            orderId = parseField(json.optionalObject("orderId")),
            pageMatch = pageMatch,
            scope = parseScope(json.optionalObject("scope")),
        )
    }

    private fun parsePageMatch(json: Map<String, Any?>?): PageNodeMatchRule {
        if (json == null) return PageNodeMatchRule()
        return PageNodeMatchRule(
            all = json.selectorList("all"),
            any = json.selectorList("any"),
            none = json.selectorList("none"),
        )
    }

    private fun parseScope(json: Map<String, Any?>?): ContainerScopeRule? {
        if (json == null) return null
        val selector = json.optionalObject("selector")?.let(::parseSelector)
        val anchor = json.optionalObject("anchor")?.let(::parseSelector)
        require(selector != null || anchor != null) { "scope needs selector or anchor" }
        return ContainerScopeRule(
            selector = selector,
            anchor = anchor,
            ancestorLevels = json.optionalInt("ancestorLevels", 0).also {
                require(it in 0..20) { "Invalid scope.ancestorLevels" }
            },
        )
    }

    private fun parseAmount(json: Map<String, Any?>): AmountExtractionRule {
        val regexes = json.stringList("regexes")
        require(regexes.size in 1..MAX_REGEXES_PER_FIELD) {
            "amount.regexes must contain 1..$MAX_REGEXES_PER_FIELD items"
        }
        regexes.forEach(::validateRegex)
        val standalone = json.optionalString("standaloneRegex")?.takeIf { it.isNotBlank() }
        standalone?.let(::validateRegex)
        return AmountExtractionRule(
            labels = json.stringList("labels"),
            regexes = regexes,
            currencyLabels = json.stringList("currencyLabels").ifEmpty { listOf("¥", "￥", "人民币") },
            standaloneRegex = standalone,
            maxAnchorDistancePx = json.optionalInt("maxAnchorDistancePx", 1800).also {
                require(it in 100..5000) { "Invalid maxAnchorDistancePx" }
            },
            maxCurrencyDistancePx = json.optionalInt("maxCurrencyDistancePx", 300).also {
                require(it in 20..1000) { "Invalid maxCurrencyDistancePx" }
            },
            node = parseRelativeNode(json.optionalObject("node")),
        )
    }

    private fun parseField(json: Map<String, Any?>?): FieldExtractionRule {
        if (json == null) return FieldExtractionRule()
        val regexes = json.stringList("regexes")
        require(regexes.size <= MAX_REGEXES_PER_FIELD) {
            "field.regexes cannot contain more than $MAX_REGEXES_PER_FIELD items"
        }
        regexes.forEach(::validateRegex)
        return FieldExtractionRule(
            labels = json.stringList("labels"),
            regexes = regexes,
            beforeAmountNodes = json.optionalInt("beforeAmountNodes", 0).also {
                require(it in 0..10) { "Invalid beforeAmountNodes" }
            },
            afterAmountNodes = json.optionalInt("afterAmountNodes", 0).also {
                require(it in 0..10) { "Invalid afterAmountNodes" }
            },
            fallbackToMerchant = json.optionalBoolean("fallbackToMerchant", false),
            node = parseRelativeNode(json.optionalObject("node")),
        )
    }

    private fun parseRelativeNode(json: Map<String, Any?>?): RelativeNodeRule? {
        if (json == null) return null
        val relation = json.optionalString("relation")?.trim()?.ifEmpty { "any" } ?: "any"
        require(relation in SUPPORTED_RELATIONS) { "Unsupported node relation: $relation" }
        val relativeTo = json.optionalObject("relativeTo")?.let(::parseSelector)
        require(relation == "any" || relativeTo != null) { "node.relativeTo is required for relation $relation" }
        return RelativeNodeRule(
            selector = parseSelector(json.requireObject("selector")),
            relativeTo = relativeTo,
            relation = relation,
            requireUnique = json.optionalBoolean("requireUnique", true),
        )
    }

    private fun parseSelector(json: Map<String, Any?>): NodeSelector = NodeSelector(
        textEquals = json.stringList("textEquals"),
        textContains = json.stringList("textContains"),
        textRegexes = json.regexList("textRegexes"),
        descriptionEquals = json.stringList("descriptionEquals"),
        descriptionContains = json.stringList("descriptionContains"),
        descriptionRegexes = json.regexList("descriptionRegexes"),
        viewIdEquals = json.stringList("viewIdEquals"),
        viewIdContains = json.stringList("viewIdContains"),
        viewIdRegexes = json.regexList("viewIdRegexes"),
        classNameEquals = json.stringList("classNameEquals"),
    ).also { require(!it.isEmpty()) { "Node selector cannot be empty" } }

    private fun Map<String, Any?>.selectorList(key: String): List<NodeSelector> {
        val array = this[key]?.asArray(key) ?: emptyList()
        require(array.size <= MAX_LIST_ITEMS) { "$key has too many items" }
        return array.mapIndexed { index, value -> parseSelector(value.asObject("$key[$index]")) }
    }

    private fun Map<String, Any?>.regexList(key: String): List<String> =
        stringList(key).also { regexes ->
            require(regexes.size <= MAX_REGEXES_PER_FIELD) {
                "$key cannot contain more than $MAX_REGEXES_PER_FIELD items"
            }
            regexes.forEach(::validateRegex)
        }

    private fun validateRegex(pattern: String) {
        require(pattern.length <= MAX_REGEX_LENGTH) { "Regex is too long" }
        require(!UNSAFE_REGEX_GROUP.matcher(pattern).find()) {
            "Regex lookarounds, flags, and special groups are not supported"
        }
        require(!REGEX_BACK_REFERENCE.matcher(pattern).find()) {
            "Regex back references are not supported"
        }
        require(!UNBOUNDED_GROUP_QUANTIFIER.matcher(pattern).find()) {
            "Regex groups cannot use unbounded quantifiers"
        }
        val regex = Regex(pattern)
        require(!regex.matches("")) { "Regex cannot match empty text" }
    }

    private val UNSAFE_REGEX_GROUP = java.util.regex.Pattern.compile("\\(\\?(?!:)")
    private val REGEX_BACK_REFERENCE = java.util.regex.Pattern.compile("(?<!\\\\)(?:\\\\\\\\)*\\\\[1-9]")
    private val UNBOUNDED_GROUP_QUANTIFIER = java.util.regex.Pattern.compile("\\)(?:\\*|\\+|\\{\\d+,\\})")
    private val SUPPORTED_RELATIONS = setOf(
        "any", "self", "child", "descendant", "sibling", "followingSibling", "following", "ancestor",
    )

    private fun Map<String, Any?>.requirePositiveInt(key: String): Int = requireInt(key).also {
        require(it > 0) { "$key must be positive" }
    }

    private fun Map<String, Any?>.requireText(key: String): String =
        (this[key] as? String)?.trim()?.also {
        require(it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH) { "Invalid $key" }
    } ?: throw IllegalArgumentException("$key must be a string")

    private fun Map<String, Any?>.stringList(key: String): List<String> {
        val array = this[key]?.asArray(key) ?: emptyList()
        require(array.size <= MAX_LIST_ITEMS) { "$key has too many items" }
        return array.map { value ->
            (value as? String)?.trim()?.also {
                require(it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH) { "Invalid $key item" }
            } ?: throw IllegalArgumentException("$key must contain strings")
        }
    }

    private fun Map<String, Any?>.requireInt(key: String): Int =
        (this[key] as? Long)?.let { value ->
            require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is out of range" }
            value.toInt()
        } ?: throw IllegalArgumentException("$key must be an integer")

    private fun Map<String, Any?>.optionalInt(key: String, fallback: Int): Int =
        if (containsKey(key)) requireInt(key) else fallback

    private fun Map<String, Any?>.optionalBoolean(key: String, fallback: Boolean): Boolean =
        if (!containsKey(key)) fallback
        else this[key] as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")

    private fun Map<String, Any?>.optionalString(key: String): String? =
        if (!containsKey(key) || this[key] == null) null
        else this[key] as? String ?: throw IllegalArgumentException("$key must be a string")

    private fun Map<String, Any?>.requireArray(key: String): List<Any?> =
        this[key]?.asArray(key) ?: throw IllegalArgumentException("$key must be an array")

    private fun Map<String, Any?>.requireObject(key: String): Map<String, Any?> =
        this[key]?.asObject(key) ?: throw IllegalArgumentException("$key must be an object")

    private fun Map<String, Any?>.optionalObject(key: String): Map<String, Any?>? =
        if (!containsKey(key) || this[key] == null) null else this[key].asObject(key)

    private fun Any?.asArray(path: String): List<Any?> =
        this as? List<Any?> ?: throw IllegalArgumentException("$path must be an array")

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(path: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw IllegalArgumentException("$path must be an object")
}

/** Small strict JSON parser kept platform-independent so rule validation is unit-testable. */
private object StrictJsonParser {
    private const val MAX_NESTING_DEPTH = 64

    fun parse(raw: String): Any? = Cursor(raw).parseDocument()

    private class Cursor(private val source: String) {
        private var index = 0

        fun parseDocument(): Any? {
            skipWhitespace()
            val value = parseValue(depth = 0)
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON content" }
            return value
        }

        private fun parseValue(depth: Int): Any? {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            require(depth <= MAX_NESTING_DEPTH) { "JSON nesting is too deep" }
            return when (source[index]) {
                '{' -> parseObject(depth)
                '[' -> parseArray(depth)
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected JSON token at $index")
            }
        }

        private fun parseObject(depth: Int): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                val key = parseString()
                require(!result.containsKey(key)) { "Duplicate JSON key: $key" }
                skipWhitespace()
                expect(':')
                result[key] = parseValue(depth + 1)
                skipWhitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun parseArray(depth: Int): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                result += parseValue(depth + 1)
                skipWhitespace()
                if (consume(']')) return result
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val char = source[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < source.length) { "Invalid JSON escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= source.length) { "Invalid unicode escape" }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid unicode escape")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape: $escaped")
                        }
                    }
                    else -> {
                        require(char.code >= 0x20) { "Control character in JSON string" }
                        result.append(char)
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun parseNumber(): Any {
            val start = index
            if (source[index] == '-') index++
            require(index < source.length) { "Invalid JSON number" }
            if (source[index] == '0') index++ else {
                require(source[index] in '1'..'9') { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            var decimal = false
            if (index < source.length && source[index] == '.') {
                decimal = true
                index++
                require(index < source.length && source[index].isDigit()) { "Invalid JSON number" }
                while (index < source.length && source[index].isDigit()) index++
            }
            if (index < source.length && source[index].lowercaseChar() == 'e') {
                decimal = true
                index++
                if (index < source.length && source[index] in listOf('+', '-')) index++
                require(index < source.length && source[index].isDigit()) { "Invalid JSON exponent" }
                while (index < source.length && source[index].isDigit()) index++
            }
            val token = source.substring(start, index)
            return (if (decimal) token.toDoubleOrNull() else token.toLongOrNull())
                ?: throw IllegalArgumentException("Invalid JSON number")
        }

        private fun <T> parseLiteral(literal: String, value: T): T {
            require(source.regionMatches(index, literal, 0, literal.length)) { "Invalid JSON literal" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index] in listOf(' ', '\n', '\r', '\t')) index++
        }

        private fun expect(char: Char) {
            require(index < source.length && source[index] == char) { "Expected '$char' at $index" }
            index++
        }

        private fun consume(char: Char): Boolean {
            if (index < source.length && source[index] == char) {
                index++
                return true
            }
            return false
        }
    }
}
