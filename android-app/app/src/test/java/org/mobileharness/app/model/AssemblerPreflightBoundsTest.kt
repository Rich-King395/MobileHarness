package org.mobileharness.app.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mobileharness.app.core.*
import java.io.File
import java.util.Base64

/** BP-03 A: actual assembly/preflight, synthetic RAM data, a counting transport, no HTTP. */
class AssemblerPreflightBoundsTest {
    @Test fun invalidPngHeaderOrGeometryStopsGuiAndPlannerBeforeTransport() = runTest {
        val invalidFrames = listOf(
            frame(png = byteArrayOf()),
            frame(png = validPng.copyOf(32)),
            frame(png = validPng.copyOf().apply { this[0] = 0 }),
            frame(png = validPng.copyOf().apply { this[11] = 12 }), // IHDR length must be 13.
            frame(png = validPng.copyOf().apply { this[12] = 0 }), // Required IHDR chunk type.
            frame(width = 0),
            frame(height = 0),
            frame(width = 2),
            frame(height = 2),
        )
        for (invalid in invalidFrames) {
            val transport = CountingTransport()
            val gateway = gateway(transport)
            expectNoExchange(transport) { gateway.preflightGui(gui(invalid)) }
            expectNoExchange(transport) { gateway.gui(gui(invalid)) }
            expectNoExchange(transport) { gateway.preflightPlanner(planner(invalid)) }
            expectNoExchange(transport) { gateway.planner(planner(invalid)) }
            gateway.cancelAll()
        }
    }

    @Test fun rawPngAtTwentyMegabytesIsRejectedBeforeEitherRoleCanExchange() = runTest {
        val oversized = paddedPng(20_000_000)
        try {
            val transport = CountingTransport()
            val gateway = gateway(transport)
            expectNoExchange(transport) { gateway.gui(gui(frame(png = oversized))) }
            expectNoExchange(transport) { gateway.planner(planner(frame(png = oversized))) }
            gateway.cancelAll()
        } finally { oversized.fill(0) }
    }

    @Test fun firstPngSizeWhoseBase64PartCrossesTwentyMegabytesIsRejectedWithoutEncodingOrExchange() = runTest {
        // The prefix has 22 bytes and Base64 grows in 4-byte units: this first rejected
        // length predicts 20,000,002 bytes, while the raw PNG is still below 20 MB.
        val firstRejected = 14_999_983
        assertEquals(20_000_002L, IMAGE_PREFIX.length + 4L * ((firstRejected + 2L) / 3L))
        val oversizedPart = paddedPng(firstRejected)
        try {
            val transport = CountingTransport()
            val gateway = gateway(transport)
            expectNoExchange(transport) { gateway.preflightGui(gui(frame(png = oversizedPart))) }
            expectNoExchange(transport) { gateway.gui(gui(frame(png = oversizedPart))) }
            expectNoExchange(transport) { gateway.preflightPlanner(planner(frame(png = oversizedPart))) }
            expectNoExchange(transport) { gateway.planner(planner(frame(png = oversizedPart))) }
            gateway.cancelAll()
        } finally { oversizedPart.fill(0) }
    }

    @Test fun largestPngSizeBelowTheBase64PartLimitCanReachTheFakeTransportExactlyOnce() = runTest {
        // This is a memory-only boundary control, never a phone image or real request.
        assumeTrue("BOUNDARY_CONTROL_NEEDS_512_MIB_TEST_HEAP", Runtime.getRuntime().maxMemory() >= MIN_HEAP)
        val lastAccepted = 14_999_982
        assertEquals(19_999_998L, IMAGE_PREFIX.length + 4L * ((lastAccepted + 2L) / 3L))
        val withinLimit = paddedPng(lastAccepted)
        try {
            val transport = CountingTransport()
            val gateway = gateway(transport)
            val reply = gateway.gui(gui(frame(png = withinLimit)))
            assertEquals("finish", reply.action.name)
            assertEquals(1, transport.exchanges)
            assertTrue("ENCODED_BODY_ABOVE_SINGLE_PART", transport.lastBodyLength > 20_000_000)
            assertTrue("ENCODED_BODY_BELOW_TOTAL_LIMIT", transport.lastBodyLength < 64_000_000)
            gateway.cancelAll()
        } finally { withinLimit.fill(0) }
    }

