package org.mobileharness.app

import android.app.UiAutomation
import androidx.test.runner.AndroidJUnitRunner

/** Preserve the real accessibility service; no UI automation input or screenshots are used. */
class LiveServiceTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        super.onStart()
    }
}
