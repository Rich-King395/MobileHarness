package org.mobileharness.app.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStabilityGateTest {
    @Test fun firstMatchingPackageIsNotEnough() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(matchesTarget = true, nowMs = 10_000))
    }

    @Test fun matchingSamplesMustAlsoReachTheDurationBoundary() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(true, 10_000))
        assertFalse(gate.sample(true, 10_100))
        assertFalse(gate.sample(true, 10_749))
        assertTrue(gate.sample(true, 10_750))
    }

    @Test fun elapsedDurationDoesNotReplaceThreeMatchingSamples() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(true, 0))
        assertFalse(gate.sample(true, 1_000))
        assertTrue(gate.sample(true, 1_100))
    }

    @Test fun unavailableOrDifferentForegroundRestartsTheFullStableWindow() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(true, 0))
        assertFalse(gate.sample(true, 700))
        assertFalse(gate.sample(false, 725))
        assertFalse(gate.sample(true, 1_000))
        assertFalse(gate.sample(true, 1_500))
        assertFalse(gate.sample(true, 1_749))
        assertTrue(gate.sample(true, 1_750))
    }

    @Test fun resetAlsoDiscardsThePreviousSampleCount() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(true, 0))
        assertFalse(gate.sample(true, 100))
        assertFalse(gate.sample(true, 200))
        assertFalse(gate.sample(false, 300))
        assertFalse(gate.sample(true, 400))
        assertFalse(gate.sample(true, 1_150))
        assertTrue(gate.sample(true, 1_250))
    }

    @Test fun backwardsClockCannotReuseAnEarlierStableWindow() {
        val gate = ForegroundStabilityGate()
        assertFalse(gate.sample(true, 1_000))
        assertFalse(gate.sample(true, 1_700))
        assertFalse(gate.sample(true, 500))
        assertFalse(gate.sample(true, 1_200))
        assertTrue(gate.sample(true, 1_250))
    }
}
