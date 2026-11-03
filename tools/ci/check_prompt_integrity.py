"""Portable, read-only CI verification of the existing frozen prompt bundles.

Reuses the release verifier's pinned manifests and content checks. No SDK,
certificate, model endpoint or private signing material is needed. The CLI
reports counts and closed error codes, never prompt text or arbitrary errors.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import stat
import sys


ROOT = Path(__file__).resolve().parents[2]
_SPEC = importlib.util.spec_from_file_location(
    "mobileharness_prompt_baseline", ROOT / "tools" / "verify_release_channels.py")
BASELINE = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(BASELINE)


def safe_relative_path(value):
    BASELINE.require(isinstance(value, str) and "\x00" not in value, "UNSAFE_ARCHIVE_PATH")
    return BASELINE.safe_archive_path(value)


def nonredirected_path(root, relative, *, regular_file=True):
    """Check each component before reading; reject symlinks and Windows junctions."""
    parts = safe_relative_path(relative).parts
    candidate = root
    for index, part in enumerate(parts):
        candidate /= part
        try:
            info = candidate.lstat()
        except FileNotFoundError:
            raise BASELINE.VerificationError("PROMPT_REFERENCE_MISSING") from None
        redirected = stat.S_ISLNK(info.st_mode) or bool(
            getattr(info, "st_file_attributes", 0) & 0x400)
        BASELINE.require(not redirected, "PROMPT_PATH_REDIRECTION")
        if index != len(parts) - 1:
            BASELINE.require(stat.S_ISDIR(info.st_mode), "PROMPT_REFERENCE_MISSING")
    if regular_file:
        BASELINE.require(stat.S_ISREG(info.st_mode), "PROMPT_REFERENCE_MISSING")
    BASELINE.require(candidate.resolve().is_relative_to(root), "UNSAFE_ARCHIVE_PATH")
    return candidate


def verify(project_root):
    root = Path(project_root).resolve(strict=True)
    for version, expected in BASELINE.FROZEN_MANIFEST_SHA256.items():
        bundle_relative = f"prompts/{version}"
        manifest = nonredirected_path(root, f"{bundle_relative}/manifest.json")
        data = manifest.read_bytes()
        BASELINE.require(BASELINE.sha256(data) == expected, "FROZEN_PROMPT_MANIFEST_CHANGED")
        document = json.loads(data.decode("utf-8-sig"))

        def visit(value):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    relative = safe_relative_path(value["path"]).as_posix()
                    # Preserve the established root-first / bundle-local lookup.
                    # Check redirections even for a broken symlink candidate.
                    direct = root.joinpath(*safe_relative_path(relative).parts)
                    if direct.exists() or direct.is_symlink():
                        nonredirected_path(root, relative)
                    else:
                        nonredirected_path(root, f"{bundle_relative}/{relative}")
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        visit(document)
        supplements = BASELINE.FROZEN_RUNTIME_SUPPLEMENTS.get(version, {})
        declared = {entry["path"] for entry in document["files"]} | {"manifest.json"} | set(supplements)
        for relative in declared:
            nonredirected_path(root, f"{bundle_relative}/{safe_relative_path(relative).as_posix()}")
        actual = set()
        for folder, directories, filenames in os.walk(manifest.parent, followlinks=False):
            for name in directories + filenames:
                path = Path(folder) / name
                nonredirected_path(root, path.relative_to(root).as_posix(), regular_file=name in filenames)
            for name in filenames:
                actual.add((Path(folder) / name).relative_to(manifest.parent).as_posix())
        BASELINE.require(actual == declared, "UNDECLARED_PROMPT_SOURCE_FILE")

    assets, references = BASELINE.verify_prompt_sources(root)
    BASELINE.require(references == 59 and len(assets) == 32, "FROZEN_PROMPT_COVERAGE_CHANGED")
    return {
        "status": "PASS", "frozen_manifests": len(BASELINE.FROZEN_MANIFEST_SHA256),
        "frozen_references": references, "runtime_prompt_assets": len(assets),
        "path_redirections_rejected": True, "network_or_model_called": False,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=ROOT)
    args = parser.parse_args(argv)
    try:
        result = verify(args.project_root)
    except BASELINE.VerificationError as failure:
        result = {"status": "FAIL", "reason": str(failure)}
    except Exception:
        result = {"status": "FAIL", "reason": "PROMPT_INPUT_OR_VALIDATOR_ERROR"}
    print(json.dumps(result, ensure_ascii=True))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
