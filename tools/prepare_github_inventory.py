"""Prepare an explicit local upload inventory. Never stage, push, sign or access a phone.

Uses an isolated temporary Git repository to evaluate the workspace's ignore files.
Only regular, non-ignored text candidates are scanned; matches are never printed.
"""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/publishing"
GENERATED = {"docs/publishing/UPLOAD_FILES.txt", "docs/publishing/REVIEW_FILES.txt", "docs/publishing/upload-inventory.json"}
PRUNE_NAMES = {".git", ".gradle", ".kotlin", ".idea", ".vscode", "build", "out", ".cxx", ".externalNativeBuild", "__pycache__", ".pytest_cache", ".venv", "venv", "no_backup", "captures"}
PRUNE_PATHS = {".agents", ".codex", "baselines", "docs/acceptance/results", "docs/previews", "docs/local-archive", "private", "secrets", "local-data", "exports", "screenshots", "local-analysis"}
TEXT = {".md", ".txt", ".json", ".py", ".ps1", ".kt", ".kts", ".xml", ".properties", ".bat", ".sh", ".yml", ".yaml"}


def require(condition, code):
    if not condition:
        raise ValueError(code)


def git(args, cwd, data=None):
    result = subprocess.run(["git", *args], cwd=cwd, input=data, capture_output=True, timeout=30)
    require(result.returncode in (0, 1), "GIT_IGNORE_CHECK_FAILED")
    return result


def files():
    entries, excluded = [], []
    for folder, directories, names in os.walk(ROOT, followlinks=False):
        parent = Path(folder)
        for name in sorted(list(directories)):
            path = parent / name
            relative = path.relative_to(ROOT).as_posix()
            if path.is_symlink() or name in PRUNE_NAMES or relative in PRUNE_PATHS or (parent == ROOT and name.startswith("local-analysis-")):
                directories.remove(name)
                excluded.append(relative + "/")
        for name in sorted(names):
            path = parent / name
            relative = path.relative_to(ROOT).as_posix()
            if path.is_symlink():
                excluded.append(relative)
            elif relative not in GENERATED:
                entries.append(relative)
    return sorted(entries), sorted(excluded)


def ignored_paths(repo, candidates):
    if not candidates:
        return set()
    payload = b"\0".join(path.encode("utf-8") for path in candidates) + b"\0"
    args = ["--git-dir=" + str(repo / ".git"), "--work-tree=" + str(ROOT), "-c", "core.excludesFile=" + str(repo / "empty-excludes"),
            "check-ignore", "--no-index", "-z", "--stdin"]
    result = git(args, ROOT, payload)
    return {path.decode("utf-8") for path in result.stdout.split(b"\0") if path}


def category(name):
    if name in GENERATED:
        return "generated_upload_inventory"
    if name in {"README.md", "README.en.md", "android-app/README.md"}:
        return "user_documentation"
    if name.startswith("android-app/"):
        return "android_source_tests_and_project"
    if name.startswith("prompts/"):
        return "versioned_prompts"
    if name.startswith("HandoffHarness_APK_Handoff/"):
        return "handoff_material_review_required"
    if name.startswith(("docs/data-contracts/", "docs/runtime-policies/")):
        return "data_and_runtime_contracts"
    if name.startswith("docs/publishing/"):
        return "publishing_preparation"
    if name.startswith("docs/"):
        return "method_or_historical_documentation"
    if name.startswith("tools/"):
        return "maintenance_or_analysis_tool"
    if name in {".gitignore", ".gitattributes", "LICENSE", "LICENSE.txt", "LICENSE.md"}:
        return "repository_configuration"
    return "unclassified_review_required"


def frozen_references():
    found = set()
    for manifest in sorted((ROOT / "prompts").glob("v*/manifest.json")):
        def visit(value):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    path = ROOT / value["path"]
                    if not path.is_file():
                        path = manifest.parent / value["path"]
                    require(path.is_file() and path.resolve().is_relative_to(ROOT), "FROZEN_REFERENCE_MISSING")
                    require(hashlib.sha256(path.read_bytes()).hexdigest() == value["sha256"], "FROZEN_REFERENCE_CHANGED")
                    found.add(path.resolve().relative_to(ROOT).as_posix())
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(json.loads(manifest.read_text("utf-8-sig")))
    require(len(found) == 59, "FROZEN_REFERENCE_COUNT_CHANGED")
    return sorted(found)


