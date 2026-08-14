package com.tntlikely.beecount.accessibilitybilling

import java.util.concurrent.CopyOnWriteArraySet

/** Broadcasts overlay writes to every Flutter engine hosted by this process. */
internal object AccessibilityBillingTransactionCoordinator {
    private val listeners = CopyOnWriteArraySet<Listener>()

    fun notifySaved(ledgerId: Int, transactionId: Int) {
        listeners.forEach { listener ->
            listener.onTransactionSaved(ledgerId, transactionId)
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    internal fun interface Listener {
        fun onTransactionSaved(ledgerId: Int, transactionId: Int)
    }
}
