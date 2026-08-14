package com.tntlikely.beecount.accessibilitybilling

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

internal class RecognitionRuleRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Volatile
    private var loadedRaw: String? = null
    @Volatile
    private var loadedRules: RecognitionRuleSet = BuiltInRecognitionRules.value
    @Volatile
    private var cacheChecked = false
    @Volatile
    private var loadedFromRemote = false

    @Synchronized
    fun currentRuleSet(): RecognitionRuleSet {
        val cachedRaw = preferences.getString(KEY_CACHED_RULES, null)
        if (cacheChecked && cachedRaw == loadedRaw) return loadedRules
        val cachedRules = cachedRaw?.let { raw ->
            runCatching {
                RecognitionRuleCodec.parse(raw).also { candidate ->
                    RecognitionRuleUpdatePolicy.validateCached(candidate)
                }
            }.getOrNull()
        }
        loadedRules = cachedRules ?: BuiltInRecognitionRules.value
        loadedRaw = cachedRaw
        loadedFromRemote = cachedRules != null
        cacheChecked = true
        return loadedRules
    }

    @Synchronized
    fun statusMap(): Map<String, Any?> {
        val rules = currentRuleSet()
        return mapOf(
            "url" to REMOTE_RULES_URL,
            "schemaVersion" to rules.schemaVersion,
            "activeVersion" to rules.rulesVersion,
            "source" to if (loadedFromRemote) "remote" else "builtin",
            "lastAttemptAt" to preferences.getLong(KEY_LAST_ATTEMPT_AT, 0L).takeIf { it > 0 },
            "lastSuccessAt" to preferences.getLong(KEY_LAST_SUCCESS_AT, 0L).takeIf { it > 0 },
            "lastError" to preferences.getString(KEY_LAST_ERROR, null),
        )
    }

    @Synchronized
    fun updateFromRemote(): Map<String, Any?> {
        val attemptAt = System.currentTimeMillis()
        preferences.edit().putLong(KEY_LAST_ATTEMPT_AT, attemptAt).apply()
        return try {
            val raw = downloadRules()
            val candidate = RecognitionRuleCodec.parse(raw)
            val active = currentRuleSet()
            if (candidate.rulesVersion == active.rulesVersion && loadedFromRemote && raw == loadedRaw) {
                return statusMap() + mapOf(
                    "updated" to false,
                    "unchanged" to true,
                    "appCount" to candidate.apps.size,
                )
            }
            RecognitionRuleUpdatePolicy.validate(candidate, active)
            val successAt = System.currentTimeMillis()
            val previousRaw = preferences.getString(KEY_CACHED_RULES, null)
            val previousSuccessAt = preferences.getLong(KEY_LAST_SUCCESS_AT, 0L)
            val previousError = preferences.getString(KEY_LAST_ERROR, null)
            val committed = preferences.edit()
                .putString(KEY_CACHED_RULES, raw)
                .putLong(KEY_LAST_ATTEMPT_AT, attemptAt)
                .putLong(KEY_LAST_SUCCESS_AT, successAt)
                .remove(KEY_LAST_ERROR)
                .commit()
            if (!committed) {
                val rollback = preferences.edit()
                if (previousRaw == null) rollback.remove(KEY_CACHED_RULES)
                else rollback.putString(KEY_CACHED_RULES, previousRaw)
                if (previousSuccessAt == 0L) rollback.remove(KEY_LAST_SUCCESS_AT)
                else rollback.putLong(KEY_LAST_SUCCESS_AT, previousSuccessAt)
                if (previousError == null) rollback.remove(KEY_LAST_ERROR)
                else rollback.putString(KEY_LAST_ERROR, previousError)
                rollback.commit()
                loadedRaw = previousRaw
                loadedRules = active
                loadedFromRemote = previousRaw != null && active !== BuiltInRecognitionRules.value
                cacheChecked = true
                error("Unable to atomically commit rule cache")
            }
            loadedRaw = raw
            loadedRules = candidate
            loadedFromRemote = true
            cacheChecked = true
            statusMap() + mapOf("updated" to true, "appCount" to candidate.apps.size)
        } catch (error: Exception) {
            val message = (error.message ?: error.javaClass.simpleName).take(MAX_ERROR_LENGTH)
            preferences.edit()
                .putLong(KEY_LAST_ATTEMPT_AT, attemptAt)
                .putString(KEY_LAST_ERROR, message)
                .apply()
            statusMap() + mapOf("updated" to false, "error" to message)
        }
    }

    private fun downloadRules(): String {
        val url = URL(REMOTE_RULES_URL)
        require(url.protocol == "https") { "Only HTTPS rule URLs are allowed" }
        val connection = url.openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "BeeCount-Accessibility-Rules/1")
            val responseCode = connection.responseCode
            require(responseCode == HttpURLConnection.HTTP_OK) { "HTTP $responseCode" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= MAX_DOWNLOAD_BYTES) { "Rules file is too large" }
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_DOWNLOAD_BYTES) { "Rules file is too large" }
                    output.write(buffer, 0, count)
                }
            }
            output.toString(StandardCharsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val REMOTE_RULES_URL =
            "https://raw.githubusercontent.com/PredatorMan/BeeCount-Accessibility-Rules/main/rules.json"
        const val MAX_DOWNLOAD_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        private const val MAX_ERROR_LENGTH = 300
        private const val PREFERENCES_NAME = "accessibility_billing_rule_cache"
        private const val KEY_CACHED_RULES = "cached_rules_json"
        private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_LAST_ERROR = "last_error"
    }
}

