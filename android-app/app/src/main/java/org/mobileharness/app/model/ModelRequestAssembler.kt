package org.mobileharness.app.model

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.mobileharness.app.core.GuiContext
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.PlannerContext
import org.mobileharness.app.core.RunFailure
import org.mobileharness.app.core.ScreenFrame
import org.mobileharness.app.core.StartMode
import java.nio.ByteBuffer
import java.util.Base64

internal class ModelRequestAssembler(private val asset: (String) -> String) {
    private val gson = GsonBuilder().serializeNulls().create()
    private val profile = read("request-profiles.json").objectValue()
    val executionTools: JsonArray = read("gui/tools.execution.json").asJsonArray
    val observationTools: JsonArray = read("gui/tools.observation.json").asJsonArray
    private val placeholder = Regex("\\{\\{([A-Z_]+)\\}\\}")

    fun gui(context: GuiContext): ByteArray = preflight {
        val options = context.options
        require(options.task.isNotBlank() && options.gui.model.isNotBlank())
        if (options.startMode == StartMode.HOME_AUTO) {
            require(options.homePackage.isNotBlank() && options.initialPackage == options.homePackage)
            require(options.authorizedPackages.isNotEmpty())
            require(options.authorizedAppLabels.keys.all { it in options.authorizedPackages })
        }
        val notice = context.returnNoticeIndex
        require(notice == null || notice in 0..context.history.size)
        require(context.phase == Phase.ORIGINAL || notice == null || notice == 0)
        val taskFile = when (context.phase) {
            Phase.ORIGINAL -> "gui/original.task.txt"
            Phase.RECOVERY -> "gui/recovery.task.txt"
            Phase.VERIFY -> "gui/verify.task.txt"
            Phase.CONFIRM -> "gui/confirm.task.txt"
        }
        if (context.phase != Phase.ORIGINAL) require(context.objective.isNotBlank())
        val conditions = context.conditions.joinToString("\n") { "- $it" }
        val slots = mapOf(
            "ORIGINAL_TASK" to options.task,
            "INITIAL_APP_PACKAGE" to options.initialPackage,
            "AUTHORIZED_APP_PACKAGES_JSON" to gson.toJson(options.authorizedPackages.sorted()),
            "START_MODE" to options.startMode.name,
            "HOME_APP_PACKAGE" to options.homePackage,
            "AUTHORIZED_APP_LABELS_JSON" to gson.toJson(options.authorizedAppLabels.toSortedMap()),
            "REMAINING_OBJECTIVE" to context.objective,
            "OBSERVABLE_COMPLETION_CONDITIONS" to conditions,
            "CHECK_OBJECTIVE" to context.objective,
            "REQUIRED_VISIBLE_EVIDENCE" to conditions,
            "FINAL_CONFIRMATION_OBJECTIVE" to context.objective,
            "VISIBLE_FINAL_STATE" to conditions,
        )
        val messages = JsonArray()
        messages.add(message("system", text("gui/system.txt")))
        messages.add(message("user", render(text(taskFile), slots)))
        context.history.forEachIndexed { index, turn ->
            if (notice == index) messages.add(message("user", text("gui/control.returned.txt")))
            val arguments = StrictJson.parse(turn.reply.argumentsJson).objectValue()
            val function = obj("name" to turn.reply.action.name, "arguments" to turn.reply.argumentsJson)
            val historyTools = if (context.phase == Phase.VERIFY || context.phase == Phase.CONFIRM) observationTools else executionTools
            val matching = historyTools.singleOrNull { it.objectValue().required("function").objectValue().required("name").stringValue() == turn.reply.action.name }
                ?: error("History tool")
            ToolSchema.validate(arguments, matching.objectValue().required("function").objectValue().required("parameters").objectValue())
            val calls = JsonArray().apply { add(obj("id" to turn.reply.callId, "type" to "function", "function" to function)) }
            messages.add(obj("role" to "assistant", "content" to JsonNull.INSTANCE, "tool_calls" to calls))
            messages.add(obj("role" to "tool", "tool_call_id" to turn.reply.callId,
                "content" to gson.toJson(obj("execution_status" to turn.result.status,
                    "message" to (turn.result.failureReason?.modelFeedback ?: turn.result.message)))))
        }
        if (notice == context.history.size) messages.add(message("user", text("gui/control.returned.txt")))
        val frame = context.frame
        val observation = render(text("gui-observation.template.txt"), mapOf(
            "FRAME_ID" to frame.id, "IMAGE_WIDTH" to frame.width.toString(),
            "IMAGE_HEIGHT" to frame.height.toString(), "GUI_PHASE" to context.phase.name))
        val content = JsonArray().apply {
            add(obj("type" to "text", "text" to observation))
            add(image(frame))
        }
        messages.add(obj("role" to "user", "content" to content))
        val body = common(options.gui.model, "gui_body_overrides")
        body.add("messages", messages)
        body.add("tools", (if (context.phase == Phase.VERIFY || context.phase == Phase.CONFIRM) observationTools else executionTools).deepCopy())
        encode(body)
    }

