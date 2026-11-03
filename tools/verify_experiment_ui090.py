"""Bounded interaction checks of the synthetic record fixture only. No raw UI is saved.

Usage: python -B tools/verify_experiment_ui090.py docs/acceptance/results/experiment-analysis-0.9.0/ui-attempt1.json
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from run_stability_acceptance import adb, readiness

ROOT = Path(__file__).resolve().parents[1]
BATCH = ROOT / "docs/acceptance/results/experiment-analysis-0.9.0"
CLI = r"D:\Android\Sdk\cmdline-tools\latest\bin\android.exe"
FIXTURE = "org.mobileharness.app/.DebugRecordUiActivity"
BOUND = re.compile(r"^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$")


class ProbeFailure(Exception):
    pass


def fail(code):
    raise ProbeFailure(code)


def resources(language):
    directory = ROOT / "android-app/app/src/main/res" / ("values-zh" if language == "zh" else "values")
    return {node.attrib["name"]: "".join(node.itertext()) for path in directory.glob("*.xml")
            for node in ET.parse(path).getroot() if node.tag == "string"}


def text_resource(table, key, *args):
    return re.sub(r"%(\d+)\$[ds]", lambda m: str(args[int(m.group(1)) - 1]), table[key])


def fixed_text(value):
    return re.sub(r"\s+", " ", value.replace("\u00a0", " ")).strip()


class FixtureUi:
    def __init__(self):
        self.bounds = None
        self.layouts = 0
        self.actions = 0

    def guard(self):
        state = readiness()
        if (state.get("runtime_state") not in ("IDLE", "ENDED") or not state.get("controls_match_runtime")
                or not state.get("readiness_unlocked")):
            fail("ACTUAL_RUNTIME_NOT_IDLE_OR_UNLOCKED")
        top = adb("shell", "dumpsys", "activity", "activities")
        if not re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*org\.mobileharness\.app/\.DebugRecordUiActivity\b", top):
            fail("SYNTHETIC_ACTIVITY_NOT_FOREGROUND")

    def layout(self):
        self.guard()
        environment = dict(os.environ, JAVA_HOME=r"D:\Android\Jdks\jdk-17.0.20.1+1",
                           JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8")
        result = subprocess.run([CLI, "--no-metrics", "layout"], capture_output=True, text=True,
                                encoding="utf-8", errors="strict", timeout=25, env=environment)
        if result.returncode:
            fail("HOST_CLI_LAYOUT_UNAVAILABLE")
        try:
            tree = json.loads(result.stdout)
        except ValueError:
            fail("HOST_CLI_LAYOUT_NOT_JSON")
        windows = tree if isinstance(tree, list) else [tree]
        applications = [window for window in windows if isinstance(window, dict) and window.get("type") == "APPLICATION"]
        if len(applications) != 1:
            fail("SYNTHETIC_APPLICATION_WINDOW_NOT_UNIQUE")
        window = applications[0]
        bounds = BOUND.fullmatch(window.get("bounds", ""))
        if not bounds:
            fail("SYNTHETIC_WINDOW_BOUNDS_UNAVAILABLE")
        self.bounds = tuple(map(int, bounds.groups()))
        nodes = []

        def visit(value):
            if isinstance(value, list):
                for child in value:
                    visit(child)
            elif isinstance(value, dict):
                if isinstance(value.get("text"), str) and not value.get("off-screen") and not value.get("hidden"):
                    match = BOUND.fullmatch(value.get("bounds", ""))
                    if match:
                        rectangle = tuple(map(int, match.groups()))
                        if rectangle[2] > rectangle[0] and rectangle[3] > rectangle[1]:
                            nodes.append({"text": fixed_text(value["text"]), "bounds": rectangle})
                visit(value.get("children"))

        visit(window.get("content"))
        self.layouts += 1
        return nodes

    def tap(self, node):
        self.guard()
        x1, y1, x2, y2 = node["bounds"]
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        self.actions += 1

    def scroll(self, direction):
        self.guard()
        if not self.bounds:
            fail("SCROLL_REQUIRES_OBSERVED_BOUNDS")
        x1, y1, x2, y2 = self.bounds
        x = (x1 + x2) // 2
        upper, lower = int(y1 + (y2 - y1) * .30), int(y1 + (y2 - y1) * .82)
        start, end = (lower, upper) if direction == "down" else (upper, lower)
        adb("shell", "input", "swipe", str(x), str(start), str(x), str(end), "350")
        self.actions += 1

    def find(self, expected, direction="down", limit=4):
        expected = fixed_text(expected)
        for ordinal in range(limit + 1):
            found = [node for node in self.layout() if node["text"] == expected]
            if found:
                return min(found, key=lambda node: node["bounds"][1])
            if ordinal < limit:
                self.scroll(direction)
        fail("SYNTHETIC_FIXED_LABEL_NOT_ESTABLISHED")


def run(ui, flags):
    en, zh = resources("en"), resources("zh")
    state = readiness()
    if state.get("runtime_state") not in ("IDLE", "ENDED") or not state.get("controls_match_runtime") or not state.get("readiness_unlocked"):
        fail("ACTUAL_RUNTIME_NOT_IDLE_OR_UNLOCKED")
    adb("shell", "am", "start", "-n", FIXTURE)
    ui.find(en["analysis_title"], limit=0)
    ui.find("50%", limit=0)
    ui.find(text_resource(en, "analysis_completion_denominator", 1, 2), limit=0)
    ui.find(text_resource(en, "analysis_samples", 2), limit=0)
    flags["english_two_rated_records_half_complete"] = True
    flags["english_mean_samples_visible"] = True

    ui.tap(ui.find(en["analysis_compare"], limit=1))
    ui.find(en["analysis_filter_on"] + " · " + text_resource(en, "analysis_group_runs", 1), limit=2)
    ui.find(en["analysis_filter_off"] + " · " + text_resource(en, "analysis_group_runs", 1), limit=2)
    flags["harness_on_off_descriptive_comparison"] = True
    ui.tap(ui.find(en["analysis_hide_comparison"], direction="up", limit=3))

    ui.find(text_resource(en, "record_gui_usage", "7", "30"), limit=4)
    ui.tap(ui.find(en["record_details"], limit=2))
    ui.tap(ui.find(en["ui_assessment_complete"], limit=3))
    ui.find("100%", direction="up", limit=6)
    ui.find(text_resource(en, "analysis_completion_denominator", 2, 2), limit=0)
    flags["synthetic_assessment_updates_analysis_to_all_complete"] = True

    ui.find(text_resource(en, "analysis_gui_requests", 7), limit=5)
    ui.find("HTTP_429", limit=3)
    ui.find(text_resource(en, "record_planner_call_status", 1, en["record_call_succeeded"]), limit=4)
    ui.find(text_resource(en, "record_planner_call_status", 2, en["record_call_api_error"]), limit=2)
    flags["gui_attempts_and_two_planner_calls_visible"] = True
    flags["fixed_service_http_reason_visible"] = True
    ui.find(en["record_task_missing"], limit=4)
    flags["missing_original_task_stays_missing"] = True

    ui.tap(ui.find("简体中文", direction="up", limit=8))
    ui.find(zh["analysis_title"], limit=0)
    ui.find(zh["analysis_user_completion"], limit=0)
    ui.find("100%", limit=0)
    ui.find(text_resource(zh, "analysis_completion_denominator", 2, 2), limit=0)
    flags["chinese_analysis_labels_and_state_preserved"] = True
    ui.find(text_resource(zh, "record_gui_usage", "7", "30"), limit=4)
    ui.find(text_resource(zh, "analysis_result", zh["analysis_complete"]), limit=1)
    flags["chinese_record_result_separate_from_run_end"] = True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    target = args.output.resolve()
    if not target.is_relative_to(BATCH.resolve()) or target.suffix != ".json":
        print(json.dumps({"status": "NOT_ESTABLISHED", "code": "OUTPUT_OUTSIDE_BATCH"}))
        return 1
    if target.exists():
        print(json.dumps({"status": "NOT_ESTABLISHED", "code": "EVIDENCE_ALREADY_EXISTS"}))
        return 1
    ui, flags = FixtureUi(), {}
    status, code = "NOT_ESTABLISHED", "SYNTHETIC_UI_CHECK_INCOMPLETE"
    try:
        run(ui, flags)
        status, code = "PASS", "SYNTHETIC_RECORD_ANALYSIS_UI_CONFIRMED"
    except ProbeFailure as failure:
        code = str(failure)
    except (RuntimeError, subprocess.SubprocessError, OSError, ValueError, KeyError, TypeError):
        code = "HOST_UI_PROBE_UNAVAILABLE"
    finally:
        try:
            adb("shell", "am", "force-stop", "com.android.cli.interact.instrumentation")
            state = readiness()
            if state.get("runtime_state") in ("IDLE", "ENDED") and state.get("controls_match_runtime") and state.get("readiness_unlocked"):
                adb("shell", "am", "start", "-f", "0x24000000", "-n", "org.mobileharness.app/.MainActivity")
                flags["restored_main_without_reading_real_records"] = True
        except (RuntimeError, subprocess.SubprocessError, OSError, ValueError):
            status, code = "NOT_ESTABLISHED", "CLEANUP_NOT_ESTABLISHED"
    result = {"status": status, "code": code, "metadata_origin": "SYNTHETIC_RECORD_UI_ONLY",
              "flags": flags, "layout_reads": ui.layouts, "synthetic_input_calls": ui.actions,
              "gui_network_requests": 0, "planner_network_requests": 0, "screenshots_saved": 0,
              "raw_ui_saved_or_printed": False, "user_records_modified": False,
              "user_language_preference_changed": False, "keys_read": False,
              "failed_probe_is_not_apk_failure": True}
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"status": status, "code": code, "passing_flags": len(flags)}))
    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
