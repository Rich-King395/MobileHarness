import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("release_assets", Path(__file__).resolve().parents[1] / "prepare_release_assets.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class ReleaseAssetPreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "--quiet", str(self.root)], check=True, capture_output=True)
        (self.root / ".gitignore").write_text("/release-output/\n*.apk\n", encoding="utf-8")
        self.apk = self.root / "verified.apk"
        self.apk.write_bytes(b"synthetic APK bytes already verified by external SDK")
        self.report_file = self.root / "verification.json"
        self.output = self.root / "release-output/v0.10.0-beta.1"
        self.report = {
            "status": "PASS", "frozen_prompt_manifests": 3, "frozen_prompt_references": 59,
            "channels": {"debug": {"private_path": "not-for-publication"}, "release": {
                "application_id": TOOL.RELEASE_ID, "version_name": "0.10.0-beta.1", "version_code": "22",
                "debuggable": False, "debug_components": 0, "certificate_sha256": TOOL.RELEASE_CERT_SHA256,
                "backup_disabled": True, "private_control_receiver": True, "channel_provider_authorities": True,
                "apk_bytes": self.apk.stat().st_size, "apk_sha256": hashlib.sha256(self.apk.read_bytes()).hexdigest(),
                "archive": {name: True for name in (
                    "runtime_prompts_byte_identical", "asset_allowlist_matched", "known_secret_literals_absent",
                    "fixture_code_absent", "debug_code_absent")},
            }},
        }

    def prepare(self, **kwargs):
        self.report_file.write_text(json.dumps(self.report), encoding="utf-8")
        args = {"verification": self.report_file, "apk": self.apk, "commit": "a" * 40,
                "tag": "v0.10.0-beta.1", "output": self.output, "project_root": self.root}
        args.update(kwargs)
        return TOOL.prepare(**args)

    def rejected(self, code, **kwargs):
        with self.assertRaisesRegex(TOOL.PreparationError, "^" + code + "$"):
            self.prepare(**kwargs)
        self.assertFalse(self.output.exists())

    def test_exact_asset_set_and_checksums_match_public_bytes(self):
        result = self.prepare()
        self.assertEqual("PASS", result["status"])
        expected = {"MobileHarness-0.10.0-beta.1-release.apk", "SHA256SUMS.txt", "RELEASE-METADATA.json"}
        self.assertEqual(expected, {p.name for p in self.output.iterdir()})
        self.assertEqual(self.apk.read_bytes(), (self.output / result["asset_names"][0]).read_bytes())
        sums = (self.output / "SHA256SUMS.txt").read_text("ascii").splitlines()
        self.assertEqual(2, len(sums))
        for line in sums:
            sha, name = line.split("  ")
            self.assertEqual(sha, hashlib.sha256((self.output / name).read_bytes()).hexdigest())
        metadata = json.loads((self.output / "RELEASE-METADATA.json").read_text("utf-8"))
        self.assertFalse(metadata["validation_scope"]["release_model_task_and_handoff_verified"])
        self.assertFalse(metadata["validation_scope"]["release_install_and_startup_verified"])
        self.assertTrue(metadata["validation_scope"]["prior_release_channel_install_and_startup_verified"])
        self.assertEqual(TOOL.OBSERVED_APK_SHA256, metadata["validation_scope"]["prior_test_apk_sha256"])

    def test_extra_private_report_fields_never_enter_assets(self):
        marker = "private-detail-not-for-distribution"
        self.report["private"] = {"task": marker, "path": marker}
        self.report["channels"]["release"]["api_key"] = marker
        self.report["channels"]["release"]["labels"] = {"english": marker}
        self.report["channels"]["debug"]["private_path"] = marker
        result = self.prepare()
        for name in result["asset_names"]:
            self.assertNotIn(marker.encode(), (self.output / name).read_bytes())

    def test_tampered_apk_rejected_before_output_is_created(self):
        self.apk.write_bytes(self.apk.read_bytes() + b"changed")
        self.rejected("RELEASE_APK_DIGEST_MISMATCH")

    def test_wrong_size_and_digest_are_each_rejected(self):
        original = copy.deepcopy(self.report)
        for field, value in [("apk_bytes", True), ("apk_bytes", 0), ("apk_sha256", "0" * 64)]:
            self.report = copy.deepcopy(original)
            self.report["channels"]["release"][field] = value
            self.rejected("RELEASE_APK_DIGEST_MISMATCH")

    def test_wrong_identity_certificate_version_and_debug_bits_rejected(self):
        original = copy.deepcopy(self.report)
        cases = [("application_id", "org.mobileharness.app", "RELEASE_IDENTITY_MISMATCH"),
                 ("certificate_sha256", "b" * 64, "RELEASE_CERTIFICATE_MISMATCH"),
                 ("version_name", "0.11.0", "RELEASE_VERSION_MISMATCH"),
                 ("version_code", "23", "RELEASE_VERSION_MISMATCH"),
                 ("debuggable", True, "RELEASE_DEBUG_CODE_PRESENT"),
                 ("debug_components", True, "RELEASE_DEBUG_CODE_PRESENT"),
                 ("debug_components", 1, "RELEASE_DEBUG_CODE_PRESENT")]
        for field, value, code in cases:
            self.report = copy.deepcopy(original)
            self.report["channels"]["release"][field] = value
            self.rejected(code)

    def test_missing_archive_component_or_prompt_checks_rejected(self):
        original = copy.deepcopy(self.report)
        self.report["channels"]["release"]["archive"]["debug_code_absent"] = False
        self.rejected("RELEASE_ARCHIVE_VERIFICATION_MISSING")
        self.report = copy.deepcopy(original)
        self.report["channels"]["release"]["private_control_receiver"] = False
        self.rejected("RELEASE_COMPONENT_VERIFICATION_MISSING")
        self.report = copy.deepcopy(original)
        self.report["frozen_prompt_references"] = 58
        self.rejected("PROMPT_VERIFICATION_MISSING")

    def test_failed_or_missing_verification_rejected(self):
        self.report["status"] = "FAIL"
        self.rejected("VERIFICATION_NOT_PASS")
        self.report["status"] = "PASS"
        del self.report["channels"]["release"]
        self.rejected("RELEASE_IDENTITY_MISMATCH")

    def test_existing_output_refused_without_replacing_any_bytes(self):
        self.prepare()
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        with self.assertRaisesRegex(TOOL.PreparationError, "^OUTPUT_ALREADY_EXISTS$"):
            self.prepare()
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})

    def test_nonignored_directory_rejected_even_though_apk_extension_is_ignored(self):
        output = self.root / "ordinary-public-source"
        self.rejected("OUTPUT_MUST_BE_IGNORED_AND_UNTRACKED", output=output)
        self.assertFalse(output.exists())

    def test_outside_project_directory_and_tracked_output_refused(self):
        self.rejected("OUTPUT_MUST_BE_INSIDE_PROJECT", output=self.root.parent / "outside-release")
        self.output.mkdir(parents=True)
        file = self.output / "tracked.txt"
        file.write_text("source", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "-f", "--", str(file)], check=True, capture_output=True)
        with self.assertRaisesRegex(TOOL.PreparationError, "^OUTPUT_MUST_BE_IGNORED_AND_UNTRACKED$"):
            self.prepare()
        self.assertEqual("source", file.read_text("utf-8"))

    def test_source_commit_tag_and_parameters_are_validated(self):
        for commit in ("a" * 7, "a" * 39, "not-a-commit", "A" * 40):
            self.rejected("INVALID_SOURCE_COMMIT", commit=commit)
        for tag in ("v../escape", "0.10.0-beta.1", "v0.10.0;command", "v0.10.0-beta.1/escape"):
            self.rejected("INVALID_SOURCE_TAG", tag=tag)
        self.rejected("INVALID_RELEASE_PARAMETERS", expected_version_code=True)
        self.rejected("INVALID_RELEASE_PARAMETERS", minimum_api=29)

    def test_future_release_does_not_inherit_current_device_startup_claim(self):
        self.report["channels"]["release"].update(version_name="0.11.0-beta.1", version_code="23")
        self.prepare(tag="v0.11.0-beta.1", expected_version_code=23)
        metadata = json.loads((self.output / "RELEASE-METADATA.json").read_text("utf-8"))
        self.assertFalse(metadata["validation_scope"]["release_install_and_startup_verified"])
        self.assertFalse(metadata["validation_scope"]["prior_release_channel_install_and_startup_verified"])
        self.assertIsNone(metadata["validation_scope"]["device"])
        self.assertIsNone(metadata["validation_scope"]["prior_test_apk_sha256"])

    def test_rebuilt_apk_same_version_cannot_inherit_exact_device_startup_claim(self):
        # A valid SDK verification report for different bytes is not device
        # evidence, even when the app ID, signing identity and version match.
        self.apk.write_bytes(b"different rebuilt synthetic release APK")
        release = self.report["channels"]["release"]
        release["apk_bytes"] = self.apk.stat().st_size
        release["apk_sha256"] = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.prepare()
        metadata = json.loads((self.output / "RELEASE-METADATA.json").read_text("utf-8"))
        scope = metadata["validation_scope"]
        self.assertEqual("0.10.0-beta.1", metadata["version_name"])
        self.assertFalse(scope["release_install_and_startup_verified"])
        self.assertTrue(scope["prior_release_channel_install_and_startup_verified"])
        self.assertNotEqual(metadata["apk_sha256"], scope["prior_test_apk_sha256"])

    def test_symlink_input_or_output_refused(self):
        alias = self.root / "alias.apk"
        try:
            alias.symlink_to(self.apk)
        except OSError:
            self.skipTest("Symlinks are unavailable on this host")
        self.rejected("PATH_REDIRECTION", apk=alias)
        real = self.root / "real-output"
        real.mkdir()
        linked = self.root / "linked-output"
        linked.symlink_to(real, target_is_directory=True)
        self.rejected("PATH_REDIRECTION", output=linked / "new-release")

    def test_cli_failure_does_not_echo_private_paths_or_report_contents(self):
        self.report_file.write_text("malformed-private-content", encoding="utf-8")
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = TOOL.main(["--verification", str(self.report_file), "--apk", str(self.apk),
                              "--commit", "a" * 40, "--tag", "v0.10.0-beta.1",
                              "--project-root", str(self.root), "--output", str(self.output)])
        self.assertEqual(1, code)
        self.assertNotIn(str(self.root), output.getvalue())
        self.assertNotIn("malformed-private-content", output.getvalue())
        self.assertEqual("RELEASE_INPUT_OR_OUTPUT_ERROR", json.loads(output.getvalue())["reason"])


if __name__ == "__main__":
    unittest.main()
