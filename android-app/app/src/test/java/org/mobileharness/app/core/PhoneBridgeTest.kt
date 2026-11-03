package org.mobileharness.app.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PhoneBridgeTest {
    @Test fun taskScopeIsFrozenPropagatedToReplacementAndClearedWithoutMaskingOldInput() {
        val bridge = PhoneBridge()
        val apps = mutableSetOf("synthetic.authorized")
        val labels = mutableMapOf("synthetic.authorized" to "Synthetic App")
        // Existing fixture preserves the legacy execution policy while testing scope copying.
        val options = SessionOptions("synthetic", "synthetic.home", apps, true, 30, ModelProfile(), ModelProfile(),
            StartMode.HOME_AUTO, "synthetic.home", labels, automaticRecovery = false)
        bridge.configureSession(options)
        val old = Port().apply { nativePending = true }
        bridge.attach(old)
        apps.add("synthetic.unapproved")
        labels["synthetic.authorized"] = "changed"
        assertEquals(setOf("synthetic.authorized"), old.configured?.authorizedPackages)
        assertEquals("Synthetic App", old.configured?.authorizedAppLabels?.get("synthetic.authorized"))
        val replacement = Port()
        bridge.attach(replacement)
        assertNull(old.configured)
        assertEquals(setOf("synthetic.authorized"), replacement.configured?.authorizedPackages)
        assertEquals(StartMode.HOME_AUTO, replacement.configured?.startMode)
        assertFalse(bridge.detach(old))
        assertNotNull(replacement.configured)
        bridge.configureSession(null)
        assertNull(replacement.configured)
        assertTrue(bridge.inputInFlight)
        old.nativePending = false
        assertFalse(bridge.inputInFlight)
    }

    @Test fun detachedInputRemainsPendingUntilItsOwnTerminalAndIsThenReleased() {
        val bridge = PhoneBridge()
        val old = Port().apply { nativePending = true }
        bridge.attach(old)
        assertTrue(bridge.connected)
        assertTrue(bridge.detach(old))
        assertFalse(bridge.connected)
        repeat(3) { assertTrue(bridge.inputInFlight) }
        assertEquals(0, old.performCalls)
        assertEquals(0, old.captureChanges)

        old.nativePending = false // Represents the old port's actual terminal callback.
        assertFalse(bridge.inputInFlight)
        val terminalReadCount = old.pendingReads
        repeat(3) { assertFalse(bridge.inputInFlight) }
        // Once terminal evidence was observed, the old port is no longer retained/polled.
        assertEquals(terminalReadCount, old.pendingReads)
    }

    @Test fun replacementCannotMaskOldInputOrDispatchUntilOldTerminal() = runTest {
        val bridge = PhoneBridge()
        val old = Port().apply { nativePending = true }
        val replacement = Port()
        bridge.attach(old)
        bridge.attach(replacement) // Also covers replacement arriving before old onDestroy.
        assertTrue(bridge.connected)
        assertTrue(bridge.inputInFlight)
        assertFalse(bridge.detach(old))
        assertTrue(bridge.isAttached(replacement))

        val frame = ScreenFrame("synthetic", byteArrayOf(1), 1, 1)
        try {
            bridge.perform(PhoneAction("tap", emptyMap()), frame, emptySet()) { true }
            fail("A replacement must not bypass a detached native-input barrier")
        } catch (failure: RunFailure) {
            assertEquals("DEVICE", failure.category)
            assertEquals("INPUT_STATUS_UNKNOWN", failure.code)
        }
        assertEquals(0, replacement.performCalls)
        assertEquals(0, old.performCalls)
        assertEquals(0, old.captureChanges)

        replacement.nativePending = true
        old.nativePending = false
        assertTrue(bridge.inputInFlight) // The replacement has its own independent input.
        val oldTerminalReadCount = old.pendingReads
        replacement.nativePending = false
        assertFalse(bridge.inputInFlight)
        assertEquals(oldTerminalReadCount, old.pendingReads)
        assertEquals("completed", bridge.perform(PhoneAction("tap", emptyMap()), frame, emptySet()) { true }.status)
        assertEquals(1, replacement.performCalls)
        assertEquals(0, old.performCalls)
    }

    private class Port : PhonePort {
        override val connected = true
        var nativePending = false
        var pendingReads = 0
        var performCalls = 0
        var captureChanges = 0
        var configured: SessionOptions? = null
        override val inputInFlight: Boolean get() { pendingReads++; return nativePending }
        override fun configureSession(options: SessionOptions?) { configured = options }
        override suspend fun observe() = ScreenFrame("synthetic", byteArrayOf(1), 1, 1)
        override suspend fun perform(
            action: PhoneAction,
            frame: ScreenFrame,
            allowedPackages: Set<String>,
            canDispatch: () -> Boolean,
        ): ActionResult {
            performCalls++
            return ActionResult(if (canDispatch()) "completed" else "rejected")
        }
        override fun setCaptureEnabled(enabled: Boolean) { captureChanges++ }
        override fun showState(snapshot: RunSnapshot) = Unit
    }
}
