package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityBillingOverlayCoordinatorTest {
    @Test
    fun `visible overlay notifies service listener`() {
        val listener = RecordingListener()
        AccessibilityBillingOverlayCoordinator.addListener(listener)
        try {
            AccessibilityBillingOverlayCoordinator.markOverlayVisible()

            assertTrue(AccessibilityBillingOverlayCoordinator.isOverlayVisible)
            assertEquals(1, listener.visibleCount)
        } finally {
            AccessibilityBillingOverlayCoordinator.markOverlayHidden()
            AccessibilityBillingOverlayCoordinator.removeListener(listener)
        }
    }

    @Test
    fun `dismiss request is delivered without changing overlay state`() {
        val listener = RecordingListener()
        AccessibilityBillingOverlayCoordinator.markOverlayHidden()
        AccessibilityBillingOverlayCoordinator.addListener(listener)
        try {
            AccessibilityBillingOverlayCoordinator.requestDismiss()

            assertEquals(1, listener.dismissCount)
            assertFalse(AccessibilityBillingOverlayCoordinator.isOverlayVisible)
        } finally {
            AccessibilityBillingOverlayCoordinator.removeListener(listener)
        }
    }

    @Test
    fun `recognition stop request is delivered to the service listener`() {
        val listener = RecordingListener()
        AccessibilityBillingOverlayCoordinator.addListener(listener)
        try {
            AccessibilityBillingOverlayCoordinator.requestRecognitionReset()

            assertEquals(1, listener.stopCount)
        } finally {
            AccessibilityBillingOverlayCoordinator.removeListener(listener)
        }
    }

    @Test
    fun `suppress current page request is delivered to the service listener`() {
        val listener = RecordingListener()
        AccessibilityBillingOverlayCoordinator.addListener(listener)
        try {
            AccessibilityBillingOverlayCoordinator.requestSuppressCurrentPage()

            assertEquals(1, listener.suppressCount)
        } finally {
            AccessibilityBillingOverlayCoordinator.removeListener(listener)
        }
    }

    private class RecordingListener : AccessibilityBillingOverlayCoordinator.Listener {
        var visibleCount = 0
        var dismissCount = 0
        var suppressCount = 0
        var stopCount = 0

        override fun onOverlayVisible() {
            visibleCount += 1
        }

        override fun onDismissRequested() {
            dismissCount += 1
        }

        override fun onSuppressCurrentPageRequested() {
            suppressCount += 1
        }

        override fun onRecognitionResetRequested() {
            stopCount += 1
        }
    }
}
