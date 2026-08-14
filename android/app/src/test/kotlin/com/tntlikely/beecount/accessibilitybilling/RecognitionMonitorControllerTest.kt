package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionMonitorControllerTest {
    @Test
    fun `event storm keeps one task and never postpones inspection`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(scheduler)

        assertTrue(monitor.start())
        scheduler.advanceBy(100)
        assertFalse(monitor.wake())
        scheduler.advanceBy(100)
        assertFalse(monitor.wake())

        assertEquals(1, scheduler.pendingCount())
        assertEquals(450L, scheduler.nextDueAtMs())
    }

    @Test
    fun `event brings low frequency foreground guard forward`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(scheduler, RecognitionMonitorController.InspectionResult.NON_BILL_PAGE)

        monitor.start()
        scheduler.runNext()
        assertEquals(5_450L, scheduler.nextDueAtMs())

        scheduler.advanceBy(1_000)
        assertTrue(monitor.wake())
        assertEquals(1_900L, scheduler.nextDueAtMs())
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun `content event storm is coalesced to one inspection per second`() {
        val scheduler = FakeScheduler()
        var attempts = 0
        val monitor = monitor(
            scheduler = scheduler,
            onInspect = {
                attempts += 1
                RecognitionMonitorController.InspectionResult.NON_BILL_PAGE
            },
        )

        monitor.start(immediate = true)
        scheduler.runNext()
        repeat(20) {
            scheduler.advanceBy(20)
            monitor.wakeFromContentEvent()
        }

        assertEquals(1, attempts)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(1_000L, scheduler.nextDueAtMs())
        scheduler.runNext()
        assertEquals(2, attempts)
    }

    @Test
    fun `window event remains a fast wake after a recent inspection`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(
            scheduler,
            RecognitionMonitorController.InspectionResult.NON_BILL_PAGE,
        )

        monitor.start(immediate = true)
        scheduler.runNext()
        scheduler.advanceBy(100)

        assertTrue(monitor.wake())
        assertEquals(450L, scheduler.nextDelayMs())
    }

    @Test
    fun `unknown slow load retries forever with adaptive low frequency`() {
        val scheduler = FakeScheduler()
        var attempts = 0
        val monitor = monitor(
            scheduler = scheduler,
            onInspect = {
                attempts += 1
                RecognitionMonitorController.InspectionResult.UNKNOWN
            },
        )

        monitor.start()
        val expectedDelays = listOf(1_000L, 1_000L, 2_000L, 3_000L, 5_000L)
        expectedDelays.forEach { expectedDelay ->
            scheduler.runNext()
            assertEquals(expectedDelay, scheduler.nextDelayMs())
        }
        repeat(8) { scheduler.runNext() }

        assertEquals(13, attempts)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(5_000L, scheduler.nextDelayMs())
        assertTrue(monitor.isActive)
    }

    @Test
    fun `inspection exception cannot break the global loop`() {
        val scheduler = FakeScheduler()
        var attempts = 0
        val monitor = monitor(
            scheduler = scheduler,
            onInspect = {
                attempts += 1
                throw IllegalStateException("temporary root failure")
            },
        )

        monitor.start(immediate = true)
        repeat(8) { scheduler.runNext() }

        assertEquals(8, attempts)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(5_000L, scheduler.nextDelayMs())
        assertTrue(monitor.isActive)
    }

    @Test
    fun `bill and non bill pages use foreground guard`() {
        listOf(
            RecognitionMonitorController.InspectionResult.BILL_PAGE,
            RecognitionMonitorController.InspectionResult.NON_BILL_PAGE,
        ).forEach { result ->
            val scheduler = FakeScheduler()
            val monitor = monitor(scheduler, result)

            monitor.start()
            scheduler.runNext()

            assertEquals(1, scheduler.pendingCount())
            assertEquals(5_000L, scheduler.nextDelayMs())
        }
    }

    @Test
    fun `screen off broadcast pauses even while overlay is visible`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(scheduler)

        monitor.start(immediate = true)
        monitor.setOverlayVisible(true)
        monitor.pauseForScreenOff()

        assertTrue(monitor.isActive)
        assertTrue(monitor.isScreenPaused)
        assertEquals(0, scheduler.pendingCount())

        assertTrue(monitor.wake())
        assertFalse(monitor.isScreenPaused)
        assertEquals(450L, scheduler.nextDelayMs())
    }

    @Test
    fun `unsupported and disabled states stay alive at idle frequency`() {
        listOf(
            RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE,
            RecognitionMonitorController.InspectionResult.DISABLED_IDLE,
        ).forEach { result ->
            val scheduler = FakeScheduler()
            val monitor = monitor(scheduler, result)

            monitor.start(immediate = true)
            scheduler.runNext()

            assertTrue(monitor.isActive)
            assertEquals(1, scheduler.pendingCount())
            assertEquals(2_000L, scheduler.nextDelayMs())
        }
    }

    @Test
    fun `screen off pauses without stopping and next event resumes`() {
        val scheduler = FakeScheduler()
        var result = RecognitionMonitorController.InspectionResult.SCREEN_OFF
        val monitor = monitor(scheduler, onInspect = { result })

        monitor.start(immediate = true)
        scheduler.runNext()

        assertTrue(monitor.isActive)
        assertTrue(monitor.isScreenPaused)
        assertEquals(0, scheduler.pendingCount())

        result = RecognitionMonitorController.InspectionResult.UNKNOWN
        assertTrue(monitor.wake())
        assertFalse(monitor.isScreenPaused)
        assertEquals(450L, scheduler.nextDelayMs())
        scheduler.runNext()
        assertEquals(1_000L, scheduler.nextDelayMs())
    }

    @Test
    fun `visible overlay pauses and dismiss resumes the global monitor`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(scheduler)

        monitor.start()
        monitor.setOverlayVisible(true)
        assertEquals(1, scheduler.pendingCount())
        // Preserve an already earlier wake-up instead of postponing it.
        assertEquals(450L, scheduler.nextDelayMs())
        assertTrue(monitor.isActive)

        monitor.setOverlayVisible(false)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(450L, scheduler.nextDelayMs())
    }

    @Test
    fun `overlay watchdog runs forever without performing page recognition`() {
        val scheduler = FakeScheduler()
        var pageInspections = 0
        var overlayInspections = 0
        val monitor = RecognitionMonitorController(
            scheduler = scheduler,
            inspect = {
                pageInspections += 1
                RecognitionMonitorController.InspectionResult.BILL_PAGE
            },
            inspectOverlayHost = {
                overlayInspections += 1
                RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
            },
        )

        monitor.start(immediate = true)
        monitor.setOverlayVisible(true)
        repeat(4) { scheduler.runNext() }

        assertEquals(0, pageInspections)
        assertEquals(4, overlayInspections)
        assertEquals(1, scheduler.pendingCount())
        assertEquals(2_000L, scheduler.nextDelayMs())
    }

    @Test
    fun `overlay watchdog leaving host immediately restores page loop`() {
        val scheduler = FakeScheduler()
        var hostPresent = true
        var pageInspections = 0
        lateinit var monitor: RecognitionMonitorController
        monitor = RecognitionMonitorController(
            scheduler = scheduler,
            inspect = {
                pageInspections += 1
                RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
            },
            inspectOverlayHost = {
                if (hostPresent) {
                    RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
                } else {
                    monitor.setOverlayVisible(false)
                    RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
                }
            },
        )

        monitor.start(immediate = true)
        monitor.setOverlayVisible(true)
        scheduler.runNext()
        hostPresent = false
        scheduler.runNext()

        assertEquals(450L, scheduler.nextDelayMs())
        scheduler.runNext()
        assertEquals(1, pageInspections)
        assertTrue(monitor.isActive)
    }

    @Test
    fun `overlay shown reentrantly during recognition keeps watchdog scheduled`() {
        val scheduler = FakeScheduler()
        var overlayInspections = 0
        lateinit var monitor: RecognitionMonitorController
        monitor = RecognitionMonitorController(
            scheduler = scheduler,
            inspect = {
                monitor.setOverlayVisible(true)
                RecognitionMonitorController.InspectionResult.BILL_PAGE
            },
            inspectOverlayHost = {
                overlayInspections += 1
                RecognitionMonitorController.InspectionResult.OVERLAY_ACTIVE
            },
        )

        monitor.start(immediate = true)
        scheduler.runNext()

        assertEquals(1, scheduler.pendingCount())
        assertEquals(2_000L, scheduler.nextDelayMs())
        scheduler.runNext()
        assertEquals(1, overlayInspections)
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun `overlay dismissed reentrantly during watchdog resumes page recognition`() {
        val scheduler = FakeScheduler()
        var pageInspections = 0
        lateinit var monitor: RecognitionMonitorController
        monitor = RecognitionMonitorController(
            scheduler = scheduler,
            inspect = {
                pageInspections += 1
                RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
            },
            inspectOverlayHost = {
                monitor.setOverlayVisible(false)
                RecognitionMonitorController.InspectionResult.UNSUPPORTED_IDLE
            },
        )

        monitor.start(immediate = true)
        monitor.setOverlayVisible(true)
        scheduler.runNext()

        assertEquals(1, scheduler.pendingCount())
        assertEquals(450L, scheduler.nextDelayMs())
        scheduler.runNext()
        assertEquals(1, pageInspections)
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun `earlier replacement invalidates stale task even when removal races`() {
        val scheduler = FakeScheduler(ignoreRemovals = true)
        var attempts = 0
        val monitor = monitor(
            scheduler = scheduler,
            onInspect = {
                attempts += 1
                RecognitionMonitorController.InspectionResult.NON_BILL_PAGE
            },
        )

        monitor.start()
        scheduler.runNext()
        scheduler.advanceBy(1_000)
        assertTrue(monitor.wake())
        scheduler.runAllDueAtNextTime()

        assertEquals(2, attempts)
        assertEquals(2, scheduler.pendingCount())
        scheduler.runNext()
        assertEquals(2, attempts)
        assertEquals(1, scheduler.pendingCount())
    }

    @Test
    fun `stop invalidates stale task and restart starts cleanly`() {
        val scheduler = FakeScheduler(ignoreRemovals = true)
        var attempts = 0
        val monitor = monitor(
            scheduler = scheduler,
            onInspect = {
                attempts += 1
                RecognitionMonitorController.InspectionResult.UNKNOWN
            },
        )

        monitor.start()
        monitor.stop()
        scheduler.runAllDueAtNextTime()
        assertEquals(0, attempts)
        assertFalse(monitor.isActive)

        monitor.start(immediate = true)
        scheduler.runAllDueAtNextTime()
        assertEquals(1, attempts)
        assertTrue(monitor.isActive)
    }

    @Test
    fun `wake before start does not create an implicit loop`() {
        val scheduler = FakeScheduler()
        val monitor = monitor(scheduler)

        assertFalse(monitor.wake())
        assertEquals(0, scheduler.pendingCount())
        assertFalse(monitor.isActive)
    }

    private fun monitor(
        scheduler: FakeScheduler,
        result: RecognitionMonitorController.InspectionResult =
            RecognitionMonitorController.InspectionResult.UNKNOWN,
        onInspect: (() -> RecognitionMonitorController.InspectionResult)? = null,
    ) = RecognitionMonitorController(
        scheduler = scheduler,
        inspect = onInspect ?: { result },
    )

    private class FakeScheduler(
        private val ignoreRemovals: Boolean = false,
    ) : RecognitionMonitorController.Scheduler {
        private data class Task(
            val runnable: Runnable,
            val dueAtMs: Long,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()
        private var clockMs = 0L

        override fun nowMs(): Long = clockMs

        override fun postDelayed(task: Runnable, delayMs: Long) {
            tasks += Task(task, clockMs + delayMs)
        }

        override fun remove(task: Runnable) {
            if (!ignoreRemovals) tasks.firstOrNull { it.runnable === task }?.cancelled = true
        }

        fun advanceBy(durationMs: Long) {
            clockMs += durationMs
        }

        fun pendingCount(): Int = tasks.count { !it.cancelled }

        fun nextDueAtMs(): Long? = tasks.filterNot { it.cancelled }.minOfOrNull { it.dueAtMs }

        fun nextDelayMs(): Long? = nextDueAtMs()?.minus(clockMs)

        fun runNext() {
            val next = tasks.filterNot { it.cancelled }.minByOrNull { it.dueAtMs } ?: return
            next.cancelled = true
            clockMs = maxOf(clockMs, next.dueAtMs)
            next.runnable.run()
        }

        fun runAllDueAtNextTime() {
            val dueAt = nextDueAtMs() ?: return
            clockMs = maxOf(clockMs, dueAt)
            tasks.filter { !it.cancelled && it.dueAtMs <= clockMs }.forEach { task ->
                task.cancelled = true
                task.runnable.run()
            }
        }
    }
}
