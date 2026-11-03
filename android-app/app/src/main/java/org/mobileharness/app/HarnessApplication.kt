package org.mobileharness.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.mobileharness.app.core.*
import org.mobileharness.app.model.QwenGateway
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.storage.MinimalAudit
import org.mobileharness.app.storage.ProfileStore

class HarnessApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val connected = MutableStateFlow(false)
    // A service can bind after the first composition. Observe its identity rather than
    // choosing a nullable global instance once and remaining on an empty readiness flow.
    internal val phoneConnection = MutableStateFlow<HarnessAccessibilityService?>(null)
    val profiles by lazy { ProfileStore(this) }
    val audit by lazy { MinimalAudit(this) }
    private val bridge = PhoneBridge()
    val runtime: RuntimeControls by lazy { Coordinator(scope, QwenGateway(this, profiles::keyFor), bridge, audit) }
    override fun onCreate() {
        super.onCreate()
        runCatching { audit.markInterrupted() }
    }
    fun attachPhone(phone: HarnessAccessibilityService) {
        bridge.attach(phone)
        phone.controls = runtime
        phone.showState(runtime.snapshot.value)
        phoneConnection.value = phone
        connected.value = true
    }
    fun detachPhone(phone: HarnessAccessibilityService) {
        if (!bridge.isAttached(phone)) return
        runtime.environmentUnavailable("SERVICE_DISCONNECTED")
        bridge.detach(phone)
        phoneConnection.value = null
        connected.value = false
    }
}
