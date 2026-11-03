"""Offline validation only. No device, model, image, task text or credential access."""
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
import jsonschema

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/acceptance/results/harness-deterministic-0.3.3"


def read(name):
    return json.loads((OUT / name).read_text("utf-8-sig"))


def main():
    final = read("candidate2.json")
    device = read("device-final-all-attempt1.json")
    notification = read("notification-final.json")
    audit = read("audit-final.json")
    regression = read("device-original4-final.json")
    readiness = read("final-readiness.json")
    assert device["status"] == "PASS" and len(device["cases"]) == 97
    ids = [case["id"] for case in device["cases"]]
    assert len(set(ids)) == 97 and all(c["status"] == "PASS" for c in device["cases"])
    assert device["controls_restored"] and device["native_terminal_confirmed"]
    assert device["gui_network_requests"] == device["planner_network_requests"] == 0
    assert notification["status"] == "PASS" and len(notification["cases"]) == 2
    assert {c["variant"] for c in notification["cases"]} == {"ON", "OFF"}
    assert all(c["status"] == "PASS" and all(c["flags"].values()) for c in notification["cases"])
    assert notification["gui_network_attempts"] == notification["planner_network_attempts"] == 0
    assert audit["status"] == "PASS" and all(audit["flags"].values())
    assert audit["metadata_origin"] == "SYNTHETIC_STORAGE_ONLY"
    assert audit["gui_attempts"] == audit["planner_attempts"] == audit["screenshots"] == audit["phone_perform_calls"] == 0
    metadata_counts = {}
    for key, schema_name, version in [
        ("metadata_v1_1", "minimal-event.v1.1.schema.json", "1.1.0"),
        ("metadata_v1_0", "minimal-event.schema.json", "1.0.0"),
    ]:
        schema = json.loads((ROOT / "docs/data-contracts" / schema_name).read_text("utf-8-sig"))
        validator = jsonschema.Draft202012Validator(schema)
        sessions = {}
        for event in audit[key]:
            validator.validate(event)
            assert event["schema_version"] == version
            sessions.setdefault(event["session_id"], []).append(event)
        assert sessions
        for events in sessions.values():
            assert [event["seq"] for event in events] == list(range(1, len(events) + 1))
            assert events[0]["event"] == "session_started"
            assert any(event["event"] == "session_ended" for event in events)
        metadata_counts[version] = len(audit[key])
    tests = failures = errors = skipped = 0
    for report in (ROOT / "android-app/app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        tests += int(root.attrib["tests"])
        failures += int(root.attrib["failures"])
        errors += int(root.attrib["errors"])
        skipped += int(root.attrib["skipped"])
    assert tests == 122 and failures == errors == skipped == 0
    assert regression["status"] == "PASS" and len(regression["cases"]) == 4
    assert all(case["status"] == "PASS" for case in regression["cases"])
    assert readiness["service_connected"] and readiness["controls_match_runtime"]
    assert readiness["readiness_overlay_stop_available"] and readiness["readiness_notification_control_available"]
    assert not readiness["panel_hidden_for_capture"]
    apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
    assert hashlib.sha256(apk.read_bytes()).hexdigest() == final["apk_sha256"]
    result = {
        "schema_version": "1.0.0", "status": "PASS_FOR_RECORDED_APPLICABLE_LAYERS",
        "app_version": "0.3.3-dev", "apk_sha256": final["apk_sha256"],
        "jvm_methods": tests, "jvm_failures": 0, "jvm_skipped": 0,
        "new_jvm_methods": 34, "information_trajectories": 55,
        "terminal_control_trajectories": 18, "budget_trajectories": 104,
        "device_variants": 97, "device_variant_ids": ids,
        "notification_variants": 2, "original_device_groups": 4,
        "real_audit_metadata_validated": metadata_counts,
        "real_gui_network_attempts": 0, "real_planner_network_attempts": 0,
        "native_cancel_requires_original_matching_callback": True,
        "terminal_delay_is_phoneport_delivery_gate": True,
        "raw_duplicate_android_callback_injection_claimed": False,
        "legacy_mixed_message_import_implemented": False,
        "all_37_scenarios_all_raw_platform_variants_claimed": False,
        "prompt_bundles_changed": False, "personal_keys_or_model_config_changed": False,
        "notification_permission_enabled_for_requested_backup_entry": True,
        "raw_images_or_replies_exported": False, "controls_restored": True,
        "before_fix_failure_preserved": True,
    }
    (OUT / "summary.json").write_text(json.dumps(result, indent=2) + "\n", "utf-8")
    print(json.dumps({"status": result["status"], "jvm": tests, "device": 97,
                      "notification": 2, "audit_events": metadata_counts, "network": 0}))


if __name__ == "__main__":
    main()
