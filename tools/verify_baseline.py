"""Verify a frozen MobileHarness archive locally, without a phone or network.

Usage: python tools/verify_baseline.py baselines/<baseline-id>
The same script is copied into each archive for standalone use.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import sys
import xml.etree.ElementTree as ET
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def load(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def require(condition, code):
    if not condition:
        raise ValueError(code)


def relative_path(value):
    require(isinstance(value, str) and value and "\\" not in value, "INVALID_ARCHIVE_PATH")
    name = PurePosixPath(value)
    require(not name.is_absolute() and all(p not in (".", "..") for p in name.parts), "UNSAFE_ARCHIVE_PATH")
    require(":" not in value, "UNSAFE_ARCHIVE_PATH")
    return name


def verify(directory):
    root = directory.resolve(strict=True)
    manifest = load(root / "checksums.json")
    baseline = load(root / "baseline.json")
    require(manifest["baseline_id"] == baseline["baseline_id"], "BASELINE_ID_MISMATCH")
    seen = set()
    for entry in manifest["files"]:
        name = relative_path(entry["path"]).as_posix()
        require(name not in seen, "DUPLICATE_PAYLOAD_PATH")
        seen.add(name)
        path = root.joinpath(*PurePosixPath(name).parts)
        require(path.resolve().is_relative_to(root) and path.is_file() and not path.is_symlink(), "PAYLOAD_NOT_LOCAL_FILE")
        data = path.read_bytes()
        require(len(data) == entry["bytes"] and digest(data) == entry["sha256"], "PAYLOAD_HASH_MISMATCH:" + name)
    actual = {p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file()}
    require(actual - {"checksums.json", "verification.json"} == seen, "UNLISTED_OR_MISSING_PAYLOAD")

    apk = baseline["apk"]
    apk_path = root / relative_path(apk["path"])
    require(digest(apk_path.read_bytes()) == apk["sha256"], "APK_IDENTITY_MISMATCH")
    config = load(root / "configuration.json")
    require(config["application"]["apk_sha256"] == apk["sha256"], "CONFIG_APK_MISMATCH")
    require(config["application"]["version_name"] == baseline["app_version"] and
            config["application"]["version_code"] == baseline["version_code"], "CONFIG_VERSION_MISMATCH")

    sources = load(root / "source-files.json")
    expected = {entry["path"]: entry for entry in sources["files"]}
    require(len(expected) == len(sources["files"]), "DUPLICATE_SOURCE_PATH")
    with zipfile.ZipFile(root / "source-snapshot.zip") as archive:
        require(len(archive.namelist()) == len(expected) and set(archive.namelist()) == set(expected), "SOURCE_ZIP_INDEX_MISMATCH")
        for name, entry in expected.items():
            relative_path(name)
            parts = set(PurePosixPath(name).parts)
            require(not parts.intersection({".git", ".gradle", ".kotlin", ".idea", "build", "no_backup", "__pycache__"}), "FORBIDDEN_SOURCE_DIRECTORY")
            require(PurePosixPath(name).name != "local.properties" and
                    PurePosixPath(name).suffix.lower() not in {".jks", ".keystore", ".p12", ".pfx", ".hprof", ".enc"}, "FORBIDDEN_SOURCE_FILE")
            data = archive.read(name)
            require(len(data) == entry["bytes"] and digest(data) == entry["sha256"], "SOURCE_HASH_MISMATCH:" + name)
        prompt_refs = set()
        for name in expected:
            if name.startswith("prompts/") and name.endswith("/manifest.json"):
                document = json.loads(archive.read(name).decode("utf-8-sig"))
                def visit(value):
                    if isinstance(value, dict):
                        if "path" in value and "sha256" in value:
                            path = value["path"]
                            if path not in expected:
                                path = (PurePosixPath(name).parent / path).as_posix()
                            require(path in expected and digest(archive.read(path)) == value["sha256"], "FROZEN_PROMPT_MISMATCH")
                            prompt_refs.add(path)
                        for child in value.values():
                            visit(child)
                    elif isinstance(value, list):
                        for child in value:
                            visit(child)
                visit(document)

    index = load(root / "evidence-index.json")
    for item in index["items"]:
        require(item["relation"] in {"CURRENT_DIRECT", "PRIOR_VERSION_REUSED", "USER_REPORTED", "HISTORICAL"}, "INVALID_EVIDENCE_RELATION")
        for name in item["files"]:
            require(relative_path(name).as_posix() in seen, "MISSING_EVIDENCE_FILE:" + name)
        if "report_directory" in item:
            folder = root / relative_path(item["report_directory"])
            reports = sorted(folder.glob(item["report_pattern"]))
            totals = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
            for report in reports:
                suite = ET.parse(report).getroot()
                for key in totals:
                    totals[key] += int(suite.attrib.get(key, "0"))
            require(len(reports) == item["measurements"]["suites"], "JVM_SUITE_COUNT_MISMATCH")
            require(all(totals[k] == item["measurements"][k] for k in totals), "JVM_EVIDENCE_COUNT_MISMATCH")
    return {
        "status": "PASS", "baseline_id": baseline["baseline_id"],
        "apk_sha256": apk["sha256"], "payload_files": len(seen),
        "source_files": len(expected), "frozen_prompt_references": len(prompt_refs),
        "evidence_items": len(index["items"]),
        "phone_accessed": False, "network_or_model_called": False,
        "application_rebuilt_or_tests_rerun": False,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    arguments = parser.parse_args()
    try:
        result = verify(arguments.archive)
    except Exception as failure:
        print(json.dumps({"status": "FAIL", "reason": str(failure)}, ensure_ascii=False))
        sys.exit(1)
    print(json.dumps(result, ensure_ascii=False))