    @Test fun actualSerializedBodyAtOrAboveSixtyFourMegabytesIsRejectedBeforeTransport() = runTest {
        // About 10.7 MB of compact NUL text expands to >64 MB through the real JSON
        // encoder's six-byte \u0000 escaping. This avoids a 64 MB source fixture or any
        // profile/production threshold override. The test is explicitly skipped on a
        // small test heap rather than risking an OOM and calling that a passing check.
        assumeTrue("BODY_LIMIT_NEEDS_512_MIB_TEST_HEAP", Runtime.getRuntime().maxMemory() >= MIN_HEAP)
        val smallControlTransport = CountingTransport()
        val smallControl = gateway(smallControlTransport)
        val controlRequest = gui(frame()).let { it.copy(options = it.options.copy(task = "BP03_SMALL_NUL_\u0000")) }
        assertEquals("finish", smallControl.gui(controlRequest).action.name)
        assertEquals(1, smallControlTransport.exchanges)
        smallControl.cancelAll()
        val syntheticText = "BP03_BODY_LIMIT_" + "\u0000".repeat(10_666_667)
        assertTrue("SYNTHETIC_INPUT_BELOW_TWELVE_MB", syntheticText.length < 12_000_000)
        val transport = CountingTransport()
        val gateway = gateway(transport)
        val request = gui(frame()).let { it.copy(options = it.options.copy(task = syntheticText)) }
        expectNoExchange(transport) { gateway.gui(request) }
        gateway.cancelAll()
    }

    private suspend fun expectNoExchange(transport: CountingTransport, block: suspend () -> Unit) {
        try {
            block()
            fail("EXPECTED_OBSERVATION_PREFLIGHT_REJECTION")
        } catch (failure: RunFailure) {
            assertEquals("COORDINATOR", failure.category)
            assertEquals("OBSERVATION_UNAVAILABLE", failure.code)
            assertEquals("OBSERVATION_UNAVAILABLE", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(0, transport.exchanges)
    }

    private class CountingTransport : ModelTransport {
        var exchanges = 0
        var lastBodyLength = 0
        override suspend fun exchange(endpoint: String, key: String, body: ByteArray): ByteArray {
            exchanges++
            lastBodyLength = body.size // Do not copy, stringify, retain or print the payload/key.
            return RESPONSE.toByteArray(Charsets.UTF_8)
        }
        override fun cancelAll() = Unit
    }

    private companion object {
        const val MIN_HEAP = 512L * 1024 * 1024
        const val IMAGE_PREFIX = "data:image/png;base64,"
        const val RESPONSE = "{\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"bp03-synthetic-call\",\"type\":\"function\",\"function\":{\"name\":\"finish\",\"arguments\":\"{\\\"outcome\\\":\\\"unable\\\",\\\"message\\\":\\\"BP03_SYNTHETIC_DONE\\\"}\"}}]}}]}"
        val project = generateSequence(File(".").absoluteFile) { it.parentFile }.first { File(it, "prompts/v1.1.0").isDirectory }
        val assets: (String) -> String = { name -> File(project, "prompts/v1.1.0/$name").readText(Charsets.UTF_8) }
        val automaticAssets: (String) -> String = { name -> File(project, PromptBundles.assetPath("1.2.0", name)).readText(Charsets.UTF_8) }
        fun gateway(transport: CountingTransport) = QwenGateway(assets, transport, automaticAssetReader = automaticAssets)
        val validPng = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j3ioAAAAASUVORK5CYII=")
        fun paddedPng(size: Int) = ByteArray(size).also { validPng.copyInto(it) }
        fun frame(png: ByteArray = validPng.copyOf(), width: Int = 1, height: Int = 1) = ScreenFrame("BP03_CURRENT", png, width, height)
        fun options() = SessionOptions("BP03_SYNTHETIC_TASK", "org.synthetic.home", setOf("org.synthetic.fixture"), true, 30,
            ModelProfile(baseUrl = "https://gui.example/v1", model = "synthetic-gui", apiKey = "bp03-synthetic-key"),
            ModelProfile(baseUrl = "https://planner.example/v1", model = "synthetic-planner", apiKey = "bp03-synthetic-key"),
            startMode = StartMode.HOME_AUTO, homePackage = "org.synthetic.home", authorizedAppLabels = mapOf("org.synthetic.fixture" to "Synthetic fixture"))
        fun gui(frame: ScreenFrame) = GuiContext(options(), Phase.ORIGINAL, frame = frame)
        fun planner(frame: ScreenFrame) = PlannerContext(options(), 1, emptyList(), listOf(frame), frame.id, null)
    }
}