internal object RecognitionRuleUpdatePolicy {
    fun validate(candidate: RecognitionRuleSet, active: RecognitionRuleSet) {
        require(candidate.schemaVersion in 1..RecognitionRuleCodec.SUPPORTED_SCHEMA_VERSION) {
            "Unsupported schemaVersion"
        }
        require(candidate.rulesVersion >= BuiltInRecognitionRules.value.rulesVersion) {
            "rulesVersion is older than the built-in version"
        }
        require(candidate.rulesVersion > active.rulesVersion) {
            "rulesVersion must be newer than the active version"
        }
        requireBuiltInAppsRetained(candidate, BuiltInRecognitionRules.value)
        requireNewAppsDisabled(candidate, BuiltInRecognitionRules.value)
    }

    fun validateCached(
        candidate: RecognitionRuleSet,
        builtIn: RecognitionRuleSet = BuiltInRecognitionRules.value,
    ) {
        require(candidate.schemaVersion in 1..RecognitionRuleCodec.SUPPORTED_SCHEMA_VERSION) {
            "Unsupported schemaVersion"
        }
        require(candidate.rulesVersion > builtIn.rulesVersion) {
            "Cached rulesVersion is not newer than the built-in version"
        }
        requireBuiltInAppsRetained(candidate, builtIn)
        requireNewAppsDisabled(candidate, builtIn)
    }

    private fun requireBuiltInAppsRetained(
        candidate: RecognitionRuleSet,
        builtIn: RecognitionRuleSet,
    ) {
        val candidateApps = candidate.apps.associateBy { it.packageName }
        val missingPackages = builtIn.apps
            .filter { it.packageName !in candidateApps }
            .map { it.packageName }
        require(missingPackages.isEmpty()) {
            "Built-in apps cannot be removed: ${missingPackages.joinToString()}"
        }

        val disabledPackages = builtIn.apps
            .filter { candidateApps.getValue(it.packageName).defaultEnabled.not() }
            .map { it.packageName }
        require(disabledPackages.isEmpty()) {
            "Built-in apps cannot default to disabled: ${disabledPackages.joinToString()}"
        }
    }

    private fun requireNewAppsDisabled(
        candidate: RecognitionRuleSet,
        builtIn: RecognitionRuleSet,
    ) {
        val builtInPackages = builtIn.apps.mapTo(hashSetOf()) { it.packageName }
        val enabledNewPackages = candidate.apps
            .filter { it.packageName !in builtInPackages && it.defaultEnabled }
            .map { it.packageName }
        require(enabledNewPackages.isEmpty()) {
            "New apps must default to disabled: ${enabledNewPackages.joinToString()}"
        }
    }
}
