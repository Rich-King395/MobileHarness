"""Validate and summarize content-free normal-UI smoke archives for three real Apps."""
import json
import statistics
import sys
from collections import Counter
from pathlib import Path

import jsonschema

repo = Path(__file__).resolve().parents[1]
root = Path(sys.argv[1])

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON field")
        result[key] = value
    return result

def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"), object_pairs_hook=unique_object)

plan = read(root / "plan.json")
schema = read(repo / "docs/data-contracts/minimal-event.v1.1.schema.json")
validator = jsonschema.Draft202012Validator(schema)
rows, profiles, session_ids = [], set(), set()
for case in plan["cases"]:
    case_id = case["id"]
    events = [json.loads(line, object_pairs_hook=unique_object) for line in
              (root / f"{case_id}.events.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
    assert events[0]["event"] == "session_started" and events[-1]["event"] == "session_ended"
    sid = events[0]["session_id"]
    assert sid not in session_ids
    session_ids.add(sid)
    for i, event in enumerate(events, 1):
        validator.validate(event)
        assert event["seq"] == i and event["session_id"] == sid
        if i > 1:
            assert event["elapsed_ms"] >= events[i - 2]["elapsed_ms"]
    start, end = events[0]["data"], events[-1]["data"]
    assert start["harness_enabled"] is True and start["gui_budget"] == 30
    assert start["prompt_bundle_version"] == "1.2.0" and start["adapter_version"] == "1.3.0"
    assert start["bundle_manifest_sha256"] == "be0723200c4159ae7f4b53b6bdccc78e82eb7ef5b6ce716beef4fae4ac6d003c"
    profiles.add((start["gui_profile_id"], start["planner_profile_id"]))
    starts, terminals = {}, {}
    for event in events:
        if event["event"] != "model_call":
            continue
        d = event["data"]
        key = (d["role"], d["phase"], d["call_index"])
        target = starts if d["status"] == "started" else terminals
        assert key not in target
        target[key] = d
    assert starts.keys() == terminals.keys()
    assert all(key[0] == "GUI" and key[1] == "ORIGINAL" for key in starts)
    assert sorted(key[2] for key in starts) == list(range(1, len(starts) + 1))
    assert len(starts) == end["gui_steps_used"] <= 30 and end["planner_calls_used"] == 0
    errors = [event["data"] for event in events if event["event"] == "error"]
    controls = [event["data"] for event in events if event["event"] == "control"]
    actions = [event["data"] for event in events if event["event"] == "action"]
    oracle = read(root / f"{case_id}.oracle.json")
    durations = [d["duration_ms"] for d in terminals.values()]
    rows.append({
        "case": case_id, "gui_calls": len(starts), "planner_calls": 0,
        "protocol_accepted": sum(d["status"] == "succeeded" for d in terminals.values()),
        "error_events": errors, "control_event_count": len(controls),
        "action_type_counts": dict(Counter(d["action_type"] for d in actions)),
        "action_result_counts": dict(Counter(d["result"] for d in actions)),
        "end_reason": end["reason"], "completion_source": end["completion_source"],
        "task_achieved_independently": oracle["task_achieved"],
        "events_validated": len(events), "session_duration_ms": events[-1]["elapsed_ms"],
        "request_terminal_duration_ms": {"min": min(durations), "median": statistics.median(durations), "max": max(durations)},
        "per_action_pixel_and_path_fidelity_independently_proven": False,
    })

assert len(profiles) == 1, "selected model profile changed between cases"
summary = {
    "schema_version": "1.0.0", "date": plan["date"], "app_version": plan["app_version"],
    "apk_sha256": plan["apk_sha256"], "normal_ui": True,
    "status": "COMPLETED_THREE_APP_SMOKE", "cases": rows,
    "gui_calls": sum(row["gui_calls"] for row in rows), "planner_calls": 0,
    "events_validated": sum(row["events_validated"] for row in rows),
    "tasks_achieved_independently": sum(row["task_achieved_independently"] is True for row in rows),
    "all_recorded_smoke_checks_pass": all(row["task_achieved_independently"] is True and
        row["error_events"] == [] and row["control_event_count"] == 0 and
        row["gui_calls"] == row["protocol_accepted"] and row["end_reason"] == "model_declared_complete" for row in rows),
    "same_selected_model_profiles": True, "model_points_corrected": False,
    "new_apk_or_prompt_changes": False, "screenshots_or_raw_responses_persisted": False,
    "prior_strict_argb_fidelity_finding": "OPEN_UNCHANGED",
}
(root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
