package com.tntlikely.beecount.accessibilitybilling

import android.content.Context

internal class AccessibilityBillingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var masterEnabled: Boolean
        get() = preferences.getBoolean(KEY_MASTER_ENABLED, false)
        set(value) = preferences.edit().putBoolean(KEY_MASTER_ENABLED, value).apply()

    var autoExtractNote: Boolean
        get() = preferences.getBoolean(KEY_AUTO_EXTRACT_NOTES, true)
        set(value) = preferences.edit().putBoolean(KEY_AUTO_EXTRACT_NOTES, value).apply()

    fun isPackageEnabled(packageName: String, defaultEnabled: Boolean = true): Boolean {
        val dynamicKey = "$KEY_PACKAGE_ENABLED_PREFIX$packageName"
        if (preferences.contains(dynamicKey)) return preferences.getBoolean(dynamicKey, defaultEnabled)
        return when (packageName) {
            WECHAT_PACKAGE -> preferences.getBoolean(KEY_WECHAT_ENABLED, defaultEnabled)
            ALIPAY_PACKAGE -> preferences.getBoolean(KEY_ALIPAY_ENABLED, defaultEnabled)
            else -> defaultEnabled
        }
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean): Boolean {
        if (packageName.isBlank() || packageName.length > 255) return false
        val editor = preferences.edit()
            .putBoolean("$KEY_PACKAGE_ENABLED_PREFIX$packageName", enabled)
        when (packageName) {
            WECHAT_PACKAGE -> editor.putBoolean(KEY_WECHAT_ENABLED, enabled)
            ALIPAY_PACKAGE -> editor.putBoolean(KEY_ALIPAY_ENABLED, enabled)
        }
        editor.apply()
        return true
    }

    fun statusMap(): Map<String, Any> = mapOf(
        "masterEnabled" to masterEnabled,
        "wechatEnabled" to isPackageEnabled(WECHAT_PACKAGE),
        "alipayEnabled" to isPackageEnabled(ALIPAY_PACKAGE),
        "autoExtractNote" to autoExtractNote,
    )

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

        private const val PREFERENCES_NAME = "accessibility_billing_preferences"
        private const val KEY_MASTER_ENABLED = "master_enabled"
        private const val KEY_WECHAT_ENABLED = "wechat_enabled"
        private const val KEY_ALIPAY_ENABLED = "alipay_enabled"
        private const val KEY_AUTO_EXTRACT_NOTES = "auto_extract_notes"
        private const val KEY_PACKAGE_ENABLED_PREFIX = "package_enabled."
    }
}
