package com.tntlikely.beecount.accessibilitybilling

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.android.RenderMode
import io.flutter.embedding.android.TransparencyMode
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor

/** Hosts the complete Flutter confirmation UI in a focusable system overlay window. */
internal class AccessibilityBillingFlutterOverlay(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var flutterEngine: FlutterEngine? = null
    private var flutterView: FlutterView? = null
    private var bridge: AccessibilityBillingBridge? = null
    private var attachedToWindow = false

    val isVisible: Boolean
        get() = attachedToWindow

    fun show(): Boolean {
        if (!canDrawOverlays(appContext)) return false
        if (attachedToWindow) {
            flutterView?.requestFocus()
            return true
        }

        val engine = ensureEngine()
        val view = ensureView()
        return runCatching {
            windowManager.addView(view, createLayoutParams())
            attachedToWindow = true
            view.attachToFlutterEngine(engine)
            engine.lifecycleChannel.appIsResumed()
            engine.lifecycleChannel.aWindowIsFocused()
            view.requestFocus()
            AccessibilityBillingOverlayCoordinator.markOverlayVisible()
            true
        }.onFailure { error ->
            Log.e(TAG, "Unable to display accessibility billing overlay", error)
            if (view.isAttachedToFlutterEngine) view.detachFromFlutterEngine()
            if (view.isAttachedToWindow) {
                runCatching { windowManager.removeViewImmediate(view) }
            }
            attachedToWindow = false
            AccessibilityBillingOverlayCoordinator.markOverlayHidden()
        }.getOrDefault(false)
    }

    fun dismiss() {
        if (!attachedToWindow) return
        val view = flutterView ?: return
        attachedToWindow = false
        flutterEngine?.lifecycleChannel?.noWindowsAreFocused()
        flutterEngine?.lifecycleChannel?.appIsPaused()
        if (view.isAttachedToFlutterEngine) view.detachFromFlutterEngine()
        runCatching { windowManager.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "Unable to remove accessibility billing overlay", it) }
        AccessibilityBillingOverlayCoordinator.markOverlayHidden()
    }

    fun destroy() {
        dismiss()
        bridge?.close()
        bridge = null
        flutterView?.let { view ->
            if (view.isAttachedToFlutterEngine) view.detachFromFlutterEngine()
        }
        flutterView = null
        flutterEngine?.lifecycleChannel?.appIsDetached()
        flutterEngine?.destroy()
        flutterEngine = null
    }

    private fun ensureEngine(): FlutterEngine {
        flutterEngine?.let { return it }
        return FlutterEngine(appContext).also { engine ->
            bridge = AccessibilityBillingBridge(appContext, engine.dartExecutor.binaryMessenger)
            val flutterLoader = FlutterInjector.instance().flutterLoader()
            val entrypoint = DartExecutor.DartEntrypoint(
                flutterLoader.findAppBundlePath(),
                DART_ENTRYPOINT,
            )
            engine.dartExecutor.executeDartEntrypoint(entrypoint)
            flutterEngine = engine
        }
    }

    private fun ensureView(): FlutterView {
        flutterView?.let { return it }
        return FlutterView(
            appContext,
            RenderMode.texture,
            TransparencyMode.transparent,
        ).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFocusable = true
            isFocusableInTouchMode = true
            flutterView = this
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    companion object {
        private const val TAG = "A11yBillingOverlay"
        private const val DART_ENTRYPOINT = "accessibilityBillingOverlayMain"

        fun canDrawOverlays(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
        }
    }
}
