package com.tntlikely.beecount.accessibilitybilling

/**
 * Owns one process-wide recognition loop for the lifetime of the accessibility service.
 * Events can bring the next inspection forward, but can never create a second task or
 * postpone an earlier one.
 */
internal class RecognitionMonitorController(
    private val scheduler: Scheduler,
    private val inspect: () -> InspectionResult,
    private val inspectOverlayHost: () -> InspectionResult = {
        InspectionResult.OVERLAY_ACTIVE
    },
    private val wakeDelayMs: Long = WAKE_DELAY_MS,
    private val foregroundGuardDelayMs: Long = FOREGROUND_GUARD_DELAY_MS,
    private val idleGuardDelayMs: Long = IDLE_GUARD_DELAY_MS,
    private val overlayGuardDelayMs: Long = OVERLAY_GUARD_DELAY_MS,
    private val contentInspectionIntervalMs: Long = CONTENT_INSPECTION_INTERVAL_MS,
    private val unknownBackoffMs: List<Long> = UNKNOWN_BACKOFF_MS,
) {
    init {
        require(unknownBackoffMs.isNotEmpty()) { "unknownBackoffMs must not be empty" }
    }

    private var generation = 0L
    private var started = false
    private var screenPaused = false
    private var scheduledTask: Runnable? = null
    private var scheduledAtMs: Long? = null
    private var overlayVisible = false
    private var unknownCount = 0
    private var inspecting = false
    private var requestedDelayWhileInspectingMs: Long? = null
    private var lastInspectionAtMs: Long? = null

    fun start(immediate: Boolean = false): Boolean {
        if (!started) {
            started = true
            screenPaused = false
            unknownCount = 0
        }
        if (overlayVisible || screenPaused) return false
        return scheduleEarlier(if (immediate) 0L else wakeDelayMs)
    }

    /** Resumes a screen-paused loop and brings its next inspection forward. */
    fun wake(): Boolean {
        if (!started) return false
        screenPaused = false
        if (inspecting) {
            requestWhileInspecting(if (overlayVisible) overlayGuardDelayMs else wakeDelayMs)
            return true
        }
        return scheduleEarlier(if (overlayVisible) overlayGuardDelayMs else wakeDelayMs)
    }

    /**
     * WebViews can emit content and scroll events continuously. These events may bring
     * the guard forward, but cannot trigger full-tree scans more than once per interval.
     */
    fun wakeFromContentEvent(): Boolean {
        if (!started || overlayVisible) return false
        screenPaused = false
        if (inspecting) {
            requestWhileInspecting(wakeDelayMs)
            return true
        }
        val nowMs = scheduler.nowMs()
        val minimumDelayMs = lastInspectionAtMs
            ?.let { inspectedAt ->
                (inspectedAt + contentInspectionIntervalMs - nowMs).coerceAtLeast(0L)
            }
            ?: 0L
        return scheduleEarlier(maxOf(wakeDelayMs, minimumDelayMs))
    }

    fun setOverlayVisible(visible: Boolean) {
        overlayVisible = visible
        unknownCount = 0
        if (!started || screenPaused) return
        if (inspecting) {
            // The callback can be re-entrant: showing or dismissing the overlay happens
            // inside inspect(). Preserve the requested mode/delay until that inspection
            // returns instead of scheduling from stale overlay state.
            requestWhileInspecting(if (visible) overlayGuardDelayMs else wakeDelayMs)
            return
        }
        scheduleEarlier(if (visible) overlayGuardDelayMs else wakeDelayMs)
    }

    /** Pauses immediately when Android reports screen-off, including while an overlay is open. */
    fun pauseForScreenOff() {
        if (!started) return
        overlayVisible = false
        screenPaused = true
        unknownCount = 0
        lastInspectionAtMs = null
        inspecting = false
        requestedDelayWhileInspectingMs = null
        invalidateScheduledTask()
    }

    /** Fully tears down the loop. Navigation and screen-off must not call this. */
    fun stop() {
        invalidateScheduledTask()
        started = false
        screenPaused = false
        overlayVisible = false
        unknownCount = 0
        lastInspectionAtMs = null
        inspecting = false
        requestedDelayWhileInspectingMs = null
    }

    val isActive: Boolean
        get() = started

    val isScreenPaused: Boolean
        get() = started && screenPaused

    private fun inspectAndContinue(expectedGeneration: Long) {
        if (generation != expectedGeneration || !started) return
        scheduledTask = null
        scheduledAtMs = null

        inspecting = true
        requestedDelayWhileInspectingMs = null
        lastInspectionAtMs = scheduler.nowMs()
        val inspectedOverlay = overlayVisible
        val result = try {
            if (inspectedOverlay) inspectOverlayHost() else inspect()
        } catch (_: Exception) {
            InspectionResult.UNKNOWN
        } finally {
            inspecting = false
        }
        if (generation != expectedGeneration || !started) return

        val nextDelayMs = when (result) {
            InspectionResult.BILL_PAGE,
            InspectionResult.NON_BILL_PAGE,
            -> {
                unknownCount = 0
                foregroundGuardDelayMs
            }

            InspectionResult.UNKNOWN -> {
                val index = unknownCount.coerceAtMost(unknownBackoffMs.lastIndex)
                unknownCount += 1
                unknownBackoffMs[index]
            }

            InspectionResult.UNSUPPORTED_IDLE,
            InspectionResult.DISABLED_IDLE,
            -> {
                unknownCount = 0
                idleGuardDelayMs
            }

            InspectionResult.SCREEN_OFF -> {
                unknownCount = 0
                screenPaused = true
                invalidateScheduledTask()
                return
            }

            InspectionResult.OVERLAY_ACTIVE -> {
                unknownCount = 0
                overlayGuardDelayMs
            }
        }
        if (!screenPaused) {
            val requestedDelayMs = requestedDelayWhileInspectingMs
            requestedDelayWhileInspectingMs = null
            val modeDelayMs = if (overlayVisible) overlayGuardDelayMs else nextDelayMs
            scheduleEarlier(requestedDelayMs?.let { minOf(it, modeDelayMs) } ?: modeDelayMs)
        }
    }

    private fun requestWhileInspecting(delayMs: Long) {
        requestedDelayWhileInspectingMs = requestedDelayWhileInspectingMs
            ?.let { minOf(it, delayMs) }
            ?: delayMs
    }

    private fun scheduleEarlier(delayMs: Long): Boolean {
        if (!started || screenPaused) return false
        val targetTimeMs = scheduler.nowMs() + delayMs
        val currentTargetMs = scheduledAtMs
        if (scheduledTask != null && currentTargetMs != null && currentTargetMs <= targetTimeMs) {
            return false
        }

        scheduledTask?.let(scheduler::remove)
        val taskGeneration = generation
        lateinit var task: Runnable
        task = Runnable {
            if (scheduledTask !== task) return@Runnable
            inspectAndContinue(taskGeneration)
        }
        scheduledTask = task
        scheduledAtMs = targetTimeMs
        scheduler.postDelayed(task, delayMs)
        return true
    }

    private fun invalidateScheduledTask() {
        generation += 1
        scheduledTask?.let(scheduler::remove)
        scheduledTask = null
        scheduledAtMs = null
    }

    internal interface Scheduler {
        fun nowMs(): Long
        fun postDelayed(task: Runnable, delayMs: Long)
        fun remove(task: Runnable)
    }

    internal enum class InspectionResult {
        BILL_PAGE,
        NON_BILL_PAGE,
        UNKNOWN,
        UNSUPPORTED_IDLE,
        SCREEN_OFF,
        DISABLED_IDLE,
        OVERLAY_ACTIVE,
    }

    companion object {
        const val WAKE_DELAY_MS = 450L
        const val FOREGROUND_GUARD_DELAY_MS = 5_000L
        const val IDLE_GUARD_DELAY_MS = 2_000L
        const val OVERLAY_GUARD_DELAY_MS = 2_000L
        const val CONTENT_INSPECTION_INTERVAL_MS = 1_000L
        val UNKNOWN_BACKOFF_MS = listOf(1_000L, 1_000L, 2_000L, 3_000L, 5_000L)
    }
}
