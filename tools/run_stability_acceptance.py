"""Collect closed diagnostic metadata only; never pull images, tasks or credentials.

Run one command at a time. Device cases use synthetic fixtures and local gateways.
No force-stop, uninstall, settings changes, logcat or raw record export is used.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
ADB = Path(r"D:\Android\Sdk\platform-tools\adb.exe")
PACKAGE = "org.mobileharness.app"
SUITES = {
    "recovery": ("DebugAutomaticRecoveryVerificationActivity", "automatic-recovery-verification.json"),
    "notification": ("DebugNotificationDeterministicActivity", "notification-deterministic-verification.json"),
    "storage": ("DebugTaskRecordsVerificationActivity", "task-records-verification.json"),
    "harness": ("DebugHarnessAcceptanceActivity", "harness-deterministic-verification.json"),
}


def adb(*args):
    result = subprocess.run([str(ADB), *args], capture_output=True, text=True,
                            encoding="utf-8", errors="strict", timeout=25)
    if result.returncode:
        raise RuntimeError("ADB_COMMAND_FAILED")
    return result.stdout.strip()


def readiness():
    adb("shell", "am", "broadcast", "-n", PACKAGE + "/.DebugOverlayReadinessReceiver",
        "-a", PACKAGE + ".action.DEBUG_READ_OVERLAY_READINESS")
    return json.loads(adb("shell", "run-as", PACKAGE, "cat", "no_backup/overlay-readiness-verification.json"))


def write(path, data):
    if path.exists():
        raise RuntimeError("EVIDENCE_ALREADY_EXISTS")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def snapshot():
    ready = readiness()
    if ready["runtime_state"] not in ("IDLE", "ENDED") or not ready["controls_match_runtime"]:
        raise RuntimeError("ACTUAL_SESSION_NOT_IDLE")
    # Fixed remote shell code. Only ciphertext and content-free event hashes leave the phone.
    code = ("run-as org.mobileharness.app sh -c 'sha256sum "
            "no_backup/events/*.jsonl no_backup/local-task-records/*.enc "
            "no_backup/profiles/*.enc no_backup/authorized-apps/*.enc'")
    hashes = {}
    for line in adb("shell", code).splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})\s+(no_backup/(?:events|local-task-records|profiles|authorized-apps)/[A-Za-z0-9_.-]+)", line)
        if not match:
            raise RuntimeError("UNEXPECTED_HASH_METADATA")
        digest, path = match.groups()
        hashes[path] = digest
    if not all(any(p.startswith("no_backup/" + folder + "/") for p in hashes)
               for folder in ("events", "local-task-records", "profiles", "authorized-apps")):
        raise RuntimeError("PRESERVATION_CATEGORY_MISSING")
    package_path = adb("shell", "pm", "path", PACKAGE)
    if not re.fullmatch(r"package:/data/app/[^\s]+/base\.apk", package_path):
        raise RuntimeError("UNEXPECTED_INSTALLED_APK_PATH")
    installed = adb("shell", "sha256sum", package_path.removeprefix("package:")).split()[0]
    if not re.fullmatch("[0-9a-f]{64}", installed):
        raise RuntimeError("UNEXPECTED_INSTALLED_APK_HASH")
    return {"captured_at_utc": datetime.now(timezone.utc).isoformat(), "readiness": ready,
            "installed_apk_sha256": installed, "file_hashes": hashes,
            "file_counts": {folder: sum(p.startswith("no_backup/" + folder + "/") for p in hashes)
                            for folder in ("events", "local-task-records", "profiles", "authorized-apps")},
            "file_contents_read_on_host": False, "keys_decrypted": False, "screenshots_saved": False}


def run_suite(suite, timeout, prefix):
    ready = readiness()
    if ready["runtime_state"] not in ("IDLE", "ENDED") or not ready["controls_match_runtime"]:
        raise RuntimeError("ACTUAL_SESSION_NOT_IDLE")
    if not ready["readiness_unlocked"] or not ready["service_connected"]:
        raise RuntimeError("DEVICE_NOT_READY")
    activity, filename = SUITES[suite]
    previous = adb("shell", "run-as", PACKAGE, "cat", "no_backup/" + filename)
    command = ["shell", "am", "start", "-n", PACKAGE + "/." + activity]
    if prefix:
        if suite != "harness" or not re.fullmatch("[A-Z0-9_-]{1,80}", prefix):
            raise RuntimeError("INVALID_PREFIX")
        command.extend(["--es", "prefix", prefix])
    started_at = datetime.now(timezone.utc).isoformat()
    adb(*command)
    deadline = time.monotonic() + timeout
    fresh = False
    while time.monotonic() < deadline:
        raw = adb("shell", "run-as", PACKAGE, "cat", "no_backup/" + filename)
        fresh = fresh or raw != previous
        result = json.loads(raw)
        if fresh and result.get("status") not in ("RUNNING", "PENDING", "NOT_RUN"):
            return {"collection_started_at_utc": started_at, "suite": suite,
                    "fresh_result_observed": True, "evidence_sha256": hashlib.sha256(raw.encode()).hexdigest(),
                    "device_result": result, "post_readiness": readiness()}
        time.sleep(1)
    raise RuntimeError("SUITE_TIMEOUT_REQUIRES_REVIEW_NO_AUTOMATIC_INPUT")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("snapshot", "run"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--suite", choices=SUITES)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--prefix", default="")
    args = parser.parse_args()
    if args.mode == "run" and args.suite is None:
        parser.error("run requires --suite")
    if args.output.exists():
        raise RuntimeError("EVIDENCE_ALREADY_EXISTS")
    data = snapshot() if args.mode == "snapshot" else run_suite(args.suite, args.timeout, args.prefix)
    write(args.output, data)
    if args.mode == "snapshot":
        print(json.dumps({"status": "SAVED", "counts": data["file_counts"], "runtime": data["readiness"]["runtime_state"]}))
    else:
        result = data["device_result"]
        print(json.dumps({"status": result["status"], "suite": args.suite, "cases": len(result.get("cases", [])),
                          "elapsed_ms": result.get("elapsed_ms"), "code": result.get("code")}))
        if result["status"] != "PASS":
            raise SystemExit(1)


if __name__ == "__main__":
    main()
