import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("channel_verifier", Path(__file__).resolve().parents[1] / "verify_release_channels.py")
TOOL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOL)


class ReleaseChannelVerificationTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.assets = {"assets/prompts/v1.1.0/gui/system.txt": b"immutable prompt"}

    def manifest(self, channel="release", main=TOOL.MAIN_CLASS, extra="", debuggable=None):
        package = TOOL.RELEASE_ID if channel == "release" else TOOL.DEVELOPMENT_ID
        debug = channel == "debug" if debuggable is None else debuggable
        return f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="{package}" android:versionCode="22" android:versionName="0.10.0">
            <application android:name="org.mobileharness.app.HarnessApplication"
                android:allowBackup="false" android:usesCleartextTraffic="false"
                android:debuggable="{str(debug).lower()}">
                <activity android:name="{main}" android:exported="true"><intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                </intent-filter></activity>
                <service android:name="{TOOL.SERVICE_CLASS}" android:exported="true"
                    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"/>
                <receiver android:name="{TOOL.CONTROL_CLASS}" android:exported="false"/>
                <provider android:name="androidx.startup.InitializationProvider"
                    android:authorities="{package}.androidx-startup" android:exported="false"/>
                {extra}
            </application></manifest>'''

    def apk(self, extra=None, replace=None, duplicate=False):
        path = self.root / "test.apk"
        contents = {"AndroidManifest.xml": b"compiled manifest", "classes.dex": b"Lorg/mobileharness/app/MainActivity;",
                    **self.assets, **(extra or {}), **(replace or {})}
        with zipfile.ZipFile(path, "w") as output:
            for name, value in contents.items():
                output.writestr(name, value)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    output.writestr("classes.dex", b"duplicate")
        return path

    def fails(self, code, function, *arguments):
        with self.assertRaises(TOOL.VerificationError) as raised:
            function(*arguments)
        self.assertEqual(code, str(raised.exception))

    def test_release_main_class_keeps_namespace_when_application_id_differs(self):
        result = TOOL.inspect_manifest(self.manifest(), "release")
        self.assertEqual(TOOL.RELEASE_ID, result["application_id"])
        self.assertEqual(TOOL.MAIN_CLASS, result["main_activity"])
        self.assertFalse(result["debuggable"])
        self.fails("MAIN_ACTIVITY_CLASS_MISMATCH", TOOL.inspect_manifest, self.manifest(main=".MainActivity"), "release")
        self.fails("MAIN_ACTIVITY_CLASS_MISMATCH", TOOL.inspect_manifest,
                   self.manifest(main="org.mobileharness.app.release.MainActivity"), "release")

    def test_release_rejects_debuggability_and_debug_entry_points(self):
        self.fails("DEBUGGABLE_FLAG_MISMATCH", TOOL.inspect_manifest, self.manifest(debuggable=True), "release")
        self.fails("DEBUG_COMPONENT_IN_RELEASE", TOOL.inspect_manifest,
                   self.manifest(extra='<activity android:name="org.mobileharness.app.DebugVerificationActivity"/>'), "release")

    def test_development_id_remains_compatible_with_existing_installation(self):
        xml = self.manifest("debug", extra='<activity android:name="org.mobileharness.app.DebugVerificationActivity"/>')
        result = TOOL.inspect_manifest(xml, "debug")
        self.assertEqual(TOOL.DEVELOPMENT_ID, result["application_id"])
        self.assertTrue(result["debuggable"])

    def test_control_receiver_and_startup_provider_remain_private_and_channel_local(self):
        xml = self.manifest().replace(f'{TOOL.CONTROL_CLASS}" android:exported="false"',
                                      f'{TOOL.CONTROL_CLASS}" android:exported="true"')
        self.fails("CONTROL_RECEIVER_EXPORTED", TOOL.inspect_manifest, xml, "release")
        xml = self.manifest().replace(f'{TOOL.RELEASE_ID}.androidx-startup', f'{TOOL.DEVELOPMENT_ID}.androidx-startup')
        self.fails("PROVIDER_AUTHORITY_CHANNEL_MISMATCH", TOOL.inspect_manifest, xml, "release")

    def test_bilingual_channel_labels_are_checked_in_the_built_artifact(self):
        badging = "application-label:'MobileHarness (Release)'\napplication-label-zh:'MobileHarness（正式版）'\n" + \
                  f"launchable-activity: name='{TOOL.MAIN_CLASS}' label=''\n"
        self.assertEqual("MobileHarness（正式版）", TOOL.inspect_badging(badging, "release")["chinese"])
        self.fails("CHANNEL_LABEL_MISMATCH", TOOL.inspect_badging,
                   badging.replace("正式版", "开发版"), "release")

    def test_public_fingerprint_must_match_exactly_one_verified_signer(self):
        fingerprint = "ab" * 32
        signer = f"Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: {fingerprint}\n"
        self.assertEqual(fingerprint, TOOL.inspect_signer(signer, ":".join(["AB"] * 32)))
        self.fails("SIGNING_CERTIFICATE_MISMATCH", TOOL.inspect_signer, signer, "cd" * 32)
        self.fails("UNEXPECTED_SIGNER_COUNT", TOOL.inspect_signer,
                   signer + f"Signer #2 certificate SHA-256 digest: {fingerprint}\n", fingerprint)
        self.fails("MODERN_SIGNATURE_SCHEME_MISSING", TOOL.inspect_signer, signer.replace(": true", ": false"), fingerprint)

    def test_prompt_bytes_are_compared_and_extra_assets_are_rejected(self):
        result = TOOL.inspect_archive(self.apk(), "release", self.assets)
        self.assertTrue(result["runtime_prompts_byte_identical"])
        self.fails("RUNTIME_PROMPT_ASSET_CHANGED", TOOL.inspect_archive,
                   self.apk(replace={next(iter(self.assets)): b"modified"}), "release", self.assets)
        self.fails("UNEXPECTED_BUNDLED_ASSET", TOOL.inspect_archive,
                   self.apk(extra={"assets/task-records.json": b"private data"}), "release", self.assets)

    def test_compiler_baseline_profiles_are_permitted_without_widening_asset_allowlist(self):
        result = TOOL.inspect_archive(self.apk(extra={"assets/dexopt/baseline.prof": b"profile"}), "release", self.assets)
        self.assertTrue(result["asset_allowlist_matched"])
        self.fails("UNEXPECTED_BUNDLED_ASSET", TOOL.inspect_archive,
                   self.apk(extra={"assets/dexopt/other.json": b"not a baseline profile"}), "release", self.assets)

    def test_release_rejects_debug_and_fixture_code_even_without_manifest_entries(self):
        self.fails("DEBUG_CODE_IN_RELEASE", TOOL.inspect_archive,
                   self.apk(replace={"classes.dex": b"Lorg/mobileharness/app/verification/DeviceVerification;"}), "release", self.assets)
        self.fails("FIXTURE_CODE_IN_APP", TOOL.inspect_archive,
                   self.apk(replace={"classes.dex": b"Lorg/mobileharness/fixture/MainActivity;"}), "release", self.assets)

    def test_archive_traversal_duplicates_and_signing_material_are_rejected(self):
        self.fails("UNSAFE_ARCHIVE_PATH", TOOL.inspect_archive, self.apk(extra={"../escape": b"data"}), "release", self.assets)
        self.fails("DUPLICATE_APK_ENTRY", TOOL.inspect_archive, self.apk(duplicate=True), "release", self.assets)
        self.fails("FORBIDDEN_APK_FILE", TOOL.inspect_archive, self.apk(extra={"release.p12": b"data"}), "release", self.assets)
        self.fails("BUNDLED_SIGNING_CONFIGURATION", TOOL.inspect_archive,
                   self.apk(extra={"signing.properties": b"configuration"}), "release", self.assets)

    def test_recognizable_credential_patterns_are_detected_in_ascii_and_utf16(self):
        synthetic = "sk-" + "x" * 30
        for data in (synthetic.encode(), synthetic.encode("utf-16le"), synthetic.encode("utf-16be")):
            with self.subTest(encoding=data[:6]):
                self.fails("KNOWN_SECRET_LITERAL_IN_APK", TOOL.inspect_archive,
                           self.apk(replace={"classes.dex": data}), "release", self.assets)

    def test_existing_frozen_prompt_manifests_and_references_validate(self):
        assets, references = TOOL.verify_prompt_sources(Path(__file__).resolve().parents[2])
        self.assertEqual(32, len(assets))
        self.assertEqual(59, references)
        self.assertIn("assets/prompts/v1.2.0/gui/system.txt", assets)

    def test_cli_does_not_echo_arbitrary_tool_errors_or_private_paths(self):
        sensitive = "sensitive tool diagnostic and private path"
        with mock.patch.object(TOOL, "verify", side_effect=RuntimeError(sensitive)):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                exit_code = TOOL.main(["--sdk", str(self.root), "--release-cert-sha256", "ab" * 32])
        self.assertEqual(1, exit_code)
        self.assertNotIn(sensitive, output.getvalue())
        self.assertEqual({"status": "FAIL", "reason": "VERIFICATION_INPUT_OR_TOOL_ERROR"}, json.loads(output.getvalue()))


if __name__ == "__main__":
    unittest.main()
