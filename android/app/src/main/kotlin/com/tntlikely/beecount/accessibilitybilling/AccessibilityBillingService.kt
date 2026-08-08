package com.tntlikely.beecount.accessibilitybilling

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AccessibilityBillingService : AccessibilityService(), AccessibilityBillingOverlayCoordinator.Listener {
    private val handler = Handler(Looper.getMainLooper())
    private val pageSessionGate = PaymentPageSessionGate()

    private lateinit var preferences: AccessibilityBillingPreferences
    private lateinit var eventStore: RecognitionEventStore
    private lateinit var flutterOverlay: AccessibilityBillingFlutterOverlay
    private lateinit var ruleRepository: RecognitionRuleRepository
    private lateinit var recognitionEngine: PaymentRecognitionEngine

    private var pendingRecognition: Runnable? = null
    private var latestPackageName: String? = null
    private var latestActivityName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = AccessibilityBillingPreferences(applicationContext)
        eventStore = RecognitionEventStore(applicationContext)
        ruleRepository = RecognitionRuleRepository(applicationContext)
        recognitionEngine = PaymentRecognitionEngine(ruleRepository::currentRuleSet)
        flutterOverlay = AccessibilityBillingFlutterOverlay(applicationContext)
        AccessibilityBillingOverlayCoordinator.removeListener(this)
        AccessibilityBillingOverlayCoordinator.addListener(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::preferences.isInitialized) return
        val sourcePackage = event.packageName?.toString() ?: return

        // Events from our own focusable overlay must not end the payment-page session.
        if (sourcePackage == packageName) return
        val appRule = ruleRepository.currentRuleSet().findApp(sourcePackage)
        if (appRule == null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                leavePaymentPage()
            }
            return
        }
        if (!preferences.masterEnabled ||
            !preferences.isPackageEnabled(sourcePackage, appRule.defaultEnabled)
        ) {
            leavePaymentPage()
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        latestPackageName = sourcePackage
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            latestActivityName = event.className?.toString()
        }

        pendingRecognition?.let(handler::removeCallbacks)
        pendingRecognition = Runnable { inspectActiveWindow(sourcePackage) }.also {
            handler.postDelayed(it, RECOGNITION_DEBOUNCE_MS)
        }
    }

    private fun inspectActiveWindow(expectedPackage: String) {
        pendingRecognition = null
        val appRule = ruleRepository.currentRuleSet().findApp(expectedPackage) ?: return
        if (!preferences.masterEnabled ||
            !preferences.isPackageEnabled(expectedPackage, appRule.defaultEnabled)
        ) return
        if (latestPackageName != expectedPackage) return

        val root = rootInActiveWindow ?: return
        try {
            val actualPackage = root.packageName?.toString() ?: return
            if (actualPackage != expectedPackage || actualPackage == packageName) return
            val snapshot = AccessibilityNodeNormalizer.normalize(
                root = root,
                packageName = actualPackage,
                activityName = latestActivityName,
                appVersion = getAppVersion(actualPackage),
            )
            AccessibilityBillingDiagnostics.update(snapshot)
            val recognized = recognitionEngine.recognize(snapshot)
            if (recognized == null) {
                pageSessionGate.onPageAbsent()
                return
            }
            if (!pageSessionGate.onRecognizedPage()) return

            val recognition = recognized.withAutoExtractNote(preferences.autoExtractNote)
            eventStore.enqueue(recognition)
            if (!flutterOverlay.show()) {
                // Permission may have been revoked while the service was running. Allow retry.
                eventStore.acknowledge(recognition.id)
                pageSessionGate.onPageAbsent()
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onOverlayVisible() = Unit

    override fun onDismissRequested() {
        handler.post {
            if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
        }
    }

    private fun leavePaymentPage() {
        pendingRecognition?.let(handler::removeCallbacks)
        pendingRecognition = null
        latestPackageName = null
        latestActivityName = null
        pageSessionGate.onPageAbsent()
    }

    @Suppress("DEPRECATION")
    private fun getAppVersion(targetPackage: String): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    targetPackage,
                    PackageManager.PackageInfoFlags.of(0),
                ).versionName
            } else {
                packageManager.getPackageInfo(targetPackage, 0).versionName
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    override fun onInterrupt() {
        leavePaymentPage()
        if (::flutterOverlay.isInitialized) flutterOverlay.dismiss()
    }

    override fun onDestroy() {
        leavePaymentPage()
        AccessibilityBillingOverlayCoordinator.removeListener(this)
        if (::flutterOverlay.isInitialized) flutterOverlay.destroy()
        super.onDestroy()
    }

    companion object {
        private const val RECOGNITION_DEBOUNCE_MS = 450L
    }
}
