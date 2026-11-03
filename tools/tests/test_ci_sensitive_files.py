import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


SPEC = importlib.util.spec_from_file_location("sensitive_check", Path(__file__).resolve().parents[1] / "ci/check_sensitive_files.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class SensitiveFilesTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="mobileharness-sensitive-ci-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        template = self.root / "empty-template"
        template.mkdir()
        self.git("init", "--quiet", "--template=" + str(template))

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments], capture_output=True, check=True)

    def track(self, name, contents=b"safe\n"):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(contents.encode("utf-8") if isinstance(contents, str) else contents)
        self.git("add", "--force", "--", name)
        return file

    def codes(self):
        return {row["code"] for row in TOOL.check(self.root)["findings"]}

    def test_normal_sources_frozen_examples_and_wrapper_remain_allowed(self):
        for name in ("README.md", "android-app/gradle/wrapper/gradle-wrapper.jar",
                     "android-app/app/src/debug/AndroidManifest.xml", "android-app/app/src/test/Test.kt",
                     "prompts/v1.0.0/manifest.json", "docs/data-contracts/minimal-event.examples.json",
                     "docs/publishing/SIGNING_AND_CHANNELS.md", ".github/workflows/android-ci.yml"):
            self.track(name)
        result = TOOL.check(self.root)
        self.assertEqual("PASS", result["status"])
        self.assertEqual(8, result["scanned_files"])

    def test_ignored_files_forced_into_git_are_rejected(self):
        self.track(".gitignore", "*.p12\n.env\n**/build/\n")
        for name in ("mobileharness-release.p12", ".env", "android-app/app/build/outputs/app.apk",
                     "signing.dpapi.json", "backup/mobileharness.signing-recovery.json"):
            self.track(name)
        result = TOOL.check(self.root)
        self.assertEqual("FAIL", result["status"])
        self.assertEqual(5, len(result["findings"]))
        self.assertEqual({"FORBIDDEN_TRACKED_FILE"}, {row["code"] for row in result["findings"]})

    def test_private_data_paths_and_artifacts_are_rejected(self):
        for name in ("docs/acceptance/results/session.json", "docs/previews/screenshot.png",
                     "docs/local-archive/file.md", "baselines/source.zip", "private/config.json",
                     "exports/summary.csv", "screenshots/frame.png", "local-analysis-run/output.json",
                     "android-app/no_backup/profiles/catalog.enc", "android-app/local.properties",
                     "secrets/provider.json", "source/device.log", "capture.perfetto-trace"):
            self.track(name)
        self.assertEqual({"FORBIDDEN_TRACKED_FILE"}, self.codes())

    def test_recognizable_tokens_and_private_keys_are_rejected_without_echo(self):
        tokens = ["sk-" + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8",
                  "ghp_" + "A7b3C9d5E1f8G4h2I6j0K9l3M5n7O1p8Q4r2",
                  "AKIA" + "A1B2C3D4E5F6G7H8",
                  "AIza" + "A7b3C9d5E1f8G4h2I6j0K9l3M5n7O1p8Q4r",
                  "Bearer " + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8",
                  "-----" + "BEGIN PRIVATE KEY-----"]
        self.track("source.txt", "\n".join(tokens))
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, TOOL.main(["--project-root", str(self.root)]))
        payload = output.getvalue()
        self.assertEqual("FAIL", json.loads(payload)["status"])
        for token in tokens:
            self.assertNotIn(token, payload)
        self.assertNotIn(str(self.root), payload)
        self.assertGreaterEqual(len(json.loads(payload)["findings"]), 6)

    def test_token_in_filename_is_redacted(self):
        token = "sk-" + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8"
        self.track(token + ".p12")
        self.track("source.txt", token)
        result = TOOL.check(self.root)
        # Even findings for filename-associated content do not echo the token.
        encoded = json.dumps(result)
        self.assertNotIn(token, encoded)
        self.assertIn("[REDACTED_PATH]", encoded)

    def test_token_in_filename_is_rejected_even_with_safe_contents(self):
        token = "sk-" + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8"
        self.track(token + ".txt")
        result = TOOL.check(self.root)
        self.assertEqual("FAIL", result["status"])
        self.assertIn("SECRET_IN_TRACKED_PATH", {row["code"] for row in result["findings"]})
        self.assertNotIn(token, json.dumps(result))

    def test_utf16_secrets_are_detected(self):
        token = "sk-" + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8"
        for encoding in ("utf-16-le", "utf-16-be"):
            self.track(encoding + ".txt", token.encode(encoding))
        self.assertEqual({"API_TOKEN_LITERAL"}, self.codes())

    def test_literal_password_and_api_key_assignments_are_rejected(self):
        example_password = "LongEnough" + "Password42!"
        example_key = "ProviderSecret" + "WithoutKnownPrefix42"
        self.track("source.kt", 'val storePassword = "' + example_password + '"\nval apiKey = "' + example_key + '"\n')
        self.track("settings.properties", "keyPassword" + "=" + example_password + "\n")
        result = TOOL.check(self.root)
        self.assertEqual(3, len(result["findings"]))
        self.assertEqual({"HARDCODED_CREDENTIAL_LITERAL"}, self.codes())
        self.assertNotIn("LongEnoughPassword42", json.dumps(result))

    def test_examples_synthetic_values_and_variable_assignments_do_not_fail(self):
        self.track("source.kt", '\n'.join([
            'val apiKey = "<example-provider-key>"', 'val apiKey = ""',
            'val password = "<your-password>"', 'storePassword = System.getenv("RELEASE_STORE_PASSWORD")',
            'keyPassword = credentials.password', 'val apiKey = "YOUR_API_KEY"',
            'val password = "YOUR_PASSWORD"', 'val apiKey = "${MODEL_API_KEY}"',
            'val profile = ModelProfile(apiKey = "key-a:12345", password = "<test-password>")']))
        self.track(".env.example", "API_KEY=<your-api-key>\n")
        self.assertEqual("PASS", TOOL.check(self.root)["status"])

    def test_example_env_files_cannot_hide_unquoted_real_shaped_credentials(self):
        self.track(".env.example", "API_" + "KEY=" + "ProviderSecret" + "WithoutKnownPrefix42\n")
        self.assertIn("HARDCODED_CREDENTIAL_LITERAL", self.codes())

    def test_existing_synthetic_exceptions_are_exact_and_file_bound(self):
        project = Path(__file__).resolve().parents[2]
        for name, expected in TOOL.KNOWN_SYNTHETIC_API_LITERALS.items():
            data = (project / name).read_bytes()
            actual = {hashlib.sha256(match.group("value").encode("utf-8")).hexdigest()
                      for match in TOOL.QUOTED_CREDENTIAL.finditer(data.decode("utf-8"))
                      if match.group("name").lower() == "apikey"}
            self.assertTrue(expected <= actual)
            self.track(name, data)
        self.assertEqual("PASS", TOOL.check(self.root)["status"])

    def test_synthetic_prefix_does_not_exempt_new_credential_literals(self):
        name = next(iter(TOOL.KNOWN_SYNTHETIC_API_LITERALS))
        self.track(name, 'val apiKey = "' + "synthetic-" + "NewOpaqueProviderCredential42" + '"\n')
        self.assertIn("HARDCODED_CREDENTIAL_LITERAL", self.codes())

    def test_known_synthetic_value_outside_its_original_file_is_not_exempt(self):
        name, allowed = next(iter(TOOL.KNOWN_SYNTHETIC_API_LITERALS.items()))
        source = (Path(__file__).resolve().parents[2] / name).read_text(encoding="utf-8")
        value = next(match.group("value") for match in TOOL.QUOTED_CREDENTIAL.finditer(source)
                     if hashlib.sha256(match.group("value").encode("utf-8")).hexdigest() in allowed)
        self.track("source.kt", 'val apiKey = "' + value + '"\n')
        self.assertIn("HARDCODED_CREDENTIAL_LITERAL", self.codes())

    def test_test_files_do_not_get_blanket_secret_exemption(self):
        self.track("tools/tests/test_example.py", "value = '" + "sk-" + "Q7w2E9r4T8y6U1i3O5p0A2s4D6f8'\n")
        self.assertIn("API_TOKEN_LITERAL", self.codes())

    def test_missing_tracked_file_fails_closed(self):
        file = self.track("source.txt")
        file.unlink()
        self.assertEqual({"UNREADABLE_TRACKED_FILE"}, self.codes())

    def test_tracked_symlink_mode_is_rejected_without_following_target(self):
        self.track("source.txt")
        blob = self.git("hash-object", "-w", "--stdin").stdout.strip().decode("ascii")
        self.git("update-index", "--add", "--cacheinfo", "120000," + blob + ",link")
        self.assertIn("UNSUPPORTED_TRACKED_ENTRY", self.codes())

    def test_non_git_root_and_subdirectory_errors_do_not_echo_absolute_paths(self):
        self.track("source.txt")
        folder = self.root / "subfolder"
        folder.mkdir()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(1, TOOL.main(["--project-root", str(folder)]))
        payload = output.getvalue()
        self.assertNotIn(str(self.root), payload)
        self.assertEqual("PROJECT_ROOT_MUST_BE_GIT_ROOT", json.loads(payload)["code"])

    def test_oversized_tracked_files_fail_instead_of_skipping(self):
        file = self.track("source.txt")
        with file.open("wb") as stream:
            stream.truncate(TOOL.MAX_FILE_BYTES + 1)
        self.assertIn("TRACKED_FILE_TOO_LARGE_TO_SCAN", self.codes())

    def test_path_validation_rejects_escapes_and_control_characters(self):
        for path in ("../secret.txt", "/absolute.txt", "D:/secret.txt", "source\\secret", "file\nsecret"):
            self.assertFalse(TOOL.valid_path(path))
            self.assertEqual("[REDACTED_PATH]", TOOL.safe_path(path))


if __name__ == "__main__":
    unittest.main()
