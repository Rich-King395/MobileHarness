"""Prepare an allowlisted, offline GitHub release asset set from a verified APK.

This consumes a completed SDK verification report; it does not independently
verify an APK signature, build, publish, access a device or read credentials.
Only the APK and explicitly selected public metadata leave the local report.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
RELEASE_ID = "org.mobileharness.app.release"
RELEASE_CERT_SHA256 = "f06391df26dc56a209ffa93a77280c2237c0eb7c3caa27d923771da197e385c6"
OBSERVED_VERSION = "0.10.0-beta.1"
OBSERVED_VERSION_CODE = 22
OBSERVED_APK_SHA256 = "eea55c34412220522fca765fb9af4ecb71e0d37d21688a383434589e1a0a69bc"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-(?:alpha|beta|rc)\.[0-9]+)?")


class PreparationError(Exception):
    """Only fixed, non-sensitive codes are displayed."""


def require(condition, code):
    if not condition:
        raise PreparationError(code)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def nonredirected(path, *, regular_file=False):
    # Check lexical ancestors before resolve() can hide a symlink or junction.
    path = Path(os.path.abspath(path))
    for candidate in reversed((path, *path.parents)):
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(info.st_mode) and not bool(
            getattr(info, "st_file_attributes", 0) & 0x400), "PATH_REDIRECTION")
    if regular_file:
        require(path.is_file(), "RELEASE_INPUT_MISSING")
    return path


def ignored_output(root, output):
    root = nonredirected(root)
    require(root.is_dir(), "INVALID_PROJECT_ROOT")
    output = nonredirected(output)
    require(output != root and output.is_relative_to(root), "OUTPUT_MUST_BE_INSIDE_PROJECT")
    relative = output.relative_to(root).as_posix()
    environment = os.environ.copy()
    for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY",
                 "GIT_ALTERNATE_OBJECT_DIRECTORIES"):
        environment.pop(name, None)
    try:
        result = subprocess.run(["git", "-C", str(root), "check-ignore", "--no-index", "--quiet",
                                 "--", relative + "/release-assets-output-policy-check"],
                                capture_output=True, timeout=30, env=environment)
        tracked = subprocess.run(["git", "-C", str(root), "ls-files", "-z", "--", relative],
                                 capture_output=True, timeout=30, env=environment)
    except (OSError, subprocess.SubprocessError):
        raise PreparationError("OUTPUT_IGNORE_CHECK_UNAVAILABLE") from None
    require(result.returncode == 0 and tracked.returncode == 0 and not tracked.stdout,
            "OUTPUT_MUST_BE_IGNORED_AND_UNTRACKED")
    require(not output.exists(), "OUTPUT_ALREADY_EXISTS")
    return output


def prepare(verification, apk, commit, tag, output=None, *, project_root=ROOT,
            expected_version_code=22, minimum_api=30):
    require(isinstance(commit, str) and re.fullmatch(r"[0-9a-f]{40}", commit) is not None,
            "INVALID_SOURCE_COMMIT")
    require(isinstance(tag, str) and tag.startswith("v") and VERSION.fullmatch(tag[1:]) is not None,
            "INVALID_SOURCE_TAG")
    version = tag[1:]
    require(type(expected_version_code) is int and expected_version_code > 0 and
            type(minimum_api) is int and minimum_api >= 30, "INVALID_RELEASE_PARAMETERS")
    verification = nonredirected(verification, regular_file=True)
    apk = nonredirected(apk, regular_file=True)
    require(apk.suffix.lower() == ".apk", "RELEASE_APK_REQUIRED")
    require(verification.stat().st_size <= 2 * 1024 * 1024, "VERIFICATION_REPORT_TOO_LARGE")
    report = json.loads(verification.read_text(encoding="utf-8-sig"))
    require(isinstance(report, dict) and report.get("status") == "PASS", "VERIFICATION_NOT_PASS")
    release = report.get("channels", {}).get("release", {})
    require(isinstance(release, dict), "RELEASE_VERIFICATION_MISSING")
    require(release.get("application_id") == RELEASE_ID, "RELEASE_IDENTITY_MISMATCH")
    require(release.get("version_name") == version and
            str(release.get("version_code")) == str(expected_version_code), "RELEASE_VERSION_MISMATCH")
    require(release.get("debuggable") is False and type(release.get("debug_components")) is int and
            release["debug_components"] == 0, "RELEASE_DEBUG_CODE_PRESENT")
    require(release.get("certificate_sha256") == RELEASE_CERT_SHA256, "RELEASE_CERTIFICATE_MISMATCH")
    require(all(release.get(name) is True for name in (
        "backup_disabled", "private_control_receiver", "channel_provider_authorities")),
        "RELEASE_COMPONENT_VERIFICATION_MISSING")
    archive = release.get("archive", {})
    require(isinstance(archive, dict) and all(archive.get(name) is True for name in (
        "runtime_prompts_byte_identical", "asset_allowlist_matched", "known_secret_literals_absent",
        "fixture_code_absent", "debug_code_absent")), "RELEASE_ARCHIVE_VERIFICATION_MISSING")
    require(report.get("frozen_prompt_manifests") == 3 and report.get("frozen_prompt_references") == 59,
            "PROMPT_VERIFICATION_MISSING")
    data = apk.read_bytes()
    actual_sha = digest(data)
    require(type(release.get("apk_bytes")) is int and release["apk_bytes"] == len(data) and
            release.get("apk_sha256") == actual_sha, "RELEASE_APK_DIGEST_MISMATCH")
    output = ignored_output(Path(project_root), output or (
        Path(project_root) / "android-app/app/build/github-release" / tag))
    filename = f"MobileHarness-{version}-release.apk"
    observed_version = version == OBSERVED_VERSION and expected_version_code == OBSERVED_VERSION_CODE
    # Construct fresh public metadata. Never copy a report, labels, paths or
    # arbitrary caller-provided properties into the distribution.
    metadata = {
        "schema_version": "1.0.0", "application_id": RELEASE_ID,
        "version_name": version, "version_code": expected_version_code,
        "source_commit": commit, "source_tag": tag,
        "apk_filename": filename, "apk_bytes": len(data), "apk_sha256": actual_sha,
        "signing_certificate_sha256": RELEASE_CERT_SHA256,
        "minimum_android_api": minimum_api,
        "debuggable": False, "debug_components": 0,
        "release_channel": "prerelease" if "-" in version else "release",
        "validation_scope": {
            "sdk_signature_and_archive_verification": True,
            "frozen_prompt_references": 59,
            "device": "Google Pixel 4" if observed_version else None,
            "android_version": "13" if observed_version else None,
            "device_observation_version": OBSERVED_VERSION if observed_version else None,
            "device_observation_version_code": OBSERVED_VERSION_CODE if observed_version else None,
            "prior_release_channel_install_and_startup_verified": observed_version,
            "prior_test_apk_sha256": OBSERVED_APK_SHA256 if observed_version else None,
            "release_install_and_startup_verified": observed_version and actual_sha == OBSERVED_APK_SHA256,
            "release_model_task_and_handoff_verified": False,
            "other_devices_verified": False,
            "release_upgrade_verified": False,
        },
    }
    encoded = (json.dumps(metadata, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    checksums = f"{actual_sha}  {filename}\n{digest(encoded)}  RELEASE-METADATA.json\n".encode("ascii")
    # Create the version directory once. Refuse all pre-existing output, even
    # empty folders, so published bytes are never silently replaced.
    output.parent.mkdir(parents=True, exist_ok=True)
    nonredirected(output.parent)
    output.mkdir()
    for name, content in ((filename, data), ("RELEASE-METADATA.json", encoded),
                          ("SHA256SUMS.txt", checksums)):
        with (output / name).open("xb") as stream:
            stream.write(content)
    return {"status": "PASS", "source_tag": tag, "source_commit": commit,
            "asset_names": [filename, "SHA256SUMS.txt", "RELEASE-METADATA.json"],
            "apk_sha256": actual_sha, "apk_bytes": len(data),
            "signing_certificate_sha256": RELEASE_CERT_SHA256,
            "network_or_model_called": False, "phone_accessed": False,
            "private_signing_material_read": False, "published": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verification", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--commit", required=True, help="Full public source commit SHA")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--project-root", type=Path, default=ROOT)
    parser.add_argument("--version-code", type=int, default=22)
    parser.add_argument("--minimum-api", type=int, default=30)
    args = parser.parse_args(argv)
    try:
        result = prepare(args.verification, args.apk, args.commit, args.tag, args.output,
                         project_root=args.project_root, expected_version_code=args.version_code,
                         minimum_api=args.minimum_api)
    except PreparationError as failure:
        result = {"status": "FAIL", "reason": str(failure)}
    except Exception:
        result = {"status": "FAIL", "reason": "RELEASE_INPUT_OR_OUTPUT_ERROR"}
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
