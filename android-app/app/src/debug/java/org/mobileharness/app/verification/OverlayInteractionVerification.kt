package org.mobileharness.app.verification

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.mobileharness.app.BuildConfig
import org.mobileharness.app.HarnessApplication
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.ControlCommand
import org.mobileharness.app.platform.HarnessAccessibilityService
import org.mobileharness.app.platform.OverlayControlPanel
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real Coordinator, screenshot and executor; local controlled replies, actual external overlay taps.
 * A genuine native result is held at PhonePort's return boundary to make PAUSING observable.
 * No target root, nodes, pixels or package reads take place during MANUAL.
 */
class OverlayInteractionVerification(context: Context) {
    private val context = context.applicationContext
    private val runId = UUID.randomUUID().toString()
    private val flags = linkedMapOf<String, Boolean>()
    private val stages = mutableListOf<String>()
    private var started = 0L
    private var stage = "NOT_STARTED"
    private var status = "RUNNING"
    private var code = "OK"
    private var harness = true
    private var service: HarnessAccessibilityService? = null
    private var previous: RuntimeControls? = null
    private var coordinator: Coordinator? = null
    private var phone: DeviceVerification.GuardedPhone? = null
    private var ownedScope: CoroutineScope? = null
    private val gateway = HeldGateway()
    private val releaseRequested = CompletableDeferred<Unit>()
    private var aborted = false

