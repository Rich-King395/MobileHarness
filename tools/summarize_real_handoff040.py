"""Summarize one completed real-App handoff archive using closed metadata only.

Usage: python tools/summarize_real_handoff040.py <case-archive-directory>
The directory must be a child of docs/acceptance/results/real-app-handoff-0.4.0.
Required inputs: prepared.json and events.jsonl. Optional input: oracle.json.
Missing events are not synthesized and do not produce a summary. This tool does
not access a phone, a network, credentials, screenshots, requests or replies.

Minimal-event 1.1.0 records action RESULTS, not action dispatch/start events.
Consequently an empty manual action interval cannot prove zero dispatches or
zero captures. These limits remain explicit in the summary.
"""

import argparse
import json
import sys
from collections import Counter
from pathlib import Path

import jsonschema


REPO = Path(__file__).resolve().parents[1]
ARCHIVE_ROOT = (REPO / "docs/acceptance/results/real-app-handoff-0.4.0").resolve()
SCHEMA_PATH = REPO / "docs/data-contracts/minimal-event.v1.1.schema.json"
CHECK_PHASES = {"VERIFY", "CONFIRM"}
PREPARED_FIELDS = {
    "date": {"type": "string", "pattern": r"^\d{4}-\d{2}-\d{2}$"},
    "app_version": {"type": "string", "enum": ["0.4.0-dev"]},
    "case_id": {"type": "string", "enum": ["CLOCK_STOPWATCH_READONLY_HUMAN_HANDOFF"]},
    "variant": {"type": "string", "enum": ["ON", "OFF"]},
    "prompt_bundle_version": {"const": "1.2.0"},
    "adapter_version": {"const": "1.3.0"},
    "budget": {"type": "integer", "minimum": 1},
    "original_scope_count": {"type": "integer", "minimum": 0},
    **{name: {"type": "boolean"} for name in (
        "clock_already_authorized", "scope_modified", "profiles_modified",
        "original_draft_empty", "consent_unchecked", "start_clicked_by_assistant",
        "model_points_corrected",
    )},
    "latest_event_file_before": {
        "type": "string", "pattern": r"^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\.jsonl$",
    },
    "manual_ui_reads_planned": {"const": 0},
    "hidden_retries_planned": {"const": 0},
}
PREPARED_SCHEMA = {
    "type": "object", "properties": PREPARED_FIELDS,
    "required": list(PREPARED_FIELDS), "additionalProperties": False,
}
ORACLE_SCHEMA = {
    "type": "object",
    "properties": {
        "case": {"const": "clock"},
        "source": {"const": "INDEPENDENT_POST_SESSION_LAYOUT"},
        **{name: {"type": "boolean"} for name in (
            "target_app_window_present", "stopwatch_tab_selected",
            "stopwatch_pane_present", "task_achieved", "personal_contents_exported",
        )},
    },
    "required": [
        "case", "source", "target_app_window_present", "stopwatch_tab_selected",
        "stopwatch_pane_present", "task_achieved", "personal_contents_exported",
    ],
    "additionalProperties": False,
}


class MetadataInputError(Exception):
    """An input failure whose fixed code is safe to show without input content."""


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise MetadataInputError("DUPLICATE_JSON_FIELD")
        result[key] = value
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object)


def confined_path(directory, name):
    path = (directory / name).resolve()
    if not path.is_relative_to(directory):
        raise MetadataInputError("ARCHIVE_FILE_OUTSIDE_CASE")
    return path


def checked_directory(value):
    directory = Path(value).resolve()
    if directory == ARCHIVE_ROOT or not directory.is_relative_to(ARCHIVE_ROOT):
        raise MetadataInputError("CASE_OUTSIDE_ALLOWED_ARCHIVE_ROOT")
    if not directory.is_dir():
        raise MetadataInputError("CASE_DIRECTORY_MISSING")
    return directory


def schema_valid(value, schema):
    return jsonschema.Draft202012Validator(schema).is_valid(value)


