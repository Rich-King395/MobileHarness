package org.mobileharness.app.core

import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

enum class RunState { IDLE, AUTO, PAUSING, MANUAL, PREPARING, CHECKING, STOPPING, UNCERTAIN, BLOCKED, ENDED }
enum class Phase { ORIGINAL, VERIFY, CONFIRM, RECOVERY }
enum class StartMode { SELECTED_APP, HOME_AUTO }

data class ModelProfile(
    val baseUrl: String = "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
    val model: String = "qwen3.8-max",
    val apiKey: String = "",
    val id: String = UUID.randomUUID().toString(),
)

data class SessionOptions(
    val task: String,
    val initialPackage: String,
    val authorizedPackages: Set<String>,
    val harness: Boolean,
    val budget: Int,
    val gui: ModelProfile,
    val planner: ModelProfile,
    val startMode: StartMode = StartMode.SELECTED_APP,
    val homePackage: String = "",
    val authorizedAppLabels: Map<String, String> = emptyMap(),
    // Historical acceptance fixtures can explicitly retain policy 1.1. The App uses 1.2.
    val automaticRecovery: Boolean = true,
) {
    val promptBundleVersion: String get() = if (startMode == StartMode.HOME_AUTO) "1.2.0" else "1.1.0"
    // Adapter acceptance can evolve independently of the frozen request/prompt bundle.
    val adapterVersion: String get() = if (startMode == StartMode.HOME_AUTO) "1.4.0" else "1.1.0"
    val executionPolicyVersion: String get() = if (automaticRecovery) "1.2.0" else "1.1.0"
}

/** Pixels are process-memory only. Never log, persist, or add to text history. */
data class ScreenFrame(val id: String, val png: ByteArray, val width: Int, val height: Int)
data class PhoneAction(val name: String, val arguments: Map<String, Any?>)
data class GuiReply(val callId: String, val action: PhoneAction, val argumentsJson: String)
data class ActionResult(val status: String, val message: String = "", val failureReason: ExecutionFailureReason? = null)
data class Turn(val reply: GuiReply, val result: ActionResult)

data class GuiContext(
    val options: SessionOptions,
    val phase: Phase,
    val objective: String = "",
    val conditions: List<String> = emptyList(),
    val history: List<Turn> = emptyList(),
    val frame: ScreenFrame,
    val returnNoticeIndex: Int? = null,
)

data class PlannerContext(
    val options: SessionOptions,
    val callIndex: Int,
    val completedActions: List<Turn>,
    val frames: List<ScreenFrame>,
    val currentFrameId: String,
    val preHandoffFrameId: String?,
    val checkObjective: String = "",
    val checkConditions: List<String> = emptyList(),
    val checkReport: String = "",
    val verificationFailed: Boolean = false,
)

data class PlannerDecision(val status: String, val decision: String, val objective: String, val conditions: List<String>)

data class RunSnapshot(
    val state: RunState = RunState.IDLE,
    val phase: Phase = Phase.ORIGINAL,
    val guiUsed: Int = 0,
    val plannerUsed: Int = 0,
    val budget: Int = 30,
    val handoffUsed: Boolean = false,
    val message: String = "准备就绪",
    val sessionId: String = "",
)

interface ModelGateway {
    fun preflightGui(context: GuiContext) {}
    fun preflightPlanner(context: PlannerContext) {}
    suspend fun gui(context: GuiContext): GuiReply
    suspend fun planner(context: PlannerContext): PlannerDecision
    fun cancelAll()
}

interface PhonePort {
    val connected: Boolean
    val inputInFlight: Boolean
    /** Replaces the immutable task authorization scope and invalidates capture when cleared. */
    fun configureSession(options: SessionOptions?) {}
    suspend fun observe(): ScreenFrame
    suspend fun perform(action: PhoneAction, frame: ScreenFrame, allowedPackages: Set<String>, canDispatch: () -> Boolean): ActionResult
    fun setCaptureEnabled(enabled: Boolean)
    fun showState(snapshot: RunSnapshot)
}

interface AuditSink {
    fun begin(sessionId: String, options: SessionOptions)
    fun event(event: String, data: Map<String, Any?>)
}

interface RuntimeControls {
    val snapshot: StateFlow<RunSnapshot>
    fun start(options: SessionOptions)
    fun takeover()
    fun giveBack()
    fun stop()
    fun retry()
    fun recheck()
    fun environmentUnavailable(code: String) { stop() }
}

/** Only controlled codes reach UI/logs. Provider bodies and exception messages stay private. */
class RunFailure(val category: String, val code: String, val httpStatus: Int? = null, val transportReason: String? = null,
    val protocolReason: String? = null, val executionReason: ExecutionFailureReason? = null) : Exception(code)
