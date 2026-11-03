package org.mobileharness.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mobileharness.app.core.*
import java.io.File
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class QwenGatewayTest {
    private val promptRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "prompts/v1.1.0") }.first { it.isDirectory }
    private val assets: (String) -> String = { File(promptRoot, it).readText(Charsets.UTF_8) }
    private val assembler = ModelRequestAssembler(assets)
    private val protocol = QwenProtocol(assembler.executionTools, assembler.observationTools)
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")

    private fun options(harness: Boolean = true) = SessionOptions(
        task = "Preserve this task literally {{ORIGINAL_TASK}}", initialPackage = "org.example.notes",
        authorizedPackages = setOf("org.example.notes"), harness = harness, budget = 30,
        gui = ModelProfile(baseUrl = "https://gui.example/v1", model = "gui-model", apiKey = "synthetic-gui-key"),
        planner = ModelProfile(baseUrl = "https://planner.example/v1/chat/completions", model = "planner-model", apiKey = "synthetic-planner-key"))

    private fun frame(id: String = "current") = ScreenFrame(id, png.copyOf(), 1, 1)
    private fun guiContext(phase: Phase = Phase.ORIGINAL, history: List<Turn> = emptyList(), notice: Int? = null) =
        GuiContext(options(), phase, objective = if (phase == Phase.ORIGINAL) "" else "Inspect current evidence",
            conditions = if (phase == Phase.ORIGINAL) emptyList() else listOf("Required visible value"),
            history = history, frame = frame(), returnNoticeIndex = notice)

    private fun turn(id: String, marker: String, status: String = "completed") = Turn(
        GuiReply(id, PhoneAction("input_text", mapOf("text" to marker)),
            JsonObject().apply { addProperty("text", marker) }.toString()), ActionResult(status, "result-$marker"))

    private fun guiResponse(name: String = "tap", args: String = "{\"x\":500,\"y\":500}", reason: String = "tool_calls", content: String? = null): String {
        val call = JsonObject().apply {
            addProperty("id", "call_synthetic"); addProperty("type", "function")
            add("function", JsonObject().apply { addProperty("name", name); addProperty("arguments", args) })
        }
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            add("content", if (content == null) JsonNull.INSTANCE else com.google.gson.JsonPrimitive(content))
            add("tool_calls", JsonArray().apply { add(call) })
        }
        return JsonObject().apply { add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("index", 0); addProperty("finish_reason", reason); add("message", message)
        }) }) }.toString()
    }

    private fun plannerResponse(output: String, reason: String = "stop") = JsonObject().apply {
        add("choices", JsonArray().apply { add(JsonObject().apply {
            addProperty("index", 0); addProperty("finish_reason", reason)
            add("message", JsonObject().apply { addProperty("role", "assistant"); addProperty("content", output) })
        }) })
    }.toString()

    private fun expectFailure(category: String, code: String, block: () -> Unit) {
        try { block(); fail("Expected controlled failure") } catch (failure: RunFailure) {
            assertEquals(category, failure.category); assertEquals(code, failure.code)
            assertEquals(code, failure.message)
        }
    }

    @Test fun guiRequestHasOnlyCurrentImageAndNativeHistoryPairs() {
        val history = listOf(turn("old_1", "BEFORE_RETURN"), turn("old_2", "AFTER_RETURN"))
        val context = guiContext(history = history, notice = 1)
        val request = StrictJson.parse(assembler.gui(context).toString(Charsets.UTF_8)).asJsonObject
        assertEquals("gui-model", request["model"].asString)
        assertFalse(request.has("extra_body"))
        assertFalse(request["enable_thinking"].asBoolean)
        assertFalse(request["preserve_thinking"].asBoolean)
        assertFalse(request["stream"].asBoolean)
        assertFalse(request["parallel_tool_calls"].asBoolean)
        val messages = request["messages"].asJsonArray
        assertEquals(8, messages.size())
        assertEquals("assistant", messages[2].asJsonObject["role"].asString)
        assertTrue(messages[2].asJsonObject["content"].isJsonNull)
        assertEquals("old_1", messages[3].asJsonObject["tool_call_id"].asString)
        assertEquals(assets("gui/control.returned.txt"), messages[4].asJsonObject["content"].asString)
        assertEquals("old_2", messages[6].asJsonObject["tool_call_id"].asString)
        assertTrue(messages[1].asJsonObject["content"].asString.contains(options().task))
        val last = messages.last().asJsonObject["content"].asJsonArray
        assertEquals(2, last.size())
        assertEquals("image_url", last[1].asJsonObject["type"].asString)
        assertEquals(2_621_440, last[1].asJsonObject["max_pixels"].asInt)
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), last[1].asJsonObject["image_url"].asJsonObject["url"].asString)
        assertEquals(1, Regex("data:image/png;base64,").findAll(request.toString()).count())
        assertFalse(request.toString().contains("synthetic-gui-key"))
    }

    @Test fun newPhaseHasOnlyItsOwnHistoryAndObservationTools() {
        for (phase in listOf(Phase.VERIFY, Phase.CONFIRM, Phase.RECOVERY)) {
            val request = StrictJson.parse(assembler.gui(guiContext(phase)).toString(Charsets.UTF_8)).asJsonObject
            assertEquals(3, request["messages"].asJsonArray.size())
            val names = request["tools"].asJsonArray.map { it.asJsonObject["function"].asJsonObject["name"].asString }.toSet()
            if (phase != Phase.RECOVERY) assertEquals(setOf("wait", "finish"), names) else assertEquals(8, names.size)
            assertFalse(request.toString().contains("BEFORE_RETURN"))
            assertFalse(request.toString().contains("result-BEFORE_RETURN"))
        }
        expectFailure("COORDINATOR", "INVALID_STATE") {
            assembler.gui(guiContext(Phase.VERIFY, history = listOf(turn("old", "OLD_INPUT_HISTORY"))))
        }
    }

    @Test fun coordinatorNewPhaseReturnNoticeZeroIsAllowedAndNeutral() {
        for (phase in listOf(Phase.VERIFY, Phase.CONFIRM, Phase.RECOVERY)) {
            val request = StrictJson.parse(assembler.gui(guiContext(phase, notice = 0)).toString(Charsets.UTF_8)).asJsonObject
            val messages = request["messages"].asJsonArray
            assertEquals(4, messages.size())
            assertEquals(assets("gui/control.returned.txt"), messages[2].asJsonObject["content"].asString)
            assertEquals(1, Regex("data:image/png;base64,").findAll(request.toString()).count())
            assertFalse(request.toString().contains("BEFORE_RETURN"))
        }
    }

    @Test fun harnessOffOriginalInformationEqualsOriginalFallbackInput() {
        val original = guiContext(history = listOf(turn("own", "OWN_HISTORY")), notice = 1)
        val off = original.copy(options = original.options.copy(harness = false))
        assertEquals(StrictJson.parse(assembler.gui(original).toString(Charsets.UTF_8)),
            StrictJson.parse(assembler.gui(off).toString(Charsets.UTF_8)))
    }

    @Test fun plannerUsesOnlyCompletedActionsAndIndependentMinimalImages() {
        val context = PlannerContext(options(), 2,
            listOf(turn("ok", "SUCCESS_MARKER"), turn("fail", "FAILURE_MARKER", "device_error"), turn("unknown", "UNKNOWN_MARKER", "unknown")),
            listOf(frame("pre"), frame("latest")), "latest", "pre",
            checkObjective = "check marker", checkConditions = listOf("evidence marker"), checkReport = "unverified marker", verificationFailed = true)
        val request = StrictJson.parse(assembler.planner(context).toString(Charsets.UTF_8)).asJsonObject
        assertEquals("planner-model", request["model"].asString)
        assertEquals("json_object", request["response_format"].asJsonObject["type"].asString)
        assertFalse(request.has("tools"))
        val messages = request["messages"].asJsonArray
        assertEquals(2, messages.size())
        assertEquals(assets("planner/second.system.txt"), messages[0].asJsonObject["content"].asString)
        val content = messages[1].asJsonObject["content"].asJsonArray
        val input = StrictJson.parse(content[0].asJsonObject["text"].asString).asJsonObject
        assertEquals(1, input["own_action_history"].asJsonArray.size())
        assertEquals("latest", input["current_frame_id"].asString)
        assertTrue(input["verification_failed"].asBoolean)
        assertEquals(2, input["images"].asJsonArray.size())
        assertTrue(request.toString().contains("SUCCESS_MARKER"))
        assertFalse(request.toString().contains("FAILURE_MARKER"))
        assertFalse(request.toString().contains("UNKNOWN_MARKER"))
        assertEquals(2, Regex("data:image/png;base64,").findAll(request.toString()).count())
        val first = context.copy(callIndex = 1, completedActions = emptyList(), frames = listOf(frame()), currentFrameId = "current", preHandoffFrameId = null)
        val firstRequest = StrictJson.parse(assembler.planner(first).toString(Charsets.UTF_8)).asJsonObject
        val firstInput = StrictJson.parse(firstRequest["messages"].asJsonArray[1].asJsonObject["content"].asJsonArray[0].asJsonObject["text"].asString).asJsonObject
        assertTrue(firstInput["pre_handoff_frame_id"].isJsonNull)
        assertTrue(firstInput["verification_update"].isJsonNull)
        assertFalse(firstRequest.toString().contains("unverified marker"))
    }

    @Test fun preflightRejectsInvalidImagesAndDuplicateBoundaryIds() {
        expectFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE") { assembler.gui(guiContext().copy(frame = frame().copy(width = 2))) }
        expectFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE") { assembler.gui(guiContext().copy(frame = frame().copy(png = byteArrayOf(1, 2)))) }
        expectFailure("COORDINATOR", "INVALID_STATE") {
            assembler.planner(PlannerContext(options(), 1, emptyList(), listOf(frame(), frame()), "current", null))
        }
    }

    @Test fun strictGuiRejectsDuplicateKeysTruncationProseAndInvalidTools() {
        val invalid = listOf(
            guiResponse(args = "{\"x\":1,\"x\":999,\"y\":2}"),
            guiResponse(args = "{\"x\":1.5,\"y\":2}"),
            guiResponse(args = "{\"x\":\"1\",\"y\":2}"),
            guiResponse(args = "{\"x\":1000,\"y\":2}"),
            guiResponse(args = "{\"x\":1,\"y\":2,\"hidden\":1}"),
            guiResponse(args = "{\"x\":NaN,\"y\":2}"),
            guiResponse(reason = "length"), guiResponse(content = "done"),
            guiResponse(name = "adb_shell"), guiResponse().dropLast(3),
            guiResponse().replace("\"choices\":", "\"choices\":[],\"choices\":"),
        )
        invalid.forEach { response -> expectFailure("MODEL", "INVALID_OUTPUT") { protocol.gui(response, Phase.ORIGINAL) } }
        assertEquals(999, protocol.gui(guiResponse(args = "{\"x\":999,\"y\":0}"), Phase.ORIGINAL).action.arguments["x"])
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.gui(guiResponse(), Phase.VERIFY) }
        assertEquals("wait", protocol.gui(guiResponse("wait", "{\"duration_ms\":100}"), Phase.VERIFY).action.name)
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.gui(guiResponse("wait", "{\"duration_ms\":99}"), Phase.CONFIRM) }
    }

    @Test fun multipleNativeToolCallsExecuteNeither() {
        val parsed = StrictJson.parse(guiResponse()).asJsonObject
        val calls = parsed["choices"].asJsonArray[0].asJsonObject["message"].asJsonObject["tool_calls"].asJsonArray
        calls.add(calls[0].deepCopy())
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.gui(parsed.toString(), Phase.ORIGINAL) }
    }

    @Test fun allFrozenPlannerAndGuiFixturesMatchProtocol() {
        val planner = StrictJson.parse(assets("examples/planner.outputs.json")).asJsonObject["cases"].asJsonArray
        planner.forEach { raw ->
            val case = raw.asJsonObject
            val response = plannerResponse(case["output"].toString())
            if (case["valid"].asBoolean) protocol.planner(response, case["call"].asInt)
            else expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(response, case["call"].asInt) }
        }
        val gui = StrictJson.parse(assets("examples/gui.outputs.json")).asJsonObject["cases"].asJsonArray
        gui.forEach { raw ->
            val case = raw.asJsonObject
            val response = JsonObject().apply { add("choices", JsonArray().apply { add(JsonObject().apply {
                add("finish_reason", case["finish_reason"]); add("message", case["message"])
            }) }) }.toString()
            val phase = Phase.valueOf(case["phase"].asString)
            if (case["valid"].asBoolean) protocol.gui(response, phase)
            else expectFailure("MODEL", "INVALID_OUTPUT") { protocol.gui(response, phase) }
        }
    }

    @Test fun secondPlannerFinishIsStrictAndDoesNotRequestGuiDone() {
        val finish = "{\"completion_status\":\"COMPLETE\",\"decision\":\"FINISH\",\"objective\":\"\",\"completion_conditions\":[]}"
        assertEquals(PlannerDecision("COMPLETE", "FINISH", "", emptyList()), protocol.planner(plannerResponse(finish), 2))
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(plannerResponse(finish), 1) }
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(plannerResponse(finish.replace("\"objective\":\"\"", "\"objective\":\"more work\"")), 2) }
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(plannerResponse(finish.replace("\"COMPLETE\"", "\"COMPLETE\",\"completion_status\":\"COMPLETE\"")), 2) }
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(plannerResponse(finish.replace("FINISH", "VERIFY")), 2) }
        expectFailure("MODEL", "INVALID_OUTPUT") { protocol.planner(plannerResponse(finish, "length"), 2) }
    }

    @Test fun endpointRequiresHttpsWithoutCredentialsQueriesOrFragments() {
        assertEquals("https://api.example/v1/chat/completions", QwenGateway.endpoint("https://api.example/v1/"))
        assertEquals("https://api.example/v1/chat/completions", QwenGateway.endpoint("https://api.example/v1/chat/completions/"))
        listOf("http://api.example/v1", "https://user:pass@api.example/v1", "https://@api.example/v1", "https://api.example/v1?key=x", "https://api.example/v1#x").forEach {
            expectFailure("COORDINATOR", "INVALID_STATE") { QwenGateway.endpoint(it) }
        }
    }

    @Test fun preflightCachesResolvedKeyAndBodyWithoutMutatingProfile() = runTest {
        val transport = RecordingTransport(guiResponse())
        var resolutions = 0
        val gateway = QwenGateway(assets, transport) { resolutions++; "synthetic-resolved-key" }
        val context = guiContext().let { it.copy(options = it.options.copy(gui = it.options.gui.copy(apiKey = ""))) }
        gateway.preflightGui(context)
        assertEquals(0, transport.calls)
        assertEquals(1, resolutions)
        assertEquals("tap", gateway.gui(context).action.name)
        assertEquals(1, transport.calls)
        assertEquals(1, resolutions)
        assertEquals("synthetic-resolved-key", transport.lastKey)
        assertEquals("", context.options.gui.apiKey)
        assertFalse(transport.lastBody!!.contains("synthetic-resolved-key"))
        assertTrue(transport.retainedBody!!.all { it == 0.toByte() })
        val bad = context.copy(frame = frame().copy(width = 5))
        expectFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE") { gateway.preflightGui(bad) }
        assertEquals(1, transport.calls)
    }

    @Test fun cancelAllCancelsInFlightWorkAndClearsPreparedBytes() = runTest {
        val transport = RecordingTransport(guiResponse(), CompletableDeferred())
        val gateway = QwenGateway(assets, transport)
        val context = guiContext()
        gateway.preflightGui(context)
        val pending = launch { gateway.gui(context) }
        runCurrent()
        assertEquals(1, transport.calls)
        gateway.cancelAll()
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(1, transport.cancelCalls)
        assertTrue(transport.retainedBody!!.all { it == 0.toByte() })
    }

    @Test fun productionHttpClientHasNoRetryRedirectCacheOrLoggingInterceptors() {
        val client = OkHttpModelTransport().client
        assertFalse(client.retryOnConnectionFailure)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(120_000, client.callTimeoutMillis)
    }

    @Test fun requestBodyCannotBeAutomaticallyReplayed() {
        val body = OneShotJsonBody("{}".toByteArray())
        assertTrue(body.isOneShot())
        assertEquals(2L, body.contentLength())
        val sink = okio.Buffer()
        body.writeTo(sink)
        assertEquals("{}", sink.readUtf8())
        try { body.writeTo(okio.Buffer()); fail("Second write must be rejected") }
        catch (_: java.io.IOException) { /* no second body leaves the client */ }
    }

    @Test fun rejectedReplyCarriesFixedReasonAndZeroesBuffersWithoutRetry() = runTest {
        val returned = guiResponse(reason = "stop", content = "synthetic private reply").toByteArray()
        var retainedRequest: ByteArray? = null
        var exchanges = 0
        val transport = object : ModelTransport {
            override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
                exchanges++; retainedRequest = body; return returned
            }
            override fun cancelAll() = Unit
        }
        val gateway = QwenGateway(assets, transport)
        var observed: ProtocolDiagnostic? = null
        gateway.protocolDiagnosticObserver = { observed = it; throw IllegalStateException("synthetic observer failure") }
        try { gateway.gui(guiContext()); fail("Invalid output must never become an action") }
        catch (failure: RunFailure) {
            assertEquals("INVALID_OUTPUT", failure.code)
            assertEquals("GUI_STOP_WITHOUT_TOOL", failure.protocolReason)
            assertEquals("INVALID_OUTPUT", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(1, exchanges)
        assertEquals(ProtocolFinishReason.STOP, observed!!.finishReason)
        assertFalse(observed.toString().contains("synthetic private reply"))
        assertTrue(returned.all { it == 0.toByte() })
        assertTrue(retainedRequest!!.all { it == 0.toByte() })
    }

    @Test fun invalidUtf8IsFixedProtocolFailureAndNeverRetried() = runTest {
        val returned = byteArrayOf(0xc3.toByte(), 0x28)
        var exchanges = 0
        val gateway = QwenGateway(assets, object : ModelTransport {
            override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
                exchanges++; return returned
            }
            override fun cancelAll() = Unit
        })
        try { gateway.gui(guiContext()); fail("Invalid UTF-8 must be rejected") }
        catch (failure: RunFailure) { assertEquals("RESPONSE_UTF8_INVALID", failure.protocolReason) }
        assertEquals(1, exchanges)
        assertTrue(returned.all { it == 0.toByte() })
    }

    @Test fun automaticAdapterCompatibilityIsVersionedAndLegacyGatewayStaysStrict() = runTest {
        val transport = RecordingTransport(guiResponse(content = "synthetic auxiliary text"))
        val project = promptRoot.parentFile.parentFile
        val gateway = QwenGateway(assets, transport, automaticAssetReader = { name ->
            File(project, PromptBundles.assetPath("1.2.0", name)).readText(Charsets.UTF_8)
        })
        val original = guiContext()
        val auto = original.copy(options = original.options.copy(initialPackage = "org.synthetic.launcher",
            startMode = StartMode.HOME_AUTO, homePackage = "org.synthetic.launcher"))
        assertEquals("1.4.0", auto.options.adapterVersion)
        assertEquals("1.2.0", auto.options.executionPolicyVersion)
        assertEquals("1.1.0", auto.options.copy(automaticRecovery = false).executionPolicyVersion)
        assertEquals("1.1.0", original.options.adapterVersion)
        assertEquals(auto.options.executionPolicyVersion, original.options.executionPolicyVersion)
        assertEquals("1.2.0", auto.options.promptBundleVersion)
        val reply = gateway.gui(auto)
        assertEquals("tap", reply.action.name)
        assertEquals(mapOf("x" to 500, "y" to 500), reply.action.arguments)
        assertEquals("{\"x\":500,\"y\":500}", reply.argumentsJson)
        assertEquals(1, transport.calls)
        try { gateway.gui(original); fail("Legacy envelope must remain strict") }
        catch (failure: RunFailure) { assertEquals("GUI_CONTENT", failure.protocolReason) }
        assertEquals(2, transport.calls)
    }

    private class RecordingTransport(private val response: String, private val gate: CompletableDeferred<Unit>? = null) : ModelTransport {
        var calls = 0; var cancelCalls = 0
        var lastBody: String? = null; var lastKey: String? = null; var retainedBody: ByteArray? = null
        override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
            calls++; lastKey = key; lastBody = body.toString(Charsets.UTF_8); retainedBody = body
            gate?.await()
            return response.toByteArray(Charsets.UTF_8)
        }
        override fun cancelAll() { cancelCalls++ }
    }
}
