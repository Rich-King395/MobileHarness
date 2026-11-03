package org.mobileharness.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChannelsTest {
    @Test fun bothConfigurationSurfacesAreExcludedFromAutomation() {
        assertTrue(AppChannels.isHarnessPackage("org.mobileharness.app"))
        assertTrue(AppChannels.isHarnessPackage("org.mobileharness.app.release"))
    }

    @Test fun packageMatchingDoesNotExcludeUnrelatedApplications() {
        assertFalse(AppChannels.isHarnessPackage(null))
        assertFalse(AppChannels.isHarnessPackage(""))
        assertFalse(AppChannels.isHarnessPackage("org.mobileharness.app.release.other"))
        assertFalse(AppChannels.isHarnessPackage("org.mobileharness.fixture"))
        assertFalse(AppChannels.isHarnessPackage("com.google.android.deskclock"))
    }

    @Test fun developmentRecognizesReleaseServiceAndReleaseRecognizesDevelopmentService() {
        assertTrue(AppChannels.isCompanionService(
            AppChannels.DEVELOPMENT_PACKAGE, AppChannels.RELEASE_PACKAGE, AppChannels.ACCESSIBILITY_SERVICE_CLASS,
        ))
        assertTrue(AppChannels.isCompanionService(
            AppChannels.RELEASE_PACKAGE, AppChannels.DEVELOPMENT_PACKAGE, AppChannels.ACCESSIBILITY_SERVICE_CLASS,
        ))
    }

    @Test fun ownServiceDoesNotBlockItself() {
        listOf(AppChannels.DEVELOPMENT_PACKAGE, AppChannels.RELEASE_PACKAGE).forEach { channel ->
            assertFalse(AppChannels.isCompanionService(channel, channel, AppChannels.ACCESSIBILITY_SERVICE_CLASS))
        }
    }

    @Test fun unrelatedServicesDoNotCountAsCompanionAutomation() {
        assertFalse(AppChannels.isCompanionService(
            AppChannels.DEVELOPMENT_PACKAGE, AppChannels.RELEASE_PACKAGE, "com.example.OtherService",
        ))
        assertFalse(AppChannels.isCompanionService(
            AppChannels.DEVELOPMENT_PACKAGE, "com.example.app", AppChannels.ACCESSIBILITY_SERVICE_CLASS,
        ))
    }

    @Test fun releaseServiceClassRemainsInTheSourceNamespace() {
        assertTrue(AppChannels.isCompanionService(
            AppChannels.DEVELOPMENT_PACKAGE, AppChannels.RELEASE_PACKAGE,
            "org.mobileharness.app.platform.HarnessAccessibilityService",
        ))
        assertFalse(AppChannels.isCompanionService(
            AppChannels.DEVELOPMENT_PACKAGE, AppChannels.RELEASE_PACKAGE,
            "org.mobileharness.app.release.platform.HarnessAccessibilityService",
        ))
    }
}