def build_summary(prepared, events, event_schema, oracle=None):
    """Pure metadata analysis; no input/output or device operations."""
    violations = []

    def check(condition, code, seq=None):
        if not condition:
            item = {"code": code}
            if seq is not None:
                item["record_number"] = seq
            violations.append(item)
        return bool(condition)

    prepared_ok = check(schema_valid(prepared, PREPARED_SCHEMA), "PREPARED_METADATA_SCHEMA_INVALID")
    event_validator = jsonschema.Draft202012Validator(event_schema)
    events_ok = isinstance(events, list) and bool(events)
    check(events_ok, "EVENTS_EMPTY_OR_NOT_ARRAY")
    if isinstance(events, list):
        for number, row in enumerate(events, 1):
            events_ok = check(event_validator.is_valid(row), "EVENT_SCHEMA_INVALID", number) and events_ok
    oracle_ok = oracle is None or check(schema_valid(oracle, ORACLE_SCHEMA), "ORACLE_METADATA_SCHEMA_INVALID")
    # Never merge or reproduce unvalidated input into a result or error message.
    if not (prepared_ok and events_ok and oracle_ok):
        return {
            "schema_version": "1.0.0", "metadata_validation": "FAIL",
            "violations": violations, "evidence_scope": "CLOSED_METADATA_ONLY",
            "handoff_flow_status": "NOT_EVALUATED", "task_target_status": "NOT_EVALUATED",
        }

    first = events[0]
    session_id = first["session_id"]
    previous_ms = -1
    start_rows = [e for e in events if e["event"] == "session_started"]
    end_rows = [e for e in events if e["event"] == "session_ended"]
    check(len(start_rows) == 1 and first["event"] == "session_started", "SESSION_START_NOT_UNIQUE_AND_FIRST")
    check(len(end_rows) == 1, "SESSION_END_NOT_UNIQUE")
    ended = False
    for number, row in enumerate(events, 1):
        check(row["seq"] == number, "EVENT_SEQUENCE_NOT_CONTIGUOUS", number)
        check(row["session_id"] == session_id, "SESSION_ID_CHANGED", number)
        check(row["elapsed_ms"] >= previous_ms, "ELAPSED_TIME_MOVED_BACKWARDS", number)
        check(not ended or row["event"] == "task_assessment", "NON_ASSESSMENT_EVENT_AFTER_END", number)
        check(row["event"] != "task_assessment" or ended, "ASSESSMENT_BEFORE_END", number)
        previous_ms = row["elapsed_ms"]
        ended = ended or row["event"] == "session_ended"

    start = start_rows[0]["data"] if start_rows else None
    end_event = end_rows[0] if len(end_rows) == 1 else None
    end = end_event["data"] if end_event else None
    budget = start["gui_budget"] if start else prepared["budget"]
    if start:
        check(start["harness_enabled"] == (prepared["variant"] == "ON"), "HARNESS_DIFFERS_FROM_PREPARED")
        check(budget == prepared["budget"], "BUDGET_DIFFERS_FROM_PREPARED")
        check(start["prompt_bundle_version"] == prepared["prompt_bundle_version"], "PROMPT_VERSION_CHANGED")
        check(start["adapter_version"] == prepared["adapter_version"], "ADAPTER_VERSION_CHANGED")
    check(prepared["clock_already_authorized"], "PREPARED_CLOCK_NOT_AUTHORIZED")
    check(not prepared["scope_modified"], "PREPARED_SCOPE_MODIFIED")
    check(not prepared["profiles_modified"], "PREPARED_PROFILES_MODIFIED")
    check(not prepared["model_points_corrected"], "PREPARED_MODEL_POINTS_CORRECTED")

    starts, terminals = {}, {}
    gui_used = planner_used = 0
    call_counts = Counter()
    action_rows = []
    decisions = []
    control_rows = []
    errors = []
    action_steps = set()
    readonly_phase_runs = 0
    readonly_phase = None
    p1_verify = None
    completed_check_finish = None
    recovery_first_seq = None
    p2_finish_seq = None
    handoff_dispatch_suspended = False

    for row in events:
        event, data, seq = row["event"], row["data"], row["seq"]
        if event == "model_call":
            key = (data["role"], data["call_index"])
            if data["status"] == "started":
                check(not handoff_dispatch_suspended, "MODEL_START_BEFORE_RETURN_COMMITTED", seq)
                check(key not in starts, "DUPLICATE_MODEL_START", seq)
                starts[key] = row
                call_counts[data["role"]] += 1
                gui_used += data["role"] == "GUI"
                planner_used += data["role"] == "PLANNER"
                check(data["call_index"] == call_counts[data["role"]], "ROLE_CALL_INDEX_NOT_CONTIGUOUS", seq)
                check(data["gui_steps_used"] == gui_used, "GUI_SHARED_COUNTER_MISMATCH", seq)
                check(data["planner_calls_used"] == planner_used, "PLANNER_COUNTER_MISMATCH", seq)
                check(gui_used <= budget, "GUI_BUDGET_EXCEEDED", seq)
                check(planner_used <= 2, "PLANNER_LIMIT_EXCEEDED", seq)
                if data["role"] == "GUI":
                    if data["phase"] in CHECK_PHASES:
                        if readonly_phase != data["phase"]:
                            readonly_phase_runs += 1
                        readonly_phase = data["phase"]
                        check(p1_verify is not None, "READONLY_WITHOUT_P1_VERIFY", seq)
                        if p1_verify:
                            expected = "CONFIRM" if p1_verify["data"]["completion_status"] == "COMPLETE" else "VERIFY"
                            check(data["phase"] == expected, "READONLY_PHASE_DIFFERS_FROM_DECISION", seq)
                    else:
                        readonly_phase = None
                    if data["phase"] == "RECOVERY" and recovery_first_seq is None:
                        recovery_first_seq = seq
                    check(p2_finish_seq is None, "GUI_CALL_AFTER_P2_FINISH", seq)
                else:
                    readonly_phase = None
                    check(recovery_first_seq is None, "PLANNER_AFTER_RECOVERY_STARTED", seq)
                    if data["call_index"] == 2:
                        check(completed_check_finish is not None, "P2_WITHOUT_READONLY_FINISH", seq)
            else:
                check(key not in terminals, "DUPLICATE_MODEL_TERMINAL", seq)
                terminals[key] = row
                source = starts.get(key)
                check(source is not None, "MODEL_TERMINAL_WITHOUT_START", seq)
                if source:
                    check(source["seq"] < seq, "MODEL_TERMINAL_BEFORE_START", seq)
                    check(all(data[name] == source["data"][name] for name in (
                        "phase", "gui_steps_used", "planner_calls_used",
                    )), "MODEL_TERMINAL_ATTEMPT_FIELDS_CHANGED", seq)
        elif event == "action":
            action_rows.append(row)
            check(p2_finish_seq is None, "ACTION_AFTER_P2_FINISH", seq)
            step = data["gui_step"]
            check(step not in action_steps, "DUPLICATE_ACTION_RESULT_FOR_GUI_STEP", seq)
            action_steps.add(step)
            accepted = terminals.get(("GUI", step))
            check(accepted is not None and accepted["data"]["status"] == "succeeded",
                  "ACTION_WITHOUT_ACCEPTED_GUI_REPLY", seq)
            if accepted:
                check(accepted["seq"] < seq and accepted["data"]["phase"] == data["phase"],
                      "ACTION_REPLY_PHASE_OR_ORDER_MISMATCH", seq)
            if data["phase"] in CHECK_PHASES:
                check(data["action_type"] in {"wait", "finish"}, "NON_READONLY_ACTION_IN_CHECK", seq)
                if data["action_type"] == "finish" and data["result"] == "completed":
                    completed_check_finish = row
        elif event == "planner_decision":
            decisions.append(row)
            index = data["planner_call_index"]
            accepted = terminals.get(("PLANNER", index))
            check(accepted is not None and accepted["data"]["status"] == "succeeded",
                  "DECISION_WITHOUT_ACCEPTED_PLANNER_REPLY", seq)
            check(sum(r["data"]["planner_call_index"] == index for r in decisions) == 1,
                  "DUPLICATE_PLANNER_DECISION", seq)
            if data["decision"] == "VERIFY":
                p1_verify = row
            if index == 2 and data["decision"] == "FINISH":
                p2_finish_seq = seq
        elif event == "control":
            control_rows.append(row)
            if data["transition"] == "takeover_requested":
                handoff_dispatch_suspended = True
            elif data["transition"] == "return_committed":
                handoff_dispatch_suspended = False
        elif event == "error":
            errors.append(row)

    check(starts.keys() == terminals.keys(), "MODEL_ATTEMPTS_NOT_EXACTLY_PAIRED")
    verify_decisions = sum(r["data"]["decision"] == "VERIFY" for r in decisions)
    check(verify_decisions <= 1 and readonly_phase_runs <= 1, "READONLY_ROUND_LIMIT_EXCEEDED")
    if prepared["variant"] == "OFF":
        check(planner_used == 0 and readonly_phase_runs == 0 and recovery_first_seq is None,
              "HARNESS_OFF_RECOVERY_PLANNING_OR_PHASE_USED")
    if end:
        check(end["gui_steps_used"] == gui_used <= budget, "END_GUI_COUNTER_MISMATCH")
        check(end["planner_calls_used"] == planner_used <= 2, "END_PLANNER_COUNTER_MISMATCH")
        if p2_finish_seq is not None:
            check(end["reason"] == "model_declared_complete" and end["completion_source"] == "planner",
                  "P2_FINISH_DID_NOT_END_DIRECTLY")

    transition_counts = Counter(row["data"]["transition"] for row in control_rows)
    required_order = ["takeover_requested", "manual_granted", "return_requested", "return_committed"]
    primary_controls = [r for r in control_rows if r["data"]["transition"] in required_order]
    expected_rank = {value: index for index, value in enumerate(required_order)}
    previous_rank = -1
    for row in primary_controls:
        rank = expected_rank[row["data"]["transition"]]
        check(rank >= previous_rank, "CONTROL_ORDER_INVALID", row["seq"])
        check(rank <= previous_rank + 1, "CONTROL_PREDECESSOR_MISSING", row["seq"])
        previous_rank = rank
    # Multiple return attempts can follow a failed fresh-observation check. They
    # do not count as another committed handoff, but are outside this exact case.
    exactly_one = all(transition_counts[name] == 1 for name in required_order)
    by_transition = {name: next((r for r in control_rows if r["data"]["transition"] == name), None)
                     for name in required_order}
    manual = by_transition["manual_granted"]
    returned = by_transition["return_requested"]
    committed = by_transition["return_committed"]
    manual_new_calls = manual_action_results = None
    manual_duration_ms = None
    if manual and returned and manual["seq"] < returned["seq"]:
        interval = [r for r in events if manual["seq"] < r["seq"] < returned["seq"]]
        manual_new_calls = sum(r["event"] == "model_call" and r["data"]["status"] == "started" for r in interval)
        manual_action_results = sum(r["event"] == "action" for r in interval)
        manual_duration_ms = returned["elapsed_ms"] - manual["elapsed_ms"]
        check(manual_new_calls == 0, "MODEL_START_DURING_MANUAL")
        check(manual_action_results == 0, "ACTION_RESULT_DURING_MANUAL")
    if committed:
        for row in starts.values():
            if row["data"]["role"] == "PLANNER":
                check(row["seq"] > committed["seq"], "PLANNER_BEFORE_RETURN_COMMITTED", row["seq"])

    error_counts = {category: dict(Counter(r["data"]["code"] for r in errors if r["data"]["category"] == category))
                    for category in ("MODEL", "DEVICE", "COORDINATOR")}
    target_met = None
    if oracle is not None:
        predicate = all(oracle[name] for name in (
            "target_app_window_present", "stopwatch_tab_selected", "stopwatch_pane_present",
        ))
        check(oracle["task_achieved"] == predicate, "ORACLE_TARGET_PREDICATE_INCONSISTENT")
        check(not oracle["personal_contents_exported"], "ORACLE_PERSONAL_CONTENTS_EXPORTED")
        target_met = predicate

    validation = "PASS" if not violations else "FAIL"
    handoff_flow = "PASS" if exactly_one and validation == "PASS" else (
        "FAIL" if violations else "NOT_COVERED"
    )
    gui_phases = Counter(r["data"]["phase"] for r in starts.values() if r["data"]["role"] == "GUI")
    cancelled_indices = [key[1] for key, row in terminals.items()
                         if key[0] == "GUI" and row["data"]["status"] == "cancelled"]
    return {
        "schema_version": "1.0.0", "case_id": prepared["case_id"],
        "date": prepared["date"], "app_version": prepared["app_version"],
        "variant": prepared["variant"], "evidence_scope": "CLOSED_METADATA_ONLY",
        "metadata_validation": validation, "violations": violations,
        "events_validated": len(events), "session_id": session_id,
        "session_duration_ms": end_event["elapsed_ms"] if end_event else None,
        "prepared_scope": {
            "original_app_count": prepared["original_scope_count"],
            "scope_modified": prepared["scope_modified"],
            "profiles_modified": prepared["profiles_modified"],
            "final_scope_preservation": "NOT_MEASURED_BY_THIS_TOOL",
        },
        "shared_budget": {
            "budget": budget, "gui_calls_started": gui_used, "planner_calls_started": planner_used,
            "gui_call_indices": sorted(key[1] for key in starts if key[0] == "GUI"),
            "cancelled_gui_indices_still_charged": sorted(cancelled_indices),
            "gui_calls_by_phase": dict(gui_phases),
            "model_attempts_exactly_paired": starts.keys() == terminals.keys(),
            "terminal_status_counts": dict(Counter(r["data"]["status"] for r in terminals.values())),
        },
        "handoff_flow_status": handoff_flow,
        "exactly_one_takeover_and_one_return": exactly_one,
        "control_transition_counts": dict(transition_counts),
        "controls": [{"seq": r["seq"], "elapsed_ms": r["elapsed_ms"],
                      "transition": r["data"]["transition"]} for r in control_rows],
        "manual_interval": {
            "duration_ms": manual_duration_ms, "model_calls_started": manual_new_calls,
            "action_results_recorded": manual_action_results,
            "action_dispatch_start_evidence": "NOT_RECORDED_IN_MINIMAL_EVENT_1.1.0",
            "capture_count_evidence": "NOT_RECORDED_IN_MINIMAL_EVENT_1.1.0",
        },
        "planner": {
            "calls": planner_used, "verify_decisions": verify_decisions,
            "readonly_gui_phase_runs": readonly_phase_runs,
            "readonly_gui_calls": sum(gui_phases[p] for p in CHECK_PHASES),
            "decisions": [r["data"] for r in decisions],
            "second_planner_direct_end": p2_finish_seq is not None,
            "recovery_gui_started": recovery_first_seq is not None,
        },
        "actions": {
            "type_counts": dict(Counter(r["data"]["action_type"] for r in action_rows)),
            "result_counts": dict(Counter(r["data"]["result"] for r in action_rows)),
            "finish_declarations": [
                {"phase": r["data"]["phase"], "gui_step": r["data"]["gui_step"],
                 "declared_outcome": r["data"]["declared_outcome"]}
                for r in action_rows if r["data"]["action_type"] == "finish"
            ],
        },
        "model_end": end,
        "model_errors": error_counts["MODEL"], "device_errors": error_counts["DEVICE"],
        "coordinator_errors": error_counts["COORDINATOR"],
        "model_reasoning_error_attribution": "NOT_DETERMINABLE_FROM_METADATA",
        "task_target_status": "NOT_VERIFIED" if target_met is None else (
            "INDEPENDENT_TARGET_MATCH" if target_met else "INDEPENDENT_TARGET_MISMATCH"
        ),
        "observable_stopwatch_target_met": target_met,
        "non_interaction_constraints_independently_verified": "NOT_VERIFIED",
        "explicit_user_assessments": [r["data"]["assessment"] for r in events if r["event"] == "task_assessment"],
        "coverage": {
            "real_handoff_metadata": "EXERCISED" if exactly_one else "NOT_COVERED",
            "readonly_phase": "EXERCISED" if readonly_phase_runs else "NOT_COVERED",
            "second_planner": "EXERCISED" if planner_used == 2 else "NOT_COVERED",
            "second_planner_direct_end": "EXERCISED" if p2_finish_seq is not None else "NOT_COVERED",
            "late_reply_network_delivery_independently_observed": "NOT_VERIFIED",
            "recorded_late_discarded_terminals": sum(r["data"]["status"] == "late_discarded" for r in terminals.values()),
            "zero_manual_captures": "NOT_PROVEN_BY_METADATA_LOGS",
            "real_message_history_isolation": "NOT_PROVEN_BY_METADATA_LOGS",
            "fresh_return_observation_count": "NOT_PROVEN_BY_METADATA_LOGS",
            "native_input_takeover_timing": "NOT_PROVEN_BY_METADATA_LOGS",
            "human_identity_of_control_button_actor": "NOT_PROVEN_BY_METADATA_LOGS",
        },
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("case_archive")
    args = parser.parse_args(argv)
    try:
        directory = checked_directory(args.case_archive)
        prepared_path = confined_path(directory, "prepared.json")
        events_path = confined_path(directory, "events.jsonl")
        oracle_path = confined_path(directory, "oracle.json")
        output_path = confined_path(directory, "summary.json")
        if not events_path.is_file():
            raise MetadataInputError("EVENTS_MISSING_NO_SUMMARY_WRITTEN")
        prepared = read_json(prepared_path)
        events = [json.loads(line, object_pairs_hook=unique_object) for line in
                  events_path.read_text(encoding="utf-8-sig").splitlines() if line.strip()]
        oracle = read_json(oracle_path) if oracle_path.is_file() else None
        summary = build_summary(prepared, events, read_json(SCHEMA_PATH), oracle)
        output_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 0 if summary["metadata_validation"] == "PASS" else 1
    except MetadataInputError as exc:
        print(json.dumps({"status": "INPUT_REJECTED", "code": str(exc)}))
    except (json.JSONDecodeError, UnicodeError):
        print(json.dumps({"status": "INPUT_REJECTED", "code": "INPUT_JSON_OR_ENCODING_INVALID"}))
    except OSError:
        print(json.dumps({"status": "INPUT_REJECTED", "code": "INPUT_OR_OUTPUT_UNAVAILABLE"}))
    return 2


if __name__ == "__main__":
    sys.exit(main())
