"""Summarize fixed calculator diagnostic metadata; never reads images or raw model replies."""
import json
import sys
from collections import Counter
from pathlib import Path
import jsonschema

root = Path(sys.argv[1])
read = lambda name: json.loads((root / name).read_text(encoding="utf-8-sig"))
calibration = read("calibration-final.json")
workflow = read("workflow-attempt1.json")
calls, frames, taps = (workflow[key] for key in ("calls", "frames", "taps"))
calc_frames = [frame for frame in frames if frame["package_class"] == "CALCULATOR"]

def count_true(rows, key):
    return {"true": sum(row.get(key) is True for row in rows),
            "false": sum(row.get(key) is False for row in rows),
            "unknown": sum(row.get(key) not in (True, False) for row in rows)}

summary = {
    "schema_version": "1.0.0", "app_version": workflow["app_version"],
    "gui_calls": workflow["actual_gui_calls"], "planner_calls": workflow["actual_planner_calls"],
    "end_reason": workflow["end_reason"], "completion_source": workflow["completion_source"],
    "goal_420_observed": workflow["flags"].get("final_result_is_420"),
    "calibration_pass": calibration["status"] == "PASS",
    "calibration_control_taps": len(calibration["steps"]),
    "calibration_source_png": count_true(calibration["steps"], "image_source_png_equal"),
    "frames": len(frames), "calculator_frames": len(calc_frames), "model_taps": len(taps),
    "request_image_identity": count_true(calls, "request_image_equals_frame_png"),
    "reply_argument_identity": count_true(calls, "raw_reply_equals_parsed_action"),
    "source_png_pixel_identity": count_true(frames, "image_source_png_equal"),
    "bitmap_same_as": count_true(frames, "image_bitmap_same_as"),
    "capture_since_request": count_true(frames, "capture_timestamp_since_request"),
    "formula_dark_mask_exact": count_true(calc_frames, "formula_raster_dark_mask_exact"),
    "formula_rgb_within8": count_true(calc_frames, "formula_raster_rgb_within8"),
    "calculator_state_stable_across_reference": count_true(calc_frames, "calculator_state_stable_across_reference"),
    "keypad_all_dark_masks_exact": sum(frame.get("keypad_raster_regions_compared", 0) > 0 and
        frame["keypad_raster_regions_compared"] == frame.get("keypad_raster_dark_mask_exact_regions") for frame in calc_frames),
    "mapped_captured_key_counts": dict(Counter(tap["normalized_point_captured_key"] for tap in taps)),
    "mapped_dispatch_key_counts": dict(Counter(tap["normalized_point_dispatch_key"] for tap in taps)),
    "mapped_key_sequence": [tap["normalized_point_dispatch_key"] for tap in taps],
    "native_path_identity": count_true(taps, "native_path_matches_normalized_point"),
    "native_callback_path_identity": count_true(taps, "native_callback_path_matches"),
    "native_completion": count_true(taps, "native_completed"),
    "original_action_identity": count_true(taps, "original_action_unchanged"),
    "state_stable_since_capture": count_true(taps, "calculator_state_stable_since_capture"),
    "digit_append_effect": count_true(taps, "digit_append_expected_effect"),
    "controls_restored": workflow["flags"].get("controls_restored"),
    "native_terminal_confirmed": workflow["flags"].get("native_terminal_confirmed"),
    "raw_images_coordinates_replies_persisted": False,
    "model_points_corrected": False,
    "cause": "REQUIRES_HUMAN_EVIDENCE_REVIEW",
}
assert workflow["state"] == "ENDED", "workflow has not ended"
assert workflow["actual_gui_calls"] == workflow["gui_steps_used"] <= 30
assert workflow["actual_planner_calls"] == workflow["planner_calls_used"] == 0
assert len(calls) == workflow["actual_gui_calls"]
assert [call["index"] for call in calls] == list(range(1, len(calls) + 1))
assert summary["controls_restored"] is True and summary["native_terminal_confirmed"] is True
assert workflow["flags"].get("service_flags_restored") is True
assert workflow["flags"].get("diagnostic_report_write_failed") is not True
events = [json.loads(line) for line in (root / "workflow-attempt1.events.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
schema = json.loads((Path(__file__).resolve().parents[1] / "docs/data-contracts/minimal-event.v1.1.schema.json").read_text(encoding="utf-8-sig"))
validator = jsonschema.Draft202012Validator(schema)
for index, event in enumerate(events, 1):
    validator.validate(event)
    assert event["seq"] == index and event["session_id"] == workflow["session_id"]
assert events[0]["event"] == "session_started" and events[-1]["event"] == "session_ended"
starts, ends = {}, {}
for event in events:
    if event["event"] != "model_call":
        continue
    data = event["data"]
    key = (data["role"], data["phase"], data["call_index"])
    target = starts if data["status"] == "started" else ends
    assert key not in target
    target[key] = data
assert len(starts) == len(ends) == workflow["actual_gui_calls"] and starts.keys() == ends.keys()
assert all(value["status"] == "succeeded" for value in ends.values())
assert not any(event["event"] in ("error", "control") for event in events)
summary["events_validated"] = len(events)
summary["source_argb_mismatch_calculator_min"] = min(frame["image_argb_mismatch_pixels"] for frame in calc_frames)
summary["source_argb_mismatch_calculator_max"] = max(frame["image_argb_mismatch_pixels"] for frame in calc_frames)
if (root / "verdict.json").exists():
    summary["cause"] = read("verdict.json")["attribution"]
    summary["screenshot_fidelity"] = "OPEN"
(root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(summary, ensure_ascii=False, indent=2))
