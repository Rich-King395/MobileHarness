"""Offline, content-free summary for the fixed 0.3.2 batch. Never contacts a device.

Run with the project's Python containing jsonschema. Inputs are immutable archives;
only the batch's summary.json is replaced. Unknown input values are never echoed.
Exit 0: complete and validated; 2: partial or validation issues.
"""
from __future__ import annotations

import collections
import json
import math
import os
from pathlib import Path
import statistics
import sys

import jsonschema

ROOT = Path(__file__).resolve().parents[1]
BATCH = ROOT / "docs/acceptance/results/multiround-0.3.2-20261031"
APP, PROMPT, ADAPTER = "0.3.2-dev", "1.2.0", "1.3.0"
APK = "3c77f81e6918a232688dc484cbcec5b6c9de3ae52bd1e2a4442b431d3d61f288"
MANIFEST = "be0723200c4159ae7f4b53b6bdccc78e82eb7ef5b6ce716beef4fae4ac6d003c"
UUID = r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
INT = {"type": "integer", "minimum": 0, "maximum": 9007199254740991}
BOOL = {"type": "boolean"}
NULLINT = {"anyOf": [INT, {"type": "null"}]}
BOOLNULL = {"type": ["boolean", "null"]}


def enum(*values):
    return {"enum": list(values)}


def obj(properties, required=None):
    return {"type": "object", "properties": properties, "additionalProperties": False,
            "required": list(properties if required is None else required)}


ERRORS = set("TRANSPORT_ERROR API_ERROR INVALID_OUTPUT AUTHORIZATION_UNAVAILABLE SCREENSHOT_FAILED INPUT_FAILED INPUT_STATUS_UNKNOWN SERVICE_DISCONNECTED INVALID_STATE PAUSE_UNCONFIRMED STOP_ENTRY_UNAVAILABLE OBSERVATION_UNAVAILABLE".split())
BLOCKED = set("INVALID_STATE SERVICE_DISCONNECTED SERVICE_NOT_IDLE KEY_MISSING HOME_UNAVAILABLE HOME_NOT_FOREGROUND TARGET_NODE_UNAVAILABLE PRECONDITION_NOT_RESET STOP_ENTRY_UNAVAILABLE".split())
ENDS = set("model_declared_complete model_unable user_stop budget_exhausted interrupted".split())
ACTIONS = set("tap long_press swipe input_text press_key launch_app wait finish".split())
FLOW_CODES = ERRORS | BLOCKED | set("PENDING VERIFICATION_ALREADY_RUNNING WORKFLOW_TIMEOUT WORKFLOW_CONDITION_INTERRUPTED WORKFLOW_BLOCKED_STOPPED_NO_RETRY FULL_WORKFLOW_CONFIRMED MODEL_ENDED_TASK_NOT_CONFIRMED SHARED_BUDGET_EXHAUSTED MODEL_UNABLE_END WORKFLOW_STOPPED CANCELLED DIAGNOSTIC_FAILURE INPUT_TERMINAL_UNCONFIRMED FINAL_STATE_RESTORE_FAILED EXECUTION_CONTRACT_INVARIANT_FAILED FIXTURE_SCREENSHOT_RENDER_EVIDENCE_INSUFFICIENT".split())
FLOW_FLAGS = set("fixture_reset_baseline workflow_timeout final_fixture_current final_click_count_is_one final_other_fixture_state_unchanged no_takeover_or_return no_planner_request gui_attempt_accounting_matches no_retry_requested execution_contract_invariants service_flags_restored controls_restored native_terminal_confirmed takeover_or_return_seen retry_requested stop_after_blocked_without_retry observed_png_geometry_valid fixture_static_background_rendered started_from_resolved_home diagnostic_report_write_failed".split())
TAP_FLAGS = set("same_captured_frame target_available_before point_inside_captured_target point_inside_dispatch_target raw_arguments_as_pixels_inside_target target_and_count_stable_since_capture target_and_count_stable_across_capture fixture_static_background_rendered png_unchanged_before_dispatch native_completed native_terminal_confirmed original_action_unchanged png_unchanged_after_dispatch target_available_after counter_delta_one counter_unchanged other_fixture_state_unchanged".split())
MECHANICAL = ("same_captured_frame", "png_unchanged_before_dispatch", "png_unchanged_after_dispatch",
              "original_action_unchanged", "native_completed", "native_terminal_confirmed")
