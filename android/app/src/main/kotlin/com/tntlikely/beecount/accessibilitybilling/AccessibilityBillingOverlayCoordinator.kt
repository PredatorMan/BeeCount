package com.tntlikely.beecount.accessibilitybilling

import java.util.concurrent.CopyOnWriteArraySet

internal object AccessibilityBillingOverlayCoordinator {
    @Volatile
    var isOverlayVisible: Boolean = false
        private set

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun markOverlayVisible() {
        isOverlayVisible = true
        listeners.forEach(Listener::onOverlayVisible)
    }

    fun markOverlayHidden() {
        isOverlayVisible = false
    }

    fun requestDismiss() {
        listeners.forEach(Listener::onDismissRequested)
    }

    fun requestSuppressCurrentPage() {
        listeners.forEach(Listener::onSuppressCurrentPageRequested)
    }

    /** Clears the current page session and asks the service to re-evaluate its global loop. */
    fun requestRecognitionReset() {
        listeners.forEach(Listener::onRecognitionResetRequested)
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    internal interface Listener {
        fun onOverlayVisible()
        fun onDismissRequested()
        fun onSuppressCurrentPageRequested()
        fun onRecognitionResetRequested()
    }
}
