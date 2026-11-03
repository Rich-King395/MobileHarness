package org.mobileharness.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.RunFailure
import java.io.File

class ProtocolDiagnosticsTest {
    private val promptRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "prompts/v1.1.0") }.first { it.isDirectory }
    private val assembler = ModelRequestAssembler { File(promptRoot, it).readText(Charsets.UTF_8) }
    private val protocol = QwenProtocol(assembler.executionTools, assembler.observationTools)

    private fun response(): JsonObject = StrictJson.parse("""{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"synthetic-call","type":"function","function":{"name":"tap","arguments":"{\"x\":500,\"y\":500}"}}]}}]}""").asJsonObject
    private fun choice(root: JsonObject) = root["choices"].asJsonArray[0].asJsonObject
    private fun message(root: JsonObject) = choice(root)["message"].asJsonObject
    private fun call(root: JsonObject) = message(root)["tool_calls"].asJsonArray[0].asJsonObject
    private fun function(root: JsonObject) = call(root)["function"].asJsonObject
    private fun changed(block: (JsonObject) -> Unit) = response().also(block).toString()
    private fun arguments(value: String) = changed { function(it).addProperty("arguments", value) }

    private fun assertGuiReason(expected: ProtocolReason, response: String, phase: Phase = Phase.ORIGINAL) {
        assertEquals(expected, protocol.inspectGui(response, phase).reason)
        if (expected == ProtocolReason.VALID) protocol.gui(response, phase) else {
            try { protocol.gui(response, phase); fail("Rejected diagnostic must reject execution") }
            catch (failure: RunFailure) {
                assertEquals("MODEL", failure.category)
                assertEquals("INVALID_OUTPUT", failure.code)
                assertEquals("INVALID_OUTPUT", failure.message)
                assertEquals(expected.name, failure.protocolReason)
                assertNull(failure.cause)
            }
        }
    }

    @Test fun validNativeActionHasOnlyFixedMetadataAndSameExecutionDecision() {
        val raw = changed { message(it).addProperty("reasoning_content", "") }
        val report = protocol.inspectGui(raw, Phase.ORIGINAL)
        assertGuiReason(ProtocolReason.VALID, raw)
        assertEquals(ProtocolFinishReason.TOOL_CALLS, report.finishReason)
        assertEquals(1, report.choiceCount)
        assertEquals(1, report.toolCallCount)
        assertEquals(ProtocolTextShape.NULL, report.contentShape)
        assertNull(report.contentChars)
        assertEquals(ProtocolTextShape.EMPTY_STRING, report.reasoningShape)
        assertEquals(0, report.reasoningChars)
        assertEquals(ProtocolToolName.TAP, report.toolName)
    }

    @Test fun naturalLanguageStopAndTokenLimitAreDistinguishedWithoutAcceptingEither() {
        val natural = changed {
            choice(it).addProperty("finish_reason", "stop")
            message(it).addProperty("content", "synthetic completion text")
            message(it).remove("tool_calls")
        }
        assertGuiReason(ProtocolReason.GUI_STOP_WITHOUT_TOOL, natural)
        val report = protocol.inspectGui(natural, Phase.ORIGINAL)
        assertEquals(ProtocolFinishReason.STOP, report.finishReason)
        assertEquals(ProtocolTextShape.NONEMPTY_STRING, report.contentShape)
        assertEquals(25, report.contentChars)
        assertNull(report.toolCallCount)
        assertEquals(ProtocolToolName.ABSENT, report.toolName)
        val limited = changed { choice(it).addProperty("finish_reason", "length") }
        assertGuiReason(ProtocolReason.RESPONSE_TRUNCATED, limited)
        assertEquals(ProtocolFinishReason.LENGTH, protocol.inspectGui(limited, Phase.ORIGINAL).finishReason)
    }

    @Test fun responseEnvelopeRejectionsAreClassified() {
        val cases = listOf(
            ProtocolReason.RESPONSE_JSON_INVALID to "{",
            ProtocolReason.RESPONSE_JSON_DUPLICATE_FIELD to "{\"choices\":[],\"choices\":[]}",
            ProtocolReason.RESPONSE_JSON_DEPTH to "[".repeat(66) + "0" + "]".repeat(66),
            ProtocolReason.RESPONSE_ROOT to "[]",
            ProtocolReason.RESPONSE_ERROR to changed { it.addProperty("error", "synthetic error") },
            ProtocolReason.CHOICES_SHAPE to changed { it.remove("choices") },
            ProtocolReason.CHOICES_SHAPE to changed { it.addProperty("choices", "synthetic") },
            ProtocolReason.CHOICE_COUNT to changed { it.add("choices", JsonArray()) },
            ProtocolReason.CHOICE_COUNT to changed { it["choices"].asJsonArray.add(choice(it).deepCopy()) },
            ProtocolReason.CHOICE_SHAPE to changed { it["choices"].asJsonArray.set(0, JsonNull.INSTANCE) },
            ProtocolReason.FINISH_REASON to changed { choice(it).remove("finish_reason") },
            ProtocolReason.CHOICE_INDEX to changed { choice(it).addProperty("index", "0") },
            ProtocolReason.MESSAGE_SHAPE to changed { choice(it).add("message", JsonNull.INSTANCE) },
            ProtocolReason.MESSAGE_ROLE to changed { message(it).addProperty("role", "user") },
            ProtocolReason.REASONING_CONTENT to changed { message(it).addProperty("reasoning_content", "synthetic reasoning") },
            ProtocolReason.REASONING_CONTENT to changed { message(it).add("reasoning_content", JsonArray()) },
            ProtocolReason.GUI_CONTENT to changed { message(it).addProperty("content", "synthetic prose") },
            ProtocolReason.GUI_CONTENT to changed { message(it).add("content", JsonArray()) },
        )
        cases.forEach { (reason, raw) -> assertGuiReason(reason, raw) }
    }

    @Test fun toolStructureRejectionsAreClassified() {
        val cases = listOf(
            ProtocolReason.TOOL_CALLS_SHAPE to changed { message(it).remove("tool_calls") },
            ProtocolReason.TOOL_CALLS_SHAPE to changed { message(it).add("tool_calls", JsonNull.INSTANCE) },
            ProtocolReason.TOOL_CALL_COUNT to changed { message(it).add("tool_calls", JsonArray()) },
            ProtocolReason.TOOL_CALL_COUNT to changed { message(it)["tool_calls"].asJsonArray.add(call(it).deepCopy()) },
            ProtocolReason.TOOL_CALL_SHAPE to changed { message(it)["tool_calls"].asJsonArray.set(0, JsonNull.INSTANCE) },
            ProtocolReason.TOOL_CALL_TYPE to changed { call(it).addProperty("type", "other") },
            ProtocolReason.TOOL_CALL_ID to changed { call(it).remove("id") },
            ProtocolReason.TOOL_CALL_ID to changed { call(it).addProperty("id", " ") },
            ProtocolReason.FUNCTION_SHAPE to changed { call(it).add("function", JsonArray()) },
            ProtocolReason.FUNCTION_FIELDS to changed { function(it).addProperty("extra", "synthetic") },
            ProtocolReason.TOOL_NAME_TYPE to changed { function(it).add("name", JsonNull.INSTANCE) },
            ProtocolReason.TOOL_NOT_ALLOWED to changed { function(it).addProperty("name", "unknown-synthetic-tool") },
            ProtocolReason.ARGUMENTS_TYPE to changed { function(it).add("arguments", JsonObject()) },
            ProtocolReason.ARGUMENTS_JSON_INVALID to arguments("{\"x\":"),
            ProtocolReason.ARGUMENTS_JSON_DUPLICATE_FIELD to arguments("{\"x\":1,\"x\":2,\"y\":3}"),
            ProtocolReason.ARGUMENTS_JSON_DEPTH to arguments("[".repeat(66) + "0" + "]".repeat(66)),
            ProtocolReason.ARGUMENTS_SHAPE to arguments("[]"),
        )
        cases.forEach { (reason, raw) -> assertGuiReason(reason, raw) }
        assertGuiReason(ProtocolReason.TOOL_NOT_ALLOWED, response().toString(), Phase.VERIFY)
        assertGuiReason(ProtocolReason.TOOL_NOT_ALLOWED, response().toString(), Phase.CONFIRM)
        assertEquals(ProtocolToolName.TAP, protocol.inspectGui(response().toString(), Phase.VERIFY).toolName)
    }

    @Test fun closedSchemaRejectionsHaveFixedReasons() {
        val cases = listOf(
            ProtocolReason.SCHEMA_MISSING_REQUIRED_FIELD to arguments("{\"x\":2}"),
            ProtocolReason.SCHEMA_ADDITIONAL_FIELD to arguments("{\"x\":2,\"y\":3,\"synthetic_secret\":1}"),
            ProtocolReason.SCHEMA_WRONG_TYPE to arguments("{\"x\":\"2\",\"y\":3}"),
            ProtocolReason.SCHEMA_INTEGER_FORMAT to arguments("{\"x\":2.5,\"y\":3}"),
            ProtocolReason.SCHEMA_VALUE_OUT_OF_RANGE to arguments("{\"x\":1000,\"y\":3}"),
            ProtocolReason.SCHEMA_STRING_TOO_SHORT to changed {
                function(it).addProperty("name", "finish")
                function(it).addProperty("arguments", "{\"outcome\":\"completed\",\"message\":\"\"}")
            },
            ProtocolReason.SCHEMA_ENUM_VALUE to changed {
                function(it).addProperty("name", "finish")
                function(it).addProperty("arguments", "{\"outcome\":\"synthetic\",\"message\":\"text\"}")
            },
        )
        cases.forEach { (reason, raw) -> assertGuiReason(reason, raw) }
        val wait = changed {
            function(it).addProperty("name", "wait")
            function(it).addProperty("arguments", "{\"duration_ms\":100}")
        }
        assertGuiReason(ProtocolReason.VALID, wait, Phase.VERIFY)
        assertGuiReason(ProtocolReason.VALID, wait, Phase.CONFIRM)
    }

    @Test fun noUnknownProviderTextCanEscapeViaMetadataOrDiagnosticException() {
        val marker = "SYNTHETIC_SECRET_DO_NOT_EXPORT"
        val raw = changed {
            function(it).addProperty("name", marker)
            function(it).addProperty("arguments", "{\"x\":1,\"y\":2}")
            call(it).addProperty("id", marker)
            it.addProperty("provider_extra", marker)
        }
        val diagnostic = protocol.inspectGui(raw, Phase.ORIGINAL)
        assertEquals(ProtocolToolName.UNKNOWN, diagnostic.toolName)
        assertFalse(diagnostic.toString().contains(marker))
        val unknownFinish = changed { choice(it).addProperty("finish_reason", marker) }
        assertEquals(ProtocolFinishReason.OTHER, protocol.inspectGui(unknownFinish, Phase.ORIGINAL).finishReason)
        assertFalse(protocol.inspectGui(unknownFinish, Phase.ORIGINAL).toString().contains(marker))
        ProtocolDiagnostic::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            // Compose also emits a non-synthetic static primitive-int stability marker.
            assertTrue("Diagnostic fields must be enum or integer only: ${field.name}",
                field.type.isEnum || field.type == Int::class.javaObjectType || field.type == Int::class.javaPrimitiveType)
        }
        try { StrictJson.parse("{\"$marker\":\"truncated") ; fail("Expected rejection") }
        catch (failure: StrictJsonViolation) {
            assertEquals("INVALID", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(marker))
        }
    }

    @Test fun finishReasonMetadataCoversMissingNullWrongTypeAndKnownValues() {
        val cases = listOf(
            ProtocolFinishReason.ABSENT to changed { choice(it).remove("finish_reason") },
            ProtocolFinishReason.NULL to changed { choice(it).add("finish_reason", JsonNull.INSTANCE) },
            ProtocolFinishReason.INVALID_TYPE to changed { choice(it).addProperty("finish_reason", 5) },
            ProtocolFinishReason.CONTENT_FILTER to changed { choice(it).addProperty("finish_reason", "content_filter") },
            ProtocolFinishReason.FUNCTION_CALL to changed { choice(it).addProperty("finish_reason", "function_call") },
        )
        cases.forEach { (finish, raw) ->
            assertGuiReason(ProtocolReason.FINISH_REASON, raw)
            assertEquals(finish, protocol.inspectGui(raw, Phase.ORIGINAL).finishReason)
        }
    }

    private fun plannerResponse(content: String): String = changed {
        choice(it).addProperty("finish_reason", "stop")
        message(it).remove("tool_calls")
        message(it).addProperty("content", content)
    }

    @Test fun plannerUsesSameStrictParserAndPreservesSecondCallFinishRule() {
        val finish = """{"completion_status":"COMPLETE","decision":"FINISH","objective":"","completion_conditions":[]}"""
        val raw = plannerResponse(finish)
        assertEquals(ProtocolReason.VALID, protocol.inspectPlanner(raw, 2).reason)
        assertEquals("FINISH", protocol.planner(raw, 2).decision)
        assertEquals(ProtocolReason.PLANNER_BRANCH, protocol.inspectPlanner(raw, 1).reason)
        assertEquals(ProtocolReason.PLANNER_CALL_INDEX, protocol.inspectPlanner(raw, 3).reason)
        val truncated = StrictJson.parse(raw).asJsonObject.also { choice(it).addProperty("finish_reason", "length") }.toString()
        assertEquals(ProtocolReason.RESPONSE_TRUNCATED, protocol.inspectPlanner(truncated, 2).reason)
        try { protocol.planner(truncated, 2); fail("Truncated output must never finish a task") }
        catch (failure: RunFailure) {
            assertEquals("INVALID_OUTPUT", failure.code)
            assertEquals(ProtocolReason.RESPONSE_TRUNCATED.name, failure.protocolReason)
        }
        assertEquals(ProtocolReason.PLANNER_JSON_INVALID, protocol.inspectPlanner(plannerResponse("{"), 2).reason)
        assertEquals(ProtocolReason.PLANNER_JSON_DUPLICATE_FIELD,
            protocol.inspectPlanner(plannerResponse(finish.replace("\"objective\":", "\"objective\":\"\",\"objective\":")), 2).reason)
        assertEquals(ProtocolReason.PLANNER_JSON_DEPTH,
            protocol.inspectPlanner(plannerResponse("[".repeat(66) + "0" + "]".repeat(66)), 2).reason)
        assertEquals(ProtocolReason.PLANNER_OUTPUT_SHAPE, protocol.inspectPlanner(plannerResponse("[]"), 2).reason)
        assertEquals(ProtocolReason.PLANNER_FIELDS, protocol.inspectPlanner(plannerResponse("{}"), 2).reason)
        assertEquals(ProtocolReason.PLANNER_VALUE_TYPE,
            protocol.inspectPlanner(plannerResponse(finish.replace("\"objective\":\"\"", "\"objective\":false")), 2).reason)
        assertEquals(ProtocolReason.PLANNER_CONDITIONS,
            protocol.inspectPlanner(plannerResponse(finish.replace("\"completion_conditions\":[]", "\"completion_conditions\":[null]")), 2).reason)
        assertEquals(ProtocolReason.PLANNER_FINISH_FIELDS,
            protocol.inspectPlanner(plannerResponse(finish.replace("\"objective\":\"\"", "\"objective\":\"synthetic\"")), 2).reason)
        val execute = finish.replace("COMPLETE", "INCOMPLETE").replace("FINISH", "EXECUTE")
        assertEquals(ProtocolReason.PLANNER_OBJECTIVE, protocol.inspectPlanner(plannerResponse(execute), 2).reason)
        assertEquals(ProtocolReason.PLANNER_CONDITIONS,
            protocol.inspectPlanner(plannerResponse(execute.replace("\"objective\":\"\"", "\"objective\":\"synthetic\"")), 2).reason)
    }

    @Test fun malformedPlannerEnvelopeAndToolCallsStayRejected() {
        val withCall = changed { choice(it).addProperty("finish_reason", "stop") }
        assertEquals(ProtocolReason.PLANNER_TOOL_CALLS, protocol.inspectPlanner(withCall, 1).reason)
        val nullContent = changed {
            choice(it).addProperty("finish_reason", "stop")
            message(it).remove("tool_calls")
        }
        assertEquals(ProtocolReason.PLANNER_CONTENT_TYPE, protocol.inspectPlanner(nullContent, 1).reason)
        for ((raw, expected) in listOf(withCall to ProtocolReason.PLANNER_TOOL_CALLS, nullContent to ProtocolReason.PLANNER_CONTENT_TYPE)) {
            try { protocol.planner(raw, 1); fail("Expected rejection") }
            catch (failure: RunFailure) {
                assertEquals("INVALID_OUTPUT", failure.code)
                assertEquals(expected.name, failure.protocolReason)
                assertNull(failure.cause)
            }
        }
    }

    @Test fun unexpectedValidationFailureHasFixedFallbackAndNoRawCause() {
        val brokenTools = JsonArray().apply {
            add(JsonObject().apply {
                add("function", JsonObject().apply { addProperty("name", "tap") })
            })
        }
        val broken = QwenProtocol(brokenTools, assembler.observationTools)
        assertEquals(ProtocolReason.VALIDATION_ERROR, broken.inspectGui(response().toString(), Phase.ORIGINAL).reason)
        try { broken.gui(response().toString(), Phase.ORIGINAL); fail("Expected rejection") }
        catch (failure: RunFailure) {
            assertEquals("MODEL", failure.category)
            assertEquals("INVALID_OUTPUT", failure.code)
            assertEquals(ProtocolReason.VALIDATION_ERROR.name, failure.protocolReason)
            assertEquals("INVALID_OUTPUT", failure.message)
            assertNull(failure.cause)
        }
    }
}
