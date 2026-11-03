package org.mobileharness.app.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.mobileharness.app.core.GuiReply
import org.mobileharness.app.core.Phase
import org.mobileharness.app.core.PhoneAction
import org.mobileharness.app.core.PlannerDecision
import org.mobileharness.app.core.RunFailure
import java.io.StringReader
import java.math.BigDecimal

/** Deliberately does not use Gson's last-key-wins object deserializer. */
internal object StrictJson {
    fun parse(text: String): JsonElement = try {
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            val result = read(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT)
            result
        }
    } catch (failure: StrictJsonViolation) { throw failure
    } catch (_: Exception) {
        // Syntax exceptions can contain excerpts of the input. Do not retain them as causes.
        throw StrictJsonViolation(StrictJsonReason.INVALID)
    }

    private fun read(reader: JsonReader, depth: Int): JsonElement {
        if (depth > 64) throw StrictJsonViolation(StrictJsonReason.DEPTH)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val result = JsonObject()
                val seen = HashSet<String>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!seen.add(name)) throw StrictJsonViolation(StrictJsonReason.DUPLICATE_FIELD)
                    result.add(name, read(reader, depth + 1))
                }
                reader.endObject()
                result
            }
            JsonToken.BEGIN_ARRAY -> {
                val result = JsonArray()
                reader.beginArray()
                while (reader.hasNext()) result.add(read(reader, depth + 1))
                reader.endArray()
                result
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
            else -> throw IllegalArgumentException("JSON token")
        }
    }
}

internal fun JsonElement.objectValue(): JsonObject {
    require(isJsonObject)
    return asJsonObject
}

internal fun JsonElement.stringValue(): String {
    require(isJsonPrimitive && asJsonPrimitive.isString)
    return asString
}

internal fun JsonObject.required(name: String): JsonElement = get(name) ?: error("Missing field")

internal fun JsonObject.exactFields(vararg names: String) {
    require(keySet() == names.toSet())
}

/** Tool schemas in the frozen bundle use this small, explicitly supported subset. */
internal object ToolSchema {
    fun validate(value: JsonElement, schema: JsonObject) {
        when (schema.required("type").stringValue()) {
            "object" -> {
                protocolRequire(value.isJsonObject, ProtocolReason.SCHEMA_WRONG_TYPE)
                val obj = value.objectValue()
                val properties = schema.required("properties").objectValue()
                val required = schema.required("required").asJsonArray.map { it.stringValue() }.toSet()
                protocolRequire(obj.keySet().containsAll(required), ProtocolReason.SCHEMA_MISSING_REQUIRED_FIELD)
                protocolRequire(schema.required("additionalProperties").asBoolean == false, ProtocolReason.SCHEMA_UNSUPPORTED)
                protocolRequire(obj.keySet().all { properties.has(it) }, ProtocolReason.SCHEMA_ADDITIONAL_FIELD)
                obj.entrySet().forEach { (key, element) -> validate(element, properties.required(key).objectValue()) }
            }
            "integer" -> {
                protocolRequire(value.isJsonPrimitive && value.asJsonPrimitive.isNumber, ProtocolReason.SCHEMA_WRONG_TYPE)
                protocolRequire(Regex("-?(0|[1-9][0-9]*)").matches(value.asString), ProtocolReason.SCHEMA_INTEGER_FORMAT)
                val number = value.asBigDecimal
                schema.get("minimum")?.let { protocolRequire(number >= it.asBigDecimal, ProtocolReason.SCHEMA_VALUE_OUT_OF_RANGE) }
                schema.get("maximum")?.let { protocolRequire(number <= it.asBigDecimal, ProtocolReason.SCHEMA_VALUE_OUT_OF_RANGE) }
            }
            "string" -> {
                protocolRequire(value.isJsonPrimitive && value.asJsonPrimitive.isString, ProtocolReason.SCHEMA_WRONG_TYPE)
                val text = value.stringValue()
                schema.get("minLength")?.let { protocolRequire(text.codePointCount(0, text.length) >= it.asInt, ProtocolReason.SCHEMA_STRING_TOO_SHORT) }
                schema.get("enum")?.let { allowed -> protocolRequire(allowed.asJsonArray.any { it.stringValue() == text }, ProtocolReason.SCHEMA_ENUM_VALUE) }
            }
            else -> throw ProtocolViolation(ProtocolReason.SCHEMA_UNSUPPORTED)
        }
    }
}

internal class QwenProtocol(private val executionTools: JsonArray, private val observationTools: JsonArray) {
    fun gui(response: String, phase: Phase, allowAuxiliaryText: Boolean = false): GuiReply =
        invalidOutput { parseGui(response, phase, DiagnosticBuilder(), allowAuxiliaryText) }

