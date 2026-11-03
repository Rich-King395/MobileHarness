package org.mobileharness.app.platform

/** Both channels are control/configuration surfaces, never GUI automation targets. */
object AppChannels {
    const val DEVELOPMENT_PACKAGE = "org.mobileharness.app"
    const val RELEASE_PACKAGE = "org.mobileharness.app.release"
    const val ACCESSIBILITY_SERVICE_CLASS = "org.mobileharness.app.platform.HarnessAccessibilityService"

    private val packages = setOf(DEVELOPMENT_PACKAGE, RELEASE_PACKAGE)

    fun isHarnessPackage(packageName: String?): Boolean = packageName in packages

    /** Match the exact companion component; unrelated accessibility services remain allowed. */
    fun isCompanionService(ownPackage: String, servicePackage: String, serviceClass: String): Boolean =
        servicePackage != ownPackage && isHarnessPackage(servicePackage) &&
            serviceClass == ACCESSIBILITY_SERVICE_CLASS
}
