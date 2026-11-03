package org.mobileharness.app.model

/** No reply text, JSON keys, arguments, call ids, model names or exception messages enter this record. */
internal data class ProtocolDiagnostic(
    val reason: ProtocolReason,
    val finishReason: ProtocolFinishReason = ProtocolFinishReason.ABSENT,
    val choiceCount: Int? = null,
    val toolCallCount: Int? = null,
    val contentShape: ProtocolTextShape = ProtocolTextShape.ABSENT,
    val contentChars: Int? = null,
    val reasoningShape: ProtocolTextShape = ProtocolTextShape.ABSENT,
    val reasoningChars: Int? = null,
    val toolName: ProtocolToolName = ProtocolToolName.ABSENT,
)

internal enum class ProtocolReason {
    VALID,
    RESPONSE_UTF8_INVALID, RESPONSE_BODY_MISSING, RESPONSE_TOO_LARGE,
    COORDINATOR_REPLY_REJECTED, COORDINATOR_PLANNER_REJECTED,
    RESPONSE_JSON_INVALID, RESPONSE_JSON_DUPLICATE_FIELD, RESPONSE_JSON_DEPTH,
    RESPONSE_ROOT, RESPONSE_ERROR, CHOICES_SHAPE, CHOICE_COUNT, CHOICE_SHAPE,
    FINISH_REASON, RESPONSE_TRUNCATED, GUI_STOP_WITHOUT_TOOL,
    CHOICE_INDEX, MESSAGE_SHAPE, MESSAGE_ROLE, REASONING_CONTENT,
    GUI_CONTENT, TOOL_CALLS_SHAPE, TOOL_CALL_COUNT, TOOL_CALL_SHAPE, TOOL_CALL_TYPE,
    TOOL_CALL_ID, FUNCTION_SHAPE, FUNCTION_FIELDS, TOOL_NAME_TYPE, TOOL_NOT_ALLOWED,
    ARGUMENTS_TYPE, ARGUMENTS_JSON_INVALID, ARGUMENTS_JSON_DUPLICATE_FIELD,
    ARGUMENTS_JSON_DEPTH, ARGUMENTS_SHAPE, ARGUMENTS_INTEGER_RANGE,
    SCHEMA_MISSING_REQUIRED_FIELD, SCHEMA_ADDITIONAL_FIELD, SCHEMA_WRONG_TYPE,
    SCHEMA_INTEGER_FORMAT, SCHEMA_VALUE_OUT_OF_RANGE, SCHEMA_STRING_TOO_SHORT,
    SCHEMA_ENUM_VALUE, SCHEMA_UNSUPPORTED,
    PLANNER_CALL_INDEX, PLANNER_TOOL_CALLS, PLANNER_CONTENT_TYPE,
    PLANNER_JSON_INVALID, PLANNER_JSON_DUPLICATE_FIELD, PLANNER_JSON_DEPTH,
    PLANNER_OUTPUT_SHAPE, PLANNER_FIELDS, PLANNER_VALUE_TYPE,
    PLANNER_CONDITIONS, PLANNER_BRANCH, PLANNER_FINISH_FIELDS, PLANNER_OBJECTIVE,
    VALIDATION_ERROR,
}

internal enum class ProtocolFinishReason {
    ABSENT, NULL, TOOL_CALLS, STOP, LENGTH, CONTENT_FILTER, FUNCTION_CALL, OTHER, INVALID_TYPE,
}

internal enum class ProtocolTextShape { ABSENT, NULL, EMPTY_STRING, NONEMPTY_STRING, INVALID_TYPE }

/** Unknown names are collapsed; this enum cannot carry provider-controlled text. */
internal enum class ProtocolToolName {
    ABSENT, INVALID_TYPE, UNKNOWN,
    TAP, LONG_PRESS, SWIPE, INPUT_TEXT, PRESS_KEY, LAUNCH_APP, WAIT, FINISH,
}

internal class ProtocolViolation(val reason: ProtocolReason) : IllegalArgumentException(reason.name)

internal fun protocolRequire(value: Boolean, reason: ProtocolReason) {
    if (!value) throw ProtocolViolation(reason)
}

internal enum class StrictJsonReason { INVALID, DUPLICATE_FIELD, DEPTH }
internal class StrictJsonViolation(val reason: StrictJsonReason) : IllegalArgumentException(reason.name)
