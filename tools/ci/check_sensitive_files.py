"""Check tracked checkout files for private artifacts and recognizable secrets.

Python standard library only. No phone, model, signing credential or network
access. Findings contain fixed codes and sanitized relative paths, never matched
content. This is a guardrail, not an exhaustive secret detector or history scan.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
MAX_FILE_BYTES = 16 * 1024 * 1024
FORBIDDEN_PARTS = {
    ".git", ".gradle", ".kotlin", ".idea", ".vscode", "build", "out", ".cxx",
    ".externalnativebuild", "captures", "no_backup", "__pycache__", ".pytest_cache", ".venv", "venv",
}
FORBIDDEN_ROOTS = {
    ".agents", ".codex", "baselines", "private", "secrets", "local-data", "exports",
    "screenshots", "local-analysis",
}
FORBIDDEN_PREFIXES = ("docs/acceptance/results/", "docs/previews/", "docs/local-archive/")
FORBIDDEN_SUFFIXES = {
    ".apk", ".aab", ".apks", ".xapk", ".jks", ".keystore", ".p12", ".pfx", ".key", ".pem",
    ".enc", ".hprof", ".heapdump", ".trace", ".perfetto-trace", ".log", ".zip", ".csv", ".jsonl", ".ndjson",
}
TOKEN_PATTERNS = (
    ("API_TOKEN_LITERAL", re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")),
    ("GITHUB_TOKEN_LITERAL", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{24,}|github_pat_[A-Za-z0-9_]{40,})\b")),
    ("AWS_ACCESS_KEY_LITERAL", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("GOOGLE_API_KEY_LITERAL", re.compile(r"\bAIza[A-Za-z0-9_-]{35}\b")),
    ("SLACK_TOKEN_LITERAL", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{24,}\b")),
    ("BEARER_TOKEN_LITERAL", re.compile(r"\bBearer[ \t]+[A-Za-z0-9._~-]{24,}", re.I)),
    ("JWT_LITERAL", re.compile(r"\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\b")),
    ("PRIVATE_KEY_LITERAL", re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
)
CREDENTIAL_NAME = r"(?:api[_-]?key|access[_-]?token|client[_-]?secret|storepassword|keypassword|password)"
QUOTED_CREDENTIAL = re.compile(
    r"\b(?P<name>" + CREDENTIAL_NAME + r")[\"']?\s*[:=]\s*(?P<quote>[\"'])(?P<value>[^\r\n\"']*)(?P=quote)", re.I)
PROPERTY_CREDENTIAL = re.compile(
    r"^\s*(?P<name>" + CREDENTIAL_NAME + r")\s*=\s*(?P<value>[^\r\n]+)", re.I | re.M)
PLACEHOLDER = re.compile(
    r"(?:<[^<>]+>|\$\{[^{}]+\}|YOUR_API_KEY|YOUR_PASSWORD|YOUR_ACCESS_TOKEN|"
    r"REPLACE_ME|CHANGE_ME|INSERT_API_KEY|redacted|changeme)", re.I)
# Exact existing API-key test literals, reviewed as synthetic. Bind exceptions
# to their original source file and digest; no test-directory or prefix bypass.
# Recognizable vendor tokens/private keys are always reported before this rule.
KNOWN_SYNTHETIC_API_LITERALS = {
    "android-app/app/src/androidTest/java/org/mobileharness/app/PrivateStorageTest.kt": {
        "2202e402768c1dfc55af2261a53d2580a357900b440fdbfd5b7f11ab844a9d69",
        "224cca976fbaf794924b0d16af78461746c722cd8585cea513799adfc6393add",
    },
    "android-app/app/src/test/java/org/mobileharness/app/core/HttpStatusTest.kt": {
        "82986baf72b77173279eade4b69e58f7f61b460e57ff07b7249dd8e66ee4aea2",
    },
    "android-app/app/src/test/java/org/mobileharness/app/model/AssemblerPreflightBoundsTest.kt": {
        "7ae05a6ea737539570eb9d17e914e323030a6e988e10b8e053e23f21908fc7b4",
    },
    "android-app/app/src/test/java/org/mobileharness/app/model/AutomaticStartAssemblyTest.kt": {
        "b581d8954cfb5348b5379bb6ffb8c59a0e2a808b3a56726a626a5345fe47f0ab",
        "c0359a8b82bd79f966b23127e59ef3afd1116e05962df77edd0f93fcee37c7e8",
    },
    "android-app/app/src/test/java/org/mobileharness/app/model/GuiEnvelopeCompatibilityTest.kt": {
        "2ee8f42267a3635138b7b04501b6a854349d168cdc4e2db23bf05b64e65371f7",
    },
    "android-app/app/src/test/java/org/mobileharness/app/model/QwenGatewayTest.kt": {
        "b5dff02c5378aa99c9029f296267cdc08c928cd9ba48ac49a4ac46b5561e9188",
        "c86c188698509f908e10716c080cbfc92773a45457e2ba40b8e4bcbad3fc4003",
    },
}


class CheckError(Exception):
    """Closed reason codes only."""


def git(root, *arguments):
    environment = os.environ.copy()
    # An inherited alternate index/work-tree must not redirect a local check.
    for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES"):
        environment.pop(key, None)
    try:
        result = subprocess.run(["git", "-C", str(root), *arguments], capture_output=True,
                                timeout=30, env=environment)
    except (OSError, subprocess.SubprocessError):
        raise CheckError("GIT_CHECK_UNAVAILABLE") from None
    if result.returncode != 0:
        raise CheckError("GIT_CHECK_UNAVAILABLE")
    return result.stdout


def valid_path(name):
    if not name or "\\" in name or any(ord(char) < 32 or ord(char) == 127 for char in name):
        return False
    path = PurePosixPath(name)
    return not path.is_absolute() and not any(part in {"", ".", ".."} for part in name.split("/")) and ":" not in name


def safe_path(name):
    # A token may itself be part of a malicious filename. Do not echo it.
    if not valid_path(name) or any(pattern.search(name) for _, pattern in TOKEN_PATTERNS):
        return "[REDACTED_PATH]"
    if re.search(r"(?:password|secret|token|api[_-]?key)[=:]", name, re.I):
        return "[REDACTED_PATH]"
    return name


def forbidden_path(name):
    path = PurePosixPath(name.lower())
    parts = path.parts
    if FORBIDDEN_PARTS.intersection(parts) or parts[0] in FORBIDDEN_ROOTS or parts[0].startswith("local-analysis-"):
        return True
    if name.lower().startswith(FORBIDDEN_PREFIXES) or path.suffix in FORBIDDEN_SUFFIXES:
        return True
    base = path.name
    if base.startswith("local-secrets") or base == "local.properties" or base == "signing.dpapi.json" or base.endswith(".signing-recovery.json"):
        return True
    if base.startswith(".env") and base not in {".env.example", ".env.template"}:
        return True
    return base in {"key.properties", "keystore.properties", "signing.properties"} or (
        base.endswith(".properties") and ("keystore" in base or "signing" in base))


def find_secrets(data, name):
    texts = [data.decode("utf-8-sig", errors="ignore")]
    if b"\0" in data:
        texts.extend(data.decode(encoding, errors="ignore") for encoding in ("utf-16-le", "utf-16-be"))
    found = set()
    for text in texts:
        for code, pattern in TOKEN_PATTERNS:
            for match in pattern.finditer(text):
                found.add((code, text.count("\n", 0, match.start()) + 1))
        assignments = list(QUOTED_CREDENTIAL.finditer(text))
        if PurePosixPath(name).suffix.lower() == ".properties" or PurePosixPath(name).name.lower() in {".env.example", ".env.template"}:
            assignments.extend(PROPERTY_CREDENTIAL.finditer(text))
        for match in assignments:
            value = match.group("value").strip()
            minimum = 8 if "password" in match.group("name").lower() else 12
            field = re.sub("[_-]", "", match.group("name").lower())
            known_synthetic = field == "apikey" and hashlib.sha256(value.encode("utf-8")).hexdigest() in KNOWN_SYNTHETIC_API_LITERALS.get(name, set())
            if len(value) >= minimum and not PLACEHOLDER.fullmatch(value) and not known_synthetic:
                found.add(("HARDCODED_CREDENTIAL_LITERAL", text.count("\n", 0, match.start()) + 1))
    return sorted(found)


def check(root):
    root = root.resolve()
    try:
        top = Path(git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    except (UnicodeError, OSError):
        raise CheckError("INVALID_GIT_ROOT") from None
    if top != root:
        raise CheckError("PROJECT_ROOT_MUST_BE_GIT_ROOT")
    findings = []
    entries = git(root, "ls-files", "--stage", "-z").split(b"\0")
    scanned = 0
    seen = set()
    for entry in entries:
        if not entry:
            continue
        try:
            metadata, raw_name = entry.split(b"\t", 1)
            mode, _, stage = metadata.decode("ascii").split(" ")
            name = raw_name.decode("utf-8")
        except (ValueError, UnicodeError):
            raise CheckError("INVALID_GIT_ENTRY") from None
        report_path = safe_path(name)
        def add(code, line=None):
            finding = {"code": code, "path": report_path}
            if line is not None:
                finding["line"] = line
            if finding not in findings:
                findings.append(finding)
        if not valid_path(name):
            add("INVALID_TRACKED_PATH")
            continue
        if stage != "0":
            add("UNMERGED_TRACKED_FILE")
            continue
        if name in seen:
            continue
        seen.add(name)
        if any(pattern.search(name) for _, pattern in TOKEN_PATTERNS):
            add("SECRET_IN_TRACKED_PATH")
        if forbidden_path(name):
            add("FORBIDDEN_TRACKED_FILE")
            continue
        if mode not in {"100644", "100755"}:
            add("UNSUPPORTED_TRACKED_ENTRY")
            continue
        file = root / name
        try:
            # Check all ancestors: a local symlink may differ from index mode.
            if any(parent.is_symlink() for parent in (file, *file.parents) if parent != root.parent):
                add("SYMLINK_TRACKED_FILE")
                continue
            info = file.stat()
            if not stat.S_ISREG(info.st_mode) or not file.resolve().is_relative_to(root):
                add("UNREADABLE_TRACKED_FILE")
                continue
            if info.st_size > MAX_FILE_BYTES:
                add("TRACKED_FILE_TOO_LARGE_TO_SCAN")
                continue
            data = file.read_bytes()
        except OSError:
            add("UNREADABLE_TRACKED_FILE")
            continue
        scanned += 1
        for code, line in find_secrets(data, name):
            add(code, line)
    if not seen:
        raise CheckError("NO_TRACKED_FILES")
    return {"status": "FAIL" if findings else "PASS", "tracked_files": len(seen),
            "scanned_files": scanned, "findings": findings,
            "scope": "Tracked checkout files; recognizable patterns and private-path policy. Not Git history or exhaustive secret detection.",
            "phone_accessed": False, "network_or_model_called": False, "signing_credentials_read": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=ROOT)
    arguments = parser.parse_args(argv)
    try:
        result = check(arguments.project_root)
    except CheckError as failure:
        result = {"status": "FAIL", "code": str(failure)}
    except Exception:
        # Exceptions can contain absolute paths, file contents or Git stderr.
        result = {"status": "FAIL", "code": "SENSITIVE_CHECK_INPUT_ERROR"}
    print(json.dumps(result, ensure_ascii=True, indent=2))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
