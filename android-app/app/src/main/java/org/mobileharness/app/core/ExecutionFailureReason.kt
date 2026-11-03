package org.mobileharness.app.core

/** Fixed, content-free execution feedback. Never derive this from exception messages or UI text. */
enum class ExecutionFailureReason(val canReobserve: Boolean, val modelFeedback: String) {
    NO_FOCUSED_INPUT(true, "Text was not entered because no input field was focused. Inspect the current screen before choosing your next action."),
    INPUT_NOT_FOCUSED(true, "Text was not entered because the input field was not visible and focused. Inspect the current screen before choosing your next action."),
    INPUT_NOT_EDITABLE(true, "Text was not entered because the focused element was not editable. Inspect the current screen before choosing your next action."),
    INPUT_DISABLED(true, "Text was not entered because the focused field was disabled. Inspect the current screen before choosing your next action."),
    INPUT_ACTION_UNSUPPORTED(true, "Text was not entered because the focused field does not support the available text input action. Inspect the current screen and choose a supported action."),
    SET_TEXT_REJECTED(true, "The text input operation returned failure. Its visible effect is not confirmed. Inspect the current screen before choosing your next action."),
    IME_SUBMIT_UNSUPPORTED(true, "The Enter action was not performed because a supported IME submit connection is unavailable. Inspect the current screen and choose a supported action."),
    GLOBAL_ACTION_REJECTED(true, "The navigation operation returned failure. Its visible effect is not confirmed. Inspect the current screen before choosing your next action."),
    GESTURE_NOT_DISPATCHED(true, "The system did not accept the gesture for dispatch. Inspect the current screen before choosing your next action."),
    GESTURE_CANCELLED(true, "The gesture reached a cancelled terminal state and may have partially affected the screen. Inspect the current screen before choosing your next action."),
    STALE_OBSERVATION(true, "The proposed action was rejected before dispatch because its observation became stale. Inspect the new current screen before choosing your next action."),
    OVERLAY_TARGET_BLOCKED(true, "The action was not dispatched because the control window could not be moved clear of its path. The proposed coordinates were not changed. Inspect the current screen before choosing your next action."),
    PROTECTED_INPUT(false, "The operation was blocked because the focused input is protected."),
    INPUT_APP_NOT_AUTHORIZED(false, "The operation was blocked because its input target is outside the authorized App scope."),
    CONTROL_NOT_AVAILABLE(false, "The operation was blocked because valid control ownership or a control entry was unavailable."),
    DEVICE_NOT_READY(false, "The operation was blocked because the device was not ready for authorized automatic operation."),
    SCREENSHOT_UNAVAILABLE(false, "The operation was blocked because an authorized current observation was unavailable."),
    INPUT_RESULT_UNKNOWN(false, "The input result is uncertain. No automatic continuation is permitted until the input state is resolved."),
}
