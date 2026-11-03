package org.mobileharness.app.model

import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.core.*
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Execution feedback changes the information condition, not the native action or image protocol. */
class ExecutionFailureFeedbackTest {
    private val project = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "prompts/v1.1.0").isDirectory }
    private val assembler = ModelRequestAssembler { File(project, "prompts/v1.1.0/$it").readText(Charsets.UTF_8) }
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    private val frame = ScreenFrame("current-only", png, 1, 1)
    private val options = SessionOptions("Synthetic task", "org.synthetic", setOf("org.synthetic"), false, 30,
        ModelProfile(), ModelProfile())
    private val reply = GuiReply("original_call", PhoneAction("tap", mapOf("x" to 123, "y" to 456)), "{\"x\":123,\"y\":456}")

    @Test fun executionPolicy11FeedbackIsAFrozenContentFreeWhitelist() {
        assertEquals(18, ExecutionFailureReason.entries.size)
        val fixedContract = ExecutionFailureReason.entries.joinToString("\n") { "${it.name}|${it.canReobserve}|${it.modelFeedback}" }
        val digest = MessageDigest.getInstance("SHA-256").digest(fixedContract.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals("39955778c076618c0998807624a4c021ad0623e7bca7d641c43475644df5637c", digest)
        assertTrue(ExecutionFailureReason.entries.all { it.modelFeedback.all { ch -> ch in ' '..'~' } })
    }

    @Test fun everyClassifiedFailureUsesOnlyItsFixedMessageInNativeToolResult() {
        for (reason in ExecutionFailureReason.entries) {
            val body = request(ActionResult("device_error", "PRIVATE_DYNAMIC_EXCEPTION_WITH_INPUT_OR_KEY", reason))
            val messages = body["messages"].asJsonArray
            val tool = messages.single { it.asJsonObject["role"].asString == "tool" }.asJsonObject
            val result = StrictJson.parse(tool["content"].asString).asJsonObject
            assertEquals(setOf("execution_status", "message"), result.keySet())
            assertEquals("device_error", result["execution_status"].asString)
            assertEquals(reason.modelFeedback, result["message"].asString)
            assertFalse(result["message"].asString.isBlank())
            assertFalse(body.toString().contains("PRIVATE_DYNAMIC_EXCEPTION_WITH_INPUT_OR_KEY"))
            assertEquals("original_call", tool["tool_call_id"].asString)
            val original = messages.single { it.asJsonObject["role"].asString == "assistant" }
                .asJsonObject["tool_calls"].asJsonArray.single().asJsonObject["function"].asJsonObject
            assertEquals("tap", original["name"].asString)
            assertEquals(reply.argumentsJson, original["arguments"].asString)
            assertEquals(1, Regex("data:image/png;base64,").findAll(body.toString()).count())
            assertEquals(png.toList(), frame.png.toList())
        }
    }

    @Test fun unclassifiedEmptyResultKeepsThePriorNativeShape() {
        val messages = request(ActionResult("completed"))["messages"].asJsonArray
        val result = StrictJson.parse(messages.single { it.asJsonObject["role"].asString == "tool" }
            .asJsonObject["content"].asString).asJsonObject
        assertEquals(setOf("execution_status", "message"), result.keySet())
        assertEquals("completed", result["execution_status"].asString)
        assertEquals("", result["message"].asString)
    }

    @Test fun harnessToggleDoesNotChangeFailureFeedbackInformation() {
        val result = ActionResult("device_error", failureReason = ExecutionFailureReason.NO_FOCUSED_INPUT)
        assertEquals(request(result), request(result, options.copy(harness = true)))
    }

    private fun request(result: ActionResult, session: SessionOptions = options): JsonObject =
        StrictJson.parse(assembler.gui(GuiContext(session, Phase.ORIGINAL,
            history = listOf(Turn(reply, result)), frame = frame)).toString(Charsets.UTF_8)).asJsonObject
}