LOCAL_FLAGS = set("scope_store_roundtrip scope_store_no_plaintext corrupt_scope_rejected_without_rewrite explicit_empty_scope_saved home_frame_captured arbitrary_home_tap_rejected authorized_app_launched fixture_static_background_rendered_before_tap fixture_tap_effect original_action_unchanged native_terminal_confirmed unauthorized_foreground_rejected unauthorized_rejected_before_capture_ticket missing_scope_rejected launch_before_invalidation_completed invalidated_launch_observes_current_home controls_restored".split())
LOCAL_CODES = set("OK PENDING ALREADY_RUNNING ASSERTION_FAILED AUTHORIZATION_UNAVAILABLE CANCELLED CONTROLS_UNAVAILABLE DEVICE_FAILURE FIXTURE_BUTTON_UNAVAILABLE FIXTURE_COUNTER_UNAVAILABLE FIXTURE_LAUNCH_FAILED FIXTURE_NOT_FOREGROUND FIXTURE_RENDER_NOT_CONFIRMED_BEFORE_TAP FIXTURE_TAP_FAILED HOME_INPUT_NOT_REJECTED HOME_UNAVAILABLE INPUT_FAILED INPUT_STATUS_UNKNOWN INVALIDATED_LAUNCH_REUSED INVALIDATION_LAUNCH_FAILED OBSERVATION_SCOPE_FAILED SCOPE_STORE_FAILED SCOPE_STORE_INITIAL_STATE SCREENSHOT_FAILED SERVICE_NOT_IDLE SERVICE_UNAVAILABLE VERIFICATION_FAILURE".split())


