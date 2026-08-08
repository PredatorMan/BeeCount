package com.tntlikely.beecount.accessibilitybilling

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal data class NodeBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerY: Int get() = top + ((bottom - top) / 2)
}

internal data class NormalizedNode(
    val index: Int,
    val parentIndex: Int?,
    val depth: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val bounds: NodeBounds,
) {
    val searchableText: String
        get() = listOfNotNull(text, contentDescription)
            .joinToString(" ")
            .trim()
}

internal data class AccessibilityPageSnapshot(
    val packageName: String,
    val activityName: String?,
    val appVersion: String?,
    val nodes: List<NormalizedNode>,
)

internal object AccessibilityNodeNormalizer {
    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 30

    fun normalize(
        root: AccessibilityNodeInfo,
        packageName: String,
        activityName: String?,
        appVersion: String?,
    ): AccessibilityPageSnapshot {
        val nodes = ArrayList<NormalizedNode>()
        visit(root, parentIndex = null, depth = 0, nodes = nodes)
        return AccessibilityPageSnapshot(packageName, activityName, appVersion, nodes)
    }

    private fun visit(
        node: AccessibilityNodeInfo,
        parentIndex: Int?,
        depth: Int,
        nodes: MutableList<NormalizedNode>,
    ) {
        if (nodes.size >= MAX_NODES || depth > MAX_DEPTH) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val index = nodes.size
        nodes += NormalizedNode(
            index = index,
            parentIndex = parentIndex,
            depth = depth,
            text = normalizeText(node.text),
            contentDescription = normalizeText(node.contentDescription),
            viewId = node.viewIdResourceName?.take(200),
            className = node.className?.toString()?.take(200),
            bounds = NodeBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
        )

        for (childIndex in 0 until node.childCount) {
            if (nodes.size >= MAX_NODES) break
            val child = node.getChild(childIndex) ?: continue
            try {
                visit(child, parentIndex = index, depth = depth + 1, nodes = nodes)
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
    }

    private fun normalizeText(value: CharSequence?): String? {
        val normalized = value
            ?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(500)
        return normalized?.takeIf { it.isNotEmpty() }
    }
}
