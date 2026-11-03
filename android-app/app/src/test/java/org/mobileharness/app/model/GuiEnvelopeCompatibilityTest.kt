package org.mobileharness.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.core.ActionResult
import org.mobileharness.app.core.GuiContext
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.RunFailure
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.core.Turn
import java.io.File
import java.util.Base64

class GuiEnvelopeCompatibilityTest {
    private val promptRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "prompts/v1.1.0") }.first { it.isDirectory }
    private val assembler = ModelRequestAssembler { File(promptRoot, it).readText(Charsets.UTF_8) }
    private val protocol = QwenProtocol(assembler.executionTools, assembler.observationTools)
    private val arguments = "{ \"x\" : 501, \"y\" : 502 }"
    private val prose = "AUXILIARY_PRIVATE_PROSE: synthetic explanation, never an executable instruction or history turn."

    private fun response(): JsonObject = JsonObject().apply {
        add("choices", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("index", 0)
                addProperty("finish_reason", "tool_calls")
                add("message", JsonObject().apply {
                    addProperty("role", "assistant")
                    addProperty("content", prose)
                    addProperty("reasoning_content", "")
                    add("tool_calls", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", "synthetic-call")
                            addProperty("type", "function")
                            add("function", JsonObject().apply {
                                addProperty("name", "tap")
                                addProperty("arguments", arguments)
                            })
                        })
                    })
                })
            })
        })
    }

    private fun choice(root: JsonObject) = root["choices"].asJsonArray[0].asJsonObject
    private fun message(root: JsonObject) = choice(root)["message"].asJsonObject
    private fun function(root: JsonObject) = message(root)["tool_calls"].asJsonArray[0].asJsonObject["function"].asJsonObject
    private fun changed(block: (JsonObject) -> Unit) = response().also(block).toString()

    private fun assertRejected(raw: String, expected: ProtocolReason, phase: Phase = Phase.ORIGINAL) {
        assertEquals(expected, protocol.inspectGui(raw, phase, allowAuxiliaryText = true).reason)
        try { protocol.gui(raw, phase, allowAuxiliaryText = true); fail("Compatibility must still reject invalid actions") }
        catch (failure: RunFailure) {
            assertEquals("MODEL", failure.category)
            assertEquals("INVALID_OUTPUT", failure.code)
            assertEquals(expected.name, failure.protocolReason)
            assertNull(failure.cause)
        }
    }

    @Test fun legacyDefaultStillRejectsAuxiliaryContentAndCompatibilityPreservesExactTool() {
        val raw = response().toString()
        assertEquals(ProtocolReason.GUI_CONTENT, protocol.inspectGui(raw, Phase.ORIGINAL).reason)
        try { protocol.gui(raw, Phase.ORIGINAL); fail("Legacy default must stay strict") }
        catch (failure: RunFailure) { assertEquals(ProtocolReason.GUI_CONTENT.name, failure.protocolReason) }
        val baselineRaw = changed { message(it).add("content", JsonNull.INSTANCE) }
        val baseline = protocol.gui(baselineRaw, Phase.ORIGINAL)
        val compatible = protocol.gui(raw, Phase.ORIGINAL, allowAuxiliaryText = true)
        assertEquals(baseline, compatible)
        assertEquals("synthetic-call", compatible.callId)
        assertEquals("tap", compatible.action.name)
        assertEquals(mapOf("x" to 501, "y" to 502), compatible.action.arguments)
        assertArrayEquals(arguments.toByteArray(Charsets.UTF_8), compatible.argumentsJson.toByteArray(Charsets.UTF_8))
        val diagnostic = protocol.inspectGui(raw, Phase.ORIGINAL, allowAuxiliaryText = true)
        assertEquals(ProtocolReason.VALID, diagnostic.reason)
        assertEquals(ProtocolTextShape.NONEMPTY_STRING, diagnostic.contentShape)
        assertEquals(ProtocolToolName.TAP, diagnostic.toolName)
        assertFalse(compatible.toString().contains(prose))
    }

    @Test fun auxiliaryTextNeverEntersTheToolConversationOrNextRequest() {
        val reply = protocol.gui(response().toString(), Phase.ORIGINAL, allowAuxiliaryText = true)
        val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
        val options = SessionOptions("SYNTHETIC_TASK", "synthetic.app", setOf("synthetic.app"), false, 30,
            ModelProfile(baseUrl = "https://gui.example/v1", model = "synthetic", apiKey = "synthetic-key"), ModelProfile())
        val history = listOf(Turn(reply, ActionResult("completed")))
        val request = assembler.gui(GuiContext(options, Phase.ORIGINAL, history = history,
            frame = ScreenFrame("synthetic-current", png, 1, 1))).toString(Charsets.UTF_8)
        assertFalse(history.toString().contains(prose))
        assertFalse(request.contains(prose))
        assertFalse(request.contains("AUXILIARY_PRIVATE_PROSE"))
        val messages = StrictJson.parse(request).asJsonObject["messages"].asJsonArray
        val assistant = messages.first { it.asJsonObject["role"].asString == "assistant" }.asJsonObject
        assertTrue(assistant["content"].isJsonNull)
        val savedCall = assistant["tool_calls"].asJsonArray.single().asJsonObject
        assertEquals(reply.callId, savedCall["id"].asString)
        assertEquals(StrictJson.parse(arguments), StrictJson.parse(savedCall["function"].asJsonObject["arguments"].asString))
    }

    @Test fun onlyAbsentNullOrStringContentIsCompatible() {
        val acceptable = listOf(
            changed { message(it).remove("content") },
            changed { message(it).add("content", JsonNull.INSTANCE) },
            changed { message(it).addProperty("content", "") },
            response().toString(),
        )
        acceptable.forEach { raw ->
            assertEquals("tap", protocol.gui(raw, Phase.ORIGINAL, allowAuxiliaryText = true).action.name)
            assertEquals(ProtocolReason.VALID, protocol.inspectGui(raw, Phase.ORIGINAL, allowAuxiliaryText = true).reason)
        }
        listOf(
            changed { message(it).add("content", JsonArray()) },
            changed { message(it).add("content", JsonObject()) },
            changed { message(it).addProperty("content", 1) },
            changed { message(it).addProperty("content", false) },
        ).forEach { assertRejected(it, ProtocolReason.GUI_CONTENT) }
    }

    @Test fun compatibilityNeverTurnsProseStopTruncationOrMultipleToolsIntoActions() {
        val cases = listOf(
            ProtocolReason.GUI_STOP_WITHOUT_TOOL to changed {
                choice(it).addProperty("finish_reason", "stop"); message(it).remove("tool_calls")
            },
            ProtocolReason.RESPONSE_TRUNCATED to changed { choice(it).addProperty("finish_reason", "length") },
            ProtocolReason.FINISH_REASON to changed { choice(it).addProperty("finish_reason", "unknown-synthetic") },
            ProtocolReason.TOOL_CALLS_SHAPE to changed { message(it).remove("tool_calls") },
            ProtocolReason.TOOL_CALL_COUNT to changed { message(it).add("tool_calls", JsonArray()) },
            ProtocolReason.TOOL_CALL_COUNT to changed {
                val calls = message(it)["tool_calls"].asJsonArray; calls.add(calls[0].deepCopy())
            },
            ProtocolReason.TOOL_NOT_ALLOWED to changed { function(it).addProperty("name", "unknown-synthetic") },
            ProtocolReason.REASONING_CONTENT to changed { message(it).addProperty("reasoning_content", "synthetic reasoning") },
        )
        cases.forEach { (reason, raw) -> assertRejected(raw, reason) }
        assertRejected(response().toString(), ProtocolReason.TOOL_NOT_ALLOWED, Phase.VERIFY)
        assertRejected(response().toString(), ProtocolReason.TOOL_NOT_ALLOWED, Phase.CONFIRM)
    }

    @Test fun compatibilityStillRequiresClosedValidArgumentsAndFunctionStructure() {
        val cases = listOf(
            ProtocolReason.ARGUMENTS_JSON_INVALID to "{",
            ProtocolReason.ARGUMENTS_JSON_DUPLICATE_FIELD to "{\"x\":1,\"x\":2,\"y\":3}",
            ProtocolReason.ARGUMENTS_SHAPE to "[]",
            ProtocolReason.SCHEMA_MISSING_REQUIRED_FIELD to "{\"x\":1}",
            ProtocolReason.SCHEMA_ADDITIONAL_FIELD to "{\"x\":1,\"y\":2,\"extra\":3}",
            ProtocolReason.SCHEMA_WRONG_TYPE to "{\"x\":\"1\",\"y\":2}",
            ProtocolReason.SCHEMA_INTEGER_FORMAT to "{\"x\":1.5,\"y\":2}",
            ProtocolReason.SCHEMA_VALUE_OUT_OF_RANGE to "{\"x\":1000,\"y\":2}",
        )
        cases.forEach { (reason, args) -> assertRejected(changed { function(it).addProperty("arguments", args) }, reason) }
        assertRejected(changed { function(it).addProperty("extra", "synthetic") }, ProtocolReason.FUNCTION_FIELDS)
        assertRejected(response().toString().dropLast(1), ProtocolReason.RESPONSE_JSON_INVALID)
    }
}
