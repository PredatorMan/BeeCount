package com.tntlikely.beecount.accessibilitybilling

/**
 * Keeps bill-page suppression scoped to one foreground page session.
 *
 * UNKNOWN observations are deliberately inert: an unavailable accessibility root or a
 * partially loaded WebView is not evidence that the user left the current page.
 */
internal class PaymentPageSessionGate(
    private val nonBillConfirmationsRequired: Int = DEFAULT_NON_BILL_CONFIRMATIONS,
) {
    private var activePageKey: String? = null
    private var suppressedPageKey: String? = null
    private var nonBillConfirmations = 0

    init {
        require(nonBillConfirmationsRequired > 0) {
            "nonBillConfirmationsRequired must be positive"
        }
    }

    /** Returns SHOW_OVERLAY unless this page session was explicitly cancelled or saved. */
    fun observe(observation: Observation): Decision = when (observation) {
        is Observation.Bill -> observeBill(observation.pageKey)
        Observation.NonBill -> observeNonBill()
        Observation.Unknown -> Decision.NONE
    }

    /** Call for both Cancel and Save so the current page cannot immediately reopen the sheet. */
    fun suppressCurrentPage() {
        suppressedPageKey = activePageKey
    }

    /** Clears in-memory state when monitoring is explicitly stopped or the service is destroyed. */
    fun reset() {
        activePageKey = null
        suppressedPageKey = null
        nonBillConfirmations = 0
    }

    private fun observeBill(pageKey: String): Decision {
        require(pageKey.isNotBlank()) { "Bill pageKey must not be blank" }
        nonBillConfirmations = 0

        if (activePageKey != pageKey) {
            activePageKey = pageKey
            suppressedPageKey = null
        }

        if (suppressedPageKey == pageKey) {
            return Decision.NONE
        }

        return Decision.SHOW_OVERLAY
    }

    private fun observeNonBill(): Decision {
        if (activePageKey == null) return Decision.NONE

        nonBillConfirmations += 1
        if (nonBillConfirmations >= nonBillConfirmationsRequired) {
            activePageKey = null
            suppressedPageKey = null
            nonBillConfirmations = 0
        }
        return Decision.NONE
    }

    internal sealed interface Observation {
        data class Bill(val pageKey: String) : Observation
        data object NonBill : Observation
        data object Unknown : Observation
    }

    internal enum class Decision {
        SHOW_OVERLAY,
        NONE,
    }

    companion object {
        private const val DEFAULT_NON_BILL_CONFIRMATIONS = 2
    }
}
