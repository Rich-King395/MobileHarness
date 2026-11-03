"""Analyze MobileHarness content-free CSV v1.0.0 locally (Python standard library).

Usage: python tools/analyze_experiment_csv.py run1.csv [run2.csv ...] --output analysis-dir
Without --output, prints JSON only. Later files/rows replace an earlier session snapshot.
Input CSVs contain ended sessions by their export contract. No task or image is read.
Groups are descriptive; user-reported completion uses rated sessions as denominator.
"""
import argparse
import csv
import json
from pathlib import Path
import re
import sys

VERSION = "1.0.0"
UUID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
SEMVER = re.compile(r"(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})\.(?:0|[1-9][0-9]{0,2})")
SHA256 = re.compile(r"[0-9a-f]{64}")
INTEGER = re.compile(r"0|[1-9][0-9]*")
SCHEMAS = {"1.0.0", "1.1.0", "1.2.0", "1.3.0"}
REASONS = {"model_declared_complete", "model_unable", "user_stop", "budget_exhausted", "interrupted", "runtime_failure"}
RATINGS = {"user_reported_complete", "user_reported_incomplete"}
ERRORS = {"MODEL": {"TRANSPORT_ERROR", "API_ERROR", "INVALID_OUTPUT"},
          "DEVICE": {"AUTHORIZATION_UNAVAILABLE", "SCREENSHOT_FAILED", "INPUT_FAILED", "INPUT_STATUS_UNKNOWN", "SERVICE_DISCONNECTED"},
          "COORDINATOR": {"INVALID_STATE", "PAUSE_UNCONFIRMED", "STOP_ENTRY_UNAVAILABLE", "OBSERVATION_UNAVAILABLE"}}
TRANSPORT = {"DNS_FAILURE", "TLS_HANDSHAKE_FAILED", "TLS_PEER_UNVERIFIED", "TIMEOUT", "CONNECT_FAILED", "REQUEST_REPLAY_BLOCKED", "NETWORK_IO"}
EXECUTION = set("NO_FOCUSED_INPUT INPUT_NOT_FOCUSED INPUT_NOT_EDITABLE INPUT_DISABLED INPUT_ACTION_UNSUPPORTED SET_TEXT_REJECTED IME_SUBMIT_UNSUPPORTED GLOBAL_ACTION_REJECTED GESTURE_NOT_DISPATCHED GESTURE_CANCELLED STALE_OBSERVATION OVERLAY_TARGET_BLOCKED PROTECTED_INPUT INPUT_APP_NOT_AUTHORIZED CONTROL_NOT_AVAILABLE DEVICE_NOT_READY SCREENSHOT_UNAVAILABLE INPUT_RESULT_UNKNOWN".split())
PROTOCOL = set("RESPONSE_UTF8_INVALID RESPONSE_BODY_MISSING RESPONSE_TOO_LARGE COORDINATOR_REPLY_REJECTED COORDINATOR_PLANNER_REJECTED RESPONSE_JSON_INVALID RESPONSE_JSON_DUPLICATE_FIELD RESPONSE_JSON_DEPTH RESPONSE_ROOT RESPONSE_ERROR CHOICES_SHAPE CHOICE_COUNT CHOICE_SHAPE FINISH_REASON RESPONSE_TRUNCATED GUI_STOP_WITHOUT_TOOL CHOICE_INDEX MESSAGE_SHAPE MESSAGE_ROLE REASONING_CONTENT GUI_CONTENT TOOL_CALLS_SHAPE TOOL_CALL_COUNT TOOL_CALL_SHAPE TOOL_CALL_TYPE TOOL_CALL_ID FUNCTION_SHAPE FUNCTION_FIELDS TOOL_NAME_TYPE TOOL_NOT_ALLOWED ARGUMENTS_TYPE ARGUMENTS_JSON_INVALID ARGUMENTS_JSON_DUPLICATE_FIELD ARGUMENTS_JSON_DEPTH ARGUMENTS_SHAPE ARGUMENTS_INTEGER_RANGE SCHEMA_MISSING_REQUIRED_FIELD SCHEMA_ADDITIONAL_FIELD SCHEMA_WRONG_TYPE SCHEMA_INTEGER_FORMAT SCHEMA_VALUE_OUT_OF_RANGE SCHEMA_STRING_TOO_SHORT SCHEMA_ENUM_VALUE SCHEMA_UNSUPPORTED PLANNER_CALL_INDEX PLANNER_TOOL_CALLS PLANNER_CONTENT_TYPE PLANNER_JSON_INVALID PLANNER_JSON_DUPLICATE_FIELD PLANNER_JSON_DEPTH PLANNER_OUTPUT_SHAPE PLANNER_FIELDS PLANNER_VALUE_TYPE PLANNER_CONDITIONS PLANNER_BRANCH PLANNER_FINISH_FIELDS PLANNER_OBJECTIVE VALIDATION_ERROR".split())
METRICS = ["attempts_started", "valid_responses", "failed_calls", "cancelled_calls", "late_discarded_calls", "in_flight_calls", "missing_terminal_calls", "duration_samples", "call_duration_ms", "orphan_terminals"]
COLUMNS = ["analysis_version", "session_id", "schema_version", "harness_enabled", "gui_steps", "gui_budget", "planner_calls", "duration_ms", "recorded_duration_ms", "end_reason", "completion_source", "user_assessment", "takeover_count", "return_count", "automatic_recoveries"] + [f"{role}_{metric}" for role in ("gui", "planner") for metric in METRICS] + ["model_error_events", "device_error_events", "coordinator_error_events", "error_summary", "prompt_bundle_version", "adapter_version", "execution_policy_version", "bundle_manifest_sha256", "gui_profile_id", "planner_profile_id"]
TEXT = {"analysis_version", "session_id", "schema_version", "harness_enabled", "end_reason", "completion_source", "user_assessment", "error_summary", "prompt_bundle_version", "adapter_version", "execution_policy_version", "bundle_manifest_sha256", "gui_profile_id", "planner_profile_id"}
NUMBER_COLUMNS = set(COLUMNS) - TEXT


