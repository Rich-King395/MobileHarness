import contextlib
import csv
import importlib.util
import io
import json
import re
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("analysis_tool", Path(__file__).resolve().parents[1] / "analyze_experiment_csv.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class ExperimentCsvTests(unittest.TestCase):
    def test_reader_contract_matches_android_writer_and_closed_error_vocabularies(self):
        project = Path(__file__).resolve().parents[2]
        source = project / "android-app/app/src/main/java/org/mobileharness/app"
        writer = (source / "storage/ExperimentAnalysis.kt").read_text("utf-8-sig")
        literals = re.findall(r'"([^"\n]+)"', writer.split("private val columns = ", 1)[1])
        roles = literals.index("gui")
        template = literals.index("${role}_$it")
        expected = literals[:roles] + [f"{role}_{metric}" for role in literals[roles:roles + 2]
                                      for metric in literals[roles + 2:template]] + literals[template + 1:]
        self.assertEqual(expected, TOOL.COLUMNS)
        protocol = (source / "model/ProtocolDiagnostics.kt").read_text("utf-8-sig")
        body = protocol.split("enum class ProtocolReason {", 1)[1].split("}", 1)[0]
        self.assertEqual(set(re.findall(r"\b[A-Z][A-Z0-9_]+\b", body)) - {"VALID"}, TOOL.PROTOCOL)
        execution = (source / "core/ExecutionFailureReason.kt").read_text("utf-8-sig")
        self.assertEqual(set(re.findall(r"^\s+([A-Z_]+)\((?:true|false),", execution, re.M)), TOOL.EXECUTION)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def row(self, index=1, **values):
        row = dict.fromkeys(TOOL.COLUMNS, "")
        row.update(analysis_version="1.0.0", session_id=f"00000000-0000-4000-8000-{index:012d}", harness_enabled="true",
                   end_reason="model_declared_complete", completion_source="gui")
        row.update(values)
        return row

    def file(self, name, rows, columns=None):
        path = self.root / name
        with path.open("w", encoding="utf-8", newline="") as output:
            writer = csv.DictWriter(output, columns or TOOL.COLUMNS)
            writer.writeheader()
            writer.writerows(rows)
        return path

    def test_rated_denominator_does_not_use_model_finish_as_success(self):
        path = self.file("one.csv", [self.row(user_assessment="user_reported_complete"), self.row(2),
                                    self.row(3, user_assessment="user_reported_incomplete")])
        group = TOOL.analyze([path])["groups"]["ON"]
        self.assertEqual((3, 2, 1, 1), (group["ended_runs"], group["rated"], group["user_completed"], group["unrated"]))
        self.assertEqual(0.5, group["completion_rate"])

    def test_later_row_and_file_replace_snapshot_not_add_tasks(self):
        one = self.file("one.csv", [self.row(user_assessment="user_reported_complete"), self.row(user_assessment="user_reported_incomplete")])
        two = self.file("two.csv", [self.row()])  # A newer reset to unknown must win.
        result = TOOL.analyze([one, two])
        self.assertEqual((1, 3, 2), (result["ended_runs"], result["input_rows"], result["replaced_snapshots"]))
        self.assertEqual(0, result["groups"]["ON"]["rated"])
        self.assertIsNone(result["groups"]["ON"]["completion_rate"])

    def test_missing_values_do_not_become_zero_and_known_zero_is_sample(self):
        path = self.file("one.csv", [self.row(gui_steps="0", duration_ms="0", model_error_events="0"), self.row(2)])
        group = TOOL.analyze([path])["groups"]["ON"]
        self.assertEqual({"value": 0.0, "samples": 1}, group["mean_gui_steps"])
        self.assertEqual({"value": 0.0, "samples": 1}, group["mean_duration_ms"])
        self.assertEqual({"recorded_total": 0, "samples": 1}, group["error_events"]["model"])
        self.assertEqual({"recorded_total": None, "samples": 0}, group["error_events"]["device"])

    def test_unknown_harness_and_unknown_end_reason_remain_eligible_unknown(self):
        path = self.file("one.csv", [self.row(harness_enabled="", end_reason="")])
        result = TOOL.analyze([path])
        self.assertEqual(1, result["groups"]["UNKNOWN"]["ended_runs"])
        self.assertEqual(0, result["groups"]["ON"]["ended_runs"])

    def test_interrupted_duration_is_not_actual_termination_time(self):
        path = self.file("one.csv", [self.row(end_reason="interrupted", duration_ms="99999", recorded_duration_ms="99999")])
        self.assertEqual({"value": None, "samples": 0}, TOOL.analyze([path])["groups"]["ON"]["mean_duration_ms"])

    def test_error_and_runtime_failure_counts_are_distinct(self):
        path = self.file("one.csv", [self.row(end_reason="runtime_failure", model_error_events="2", device_error_events="1",
                                             error_summary="MODEL/API_ERROR/HTTP_401:2;DEVICE/INPUT_FAILED/GESTURE_CANCELLED:1")])
        group = TOOL.analyze([path])["groups"]["ON"]
        self.assertEqual(1, group["runtime_failure_runs"])
        self.assertEqual(2, group["error_events"]["model"]["recorded_total"])
        self.assertEqual(0, group["rated"])

    def test_extra_private_column_and_missing_columns_rejected_without_contents(self):
        for name, columns, row in [("extra.csv", TOOL.COLUMNS + ["task"], self.row(task="PRIVATE_TASK")),
                                   ("missing.csv", TOOL.COLUMNS[:-1], {k: v for k, v in self.row().items() if k != TOOL.COLUMNS[-1]})]:
            path = self.file(name, [row], columns)
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(2, TOOL.main([str(path)]))
            self.assertEqual("", out.getvalue())
            self.assertEqual("INVALID_COLUMNS", json.loads(err.getvalue())["code"])
            self.assertNotIn("PRIVATE", err.getvalue())

    def test_formula_and_private_detail_values_rejected_without_leak(self):
        for number, values in enumerate([{"gui_steps": "=PRIVATE_FORMULA"}, {"gui_profile_id": "PRIVATE_KEY"},
                                          {"error_summary": "MODEL/API_ERROR/PRIVATE_RESPONSE:1"},
                                          {"error_summary": "MODEL/API_ERROR/HTTP_600:1"}], 1):
            path = self.file(f"bad{number}.csv", [self.row(**values)])
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(2, TOOL.main([str(path)]))
            self.assertNotIn("PRIVATE", out.getvalue() + err.getvalue())
            self.assertEqual("INVALID_CSV", json.loads(err.getvalue())["code"])

    def test_output_unknown_cells_empty_and_existing_files_never_overwritten(self):
        path = self.file("one.csv", [self.row()])
        summary = TOOL.analyze([path])
        target = self.root / "analysis"
        TOOL.write_outputs(target, summary)
        original = (target / "summary.json").read_bytes()
        with (target / "summary.csv").open(newline="", encoding="utf-8") as source:
            row = next(csv.DictReader(source))
        self.assertEqual("", row["completion_rate"])
        self.assertEqual("", row["mean_gui_steps"])
        with self.assertRaisesRegex(TOOL.AnalysisError, "OUTPUT_EXISTS"):
            TOOL.write_outputs(target, summary)
        self.assertEqual(original, (target / "summary.json").read_bytes())


if __name__ == "__main__":
    unittest.main()
