"""Read-only, offline checks of the development and signed release APKs.

Requires Android SDK apkanalyzer, aapt and apksigner. Supply the release
certificate's PUBLIC SHA-256 fingerprint, never a keystore or password.
Raw SDK output and APK contents are kept in memory and are never reported.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
DEVELOPMENT_ID = "org.mobileharness.app"
RELEASE_ID = "org.mobileharness.app.release"
MAIN_CLASS = "org.mobileharness.app.MainActivity"
SERVICE_CLASS = "org.mobileharness.app.platform.HarnessAccessibilityService"
CONTROL_CLASS = "org.mobileharness.app.platform.ControlReceiver"
DEVELOPMENT_CERT_SHA256 = "bd86ce1e682b3901b2198f65615cabfa90ddfa7bec250fe6b656154e7f60de80"
# The existing research prompt snapshots remain immutable across app channels.
FROZEN_MANIFEST_SHA256 = {
    "v1.0.0": "faef79795ed6c1bbf33953372980a9d823a1cb78dfa56daea9a254c345a01f73",
    "v1.1.0": "0434d82c2f01bc4bd39b93df1b65ef176c25031760ef86d9b6ec8988f4c294ad",
    "v1.2.0": "be0723200c4159ae7f4b53b6bdccc78e82eb7ef5b6ce716beef4fae4ac6d003c",
}
RUNTIME_BUNDLES = ("v1.1.0", "v1.2.0")
# This validation report records the v1.2 manifest hash and cannot be listed
# inside that same manifest without creating a circular hash dependency.
FROZEN_RUNTIME_SUPPLEMENTS = {
    "v1.2.0": {"VALIDATION.md": "09454faeb924187fa72e86fa07651d407b843232e9220eedf7b4cfa890ff3ec6"},
}
FORBIDDEN_PARTS = {
    ".git", ".gradle", ".idea", "no_backup", "screenshots", "exports",
    "local-data", "local-secrets", "baselines", "fixture",
}
FORBIDDEN_SUFFIXES = {
    ".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".enc",
    ".csv", ".jsonl", ".ndjson", ".hprof", ".trace", ".log",
}
SECRET_PATTERNS = (
    re.compile(rb"\bsk-(?:[A-Za-z0-9_-]){20,}"),
    re.compile(rb"\bAKIA[A-Z0-9]{16}\b"),
    re.compile(rb"Bearer[ \t]+[A-Za-z0-9._-]{24,}"),
    re.compile(rb"-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----"),
    re.compile(rb"s\x00k\x00-\x00(?:[A-Za-z0-9_-]\x00){20,}"),
    re.compile(rb"\x00s\x00k\x00-(?:\x00[A-Za-z0-9_-]){20,}"),
)


class VerificationError(Exception):
    """Only closed, non-sensitive reason codes reach the CLI."""


def require(condition, code):
    if not condition:
        raise VerificationError(code)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def normalize_fingerprint(value):
    normalized = re.sub(r"[:\s]", "", value).lower()
    require(re.fullmatch(r"[0-9a-f]{64}", normalized) is not None, "INVALID_PUBLIC_FINGERPRINT")
    return normalized


def safe_archive_path(value):
    require(isinstance(value, str) and value and "\\" not in value and ":" not in value,
            "UNSAFE_ARCHIVE_PATH")
    path = PurePosixPath(value)
    require(not path.is_absolute() and ".." not in path.parts and
            all(part not in ("", ".", "..") for part in value.split("/")), "UNSAFE_ARCHIVE_PATH")
    return path


def component_name(package_name, value):
    if value.startswith("."):
        return package_name + value
    return value if "." in value else package_name + "." + value


def inspect_manifest(xml, channel):
    document = ET.fromstring(xml)
    package_name = document.attrib.get("package")
    expected_id = RELEASE_ID if channel == "release" else DEVELOPMENT_ID
    require(package_name == expected_id, "APPLICATION_ID_MISMATCH")
    app = document.find("application")
    require(app is not None, "MISSING_APPLICATION")
    debuggable = app.attrib.get(ANDROID + "debuggable", "false") == "true"
    require(debuggable == (channel == "debug"), "DEBUGGABLE_FLAG_MISMATCH")
    require(app.attrib.get(ANDROID + "allowBackup") == "false", "BACKUP_NOT_DISABLED")
    require(app.attrib.get(ANDROID + "usesCleartextTraffic") == "false", "CLEARTEXT_NOT_DISABLED")
    require(component_name(package_name, app.attrib.get(ANDROID + "name", "")) ==
            "org.mobileharness.app.HarnessApplication", "APPLICATION_CLASS_MISMATCH")
    components = {}
    launchers = []
    for child in app:
        if child.tag not in {"activity", "activity-alias", "service", "receiver", "provider"}:
            continue
        name = component_name(package_name, child.attrib.get(ANDROID + "name", ""))
        require(name not in components, "DUPLICATE_COMPONENT")
        components[name] = child
        for intent in child.findall("intent-filter"):
            actions = {n.attrib.get(ANDROID + "name") for n in intent.findall("action")}
            categories = {n.attrib.get(ANDROID + "name") for n in intent.findall("category")}
            if "android.intent.action.MAIN" in actions and "android.intent.category.LAUNCHER" in categories:
                launchers.append(name)
        if child.tag == "provider":
            require(child.attrib.get(ANDROID + "exported") == "false", "EXPORTED_PROVIDER")
            authorities = child.attrib.get(ANDROID + "authorities", "").split(";")
            require(all(a.startswith(expected_id + ".") for a in authorities), "PROVIDER_AUTHORITY_CHANNEL_MISMATCH")
    require(launchers == [MAIN_CLASS], "MAIN_ACTIVITY_CLASS_MISMATCH")
    require(SERVICE_CLASS in components and CONTROL_CLASS in components, "MISSING_CONTROL_COMPONENT")
    require(components[SERVICE_CLASS].attrib.get(ANDROID + "permission") ==
            "android.permission.BIND_ACCESSIBILITY_SERVICE", "ACCESSIBILITY_PERMISSION_MISMATCH")
    require(components[CONTROL_CLASS].attrib.get(ANDROID + "exported") == "false", "CONTROL_RECEIVER_EXPORTED")
    debug_components = [name for name in components if name.startswith("org.mobileharness.app.Debug") or
                        name.startswith("org.mobileharness.app.verification.") or
                        name == "androidx.compose.ui.tooling.PreviewActivity"]
    if channel == "release":
        require(not debug_components, "DEBUG_COMPONENT_IN_RELEASE")
        require(document.find("instrumentation") is None, "INSTRUMENTATION_IN_RELEASE")
    else:
        require(bool(debug_components), "DEVELOPMENT_CHECK_COMPONENTS_MISSING")
    require(not any("org.mobileharness.fixture" in name for name in components), "FIXTURE_COMPONENT_IN_APP")
    return {
        "application_id": package_name, "version_name": document.attrib.get(ANDROID + "versionName"),
        "version_code": document.attrib.get(ANDROID + "versionCode"), "main_activity": MAIN_CLASS,
        "debuggable": debuggable, "debug_components": len(debug_components),
        "backup_disabled": True, "private_control_receiver": True, "channel_provider_authorities": True,
    }


def inspect_badging(text, channel):
    labels = dict(re.findall(r"^application-label(?:-([^:]+))?:'([^\r\n]*)'$", text, re.M))
    english = labels.get("en", labels.get("", ""))
    chinese = labels.get("zh", labels.get("zh-CN", ""))
    expected_english = "MobileHarness (Release)" if channel == "release" else "MobileHarness (Dev)"
    expected_chinese = "MobileHarness（正式版）" if channel == "release" else "MobileHarness（开发版）"
    require(english == expected_english and chinese == expected_chinese, "CHANNEL_LABEL_MISMATCH")
    launchable = re.findall(r"^launchable-activity: name='([^']+)'", text, re.M)
    require(launchable == [MAIN_CLASS], "BADGING_LAUNCHER_MISMATCH")
    return {"english": english, "chinese": chinese}


def inspect_signer(text, expected_fingerprint):
    fingerprints = re.findall(r"^Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F: ]+)\s*$", text, re.M)
    require(len(fingerprints) == 1, "UNEXPECTED_SIGNER_COUNT")
    actual = normalize_fingerprint(fingerprints[0])
    require(actual == normalize_fingerprint(expected_fingerprint), "SIGNING_CERTIFICATE_MISMATCH")
    require(re.search(r"Verified using v[23] scheme[^\r\n]*:\s*true", text) is not None,
            "MODERN_SIGNATURE_SCHEME_MISSING")
    return actual


def verify_prompt_sources(project_root):
    root = project_root.resolve()
    checked = set()
    assets = {}
    for version, expected_hash in FROZEN_MANIFEST_SHA256.items():
        manifest = root / "prompts" / version / "manifest.json"
        data = manifest.read_bytes()
        require(sha256(data) == expected_hash, "FROZEN_PROMPT_MANIFEST_CHANGED")
        document = json.loads(data.decode("utf-8-sig"))

        def visit(value):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    relative = safe_archive_path(value["path"])
                    path = root.joinpath(*relative.parts)
                    if not path.is_file():
                        path = manifest.parent.joinpath(*relative.parts)
                    require(path.resolve().is_relative_to(root) and path.is_file(), "PROMPT_REFERENCE_MISSING")
                    content = path.read_bytes()
                    require(sha256(content) == value["sha256"], "FROZEN_PROMPT_REFERENCE_CHANGED")
                    if "bytes" in value:
                        require(len(content) == value["bytes"], "FROZEN_PROMPT_SIZE_CHANGED")
                    checked.add(path.resolve())
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)

        visit(document)
        if version in RUNTIME_BUNDLES:
            supplements = FROZEN_RUNTIME_SUPPLEMENTS.get(version, {})
            for relative, expected in supplements.items():
                require(sha256((manifest.parent / relative).read_bytes()) == expected, "FROZEN_PROMPT_SUPPLEMENT_CHANGED")
            declared = {entry["path"] for entry in document["files"]} | {"manifest.json"} | set(supplements)
            actual = {p.relative_to(manifest.parent).as_posix() for p in manifest.parent.rglob("*") if p.is_file()}
            require(actual == declared, "UNDECLARED_PROMPT_SOURCE_FILE")
            for relative in declared:
                safe_archive_path(relative)
                assets[f"assets/prompts/{version}/{relative}"] = (manifest.parent / relative).read_bytes()
    return assets, len(checked)


def inspect_archive(apk, channel, prompt_assets):
    with zipfile.ZipFile(apk) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        names = [entry.filename for entry in entries]
        require(len(names) == len(set(names)), "DUPLICATE_APK_ENTRY")
        allowed_assets = set(prompt_assets) | {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}
        require(set(prompt_assets) <= set(names), "RUNTIME_PROMPT_ASSET_MISSING")
        require(not {name for name in names if name.startswith("assets/")} - allowed_assets,
                "UNEXPECTED_BUNDLED_ASSET")
        dex_count = 0
        debug_classes = False
        for entry in entries:
            path = safe_archive_path(entry.filename)
            require(not FORBIDDEN_PARTS.intersection(part.lower() for part in path.parts), "FORBIDDEN_APK_DIRECTORY")
            require(path.suffix.lower() not in FORBIDDEN_SUFFIXES, "FORBIDDEN_APK_FILE")
            require(path.name.lower() not in {"local.properties", "key.properties", "keystore.properties", ".env"} and
                    not path.name.lower().startswith(("signing.", "signing-", "signing_")), "BUNDLED_SIGNING_CONFIGURATION")
            data = archive.read(entry)
            require(not any(pattern.search(data) for pattern in SECRET_PATTERNS), "KNOWN_SECRET_LITERAL_IN_APK")
            if entry.filename in prompt_assets:
                require(data == prompt_assets[entry.filename], "RUNTIME_PROMPT_ASSET_CHANGED")
            if re.fullmatch(r"classes\d*\.dex", entry.filename):
                dex_count += 1
                require(b"Lorg/mobileharness/fixture/" not in data, "FIXTURE_CODE_IN_APP")
                found = b"Lorg/mobileharness/app/Debug" in data or b"Lorg/mobileharness/app/verification/" in data
                debug_classes = debug_classes or found
        require(dex_count > 0, "APK_DEX_MISSING")
        if channel == "release":
            require(not debug_classes, "DEBUG_CODE_IN_RELEASE")
        else:
            require(debug_classes, "DEVELOPMENT_CHECK_CODE_MISSING")
        return {"entries": len(entries), "dex_files": dex_count, "runtime_prompt_assets": len(prompt_assets),
                "runtime_prompts_byte_identical": True, "asset_allowlist_matched": True,
                "known_secret_literals_absent": True, "fixture_code_absent": True,
                "debug_code_absent": not debug_classes}


def sdk_tools(sdk):
    versions = [p for p in (sdk / "build-tools").iterdir() if p.is_dir() and re.fullmatch(r"\d+\.\d+\.\d+", p.name)]
    require(bool(versions), "SDK_BUILD_TOOLS_MISSING")
    build_tools = max(versions, key=lambda p: tuple(map(int, p.name.split("."))))
    windows = os.name == "nt"
    tools = {
        "aapt": build_tools / ("aapt.exe" if windows else "aapt"),
        "apksigner": build_tools / ("apksigner.bat" if windows else "apksigner"),
        "apkanalyzer": sdk / "cmdline-tools/latest/bin" / ("apkanalyzer.bat" if windows else "apkanalyzer"),
    }
    require(all(path.is_file() for path in tools.values()), "SDK_VERIFICATION_TOOL_MISSING")
    return tools


def run_sdk(tool, arguments, java_home):
    environment = os.environ.copy()
    if java_home:
        require(java_home.is_dir(), "JAVA_HOME_MISSING")
        environment["JAVA_HOME"] = str(java_home)
    # Do not inherit build/signing credentials into the read-only SDK tools.
    for key in list(environment):
        if key.startswith("MOBILEHARNESS_RELEASE_"):
            del environment[key]
    result = subprocess.run([str(tool), *map(str, arguments)], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=90, env=environment)
    require(result.returncode == 0, "SDK_VERIFICATION_COMMAND_FAILED")
    return result.stdout


def verify(arguments):
    fingerprint = normalize_fingerprint(arguments.release_cert_sha256)
    require(fingerprint != DEVELOPMENT_CERT_SHA256, "RELEASE_USES_DEVELOPMENT_CERTIFICATE")
    tools = sdk_tools(arguments.sdk)
    assets, references = verify_prompt_sources(arguments.project_root)
    channels = {}
    for channel, apk, expected_cert in (
        ("debug", arguments.debug_apk, DEVELOPMENT_CERT_SHA256),
        ("release", arguments.release_apk, fingerprint),
    ):
        require(apk.is_file(), "APK_NOT_FOUND")
        metadata = inspect_manifest(run_sdk(tools["apkanalyzer"], ["manifest", "print", apk], arguments.java_home), channel)
        metadata["labels"] = inspect_badging(run_sdk(tools["aapt"], ["dump", "badging", apk], arguments.java_home), channel)
        metadata["certificate_sha256"] = inspect_signer(
            run_sdk(tools["apksigner"], ["verify", "--verbose", "--print-certs", apk], arguments.java_home), expected_cert)
        metadata["archive"] = inspect_archive(apk, channel, assets)
        metadata["apk_sha256"] = sha256(apk.read_bytes())
        metadata["apk_bytes"] = apk.stat().st_size
        channels[channel] = metadata
    require(channels["debug"]["version_code"] == channels["release"]["version_code"], "CHANNEL_VERSION_CODE_MISMATCH")
    return {"status": "PASS", "schema_version": "1.0.0", "channels": channels,
            "frozen_prompt_manifests": len(FROZEN_MANIFEST_SHA256), "frozen_prompt_references": references,
            "phone_accessed": False, "network_or_model_called": False, "private_signing_material_read": False,
            "scope": "Static APK integrity and known-secret-pattern checks; no runtime or task-success claim."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=ROOT)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--debug-apk", type=Path)
    parser.add_argument("--release-apk", type=Path)
    parser.add_argument("--release-cert-sha256", required=True, help="Public certificate SHA-256 fingerprint only")
    parser.add_argument("--output", type=Path)
    arguments = parser.parse_args(argv)
    base = arguments.project_root / "android-app/app/build/outputs/apk"
    arguments.debug_apk = (arguments.debug_apk or base / "debug/app-debug.apk").resolve()
    arguments.release_apk = (arguments.release_apk or base / "release/app-release.apk").resolve()
    try:
        result = verify(arguments)
    except VerificationError as failure:
        result = {"status": "FAIL", "reason": str(failure)}
    except Exception:
        # Paths, XML payloads and tool diagnostics may contain sensitive data.
        result = {"status": "FAIL", "reason": "VERIFICATION_INPUT_OR_TOOL_ERROR"}
    encoded = json.dumps(result, ensure_ascii=False, indent=2)
    if arguments.output:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(encoded + "\n", encoding="utf-8")
    print(encoded)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
