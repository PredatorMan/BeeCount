package com.tntlikely.beecount.accessibilitybilling

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class AccessibilityBillingService : AccessibilityService(), AccessibilityBillingOverlayCoordinator.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val pageSessionGate = PaymentPageSessionGate()
    private val monitor = RecognitionMonitorController(
        scheduler = object : RecognitionMonitorController.Scheduler {
            override fun nowMs(): Long = android.os.SystemClock.uptimeMillis()
            override fun postDelayed(task: Runnable, delayMs: Long) {
                handler.postDelayed(task, delayMs)
            }

            override fun remove(task: Runnable) {
                handler.removeCallbacks(task)
            }
        },
        inspect = ::inspectActiveWindow,
        inspectOverlayHost = ::inspectOverlayHost,
    )

    private lateinit var preferences: AccessibilityBillingPreferences
    private lateinit var eventStore: RecognitionEventStore
    private lateinit var flutterOverlay: AccessibilityBillingFlutterOverlay
    private lateinit var ruleRepository: RecognitionRuleRepository
    private lateinit var recognitionEngine: PaymentRecognitionEngine

    private var latestSupportedPackageName: String? = null
    private var latestActivityPackageName: String? = null
    private var latestActivityName: String? = null
    private var screenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT,
                -> monitor.wake()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacksAndMessages(null)
        monitor.stop()
        pageSessionGate.reset()
        if (::flutterOverlay.isInitialized) flutterOverlay.destroy()
        preferences = AccessibilityBillingPreferences(applicationContext)
        eventStore = RecognitionEventStore(applicationContext)
        ruleRepository = RecognitionRuleRepository(applicationContext)
        recognitionEngine = PaymentRecognitionEngine(ruleRepository::currentRuleSet)
        flutterOverlay = AccessibilityBillingFlutterOverlay(applicationContext)
        AccessibilityBillingOverlayCoordinator.removeListener(this)
        AccessibilityBillingOverlayCoordinator.addListener(this)
        registerScreenReceiver()
        Log.d(LOG_TAG, "service=connected")
        monitor.start(immediate = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::preferences.isInitialized) return
        val sourcePackage = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            sourcePackage != null &&
            ruleRepository.currentRuleSet().findApp(sourcePackage) != null
        ) {
            latestActivityPackageName = sourcePackage
            event.className?.toString()?.let { latestActivityName = it }
        }
        // Event packages can belong to the keyboard, System UI, another accessibility
        // service, or a stale window. They only select wake-up urgency; inspection always
        // verifies the current active/focused application window itself.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            -> monitor.wake()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> monitor.wakeFromContentEvent()
        }
    }

    private fun inspectActiveWindow(): RecognitionMonitorController.InspectionResult {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) return screenOff()
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) return screenOff()
        if (!preferences.masterEnabled) return disabledIdle("master_disabled")

        val rootLookup = findInspectableRoot()
        val root = rootLookup.root ?: return if (
            rootLookup.foregroundPackage != null &&
            !isTransientPackage(rootLookup.foregroundPackage)
        ) {
            observeUnsupportedForeground()
            unsupportedIdle("unsupported_foreground")
        } else {
            unknown("expected_window_unavailable")
        }
        try {
            val actualPackage = root.packageName?.toString() ?: return unknown("root_package_unavailable")
            val appRule = ruleRepository.currentRuleSet().findApp(actualPackage)
                ?: return unsupportedIdle("rule_missing")
            if (!isRecognitionEnabled(actualPackage, appRule)) {
                return disabledIdle("package_disabled")
            }
            if (latestSupportedPackageName != actualPackage) {
                pageSessionGate.reset()
                latestSupportedPackageName = actualPackage
            }
            val snapshot = AccessibilityNodeNormalizer.normalize(
                root = root,
                packageName = actualPackage,
                activityName = latestActivityName.takeIf {
                    latestActivityPackageName == actualPackage
                },
                appVersion = getAppVersion(actualPackage),
            )
            val recognized = recognitionEngine.recognize(snapshot)
            if (recognized == null) {
                val state = recognitionEngine.classify(snapshot)
                Log.d(LOG_TAG, "inspection=${state.name.lowercase()}")
                return when (state) {
                    PaymentRecognitionEngine.PageState.BILL_CANDIDATE -> {
                        pageSessionGate.observe(PaymentPageSessionGate.Observation.Unknown)
                        RecognitionMonitorController.InspectionResult.UNKNOWN
                    }
                    PaymentRecognitionEngine.PageState.NON_BILL_PAGE -> {
                        pageSessionGate.observe(PaymentPageSessionGate.Observation.NonBill)
                        RecognitionMonitorController.InspectionResult.NON_BILL_PAGE
                    }
                }
            }

            val decision = pageSessionGate.observe(
                PaymentPageSessionGate.Observation.Bill(recognized.pageFingerprint),
            )
            if (decision != PaymentPageSessionGate.Decision.SHOW_OVERLAY) {
                Log.d(LOG_TAG, "inspection=bill_page result=suppressed")
                return RecognitionMonitorController.InspectionResult.BILL_PAGE
            }
            val recognition = recognized.withAutoExtractNote(preferences.autoExtractNote)
            eventStore.enqueue(recognition)
            if (!flutterOverlay.show()) {
                eventStore.acknowledge(recognition.id)
                return disabledIdle("overlay_permission_unavailable")
            }
            monitor.setOverlayVisible(true)
            Log.d(LOG_TAG, "inspection=bill_page result=success")
            return RecognitionMonitorController.InspectionResult.BILL_PAGE
        } catch (error: Exception) {
            pageSessionGate.observe(PaymentPageSessionGate.Observation.Unknown)
            Log.d(LOG_TAG, "inspection=unknown reason=exception error=${error.javaClass.simpleName}")
            return RecognitionMonitorController.InspectionResult.UNKNOWN
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * The confirmation panel owns the active accessibility root while it is visible.
     * Keep a small watchdog alive so leaving via Home, notifications, or another App
     * cannot strand the recognizer when Android omits a window-change callback.
     */
    private fun inspectOverlayHost(): RecognitionMonitorController.InspectionResult {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked) {
            handleScreenOff()
            return RecognitionMonitorController.InspectionResult.SCREEN_OFF
        }
        if (!preferences.masterEnabled) {
            leaveSupportedForeground()
            return disabledIdle("master_disabled_while_overlay_visible")
        }

        val expectedPackage = latestSupportedPackageName
            ?: return leaveOverlayForUnknownHost("overlay_source_missing")
        val activeRoot = rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString()
        activeRoot?.recycleCompat()
        if (activePackage != null && !isTransientPackage(activePackage, overlayHostInspection = true)) {
            return if (activePackage == expectedPackage) {
                RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
            } else {
                leaveOverlayForUnsupportedHost(activePackage)
            }
        }

        val underlyingPackage = windows.orEmpty()
            .asSequence()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isActive || it.isFocused)
            }
            .sortedByDescending { it.layer }
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                try {
                    root.packageName?.toString()
                } finally {
                    root.recycleCompat()
                }
            }
            .firstOrNull { !isTransientPackage(it, overlayHostInspection = true) }

        return when {
            underlyingPackage == null -> RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
            underlyingPackage == expectedPackage -> RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
            else -> leaveOverlayForUnsupportedHost(underlyingPackage)
        }
    }

    private fun leaveOverlayForUnsupportedHost(
        foregroundPackage: String,
    ): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "overlay_host=left foreground=$foregroundPackage")
        leaveSupportedForeground()
        return RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
    }

    private fun leaveOverlayForUnknownHost(
        reason: String,
    ): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "overlay_host=left reason=$reason")
        leaveSupportedForeground()
        return RecognitionMonitorController.InspectionResult.UNKNOWN
    }

    private fun findInspectableRoot(): RootLookup {
        val activeRoot = rootInActiveWindow
        val foregroundPackage = activeRoot?.packageName?.toString()
        if (foregroundPackage != null &&
            ruleRepository.currentRuleSet().findApp(foregroundPackage) != null
        ) {
            return RootLookup(activeRoot, foregroundPackage)
        }

        // A readable ordinary App root is authoritative. Never scan a retained payment
        // window behind it. Null/system/input-method/security roots may temporarily cover it.
        if (foregroundPackage != null && !isTransientPackage(foregroundPackage)) {
            activeRoot?.recycleCompat()
            return RootLookup(null, foregroundPackage)
        }
        activeRoot?.recycleCompat()

        windows.orEmpty()
            .asSequence()
            .filter { window ->
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (window.isActive || window.isFocused)
            }
            .sortedByDescending { it.layer }
            .forEach { window ->
                val candidate = window.root ?: return@forEach
                val candidatePackage = candidate.packageName?.toString()
                if (candidatePackage != null &&
                    ruleRepository.currentRuleSet().findApp(candidatePackage) != null
                ) {
                    return RootLookup(candidate, foregroundPackage)
                }
                candidate.recycleCompat()
            }
        return RootLookup(null, foregroundPackage)
    }

    private fun isTransientPackage(
        packageName: String,
        overlayHostInspection: Boolean = false,
    ): Boolean {
        val inputMethodPackage = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD,
        ).orEmpty().substringBefore('/')
        return ForegroundWindowPolicy.isTransientCoveringPackage(
            packageName = packageName,
            servicePackageName = this.packageName,
            inputMethodPackageName = inputMethodPackage,
            overlayHostInspection = overlayHostInspection,
        )
    }

    private fun isRecognitionEnabled(packageName: String, appRule: AppRecognitionRule): Boolean =
        preferences.masterEnabled && preferences.isPackageEnabled(packageName, appRule.defaultEnabled)

    override fun onOverlayVisible() = monitor.setOverlayVisible(true)

    override fun onDismissRequested() {
        pageSessionGate.suppressCurrentPage()
        handler.post {
            if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
            monitor.setOverlayVisible(false)
        }
    }

    override fun onSuppressCurrentPageRequested() {
        pageSessionGate.suppressCurrentPage()
    }

    override fun onRecognitionResetRequested() {
        handler.post {
            pageSessionGate.reset()
            latestSupportedPackageName = null
            latestActivityPackageName = null
            latestActivityName = null
            if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
            monitor.setOverlayVisible(false)
            monitor.wake()
        }
    }

    private fun stopMonitoring() {
        monitor.stop()
        latestSupportedPackageName = null
        latestActivityPackageName = null
        latestActivityName = null
        pageSessionGate.reset()
        if (::flutterOverlay.isInitialized && flutterOverlay.isVisible) {
            flutterOverlay.dismiss()
        }
    }

    private fun unknown(reason: String): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "inspection=unknown reason=$reason")
        return RecognitionMonitorController.InspectionResult.UNKNOWN
    }

    private fun unsupportedIdle(reason: String): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "inspection=unsupported_idle reason=$reason")
        return RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
    }

    private fun disabledIdle(reason: String): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "inspection=disabled_idle reason=$reason")
        return RecognitionMonitorController.InspectionResult.DISABLED_IDLE
    }

    private fun screenOff(): RecognitionMonitorController.InspectionResult {
        Log.d(LOG_TAG, "inspection=screen_off")
        return RecognitionMonitorController.InspectionResult.SCREEN_OFF
    }

    private fun observeUnsupportedForeground() {
        if (latestSupportedPackageName == null) return
        leaveSupportedForeground()
    }

    private fun leaveSupportedForeground() {
        pageSessionGate.reset()
        latestSupportedPackageName = null
        latestActivityPackageName = null
        latestActivityName = null
        if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
        monitor.setOverlayVisible(false)
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(screenReceiver, filter)
        }
        screenReceiverRegistered = true
    }

    private fun handleScreenOff() {
        pageSessionGate.reset()
        latestSupportedPackageName = null
        latestActivityPackageName = null
        latestActivityName = null
        if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
        monitor.pauseForScreenOff()
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() = recycle()

    @Suppress("DEPRECATION")
    private fun getAppVersion(targetPackage: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(targetPackage, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            packageManager.getPackageInfo(targetPackage, 0).versionName
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    override fun onInterrupt() {
        if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
        monitor.setOverlayVisible(false)
        monitor.wake()
    }

    override fun onDestroy() {
        stopMonitoring()
        handler.removeCallbacksAndMessages(null)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        AccessibilityBillingOverlayCoordinator.removeListener(this)
        if (::flutterOverlay.isInitialized) flutterOverlay.destroy()
        super.onDestroy()
    }

    companion object {
        const val LOG_TAG = "OrangeBilling"
    }

    private data class RootLookup(
        val root: AccessibilityNodeInfo?,
        val foregroundPackage: String?,
    )
}
