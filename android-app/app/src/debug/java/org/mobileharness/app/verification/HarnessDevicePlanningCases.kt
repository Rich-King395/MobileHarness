package org.mobileharness.app.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.PlannerDecision
import org.mobileharness.app.core.RunFailure
import org.mobileharness.app.core.RunState
import org.mobileharness.app.verification.DeviceVerification.Companion.PACKAGE
import org.mobileharness.app.verification.DeviceVerification.Companion.action
import org.mobileharness.app.verification.DeviceVerification.Companion.assertEquals
import org.mobileharness.app.verification.DeviceVerification.Companion.assertFalse
import org.mobileharness.app.verification.DeviceVerification.Companion.assertTrue
import org.mobileharness.app.verification.DeviceVerification.Companion.await
import org.mobileharness.app.verification.DeviceVerification.Companion.finish

/**
 * Deterministic C/D planning acceptance. Each case owns a new fixture session.
 * Returned values are in-process substitutes: no HTTP, key resolution, or request parsing.
 * Assertions and fixture locations never enter a production model context or an export.
 */
internal fun DeviceVerification.planningDeviceCases(): List<Pair<String, suspend () -> Unit>> {
    val cases = mutableListOf<Pair<String, suspend () -> Unit>>()
    fun add(id: String, body: suspend DeviceVerification.Live.() -> Unit) {
        cases += id to suspend { withFixture { fixture -> fixture.body() } }
    }

    for ((status, branch) in listOf("COMPLETE" to "VERIFY", "INCOMPLETE" to "VERIFY", "UNVERIFIABLE" to "VERIFY",
        "INCOMPLETE" to "EXECUTE", "INCOMPLETE" to "FALLBACK", "UNVERIFIABLE" to "FALLBACK")) {
        add("BP05-D-P1-$status-$branch") {
            val first = prepareReturn()
            gateway.answer(first, planned(status, branch))
            val delivered = gui(1)
            val expected = when (branch) {
                "VERIFY" -> if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY
                "EXECUTE" -> Phase.RECOVERY
                else -> Phase.ORIGINAL
            }
            assertEquals(expected, delivered.phase)
            assertTrue(delivered.historyIds.isEmpty())
            assertEquals(1, state().plannerUsed)
            assertEquals(2, state().guiUsed)
            assertEquals(1, gateway.plannerCalls.size)
            assertEquals(0, phone.performCalls.get())
            assertFalse(real.inputInFlight)
        }
    }

    val readonlyWrites = listOf(
        "TAP" to action("tap", mapOf("x" to 500, "y" to 500)),
        "LONG_PRESS" to action("long_press", mapOf("x" to 500, "y" to 500, "duration_ms" to 300)),
        "SWIPE" to action("swipe", mapOf("from_x" to 500, "from_y" to 600, "to_x" to 500, "to_y" to 400, "duration_ms" to 100)),
        "INPUT_TEXT" to action("input_text", mapOf("text" to "SYNTHETIC_READONLY_FORBIDDEN")),
        "PRESS_KEY" to action("press_key", mapOf("key" to "back")),
        "LAUNCH_APP" to action("launch_app", mapOf("package_name" to PACKAGE)),
    )
    for (status in listOf("INCOMPLETE", "COMPLETE")) for ((name, reply) in readonlyWrites) {
        val phase = if (status == "COMPLETE") "CONFIRM" else "VERIFY"
        add("BP06-D-$phase-REJECT-$name") {
            val checking = prepareCheck(status)
            gateway.answer(checking, reply)
            await("readonly illegal tool rejection") { state().state == RunState.BLOCKED }
            assertEquals(if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, state().phase)
            assertEquals(2, state().guiUsed)
            assertEquals(1, state().plannerUsed)
            assertEquals(1, gateway.plannerCalls.size)
            assertEquals(0, phone.performCalls.get())
            assertEquals(0, audit.actionCount(reply.action.name))
            assertFalse(real.inputInFlight)
            assertFalse(real.captureEnabled)
        }
    }

    for (status in listOf("INCOMPLETE", "COMPLETE")) for (outcome in listOf("completed", "unable")) {
        val phase = if (status == "COMPLETE") "CONFIRM" else "VERIFY"
        add("BP07-D-$phase-${outcome.uppercase()}") {
            val checking = prepareCheck(status)
            gateway.answer(checking, finish(outcome))
            if (status == "COMPLETE" && outcome == "completed") {
                await("successful confirmation end") { state().state == RunState.ENDED }
                assertEquals(1, state().plannerUsed)
                assertEquals(1, gateway.plannerCalls.size)
                assertEquals("model_declared_complete", audit.lastEnd()["reason"])
                assertEquals("confirmation", audit.lastEnd()["completion_source"])
            } else {
                val second = planner(1)
                assertEquals(2, state().plannerUsed)
                assertEquals(2, second.index)
                assertEquals(outcome == "unable", second.verificationFailed)
                assertEquals(2, gateway.plannerCalls.size)
            }
            assertEquals(2, state().guiUsed)
            assertEquals(2, gateway.guiCalls.size)
            assertEquals(1, audit.actionCount("finish"))
            assertEquals(0, phone.performCalls.get())
        }
    }

    val checkEntrances = listOf(
        CheckEntrance("VERIFY-COMPLETED", "INCOMPLETE", "completed"),
        CheckEntrance("VERIFY-UNABLE", "INCOMPLETE", "unable"),
        CheckEntrance("CONFIRM-UNABLE", "COMPLETE", "unable"),
    )
    val secondBranches = listOf("COMPLETE" to "FINISH", "INCOMPLETE" to "EXECUTE",
        "INCOMPLETE" to "FALLBACK", "UNVERIFIABLE" to "FALLBACK")
    for (entry in checkEntrances) for ((status, branch) in secondBranches) {
        add("BP08-D-${entry.label}-P2-$status-$branch") {
            val second = prepareSecond(entry)
            val guiBefore = gateway.guiCalls.size
            gateway.answer(second, planned(status, branch))
            if (branch == "FINISH") {
                await("second Planner direct end") { state().state == RunState.ENDED }
                assertEquals(guiBefore, gateway.guiCalls.size)
                assertEquals("model_declared_complete", audit.lastEnd()["reason"])
                assertEquals("planner", audit.lastEnd()["completion_source"])
                assertFalse(real.captureEnabled)
            } else {
                val delivered = gui(guiBefore)
                assertEquals(if (branch == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL, delivered.phase)
                assertTrue(delivered.historyIds.isEmpty())
                assertEquals(3, state().guiUsed)
            }
            assertEquals(2, state().plannerUsed)
            assertEquals(2, gateway.plannerCalls.size)
            assertEquals(1, gateway.guiCalls.count { it.phase in setOf(Phase.VERIFY, Phase.CONFIRM) })
            assertEquals(1, audit.actionCount("finish"))
            assertEquals(0, phone.performCalls.get())
        }
    }

    for (ordinal in listOf(1, 2)) for (code in listOf("TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT")) {
        add("BP10-D-P$ordinal-$code-FALLBACK") {
            val pending = if (ordinal == 1) prepareReturn() else prepareSecond(checkEntrances.first())
            val nextGui = gateway.guiCalls.size
            assertTrue(pending.answer.completeExceptionally(RunFailure("MODEL", code)))
            val fallback = gui(nextGui)
            assertEquals(Phase.ORIGINAL, fallback.phase)
            assertEquals(ordinal, state().plannerUsed)
            assertEquals(ordinal, gateway.plannerCalls.size)
            assertEquals(nextGui + 1, state().guiUsed)
            assertEquals(0, phone.performCalls.get())
            assertFalse(real.inputInFlight)
            assertEquals(if (ordinal == 1) 0 else 1, audit.actionCount("finish"))
        }
    }

    for (entry in checkEntrances) for (branch in listOf("FINISH", "EXECUTE", "FALLBACK")) {
        add("BP09-D-${entry.label}-ZERO-GUI-$branch") {
            val second = prepareSecond(entry, budget = 2)
            assertEquals(2, state().guiUsed)
            val captures = phone.observeCalls.get()
            val finishes = audit.actionCount("finish")
            gateway.answer(second, planned(if (branch == "FINISH") "COMPLETE" else "INCOMPLETE", branch))
            await("zero GUI balance end") { state().state == RunState.ENDED }
            assertEquals(2, state().guiUsed)
            assertEquals(2, state().plannerUsed)
            assertEquals(2, gateway.guiCalls.size)
            assertEquals(2, gateway.plannerCalls.size)
            assertEquals(captures, phone.observeCalls.get())
            assertEquals(finishes, audit.actionCount("finish"))
            assertEquals(0, phone.performCalls.get())
            assertEquals(if (branch == "FINISH") "model_declared_complete" else "budget_exhausted", audit.lastEnd()["reason"])
            assertEquals(if (branch == "FINISH") "planner" else "none", audit.lastEnd()["completion_source"])
            assertFalse(real.captureEnabled)
            assertFalse(real.inputInFlight)
        }
    }

    for (ordinal in listOf(1, 2)) for (branch in listOf("EXECUTE", "FALLBACK")) {
        for (outcome in listOf("completed", "unable")) {
            add("BP11-D-P$ordinal-$branch-${outcome.uppercase()}-NO-REPLAN") {
                val delivered = prepareDelivery(ordinal, branch)
                val nextIndex = gateway.guiCalls.size
                gateway.answer(delivered, at(delivered, "fixture_click", "tap"))
                val next = gui(nextIndex)
                assertEquals("点击次数：1", text("fixture_click_count"))
                gateway.answer(next, finish(outcome))
                await("delivered execution finish") { state().state == RunState.ENDED }
                assertEquals(ordinal, state().plannerUsed)
                assertEquals(ordinal, gateway.plannerCalls.size)
                assertEquals(1, phone.performCalls.get())
                assertEquals(if (outcome == "completed") "model_declared_complete" else "model_unable", audit.lastEnd()["reason"])
                assertEquals("gui", audit.lastEnd()["completion_source"])
                assertFalse(real.captureEnabled)
                assertFalse(real.inputInFlight)
            }
        }
        add("BP11-D-P$ordinal-$branch-EXPLICIT-RETRY-NO-REPLAN") {
            val delivered = prepareDelivery(ordinal, branch)
            val charged = state().guiUsed
            assertTrue(delivered.answer.completeExceptionally(RunFailure("MODEL", "API_ERROR")))
            await("delivered GUI model error pause") { state().state == RunState.BLOCKED }
            assertEquals(charged, state().guiUsed)
            assertEquals(ordinal, state().plannerUsed)
            val captures = phone.observeCalls.get()
            val nextIndex = gateway.guiCalls.size
            withContext(Dispatchers.Main.immediate) { coordinator.retry() }
            val retried = gui(nextIndex)
            assertEquals(charged + 1, state().guiUsed)
            assertEquals(if (branch == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL, retried.phase)
            assertEquals(ordinal, gateway.plannerCalls.size)
            assertTrue(phone.observeCalls.get() > captures)
            gateway.answer(retried, finish("unable"))
            await("retried delivered execution end") { state().state == RunState.ENDED }
            assertEquals(ordinal, state().plannerUsed)
            assertEquals(ordinal, gateway.plannerCalls.size)
            assertEquals(0, phone.performCalls.get())
            assertEquals("model_unable", audit.lastEnd()["reason"])
        }
    }
    return cases
}

private data class CheckEntrance(val label: String, val firstStatus: String, val finishOutcome: String)

private fun planned(status: String, branch: String) = if (branch == "FINISH")
    PlannerDecision(status, branch, "", emptyList())
else PlannerDecision(status, branch, "Observe or continue only the synthetic fixture", listOf("The synthetic fixture state is visible"))

private suspend fun DeviceVerification.Live.prepareReturn(budget: Int = 8): DeviceVerification.PlannerCall {
    start(harness = true, budget = budget)
    gui(0)
    takeover()
    assertEquals(1, state().guiUsed)
    assertEquals(0, state().plannerUsed)
    giveBack()
    val first = planner(0)
    assertEquals(1, state().guiUsed)
    assertEquals(1, state().plannerUsed)
    return first
}

private suspend fun DeviceVerification.Live.prepareCheck(status: String, budget: Int = 8): DeviceVerification.GuiCall {
    val first = prepareReturn(budget)
    gateway.answer(first, planned(status, "VERIFY"))
    val checking = gui(1)
    assertEquals(if (status == "COMPLETE") Phase.CONFIRM else Phase.VERIFY, checking.phase)
    assertTrue(checking.historyIds.isEmpty())
    return checking
}

private suspend fun DeviceVerification.Live.prepareSecond(entry: CheckEntrance, budget: Int = 8): DeviceVerification.PlannerCall {
    val checking = prepareCheck(entry.firstStatus, budget)
    gateway.answer(checking, finish(entry.finishOutcome))
    val second = planner(1)
    assertEquals(2, state().guiUsed)
    assertEquals(2, state().plannerUsed)
    assertEquals(entry.finishOutcome == "unable", second.verificationFailed)
    return second
}

private suspend fun DeviceVerification.Live.prepareDelivery(ordinal: Int, branch: String): DeviceVerification.GuiCall {
    val pending = if (ordinal == 1) prepareReturn() else prepareSecond(CheckEntrance("VERIFY-COMPLETED", "INCOMPLETE", "completed"))
    val next = gateway.guiCalls.size
    gateway.answer(pending, planned("INCOMPLETE", branch))
    val delivered = gui(next)
    assertEquals(if (branch == "EXECUTE") Phase.RECOVERY else Phase.ORIGINAL, delivered.phase)
    assertEquals(ordinal, state().plannerUsed)
    return delivered
}