    fun planner(response: String, callIndex: Int): PlannerDecision = invalidOutput { parsePlanner(response, callIndex, DiagnosticBuilder()) }

    /** Uses the execution parser itself; a diagnostic never repairs or accepts a rejected reply. */
    fun inspectGui(response: String, phase: Phase, allowAuxiliaryText: Boolean = false): ProtocolDiagnostic =
        inspect { trace -> parseGui(response, phase, trace, allowAuxiliaryText) }

    fun inspectPlanner(response: String, callIndex: Int): ProtocolDiagnostic = inspect { trace -> parsePlanner(response, callIndex, trace) }

    private fun parseGui(response: String, phase: Phase, trace: DiagnosticBuilder, allowAuxiliaryText: Boolean): GuiReply {
        val message = responseMessage(response, "tool_calls", trace)
        val content = message.get("content")
        // The versioned envelope option permits accompanying text, never text-only actions.
        // Do not import that text into GuiReply, arguments or the next tool conversation.
        protocolRequire(content == null || content.isJsonNull || (content.isJsonPrimitive && content.asJsonPrimitive.isString &&
            (allowAuxiliaryText || content.asString.isEmpty())), ProtocolReason.GUI_CONTENT)
        val calls = message.get("tool_calls")
        protocolRequire(calls != null && calls.isJsonArray, ProtocolReason.TOOL_CALLS_SHAPE)
        protocolRequire(calls!!.asJsonArray.size() == 1, ProtocolReason.TOOL_CALL_COUNT)
        val call = objectAt(calls.asJsonArray[0], ProtocolReason.TOOL_CALL_SHAPE)
        protocolRequire(stringAt(call.get("type"), ProtocolReason.TOOL_CALL_TYPE) == "function", ProtocolReason.TOOL_CALL_TYPE)
        val callId = stringAt(call.get("id"), ProtocolReason.TOOL_CALL_ID)
        protocolRequire(callId.isNotBlank(), ProtocolReason.TOOL_CALL_ID)
        val function = objectAt(call.get("function"), ProtocolReason.FUNCTION_SHAPE)
        protocolRequire(function.keySet() == setOf("name", "arguments"), ProtocolReason.FUNCTION_FIELDS)
        val name = stringAt(function.get("name"), ProtocolReason.TOOL_NAME_TYPE)
        val argumentsJson = stringAt(function.get("arguments"), ProtocolReason.ARGUMENTS_TYPE)
        val arguments = objectAt(parseJson(argumentsJson, JsonSlot.ARGUMENTS), ProtocolReason.ARGUMENTS_SHAPE)
        val allowed = if (phase == Phase.VERIFY || phase == Phase.CONFIRM) observationTools else executionTools
        val tool = allowed.singleOrNull { it.objectValue().required("function").objectValue().required("name").stringValue() == name }
            ?: throw ProtocolViolation(ProtocolReason.TOOL_NOT_ALLOWED)
        ToolSchema.validate(arguments, tool.objectValue().required("function").objectValue().required("parameters").objectValue())
        val typed = arguments.entrySet().associate { (key, value) ->
            key to if (value.asJsonPrimitive.isNumber) {
                try { value.asBigDecimal.intValueExact() } catch (_: ArithmeticException) { throw ProtocolViolation(ProtocolReason.ARGUMENTS_INTEGER_RANGE) }
            } else value.stringValue()
        }
        return GuiReply(callId, PhoneAction(name, typed), argumentsJson)
    }

