package org.mobileharness.app.verification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.mobileharness.app.core.*
import org.mobileharness.app.verification.DeviceVerification.Companion.action
import org.mobileharness.app.verification.DeviceVerification.Companion.await
import org.mobileharness.app.verification.DeviceVerification.Companion.finish
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path

/** Real screenshots/executor, controlled replies; no network or human-interval observations. */
internal fun DeviceVerification.controlDeviceCases(): List<Pair<String, suspend () -> Unit>> = buildList {
    for (harness in listOf(false, true)) for (returned in listOf(false, true))
        for (outcome in listOf("TAP", "COMPLETED", "UNABLE")) {
            val id = "CT02-D-${if (harness) "ON" else "OFF"}-${if (returned) "RETURNED" else "MANUAL"}-$outcome"
            add(id to {
                withFixture { f ->
                    f.gateway.ignoreCancellation.add(0)
                    f.start(harness)
                    val old = f.gui(0)
                    f.takeover()
                    val captures = f.phone.observeCalls.get()
                    delay(150)
                    check(f.phone.observeCalls.get() == captures && !f.real.captureEnabled)
                    if (returned) {
                        f.giveBack()
                        if (harness) f.planner(0) else f.gui(1)
                    }
                    val g = f.state().guiUsed; val p = f.state().plannerUsed
                    val o = f.phone.observeCalls.get()
                    f.gateway.answer(old, when (outcome) {
                        "TAP" -> action("tap", mapOf("x" to 500, "y" to 500))
                        "COMPLETED" -> finish("completed")
                        else -> finish("unable")
                    })
                    delay(550)
                    check(f.state().guiUsed == g && f.state().plannerUsed == p)
                    check(f.phone.observeCalls.get() == o && f.phone.performCalls.get() == 0)
                    check(f.audit.actionCount("finish") == 0 && f.audit.actionCount("tap") == 0)
                    check(f.state().state != RunState.ENDED)
                }
            })
        }
    for (hasPre in listOf(false, true)) for (afterReturn in listOf(false, true)) {
        add("CT07-D-${if (hasPre) "PRE" else "NO-PRE"}-${if (afterReturn) "AFTER-RETURN" else "MANUAL"}" to {
            withFixture { f ->
                f.phone.holdObservationOrdinals.add(if (hasPre) 2 else 1)
                f.start(harness = true)
                if (hasPre) {
                    val initial = f.gui(0)
                    f.gateway.answer(initial, f.at(initial, "fixture_click", "tap"))
                }
                await("held pre-takeover capture") { f.phone.heldObservations.size == 1 }
                val held = f.phone.heldObservations.single()
                f.takeover()
                val before = f.phone.observeCalls.get()
                delay(150)
                check(f.phone.observeCalls.get() == before && !f.real.captureEnabled)
                if (afterReturn) { f.giveBack(); f.planner(0) }
                val g = f.state().guiUsed; val p = f.state().plannerUsed; val o = f.phone.observeCalls.get()
                held.release.complete(Unit)
                delay(100)
                check(f.state().guiUsed == g && f.state().plannerUsed == p && f.phone.observeCalls.get() == o)
                if (!afterReturn) { f.giveBack(); f.planner(0) }
                check(f.state().handoffUsed && f.audit.transitionCount("return_committed") == 1)
                check(f.state().guiUsed == if (hasPre) 1 else 0)
                check(f.state().plannerUsed == 1)
                f.gateway.answer(f.gateway.plannerCalls.single(), PlannerDecision("INCOMPLETE", "EXECUTE", "Synthetic remaining work", listOf("Synthetic visible evidence")))
                val recovered = f.gui(if (hasPre) 1 else 0)
                check(recovered.phase == Phase.RECOVERY && recovered.historyIds.isEmpty())
                check(recovered.frameId != held.frame.id)
            }
        })
    }
    for (harness in listOf(false, true)) {
        add("CT08-D-${if (harness) "ON" else "OFF"}-WAIT-CANCEL" to {
            withFixture { f ->
                f.start(harness)
                val pending = f.gui(0)
                f.gateway.answer(pending, action("wait", mapOf("duration_ms" to 2000)))
                await("wait accepted") { f.state().message == "等待画面刷新" }
                f.takeover()
                val o = f.phone.observeCalls.get()
                delay(2100) // Cross the obsolete real timer deadline; no observations in manual.
                check(f.phone.observeCalls.get() == o && f.state().guiUsed == 1)
                check(f.audit.actionCount("wait") == 0)
                f.giveBack()
                if (harness) {
                    f.gateway.answer(f.planner(0), PlannerDecision("INCOMPLETE", "FALLBACK", "Original task", emptyList()))
                }
                val continued = f.gui(1)
                check(continued.historyIds.isEmpty())
                val captures = f.phone.observeCalls.get()
                delay(150)
                check(f.phone.observeCalls.get() == captures && f.state().guiUsed == 2)
            }
        })
    }
    for (second in listOf(false, true)) {
        add("BP13-D-${if (second) "P2" else "P1"}-LATE-STOP" to {
            withFixture { f ->
                if (!second) f.gateway.ignorePlannerCancellation.add(0)
                f.start(harness = true)
                f.gui(0); f.takeover(); f.giveBack()
                val first = f.planner(0)
                val pending = if (second) {
                    f.gateway.answer(first, PlannerDecision("INCOMPLETE", "VERIFY", "Observe fixture", listOf("Visible fixture")))
                    f.gateway.ignorePlannerCancellation.add(1)
                    f.gateway.answer(f.gui(1), finish("unable"))
                    f.planner(1)
                } else first
                withContext(Dispatchers.Main.immediate) { f.coordinator.stop() }
                await("stop before late planner") { f.state().state == RunState.ENDED }
                val g = f.state().guiUsed; val p = f.state().plannerUsed; val o = f.phone.observeCalls.get()
                f.gateway.answer(pending, if (second) PlannerDecision("COMPLETE", "FINISH", "", emptyList())
                    else PlannerDecision("COMPLETE", "VERIFY", "Confirm original task", listOf("Visible state")))
                delay(550)
                check(f.state().state == RunState.ENDED && f.state().guiUsed == g && f.state().plannerUsed == p)
                check(f.phone.observeCalls.get() == o && f.phone.performCalls.get() == 0)
                check(f.audit.lastEnd()["reason"] == "user_stop" && f.audit.lastEnd()["completion_source"] == "none")
            }
        })
    }
    for (native in listOf(false, true)) {
        add("CT10-D-USED-RETURN-${if (native) "NATIVE" else "IDLE"}" to {
            withFixture { f ->
                f.start(harness = true)
                f.gui(0); f.takeover(); f.giveBack()
                f.gateway.answer(f.planner(0), PlannerDecision("INCOMPLETE", "EXECUTE", "Synthetic remaining work", listOf("Synthetic fixture")))
                val current = f.gui(1)
                if (native) {
                    f.gateway.answer(current, f.at(current, "fixture_long_press", "long_press", 2000))
                    await("native gesture submitted") { f.real.inputInFlight }
                }
                val g = f.state().guiUsed; val p = f.state().plannerUsed; val o = f.phone.observeCalls.get()
                withContext(Dispatchers.Main.immediate) { f.coordinator.takeover(); f.coordinator.giveBack() }
                await("one-return stop end") { f.state().state == RunState.ENDED }
                withContext(Dispatchers.Main.immediate) { f.coordinator.giveBack(); f.coordinator.takeover(); f.coordinator.stop() }
                delay(100)
                check(f.state().handoffUsed && f.audit.transitionCount("manual_granted") == 1)
                check(f.audit.transitionCount("return_committed") == 1)
                check(f.state().guiUsed == g && f.state().plannerUsed == p && f.phone.observeCalls.get() == o)
                check(!f.real.inputInFlight && !f.real.captureEnabled)
            }
        })
    }
    for (harness in listOf(false, true)) for (stopping in listOf(false, true)) {
        add("CT04-D-${if (harness) "ON" else "OFF"}-NATIVE-CANCEL-${if (stopping) "STOP" else "MANUAL"}" to {
            withFixture { f ->
                f.start(harness)
                val first = f.gui(0)
                f.gateway.answer(first, f.at(first, "fixture_long_press", "long_press", 2000))
                await("native gesture cancellation setup") { f.real.inputInFlight }
                withContext(Dispatchers.Main.immediate) { f.coordinator.takeover(); if (stopping) f.coordinator.stop() }
                val before = f.phone.observeCalls.get()
                cancelWithExternalFixtureStimulus(f, first)
                await("matching cancelled original gesture") { f.phone.nativeResults.isNotEmpty() && !f.real.inputInFlight }
                await("cancelled ownership barrier") { f.state().state == if (stopping) RunState.ENDED else RunState.MANUAL }
                check(f.phone.nativeResults.single() == "cancelled")
                check(f.phone.observeCalls.get() == before && f.phone.performCalls.get() == 1)
                check(!f.real.captureEnabled && f.state().guiUsed == 1)
                // MANUAL is published before its audit event in the same Main turn.
                // Observe both after that turn, rather than racing the Default thread.
                withContext(Dispatchers.Main.immediate) {
                    check(f.state().state == if (stopping) RunState.ENDED else RunState.MANUAL)
                    check(f.audit.transitionCount("manual_granted") == if (stopping) 0 else 1)
                }
            }
        })
    }
    for (harness in listOf(false, true)) for (cancelled in listOf(false, true)) for (stopping in listOf(false, true)) {
        add("CT05-D-${if (harness) "ON" else "OFF"}-${if (cancelled) "CANCELLED" else "COMPLETED"}-${if (stopping) "STOP" else "MANUAL"}-HELD-TERMINAL" to {
            withFixture { f ->
                f.phone.holdInputTerminals = true
                f.start(harness)
                val first = f.gui(0)
                f.gateway.answer(first, f.at(first, "fixture_long_press", "long_press", 2000))
                await("native gesture held terminal setup") { f.real.inputInFlight }
                withContext(Dispatchers.Main.immediate) { f.coordinator.takeover() }
                if (cancelled) cancelWithExternalFixtureStimulus(f, first)
                await("actual native result held at PhonePort boundary") { f.phone.heldInputTerminals.size == 1 }
                val o = f.phone.observeCalls.get()
                delay(5100)
                check(f.state().state == RunState.UNCERTAIN && !f.real.captureEnabled)
                withContext(Dispatchers.Main.immediate) {
                    f.coordinator.recheck(); f.coordinator.giveBack(); if (stopping) f.coordinator.stop()
                }
                delay(100)
                check(f.state().state == RunState.UNCERTAIN && f.phone.observeCalls.get() == o)
                check(f.audit.transitionCount("manual_granted") == 0)
                check(f.phone.nativeResults.single() == if (cancelled) "cancelled" else "completed")
                f.phone.releaseInputTerminals()
                await("released actual matching terminal") { f.state().state == if (stopping) RunState.ENDED else RunState.MANUAL }
                withContext(Dispatchers.Main.immediate) {
                    check(f.state().state == if (stopping) RunState.ENDED else RunState.MANUAL)
                    check(f.audit.transitionCount("manual_granted") == if (stopping) 0 else 1)
                }
                check(f.phone.observeCalls.get() == o && f.state().guiUsed == 1 && !f.real.inputInFlight)
            }
        })
    }
    for (confirmed in listOf(false, true)) {
        add("BP06-D-${if (confirmed) "CONFIRM" else "VERIFY"}-MULTI-WAIT" to {
            withFixture { f ->
                f.start(harness = true, budget = 6)
                f.gui(0); f.takeover(); f.giveBack()
                f.gateway.answer(f.planner(0), PlannerDecision(if (confirmed) "COMPLETE" else "INCOMPLETE", "VERIFY", "Observe only", listOf("Visible fixture")))
                for (index in 1..2) {
                    val observationCall = f.gui(index)
                    check(observationCall.phase == if (confirmed) Phase.CONFIRM else Phase.VERIFY)
                    f.gateway.answer(observationCall, action("wait", mapOf("duration_ms" to 100)))
                }
                val finalCall = f.gui(3)
                check(finalCall.historyIds.size == 2 && f.state().plannerUsed == 1)
                f.gateway.answer(finalCall, finish("completed"))
                if (confirmed) {
                    await("confirmed final state") { f.state().state == RunState.ENDED }
                    check(f.state().plannerUsed == 1)
                } else {
                    f.gateway.answer(f.planner(1), PlannerDecision("COMPLETE", "FINISH", "", emptyList()))
                    await("verified final planner state") { f.state().state == RunState.ENDED }
                    check(f.state().plannerUsed == 2)
                }
                check(f.gateway.guiCalls.size == 4 && f.phone.performCalls.get() == 0)
            }
        })
    }
}

/** Test disturbance only. Success still requires the original tracked onCancelled result. */
private suspend fun cancelWithExternalFixtureStimulus(f: DeviceVerification.Live, frame: DeviceVerification.GuiCall) {
    val terminal = CompletableDeferred<Unit>()
    withContext(Dispatchers.Main.immediate) {
        check(f.real.inputInFlight)
        // The pre-authorized fixture's static lower area; no root or pixels are read here.
        val path = Path().apply { moveTo(frame.width * .5f, frame.height * .8f) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 1)).build()
        val accepted = f.real.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: GestureDescription) { terminal.complete(Unit) }
            override fun onCancelled(description: GestureDescription) { terminal.complete(Unit) }
        }, null)
        check(accepted)
    }
    await("external test disturbance genuine terminal") { terminal.isCompleted }
}
