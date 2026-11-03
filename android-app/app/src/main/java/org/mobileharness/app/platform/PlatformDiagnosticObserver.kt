package org.mobileharness.app.platform

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import org.mobileharness.app.core.ScreenFrame

/** Optional in-process debug probe. Implementations must not retain pixels or change inputs. */
internal interface PlatformDiagnosticObserver {
    fun imageEncoded(source: Bitmap, png: ByteArray, screenshotUptimeMs: Long, requestedUptimeMs: Long) = Unit
    fun gestureDispatch(description: GestureDescription, frame: ScreenFrame) = Unit
    fun gestureTerminal(description: GestureDescription, status: String) = Unit
}
