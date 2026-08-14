package com.tntlikely.beecount.accessibilitybilling

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundWindowPolicyTest {
    @Test
    fun `own app is transient only while inspecting the overlay host`() {
        assertFalse(transientPackage("orange.app", overlayHostInspection = false))
        assertTrue(transientPackage("orange.app", overlayHostInspection = true))
    }

    @Test
    fun `system and input method roots remain transient in both monitor modes`() {
        listOf("com.android.systemui", "keyboard.app", "com.miui.systemui.plugin").forEach { packageName ->
            assertTrue(transientPackage(packageName, overlayHostInspection = false))
            assertTrue(transientPackage(packageName, overlayHostInspection = true))
        }
        assertFalse(transientPackage("browser.app", overlayHostInspection = true))
    }

    @Test
    fun `expected active package is inspectable`() {
        assertTrue(policy("payment.app", transient = false))
    }

    @Test
    fun `missing or transient active root can fall back to expected window`() {
        assertTrue(policy(null, transient = false))
        assertTrue(policy("android.systemui", transient = true))
        assertTrue(policy("keyboard.app", transient = true))
        assertTrue(policy("orange.app", transient = true))
    }

    @Test
    fun `ordinary foreground app blocks retained background payment window`() {
        assertFalse(policy("browser.app", transient = false))
        assertFalse(policy("launcher.app", transient = false))
    }

    @Test
    fun `unsupported event stops only for a readable ordinary foreground app`() {
        assertTrue(stopForUnsupported("browser.app", transient = false))
        assertFalse(stopForUnsupported("android.systemui", transient = true))
        assertFalse(stopForUnsupported(null, transient = false))
        assertFalse(
            ForegroundWindowPolicy.shouldStopForUnsupportedWindowState(
                monitoredPackage = null,
                activePackage = "browser.app",
                activePackageIsTransient = false,
            ),
        )
    }

    private fun policy(activePackage: String?, transient: Boolean): Boolean =
        ForegroundWindowPolicy.canInspectExpectedWindow(
            expectedPackage = "payment.app",
            activePackage = activePackage,
            activePackageIsTransient = transient,
        )

    private fun transientPackage(
        packageName: String,
        overlayHostInspection: Boolean,
    ): Boolean = ForegroundWindowPolicy.isTransientCoveringPackage(
        packageName = packageName,
        servicePackageName = "orange.app",
        inputMethodPackageName = "keyboard.app",
        overlayHostInspection = overlayHostInspection,
    )

    private fun stopForUnsupported(activePackage: String?, transient: Boolean): Boolean =
        ForegroundWindowPolicy.shouldStopForUnsupportedWindowState(
            monitoredPackage = "payment.app",
            activePackage = activePackage,
            activePackageIsTransient = transient,
        )
}