    suspend fun run(useHarness: Boolean) {
        if (!RUNNING.compareAndSet(false, true)) return
        harness = useHarness
        started = SystemClock.elapsedRealtime()
        current = this
        try {
            val app = context as? HarnessApplication ?: fail("APPLICATION_UNAVAILABLE")
            val real = HarnessAccessibilityService.instance?.takeIf { it.connected } ?: fail("SERVICE_UNAVAILABLE")
            service = real
            previous = withContext(Dispatchers.Main.immediate) {
                val old = real.controls ?: fail("CONTROLS_UNAVAILABLE")
                if (old !== app.runtime || old.snapshot.value.state !in setOf(RunState.IDLE, RunState.ENDED) || real.inputInFlight) fail("SERVICE_NOT_IDLE")
                if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) fail("DEVICE_LOCKED")
                if (!real.overlayStopAvailable) fail("OVERLAY_UNAVAILABLE")
                real.setCaptureEnabled(false)
                old
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            ownedScope = scope
            val audit = DeviceVerification.MemoryAudit()
            val guarded = DeviceVerification.GuardedPhone(real)
            phone = guarded
            val runtime = Coordinator(scope, gateway, guarded, audit)
            coordinator = runtime
            guarded.coordinator = runtime
            guarded.holdInputTerminals = true
            withContext(Dispatchers.Main.immediate) {
                real.controls = runtime
                real.showState(runtime.snapshot.value)
                context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                    .setClassName(FIXTURE, "$FIXTURE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            delay(650)
            withContext(Dispatchers.Main.immediate) {
                guarded.requireFixture()
                if (HarnessAccessibilityService.sensitivePageVisible) fail("SENSITIVE_PAGE_VISIBLE")
                runtime.start(SessionOptions("Synthetic physical overlay interaction", FIXTURE, setOf(FIXTURE), harness, 8,
                    ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"),
                    ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic")))
            }
            await { gateway.guiCalls.size == 1 }
            val first = gateway.guiCalls[0]
            val arguments = withContext(Dispatchers.Main.immediate) {
                val root = guarded.checkedRoot()
                try {
                    val nodes = root.findAccessibilityNodeInfosByViewId("$FIXTURE:id/fixture_long_press")
                    try {
                        val node = nodes.firstOrNull { it.refresh() && it.isVisibleToUser } ?: fail("FIXTURE_BUTTON_UNAVAILABLE")
                        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                        mapOf<String, Any?>("x" to ((bounds.left + bounds.width() * 85 / 100).toLong() * 1000 / first.width).toInt().coerceIn(0, 999),
                            "y" to (bounds.centerY().toLong() * 1000 / first.height).toInt().coerceIn(0, 999), "duration_ms" to 2000)
                    } finally { nodes.forEach { it.recycle() } }
                } finally { root.recycle() }
            }
            first.answer.complete(GuiReply(first.id, PhoneAction("long_press", arguments), JSONObject(arguments).toString()))
            await { guarded.heldInputTerminals.size == 1 }
            requireFlag("genuine_native_result_held", guarded.nativeResults.size == 1 && !real.inputInFlight)
            publish("WAIT_TAKEOVER")
            await { runtime.snapshot.value.state == RunState.PAUSING }
            withContext(Dispatchers.Main.immediate) {
                requireFlag("physical_takeover_reached_pausing", audit.transitionCount("takeover_requested") == 1)
                requireFlag("pausing_capture_disabled", !real.captureEnabled)
                requireFlag("pausing_no_return_button", !overlayCommands().contains(ControlCommand.RETURN.label))
            }
            publish("WAIT_TERMINAL_RELEASE")
            withTimeout(60_000) { releaseRequested.await() }
            await { runtime.snapshot.value.state == RunState.MANUAL }
            val manualObservations = guarded.observeCalls.get()
            requireFlag("manual_after_real_terminal", guarded.nativeResults.size == 1 && !real.inputInFlight)
            delay(300)
            requireFlag("manual_no_new_observation", guarded.observeCalls.get() == manualObservations && !real.captureEnabled)
            publish("WAIT_RETURN")
            await { runtime.snapshot.value.handoffUsed || runtime.snapshot.value.state == RunState.PREPARING }
            if (harness) {
                await { gateway.plannerCalls.size == 1 }
                gateway.plannerCalls[0].complete(PlannerDecision("INCOMPLETE", "EXECUTE", "Continue synthetic fixture task", listOf("Synthetic fixture visible")))
            }
            await { gateway.guiCalls.size == 2 && runtime.snapshot.value.state == RunState.AUTO }
            withContext(Dispatchers.Main.immediate) {
                val value = runtime.snapshot.value
                requireFlag("fresh_return_observation", guarded.observeCalls.get() == manualObservations + 1)
                requireFlag("single_return_committed", value.handoffUsed && audit.transitionCount("return_committed") == 1)
                requireFlag("shared_budget_not_reset", value.guiUsed == 2 && value.budget == 8)
                requireFlag("expected_planner_count", value.plannerUsed == if (harness) 1 else 0)
                requireFlag("resumed_overlay_has_no_takeover", !overlayCommands().contains(ControlCommand.TAKEOVER.label))
                requireFlag("one_native_action", guarded.performCalls.get() == 1)
            }
            publish("WAIT_STOP")
            await { runtime.snapshot.value.state == RunState.ENDED }
            requireFlag("stopped_by_user_command", audit.lastEnd()["reason"] == "user_stop")
            status = "PASS"
            code = "PHYSICAL_OVERLAY_HANDOFF_CONFIRMED"
        } catch (_: TimeoutCancellationException) {
            status = "FAIL"; code = "INTERACTION_STAGE_TIMEOUT"
        } catch (cancelled: CancellationException) {
            status = "CANCELLED"; code = "CANCELLED"
        } catch (failure: Failure) {
            status = when (failure.code) {
                "SERVICE_UNAVAILABLE", "CONTROLS_UNAVAILABLE", "SERVICE_NOT_IDLE", "DEVICE_LOCKED", "OVERLAY_UNAVAILABLE" -> "BLOCKED"
                "ABORTED", "INTERACTION_STOPPED" -> "CANCELLED"
                else -> "FAIL"
            }
            code = failure.code
        } catch (_: Exception) {
            status = "FAIL"; code = "DIAGNOSTIC_FAILURE"
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    // A rejected preflight must leave an existing user session untouched.
                    if (coordinator != null && service?.controls === coordinator) {
                        service?.setCaptureEnabled(false)
                        coordinator?.stop()
                    }
                    phone?.releaseInputTerminals()
                    phone?.releaseObservations()
                    gateway.release()
                }
                val terminal = try {
                    withTimeout(6_000) {
                        while (service?.inputInFlight == true || coordinator?.snapshot?.value?.state?.let { it !in setOf(RunState.IDLE, RunState.ENDED) } == true) delay(20)
                    }
                    true
                } catch (_: TimeoutCancellationException) { false }
                withContext(Dispatchers.Main.immediate) {
                    val real = service
                    val old = previous
                    flags["native_terminal_confirmed"] = terminal
                    flags["controls_restored"] = terminal && real != null && old != null && real === HarnessAccessibilityService.instance && real.controls === coordinator
                    if (flags["controls_restored"] == true) {
                        real!!.setCaptureEnabled(false)
                        real.configureSession(null)
                        real.controls = old
                        real.showState(old!!.snapshot.value)
                    }
                    if (!terminal) { status = "UNCERTAIN"; code = "INPUT_TERMINAL_UNCONFIRMED" }
                    else if (status == "PASS" && flags["controls_restored"] != true) { status = "FAIL"; code = "CONTROLS_NOT_RESTORED" }
                    // Do not cancel a scope still responsible for an unresolved native input.
                    if (terminal) ownedScope?.cancel()
                    try {
                        publish(if (status == "PASS") "COMPLETED" else "FINISHED")
                    } catch (_: Exception) {
                        status = "FAIL"; code = "METADATA_WRITE_FAILED"
                    } finally {
                        last = record()
                        if (current === this@OverlayInteractionVerification) current = null
                        RUNNING.set(false)
                    }
                }
            }
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(60_000) {
        while (!predicate()) {
            if (aborted) fail("ABORTED")
            if (service !== HarnessAccessibilityService.instance || service?.connected != true) fail("SERVICE_CHANGED")
            if (coordinator?.snapshot?.value?.state == RunState.BLOCKED) fail("DEVICE_BLOCKED")
            if (coordinator?.snapshot?.value?.state == RunState.ENDED) fail("INTERACTION_STOPPED")
            delay(20)
        }
    }

    private fun requireFlag(name: String, value: Boolean) {
        flags[name] = value
        if (!value) fail(name.uppercase())
    }

    private fun publish(next: String) {
        stage = next
        if (stages.lastOrNull() != next) stages.add(next)
        val file = AtomicFile(File(context.noBackupFilesDir, "overlay-interaction-verification.json"))
        val stream = file.startWrite()
        try { stream.write(record().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    private fun record(): JSONObject {
        val value = coordinator?.snapshot?.value
        return JSONObject().put("schema_version", "1.0.0").put("app_version", BuildConfig.VERSION_NAME)
            .put("run_id", runId).put("variant", if (harness) "ON" else "OFF").put("status", status).put("code", code)
            .put("stage", stage).put("stages", JSONArray(stages)).put("state", value?.state?.name ?: "NONE")
            .put("gui_used", value?.guiUsed ?: 0).put("planner_used", value?.plannerUsed ?: 0).put("budget", value?.budget ?: 0)
            .put("gui_network_requests", 0).put("planner_network_requests", 0)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("flags", JSONObject(flags))
    }

    /** Called on main by the DUMP-protected receiver. Does not inspect any target view/window. */
    private fun probe(command: String): JSONObject {
        if (command == "release_terminal") {
            if (stage != "WAIT_TERMINAL_RELEASE" || coordinator?.snapshot?.value?.state != RunState.PAUSING || phone?.nativeResults?.size != 1) {
                return JSONObject().put("status", "REJECTED").put("code", "INVALID_RELEASE_STATE")
            }
            phone?.releaseInputTerminals()
            releaseRequested.complete(Unit)
            return JSONObject().put("status", "RELEASED").put("stage", stage)
        }
        if (command == "abort") {
            aborted = true
            coordinator?.stop()
            phone?.releaseInputTerminals()
            releaseRequested.complete(Unit)
            return JSONObject().put("status", "ABORT_REQUESTED")
        }
        if (command != "probe") return JSONObject().put("status", "REJECTED").put("code", "UNKNOWN_COMMAND")
        val response = record().put("capture_enabled", service?.captureEnabled == true)
        if (service !== HarnessAccessibilityService.instance || service?.controls !== coordinator) return response.put("geometry_available", false)
        val panel = panel()
        val geometry = JSONObject()
        fun add(name: String, view: View) {
            if (!panel.available || !view.isShown || view.width <= 0 || view.height <= 0) return
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            geometry.put(name, JSONObject().put("center_x", location[0] + view.width / 2).put("center_y", location[1] + view.height / 2))
        }
        add("ball", field(panel, "ball") as View)
        add("STOP", field(panel, "stop") as View)
        val primary = field(panel, "primary") as Button
        val action = primary.tag as? ControlCommand
        if (action != null && primary.isEnabled) add(action.name, primary)
        return response.put("geometry_available", geometry.length() > 0).put("geometry", geometry)
    }

    private fun overlayCommands(): Set<String> {
        val primary = field(panel(), "primary") as Button
        val action = primary.tag as? ControlCommand
        return if (action != null && primary.isEnabled) setOf(action.label) else emptySet()
    }

    private fun panel(): OverlayControlPanel = HarnessAccessibilityService::class.java.getDeclaredField("overlay").let {
        it.isAccessible = true
        it.get(checkNotNull(service)) as? OverlayControlPanel ?: fail("OVERLAY_UNAVAILABLE")
    }

    private fun field(panel: OverlayControlPanel, name: String): Any = OverlayControlPanel::class.java.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(panel) ?: fail("OVERLAY_VIEW_UNAVAILABLE")
    }

    private class HeldGateway : ModelGateway {
        data class Call(val id: String, val width: Int, val height: Int, val answer: CompletableDeferred<GuiReply> = CompletableDeferred())
        val guiCalls = CopyOnWriteArrayList<Call>()
        val plannerCalls = CopyOnWriteArrayList<CompletableDeferred<PlannerDecision>>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val call = Call(UUID.randomUUID().toString(), context.frame.width, context.frame.height)
            guiCalls.add(call)
            return withTimeout(60_000) { call.answer.await() }
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val call = CompletableDeferred<PlannerDecision>()
            plannerCalls.add(call)
            return withTimeout(60_000) { call.await() }
        }
        override fun cancelAll() = Unit
        fun release() {
            guiCalls.forEach { it.answer.complete(GuiReply(it.id, PhoneAction("finish", mapOf("outcome" to "unable", "message" to "SYNTHETIC")), "{}")) }
            plannerCalls.forEach { it.complete(PlannerDecision("INCOMPLETE", "FALLBACK", "", emptyList())) }
        }
    }

    private class Failure(val code: String) : Exception()
    private fun fail(code: String): Nothing = throw Failure(code)

    companion object {
        private const val FIXTURE = "org.mobileharness.fixture"
        private val RUNNING = AtomicBoolean(false)
        @Volatile private var current: OverlayInteractionVerification? = null
        @Volatile private var last: JSONObject? = null
        fun command(value: String): JSONObject = try {
            current?.probe(value) ?: last?.let { JSONObject(it.toString()) } ?: JSONObject().put("status", "IDLE")
        } catch (_: Exception) { JSONObject().put("status", "FAIL").put("code", "PROBE_UNAVAILABLE") }
    }
}