class AnalysisError(Exception):
    """Its code is fixed; never include input values or file contents."""


def require(condition, code="INVALID_CSV"):
    if not condition:
        raise AnalysisError(code)


def valid_error_summary(value):
    if not value:
        return True
    for item in value.split(";"):
        parts = item.rsplit(":", 1)
        if len(parts) != 2 or not INTEGER.fullmatch(parts[1]) or int(parts[1]) <= 0:
            return False
        tokens = parts[0].split("/")
        if len(tokens) not in (2, 3) or tokens[1] not in ERRORS.get(tokens[0], set()):
            return False
        if len(tokens) == 3:
            category, code, detail = tokens
            allowed = ((category == "MODEL" and code == "TRANSPORT_ERROR" and detail in TRANSPORT) or
                       (category == "MODEL" and code == "INVALID_OUTPUT" and detail in PROTOCOL) or
                       (category == "MODEL" and code == "API_ERROR" and re.fullmatch(r"HTTP_[1-5][0-9]{2}", detail)) or
                       (category == "DEVICE" and detail in EXECUTION))
            if not allowed:
                return False
    return True


def parse_row(row):
    require(set(row) == set(COLUMNS) and all(isinstance(value, str) for value in row.values()))
    require(row["analysis_version"] == VERSION, "UNSUPPORTED_ANALYSIS_VERSION")
    require(UUID.fullmatch(row["session_id"]) is not None)
    require(not row["schema_version"] or row["schema_version"] in SCHEMAS)
    require(row["harness_enabled"] in ("", "true", "false"))
    require(not row["end_reason"] or row["end_reason"] in REASONS)
    require(row["completion_source"] in ("", "none", "gui", "planner", "confirmation"))
    require(not row["user_assessment"] or row["user_assessment"] in RATINGS)
    for key in ("prompt_bundle_version", "adapter_version", "execution_policy_version"):
        require(not row[key] or SEMVER.fullmatch(row[key]) is not None)
    for key in ("gui_profile_id", "planner_profile_id"):
        require(not row[key] or UUID.fullmatch(row[key]) is not None)
    require(not row["bundle_manifest_sha256"] or SHA256.fullmatch(row["bundle_manifest_sha256"]) is not None)
    require(valid_error_summary(row["error_summary"]))
    result = dict(row)
    for key in NUMBER_COLUMNS:
        raw = row[key]
        require(not raw or (len(raw) <= 16 and INTEGER.fullmatch(raw) and int(raw) <= 9_007_199_254_740_991))
        result[key] = int(raw) if raw else None
    # An interrupted end is only a last-record lower bound, even if a modified CSV filled duration.
    if result["end_reason"] == "interrupted":
        result["duration_ms"] = None
    return result


