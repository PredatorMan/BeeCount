package com.tntlikely.beecount.accessibilitybilling

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

internal class RecognitionEventStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(recognition: PaymentRecognition) {
        val pending = readPendingInternal().toMutableList()
        pending.removeAll { it.id == recognition.id }
        pending.add(0, recognition)
        while (pending.size > MAX_PENDING) pending.removeAt(pending.lastIndex)

        preferences.edit()
            .putString(KEY_PENDING, pendingToJson(pending).toString())
            .remove(KEY_SEEN)
            .apply()
        listeners.forEach { it.onRecognitionQueued(recognition) }
    }

    @Synchronized
    fun pending(): List<PaymentRecognition> = readPendingInternal()

    @Synchronized
    fun acknowledge(id: String): Boolean {
        val pending = readPendingInternal().toMutableList()
        val removed = pending.removeAll { it.id == id }
        if (removed) preferences.edit().putString(KEY_PENDING, pendingToJson(pending).toString()).apply()
        return removed
    }

    @Synchronized
    fun clearPending() {
        preferences.edit().remove(KEY_PENDING).apply()
    }

    private fun readPendingInternal(): List<PaymentRecognition> {
        val raw = preferences.getString(KEY_PENDING, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    recognitionFromJson(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun pendingToJson(items: List<PaymentRecognition>): JSONArray {
        return JSONArray().apply { items.forEach { put(recognitionToJson(it)) } }
    }

    private fun recognitionToJson(value: PaymentRecognition): JSONObject = JSONObject().apply {
        put("id", value.id)
        put("sourcePackage", value.sourcePackage)
        put("sourceApp", value.sourceApp)
        putNullable("activityName", value.activityName)
        putNullable("appVersion", value.appVersion)
        put("transactionType", value.transactionType)
        put("amount", value.amount)
        put("currency", value.currency)
        putNullable("merchant", value.merchant)
        putNullable("note", value.note)
        putNullable("paymentMethod", value.paymentMethod)
        putNullable("transactionTime", value.transactionTime)
        putNullable("orderFingerprint", value.orderFingerprint)
        put("ruleId", value.ruleId)
        put("confidence", value.confidence)
        put("detectedAt", value.detectedAt)
        put("pageFingerprint", value.pageFingerprint)
    }

    private fun recognitionFromJson(json: JSONObject): PaymentRecognition? = runCatching {
        PaymentRecognition(
            id = json.getString("id"),
            sourcePackage = json.getString("sourcePackage"),
            sourceApp = json.getString("sourceApp"),
            activityName = json.nullableString("activityName"),
            appVersion = json.nullableString("appVersion"),
            transactionType = json.getString("transactionType"),
            amount = json.getString("amount"),
            currency = json.optString("currency", "CNY"),
            merchant = json.nullableString("merchant"),
            note = json.nullableString("note"),
            paymentMethod = json.nullableString("paymentMethod"),
            transactionTime = json.nullableString("transactionTime"),
            orderFingerprint = json.nullableString("orderFingerprint"),
            ruleId = json.getString("ruleId"),
            confidence = json.getDouble("confidence"),
            detectedAt = json.getLong("detectedAt"),
            pageFingerprint = json.getString("pageFingerprint"),
        )
    }.getOrNull()

    private fun JSONObject.putNullable(key: String, value: String?) {
        put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.nullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    fun interface Listener {
        fun onRecognitionQueued(recognition: PaymentRecognition)
    }

    companion object {
        private const val PREFERENCES_NAME = "accessibility_billing_recognitions"
        private const val KEY_PENDING = "pending"
        private const val KEY_SEEN = "seen"
        private const val MAX_PENDING = 25

        private val listeners = CopyOnWriteArraySet<Listener>()

        fun addListener(listener: Listener) {
            listeners += listener
        }

        fun removeListener(listener: Listener) {
            listeners -= listener
        }
    }
}
