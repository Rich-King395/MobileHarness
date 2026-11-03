"""One-time allowlisted archive of the existing 0.8.1 APK; never builds or uses a phone.

After checksums.json seals the archive, use verify_baseline.py instead of rerunning this.
"""
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "baselines/2026-11-03-mobileharness-0.8.1"
APK_HASH = "8a515bf7763a32e02bc94039dfb56dcef9396290eba9a5c1429d353d46971edd"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def write_new(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        if path.read_bytes() != data:
            raise RuntimeError("Refusing to overwrite baseline payload: " + path.relative_to(ROOT).as_posix())
    else:
        path.write_bytes(data)


def main():
    if (OUT / "checksums.json").exists():
        raise RuntimeError("Baseline is sealed. Run verify_baseline.py; create a new baseline for changed inputs.")
    OUT.mkdir(parents=True, exist_ok=True)
    files = set()
    for folder in ("android-app/app/src", "android-app/fixture/src", "prompts", "docs/data-contracts", "docs/runtime-policies"):
        files.update(p for p in (ROOT / folder).rglob("*") if p.is_file() and "__pycache__" not in p.parts and p.suffix != ".pyc")
    for name in (
        "android-app/build.gradle.kts", "android-app/settings.gradle.kts", "android-app/gradle.properties",
        "android-app/gradlew", "android-app/gradlew.bat", "android-app/app/build.gradle.kts", "android-app/fixture/build.gradle.kts",
        "android-app/gradle/wrapper/gradle-wrapper.jar", "android-app/gradle/wrapper/gradle-wrapper.properties",
        "tools/verify_baseline.py", "tools/freeze_baseline081.py", "tools/verify_automatic_recovery080.py",
        "tools/verify_execution_feedback_schema050.py", "tools/verify_localization060.py", "tools/verify_task_records070.py",
    ):
        path = ROOT / name
        if not path.is_file():
            raise RuntimeError("Missing allowlisted source: " + name)
        files.add(path)
    refs = set()
    for manifest in ROOT.glob("prompts/v*/manifest.json"):
        def visit(value):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    path = ROOT / value["path"]
                    if not path.is_file():
                        path = manifest.parent / value["path"]
                    path = path.resolve()
                    path.relative_to(ROOT)
                    if sha(path.read_bytes()) != value["sha256"]:
                        raise RuntimeError("Frozen reference changed: " + path.relative_to(ROOT).as_posix())
                    files.add(path)
                    refs.add(path)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(json.loads(manifest.read_text("utf-8-sig")))
    forbidden = {".git", ".agents", ".codex", ".gradle", ".kotlin", ".idea", "build", "no_backup", "__pycache__"}
    # Token boundary avoids treating a filename such as task-records-verification as a key.
    credential = re.compile(rb"(?<![A-Za-z0-9])(?:sk-(?:sp-|proj-)?[A-Za-z0-9_-]{20,}|AKIA[A-Z0-9]{16}|-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----)")
    sources = {}
    entries = []
    for path in sorted(files, key=lambda p: p.relative_to(ROOT).as_posix()):
        name = path.relative_to(ROOT).as_posix()
        data = path.read_bytes()
        if path.is_symlink() or not path.resolve().is_relative_to(ROOT) or forbidden.intersection(path.relative_to(ROOT).parts):
            raise RuntimeError("Forbidden source path: " + name)
        if path.name in {"local.properties", ".env"} or path.suffix.lower() in {".jks", ".keystore", ".enc", ".p12", ".pfx", ".hprof"}:
            raise RuntimeError("Forbidden source file: " + name)
        if path.suffix.lower() in {".kt", ".java", ".kts", ".xml", ".json", ".txt", ".md", ".py", ".ps1", ".properties", ".bat"} and credential.search(data):
            raise RuntimeError("Potential credential literal requires review: " + name)
        sources[name] = data
        entries.append({"path": name, "bytes": len(data), "sha256": sha(data)})
    write_new(OUT / "source-files.json", encoded({"schema_version": "1.0.0", "baseline_id": OUT.name,
        "snapshot_kind": "CURRENT_WORKSPACE_ALLOWLIST_NOT_GIT_COMMIT", "app_rebuilt_in_this_stage": False,
        "byte_identical_rebuild_claimed": False, "frozen_prompt_references": len(refs), "credential_literal_scan": "PASS", "files": entries}))
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sources.items():
            info = zipfile.ZipInfo(name, (2026, 11, 3, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    write_new(OUT / "source-snapshot.zip", buffer.getvalue())
    buffer.close()
    data = (ROOT / "android-app/app/build/outputs/apk/debug/app-debug.apk").read_bytes()
    if sha(data) != APK_HASH or len(data) != 33624950:
        raise RuntimeError("Existing APK no longer matches the validated 0.8.1 artifact")
    write_new(OUT / "artifacts/MobileHarness-0.8.1-dev-vc19-debug.apk", data)
    write_new(OUT / "verify_baseline.py", (ROOT / "tools/verify_baseline.py").read_bytes())
    index = json.loads((OUT / "evidence-index.json").read_text("utf-8-sig"))
    for item in index["items"]:
        for name in item["files"]:
            if name.startswith("evidence/"):
                write_new(OUT / name, (ROOT / name[len("evidence/"):]).read_bytes())
            elif not (OUT / name).is_file():
                raise RuntimeError("Missing archive-local evidence: " + name)
        if "report_directory" in item:
            reports = list((ROOT / item["report_directory"][len("evidence/"):]).glob(item["report_pattern"]))
            if len(reports) != item["measurements"]["suites"]:
                raise RuntimeError("JVM evidence suite count changed")
            for path in reports:
                write_new(OUT / item["report_directory"] / path.name, path.read_bytes())
    prior_name = "docs/acceptance/results/capture-policy-0.8.1/before-install.json"
    write_new(OUT / "evidence" / prior_name, (ROOT / prior_name).read_bytes())
    prior = json.loads((ROOT / prior_name).read_text("utf-8-sig"))["unchanged_runtime_source_sha256"]
    reuse = all(sha(sources["android-app/app/src/main/java/org/mobileharness/app/" + name]) == digest for name, digest in prior.items())
    write_new(OUT / "apk-verification.json", encoded({"status": "PASS", "apk_sha256": APK_HASH,
        "package_name": "org.mobileharness.app", "version_name": "0.8.1-dev", "version_code": 19, "variant": "debug",
        "signature_verified": True, "v2_signature_verified": True,
        "signing_certificate_sha256": "bd86ce1e682b3901b2198f65615cabfa90ddfa7bec250fe6b656154e7f60de80",
        "private_signing_key_read_or_archived": False,
        "verification_tool": "Android SDK Build Tools 36.0.0 apksigner verify --verbose --print-certs and aapt2 dump badging"}))
    write_new(OUT / "installed-apk-check.json", encoded({"status": "READ_ONLY_INSTALLED_APK_SAMPLED",
        "installed_apk_sha256": APK_HASH, "matches_archive": True, "device_identity_archived": False,
        "model_or_task_data_read": False, "device_ui_or_task_controlled": False}))
    write_new(OUT / "baseline.json", encoded({"schema_version": "1.0.0", "baseline_id": OUT.name, "recorded_date": "2026-11-03",
        "app_version": "0.8.1-dev", "version_code": 19, "application_id": "org.mobileharness.app", "build_variant": "debug",
        "apk": {"path": "artifacts/MobileHarness-0.8.1-dev-vc19-debug.apk", "bytes": 33624950, "sha256": APK_HASH},
        "source_snapshot": "source-snapshot.zip", "source_file_index": "source-files.json", "configuration": "configuration.json",
        "evidence_index": "evidence-index.json", "user_report": "user-report-2026-11-03.json", "archive_checksums": "checksums.json",
        "application_code_changed_in_baseline_stage": False, "application_rebuilt_in_baseline_stage": False,
        "new_model_calls": 0, "new_screenshots": 0, "runtime_source_hashes_match_prior_capture_evidence": reuse,
        "source_to_apk_bit_rebuild_verified": False, "metadata_only_evidence_archived": True,
        "key_private_profile_and_user_task_attachments_archived": False}))
    print(json.dumps({"status": "PAYLOADS_ARCHIVED", "source_files": len(entries), "frozen_prompt_references": len(refs),
        "runtime_sources_match_prior_evidence": reuse, "evidence_items": len(index["items"]), "apk_sha256": APK_HASH}))


if __name__ == "__main__":
    main()
