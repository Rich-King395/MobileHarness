"""Validate and archive this bounded acceptance batch offline. No phone or network access."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/acceptance/results/experiment-analysis-0.9.0"


def require(value, code):
    if not value:
        raise ValueError(code)


def load(path):
    return json.loads(path.read_text("utf-8-sig"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def native(name):
    value = load(OUT / name)
    result = value["device_result"]
    require(value["fresh_result_observed"] and result["status"] == "PASS" and result["app_version"] == "0.9.0-dev", "NATIVE_RESULT_NOT_PASS")
    require(result["gui_network_requests"] == result["planner_network_requests"] == 0, "NETWORK_REQUEST_NOT_ZERO")
    require(value["post_readiness"]["controls_match_runtime"] and value["post_readiness"]["service_connected"], "NATIVE_CONTROLS_NOT_RESTORED")
    require(result["flags"] and all(item is True for item in result["flags"].values()), "NATIVE_FLAG_FAILED")
    return result


def main():
    before, after = load(OUT / "device-before.json"), load(OUT / "device-after.json")
    require(before["file_hashes"] and before["file_hashes"] == after["file_hashes"], "EXISTING_USER_FILES_CHANGED")
    require(before["file_counts"] == after["file_counts"], "EXISTING_USER_FILE_COUNTS_CHANGED")
    require(after["readiness"]["runtime_state"] in ("IDLE", "ENDED") and after["readiness"]["controls_match_runtime"] and
            after["readiness"]["service_connected"], "FINAL_READINESS_FAILED")
    apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
    require(sha(apk) == after["installed_apk_sha256"], "INSTALLED_APK_MISMATCH")
    storage, recovery = native("storage-final.json"), native("recovery-final.json")
    require(len(storage["flags"]) == 43, "STORAGE_FLAGS_NOT_43")
    require(len(recovery["cases"]) == 8 and recovery["execution_policy_version"] == "1.2.0", "RECOVERY_CASES_MISMATCH")
    require(all(row["passed"] and all(v is True for v in row["flags"].values()) for row in recovery["cases"]), "RECOVERY_CASE_FAILED")
    ui, real_ui = load(OUT / "ui-attempt1.json"), load(OUT / "device-ui.json")
    require(ui["status"] == "PASS" and len(ui["flags"]) == 10 and all(v is True for v in ui["flags"].values()), "UI_CHECK_FAILED")
    require(ui["gui_network_requests"] == ui["planner_network_requests"] == ui["screenshots_saved"] == 0 and
            ui["user_records_modified"] is False and ui["user_language_preference_changed"] is False and ui["keys_read"] is False, "UI_BOUNDARY_CHANGED")
    require(real_ui["status"] == "PASS" and real_ui["encrypted_profile_badges"] == 2 and real_ui["authorized_scope_24_readable"], "CONFIGURATION_NOT_READABLE")

    baseline = load(ROOT / "baselines/2026-11-03-mobileharness-0.8.1/source-files.json")
    protected = [row for row in baseline["files"] if row["path"].startswith((
        "android-app/app/src/main/java/org/mobileharness/app/core/", "android-app/app/src/main/java/org/mobileharness/app/model/",
        "android-app/app/src/main/java/org/mobileharness/app/platform/", "prompts/", "docs/data-contracts/", "docs/runtime-policies/"))]
    require(len(protected) == 79 and all(sha(ROOT / row["path"]) == row["sha256"] for row in protected), "RUNTIME_OR_FROZEN_CONTRACT_CHANGED")

    target = OUT / "unit-tests"
    target.mkdir(exist_ok=True)
    reports = sorted((ROOT / "android-app/app/build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
    require(len(reports) == 23, "UNIT_SUITE_COUNT_MISMATCH")
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    archived = []
    for path in reports:
        suite = ET.parse(path).getroot()
        require(suite.attrib["timestamp"].startswith("2026-11-03"), "UNIT_REPORT_NOT_THIS_RUN")
        for key in totals:
            totals[key] += int(suite.attrib[key])
        copy = target / path.name
        if copy.exists():
            require(copy.read_bytes() == path.read_bytes(), "ARCHIVED_UNIT_REPORT_DIFFERS")
        else:
            copy.write_bytes(path.read_bytes())
        archived.append({"path": copy.relative_to(OUT).as_posix(), "sha256": sha(copy), "tests": int(suite.attrib["tests"])})
    require(totals == {"tests": 236, "failures": 0, "errors": 0, "skipped": 0}, "UNIT_TESTS_NOT_PASS")
    write("unit-tests.json", {"status": "PASS", "suites": 23, **totals, "new_analysis_methods": 16, "reports": archived})
    python = subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "tools/tests", "-p", "test_analyze_experiment_csv.py"],
                            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", timeout=30)
    require(python.returncode == 0 and re.search(r"Ran 10 tests", python.stdout + python.stderr), "CSV_TOOL_TESTS_NOT_PASS")
    write("csv-tool-tests.json", {"status": "PASS", "tests": 10, "dependencies": "PYTHON_STANDARD_LIBRARY_ONLY", "network_requests": 0})

    references = ["unit-tests.json", "csv-tool-tests.json", "storage-final.json", "recovery-final.json", "ui-attempt1.json", "device-before.json", "device-after.json", "device-ui.json"]
    result = {"status": "PASS_FOR_RECORDED_SCOPE", "generated_at_utc": datetime.now(timezone.utc).isoformat(),
              "app_version": "0.9.0-dev", "version_code": 21, "apk_sha256": sha(apk), "apk_bytes": apk.stat().st_size,
              "jvm": {"suites": 23, **totals, "new_analysis_methods": 16}, "csv_tool_tests": 10,
              "native_storage_flags": 43, "native_recovery_cases": 8, "synthetic_ui_flags": 10,
              "protected_runtime_model_platform_and_contract_files_unchanged": 79,
              "event_schema_changed": False, "analysis_format_version": "1.0.0", "analysis_csv_columns": 45,
              "user_file_counts": after["file_counts"], "existing_user_files_hashes_unchanged": True,
              "configuration_readable": {"encrypted_profiles": 2, "authorized_apps": 24},
              "real_gui_requests": 0, "real_planner_requests": 0, "user_screenshots_saved": 0,
              "bounds": {"only_device": "Pixel 4 / Android 13 API 33", "real_user_records_assessed_or_exported": False,
                         "system_saf_picker_complete_flow_tested": False, "researcher_verified_success": False,
                         "cross_day_soak_tested": False, "causal_harness_effect_claimed": False},
              "evidence": [{"path": name, "sha256": sha(OUT / name)} for name in references]}
    write("summary.json", result)
    print(json.dumps({"status": result["status"], "jvm": 236, "storage_flags": 43, "ui_flags": 10, "csv_tool_tests": 10,
                      "user_records_preserved": after["file_counts"]["events"]}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, ET.ParseError):
        print(json.dumps({"status": "FAIL", "code": "ACCEPTANCE_EVIDENCE_NOT_ESTABLISHED"}))
        raise SystemExit(1)
