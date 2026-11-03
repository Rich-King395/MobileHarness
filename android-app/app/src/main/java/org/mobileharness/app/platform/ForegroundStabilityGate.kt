package org.mobileharness.app.platform

/** Package identity may arrive before Home finishes its launch animation. No pixels are sampled. */
internal class ForegroundStabilityGate(
    private val minDurationMs: Long = 750,
    private val minSamples: Int = 3,
) {
    init {
        require(minDurationMs >= 0 && minSamples > 0)
    }

    private var matchedSinceMs: Long? = null
    private var matchingSamples = 0

    /** The caller supplies monotonic time and handles authorization, cancellation and timeout. */
    fun sample(matchesTarget: Boolean, nowMs: Long): Boolean {
        if (!matchesTarget) {
            matchedSinceMs = null
            matchingSamples = 0
            return false
        }
        val since = matchedSinceMs
        if (since == null || nowMs < since) {
            matchedSinceMs = nowMs
            matchingSamples = 1
        } else {
            matchingSamples++
        }
        return matchingSamples >= minSamples && nowMs - checkNotNull(matchedSinceMs) >= minDurationMs
    }
}
