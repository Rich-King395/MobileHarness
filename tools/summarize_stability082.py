"""Offline validation of the bounded 0.8.2 stability batch; never contacts a phone.

Usage:
  python -B tools/summarize_stability082.py --prepare-only
  python -B tools/summarize_stability082.py

Preparation copies this run's 22 synthetic JVM XML reports and writes derived
unit-tests.json/source-delta.json. Full mode additionally requires every native
result plus before/after preservation snapshots, then writes summary.json.
Raw evidence and the sealed 0.8.1 baseline are never overwritten.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/acceptance/results/stability-0.8.2"
BASELINE = ROOT / "baselines/2026-11-03-mobileharness-0.8.1"
APP = "0.8.2-dev"
POLICY = "1.2.0"
EXPECTED_PREFIXES = {
    "BP05", "BP07", "BP08", "BP09", "BP10", "CT02", "CT07", "CT08",
    "BP13", "CT10", "CT04", "CT05", "BP06-D-VERIFY-MULTI-WAIT", "BP06-D-CONFIRM-MULTI-WAIT",
} | {f"BP11-D-P{p}-{branch}-{outcome}-NO-REPLAN"
     for p in (1, 2) for branch in ("EXECUTE", "FALLBACK") for outcome in ("COMPLETED", "UNABLE")}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def load(path):
    return json.loads(path.read_text("utf-8-sig"))


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_derived(path, data):
    require(path.parent == OUT, "DERIVED_OUTPUT_NOT_IN_BATCH")
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def safe_source(name):
    path = PurePosixPath(name)
    require(isinstance(name, str) and name and "\\" not in name and ":" not in name and
            not path.is_absolute() and ".." not in path.parts, "UNSAFE_SOURCE_PATH")
    result = ROOT.joinpath(*path.parts)
    require(result.resolve().is_relative_to(ROOT) and not result.is_symlink(), "SOURCE_OUTSIDE_WORKSPACE")
    return result


def source_delta(extra_sources):
    manifest = load(BASELINE / "source-files.json")
    baseline = {entry["path"]: entry for entry in manifest["files"]}
    require(len(baseline) == len(manifest["files"]), "DUPLICATE_BASELINE_SOURCE")
    tree_roots = ("android-app/app/src", "android-app/fixture/src", "prompts", "docs/data-contracts", "docs/runtime-policies")
    protected = ("android-app/app/src/main/", "android-app/fixture/src/main/", "prompts/", "docs/data-contracts/", "docs/runtime-policies/")
    current = set(baseline)
    for folder in tree_roots:
        current.update(p.relative_to(ROOT).as_posix() for p in (ROOT / folder).rglob("*")
                       if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc")
    delta = []
    unchanged = 0
    with zipfile.ZipFile(BASELINE / "source-snapshot.zip") as frozen:
        for name in sorted(current):
            path = safe_source(name)
            old = baseline.get(name)
            data = path.read_bytes() if path.is_file() else None
            digest = sha(data) if data is not None else None
            if old is not None and digest == old["sha256"]:
                unchanged += 1
                continue
            require(not name.startswith(protected), "PROTECTED_SOURCE_CHANGED:" + name)
            require(data is not None, "BASELINE_SOURCE_REMOVED:" + name)
            kind = "added" if old is None else "modified"
            category = "debug_or_test"
            if name == "android-app/app/build.gradle.kts":
                before = frozen.read(name).decode("utf-8-sig")
                after = data.decode("utf-8-sig")
                normalize = lambda text: re.sub(r'(versionName\s*=\s*)"[^"\r\n]+"', r'\1"VERSION"',
                                               re.sub(r"(versionCode\s*=\s*)\d+", r"\1VERSION", text))
                require(normalize(before) == normalize(after), "BUILD_CHANGE_EXCEEDS_VERSION")
                require('versionName = "0.8.2-dev"' in after and "versionCode = 20" in after, "BUILD_VERSION_MISMATCH")
                category = "build_version_only"
            else:
                require(name.startswith(("android-app/app/src/debug/", "android-app/app/src/test/", "android-app/app/src/androidTest/",
                                         "tools/", "docs/")), "UNEXPECTED_SOURCE_CHANGE:" + name)
                if name.startswith(("tools/", "docs/")):
                    category = "support_tool_or_document"
            delta.append({"path": name, "change": kind, "category": category,
                          "baseline_sha256": old["sha256"] if old else None, "current_sha256": digest})
    extras = []
    for name in sorted(set(extra_sources) | {"tools/summarize_stability082.py", "tools/run_stability_acceptance.py"}):
        require(name.startswith(("tools/", "docs/")) and not name.startswith("docs/acceptance/results/"), "EXTRA_SOURCE_NOT_SUPPORT_FILE")
        path = safe_source(name)
        require(path.is_file(), "EXTRA_SOURCE_MISSING")
        if name not in current:
            extras.append({"path": name, "sha256": sha(path.read_bytes()), "baseline_comparison": "NOT_IN_FROZEN_SOURCE_INDEX"})
    result = {"schema_version": "1.0.0", "status": "PASS", "baseline_id": manifest["baseline_id"],
              "comparison": "FROZEN_WORKSPACE_HASH_INDEX_NOT_GIT", "baseline_indexed_files": len(baseline),
              "unchanged_indexed_files": unchanged, "changes": delta, "additional_support_files": extras,
              "production_main_changed": False, "prompts_changed": False, "data_contracts_changed": False,
              "runtime_policies_changed": False, "build_changes_limited_to_version": True,
              "source_to_apk_byte_identical_rebuild_claimed": False}
    write_derived(OUT / "source-delta.json", result)
    return result


def unit_tests(unit_directory):
    archived = OUT / "unit-tests"
    if not archived.exists():
        reports = sorted(unit_directory.glob("TEST-*.xml"))
        require(len(reports) == 22, "EXPECTED_22_SOURCE_JVM_REPORTS")
        archived.mkdir()
        for report in reports:
            (archived / report.name).write_bytes(report.read_bytes())
    reports = sorted(archived.glob("TEST-*.xml"))
    require(len(reports) == 22 and len(list(archived.iterdir())) == 22, "EXPECTED_22_ARCHIVED_JVM_REPORTS")
    totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
    rows = []
    identities = set()
    for report in reports:
        raw = report.read_bytes()
        node = ET.fromstring(raw)
        require(node.tag == "testsuite" and node.attrib["timestamp"].startswith("2026-11-03"), "JVM_REPORT_NOT_THIS_RUN")
        counts = {key: int(node.attrib[key]) for key in totals}
        for key in totals:
            totals[key] += counts[key]
        cases = node.findall("testcase")
        require(len(cases) == counts["tests"], "JVM_CASE_TOTAL_MISMATCH")
        for case in cases:
            identity = (case.attrib["classname"], case.attrib["name"])
            require(identity not in identities, "DUPLICATE_JVM_METHOD")
            identities.add(identity)
            require(not any(case.find(tag) is not None for tag in ("failure", "error", "skipped")), "JVM_CASE_NOT_PASS")
        rows.append({"file": "unit-tests/" + report.name, "suite": node.attrib["name"],
                     "sha256": sha(raw), "timestamp": node.attrib["timestamp"], **counts})
    require(totals == {"tests": 220, "failures": 0, "errors": 0, "skipped": 0}, "JVM_TOTALS_NOT_EXPECTED")
    new = {"org.mobileharness.app.core.CoordinatorRecoveryHarnessAcceptanceTest": (12, 50),
           "org.mobileharness.app.core.CoordinatorLifecycleStabilityTest": (5, 13)}
    for name, (methods, _) in new.items():
        require(sum(row["tests"] for row in rows if row["suite"] == name) == methods, "NEW_JVM_SUITE_TOTAL_MISMATCH")
    result = {"schema_version": "1.0.0", "status": "PASS", "origin": "LOCAL_SYNTHETIC_JVM_NO_ANDROID_OR_NETWORK",
              "app_version": APP, "suites": 22, **totals, "new_junit_methods": 17,
              "new_parameter_trajectories": 63, "parameter_trajectories_are_additional_junit_methods": False,
              "new_suites": [{"suite": name, "junit_methods": methods, "parameter_trajectories": trajectories}
                             for name, (methods, trajectories) in new.items()], "reports": rows}
    write_derived(OUT / "unit-tests.json", result)
    return result


def all_true(flags, code):
    require(isinstance(flags, dict) and flags and all(value is True for value in flags.values()), code)


def ready(value):
    require(value["service_connected"] is True and value["controls_match_runtime"] is True and
            value["runtime_state"] in ("IDLE", "ENDED"), "DEVICE_CONTROLS_NOT_RESTORED")


def native(name, suite):
    path = OUT / name
    require(path.is_file(), "MISSING_NATIVE_EVIDENCE:" + name)
    value = load(path)
    require(value["suite"] == suite and value["fresh_result_observed"] is True, "NATIVE_RESULT_NOT_FRESH")
    require(value["collection_started_at_utc"].startswith("2026-11-03"), "NATIVE_RESULT_NOT_THIS_RUN")
    require(re.fullmatch(r"[0-9a-f]{64}", value["evidence_sha256"]) is not None, "INVALID_NATIVE_SOURCE_HASH")
    result = value["device_result"]
    require(result["status"] == "PASS" and result["app_version"] == APP, "NATIVE_RESULT_NOT_PASS_OR_VERSION")
    for key in ("gui_network_requests", "planner_network_requests") if suite != "notification" else ("gui_network_attempts", "planner_network_attempts"):
        require(type(result[key]) is int and result[key] == 0, "NATIVE_NETWORK_CALLS_NOT_ZERO")
    ready(value["post_readiness"])
    return result


def summary(units, delta):
    recovery = []
    for number in (1, 2, 3):
        result = native(f"recovery-round-{number}.json", "recovery")
        require(result["execution_policy_version"] == POLICY, "RECOVERY_POLICY_MISMATCH")
        all_true(result["flags"], "RECOVERY_SUITE_FLAG_FAILED")
        cases = result["cases"]
        require(len(cases) == 8 and {row["id"] for row in cases} == {f"R{i}" for i in range(1, 9)}, "RECOVERY_CASES_MISMATCH")
        for row in cases:
            require(row["passed"] is True and row["execution_policy_version"] == POLICY, "RECOVERY_CASE_FAILED")
            all_true(row["flags"], "RECOVERY_CASE_FLAG_FAILED")
        recovery.append({"round": number, "cases": 8, "elapsed_ms": result["elapsed_ms"]})
    harness_paths = sorted(OUT.glob("harness-*.json"))
    require(len(harness_paths) == 22, "EXPECTED_22_HARNESS_BATCHES")
    prefixes, ids = set(), set()
    for path in harness_paths:
        result = native(path.name, "harness")
        prefix = result["prefix"]
        require(prefix in EXPECTED_PREFIXES and prefix not in prefixes, "INVALID_OR_DUPLICATE_HARNESS_PREFIX")
        prefixes.add(prefix)
        require(result["controls_restored"] is True and result["native_terminal_confirmed"] is True, "HARNESS_NOT_RESTORED")
        require(result["cases"], "EMPTY_HARNESS_BATCH")
        for row in result["cases"]:
            require(row["status"] == "PASS" and row["id"].startswith(prefix), "HARNESS_VARIANT_FAILED")
            require(row["id"] not in ids and "-REJECT-" not in row["id"] and "-EXPLICIT-RETRY-" not in row["id"], "DUPLICATE_OR_LEGACY_HARNESS_VARIANT")
            ids.add(row["id"])
    require(prefixes == EXPECTED_PREFIXES and len(ids) == 81, "HARNESS_COVERAGE_NOT_81_UNIQUE_VARIANTS")
    notification = native("notification.json", "notification")
    require(len(notification["cases"]) == 2 and {row["variant"] for row in notification["cases"]} == {"ON", "OFF"}, "NOTIFICATION_VARIANTS_MISMATCH")
    for row in notification["cases"]:
        require(row["status"] == "PASS", "NOTIFICATION_CASE_FAILED")
        all_true(row["flags"], "NOTIFICATION_FLAG_FAILED")
    storage = native("storage.json", "storage")
    require(storage["metadata_origin"] == "SYNTHETIC_STORAGE_ONLY" and storage["screenshots"] == storage["phone_perform_calls"] == 0,
            "STORAGE_BOUNDARY_FAILED")
    all_true(storage["flags"], "STORAGE_FLAG_FAILED")
    before, after = load(OUT / "device-before.json"), load(OUT / "device-after.json")
    for value in (before, after):
        ready(value["readiness"])
        require(value["file_contents_read_on_host"] is False and value["keys_decrypted"] is False and value["screenshots_saved"] is False,
                "PRESERVATION_BOUNDARY_FAILED")
    require(before["file_hashes"] and before["file_hashes"] == after["file_hashes"] and before["file_counts"] == after["file_counts"], "USER_DATA_HASHES_CHANGED")
    require(before["file_counts"] == {"events": 18, "local-task-records": 18, "profiles": 1, "authorized-apps": 1}, "USER_DATA_COUNTS_MISMATCH")
    require(before["installed_apk_sha256"] == load(BASELINE / "baseline.json")["apk"]["sha256"], "BEFORE_APK_NOT_BASELINE")
    apk = ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk"
    require(after["installed_apk_sha256"] == sha(apk.read_bytes()), "INSTALLED_APK_DIFFERS_FROM_TEST_BUILD")
    ui = load(OUT / "device-ui.json")
    require(ui["status"] == "PASS" and ui["app_version"] == APP and
            ui["authorized_scope_24_readable"] is True and ui["encrypted_profile_badges"] == 2 and
            ui["phone_local_catalog_decryption_exercised_by_normal_ui"] is True and
            ui["profile_keys_returned_to_host"] is False and ui["profile_or_scope_written"] is False and
            ui["screenshots_saved"] is False and ui["raw_ui_saved_or_printed"] is False and ui["remote_requests"] == 0 and
            ui["service_connected"] is True and ui["controls_match_runtime"] is True and
            ui["runtime_state"] in ("IDLE", "ENDED"), "FINAL_PROFILE_AND_SCOPE_UI_NOT_ESTABLISHED")
    # These historical debug harnesses use SessionOptions' current default policy.
    contracts = (ROOT / "android-app/app/src/main/java/org/mobileharness/app/core/Contracts.kt").read_text("utf-8-sig")
    require("val automaticRecovery: Boolean = true" in contracts, "CURRENT_DEFAULT_POLICY_CHANGED")
    for filename in ("DeviceVerification.kt", "NotificationDeterministicVerification.kt"):
        source = (ROOT / "android-app/app/src/debug/java/org/mobileharness/app/verification" / filename).read_text("utf-8-sig")
        require(re.search(r"automaticRecovery\s*=\s*false", source) is None, "LEGACY_POLICY_IN_CURRENT_DEVICE_HARNESS")
    initial_review = load(OUT / "initial-candidate/review.json")
    initial_failure = load(OUT / "initial-candidate/harness-CT04.json")["device_result"]
    require(initial_review["classification"] == "DEBUG_TEST_CROSS_THREAD_OBSERVATION_RACE" and
            initial_review["assertion_relaxed"] is False and initial_review["production_coordinator_changed"] is False and
            initial_failure["status"] == "FAIL", "INITIAL_FAILURE_REVIEW_MISSING")
    label_review = load(OUT / "notification-label-candidate/review.json")
    runtime_artifact = load(OUT / "notification-label-candidate/artifact.json")
    runtime_delta = load(OUT / "notification-label-candidate/source-delta.json")
    old_changes = {row["path"]: row["current_sha256"] for row in runtime_delta["changes"]}
    new_changes = {row["path"]: row["current_sha256"] for row in delta["changes"]}
    label_source = "android-app/app/src/debug/java/org/mobileharness/app/verification/NotificationDeterministicVerification.kt"
    require(all(new_changes.get(path) == digest for path, digest in old_changes.items()) and
            set(new_changes) - set(old_changes) == {label_source}, "FINAL_APK_DELTA_EXCEEDS_NOTIFICATION_DIAGNOSTIC")
    with zipfile.ZipFile(BASELINE / "source-snapshot.zip") as frozen:
        before_label = frozen.read(label_source).decode("utf-8-sig").replace("\r\n", "\n")
    after_label = safe_source(label_source).read_text("utf-8-sig")
    require(before_label.replace("it.title.toString() == command.label", "it.title.toString() == command.localizedLabel(context)") == after_label,
            "NOTIFICATION_DIAGNOSTIC_CHANGE_EXCEEDS_LOCALIZED_SELECTOR")
    require(label_review["runtime_candidate_apk_sha256"] == runtime_artifact["apk_sha256"] and
            label_review["production_notification_or_control_receiver_changed"] is False and
            label_review["classification"] == "DEBUG_TEST_USED_FIXED_CHINESE_LABEL_IN_ENGLISH_UI", "NOTIFICATION_REVIEW_MISSING")
    evidence_paths = [*harness_paths, *(OUT / f"recovery-round-{i}.json" for i in (1, 2, 3)),
                      OUT / "notification.json", OUT / "storage.json", OUT / "device-before.json", OUT / "device-after.json", OUT / "device-ui.json",
                      OUT / "unit-tests.json", OUT / "source-delta.json",
                      OUT / "initial-candidate/review.json", OUT / "initial-candidate/harness-CT04.json",
                      OUT / "notification-label-candidate/review.json", OUT / "notification-label-candidate/notification.json",
                      OUT / "notification-label-candidate/artifact.json", OUT / "notification-label-candidate/source-delta.json", OUT / "artifact.json"]
    return {"schema_version": "1.0.0", "status": "PASS_FOR_RECORDED_BOUNDED_LAYERS",
            "generated_at_utc": datetime.now(timezone.utc).isoformat(), "app_version": APP, "version_code": 20,
            "apk_sha256": after["installed_apk_sha256"], "execution_policy_version": POLICY,
            "jvm": {key: units[key] for key in ("suites", "tests", "failures", "errors", "skipped", "new_junit_methods", "new_parameter_trajectories")},
            "native_recovery": {"unique_cases": 8, "rounds": recovery, "case_executions": 24},
            "native_harness": {"unique_device_variants": 81, "batches": 22, "variant_ids": sorted(ids),
                               "per_case_runtime_policy_field_available": False, "policy_evidence": "CURRENT_SOURCE_DEFAULT_AUTOMATIC_RECOVERY"},
            "tested_builds": {"harness_and_recovery_apk_sha256": runtime_artifact["apk_sha256"],
                              "installed_notification_storage_and_latest_jvm_apk_sha256": after["installed_apk_sha256"],
                              "only_later_apk_source_change": label_source,
                              "later_change": "DEBUG_NOTIFICATION_SELECTOR_LOCALIZATION_ONLY",
                              "shared_production_and_other_test_sources_hashes_equal": True,
                              "all_native_results_claimed_from_one_binary": False},
            "notification": {"variants": ["ON", "OFF"], "count": 2},
            "storage": {"passing_flags": len(storage["flags"]), "origin": "SYNTHETIC_STORAGE_ONLY"},
            "user_data_preservation": {"status": "PASS", "all_file_hashes_unchanged": True, "file_counts": before["file_counts"]},
            "phone_local_configuration_readable": {"encrypted_profiles": 2, "authorized_app_count": 24, "host_received_keys": False},
            "production_main_changed": delta["production_main_changed"], "prompts_changed": False,
            "data_contracts_changed": False, "runtime_policies_changed": False,
            "real_remote_gui_calls": 0, "real_remote_planner_calls": 0,
            "initial_failure": {"classification": initial_review["classification"], "original_evidence_retained": True,
                                "production_fix_required": False, "final_ct04_variants_passed": 4},
            "evidence_origin": "SYNTHETIC_LOCAL_MODELS_WITH_REAL_ANDROID_SERVICE_FIXTURE_AND_STORAGE",
            "counting_boundary": "JVM_METHODS_PARAMETER_TRAJECTORIES_NATIVE_VARIANTS_REPEATED_EXECUTIONS_AND_FLAGS_ARE_SEPARATE_UNITS",
            "bounds": {"only_device": "Google Pixel 4 / Android 13 API 33", "cross_day_soak_completed": False,
                       "multi_device_completed": False, "release_build_validated": False, "real_model_ability_evaluated": False,
                       "actual_os_service_restart_or_lock_stress_completed": False, "process_death_or_reboot_during_active_task_completed": False,
                       "all_37_original_scenarios_all_platform_variants_current_policy_claimed": False},
            "evidence_files": [{"path": path.relative_to(OUT).as_posix(), "sha256": sha(path.read_bytes())} for path in evidence_paths]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--unit-xml-dir", type=Path, default=ROOT / "android-app/app/build/test-results/testDebugUnitTest")
    parser.add_argument("--extra-source", action="append", default=[], help="Additional tools/docs source path, relative to workspace")
    args = parser.parse_args()
    require(OUT.is_dir(), "STABILITY_BATCH_MISSING")
    units = unit_tests(args.unit_xml_dir)
    delta = source_delta(args.extra_source)
    if args.prepare_only:
        print(json.dumps({"status": "PREPARED", "jvm_methods": units["tests"], "source_changes": len(delta["changes"]), "summary_written": False}))
        return 0
    result = summary(units, delta)
    write_derived(OUT / "summary.json", result)
    print(json.dumps({"status": result["status"], "jvm_methods": units["tests"], "native_harness_variants": 81,
                      "recovery_case_executions": 24, "notification_variants": 2, "real_remote_calls": 0}))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, OSError, ET.ParseError, zipfile.BadZipFile) as failure:
        # Never dump arbitrary input documents, user values, or a Python traceback.
        message = str(failure) if isinstance(failure, ValueError) else "EVIDENCE_VALIDATION_FAILED"
        print(json.dumps({"status": "FAIL", "code": message}), file=sys.stderr)
        raise SystemExit(1)
