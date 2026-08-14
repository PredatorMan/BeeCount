package com.tntlikely.beecount.accessibilitybilling

/** Decides whether a retained payment window may be inspected behind another active root. */
internal object ForegroundWindowPolicy {
    fun isTransientCoveringPackage(
        packageName: String,
        servicePackageName: String,
        inputMethodPackageName: String,
        overlayHostInspection: Boolean,
    ): Boolean =
        (overlayHostInspection && packageName == servicePackageName) ||
            packageName == inputMethodPackageName ||
            packageName == "com.android.systemui" ||
            packageName == "com.miui.securitycore" ||
            packageName.startsWith("com.miui.systemui")

    fun canInspectExpectedWindow(
        expectedPackage: String,
        activePackage: String?,
        activePackageIsTransient: Boolean,
    ): Boolean = activePackage == null ||
        activePackage == expectedPackage ||
        activePackageIsTransient

    fun shouldStopForUnsupportedWindowState(
        monitoredPackage: String?,
        activePackage: String?,
        activePackageIsTransient: Boolean,
    ): Boolean = monitoredPackage != null &&
        activePackage != null &&
        activePackage != monitoredPackage &&
        !activePackageIsTransient
}
