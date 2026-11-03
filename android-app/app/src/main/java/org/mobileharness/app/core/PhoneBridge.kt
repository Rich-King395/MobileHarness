package org.mobileharness.app.core

/**
 * Replacing a service connection is not a native-input terminal. Retired ports
 * remain barrier evidence until their own inputInFlight becomes false.
 * This bridge never cancels native input or manufactures its terminal state.
 */
internal class PhoneBridge : PhonePort {
    private val lock = Any()
    private var attached: PhonePort? = null
    private var session: SessionOptions? = null
    private val detachedInputs = mutableListOf<PhonePort>()

    fun attach(port: PhonePort) = synchronized(lock) {
        if (attached !== port) {
            retainInputLocked(attached)
            attached?.configureSession(null)
        }
        attached = port
        port.configureSession(session)
        detachedInputs.removeAll { it === port }
        detachedInputPendingLocked()
        Unit
    }

    fun isAttached(port: PhonePort): Boolean = synchronized(lock) { attached === port }

    fun detach(port: PhonePort): Boolean = synchronized(lock) {
        if (attached !== port) {
            detachedInputPendingLocked()
            false
        } else {
            retainInputLocked(port)
            port.configureSession(null)
            attached = null
            detachedInputPendingLocked()
            true
        }
    }

    override val connected: Boolean get() = synchronized(lock) { attached?.connected == true }

    override val inputInFlight: Boolean get() = synchronized(lock) {
        val retiredPending = detachedInputPendingLocked()
        attached?.inputInFlight == true || retiredPending
    }

    override suspend fun observe(): ScreenFrame = readyPort().observe()

    override fun configureSession(options: SessionOptions?) = synchronized(lock) {
        session = options?.copy(authorizedPackages = options.authorizedPackages.toSet(),
            authorizedAppLabels = options.authorizedAppLabels.toMap())
        attached?.configureSession(session)
        Unit
    }

    override suspend fun perform(
        action: PhoneAction,
        frame: ScreenFrame,
        allowedPackages: Set<String>,
        canDispatch: () -> Boolean,
    ): ActionResult = readyPort().perform(action, frame, allowedPackages, canDispatch)

    override fun setCaptureEnabled(enabled: Boolean) {
        val port = synchronized(lock) { attached }
        port?.setCaptureEnabled(enabled)
    }

    override fun showState(snapshot: RunSnapshot) {
        val port = synchronized(lock) { attached }
        port?.showState(snapshot)
    }

    private fun readyPort(): PhonePort = synchronized(lock) {
        if (detachedInputPendingLocked()) throw RunFailure("DEVICE", "INPUT_STATUS_UNKNOWN")
        attached ?: throw RunFailure("DEVICE", "SERVICE_DISCONNECTED")
    }

    private fun retainInputLocked(port: PhonePort?) {
        if (port != null && port.inputInFlight && detachedInputs.none { it === port }) detachedInputs.add(port)
    }

    private fun detachedInputPendingLocked(): Boolean {
        var pending = false
        val iterator = detachedInputs.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().inputInFlight) pending = true else iterator.remove()
        }
        return pending
    }
}
