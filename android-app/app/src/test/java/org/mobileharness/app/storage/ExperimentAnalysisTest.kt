package org.mobileharness.app.storage

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExperimentAnalysisTest {
    @Test fun roleAndIndexPairAttemptsInsteadOfCountingStartedAndTerminalTwice() {
        val analysis = analysis(start(), call("GUI", 1, "started"), call("PLANNER", 1, "started"),
            call("GUI", 1, "succeeded", 100), call("PLANNER", 1, "api_error", 250),
            event("error", "category" to "MODEL", "code" to "API_ERROR"), end(gui = 1, planner = 1))
        assertEquals(1, analysis.guiCalls.started)
        assertEquals(1, analysis.guiCalls.validResponses)
        assertEquals(0, analysis.guiCalls.failed)
        assertEquals(100L, analysis.guiCalls.totalDurationMs)
        assertEquals(1, analysis.plannerCalls.started)
        assertEquals(1, analysis.plannerCalls.failed)
        assertEquals(250L, analysis.plannerCalls.totalDurationMs)
        assertEquals(1, analysis.errors.single().count) // This error is not another failed call.
        assertNull(analysis.userAssessment)
    }

    @Test fun repeatedAttemptEventsDoNotChargeOrCountTheSameCallAgain() {
        val analysis = analysis(start(), call("GUI", 1, "started"), call("GUI", 1, "started"),
            call("GUI", 1, "api_error", 10), call("GUI", 1, "api_error", 10), end())
        assertEquals(1, analysis.guiCalls.started)
        assertEquals(1, analysis.guiCalls.failed)
        assertEquals(1, analysis.guiCalls.durationSamples)
        assertEquals(10L, analysis.guiCalls.totalDurationMs)
    }

    @Test fun cancelledLateAndOrphanTerminalsAreNotRequestFailures() {
        val analysis = analysis(start(), call("GUI", 1, "started"), call("GUI", 1, "cancelled", 5),
            call("GUI", 2, "started"), call("GUI", 2, "late_discarded", 7),
            call("GUI", 3, "api_error", 8), call("GUI", 4, "started"), end())
        assertEquals(3, analysis.guiCalls.started)
        assertEquals(0, analysis.guiCalls.failed)
        assertEquals(1, analysis.guiCalls.cancelled)
        assertEquals(1, analysis.guiCalls.lateDiscarded)
        assertEquals(1, analysis.guiCalls.orphanTerminals)
        assertEquals(1, analysis.guiCalls.missingTerminal)
        assertEquals(0, analysis.guiCalls.inFlight)
    }

    @Test fun unfinishedAttemptIsInFlightUntilEndedAndPartialDurationIsNotATotal() {
        val active = analysis(start(), call("GUI", 1, "started"))
        assertEquals(1, active.guiCalls.inFlight)
        assertEquals(0, active.guiCalls.missingTerminal)
        val ended = analysis(start(), call("GUI", 1, "started"), call("GUI", 1, "succeeded", 20),
            call("GUI", 2, "started"), call("GUI", 2, "cancelled"), end())
        assertEquals(1, ended.guiCalls.durationSamples)
        assertNull(ended.guiCalls.totalDurationMs)
    }

    @Test fun interruptionRetainsOnlyLastRecordedDurationNotActualWallTime() {
        val start = start().put("elapsed_ms", 40)
        val stop = end("interrupted").put("elapsed_ms", 940)
        val value = analysis(start, call("GUI", 1, "started").put("elapsed_ms", 140), stop)
        assertTrue(value.ended)
        assertEquals(900L, value.recordedDurationMs)
        assertNull(value.durationMs)
        assertNull(ExperimentAnalysis.aggregate(listOf(value)).on.meanDurationMs.value)
    }

    @Test fun durationUsesMonotonicExecutionEventsAndIgnoresLaterAssessmentTime() {
        val valid = analysis(start().put("elapsed_ms", 50), end().put("elapsed_ms", 1050),
            assessment("user_reported_complete").put("elapsed_ms", 30000))
        assertEquals(1000L, valid.durationMs)
        assertEquals(1000L, valid.recordedDurationMs)
        val backwards = analysis(start(), call("GUI", 1, "started").put("elapsed_ms", 500),
            end().put("elapsed_ms", 400))
        assertNull(backwards.durationMs)
        assertNull(backwards.recordedDurationMs)
        val numericText = analysis(start(), end().put("elapsed_ms", "1200"))
        assertNull(numericText.durationMs)
    }

    @Test fun latestExplicitAssessmentCanBeCorrectedOrResetWithoutUsingModelFinish() {
        val completed = analysis(start(), end(), assessment("user_reported_incomplete"),
            assessment("user_reported_complete"), assessment("PRIVATE_INVALID_VALUE"))
        assertEquals("user_reported_complete", completed.userAssessment)
        assertEquals("model_declared_complete", completed.endReason)
        assertEquals("gui", completed.completionSource)
        val reset = analysis(start(), end(), assessment("user_reported_complete"), assessment("unknown"))
        assertNull(reset.userAssessment)
        val modelOnly = analysis(start(), end())
        assertNull(modelOnly.userAssessment)
        assertEquals(0, ExperimentAnalysis.aggregate(listOf(modelOnly)).on.rated)
    }

    @Test fun duplicateSequencesCannotInflateControlAndErrorCounts() {
        val control = event("control", "transition" to "manual_granted").put("seq", 2)
        val error = event("error", "category" to "DEVICE", "code" to "INPUT_FAILED",
            "execution_failure_reason" to "GESTURE_CANCELLED").put("seq", 3)
        val records = records(start(), control, JSONObject(control.toString()), error, JSONObject(error.toString()),
            event("control", "transition" to "return_committed").put("seq", 4), end().put("seq", 5))
        val value = ExperimentAnalysis.fromRecords(records)
        assertEquals(1, value.takeoverCount)
        assertEquals(1, value.returnCount)
        assertEquals(1, value.errors.single().count)
        assertEquals("GESTURE_CANCELLED", value.errors.single().detail)
    }

    @Test fun errorCategoriesAndOptionalDetailsUseClosedVocabulary() {
        val value = analysis(start(),
            event("error", "category" to "MODEL", "code" to "TRANSPORT_ERROR", "transport_reason" to "TIMEOUT"),
            event("error", "category" to "MODEL", "code" to "INVALID_OUTPUT", "protocol_reason" to "TOOL_NOT_ALLOWED"),
            event("error", "category" to "DEVICE", "code" to "INPUT_FAILED", "execution_failure_reason" to "PRIVATE_UI_TEXT"),
            event("error", "category" to "MODEL", "code" to "INPUT_FAILED"),
            event("error", "category" to "PRIVATE_CATEGORY", "code" to "PRIVATE_CODE"), end())
        assertEquals(3, value.errors.size)
        assertEquals(setOf("TIMEOUT", "TOOL_NOT_ALLOWED"), value.errors.mapNotNull { it.detail }.toSet())
        assertNull(value.errors.single { it.category == "DEVICE" }.detail)
        assertFalse(ExperimentAnalysis.csv(listOf(value)).contains("PRIVATE"))
    }

    @Test fun metadataVersionsAndOpaqueConfigurationIdsCannotCarryNamesOrServiceUrls() {
        val start = start()
        start.getJSONObject("data").put("prompt_bundle_version", "1.2.0").put("adapter_version", "PRIVATE_NAME")
            .put("execution_policy_version", "https://PRIVATE_SERVICE").put("bundle_manifest_sha256", "a".repeat(64))
            .put("gui_profile_id", ID2).put("planner_profile_id", "PRIVATE_KEY")
        val value = analysis(start, end())
        assertEquals("1.2.0", value.versions.promptBundleVersion)
        assertEquals(ID2, value.versions.guiProfileId)
        assertNull(value.versions.adapterVersion)
        assertNull(value.versions.executionPolicyVersion)
        assertNull(value.versions.plannerProfileId)
        assertFalse(ExperimentAnalysis.csv(listOf(value)).contains("PRIVATE"))
    }

    @Test fun apiHttpDetailsGroupOnlyIntegerStatusesAndCsvRevalidatesConstructedDetails() {
        fun http(value: Any) = event("error", "category" to "MODEL", "code" to "API_ERROR", "http_status" to value)
        val value = analysis(start(), http(401), http(401), http(503), http(99), http(600), http("401"), http("PRIVATE_RESPONSE"), end())
        assertEquals(3, value.errors.size)
        assertEquals(2, value.errors.single { it.detail == "HTTP_401" }.count)
        assertEquals(1, value.errors.single { it.detail == "HTTP_503" }.count)
        assertEquals(4, value.errors.single { it.detail == null }.count)
        val csv = ExperimentAnalysis.csv(listOf(value))
        assertTrue(csv.contains("MODEL/API_ERROR/HTTP_401:2"))
        assertTrue(csv.contains("MODEL/API_ERROR/HTTP_503:1"))
        assertFalse(csv.contains("HTTP_99"))
        assertFalse(csv.contains("HTTP_600"))
        assertFalse(csv.contains("PRIVATE"))
        val forged = value.copy(errors = listOf(
            ExperimentAnalysis.ErrorCount("MODEL", "API_ERROR", "HTTP_401", 1),
            ExperimentAnalysis.ErrorCount("MODEL", "API_ERROR", "HTTP_000", 1),
            ExperimentAnalysis.ErrorCount("MODEL", "API_ERROR", "HTTP_600", 1),
            ExperimentAnalysis.ErrorCount("MODEL", "API_ERROR", "HTTP_401\nPRIVATE_RESPONSE", 1),
        ))
        val sanitized = ExperimentAnalysis.csv(listOf(forged))
        assertTrue(sanitized.contains("MODEL/API_ERROR/HTTP_401:1"))
        assertFalse(sanitized.contains("HTTP_000"))
        assertFalse(sanitized.contains("HTTP_600"))
        assertFalse(sanitized.contains("PRIVATE"))
    }

    @Test fun mixedSessionRowsCannotSupplyCountersRatingsOrErrorsToThisSession() {
        val foreign = call("GUI", 1, "started").put("session_id", ID2)
        val rating = assessment("user_reported_complete").put("session_id", ID2)
        val value = analysis(start(), foreign, rating, end(gui = 0))
        assertEquals(0, value.guiCalls.started)
        assertNull(value.userAssessment)
        val invalid = analysis(start().put("session_id", "PRIVATE_TASK"), end())
        assertFalse(invalid.ended)
        assertNull(invalid.sessionId)
    }

    @Test fun comparisonUsesRatedDenominatorAndKeepsUnknownAndUnratedSeparate() {
        val a = analysis(start(), end(gui = 2).put("elapsed_ms", 1000), assessment("user_reported_complete"))
        val b = withId(analysis(start(), end(gui = 4).put("elapsed_ms", 3000)), ID2)
        val c = withId(analysis(start(false), end(gui = 8), assessment("user_reported_incomplete")), ID3)
        val unknown = withId(analysis(start(null), end(gui = 0)), ID4)
        val result = ExperimentAnalysis.aggregate(listOf(a, b, c, unknown))
        assertEquals(2, result.on.sessions)
        assertEquals(1, result.on.rated)
        assertEquals(1, result.on.unrated)
        assertEquals(1.0, result.on.completionRate!!, 0.0)
        assertEquals(3.0, result.on.meanGuiSteps.value!!, 0.0)
        assertEquals(2, result.on.meanGuiSteps.samples)
        assertEquals(2000.0, result.on.meanDurationMs.value!!, 0.0)
        assertEquals(0.0, result.off.completionRate!!, 0.0)
        assertEquals(1, result.unknown.sessions)
        assertNull(result.unknown.completionRate)
    }

    @Test fun unknownMetricsAreEmptyAndNotPartOfMeansWhileGenuineZeroIsRetained() {
        val unknown = analysis(event("session_started"), event("session_ended"))
        val zero = withId(analysis(start(), end(gui = 0, planner = 0)), ID2)
        val group = ExperimentAnalysis.aggregate(listOf(unknown, zero))
        assertNull(group.unknown.meanGuiSteps.value)
        assertEquals(0, group.unknown.meanGuiSteps.samples)
        assertEquals(0.0, group.on.meanGuiSteps.value!!, 0.0)
        val csv = ExperimentAnalysis.csv(listOf(unknown))
        val cells = csv.lineSequence().filter { it.isNotBlank() }.last().split(',')
        assertEquals("\"\"", cells[4]) // gui_steps is unknown, not zero.
    }

    @Test fun csvHasOnlyEndedRowsAndReplacesDuplicateSessionSnapshots() {
        val active = analysis(start(), call("GUI", 1, "started"))
        val ended = analysis(start(), end(), assessment("user_reported_incomplete"))
        val changed = ended.copy(userAssessment = "user_reported_complete")
        val csv = ExperimentAnalysis.csv(listOf(active, ended, changed))
        assertEquals(2, csv.lineSequence().filter { it.isNotBlank() }.count())
        assertTrue(csv.contains("\"user_reported_complete\""))
        assertFalse(csv.contains("\"user_reported_incomplete\""))
        assertEquals(1, ExperimentAnalysis.aggregate(listOf(active, ended, changed)).on.sessions)
        assertEquals(1, ExperimentAnalysis.csv(emptyList()).lineSequence().filter { it.isNotBlank() }.count())
        assertEquals(1, ExperimentAnalysis.csv(listOf(active)).lineSequence().filter { it.isNotBlank() }.count())
    }

    @Test fun arbitraryInputFieldsAndCsvFormulaTextNeverBecomeExportCells() {
        val start = start()
        start.getJSONObject("data").put("task", "PRIVATE_TASK").put("api_key", "PRIVATE_KEY")
            .put("base_url", "https://PRIVATE_SERVICE").put("screenshot", "PRIVATE_IMAGE")
            .put("model", "PRIVATE_MODEL").put("package_name", "PRIVATE_PACKAGE")
        val value = analysis(start, event("action", "arguments" to JSONObject().put("text", "PRIVATE_INPUT")), end())
        val unsafeCopy = value.copy(endReason = "=PRIVATE_FORMULA,\"x\"\r\nPRIVATE_ROW",
            completionSource = "PRIVATE_SOURCE", userAssessment = "PRIVATE_ASSESSMENT",
            versions = value.versions.copy(guiProfileId = "PRIVATE_ID", adapterVersion = "+PRIVATE_VERSION"),
            errors = listOf(ExperimentAnalysis.ErrorCount("MODEL", "API_ERROR", "PRIVATE_RESPONSE", 1),
                ExperimentAnalysis.ErrorCount("PRIVATE_CATEGORY", "PRIVATE_CODE", null, 2)))
        val csv = ExperimentAnalysis.csv(listOf(unsafeCopy))
        assertFalse(csv.contains("PRIVATE"))
        assertFalse(csv.contains("https://"))
        assertTrue(csv.startsWith("\"analysis_version\",\"session_id\""))
        assertEquals(2, csv.split("\r\n").count { it.isNotEmpty() })
        assertEquals(45, csv.lineSequence().first().split(',').size)
        assertEquals(45, csv.lineSequence().drop(1).first().split(',').size)
        assertTrue(csv.contains("MODEL/API_ERROR:1"))
    }

    private fun withId(a: ExperimentAnalysis.Analysis, id: String) = a.copy(sessionId = id)
    private fun analysis(vararg rows: JSONObject) = ExperimentAnalysis.fromRecords(records(*rows))
    private fun records(vararg rows: JSONObject) = rows.mapIndexed { index, row ->
        JSONObject(row.toString()).apply {
            if (!has("session_id")) put("session_id", ID)
            if (!has("seq")) put("seq", index + 1)
            if (!has("elapsed_ms")) put("elapsed_ms", index * 100L)
            if (!has("schema_version")) put("schema_version", "1.3.0")
        }
    }
    private fun event(name: String, vararg fields: Pair<String, Any?>) = JSONObject().put("event", name)
        .put("data", JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } })
    private fun start(harness: Boolean? = true) = event("session_started", "gui_budget" to 30).apply {
        harness?.let { getJSONObject("data").put("harness_enabled", it) }
    }
    private fun end(reason: String = "model_declared_complete", gui: Int = 1, planner: Int = 0) = event("session_ended",
        "reason" to reason, "completion_source" to (if (reason == "model_declared_complete") "gui" else "none"),
        "gui_steps_used" to gui, "planner_calls_used" to planner)
    private fun call(role: String, index: Int, status: String, duration: Long? = null) = event("model_call",
        "role" to role, "call_index" to index, "status" to status).apply {
        duration?.let { getJSONObject("data").put("duration_ms", it) }
    }
    private fun assessment(value: String) = event("task_assessment", "assessment" to value)

    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000901"
        const val ID2 = "00000000-0000-4000-8000-000000000902"
        const val ID3 = "00000000-0000-4000-8000-000000000903"
        const val ID4 = "00000000-0000-4000-8000-000000000904"
    }
}
