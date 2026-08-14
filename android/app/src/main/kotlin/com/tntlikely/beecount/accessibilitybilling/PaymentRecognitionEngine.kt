package com.tntlikely.beecount.accessibilitybilling

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

internal data class PaymentRecognition(
    val id: String = UUID.randomUUID().toString(),
    val sourcePackage: String,
    val sourceApp: String,
    val activityName: String?,
    val appVersion: String?,
    val transactionType: String,
    val amount: String,
    val currency: String = "CNY",
    val merchant: String?,
    val note: String?,
    val paymentMethod: String?,
    val transactionTime: String?,
    val orderFingerprint: String?,
    val ruleId: String,
    val confidence: Double,
    val detectedAt: Long = System.currentTimeMillis(),
    val pageFingerprint: String,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "id" to id,
        "sourcePackage" to sourcePackage,
        "sourceApp" to sourceApp,
        "activityName" to activityName,
        "appVersion" to appVersion,
        "transactionType" to transactionType,
        "amount" to amount,
        "currency" to currency,
        "merchant" to merchant,
        "note" to note,
        "paymentMethod" to paymentMethod,
        "transactionTime" to transactionTime,
        "orderFingerprint" to orderFingerprint,
        "ruleId" to ruleId,
        "confidence" to confidence,
        "detectedAt" to detectedAt,
        "pageFingerprint" to pageFingerprint,
    )

    fun withAutoExtractNote(enabled: Boolean): PaymentRecognition =
        if (enabled) this else copy(note = null)
}