    private fun parsePlanner(response: String, callIndex: Int, trace: DiagnosticBuilder): PlannerDecision {
        protocolRequire(callIndex in 1..2, ProtocolReason.PLANNER_CALL_INDEX)
        val message = responseMessage(response, "stop", trace)
        protocolRequire(!message.has("tool_calls") || message.required("tool_calls").isJsonNull, ProtocolReason.PLANNER_TOOL_CALLS)
        val output = objectAt(parseJson(stringAt(message.get("content"), ProtocolReason.PLANNER_CONTENT_TYPE), JsonSlot.PLANNER), ProtocolReason.PLANNER_OUTPUT_SHAPE)
        protocolRequire(output.keySet() == setOf("completion_status", "decision", "objective", "completion_conditions"), ProtocolReason.PLANNER_FIELDS)
        val status = stringAt(output.get("completion_status"), ProtocolReason.PLANNER_VALUE_TYPE)
        val decision = stringAt(output.get("decision"), ProtocolReason.PLANNER_VALUE_TYPE)
        val objective = stringAt(output.get("objective"), ProtocolReason.PLANNER_VALUE_TYPE)
        val rawConditions = output.get("completion_conditions")
        protocolRequire(rawConditions != null && rawConditions.isJsonArray, ProtocolReason.PLANNER_CONDITIONS)
        val conditions = rawConditions!!.asJsonArray.map { stringAt(it, ProtocolReason.PLANNER_CONDITIONS).also { value -> protocolRequire(value.isNotBlank(), ProtocolReason.PLANNER_CONDITIONS) } }
        val firstAllowed = setOf("COMPLETE:VERIFY", "INCOMPLETE:EXECUTE", "INCOMPLETE:VERIFY", "INCOMPLETE:FALLBACK", "UNVERIFIABLE:VERIFY", "UNVERIFIABLE:FALLBACK")
        val secondAllowed = setOf("COMPLETE:FINISH", "INCOMPLETE:EXECUTE", "INCOMPLETE:FALLBACK", "UNVERIFIABLE:FALLBACK")
        protocolRequire("$status:$decision" in if (callIndex == 1) firstAllowed else secondAllowed, ProtocolReason.PLANNER_BRANCH)
        if (decision == "FINISH") {
            protocolRequire(objective.isEmpty() && conditions.isEmpty(), ProtocolReason.PLANNER_FINISH_FIELDS)
        } else {
            protocolRequire(objective.isNotBlank(), ProtocolReason.PLANNER_OBJECTIVE)
            if (decision != "FALLBACK") protocolRequire(conditions.isNotEmpty(), ProtocolReason.PLANNER_CONDITIONS)
        }
        return PlannerDecision(status, decision, objective, conditions)
    }

    private fun responseMessage(response: String, finishReason: String, trace: DiagnosticBuilder): JsonObject {
        val root = objectAt(parseJson(response, JsonSlot.RESPONSE), ProtocolReason.RESPONSE_ROOT)
        trace.read(root)
        protocolRequire(!root.has("error"), ProtocolReason.RESPONSE_ERROR)
        val choices = root.get("choices")
        protocolRequire(choices != null && choices.isJsonArray, ProtocolReason.CHOICES_SHAPE)
        protocolRequire(choices!!.asJsonArray.size() == 1, ProtocolReason.CHOICE_COUNT)
        val choice = objectAt(choices.asJsonArray[0], ProtocolReason.CHOICE_SHAPE)
        val actualFinish = stringAt(choice.get("finish_reason"), ProtocolReason.FINISH_REASON)
        val finishFailure = when {
            actualFinish == "length" -> ProtocolReason.RESPONSE_TRUNCATED
            finishReason == "tool_calls" && actualFinish == "stop" -> ProtocolReason.GUI_STOP_WITHOUT_TOOL
            else -> ProtocolReason.FINISH_REASON
        }
        protocolRequire(actualFinish == finishReason, finishFailure)
        choice.get("index")?.let { protocolRequire(it.isJsonPrimitive && it.asJsonPrimitive.isNumber && it.asString == "0", ProtocolReason.CHOICE_INDEX) }
        val message = objectAt(choice.get("message"), ProtocolReason.MESSAGE_SHAPE)
        protocolRequire(stringAt(message.get("role"), ProtocolReason.MESSAGE_ROLE) == "assistant", ProtocolReason.MESSAGE_ROLE)
        // Both profiles disable thinking; never import an unexpected reasoning history.
        message.get("reasoning_content")?.let { protocolRequire(it.isJsonNull || (it.isJsonPrimitive && it.asJsonPrimitive.isString && it.asString.isEmpty()), ProtocolReason.REASONING_CONTENT) }
        return message
    }

    private fun objectAt(value: JsonElement?, reason: ProtocolReason): JsonObject {
        protocolRequire(value != null && value.isJsonObject, reason)
        return value!!.asJsonObject
    }

    private fun stringAt(value: JsonElement?, reason: ProtocolReason): String {
        protocolRequire(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString, reason)
        return value!!.asString
    }

    private enum class JsonSlot { RESPONSE, ARGUMENTS, PLANNER }

