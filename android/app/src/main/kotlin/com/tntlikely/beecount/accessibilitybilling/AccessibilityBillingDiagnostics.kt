package com.tntlikely.beecount.accessibilitybilling

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object AccessibilityBillingDiagnostics {
    @Volatile
    private var latestSnapshot: AccessibilityPageSnapshot? = null

    fun update(snapshot: AccessibilityPageSnapshot) {
        latestSnapshot = snapshot
    }

    fun saveLatest(context: Context): String? {
        val snapshot = latestSnapshot ?: return null
        val directory = File(context.filesDir, "accessibility_billing/diagnostics").apply {
            if (!exists() && !mkdirs()) return null
        }
        val target = File(directory, "snapshot_${System.currentTimeMillis()}.json")
        target.writeText(snapshotToJson(snapshot).toString(2), Charsets.UTF_8)
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith("snapshot_") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_SNAPSHOTS)
            .forEach { runCatching { it.delete() } }
        return target.absolutePath
    }

    private fun snapshotToJson(snapshot: AccessibilityPageSnapshot): JSONObject =
        JSONObject().apply {
            put("packageName", snapshot.packageName)
            putNullable("activityName", snapshot.activityName)
            putNullable("appVersion", snapshot.appVersion)
            put("capturedAt", System.currentTimeMillis())
            put("nodes", JSONArray().apply {
                snapshot.nodes.forEach { node ->
                    put(JSONObject().apply {
                        put("index", node.index)
                        putNullable("parentIndex", node.parentIndex)
                        put("depth", node.depth)
                        putNullable("text", node.text?.let(::redactText))
                        putNullable(
                            "contentDescription",
                            node.contentDescription?.let(::redactText),
                        )
                        putNullable("viewId", node.viewId)
                        putNullable("className", node.className)
                        put("bounds", JSONObject().apply {
                            put("left", node.bounds.left)
                            put("top", node.bounds.top)
                            put("right", node.bounds.right)
                            put("bottom", node.bounds.bottom)
                        })
                    })
                }
            })
        }

    internal fun redactText(value: String): String {
        return value.replace(LONG_NUMBER_REGEX) { match ->
            val digits = match.value.filter(Char::isDigit)
            val suffix = digits.takeLast(4)
            "[已脱敏:$suffix]"
        }
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    private const val MAX_SNAPSHOTS = 5
    private val LONG_NUMBER_REGEX = Regex("(?<![0-9])(?:[0-9][ -]?){6,}(?![0-9])")
}
