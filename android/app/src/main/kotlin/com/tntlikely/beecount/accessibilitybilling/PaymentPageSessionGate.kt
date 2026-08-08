package com.tntlikely.beecount.accessibilitybilling

/**
 * Suppresses repeated accessibility events while the user remains on one bill page.
 * Leaving the recognized page ends the session, so opening the same bill later is allowed.
 */
internal class PaymentPageSessionGate {
    private var pageActive = false

    fun onRecognizedPage(): Boolean {
        if (pageActive) return false
        pageActive = true
        return true
    }

    fun onPageAbsent() {
        pageActive = false
    }
}
