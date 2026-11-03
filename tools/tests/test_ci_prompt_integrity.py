import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("ci_prompt_integrity", ROOT / "tools/ci/check_prompt_integrity.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class PromptIntegrityCiTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copytree(ROOT / "prompts", self.root / "prompts")

        def copy_references(value, manifest):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    relative = Path(value["path"])
                    source = ROOT / relative
                    if not source.is_file():
                        source = manifest.parent / relative
                    target = self.root / source.relative_to(ROOT)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(source, target)
                for child in value.values():
                    copy_references(child, manifest)
            elif isinstance(value, list):
                for child in value:
                    copy_references(child, manifest)

        for version in TOOL.BASELINE.FROZEN_MANIFEST_SHA256:
            manifest = ROOT / "prompts" / version / "manifest.json"
            copy_references(json.loads(manifest.read_text(encoding="utf-8-sig")), manifest)

    def fails(self, expected, operation, *arguments):
        with self.assertRaises(TOOL.BASELINE.VerificationError) as raised:
            operation(*arguments)
        self.assertEqual(expected, str(raised.exception))

    def symlink(self, link, target, directory=False):
        try:
            link.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError):
            self.skipTest("This environment cannot create symlinks")

    def test_real_frozen_snapshots_validate_without_android_sdk(self):
        result = TOOL.verify(self.root)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(3, result["frozen_manifests"])
        self.assertEqual(59, result["frozen_references"])
        self.assertEqual(32, result["runtime_prompt_assets"])

    def test_modified_manifest_is_rejected_before_new_references_are_followed(self):
        manifest = self.root / "prompts/v1.2.0/manifest.json"
        data = json.loads(manifest.read_text(encoding="utf-8"))
        data["files"][0]["path"] = "../outside"
        manifest.write_text(json.dumps(data), encoding="utf-8")
        self.fails("FROZEN_PROMPT_MANIFEST_CHANGED", TOOL.verify, self.root)

    def test_modified_prompt_content_is_rejected(self):
        (self.root / "prompts/v1.2.0/gui/system.txt").write_bytes(b"modified prompt")
        self.fails("FROZEN_PROMPT_REFERENCE_CHANGED", TOOL.verify, self.root)

    def test_line_ending_conversion_changes_the_frozen_bytes(self):
        path = self.root / "prompts/v1.1.0/gui/system.txt"
        original = path.read_bytes()
        alternate = original.replace(b"\r\n", b"\n")
        if alternate == original:
            alternate = original.replace(b"\n", b"\r\n")
        self.assertNotEqual(original, alternate)
        path.write_bytes(alternate)
        self.fails("FROZEN_PROMPT_REFERENCE_CHANGED", TOOL.verify, self.root)

    def test_missing_reference_is_rejected(self):
        (self.root / "prompts/v1.2.0/gui/system.txt").unlink()
        self.fails("PROMPT_REFERENCE_MISSING", TOOL.verify, self.root)

    def test_changed_research_handoff_source_is_rejected(self):
        (self.root / "HandoffHarness_APK_Handoff/HANDOFF_ANDROID_APK.md").write_bytes(b"edited source snapshot")
        self.fails("FROZEN_PROMPT_REFERENCE_CHANGED", TOOL.verify, self.root)

    def test_changed_validation_supplement_is_rejected(self):
        (self.root / "prompts/v1.2.0/VALIDATION.md").write_bytes(b"edited validation")
        self.fails("FROZEN_PROMPT_SUPPLEMENT_CHANGED", TOOL.verify, self.root)

    def test_undeclared_files_are_rejected_in_archived_and_runtime_bundles(self):
        for version in ("v1.0.0", "v1.1.0", "v1.2.0"):
            with self.subTest(version=version):
                extra = self.root / "prompts" / version / "unexpected.txt"
                extra.write_bytes(b"undeclared")
                self.fails("UNDECLARED_PROMPT_SOURCE_FILE", TOOL.verify, self.root)
                extra.unlink()

    def test_reference_paths_reject_traversal_absolute_windows_and_null_forms(self):
        for value in ("../outside", "/outside", "C:/outside", "a\\b", "a/./b", "a//b", "a\x00b"):
            with self.subTest(path=value):
                self.fails("UNSAFE_ARCHIVE_PATH", TOOL.safe_relative_path, value)

    def test_manifest_symlink_is_rejected_even_for_identical_bytes(self):
        manifest = self.root / "prompts/v1.2.0/manifest.json"
        target = self.root / "manifest-copy.json"
        target.write_bytes(manifest.read_bytes())
        manifest.unlink()
        self.symlink(manifest, target)
        self.fails("PROMPT_PATH_REDIRECTION", TOOL.verify, self.root)

    def test_reference_symlink_is_rejected_even_for_identical_bytes(self):
        prompt = self.root / "prompts/v1.2.0/gui/system.txt"
        target = self.root / "prompt-copy.txt"
        target.write_bytes(prompt.read_bytes())
        prompt.unlink()
        self.symlink(prompt, target)
        self.fails("PROMPT_PATH_REDIRECTION", TOOL.verify, self.root)

    def test_symlink_directory_is_rejected_before_reading_children(self):
        directory = self.root / "prompts/v1.2.0/gui"
        target = self.root / "gui-copy"
        directory.rename(target)
        self.symlink(directory, target, directory=True)
        self.fails("PROMPT_PATH_REDIRECTION", TOOL.verify, self.root)

    def test_cli_reports_closed_errors_without_prompt_or_private_path(self):
        sensitive = "private/path and raw prompt text"
        with mock.patch.object(TOOL, "verify", side_effect=RuntimeError(sensitive)):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = TOOL.main(["--project-root", str(self.root)])
        self.assertEqual(1, result)
        self.assertNotIn(sensitive, output.getvalue())
        self.assertEqual({"status": "FAIL", "reason": "PROMPT_INPUT_OR_VALIDATOR_ERROR"}, json.loads(output.getvalue()))


if __name__ == "__main__":
    unittest.main()
