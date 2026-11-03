package org.mobileharness.app.verification

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.mobileharness.app.core.*
import org.mobileharness.app.platform.HarnessAccessibilityService
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real PhonePort, synthetic target, in-memory model/audit substitutes. No network, files, shell
 * input, UiDevice, Espresso, screenshots from UiAutomation, or private application observations.
 * Runs inside the debug application process, without instrumentation lifecycle changes.
 */
class DeviceVerification(context: Context) {
    private val context = context.applicationContext
    internal suspend fun withFixture(block: suspend (Live) -> Unit) = live(block)
    internal fun harnessCases(): List<Pair<String, suspend () -> Unit>> = planningDeviceCases() + controlDeviceCases()
    internal suspend fun runHarnessAcceptance(prefix: String = "", onCaseResult: (String, Boolean, String) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onCaseResult("HARNESS_BUSY", false, "VERIFICATION_ALREADY_RUNNING"); return
        }
        try {
            for ((id, body) in harnessCases().filter { it.first.startsWith(prefix) }) {
                try { body(); onCaseResult(id, true, "OK") }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    onCaseResult(id, false, if (failure is VerificationFailure) failure.code else diagnosticCode(failure))
                    return // A failed case is archived and reviewed before any further device input.
                }
            }
        } finally { RUNNING.set(false) }
    }

    /** Reports only fixed case identifiers and fixed diagnostic codes. Never serializes exceptions. */
    suspend fun runAll(onCaseResult: (id: String, passed: Boolean, code: String) -> Unit) {
        if (!RUNNING.compareAndSet(false, true)) {
            onCaseResult("D-01", false, "VERIFICATION_ALREADY_RUNNING")
            return
        }
        try {
            val cases: List<Pair<String, suspend () -> Unit>> = listOf(
                "D-01" to { case01() }, "D-02" to { case02() },
                "D-03" to { case03() }, "D-04" to { case04() },
            )
            for ((id, body) in cases) {
                try {
                    body()
                    onCaseResult(id, true, "OK")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val code = when (failure) {
                        is VerificationFailure -> {
                            val line = failure.stackTrace.firstOrNull {
                                it.fileName == "DeviceVerification.kt" && !it.className.endsWith("\$Companion") && !it.className.endsWith("\$VerificationFailure")
                            }?.lineNumber?.takeIf { it in 1..100_000 } ?: 0
                            "${failure.code}_L$line"
                        }
                        is RunFailure -> failure.code.takeIf { it in setOf("AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED") } ?: "DEVICE_FAILURE"
                        else -> diagnosticCode(failure)
                    }
                    onCaseResult(id, false, code)
                    // A failed/stopped case never starts a later case automatically.
                    return
                }
            }
        } finally { RUNNING.set(false) }
    }
    private suspend fun case01() {
        live { f ->
            f.start(harness = false, budget = 12)
            var call = f.gui(0)
            assertTrue(f.phone.frameCount.get() > 0)
            assertTrue(call.width > 0 && call.height > 0)

            f.gateway.answer(call, f.at(call, "fixture_click", "tap"))
            call = f.gui(1)
            assertEquals("点击次数：1", f.text("fixture_click_count"))

            f.gateway.answer(call, f.at(call, "fixture_long_press", "long_press", 2_000))
            call = f.gui(2)
            assertEquals("长按次数：1", f.text("fixture_long_press_count"))

            f.gateway.answer(call, f.at(call, "fixture_input", "tap"))
            call = f.gui(3)
            f.gateway.answer(call, action("input_text", mapOf("text" to "SYNTHETIC-完整替换-42")))
            call = f.gui(4)
            assertEquals("SYNTHETIC-完整替换-42", f.text("fixture_input"))
            f.gateway.answer(call, action("input_text", mapOf("text" to "")))
            call = f.gui(5)
            assertEquals("", f.text("fixture_input"))
            f.gateway.answer(call, action("input_text", mapOf("text" to "SYNTHETIC-SAVED-42")))
            call = f.gui(6)
            f.gateway.answer(call, f.at(call, "fixture_save", "tap"))
            call = f.gui(7)
            assertEquals("保存状态：已保存", f.text("fixture_save_state"))
            assertEquals("已保存内容：SYNTHETIC-SAVED-42", f.text("fixture_saved_value"))

            val scroll = f.bounds("fixture_scroll")
            val firstTop = f.bounds("fixture_row_01").top
            val x = norm(scroll.left + scroll.width() * 85 / 100, call.width)
            f.gateway.answer(call, action("swipe", mapOf(
                "from_x" to x, "from_y" to norm(scroll.top + scroll.height() * 4 / 5, call.height),
                "to_x" to x, "to_y" to norm(scroll.top + scroll.height() / 5, call.height),
                "duration_ms" to 500,
            )))
            f.gui(8)
            val after = f.visibleBounds("fixture_row_01")
            assertTrue("Fixture scroll did not move", after == null || after.top < firstTop)
            assertEquals(0, f.gateway.plannerCalls.size)
            assertFalse(f.real.inputInFlight)
        }
    }

    private suspend fun case02() {
        live { f ->
            f.gateway.ignoreCancellation.add(0)
            f.start(harness = false)
            val old = f.gui(0)
            val staleTap = f.at(old, "fixture_click", "tap")
            f.takeover()
            val observations = f.phone.observeCalls.get()
            val calls = f.phone.performCalls.get()
            // No root/tree/text/coordinates/screenshot reads while the person owns control.
            delay(650)
            assertEquals(RunState.MANUAL, f.state().state)
            assertFalse(f.real.captureEnabled)
            assertEquals(observations, f.phone.observeCalls.get())
            assertEquals(calls, f.phone.performCalls.get())

            f.giveBack()
            val fresh = f.gui(1)
            assertNotEquals(old.frameId, fresh.frameId)
            assertTrue(f.phone.observeCalls.get() > observations)
            assertEquals(Phase.ORIGINAL, fresh.phase)
            assertEquals(0, fresh.returnNoticeIndex)
            assertTrue(fresh.historyIds.isEmpty())
            // Deliver the old HTTP-equivalent reply after return and a newer GUI request.
            f.gateway.answer(old, staleTap)
            delay(650)
            assertEquals(calls, f.phone.performCalls.get())
            assertEquals("点击次数：0", f.text("fixture_click_count"))
            assertEquals(0, f.gateway.plannerCalls.size)
            assertTrue(fresh.historyIds.isEmpty())
        }
    }

    private suspend fun case03() {
        live { f ->
            f.start(harness = false)
            val call = f.gui(0)
            f.gateway.answer(call, f.at(call, "fixture_long_press", "long_press", 2_000))
            await("native gesture dispatch") { f.real.inputInFlight }
            withContext(Dispatchers.Main.immediate) { f.coordinator.takeover() }
            assertEquals(RunState.PAUSING, f.state().state)
            assertTrue(f.real.inputInFlight)
            val count = f.phone.observeCalls.get()
            delay(150)
            assertEquals(RunState.PAUSING, f.state().state)
            assertTrue(f.real.inputInFlight)
            assertFalse(f.real.captureEnabled)
            await("genuine gesture terminal and manual barrier") { f.state().state == RunState.MANUAL }
            assertFalse(f.real.inputInFlight)
            assertEquals(count, f.phone.observeCalls.get())
            assertEquals(1, f.phone.performCalls.get())
            // MANUAL is published before its audit event within the same Main actor turn.
            // Default may observe that StateFlow write first; cross the actor boundary before
            // requiring the event, rather than polling/retrying the event assertion.
            withContext(Dispatchers.Main.immediate) {
                assertEquals(1, f.audit.transitionCount("manual_granted"))
            }
            delay(450) // Intentional pacing, never a screenshot retry.
            assertEquals(count, f.phone.observeCalls.get())
            withContext(Dispatchers.Main.immediate) {
                assertEquals(1, f.audit.transitionCount("manual_granted"))
            }
            f.giveBack()
            val returned = f.gui(1)
            assertEquals(listOf(call.id), returned.historyIds)
            assertEquals("长按次数：1", f.text("fixture_long_press_count"))
        }
    }

    private suspend fun case04() {
        for (checkFinishes in listOf(false, true)) live { f ->
            f.start(harness = true, budget = 2)
            f.gui(0)
            f.takeover()
            f.giveBack()
            val firstPlanner = f.planner(0)
            f.gateway.answer(firstPlanner, PlannerDecision("INCOMPLETE", "VERIFY",
                "Observe only this synthetic fixture page", listOf("The synthetic page is visible")))
            val checking = f.gui(1)
            assertEquals(Phase.VERIFY, checking.phase)
            assertTrue(checking.historyIds.isEmpty())
            f.gateway.answer(checking, if (checkFinishes) finish("unable")
                else action("wait", mapOf("duration_ms" to 100)))
            if (checkFinishes) {
                val second = f.planner(1)
                assertEquals(2, f.state().guiUsed)
                assertEquals(2, f.state().plannerUsed)
                assertTrue(second.verificationFailed)
                f.gateway.answer(second, PlannerDecision("COMPLETE", "FINISH", "", emptyList()))
            }
            await("bounded verification end") { f.state().state == RunState.ENDED }
            assertEquals(2, f.state().guiUsed)
            assertEquals(if (checkFinishes) 2 else 1, f.state().plannerUsed)
            assertEquals(if (checkFinishes) 2 else 1, f.gateway.plannerCalls.size)
            assertEquals(2, f.gateway.guiCalls.size)
            assertEquals(0, f.phone.performCalls.get())
            assertEquals(if (checkFinishes) "planner" else "none", f.audit.lastEnd()["completion_source"])
            assertEquals(if (checkFinishes) "model_declared_complete" else "budget_exhausted", f.audit.lastEnd()["reason"])
            assertEquals(if (checkFinishes) 1 else 0, f.audit.actionCount("finish"))
        }
    }

    private suspend fun live(block: suspend (Live) -> Unit) {
        var real: HarnessAccessibilityService? = null
        await("system-bound Harness accessibility service") {
            real = HarnessAccessibilityService.instance
            real?.connected == true
        }
        val service = checkNotNull(real)
        val old = withContext(Dispatchers.Main.immediate) {
            val controls = checkNotNull(service.controls)
            check(controls.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED)) { "A real user session is active" }
            check(!service.inputInFlight) { "An input is already in flight" }
            service.setCaptureEnabled(false)
            controls
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val gateway = ControlledGateway()
        val audit = MemoryAudit()
        val phone = GuardedPhone(service)
        val coordinator = Coordinator(scope, gateway, phone, audit)
        phone.coordinator = coordinator
        val fixture = Live(service, phone, gateway, audit, coordinator)
        try {
            withContext(Dispatchers.Main.immediate) {
                service.controls = coordinator
                service.showState(coordinator.snapshot.value)
                context.startActivity(Intent("org.mobileharness.fixture.action.RESET")
                    .setClassName(PACKAGE, "$PACKAGE.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            delay(650)
            withContext(Dispatchers.Main.immediate) { phone.requireFixture() }
            check(!HarnessAccessibilityService.sensitivePageVisible) { "Host configuration is still visible" }
            check(service.overlayStopAvailable) { "Overlay stop entry is unavailable" }
            scope.launch {
                // Package identity only, and never even a root read during human ownership.
                // Bounded to one minute; cancelled with the test scope in every normal cleanup.
                repeat(600) {
                    delay(100)
                    if (coordinator.snapshot.value.state in setOf(RunState.AUTO, RunState.CHECKING, RunState.PREPARING)) {
                        try { phone.requireFixture() } catch (_: Exception) { return@launch }
                    }
                }
            }
            block(fixture)
        } finally {
            withContext(NonCancellable) {
                withContext(Dispatchers.Main.immediate) {
                    service.setCaptureEnabled(false)
                    coordinator.stop()
                    gateway.releaseAll()
                    phone.releaseObservations()
                    phone.releaseInputTerminals()
                }
                // A timeout is a failed/uncertain test, never permission to clear native input.
                await("cleanup genuine input terminal") { !service.inputInFlight }
                await("cleanup coordinator barrier") {
                    coordinator.snapshot.value.state in setOf(RunState.IDLE, RunState.ENDED)
                }
                scope.cancel()
                gateway.clear()
                audit.clear()
                withContext(Dispatchers.Main.immediate) {
                    service.setCaptureEnabled(false)
                    service.controls = old
                    service.showState(old.snapshot.value)
                }
            }
        }
    }

    internal class Live(
        val real: HarnessAccessibilityService,
        val phone: GuardedPhone,
        val gateway: ControlledGateway,
        val audit: MemoryAudit,
        val coordinator: Coordinator,
    ) {
        fun state() = coordinator.snapshot.value
        suspend fun start(harness: Boolean, budget: Int = 8) = withContext(Dispatchers.Main.immediate) {
            coordinator.start(SessionOptions("Synthetic device capability acceptance", PACKAGE, setOf(PACKAGE), harness, budget,
                ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic"),
                ModelProfile(baseUrl = "https://invalid.example/v1", model = "synthetic")))
        }
        suspend fun gui(index: Int): GuiCall {
            await("GUI call $index") { gateway.guiCalls.size > index || state().state == RunState.BLOCKED }
            check(state().state != RunState.BLOCKED) { "Device capability blocked before GUI call $index" }
            return gateway.guiCalls[index]
        }
        suspend fun planner(index: Int): PlannerCall {
            await("Planner call $index") { gateway.plannerCalls.size > index || state().state == RunState.BLOCKED }
            check(state().state != RunState.BLOCKED) { "Device capability blocked before Planner call $index" }
            return gateway.plannerCalls[index]
        }
        suspend fun takeover() {
            withContext(Dispatchers.Main.immediate) { coordinator.takeover() }
            await("manual ownership") { state().state == RunState.MANUAL }
            assertFalse(real.captureEnabled)
        }
        suspend fun giveBack() {
            // Deliberate pacing of independent observations, not a hidden rate-limit retry.
            delay(450)
            withContext(Dispatchers.Main.immediate) { coordinator.giveBack() }
        }
        suspend fun text(id: String): String = node(id, true)!!.text
        suspend fun bounds(id: String): Rect = node(id, true)!!.bounds
        suspend fun visibleBounds(id: String): Rect? = node(id, false)?.bounds
        private suspend fun node(id: String, required: Boolean): NodeValue? = withContext(Dispatchers.Main.immediate) {
            check(state().state != RunState.MANUAL) { "No fixture observations during manual ownership" }
            val root = phone.checkedRoot()
            try {
                val nodes = root.findAccessibilityNodeInfosByViewId("$PACKAGE:id/$id")
                try {
                    val node = nodes.firstOrNull { it.refresh() && it.isVisibleToUser }
                    if (required) checkNotNull(node) { "Synthetic fixture view is not visible: $id" }
                    node?.let {
                        check(it.packageName?.toString() == PACKAGE)
                        val rectangle = Rect().also(it::getBoundsInScreen)
                        NodeValue(rectangle, if (it.isEditable && it.isShowingHintText) "" else it.text?.toString().orEmpty())
                    }
                } finally { nodes.forEach { it.recycle() } }
            } finally { root.recycle() }
        }
        suspend fun at(call: GuiCall, id: String, name: String, duration: Int = 0): GuiReply {
            val rect = bounds(id)
            val args = mutableMapOf<String, Any?>(
                "x" to norm(rect.left + rect.width() * 85 / 100, call.width),
                "y" to norm(rect.centerY(), call.height),
            )
            if (name == "long_press") args["duration_ms"] = duration
            return action(name, args, call.id)
        }
    }

    private data class NodeValue(val bounds: Rect, val text: String)
    internal class GuardedPhone(val real: HarnessAccessibilityService) : PhonePort {
        lateinit var coordinator: Coordinator
        val observeCalls = AtomicInteger()
        val frameCount = AtomicInteger()
        val performCalls = AtomicInteger()
        val holdObservationOrdinals = mutableSetOf<Int>()
        val heldObservations = CopyOnWriteArrayList<HeldObservation>()
        var holdInputTerminals = false
        val nativeResults = CopyOnWriteArrayList<String>()
        val heldInputTerminals = CopyOnWriteArrayList<CompletableDeferred<Unit>>()
        class HeldObservation(val frame: ScreenFrame, val release: CompletableDeferred<Unit> = CompletableDeferred())
        fun releaseObservations() { heldObservations.forEach { it.release.complete(Unit) } }
        fun releaseInputTerminals() { heldInputTerminals.forEach { it.complete(Unit) } }
        override val connected get() = real.connected
        override val inputInFlight get() = real.inputInFlight
        override fun configureSession(options: SessionOptions?) = real.configureSession(options)
        fun checkedRoot(): AccessibilityNodeInfo {
            check(coordinator.snapshot.value.state != RunState.MANUAL) { "Manual page access is forbidden" }
            val root = real.rootInActiveWindow ?: throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            // Only package identity is inspected before authorization. No foreign contents.
            if (root.packageName?.toString() != PACKAGE) {
                root.recycle()
                real.setCaptureEnabled(false)
                coordinator.environmentUnavailable("AUTHORIZATION_UNAVAILABLE")
                throw RunFailure("DEVICE", "AUTHORIZATION_UNAVAILABLE")
            }
            return root
        }
        fun requireFixture() { checkedRoot().recycle() }
        override suspend fun observe(): ScreenFrame = withContext(Dispatchers.Main.immediate) {
            val ordinal = observeCalls.incrementAndGet()
            requireFixture()
            val frame = real.observe()
            requireFixture()
            val decoded = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, decoded)
            check(decoded.outWidth == frame.width && decoded.outHeight == frame.height)
            check(decoded.outMimeType == "image/png")
            frameCount.incrementAndGet()
            if (ordinal in holdObservationOrdinals) {
                val held = HeldObservation(frame)
                heldObservations.add(held)
                // Pixels were captured before takeover. Delivering this stale result later
                // never queries a root or initiates a second capture during human ownership.
                withContext(NonCancellable) { held.release.await() }
            }
            frame
        }
        override suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult =
            withContext(Dispatchers.Main.immediate) {
                requireFixture()
                check(allowedPackages == setOf(PACKAGE))
                performCalls.incrementAndGet()
                val result = real.perform(action, frame, allowedPackages, canDispatch)
                nativeResults.add(result.status)
                if (holdInputTerminals) {
                    val gate = CompletableDeferred<Unit>()
                    heldInputTerminals.add(gate)
                    withContext(NonCancellable) { gate.await() }
                }
                result
            }
        override fun setCaptureEnabled(enabled: Boolean) = real.setCaptureEnabled(enabled)
        override fun showState(snapshot: RunSnapshot) = real.showState(snapshot)
    }

    internal data class GuiCall(
        val id: String, val phase: Phase, val frameId: String, val width: Int, val height: Int,
        val historyIds: List<String>, val returnNoticeIndex: Int?,
        val answer: CompletableDeferred<GuiReply> = CompletableDeferred(),
    )
    internal data class PlannerCall(val index: Int, val verificationFailed: Boolean,
        val answer: CompletableDeferred<PlannerDecision> = CompletableDeferred())
    internal class ControlledGateway : ModelGateway {
        val guiCalls = CopyOnWriteArrayList<GuiCall>()
        val plannerCalls = CopyOnWriteArrayList<PlannerCall>()
        val ignoreCancellation = mutableSetOf<Int>()
        val ignorePlannerCancellation = mutableSetOf<Int>()
        override suspend fun gui(context: GuiContext): GuiReply {
            val index = guiCalls.size
            val call = GuiCall(UUID.randomUUID().toString(), context.phase, context.frame.id,
                context.frame.width, context.frame.height, context.history.map { it.reply.callId }, context.returnNoticeIndex)
            guiCalls.add(call)
            suspend fun response(): GuiReply = withTimeout(5_000) {
                val answer = call.answer.await()
                delay(450)
                answer
            }
            return if (index in ignoreCancellation) withContext(NonCancellable) { response() } else response()
        }
        override suspend fun planner(context: PlannerContext): PlannerDecision {
            val ordinal = plannerCalls.size
            val call = PlannerCall(context.callIndex, context.verificationFailed)
            plannerCalls.add(call)
            suspend fun response() = withTimeout(5_000) { val answer = call.answer.await(); delay(450); answer }
            return if (ordinal in ignorePlannerCancellation) withContext(NonCancellable) { response() } else response()
        }
        override fun cancelAll() = Unit // Tests prove cancellation acknowledgement is not a barrier.
        fun answer(call: GuiCall, value: GuiReply) { check(call.answer.complete(value.copy(callId = call.id))) }
        fun answer(call: PlannerCall, value: PlannerDecision) { check(call.answer.complete(value)) }
        fun releaseAll() {
            guiCalls.forEach { it.answer.complete(finish("unable")) }
            plannerCalls.forEach { it.answer.complete(PlannerDecision("INCOMPLETE", "FALLBACK", "", emptyList())) }
        }
        fun clear() { guiCalls.clear(); plannerCalls.clear(); ignoreCancellation.clear(); ignorePlannerCancellation.clear() }
    }

    /** Only the same content-free metadata needed by assertions is retained, for this test only. */
    internal class MemoryAudit : AuditSink {
        private val events = CopyOnWriteArrayList<Pair<String, Map<String, Any?>>>()
        override fun begin(sessionId: String, options: SessionOptions) = Unit
        override fun event(event: String, data: Map<String, Any?>) {
            val keys = setOf("transition", "reason", "completion_source", "action_type", "result", "category", "code")
            if (event in setOf("control", "session_ended", "action", "error")) events.add(event to data.filterKeys { it in keys })
        }
        fun transitionCount(name: String) = events.count { it.first == "control" && it.second["transition"] == name }
        fun actionCount(name: String) = events.count { it.first == "action" && it.second["action_type"] == name }
        fun lastEnd() = events.last { it.first == "session_ended" }.second
        fun clear() = events.clear()
    }

    private class VerificationFailure(val code: String) : Exception(code)

    internal companion object {
        val RUNNING = AtomicBoolean(false)
        /** Only a bounded exception-type vocabulary and our own source location may leave RAM. */
        fun diagnosticCode(failure: Exception): String {
            val knownTypes = setOf("ActivityNotFoundException", "SecurityException", "IllegalArgumentException",
                "IllegalStateException", "NullPointerException", "UnsupportedOperationException",
                "ClassCastException", "NotFoundException", "RuntimeException", "Exception")
            val type = failure.javaClass.simpleName.takeIf { it in knownTypes } ?: "OTHER"
            val frame = failure.stackTrace.firstOrNull { it.className.startsWith("org.mobileharness.app.") }
            val area = when {
                frame == null -> "NONE"
                frame.className.startsWith("org.mobileharness.app.verification.DeviceVerification") -> "VERIFY"
                frame.className.startsWith("org.mobileharness.app.platform.") -> "PLATFORM"
                frame.className.startsWith("org.mobileharness.app.core.Coordinator") -> "CORE"
                else -> "APP"
            }
            val line = frame?.lineNumber?.takeIf { it in 1..100_000 } ?: 0
            return "UNEXPECTED_${type}_${area}_L$line"
        }
        fun assertTrue(value: Boolean) { if (!value) throw VerificationFailure("ASSERT_TRUE_FAILED") }
        fun assertTrue(@Suppress("UNUSED_PARAMETER") message: String, value: Boolean) = assertTrue(value)
        fun assertFalse(value: Boolean) { if (value) throw VerificationFailure("ASSERT_FALSE_FAILED") }
        fun assertEquals(expected: Any?, actual: Any?) { if (expected != actual) throw VerificationFailure("ASSERT_EQUALS_FAILED") }
        fun assertNotEquals(expected: Any?, actual: Any?) { if (expected == actual) throw VerificationFailure("ASSERT_NOT_EQUALS_FAILED") }
        fun check(value: Boolean, @Suppress("UNUSED_PARAMETER") lazyMessage: () -> Any = { "PRECONDITION_FAILED" }) {
            if (!value) throw VerificationFailure("PRECONDITION_FAILED")
        }
        fun <T : Any> checkNotNull(value: T?, @Suppress("UNUSED_PARAMETER") lazyMessage: () -> Any = { "REQUIRED_VALUE_MISSING" }): T = value ?: throw VerificationFailure("REQUIRED_VALUE_MISSING")
        const val PACKAGE = "org.mobileharness.fixture"
        suspend fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!predicate()) {
                if (SystemClock.elapsedRealtime() >= deadline) throw VerificationFailure(when {
                    label.startsWith("GUI call") -> "GUI_WAIT_TIMEOUT"
                    label.startsWith("Planner call") -> "PLANNER_WAIT_TIMEOUT"
                    label.startsWith("system-bound") -> "SERVICE_BIND_TIMEOUT"
                    label.startsWith("cleanup genuine") -> "INPUT_TERMINAL_TIMEOUT"
                    label.startsWith("cleanup coordinator") -> "CLEANUP_BARRIER_TIMEOUT"
                    label == "manual ownership" -> "MANUAL_BARRIER_TIMEOUT"
                    label.startsWith("native gesture") -> "INPUT_DISPATCH_TIMEOUT"
                    label.startsWith("genuine gesture") -> "GESTURE_TERMINAL_TIMEOUT"
                    else -> "STATE_WAIT_TIMEOUT"
                })
                delay(20)
            }
        }
        fun norm(pixel: Int, dimension: Int): Int = (pixel.toLong() * 1000 / dimension).toInt().coerceIn(0, 999)
        fun action(name: String, args: Map<String, Any?>, id: String = UUID.randomUUID().toString()) =
            GuiReply(id, PhoneAction(name, args), "{}")
        fun finish(outcome: String) = action("finish", mapOf("outcome" to outcome, "message" to "Synthetic current-page evidence only"))
    }
}