def read_exports(paths):
    sessions, count = {}, 0
    for path in paths:
        with Path(path).open("r", encoding="utf-8-sig", newline="") as source:
            reader = csv.DictReader(source, strict=True)
            require(reader.fieldnames is not None and len(reader.fieldnames) == len(COLUMNS) and
                    set(reader.fieldnames) == set(COLUMNS), "INVALID_COLUMNS")
            for row in reader:
                value = parse_row(row)
                sessions[value["session_id"]] = value
                count += 1
    return list(sessions.values()), count - len(sessions), count


def mean(rows, key):
    values = [row[key] for row in rows if row[key] is not None]
    return {"value": sum(values) / len(values) if values else None, "samples": len(values)}


def group(rows):
    rated = sum(row["user_assessment"] in RATINGS for row in rows)
    completed = sum(row["user_assessment"] == "user_reported_complete" for row in rows)
    error_counts = {}
    for category in ("model", "device", "coordinator"):
        values = [row[f"{category}_error_events"] for row in rows if row[f"{category}_error_events"] is not None]
        error_counts[category] = {"recorded_total": sum(values) if values else None, "samples": len(values)}
    return {"ended_runs": len(rows), "rated": rated, "user_completed": completed,
            "user_incomplete": rated - completed, "unrated": len(rows) - rated,
            "completion_rate": completed / rated if rated else None,
            "mean_gui_steps": mean(rows, "gui_steps"), "mean_duration_ms": mean(rows, "duration_ms"),
            "runtime_failure_runs": sum(row["end_reason"] == "runtime_failure" for row in rows),
            "error_events": error_counts}


def analyze(paths):
    rows, replaced, records = read_exports(paths)
    return {"analysis_version": VERSION, "kind": "DESCRIPTIVE_USER_REPORTS_NOT_CAUSAL",
            "input_files": len(paths), "input_rows": records, "replaced_snapshots": replaced,
            "ended_runs": len(rows), "duration_excludes_interrupted": True,
            "groups": {label: group([row for row in rows if row["harness_enabled"] == value])
                       for label, value in (("ON", "true"), ("OFF", "false"), ("UNKNOWN", ""))}}


def write_outputs(directory, summary):
    directory = Path(directory)
    json_path, csv_path = directory / "summary.json", directory / "summary.csv"
    require(not json_path.exists() and not csv_path.exists(), "OUTPUT_EXISTS")
    directory.mkdir(parents=True, exist_ok=True)
    headers = ["harness", "ended_runs", "rated", "user_completed", "user_incomplete", "unrated", "completion_rate",
               "mean_gui_steps", "gui_samples", "mean_duration_ms", "duration_samples", "runtime_failure_runs"]
    headers += [f"{category}_error_events_{field}" for category in ("model", "device", "coordinator") for field in ("recorded_total", "samples")]
    with json_path.open("x", encoding="utf-8") as target:
        json.dump(summary, target, ensure_ascii=False, indent=2)
        target.write("\n")
    with csv_path.open("x", encoding="utf-8", newline="") as target:
        writer = csv.writer(target, lineterminator="\r\n", quoting=csv.QUOTE_ALL)
        writer.writerow(headers)
        for label, data in summary["groups"].items():
            row = [label] + [data[key] for key in headers[1:7]] + [data["mean_gui_steps"]["value"], data["mean_gui_steps"]["samples"],
                    data["mean_duration_ms"]["value"], data["mean_duration_ms"]["samples"], data["runtime_failure_runs"]]
            row += [data["error_events"][category][field] for category in ("model", "device", "coordinator") for field in ("recorded_total", "samples")]
            writer.writerow(row)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv", nargs="+", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    try:
        summary = analyze(args.csv)
        if args.output is not None:
            write_outputs(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, allow_nan=False))
        return 0
    except AnalysisError as failure:
        code = str(failure)
    except (OSError, UnicodeError, csv.Error, ValueError, OverflowError):
        code = "INPUT_OR_OUTPUT_UNAVAILABLE"
    print(json.dumps({"status": "ERROR", "code": code}), file=sys.stderr)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
