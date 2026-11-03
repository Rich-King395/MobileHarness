"""Validate metadata-only same-frame color diagnostics and the unchanged-PNG recognition smoke."""
import json
import sys
from collections import Counter
from pathlib import Path

import jsonschema

root = Path(sys.argv[1])
repo = Path(__file__).resolve().parents[1]
read = lambda path: json.loads(path.read_text(encoding="utf-8-sig"))
local = read(root / "local-attempt1.json")
build = read(root / "build.json")
assert local["status"] == "DIAGNOSED" and local["app_version"] == "0.3.5-diag"
assert local["gui_attempts"] == local["planner_attempts"] == local["native_action_calls"] == 0
assert local["observe_calls"] == local["screenshot_calls"] == 4
assert len(local["frames"]) == len(local["synthetic"]) == 4
for key in ("controls_restored", "service_flags_restored", "native_terminal_confirmed", "capture_disabled", "session_scope_cleared", "observer_restored"):
    assert local[key] is True
assert local["diagnostic_write_failed"] is False

rows = []
for frame in local["frames"] + local["synthetic"]:
    assert frame["status"] == "ANALYZED" and frame["same_frame_comparison"] is True
    assert frame["source_generation_unchanged"] and frame["original_png_bytes_unchanged"]
    assert frame["controls_used_as_model_input"] is False
    assert frame["regions_requested"] == frame["regions_accepted"] and not frame["regions_dropped_names"]
    assert frame["native_storage"]["native_payload_equal"] is True
    stats = frame["argb_readback"]
    assert stats["compared_pixels"] == frame["width"] * frame["height"]
    assert sum(stats[key] for key in ("alpha_only_pixels", "rgb_only_pixels", "alpha_and_rgb_pixels")) == stats["mismatch_pixels"]
    for channel in stats["channels"].values():
        assert sum(channel["histogram"].values()) == stats["compared_pixels"]
    controls = frame["diagnostic_controls"]
    assert controls["mutable_copy_before_retag"]["argb_mismatch_pixels"] == stats["mismatch_pixels"]
    assert controls["copy_retagged_with_source_java_color_space"]["argb_mismatch_pixels"] == 0
    assert controls["decode_preferred_source_color_space"]["argb_mismatch_pixels"] == 0
    rows.append({
        "case": frame["case_label"], "mismatch_pixels": stats["mismatch_pixels"],
        "max_abs_delta_a_r_g_b": [stats["channels"][c]["max_abs_delta"] for c in "ARGB"],
        "mismatch_bbox": stats["mismatch_bbox"],
        "native_pixel_bytes_equal": True, "retag_copy_mismatch_pixels": 0,
        "changed_regions": [{k: region[k] for k in ("name", "mismatch_pixels", "mismatch_bbox", "max_abs_rgb_delta", "source_edge_changed_pixels")}
                            for region in stats["regions"] if region["mismatch_pixels"]],
        "thresholds": frame["threshold_mask_proxies"]["luma_thresholds"],
        "threshold_flip_pixels": frame["threshold_mask_proxies"]["global_flip_pixels"],
        "probe_elapsed_ms": frame["probe_elapsed_ms"],
    })

path = root / "recognition"
events = [json.loads(line) for line in (path / "calc_recognition.events.jsonl").read_text(encoding="utf-8-sig").splitlines() if line.strip()]
schema = read(repo / "docs/data-contracts/minimal-event.v1.1.schema.json")
validator = jsonschema.Draft202012Validator(schema)
for index, event in enumerate(events, 1):
    validator.validate(event)
    assert event["seq"] == index and event["session_id"] == events[0]["session_id"]
assert events[0]["event"] == "session_started" and events[-1]["event"] == "session_ended"
starts, ends = {}, {}
for event in events:
    if event["event"] == "model_call":
        data = event["data"]
        key = (data["role"], data["phase"], data["call_index"])
        target = starts if data["status"] == "started" else ends
        assert key not in target
        target[key] = data
assert starts.keys() == ends.keys()
assert all(key[0] == "GUI" and key[1] == "ORIGINAL" for key in starts)
assert all(data["status"] == "succeeded" for data in ends.values())
assert not any(event["event"] in ("error", "control") for event in events)
actions = [event["data"] for event in events if event["event"] == "action"]
oracle, before = read(path / "calc_recognition.oracle.json"), read(path / "precondition.json")
assert before["formula_nonempty"] is True and oracle["task_achieved"] is True
end = events[-1]["data"]
assert len(starts) == end["gui_steps_used"] == 3 and end["planner_calls_used"] == 0
assert end["reason"] == "model_declared_complete" and end["completion_source"] == "gui"
assert Counter(action["action_type"] for action in actions) == {"launch_app": 1, "tap": 1, "finish": 1}
assert all(action["result"] == "completed" for action in actions)

summary = {
    "schema_version": "1.0.0", "app_version": local["app_version"], "apk_sha256": build["apk_sha256"],
    "status": "EXPLAINED_FOR_TESTED_COLOR_READBACK_PIPELINE", "local_frames": rows,
    "native_pixel_payload_loss_observed": False,
    "cause_supported": "COLOR_PROFILE_INTERPRETATION_DURING_SRGB_ARGB_READBACK",
    "exact_internal_icc_quantization_step_measured": False,
    "strict_default_argb_equality_still_fails_for_p3": True,
    "production_image_conversion_changed": False, "model_coordinates_corrected": False,
    "recognition": {"gui_calls": len(starts), "planner_calls": 0, "events_validated": len(events),
                    "original_png_pipeline_used": True, "clear_target_reached_independently": True,
                    "session_duration_ms": events[-1]["elapsed_ms"], "no_errors_or_retries": True},
    "universal_model_recognition_invariance_proven": False,
    "screenshots_or_raw_responses_persisted": False,
}
(root / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({k: v for k, v in summary.items() if k != "local_frames"}, ensure_ascii=False, indent=2))