def main():
    entries, pruned = files()
    expected_ignored = ["android-app/local.properties", "android-app/.gradle/cache.bin", "android-app/app/build/outputs/app.apk",
                        "release.jks", "release.keystore", "signing.properties", ".env", ".env.local", "private/auth.json",
                        "baselines/archive.zip", "docs/acceptance/results/log.json", "docs/previews/screen.png",
                        "docs/local-archive/original.md", ".agents/skills/example/SKILL.md", ".codex/config.toml",
                        "tools/__pycache__/example.pyc", "exports/analysis.csv", "run.jsonl", "raw.ndjson", "backup/no_backup/profiles/catalog.enc",
                        "device.log", "source-snapshot.zip", "release-signing.properties"]
    expected_kept = ["README.md", "README.en.md", ".gitignore", ".gitattributes", "LICENSE", "android-app/gradle/wrapper/gradle-wrapper.jar",
                     "android-app/gradlew", "android-app/app/src/debug/AndroidManifest.xml",
                     "android-app/app/src/test/java/org/mobileharness/app/storage/ExperimentAnalysisTest.kt",
                     "android-app/fixture/src/main/AndroidManifest.xml", "prompts/v1.2.0/manifest.json",
                     "HandoffHarness_APK_Handoff/HANDOFF_ANDROID_APK.md", "docs/data-contracts/minimal-event.v1.3.schema.json",
                     "tools/tests/test_analyze_experiment_csv.py", "docs/publishing/SIGNING_AND_CHANNELS.md", ".env.example"]
    with tempfile.TemporaryDirectory(prefix="mobileharness-ignore-") as temporary:
        temp = Path(temporary)
        empty_template = temp / "empty-template"
        empty_template.mkdir()
        repo = temp / "repo"
        require(git(["init", "--quiet", "--template=" + str(empty_template), str(repo)], temp).returncode == 0, "TEMPORARY_GIT_INIT_FAILED")
        (repo / "empty-excludes").write_text("", encoding="utf-8")
        require(ignored_paths(repo, expected_ignored) == set(expected_ignored), "EXPECTED_LOCAL_DATA_NOT_IGNORED")
        require(not ignored_paths(repo, expected_kept), "REQUIRED_SOURCE_MISTAKENLY_IGNORED")
        ignored = ignored_paths(repo, entries)
    candidates = sorted((set(entries) - ignored) | GENERATED)
    references = frozen_references()
    require(set(references) <= set(candidates), "UPLOAD_LIST_MISSING_FROZEN_DEPENDENCY")
    require(all((ROOT / path).is_file() for path in expected_kept if path != ".env.example"), "REQUIRED_SOURCE_MISSING")

    secret_patterns = {
        "PRIVATE_KEY_MARKER": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
        "API_TOKEN_LITERAL": re.compile(r"\bsk-[A-Za-z0-9_-]{24,}\b"),
        "GITHUB_TOKEN_LITERAL": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{24,}|github_pat_[A-Za-z0-9_]{40,})\b"),
    }
    machine_path = re.compile(r"(?:\b[A-Za-z]:[\\/](?![/\\])|/(?:Users|home)/[^\s/]+/)")
    rows, reviews, blocking = [], [], []
    for name in candidates:
        if name in GENERATED:
            rows.append({"path": name, "category": category(name), "sha256": None, "bytes": None})
            continue
        path = ROOT / name
        data = path.read_bytes()
        row = {"path": name, "category": category(name), "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
        rows.append(row)
        reasons = []
        if name.startswith("HandoffHarness_APK_Handoff/"):
            reasons.append("HANDOFF_CONTENT_AND_PUBLICATION_RIGHTS_REVIEW")
        if name.startswith("docs/") and not name.startswith(("docs/publishing/", "docs/data-contracts/", "docs/runtime-policies/")):
            reasons.append("HISTORICAL_OR_METHOD_DOCUMENT_LINKS_REVIEW")
        if category(name) == "unclassified_review_required":
            reasons.append("UNCLASSIFIED_FILE")
        if path.suffix in TEXT or name.endswith(("gradlew", ".gitignore")):
            try:
                content = data.decode("utf-8-sig")
            except UnicodeError:
                reasons.append("NON_UTF8_TEXT_REVIEW")
            else:
                if machine_path.search(content):
                    reasons.append("MACHINE_PATH_REFERENCE")
                for code, pattern in secret_patterns.items():
                    if pattern.search(content):
                        reasons.append(code)
                        blocking.append({"path": name, "code": code})
        if reasons:
            reviews.append({"path": name, "reasons": reasons})
    # README scope: the APK guides contain no build command sections or invented repository URL.
    for name in ("README.md", "README.en.md", "android-app/README.md"):
        content = (ROOT / name).read_text("utf-8-sig")
        require(not re.search(r"\b(?:gradlew?|assembleDebug|assembleRelease|JAVA_HOME|JDK|ADB)\b|^#{1,4}[^\n]*(?:构建|编译|Build)", content, re.I | re.M), "USER_README_HAS_BUILD_INSTRUCTIONS")
    archive_meta = json.loads((ROOT / "docs/local-archive/android-app-README.pre-github.json").read_text("utf-8-sig"))
    archive = ROOT / "docs/local-archive" / archive_meta["archived_file"]
    require(hashlib.sha256(archive.read_bytes()).hexdigest() == archive_meta["sha256"], "ORIGINAL_README_NOT_PRESERVED")
    result = {"schema_version": "1.0.0", "status": "LOCAL_PREPARATION_REVIEW_REQUIRED" if not blocking else "POTENTIAL_SECRET_REVIEW_REQUIRED",
              "uploaded": False, "project_git_initialized_by_tool": False, "phone_accessed": False, "apk_or_signing_changed": False,
              "ignored_probe_count": len(expected_ignored), "kept_probe_count": len(expected_kept),
              "frozen_reference_checks": len(references), "candidate_file_count": len(rows), "review_file_count": len(reviews),
              "categories": dict(sorted(Counter(row["category"] for row in rows).items())),
              "local_only_pruned_directories": pruned, "additional_ignored_files": sorted(ignored),
              "secret_scan": {"scope": "NON_IGNORED_UTF8_TEXT_CANDIDATES_ONLY", "potential_matches": blocking,
                              "complete_secret_detection_claimed": False},
              "source_repository": "https://github.com/Rich-King395/MobileHarness",
              "root_license": "Apache-2.0" if (ROOT / "LICENSE").is_file() else None,
              "public_release_prerequisites": ["RELEASE_ID_SIGNING_AND_DEVICE_ACCEPTANCE", "THIRD_PARTY_BINARY_NOTICES_REVIEW"],
              "original_android_readme_preserved": True, "files": rows, "review": reviews}
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "UPLOAD_FILES.txt").write_text("\n".join(row["path"] for row in rows) + "\n", encoding="utf-8")
    (OUT / "REVIEW_FILES.txt").write_text("# Paths and fixed review codes only; no matched content.\n" +
        "\n".join(row["path"] + "\t" + ",".join(row["reasons"]) for row in reviews) + "\n", encoding="utf-8")
    (OUT / "upload-inventory.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("status", "candidate_file_count", "review_file_count", "ignored_probe_count", "kept_probe_count", "frozen_reference_checks", "uploaded")}))
    require(not blocking, "POTENTIAL_SECRET_REVIEW_REQUIRED")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, KeyError) as failure:
        code = str(failure) if type(failure) is ValueError and re.fullmatch(r"[A-Z_]+", str(failure)) else "UPLOAD_PREPARATION_REQUIRES_REVIEW"
        print(json.dumps({"status": "NOT_ESTABLISHED", "code": code}))
        raise SystemExit(1)
