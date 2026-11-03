package org.mobileharness.app.model

import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.core.ActionResult
import org.mobileharness.app.core.GuiContext
import org.mobileharness.app.core.GuiReply
import org.mobileharness.app.core.ModelProfile
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.PhoneAction
import org.mobileharness.app.core.PlannerContext
import org.mobileharness.app.core.RunFailure
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.core.SessionOptions
import org.mobileharness.app.core.StartMode
import org.mobileharness.app.core.Turn
import java.io.File
import java.util.Base64

/** Synthetic request checks: automatic entry changes task metadata, not the frozen phone protocol. */
class AutomaticStartAssemblyTest {
    private val project = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "prompts/v1.1.0").isDirectory }
    private val baselineAssets: (String) -> String = { name ->
        File(project, PromptBundles.assetPath("1.1.0", name)).readText(Charsets.UTF_8)
    }
    private val automaticAssets: (String) -> String = { name ->
        File(project, PromptBundles.assetPath("1.2.0", name)).readText(Charsets.UTF_8)
    }
    private val baseline = ModelRequestAssembler(baselineAssets)
    private val automatic = ModelRequestAssembler(automaticAssets)
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
    private val literalTask = "SYNTHETIC_REQUIRED_42\nKeep {{ORIGINAL_TASK}} and literal 23 × 47 unchanged."
    private val labels = mapOf("org.synthetic.notes" to "合成笔记", "org.synthetic.calculator" to "Calculator {{NOT_A_TEMPLATE}}")

    private fun options() = SessionOptions(
        task = literalTask,
        initialPackage = "org.synthetic.launcher",
        authorizedPackages = setOf("org.synthetic.notes", "org.synthetic.calculator"),
        harness = true, budget = 30,
        gui = ModelProfile(baseUrl = "https://gui.example/v1", model = "synthetic-gui", apiKey = "synthetic-source-gui-key"),
        planner = ModelProfile(baseUrl = "https://planner.example/v1", model = "synthetic-planner", apiKey = "synthetic-source-planner-key"),
        startMode = StartMode.HOME_AUTO,
        homePackage = "org.synthetic.launcher",
        authorizedAppLabels = labels,
    )

    private fun frame(id: String = "current-only") = ScreenFrame(id, png.copyOf(), 1, 1)
    private fun context(phase: Phase = Phase.ORIGINAL) = GuiContext(options(), phase,
        objective = if (phase == Phase.ORIGINAL) "" else "SYNTHETIC_PHASE_OBJECTIVE",
        conditions = if (phase == Phase.ORIGINAL) emptyList() else listOf("SYNTHETIC_VISIBLE_VALUE"),
        frame = frame())
    private fun body(bytes: ByteArray) = StrictJson.parse(bytes.toString(Charsets.UTF_8)).asJsonObject
    private fun withoutMessages(value: JsonObject) = value.deepCopy().apply { remove("messages") }
    private fun taskMessage(value: JsonObject) = value["messages"].asJsonArray[1].asJsonObject["content"].asString
    private fun metadataLine(text: String, prefix: String) = text.lineSequence().single { it.startsWith(prefix) }.removePrefix(prefix)

    private fun assertAutomaticMetadata(value: JsonObject, expected: SessionOptions, phase: Phase) {
        val task = taskMessage(value)
        assertTrue(task.startsWith("Phase: ${phase.name}"))
        assertEquals(1, Regex(Regex.escape(expected.task)).findAll(task).count())
        assertFalse(task.contains("User-selected initial App package"))
        assertEquals("HOME_AUTO", metadataLine(task, "- Start mode: "))
        assertEquals(expected.homePackage, metadataLine(task, "- Navigation-only Home package: "))
        assertEquals(expected.initialPackage, metadataLine(task, "- Initial screen package (navigation entry, not a chosen target App): "))
        val packages = StrictJson.parse(metadataLine(task, "- Task-authorized App package names: ")).asJsonArray.map { it.asString }.toSet()
        assertEquals(expected.authorizedPackages, packages)
        assertFalse(packages.contains(expected.homePackage))
        val actualLabels = StrictJson.parse(metadataLine(task, "- Authorized App display labels (identifiers only): "))
            .asJsonObject.entrySet().associate { it.key to it.value.asString }
        assertEquals(expected.authorizedAppLabels, actualLabels)
    }

    @Test fun allFourPhasesUseAutomaticEntryMetadataAndPreserveLiteralTask() {
        for (phase in Phase.entries) {
            val ctx = context(phase)
            val request = body(automatic.gui(ctx))
            assertAutomaticMetadata(request, ctx.options, phase)
            val system = request["messages"].asJsonArray[0].asJsonObject["content"].asString
            assertEquals(automaticAssets("gui/system.txt"), system)
            assertNotEquals(baselineAssets("gui/system.txt"), system)
            assertTrue(system.contains("When start mode is HOME_AUTO"))
            assertTrue(system.contains("Home is navigation-only"))
            assertFalse(system.contains("User-selected initial App package"))
            if (phase != Phase.ORIGINAL) {
                assertTrue(taskMessage(request).contains("SYNTHETIC_PHASE_OBJECTIVE"))
                assertTrue(taskMessage(request).contains("SYNTHETIC_VISIBLE_VALUE"))
            }
        }
    }

    @Test fun nativeToolsSamplingAndSingleCurrentImageRemainIdenticalToBaseline() {
        for (phase in Phase.entries) {
            val ctx = context(phase)
            val expected = body(baseline.gui(ctx))
            val actual = body(automatic.gui(ctx))
            // Compare every HTTP body field outside messages, not just selected sampling fields.
            assertEquals(withoutMessages(expected), withoutMessages(actual))
            assertEquals(expected["messages"].asJsonArray.last(), actual["messages"].asJsonArray.last())
            val names = actual["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString }.toSet()
            if (phase in setOf(Phase.VERIFY, Phase.CONFIRM)) assertEquals(setOf("wait", "finish"), names)
            else assertEquals(setOf("tap", "long_press", "swipe", "input_text", "press_key", "launch_app", "wait", "finish"), names)
            assertEquals(1, Regex("data:image/png;base64,").findAll(actual.toString()).count())
            val observation = actual["messages"].asJsonArray.last().asJsonObject["content"].asJsonArray
            val image = observation.single { it.asJsonObject["type"].asString == "image_url" }.asJsonObject
            assertEquals(2_621_440, image["max_pixels"].asInt)
            assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), image["image_url"].asJsonObject["url"].asString)
            assertArrayEquals(png, ctx.frame.png)
        }
        val before = StrictJson.parse(baselineAssets("request-profiles.json")).asJsonObject
        val after = StrictJson.parse(automaticAssets("request-profiles.json")).asJsonObject
        for (field in listOf("common_request_body", "gui_body_overrides", "planner_body_overrides", "coordinates", "image_policy")) {
            assertEquals(before[field], after[field])
        }
        assertEquals(baseline.executionTools, automatic.executionTools)
        assertEquals(baseline.observationTools, automatic.observationTools)
    }

    @Test fun plannerOneAndTwoBodiesRemainIdenticalForTheSamePublicEvidence() {
        val completed = Turn(GuiReply("own-wait", PhoneAction("wait", mapOf("duration_ms" to 100)), "{\"duration_ms\":100}"), ActionResult("completed"))
        val failed = completed.copy(reply = completed.reply.copy(callId = "failed-wait"), result = ActionResult("device_error"))
        for (callIndex in 1..2) {
            val ctx = PlannerContext(options(), callIndex, listOf(completed, failed),
                listOf(frame("before-return"), frame("current-only")), "current-only", "before-return",
                checkObjective = "SYNTHETIC_CHECK_OBJECTIVE", checkConditions = listOf("SYNTHETIC_EVIDENCE"),
                checkReport = "SYNTHETIC_UNVERIFIED_REPORT", verificationFailed = callIndex == 2)
            assertEquals(body(baseline.planner(ctx)), body(automatic.planner(ctx)))
        }
    }

    @Test fun missingAutomaticReaderFailsPreflightWithoutAnyHttpAttempt() {
        val transport = RecordingTransport()
        val gateway = QwenGateway(baselineAssets, transport)
        expectInvalidState { gateway.preflightGui(context()) }
        expectInvalidState { gateway.preflightPlanner(PlannerContext(options(), 1, emptyList(), listOf(frame()), "current-only", null)) }
        assertEquals(0, transport.calls)
        val missingSystem: (String) -> String = { name ->
            if (name == "gui/system.txt") throw java.io.FileNotFoundException("SYNTHETIC_MISSING_OVERRIDE")
            automaticAssets(name)
        }
        val broken = QwenGateway(baselineAssets, transport, missingSystem)
        expectInvalidState { broken.preflightGui(context()) }
        assertEquals(0, transport.calls)
    }

    @Test fun actualGatewayUsesNewSystemAndScopeButKeepsCredentialOutOfBody() = runTest {
        val transport = RecordingTransport()
        var resolutions = 0
        val gateway = QwenGateway(baselineAssets, transport, automaticAssets) { resolutions++; "synthetic-resolved-key" }
        val ctx = context().let { it.copy(options = it.options.copy(gui = it.options.gui.copy(apiKey = ""))) }
        gateway.preflightGui(ctx)
        assertEquals(0, transport.calls)
        val reply = gateway.gui(ctx)
        assertEquals("launch_app", reply.action.name)
        assertEquals("org.synthetic.calculator", reply.action.arguments["package_name"])
        assertEquals(1, transport.calls)
        assertEquals(1, resolutions)
        assertEquals("synthetic-resolved-key", transport.key)
        val request = body(checkNotNull(transport.sentBody).toByteArray(Charsets.UTF_8))
        assertAutomaticMetadata(request, ctx.options, Phase.ORIGINAL)
        assertEquals(automaticAssets("gui/system.txt"), request["messages"].asJsonArray[0].asJsonObject["content"].asString)
        assertFalse(request.toString().contains("synthetic-resolved-key"))
        assertFalse(request.toString().contains("synthetic-source-gui-key"))
        assertFalse(request.toString().contains("synthetic-source-planner-key"))
        assertEquals("", ctx.options.gui.apiKey)
        assertTrue(checkNotNull(transport.retainedBody).all { it == 0.toByte() })
    }

    @Test fun invalidAutomaticScopeFailsBeforeHttpRatherThanFallingBack() {
        val transport = RecordingTransport()
        val gateway = QwenGateway(baselineAssets, transport, automaticAssets)
        val valid = options()
        val invalid = listOf(
            valid.copy(homePackage = ""),
            valid.copy(initialPackage = "org.synthetic.other-home"),
            valid.copy(authorizedPackages = emptySet(), authorizedAppLabels = emptyMap()),
            valid.copy(authorizedAppLabels = labels + ("org.synthetic.unauthorized" to "unapproved")),
        )
        for (value in invalid) expectInvalidState { gateway.preflightGui(context().copy(options = value)) }
        assertEquals(0, transport.calls)
    }

    @Test fun automaticBundleExplicitlyInheritsOnlyUnchangedAssets() {
        for (name in listOf("gui/system.txt", "gui/original.task.txt", "gui/verify.task.txt", "gui/confirm.task.txt", "gui/recovery.task.txt", "request-profiles.json")) {
            assertEquals("prompts/v1.2.0/$name", PromptBundles.assetPath("1.2.0", name))
            assertTrue(automaticAssets(name).isNotBlank())
        }
        for (name in listOf("gui/tools.execution.json", "gui/tools.observation.json", "gui/control.returned.txt", "planner/first.system.txt", "planner/second.system.txt", "gui-observation.template.txt")) {
            assertEquals("prompts/v1.1.0/$name", PromptBundles.assetPath("1.2.0", name))
            assertEquals(baselineAssets(name), automaticAssets(name))
        }
    }

    private fun expectInvalidState(block: () -> Unit) {
        try { block(); fail("Expected explicit automatic-start preflight failure") }
        catch (failure: RunFailure) { assertEquals("COORDINATOR", failure.category); assertEquals("INVALID_STATE", failure.code) }
    }

    private class RecordingTransport : ModelTransport {
        var calls = 0
        var key: String? = null
        var sentBody: String? = null
        var retainedBody: ByteArray? = null
        override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
            calls++
            this.key = key
            sentBody = body.toString(Charsets.UTF_8)
            retainedBody = body
            return """{"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"synthetic_launch","type":"function","function":{"name":"launch_app","arguments":"{\"package_name\":\"org.synthetic.calculator\"}"}}]}}]}""".toByteArray(Charsets.UTF_8)
        }
        override fun cancelAll() = Unit
    }
}