    private fun parseJson(text: String, slot: JsonSlot): JsonElement = try { StrictJson.parse(text) } catch (failure: StrictJsonViolation) {
        throw ProtocolViolation(when (slot) {
            JsonSlot.RESPONSE -> when (failure.reason) {
                StrictJsonReason.INVALID -> ProtocolReason.RESPONSE_JSON_INVALID
                StrictJsonReason.DUPLICATE_FIELD -> ProtocolReason.RESPONSE_JSON_DUPLICATE_FIELD
                StrictJsonReason.DEPTH -> ProtocolReason.RESPONSE_JSON_DEPTH
            }
            JsonSlot.ARGUMENTS -> when (failure.reason) {
                StrictJsonReason.INVALID -> ProtocolReason.ARGUMENTS_JSON_INVALID
                StrictJsonReason.DUPLICATE_FIELD -> ProtocolReason.ARGUMENTS_JSON_DUPLICATE_FIELD
                StrictJsonReason.DEPTH -> ProtocolReason.ARGUMENTS_JSON_DEPTH
            }
            JsonSlot.PLANNER -> when (failure.reason) {
                StrictJsonReason.INVALID -> ProtocolReason.PLANNER_JSON_INVALID
                StrictJsonReason.DUPLICATE_FIELD -> ProtocolReason.PLANNER_JSON_DUPLICATE_FIELD
                StrictJsonReason.DEPTH -> ProtocolReason.PLANNER_JSON_DEPTH
            }
        })
    }

    private inline fun inspect(block: (DiagnosticBuilder) -> Any): ProtocolDiagnostic {
        val trace = DiagnosticBuilder()
        val reason = try { block(trace); ProtocolReason.VALID
        } catch (failure: ProtocolViolation) { failure.reason
        } catch (_: Exception) { ProtocolReason.VALIDATION_ERROR }
        return trace.result.copy(reason = reason)
    }

    private class DiagnosticBuilder {
        var result = ProtocolDiagnostic(ProtocolReason.VALIDATION_ERROR)

        fun read(root: JsonObject) {
            val choices = root.get("choices")?.takeIf { it.isJsonArray }?.asJsonArray
            result = result.copy(choiceCount = choices?.size())
            val choice = choices?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val reason = choice.get("finish_reason")
            val finish = when {
                reason == null -> ProtocolFinishReason.ABSENT
                reason.isJsonNull -> ProtocolFinishReason.NULL
                !reason.isJsonPrimitive || !reason.asJsonPrimitive.isString -> ProtocolFinishReason.INVALID_TYPE
                else -> when (reason.asString) {
                    "tool_calls" -> ProtocolFinishReason.TOOL_CALLS
                    "stop" -> ProtocolFinishReason.STOP
                    "length" -> ProtocolFinishReason.LENGTH
                    "content_filter" -> ProtocolFinishReason.CONTENT_FILTER
                    "function_call" -> ProtocolFinishReason.FUNCTION_CALL
                    else -> ProtocolFinishReason.OTHER
                }
            }
            result = result.copy(finishReason = finish)
            val message = choice.get("message")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
            val content = message.get("content")
            val reasoning = message.get("reasoning_content")
            val calls = message.get("tool_calls")?.takeIf { it.isJsonArray }?.asJsonArray
            val function = calls?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?.get("function")?.takeIf { it.isJsonObject }?.asJsonObject
            val name = function?.get("name")
            val tool = when {
                name == null -> ProtocolToolName.ABSENT
                !name.isJsonPrimitive || !name.asJsonPrimitive.isString -> ProtocolToolName.INVALID_TYPE
                else -> when (name.asString) {
                    "tap" -> ProtocolToolName.TAP
                    "long_press" -> ProtocolToolName.LONG_PRESS
                    "swipe" -> ProtocolToolName.SWIPE
                    "input_text" -> ProtocolToolName.INPUT_TEXT
                    "press_key" -> ProtocolToolName.PRESS_KEY
                    "launch_app" -> ProtocolToolName.LAUNCH_APP
                    "wait" -> ProtocolToolName.WAIT
                    "finish" -> ProtocolToolName.FINISH
                    else -> ProtocolToolName.UNKNOWN
                }
            }
            result = result.copy(toolCallCount = calls?.size(), contentShape = shape(content), contentChars = chars(content),
                reasoningShape = shape(reasoning), reasoningChars = chars(reasoning), toolName = tool)
        }

        private fun shape(value: JsonElement?): ProtocolTextShape = when {
            value == null -> ProtocolTextShape.ABSENT
            value.isJsonNull -> ProtocolTextShape.NULL
            !value.isJsonPrimitive || !value.asJsonPrimitive.isString -> ProtocolTextShape.INVALID_TYPE
            value.asString.isEmpty() -> ProtocolTextShape.EMPTY_STRING
            else -> ProtocolTextShape.NONEMPTY_STRING
        }

        private fun chars(value: JsonElement?): Int? = value?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.length
    }

    private inline fun <T> invalidOutput(block: () -> T): T = try { block()
    } catch (failure: ProtocolViolation) {
        throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = failure.reason.name)
    } catch (_: Exception) {
        throw RunFailure("MODEL", "INVALID_OUTPUT", protocolReason = ProtocolReason.VALIDATION_ERROR.name)
    }
}