def duplicate_closed(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("DUPLICATE_JSON_KEY")
        result[key] = value
    return result


def read(path):
    if path.stat().st_size > 2_000_000:
        raise ValueError("INPUT_TOO_LARGE")
    return json.loads(path.read_text("utf-8-sig"), object_pairs_hook=duplicate_closed)


def valid(value, schema):
    return not any(jsonschema.Draft202012Validator(schema).iter_errors(value))


def distribution(values):
    if not values:
        return {"count": 0, "minimum": None, "median": None, "p95_nearest_rank": None, "maximum": None, "mean": None}
    ordered = sorted(values)
    return {"count": len(values), "minimum": ordered[0], "median": statistics.median(ordered),
            "p95_nearest_rank": ordered[math.ceil(len(values) * .95) - 1],
            "maximum": ordered[-1], "mean": round(statistics.mean(values), 3)}


def ratio(passed, observed):
    return {"passed": passed, "observed": observed, "fraction": round(passed / observed, 6) if observed else None}


def main():
    issues = []

    def issue(kind, number, code):
        item = {"kind": kind, "round": number, "code": code}
        if item not in issues:
            issues.append(item)

    event_schema = read(ROOT / "docs/data-contracts/minimal-event.v1.1.schema.json")
    reasons = set(event_schema["$defs"]["error"]["properties"]["protocol_reason"]["enum"]) | {"VALID"}
    plan_properties = {key: {"const": value} for key, value in {
        "schema_version": "1.0.0", "app_version": APP, "apk_sha256": APK,
        "prompt_bundle_version": PROMPT, "adapter_version": ADAPTER,
        "workflow_rounds_planned": 10, "gui_budget_per_round": 8,
        "maximum_real_gui_attempts": 80, "planner_requests_planned": 0,
        "local_launch_rounds_planned": 3, "fixture_only_scope": True,
        "start_mode": "HOME_AUTO", "harness_enabled": True, "takeovers_planned": 0,
        "retries_per_round": 0, "model_task_parameters_changed": False,
        "screenshot_or_raw_reply_export": False, "separate_task_outcome_from_protocol_and_execution": True,
        "severe_failure_policy": "stop_on_unconfirmed_native_terminal_or_unrestored_controls",
    }.items()}
    try:
        plan = read(BATCH / "plan.json")
        if not valid(plan, obj(plan_properties)):
            issue("PLAN", 0, "PLAN_SCHEMA_OR_BASELINE_INVALID")
    except (OSError, ValueError):
        issue("PLAN", 0, "PLAN_UNREADABLE")

    call_properties = {"role": enum("GUI", "PLANNER"), "index": INT,
        "status": enum("STARTED", "PARSED", "FAILED", "CANCELLED"),
        "protocol_reason": enum(*sorted(reasons)),
        "finish_reason": enum("ABSENT", "NULL", "TOOL_CALLS", "STOP", "LENGTH", "CONTENT_FILTER", "FUNCTION_CALL", "OTHER", "INVALID_TYPE"),
        "choice_count": NULLINT, "tool_call_count": NULLINT,
        "content_shape": enum("ABSENT", "NULL", "EMPTY_STRING", "NONEMPTY_STRING", "INVALID_TYPE"),
        "reasoning_shape": enum("ABSENT", "NULL", "EMPTY_STRING", "NONEMPTY_STRING", "INVALID_TYPE"),
        "content_chars": NULLINT, "reasoning_chars": NULLINT,
        "tool_name": enum("ABSENT", "INVALID_TYPE", "UNKNOWN", "TAP", "LONG_PRESS", "SWIPE", "INPUT_TEXT", "PRESS_KEY", "LAUNCH_APP", "WAIT", "FINISH"),
        "action": enum(*sorted(ACTIONS | {"UNKNOWN"})), "error_code": enum(*sorted(ERRORS | BLOCKED | {"DIAGNOSTIC_FAILURE"})),
        "error_protocol_reason": enum(None, *sorted(reasons))}
    flow_properties = {"schema_version": {"const": "1.0.0"}, "app_version": {"const": APP},
        "prompt_bundle_version": {"const": PROMPT}, "adapter_version": enum(None, ADAPTER),
        "status": enum("RUNNING", "PASS", "DIAGNOSED", "CANCELLED", "BLOCKED", "FAIL", "UNCERTAIN"),
        "code": enum(*sorted(FLOW_CODES)), "state": enum("IDLE", "AUTO", "PAUSING", "MANUAL", "PREPARING", "CHECKING", "STOPPING", "UNCERTAIN", "BLOCKED", "ENDED"),
        "elapsed_ms": INT, "session_id": {"anyOf": [{"const": ""}, {"type": "string", "pattern": UUID}]},
        "gui_budget": {"const": 8}, "harness_enabled": {"const": True},
        "error_category": enum(None, "MODEL", "DEVICE", "COORDINATOR"), "error_code": enum(None, *sorted(ERRORS)),
        "protocol_reason": enum(None, *sorted(reasons)), "end_reason": enum(None, *sorted(ENDS)),
        "completion_source": enum(None, "none", "gui", "planner", "confirmation"),
        "flags": obj({key: BOOL for key in FLOW_FLAGS}, []),
        "calls": {"type": "array", "maxItems": 10, "items": obj(call_properties, ["role", "index", "status"])},
        "taps": {"type": "array", "maxItems": 8, "items": obj({"ordinal": INT, "gui_step": INT,
            **{key: BOOLNULL for key in TAP_FLAGS}})},
    }
    flow_properties.update({key: INT for key in "gui_steps_used planner_calls_used actual_gui_calls actual_planner_calls observe_calls home_frames fixture_frames phone_perform_calls completed_performs".split()})
    flow_properties.update({key: NULLINT for key in ("blocked_at_gui", "blocked_at_planner")})
    local_schema = obj({"schema_version": {"const": "1.0.0"}, "app_version": {"const": APP},
        "prompt_bundle_version": {"const": PROMPT}, "status": enum("RUNNING", "PASS", "FAIL", "BLOCKED"),
        "code": enum(*sorted(LOCAL_CODES)), "elapsed_ms": INT, "gui_attempts": {"const": 0},
        "planner_attempts": {"const": 0}, "perform_attempts": INT, "completed_performs": INT,
        "flags": obj({key: BOOL for key in LOCAL_FLAGS}, [])})

    rows, local_rows, sessions = [], [], set()
    total_calls = parsed_calls = nonempty_accepted = total_taps = mechanical_pass = 0
    hit_known = hit_pass = delta_known = delta_pass = 0
    event_total = event_gui = event_planner = event_actions = 0
    final_task_pass = final_task_observed = render_pass = render_observed = geometry_pass = geometry_observed = 0
    latencies, succeeded_latencies = [], []
    error_categories, error_codes, protocol_reasons, response_statuses, action_types = (collections.Counter() for _ in range(5))
    missing_workflow, missing_local, missing_events, workflow_seen, local_seen = [], [], [], 0, 0
    attribution_counts = collections.Counter()
    for number in range(1, 11):
        path = BATCH / f"workflow-{number:02d}.json"
        if not path.exists():
            missing_workflow.append(number)
            continue
        workflow_seen += 1
        before_issues = len(issues)
        row = {"round": number, "diagnostic_schema_valid": False, "events_valid": False}
        rows.append(row)
        try:
            data = read(path)
        except (OSError, ValueError):
            issue("WORKFLOW", number, "DIAGNOSTIC_UNREADABLE")
            continue
        if not valid(data, obj(flow_properties)):
            issue("WORKFLOW", number, "DIAGNOSTIC_NOT_CLOSED_OR_VERSION_INVALID")
            continue
        row["diagnostic_schema_valid"] = True
        sid = data["session_id"]
        if not sid or sid in sessions:
            issue("WORKFLOW", number, "SESSION_EMPTY_OR_DUPLICATED")
        sessions.add(sid)
        if data["adapter_version"] != ADAPTER:
            issue("WORKFLOW", number, "ADAPTER_VERSION_MISMATCH")
        flags = data["flags"]
        if data["state"] != "ENDED" or data["status"] in {"RUNNING", "UNCERTAIN"}:
            issue("WORKFLOW", number, "ROUND_NOT_TERMINAL")
        if not all(flags.get(key) is True for key in ("controls_restored", "service_flags_restored", "native_terminal_confirmed")):
            issue("WORKFLOW", number, "CLEANUP_NOT_CONFIRMED")
        if flags.get("diagnostic_report_write_failed") is True:
            issue("WORKFLOW", number, "DIAGNOSTIC_WRITE_FAILURE_REPORTED")
        if flags.get("retry_requested") is True or flags.get("takeover_or_return_seen") is True:
            issue("WORKFLOW", number, "UNPLANNED_CONTROL_TRANSITION")
        if not all(flags.get(k) is True for k in ("no_retry_requested", "no_takeover_or_return", "no_planner_request", "gui_attempt_accounting_matches")):
            issue("WORKFLOW", number, "CONDITION_FLAGS_NOT_CONFIRMED")
        if data["actual_planner_calls"] != 0 or data["planner_calls_used"] != 0:
            issue("WORKFLOW", number, "UNPLANNED_PLANNER_CALL")
        if not 0 <= data["actual_gui_calls"] == data["gui_steps_used"] <= 8:
            issue("WORKFLOW", number, "DIAGNOSTIC_ACCOUNTING_MISMATCH")
        calls = data["calls"]
        if len(calls) != data["actual_gui_calls"] or any(c["role"] != "GUI" for c in calls) or [c["index"] for c in calls] != list(range(1, len(calls) + 1)):
            issue("WORKFLOW", number, "DIAGNOSTIC_CALL_SEQUENCE_MISMATCH")
        if any(c["status"] == "STARTED" for c in calls):
            issue("WORKFLOW", number, "DIAGNOSTIC_PENDING_CALL_AT_END")
        if [t["ordinal"] for t in data["taps"]] != list(range(1, len(data["taps"]) + 1)):
            issue("WORKFLOW", number, "TAP_SEQUENCE_MISMATCH")
        event_path = BATCH / f"workflow-{number:02d}.events.jsonl"
        ev = []
        if not event_path.exists():
            missing_events.append(number)
        try:
            if event_path.stat().st_size > 2_000_000:
                raise ValueError()
            ev = [json.loads(line, object_pairs_hook=duplicate_closed) for line in event_path.read_text("utf-8-sig").splitlines() if line.strip()]
            if not ev or any(not valid(e, event_schema) for e in ev):
                raise ValueError()
            row["events_valid"] = True
        except (OSError, ValueError):
            issue("WORKFLOW", number, "EVENT_ARCHIVE_MISSING_OR_SCHEMA_INVALID")
        starts, terminals = {}, {}
        if ev and row["events_valid"]:
            if any(e["seq"] != i or e["session_id"] != sid for i, e in enumerate(ev, 1)):
                issue("WORKFLOW", number, "EVENT_SEQUENCE_OR_SESSION_MISMATCH")
            if any(ev[i]["elapsed_ms"] < ev[i - 1]["elapsed_ms"] for i in range(1, len(ev))):
                issue("WORKFLOW", number, "EVENT_TIME_REGRESSION")
            begins = [e["data"] for e in ev if e["event"] == "session_started"]
            ends = [e["data"] for e in ev if e["event"] == "session_ended"]
            if len(begins) != 1 or ev[0]["event"] != "session_started" or len(ends) != 1:
                issue("WORKFLOW", number, "START_END_CARDINALITY_INVALID")
            else:
                b, end = begins[0], ends[0]
                if b["adapter_version"] != ADAPTER or b["prompt_bundle_version"] != PROMPT or b["bundle_manifest_sha256"] != MANIFEST or b["gui_budget"] != 8 or b["harness_enabled"] is not True:
                    issue("WORKFLOW", number, "EVENT_BASELINE_VERSION_MISMATCH")
                if end["gui_steps_used"] != data["gui_steps_used"] or end["planner_calls_used"] != 0 or end["reason"] != data["end_reason"] or end["completion_source"] != data["completion_source"]:
                    issue("WORKFLOW", number, "END_DIAGNOSTIC_MISMATCH")
            for e in ev:
                d = e["data"]
                if e["event"] == "control" and d["transition"] in {"retry_requested", "takeover_requested", "manual_granted", "return_requested", "return_committed"}:
                    issue("WORKFLOW", number, "UNPLANNED_CONTROL_EVENT")
                if e["event"] != "model_call":
                    continue
                key = (d["role"], d["phase"], d["call_index"])
                if d["role"] != "GUI" or d["phase"] != "ORIGINAL" or d["planner_calls_used"] != 0:
                    issue("WORKFLOW", number, "UNPLANNED_MODEL_ROLE_OR_PHASE")
                if d["status"] == "started":
                    if key in starts or key in terminals:
                        issue("WORKFLOW", number, "DUPLICATED_OR_REORDERED_MODEL_START")
                    starts[key] = d
                else:
                    if key not in starts or key in terminals:
                        issue("WORKFLOW", number, "MODEL_TERMINAL_PAIRING_INVALID")
                    terminals[key] = d
                    if key in starts and (starts[key]["gui_steps_used"] != d["gui_steps_used"] or starts[key]["planner_calls_used"] != d["planner_calls_used"]):
                        issue("WORKFLOW", number, "MODEL_TERMINAL_ACCOUNTING_MISMATCH")
            if set(starts) != set(terminals):
                issue("WORKFLOW", number, "MODEL_TERMINAL_MISSING")
            if len(starts) != data["actual_gui_calls"] or [d["gui_steps_used"] for d in starts.values()] != list(range(1, len(starts) + 1)):
                issue("WORKFLOW", number, "EVENT_GUI_ACCOUNTING_MISMATCH")
            for call in calls:
                terminal = terminals.get((call["role"], "ORIGINAL", call["index"]))
                coordinator_rejection = terminal is not None and terminal["status"] == "invalid_output" and any(
                    e["event"] == "error" and e["data"].get("protocol_reason") == "COORDINATOR_REPLY_REJECTED" for e in ev)
                # Gateway parsing precedes Coordinator capability/authorization checks.
                # A parsed proposal safely refused there is not a contradictory archive.
                if terminal is None or (call["status"] == "PARSED" and terminal["status"] != "succeeded" and not coordinator_rejection) or (call["status"] != "PARSED" and terminal["status"] == "succeeded"):
                    issue("WORKFLOW", number, "PARSE_EVENT_STATUS_MISMATCH")
            tap_events = [e for e in ev if e["event"] == "action" and e["data"]["action_type"] == "tap"]
            if len(tap_events) != len(data["taps"]):
                issue("WORKFLOW", number, "TAP_EVENT_COUNT_MISMATCH")
            for t, e in zip(data["taps"], tap_events):
                if t["gui_step"] != e["data"]["gui_step"] or (e["data"]["result"] == "completed") != t["native_completed"]:
                    issue("WORKFLOW", number, "TAP_EVENT_STATUS_MISMATCH")
        round_valid = len(issues) == before_issues
        row.update({"validation_passed": round_valid, "gui_calls": data["actual_gui_calls"],
            "parsed_calls": sum(c["status"] == "PARSED" for c in calls), "tap_samples": len(data["taps"]),
            "render_evidence_observed": data["fixture_frames"] > 0,
            "render_evidence_passed": flags.get("fixture_static_background_rendered") is True if data["fixture_frames"] else None,
            "independent_task_confirmed": flags.get("final_click_count_is_one") is True and flags.get("final_other_fixture_state_unchanged") is True,
            "end_reason": data["end_reason"], "protocol_reason": data["protocol_reason"],
            "reported_status": data["status"], "reported_code": data["code"]})
        if not round_valid:
            row["evidence_classification"] = "VALIDATION_OR_EVIDENCE_INSUFFICIENT"
            continue
        round_errors = [e["data"] for e in ev if e["event"] == "error"]
        tapped_mechanics = bool(data["taps"]) and all(all(t.get(k) is True for k in MECHANICAL) for t in data["taps"])
        render_ok = flags.get("observed_png_geometry_valid") is True and flags.get("fixture_static_background_rendered") is True
        grounding_candidate = render_ok and tapped_mechanics and any(
            t.get("point_inside_dispatch_target") is False and t.get("counter_unchanged") is True and
            t.get("target_and_count_stable_since_capture") is True for t in data["taps"])
        if any(e["code"] in {"TRANSPORT_ERROR", "API_ERROR"} for e in round_errors):
            classification = "EXTERNAL_TRANSPORT_OR_API"
        elif any(e["category"] == "DEVICE" for e in round_errors) or (data["taps"] and not tapped_mechanics):
            classification = "APK_EXECUTION_OR_CAPTURE_CANDIDATE"
        elif data["fixture_frames"] > 0 and not render_ok:
            classification = "RENDER_EVIDENCE_INSUFFICIENT"
        elif any(e["code"] == "INVALID_OUTPUT" for e in round_errors):
            classification = "PROTOCOL_OR_CAPABILITY_OUTPUT_REJECTED_SAFELY"
        elif grounding_candidate:
            classification = "MODEL_GROUNDING_CANDIDATE_WITH_PIXEL_SEMANTIC_LIMIT"
        elif row["independent_task_confirmed"]:
            classification = "TASK_CONFIRMED_WITH_OBSERVED_EXECUTION_EVIDENCE"
        elif data["end_reason"] == "model_declared_complete":
            classification = "MODEL_PREMATURE_END_CANDIDATE"
        elif data["end_reason"] == "model_unable":
            classification = "MODEL_UNABLE_DECLARATION"
        elif data["end_reason"] == "budget_exhausted":
            classification = "SHARED_BUDGET_EXHAUSTION"
        else:
            classification = "OUTCOME_NOT_CONFIRMED_CAUSE_UNDETERMINED"
        row["evidence_classification"] = classification
        attribution_counts[classification] += 1
        total_calls += len(calls)
        parsed_calls += row["parsed_calls"]
        nonempty_accepted += sum(c["status"] == "PARSED" and c.get("content_shape") == "NONEMPTY_STRING" for c in calls)
        response_statuses.update(c["status"] for c in calls)
        total_taps += len(data["taps"])
        for t in data["taps"]:
            mechanical_pass += all(t.get(key) is True for key in MECHANICAL)
            if isinstance(t.get("point_inside_dispatch_target"), bool):
                hit_known += 1; hit_pass += t["point_inside_dispatch_target"]
            if isinstance(t.get("counter_delta_one"), bool):
                delta_known += 1; delta_pass += t["counter_delta_one"]
        geometry_observed += data["observe_calls"] > 0
        geometry_pass += data["observe_calls"] > 0 and flags.get("observed_png_geometry_valid") is True
        render_observed += data["fixture_frames"] > 0
        render_pass += data["fixture_frames"] > 0 and flags.get("fixture_static_background_rendered") is True
        final_task_observed += flags.get("final_fixture_current") is True
        final_task_pass += row["independent_task_confirmed"]
        for e in ev:
            d = e["data"]
            event_total += 1
            if e["event"] == "model_call":
                if d["status"] == "started":
                    event_gui += d["role"] == "GUI"; event_planner += d["role"] == "PLANNER"
                else:
                    latencies.append(d["duration_ms"])
                    if d["status"] == "succeeded": succeeded_latencies.append(d["duration_ms"])
            elif e["event"] == "action": event_actions += 1; action_types[d["action_type"]] += 1
            elif e["event"] == "error":
                error_categories[d["category"]] += 1; error_codes[d["code"]] += 1
                if "protocol_reason" in d: protocol_reasons[d["protocol_reason"]] += 1
    for number in range(1, 4):
        path = BATCH / f"local-launch-{number:02d}.json"
        if not path.exists(): missing_local.append(number); continue
        local_seen += 1
        try:
            data = read(path)
            if not valid(data, local_schema): raise ValueError()
        except (OSError, ValueError):
            issue("LOCAL", number, "LOCAL_NOT_CLOSED_OR_VERSION_INVALID")
            local_rows.append({"round": number, "validation_passed": False})
            continue
        flags = data["flags"]
        passed = (data["status"] == "PASS" and data["code"] == "OK" and
                  set(flags) == LOCAL_FLAGS and all(flags.values()) and
                  data["perform_attempts"] == 4 and data["completed_performs"] == 3)
        local_rows.append({"round": number, "validation_passed": True, "checks_passed": passed,
                           "reported_status": data["status"], "reported_code": data["code"],
                           "render_before_tap": flags.get("fixture_static_background_rendered_before_tap"),
                           "gui_requests": 0, "planner_requests": 0})
    partial = bool(missing_workflow or missing_local or missing_events)
    validated_rounds = sum(r.get("validation_passed") is True for r in rows)
    summary = {"schema_version": "1.0.0", "app_version": APP, "prompt_bundle_version": PROMPT,
        "adapter_version": ADAPTER, "apk_sha256": APK,
        "status": "VALIDATION_ISSUES" if issues else "PARTIAL" if partial else "COMPLETE",
        "partial": partial, "workflow_rounds_planned": 10, "workflow_rounds_archived": workflow_seen,
        "workflow_rounds_validated": validated_rounds, "missing_workflow_rounds": missing_workflow,
        "missing_event_archive_rounds": missing_events,
        "local_rounds_planned": 3, "local_rounds_archived": local_seen, "missing_local_rounds": missing_local,
        "validation_issues": issues, "aggregate_scope": "VALIDATED_WORKFLOW_ROUNDS_ONLY",
        "screenshots": {"geometry_rounds": ratio(geometry_pass, geometry_observed),
                        "fixture_render_evidence_rounds": ratio(render_pass, render_observed),
                        "full_image_semantic_stability_proven": False},
        "protocol": {"gui_attempts": total_calls, "parsed_calls": parsed_calls,
                     "parse_acceptance": ratio(parsed_calls, total_calls), "response_status_counts": dict(response_statuses),
                     "parsed_nonempty_content_calls": nonempty_accepted,
                     "error_categories": dict(error_categories), "error_codes": dict(error_codes),
                     "error_protocol_reasons": dict(protocol_reasons)},
        "execution": {"actual_tap_samples": total_taps, "tap_mechanics": ratio(mechanical_pass, total_taps),
                      "tap_point_inside_dispatch_target": ratio(hit_pass, hit_known),
                      "tap_counter_increment_one": ratio(delta_pass, delta_known),
                      "action_event_counts": dict(action_types)},
        "task_outcome": {"confirmed_rounds": final_task_pass, "validated_rounds": validated_rounds,
                         "final_fixture_observed_rounds": final_task_observed,
                         "confirmation_fraction": round(final_task_pass / validated_rounds, 6) if validated_rounds else None,
                         "model_end_equals_success": False},
        "evidence_classification_counts": dict(attribution_counts),
        "model_terminal_duration_ms": distribution(latencies),
        "succeeded_model_terminal_duration_ms": distribution(succeeded_latencies),
        "events": {"schema_version": "1.1.0", "validated_records": event_total,
                   "gui_started": event_gui, "planner_started": event_planner, "action_events": event_actions},
        "workflow_rounds": rows, "local_rounds": local_rows,
        "long_term_or_other_app_stability_claimed": False,
        "input_arguments_or_reply_text_exported": False}
    BATCH.mkdir(parents=True, exist_ok=True)
    temporary = BATCH / "summary.json.tmp"
    temporary.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", "utf-8")
    os.replace(temporary, BATCH / "summary.json")
    print(json.dumps({"status": summary["status"], "workflow_rounds_archived": workflow_seen,
        "workflow_rounds_validated": validated_rounds, "local_rounds_archived": local_seen,
        "validation_issue_count": len(issues), "gui_attempts": total_calls, "task_confirmed": final_task_pass}))
    return 2 if partial or issues else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError):
        # Fixed message only: never expose malformed archived values or exception text.
        print(json.dumps({"status": "SUMMARY_FAILED", "code": "INPUT_OR_CONTRACT_ERROR"}))
        sys.exit(2)
