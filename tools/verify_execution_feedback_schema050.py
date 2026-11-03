"""Offline execution-feedback metadata boundary checks; no phone, network, task or key access."""
import copy
import hashlib
import json
from pathlib import Path
import unittest

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
CONTRACTS = ROOT / "docs/data-contracts"
REASONS = """NO_FOCUSED_INPUT INPUT_NOT_FOCUSED INPUT_NOT_EDITABLE INPUT_DISABLED
INPUT_ACTION_UNSUPPORTED SET_TEXT_REJECTED IME_SUBMIT_UNSUPPORTED GLOBAL_ACTION_REJECTED
GESTURE_NOT_DISPATCHED GESTURE_CANCELLED STALE_OBSERVATION OVERLAY_TARGET_BLOCKED
PROTECTED_INPUT INPUT_APP_NOT_AUTHORIZED CONTROL_NOT_AVAILABLE DEVICE_NOT_READY
SCREENSHOT_UNAVAILABLE INPUT_RESULT_UNKNOWN""".split()


def load(name):
    return json.loads((CONTRACTS / name).read_text("utf-8-sig"))


SCHEMAS = {
    "1.0.0": load("minimal-event.schema.json"),
    "1.1.0": load("minimal-event.v1.1.schema.json"),
    "1.2.0": load("minimal-event.v1.2.schema.json"),
}
VALIDATORS = {version: jsonschema.Draft202012Validator(schema) for version, schema in SCHEMAS.items()}


def event(kind, data, version="1.2.0"):
    return {"schema_version": version, "session_id": "00000000-0000-4000-8000-000000000000",
            "seq": 1, "elapsed_ms": 0, "event": kind, "data": data}


def start(version="1.2.0", adapter=None):
    data = {"harness_enabled": True, "gui_budget": 30, "prompt_bundle_version": "1.2.0",
            "adapter_version": adapter or ("1.4.0" if version == "1.2.0" else "1.3.0"),
            "bundle_manifest_sha256": "0" * 64,
            "gui_profile_id": "00000000-0000-4000-8000-000000000001",
            "planner_profile_id": "00000000-0000-4000-8000-000000000002"}
    if version == "1.2.0":
        data["execution_policy_version"] = "1.1.0"
    return event("session_started", data, version)


def action(reason=None, result="device_error"):
    data = {"phase": "ORIGINAL", "gui_step": 1, "action_type": "tap", "result": result}
    if reason is not None:
        data["execution_failure_reason"] = reason
    return event("action", data)


def error(reason=None):
    data = {"category": "DEVICE", "code": "INPUT_FAILED"}
    if reason is not None:
        data["execution_failure_reason"] = reason
    return event("error", data)


class ExecutionFeedbackSchemaBoundaryTest(unittest.TestCase):
    def assert_valid(self, value, version="1.2.0"):
        VALIDATORS[version].validate(value)

    def assert_invalid(self, value, version="1.2.0"):
        self.assertFalse(VALIDATORS[version].is_valid(value))

    def test_all_schema_definitions_are_valid(self):
        for schema in SCHEMAS.values():
            jsonschema.Draft202012Validator.check_schema(schema)

    def test_frozen_old_schema_bytes_are_unchanged(self):
        frozen = {
            "minimal-event.schema.json": "f6c44d90974673f3a507ed083c835cacf76b7cbe1dc300b60f6014b9a89000bd",
            "minimal-event.v1.1.schema.json": "2c6a53620c310e9d7f9b42e0cddab48a86be3ef7ed38d572e7c18ae3f6af9781",
        }
        for name, digest in frozen.items():
            self.assertEqual(digest, hashlib.sha256((CONTRACTS / name).read_bytes()).hexdigest())

    def test_each_version_uses_its_own_start_contract(self):
        for version in SCHEMAS:
            row = start(version)
            self.assert_valid(row, version)
            for other in set(SCHEMAS) - {version}:
                self.assert_invalid(row, other)

    def test_new_session_requires_execution_policy(self):
        row = start()
        del row["data"]["execution_policy_version"]
        self.assert_invalid(row)
        row["data"]["execution_policy_version"] = "PRIVATE_DYNAMIC_TEXT"
        self.assert_invalid(row)

    def test_current_adapters_are_distinct_from_historical_compatibility(self):
        for adapter in ["1.1.0", "1.4.0"]:
            self.assert_valid(start(adapter=adapter))
        self.assert_invalid(start(adapter="1.3.0"))
        self.assert_valid(start("1.1.0", "1.3.0"), "1.1.0")

    def test_reason_enum_is_the_closed_18_value_list(self):
        self.assertEqual(REASONS, SCHEMAS["1.2.0"]["$defs"]["executionFailureReason"]["enum"])
        for reason in REASONS:
            self.assert_valid(action(reason))
            self.assert_valid(error(reason))

    def test_free_text_and_wrong_reason_types_are_rejected(self):
        for reason in ["PRIVATE_INPUT_OR_KEY", "completed", 1, True, {}, []]:
            self.assert_invalid(action(reason))
            self.assert_invalid(error(reason))

    def test_completed_action_cannot_have_a_failure_reason(self):
        self.assert_valid(action(result="completed"))
        self.assert_invalid(action("NO_FOCUSED_INPUT", "completed"))

    def test_only_device_error_can_have_execution_reason(self):
        for category, code in [("MODEL", "INVALID_OUTPUT"), ("COORDINATOR", "INVALID_STATE")]:
            row = error("NO_FOCUSED_INPUT")
            row["data"].update(category=category, code=code)
            self.assert_invalid(row)

    def test_historical_rows_cannot_carry_new_reason_or_policy(self):
        for version in ["1.0.0", "1.1.0"]:
            for row in [action("NO_FOCUSED_INPUT"), error("NO_FOCUSED_INPUT")]:
                row["schema_version"] = version
                self.assert_invalid(row, version)
            row = start(version)
            row["data"]["execution_policy_version"] = "1.1.0"
            self.assert_invalid(row, version)

    def test_no_content_field_becomes_exportable(self):
        for base in [start(), action("NO_FOCUSED_INPUT"), error("NO_FOCUSED_INPUT")]:
            for key in ["message", "text", "x", "y", "coordinates", "package_name", "url", "api_key", "screenshot", "frame_id"]:
                row = copy.deepcopy(base)
                row["data"][key] = "PRIVATE_SENTINEL"
                self.assert_invalid(row)

    def test_protocol_reason_constraints_stay_independent(self):
        row = event("error", {"category": "MODEL", "code": "INVALID_OUTPUT", "protocol_reason": "GUI_CONTENT"})
        self.assert_valid(row)
        row["data"]["execution_failure_reason"] = "NO_FOCUSED_INPUT"
        self.assert_invalid(row)
        row = error("NO_FOCUSED_INPUT")
        row["data"]["protocol_reason"] = "GUI_CONTENT"
        self.assert_invalid(row)


if __name__ == "__main__":
    unittest.main()