internal class PaymentRecognitionEngine(
    private val ruleSetProvider: () -> RecognitionRuleSet = { BuiltInRecognitionRules.value },
) {
    internal enum class PageState {
        BILL_CANDIDATE,
        NON_BILL_PAGE,
    }

    private val regexCache = mutableMapOf<String, Regex>()

    fun recognize(snapshot: AccessibilityPageSnapshot): PaymentRecognition? {
        val app = ruleSetProvider().findApp(snapshot.packageName) ?: return null
        if (app.activityIncludes.isNotEmpty() && app.activityIncludes.none { include ->
                snapshot.activityName.orEmpty().contains(include, ignoreCase = true)
            }
        ) return null

        return app.pageRules.firstNotNullOfOrNull { rule -> evaluate(snapshot, app, rule) }
    }

    fun classify(snapshot: AccessibilityPageSnapshot): PageState {
        val app = ruleSetProvider().findApp(snapshot.packageName) ?: return PageState.NON_BILL_PAGE
        val texts = snapshot.nodes.map { it.searchableText }.filter { it.isNotBlank() }
        if (texts.isEmpty() ||
            (texts.size <= MAX_LOADING_PLACEHOLDER_NODES &&
                texts.any { text -> LOADING_PAGE_TEXTS.any(text::contains) })
        ) {
            return PageState.BILL_CANDIDATE
        }
        val candidateAnchors = app.pageCandidateAnchors + app.pageRules.flatMap { rule ->
            rule.requiredAnchors + rule.anyAnchors + rule.amount.labels +
                rule.transactionTime.labels + rule.paymentMethod.labels
        }
        return if (candidateAnchors.any { anchor -> texts.any { it.contains(anchor) } }) {
            PageState.BILL_CANDIDATE
        } else {
            PageState.NON_BILL_PAGE
        }
    }

    private fun evaluate(
        snapshot: AccessibilityPageSnapshot,
        app: AppRecognitionRule,
        rule: PageRecognitionRule,
    ): PaymentRecognition? {
        val pageNodes = snapshot.nodes
        if (!matchesPage(pageNodes, rule.pageMatch)) return null
        val scopeNodes = resolveScope(pageNodes, rule.scope) ?: return null
        val textNodes = scopeNodes.filter { it.searchableText.isNotBlank() }
        val texts = textNodes.map { it.searchableText }
        val excluded = SYSTEM_EXCLUDED_PHRASES + rule.excludedAnchors
        if (texts.any { text -> excluded.any(text::contains) }) return null
        if (rule.requiredAnchors.any { anchor -> texts.none { it.contains(anchor) } }) return null
        if (rule.anyAnchors.isNotEmpty() && rule.anyAnchors.none { anchor ->
                texts.any { it.contains(anchor) }
            }
        ) return null
        val anchors = rule.anyAnchors + rule.requiredAnchors
        val nodes = scopeNodes.filter { it.searchableText.isNotBlank() || it.viewId != null }
        val anchorNode = nodes.firstOrNull { node -> anchors.any(node.searchableText::contains) }
            ?: scopeNodes.firstOrNull()
            ?: return null
        val amountCandidate = findAmount(nodes, anchorNode, rule.amount, pageNodes) ?: return null
        val allLabels = listOf(
            rule.merchant,
            rule.note,
            rule.paymentMethod,
            rule.transactionTime,
            rule.orderId,
        ).flatMap { it.labels } + rule.amount.labels
        val merchant = extractField(nodes, amountCandidate, rule.merchant, allLabels, anchors, excluded, pageNodes)
        val note = extractField(nodes, amountCandidate, rule.note, allLabels, anchors, excluded, pageNodes)
            ?: merchant.takeIf { rule.note.fallbackToMerchant }
        val paymentMethod = extractField(
            nodes,
            amountCandidate,
            rule.paymentMethod,
            allLabels,
            anchors,
            excluded,
            pageNodes,
        )
        val transactionTime = extractField(
            nodes,
            amountCandidate,
            rule.transactionTime,
            allLabels,
            anchors,
            excluded,
            pageNodes,
        )
        val rawOrderId = extractField(
            nodes,
            amountCandidate,
            rule.orderId,
            allLabels,
            anchors,
            excluded,
            pageNodes,
        )
        val orderFingerprint = rawOrderId?.let { sha256("${snapshot.packageName}|$it") }

        var confidence = 0.84
        if (merchant != null) confidence += 0.05
        if (paymentMethod != null) confidence += 0.04
        if (transactionTime != null) confidence += 0.03
        if (orderFingerprint != null) confidence += 0.04

        val pageFingerprint = sha256(buildString {
            append("package=").append(snapshot.packageName)
            append("|type=").append(rule.transactionType)
            append("|amount=").append(amountCandidate.amount)
            append("|merchant=").append(merchant.orEmpty())
            append("|transactionTime=").append(transactionTime.orEmpty())
            append("|orderFingerprint=").append(orderFingerprint.orEmpty())
        })
        return PaymentRecognition(
            sourcePackage = snapshot.packageName,
            sourceApp = app.id,
            activityName = snapshot.activityName,
            appVersion = snapshot.appVersion,
            transactionType = rule.transactionType,
            amount = amountCandidate.amount,
            merchant = merchant,
            note = note,
            paymentMethod = paymentMethod,
            transactionTime = transactionTime,
            orderFingerprint = orderFingerprint,
            ruleId = rule.id,
            confidence = confidence.coerceAtMost(1.0),
            pageFingerprint = pageFingerprint,
        )
    }

    private fun findAmount(
        nodes: List<NormalizedNode>,
        anchor: NormalizedNode,
        spec: AmountExtractionRule,
        pageNodes: List<NormalizedNode>,
    ): AmountCandidate? {
        spec.node?.let { relative ->
            val matches = resolveRelativeNodes(nodes, relative, pageNodes) ?: return null
            val candidates = matches.flatMap { node ->
                val position = nodes.indexOfFirst { it.index == node.index }
                extractAmounts(node.searchableText, spec).map { amount ->
                    AmountCandidate(amount, node, position, decorated = true)
                }
            }.distinctBy { it.amount to it.node.index }
            return candidates.singleOrNull()
        }
        val candidates = mutableListOf<AmountCandidate>()
        val compiled = spec.regexes.map(::compiledRegex)
        val standalone = spec.standaloneRegex?.let(::compiledRegex)
        val currencyNodes = nodes.filter { it.searchableText in spec.currencyLabels }

        nodes.forEachIndexed { position, node ->
            val text = node.searchableText.replace(",", "")
            compiled.forEach { regex ->
                regex.find(text)?.capturedValue()?.let(::normalizedAmount)?.let { amount ->
                    candidates += AmountCandidate(amount, node, position, decorated = true)
                }
            }
            extractLabelValue(nodes, position, spec.labels, emptyList())
                ?.let { raw -> findNumericValue(raw, compiled) }
                ?.let(::normalizedAmount)
                ?.let { amount -> candidates += AmountCandidate(amount, node, position, decorated = true) }

            if (standalone != null && currencyNodes.any { currency ->
                    abs(currency.bounds.centerY - node.bounds.centerY) <= spec.maxCurrencyDistancePx
                }
            ) {
                standalone.matchEntire(text)?.capturedValue()?.let(::normalizedAmount)?.let { amount ->
                    candidates += AmountCandidate(amount, node, position, decorated = false)
                }
            }
        }
        return candidates
            .distinctBy { it.amount to it.node.index }
            .filter { abs(it.node.bounds.centerY - anchor.bounds.centerY) <= spec.maxAnchorDistancePx }
            .minByOrNull { candidate ->
                abs(candidate.node.bounds.centerY - anchor.bounds.centerY) +
                    if (candidate.decorated) 0 else 2000
            }
    }

    private fun extractField(
        nodes: List<NormalizedNode>,
        amount: AmountCandidate,
        spec: FieldExtractionRule,
        allLabels: List<String>,
        anchors: List<String>,
        excluded: List<String>,
        pageNodes: List<NormalizedNode>,
    ): String? {
        spec.node?.let { relative ->
            val matches = resolveRelativeNodes(nodes, relative, pageNodes) ?: return null
            val values = matches.mapNotNull { node -> extractNodeValue(node, spec) }.distinct()
            return values.singleOrNull()?.take(MAX_FIELD_LENGTH)
        }
        nodes.forEachIndexed { index, _ ->
            extractLabelValue(nodes, index, spec.labels, allLabels)?.let { return it.take(MAX_FIELD_LENGTH) }
        }
        spec.regexes.map(::compiledRegex).forEach { regex ->
            nodes.forEach { node ->
                regex.find(node.searchableText)?.capturedValue()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    return it.take(MAX_FIELD_LENGTH)
                }
            }
        }
        val from = maxOf(0, amount.position - spec.beforeAmountNodes)
        val to = minOf(nodes.lastIndex, amount.position + spec.afterAmountNodes)
        if (from <= to && (spec.beforeAmountNodes > 0 || spec.afterAmountNodes > 0)) {
            val positions = buildList {
                for (index in amount.position - 1 downTo from) add(index)
                for (index in amount.position + 1..to) add(index)
            }
            positions.forEach { index ->
                val text = nodes[index].searchableText.trim()
                if (isPlausibleNearbyValue(text, allLabels, anchors, excluded)) {
                    return text.take(MAX_FIELD_LENGTH)
                }
            }
        }
        return null
    }

    private fun matchesPage(nodes: List<NormalizedNode>, spec: PageNodeMatchRule): Boolean {
        if (spec.all.any { selector -> nodes.none { matchesSelector(it, selector) } }) return false
        if (spec.any.isNotEmpty() && spec.any.none { selector -> nodes.any { matchesSelector(it, selector) } }) {
            return false
        }
        return spec.none.none { selector -> nodes.any { matchesSelector(it, selector) } }
    }

    private fun resolveScope(
        pageNodes: List<NormalizedNode>,
        scope: ContainerScopeRule?,
    ): List<NormalizedNode>? {
        if (scope == null) return pageNodes
        val byIndex = pageNodes.associateBy { it.index }
        val containers = when {
            scope.anchor != null -> pageNodes
                .filter { matchesSelector(it, scope.anchor) }
                .mapNotNull { node -> ancestor(node, scope.ancestorLevels, byIndex) }
                .filter { container -> scope.selector?.let { matchesSelector(container, it) } ?: true }
            scope.selector != null -> pageNodes.filter { matchesSelector(it, scope.selector) }
            else -> emptyList()
        }.distinctBy { it.index }
        val container = containers.singleOrNull() ?: return null
        return pageNodes.filter { it.index == container.index || isDescendantOf(it, container, byIndex) }
    }

    private fun ancestor(
        node: NormalizedNode,
        levels: Int,
        byIndex: Map<Int, NormalizedNode>,
    ): NormalizedNode? {
        var current = node
        repeat(levels) {
            current = current.parentIndex?.let(byIndex::get) ?: return null
        }
        return current
    }

    private fun resolveRelativeNodes(
        scopeNodes: List<NormalizedNode>,
        spec: RelativeNodeRule,
        pageNodes: List<NormalizedNode>,
    ): List<NormalizedNode>? {
        val targets = scopeNodes.filter { matchesSelector(it, spec.selector) }
        val references = spec.relativeTo?.let { selector ->
            scopeNodes.filter { matchesSelector(it, selector) }
        }.orEmpty()
        val byIndex = pageNodes.associateBy { it.index }
        val related = if (spec.relation == "any") targets else targets.filter { target ->
            references.any { reference -> isRelated(target, reference, spec.relation, byIndex) }
        }
        val distinct = related.distinctBy { it.index }
        if (distinct.isEmpty() || (spec.requireUnique && distinct.size != 1)) return null
        return distinct
    }

    private fun isRelated(
        target: NormalizedNode,
        reference: NormalizedNode,
        relation: String,
        byIndex: Map<Int, NormalizedNode>,
    ): Boolean = when (relation) {
        "self" -> target.index == reference.index
        "child" -> target.parentIndex == reference.index
        "descendant" -> isDescendantOf(target, reference, byIndex)
        "sibling" -> target.index != reference.index && target.parentIndex == reference.parentIndex
        "followingSibling" -> target.index > reference.index && target.parentIndex == reference.parentIndex
        "following" -> target.index > reference.index
        "ancestor" -> isDescendantOf(reference, target, byIndex)
        else -> true
    }

    private fun isDescendantOf(
        node: NormalizedNode,
        ancestor: NormalizedNode,
        byIndex: Map<Int, NormalizedNode>,
    ): Boolean {
        var parent = node.parentIndex
        while (parent != null) {
            if (parent == ancestor.index) return true
            parent = byIndex[parent]?.parentIndex
        }
        return false
    }

    private fun matchesSelector(node: NormalizedNode, selector: NodeSelector): Boolean {
        fun matchesValue(
            value: String?,
            equals: List<String>,
            contains: List<String>,
            regexes: List<String>,
        ): Boolean {
            if (equals.isEmpty() && contains.isEmpty() && regexes.isEmpty()) return true
            val actual = value ?: return false
            return (equals.isEmpty() || equals.any(actual::equals)) &&
                (contains.isEmpty() || contains.any(actual::contains)) &&
                (regexes.isEmpty() || regexes.any { compiledRegex(it).containsMatchIn(actual) })
        }
        return matchesValue(node.text, selector.textEquals, selector.textContains, selector.textRegexes) &&
            matchesValue(
                node.contentDescription,
                selector.descriptionEquals,
                selector.descriptionContains,
                selector.descriptionRegexes,
            ) &&
            matchesValue(node.viewId, selector.viewIdEquals, selector.viewIdContains, selector.viewIdRegexes) &&
            (selector.classNameEquals.isEmpty() || selector.classNameEquals.any { it == node.className })
    }

    private fun extractAmounts(raw: String, spec: AmountExtractionRule): List<String> {
        val text = raw.replace(",", "")
        return spec.regexes.map(::compiledRegex).flatMap { regex ->
            regex.findAll(text).mapNotNull { match ->
                match.capturedValue().let(::normalizedAmount)
            }
        }.distinct()
    }

    private fun extractNodeValue(node: NormalizedNode, spec: FieldExtractionRule): String? {
        val raw = node.searchableText.trim()
        spec.regexes.map(::compiledRegex).forEach { regex ->
            regex.find(raw)?.capturedValue()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return raw.takeIf { it.isNotBlank() }
    }

    private fun extractLabelValue(
        nodes: List<NormalizedNode>,
        index: Int,
        labels: List<String>,
        allLabels: List<String>,
    ): String? {
        val text = nodes[index].searchableText
        labels.forEach { label ->
            if (text == label || text == "$label：" || text == "$label:") {
                return nodes.drop(index + 1).take(4).firstOrNull { candidate ->
                    val value = candidate.searchableText
                    value.isNotBlank() && allLabels.none { isLabel(value, it) }
                }?.searchableText
            }
            listOf("$label：", "$label:").forEach { prefix ->
                if (text.startsWith(prefix)) {
                    return text.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    private fun isPlausibleNearbyValue(
        text: String,
        allLabels: List<String>,
        anchors: List<String>,
        excluded: List<String>,
    ): Boolean {
        if (text.length !in 2..80 || text.none { it.isLetter() }) return false
        if (allLabels.any { isLabel(text, it) }) return false
        if (anchors.any(text::contains) || excluded.any(text::contains)) return false
        if (NON_VALUE_TEXTS.any { it == text }) return false
        if (DEFAULT_AMOUNT_REGEXES.any { it.containsMatchIn(text.replace(",", "")) }) return false
        return true
    }

    private fun isLabel(text: String, label: String): Boolean =
        text == label || text == "$label：" || text == "$label:"

    private fun findNumericValue(raw: String, regexes: List<Regex>): String? {
        regexes.forEach { regex -> regex.find(raw.replace(",", ""))?.capturedValue()?.let { return it } }
        return DEFAULT_NUMBER_REGEX.find(raw.replace(",", ""))?.capturedValue()
    }

    private fun MatchResult.capturedValue(): String =
        groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: value

    private fun compiledRegex(pattern: String): Regex = regexCache.getOrPut(pattern) { Regex(pattern) }

    private fun normalizedAmount(raw: String): String? = runCatching {
        val cleaned = raw.replace(",", "").trim()
        val value = BigDecimal(cleaned)
        if (value <= BigDecimal.ZERO || value > MAX_AMOUNT || value.scale() > 2) return null
        value.stripTrailingZeros().toPlainString()
    }.getOrNull()

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }
    }

    private data class AmountCandidate(
        val amount: String,
        val node: NormalizedNode,
        val position: Int,
        val decorated: Boolean,
    )

    companion object {
        private const val MAX_FIELD_LENGTH = 200
        private const val MAX_LOADING_PLACEHOLDER_NODES = 3
        private val LOADING_PAGE_TEXTS = listOf("加载中", "正在加载", "请稍候", "请稍后")
        private val MAX_AMOUNT = BigDecimal("9999999.99")
        private val DEFAULT_NUMBER_REGEX = Regex("([0-9]{1,7}(?:\\.[0-9]{1,2})?)")
        private val DEFAULT_AMOUNT_REGEXES = listOf(
            Regex("[¥￥]\\s*[0-9]{1,7}(?:\\.[0-9]{1,2})?"),
            Regex("[0-9]{1,7}(?:\\.[0-9]{1,2})?\\s*元"),
        )
        private val NON_VALUE_TEXTS = listOf(
            "账单详情", "交易详情", "订单详情", "支出", "收入", "支付结果", "交易结果",
        )
        private val SYSTEM_EXCLUDED_PHRASES = listOf(
            "支付失败", "付款失败", "交易失败",
            "支付处理中", "付款处理中", "交易处理中", "处理中",
            "等待支付", "待支付", "未支付",
            "支付已取消", "付款已取消", "已取消", "交易关闭",
            "退款成功", "退款中", "退款处理中", "已退款",
            "输入支付密码", "支付密码", "验证支付密码", "确认付款", "立即付款",
        )
    }
}