    fun planner(context: PlannerContext): ByteArray = preflight {
        require(context.callIndex in 1..2)
        require(context.options.harness && context.options.task.isNotBlank() && context.options.planner.model.isNotBlank())
        require(context.frames.isNotEmpty() && context.frames.size <= 3)
        require(context.frames.map { it.id }.distinct().size == context.frames.size)
        require(context.frames.count { it.id == context.currentFrameId } == 1)
        require(context.preHandoffFrameId == null || context.frames.any { it.id == context.preHandoffFrameId })
        require(context.preHandoffFrameId != context.currentFrameId)
        require(context.callIndex != 1 || context.frames.size <= 2)
        val input = obj("planner_call_index" to context.callIndex,
            "original_task_verbatim" to context.options.task,
            "current_frame_id" to context.currentFrameId,
            "pre_handoff_frame_id" to context.preHandoffFrameId,
            "verification_failed" to context.verificationFailed)
        val actions = JsonArray()
        context.completedActions.filter { it.result.status == "completed" && it.reply.action.name != "finish" }.forEach { turn ->
            actions.add(obj("action" to turn.reply.action.name,
                "arguments" to StrictJson.parse(turn.reply.argumentsJson), "execution_status" to "completed"))
        }
        input.add("own_action_history", actions)
        val descriptions = JsonArray()
        context.frames.forEach { frame -> descriptions.add(obj("evidence_id" to frame.id,
            "width" to frame.width, "height" to frame.height,
            "is_latest_observation" to (frame.id == context.currentFrameId),
            "is_pre_handoff" to (frame.id == context.preHandoffFrameId))) }
        input.add("images", descriptions)
        input.add("verification_capabilities", obj("mode" to "current_frame_only",
            "allowed_operations" to JsonArray().apply { add("observe_current_frame"); add("wait_for_refresh") },
            "navigation_allowed" to false))
        input.add("verification_update", if (context.callIndex == 1) JsonNull.INSTANCE else obj(
            "requested_objective" to context.checkObjective,
            "required_evidence" to gson.toJsonTree(context.checkConditions),
            "executor_report_unverified" to context.checkReport))
        val content = JsonArray().apply {
            add(obj("type" to "text", "text" to gson.toJson(input)))
            context.frames.forEach { frame ->
                add(obj("type" to "text", "text" to "Public image evidence: ${frame.id}"))
                add(image(frame))
            }
        }
        val messages = JsonArray().apply {
            add(message("system", text("planner/${if (context.callIndex == 1) "first" else "second"}.system.txt")))
            add(obj("role" to "user", "content" to content))
        }
        val body = common(context.options.planner.model, "planner_body_overrides")
        body.add("messages", messages)
        encode(body)
    }

    private fun common(model: String, overrides: String): JsonObject = profile.required("common_request_body").objectValue().deepCopy().apply {
        addProperty("model", model)
        profile.required(overrides).objectValue().entrySet().forEach { (key, value) -> add(key, value.deepCopy()) }
    }

    private fun image(frame: ScreenFrame): JsonObject {
        if (frame.id.isBlank() || frame.width <= 0 || frame.height <= 0 || frame.png.size < 33 || frame.png.size >= 20_000_000) observationFailure()
        val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (!frame.png.copyOfRange(0, 8).contentEquals(signature)) observationFailure()
        val header = ByteBuffer.wrap(frame.png)
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452 || header.getInt(16) != frame.width || header.getInt(20) != frame.height) observationFailure()
        val prefix = "data:image/png;base64,"
        if (prefix.length + 4L * ((frame.png.size + 2L) / 3L) >= 20_000_000L) observationFailure()
        return obj("type" to "image_url", "image_url" to obj("url" to prefix + Base64.getEncoder().encodeToString(frame.png)),
            "max_pixels" to 2_621_440)
    }

    private fun encode(body: JsonObject): ByteArray {
        val result = gson.toJson(body).toByteArray(Charsets.UTF_8)
        if (result.size >= 64_000_000) { result.fill(0); observationFailure() }
        return result
    }

    private fun render(template: String, values: Map<String, String>): String = placeholder.replace(template) { match ->
        values[match.groupValues[1]] ?: error("Missing template value")
    }
    private fun message(role: String, content: String): JsonObject = obj("role" to role, "content" to content)
    private fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
        fields.forEach { (key, value) -> add(key, if (value is JsonElement) value else gson.toJsonTree(value)) }
    }
    private fun text(name: String): String = asset(name)
    private fun read(name: String): JsonElement = try { StrictJson.parse(text(name)) } catch (_: Exception) { throw RunFailure("COORDINATOR", "INVALID_STATE") }
    private fun observationFailure(): Nothing = throw RunFailure("COORDINATOR", "OBSERVATION_UNAVAILABLE")
    private inline fun <T> preflight(block: () -> T): T = try { block() } catch (failure: RunFailure) { throw failure } catch (_: Exception) { throw RunFailure("COORDINATOR", "INVALID_STATE") }
}
