package com.tntlikely.beecount.accessibilitybilling

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.Locale
import java.util.concurrent.Executors

internal class AccessibilityBillingBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : RecognitionEventStore.Listener {
    private val channel = MethodChannel(messenger, CHANNEL_NAME)
    private val preferences = AccessibilityBillingPreferences(context.applicationContext)
    private val eventStore = RecognitionEventStore(context.applicationContext)
    private val ruleRepository = RecognitionRuleRepository(context.applicationContext)

    init {
        channel.setMethodCallHandler(::handleMethodCall)
        RecognitionEventStore.addListener(this)
    }

    fun close() {
        RecognitionEventStore.removeListener(this)
        channel.setMethodCallHandler(null)
    }

    override fun onRecognitionQueued(recognition: PaymentRecognition) {
        channel.invokeMethod(EVENT_TRANSACTION_DETECTED, recognition.toMap())
    }

    private fun handleMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getServiceStatus" -> result.success(isServiceEnabled())
            "openAccessibilitySettings" -> result.success(
                openSettingsIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
            )
            "getSettings", "getPermissionStatus" -> result.success(settingsMap())
            "updateSettings" -> {
                call.argument<Boolean>("masterEnabled")?.let {
                    preferences.masterEnabled = it
                    if (!it) AccessibilityBillingOverlayCoordinator.requestDismiss()
                }
                call.argument<Boolean>("wechatEnabled")?.let {
                    preferences.setPackageEnabled(AccessibilityBillingPreferences.WECHAT_PACKAGE, it)
                }
                call.argument<Boolean>("alipayEnabled")?.let {
                    preferences.setPackageEnabled(AccessibilityBillingPreferences.ALIPAY_PACKAGE, it)
                }
                (call.argument<Boolean>("autoExtractNote")
                    ?: call.argument<Boolean>("autoExtractNotes"))?.let {
                    preferences.autoExtractNote = it
                }
                call.argument<Map<*, *>>("packageEnabled")?.forEach { (packageName, enabled) ->
                    if (packageName is String && enabled is Boolean) {
                        preferences.setPackageEnabled(packageName, enabled)
                    }
                }
                result.success(null)
            }
            "getRuleUpdateStatus" -> result.success(ruleRepository.statusMap())
            "updateRecognitionRules" -> {
                UPDATE_EXECUTOR.execute {
                    val updateResult = ruleRepository.updateFromRemote()
                    val response = ruleContractMap() + updateResult
                    MAIN_HANDLER.post { result.success(response) }
                }
            }
            "setRecognitionPackageEnabled" -> {
                val packageName = call.argument<String>("packageName")
                val enabled = call.argument<Boolean>("enabled")
                val app = packageName?.let { ruleRepository.currentRuleSet().findApp(it) }
                if (app == null || enabled == null) {
                    result.error("INVALID_ARGUMENT", "Known packageName and enabled are required", null)
                } else {
                    result.success(preferences.setPackageEnabled(app.packageName, enabled))
                }
            }
            "getOverlayPermissionStatus" -> result.success(canDrawOverlays())
            "openOverlaySettings", "openOverlayPermissionSettings" -> {
                result.success(openOverlayPermissionSettings())
            }
            "getBatteryOptimizationStatus" -> result.success(isIgnoringBatteryOptimizations())
            "openBatteryOptimizationSettings" -> result.success(
                openSettingsIntent(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
            )
            "requestIgnoreBatteryOptimizations" -> result.success(requestIgnoreBatteryOptimizations())
            "getNotificationStatus" -> result.success(notificationStatusMap())
            "requestNotificationPermission" -> result.success(requestNotificationPermission())
            "openNotificationSettings" -> result.success(openNotificationSettings())
            "openAutostartSettings", "openAutoStartSettings" -> result.success(openAutostartSettings())
            "openPaymentProtectionHelp", "openPaymentProtectionSettings" -> {
                result.success(openSettingsIntent(Intent(Settings.ACTION_SECURITY_SETTINGS)))
            }
            "getPendingTransactions" -> result.success(eventStore.pending().map { it.toMap() })
            "acknowledgeTransaction" -> {
                val id = call.argument<String>("id")
                if (id == null) {
                    result.error("INVALID_ARGUMENT", "id is required", null)
                } else {
                    result.success(eventStore.acknowledge(id))
                }
            }
            "clearPendingTransactions" -> {
                eventStore.clearPending()
                AccessibilityBillingOverlayCoordinator.requestDismiss()
                result.success(null)
            }
            "dismissOverlay" -> {
                AccessibilityBillingOverlayCoordinator.requestDismiss()
                result.success(null)
            }
            "captureDiagnosticSnapshot" -> {
                val savedPath = runCatching {
                    AccessibilityBillingDiagnostics.saveLatest(context)
                }.getOrNull()
                result.success(savedPath != null)
            }
            else -> result.notImplemented()
        }
    }

    private fun settingsMap(): Map<String, Any> {
        val notificationStatus = notificationStatusMap()
        return preferences.statusMap() + mapOf(
            "serviceEnabled" to isServiceEnabled(),
            "diagnosticsSupported" to true,
            "overlayPermissionGranted" to canDrawOverlays(),
            "batteryOptimizationIgnored" to isIgnoringBatteryOptimizations(),
            "notificationsEnabled" to notificationStatus.getValue("enabled"),
            "notificationPermissionGranted" to notificationStatus.getValue("permissionGranted"),
            "notificationPermissionRequired" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
            "autostartStatus" to "unknown",
            "autoStartStatus" to "unknown",
            "autostartSettingsSupported" to hasKnownAutostartSettings(),
            "paymentProtectionHelpSupported" to true,
            "recognitionRuleStatus" to ruleRepository.statusMap(),
        ) + ruleContractMap()
    }

    private fun ruleContractMap(): Map<String, Any> {
        val rules = ruleRepository.currentRuleSet()
        val status = ruleRepository.statusMap()
        val adaptedApps = rules.apps.map { app ->
            mapOf(
                "id" to app.id,
                "packageName" to app.packageName,
                "displayName" to app.displayName,
                "enabled" to preferences.isPackageEnabled(app.packageName, app.defaultEnabled),
                "defaultEnabled" to app.defaultEnabled,
                "ruleCount" to app.pageRules.size,
            )
        }
        return mapOf(
            "adaptedApps" to adaptedApps,
            "ruleVersion" to rules.rulesVersion,
            "ruleUpdatedAt" to (status["lastSuccessAt"] ?: 0L),
            "ruleSource" to status.getValue("source").toString(),
            "ruleUpdateSupported" to true,
        )
    }

    private fun isServiceEnabled(): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
        if (!accessibilityEnabled) return false

        val expected = ComponentName(context, AccessibilityBillingService::class.java)
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return enabledServices
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { component -> component == expected }
    }

    private fun canDrawOverlays(): Boolean =
        AccessibilityBillingFlutterOverlay.canDrawOverlays(context)

    private fun openOverlayPermissionSettings(): Boolean {
        val appIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
        return openSettingsIntent(appIntent, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun requestIgnoreBatteryOptimizations(): Boolean {
        if (isIgnoringBatteryOptimizations()) return true
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        return openSettingsIntent(intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun notificationStatusMap(): Map<String, Boolean> {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }
        return mapOf(
            "enabled" to (enabled && permissionGranted),
            "permissionGranted" to permissionGranted,
        )
    }

    private fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return true
        val activity = context as? Activity ?: return openNotificationSettings()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE,
        )
        return true
    }

    private fun openNotificationSettings(): Boolean {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
        return openSettingsIntent(intent, applicationDetailsIntent())
    }

    private fun hasKnownAutostartSettings(): Boolean = autostartComponent() != null

    private fun openAutostartSettings(): Boolean {
        val component = autostartComponent()
        if (component != null) {
            val explicitIntent = Intent().apply { this.component = component }
            if (openSettingsIntent(explicitIntent)) return true
        }
        return openSettingsIntent(applicationDetailsIntent())
    }

    private fun autostartComponent(): ComponentName? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            )
            manufacturer.contains("samsung") -> ComponentName(
                "com.samsung.android.lool",
                "com.samsung.android.sm.ui.battery.BatteryActivity",
            )
            else -> null
        }
    }

    private fun applicationDetailsIntent(): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    private fun openSettingsIntent(primary: Intent, vararg fallbacks: Intent): Boolean {
        return sequenceOf(primary, *fallbacks).any { candidate ->
            runCatching {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(candidate)
                true
            }.getOrDefault(false)
        }
    }

    companion object {
        const val CHANNEL_NAME = "com.tntlikely.beecount/accessibility_billing"
        const val EVENT_TRANSACTION_DETECTED = "onTransactionDetected"
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 4812
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        private val UPDATE_EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "BeeCountRuleUpdater").apply { isDaemon = true }
        }
    }
}
