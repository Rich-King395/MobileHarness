"""Offline UI localization checks. No phone, HTTP, user data or credential access."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "android-app/app/src/main"
CODE = MAIN / "java/org/mobileharness/app"
ANDROID = "{http://schemas.android.com/apk/res/android}"
HAN = re.compile(r"[\u4e00-\u9fff]")
FORMAT = re.compile(r"%(\d+)\$([dsf])")
LITERAL_OR_COMMENT = re.compile(r'//[^\n]*|/\*.*?\*/|("(?:\\.|[^"\\])*")', re.S)
FROZEN_SOURCE = {
    "core/Coordinator.kt": "8e63231b650f2c113e65f56a56ee1a0b3ad4f1ef80fad1b6c393eefe83c40b97",
    "core/Contracts.kt": "cca822e7a5bf79f67a823276e189d6e25303e7392d412be8ed07d996c9765443",
    "core/ExecutionFailureReason.kt": "8a5fe2dee07b8d1e832e1160dc287f7eb9a6a8ba7ec96ee451f1adaa43bbfdff",
    "model/ModelRequestAssembler.kt": "612a3150f86e476789f3fb5f5e969913da579df6e84ab7c8b62ab6a0b0c7a554",
    "model/QwenGateway.kt": "5c7d520c6dc477c718fca1e3209167d96703ac6f3e1bac8df8f961e1ccff194c",
    "storage/MinimalAudit.kt": "8cae8ce9d5482752065c93ae3d2e05c2ab58b7a3ed3f6727b2f35845cd5a48f2",
    "storage/ProfileStore.kt": "eaad71946c5fdf9cb317fa150fefcc05a5909e9d7c179bf4f78ebba17cd39b4d",
    "storage/AuthorizedAppsStore.kt": "fb6fbb87d56cae6370ae41fcf033a8a732ce0a7c48aa79536837773573808da0",
}


def resources(directory):
    values, elements, untranslatable = {}, {}, set()
    for path in sorted(directory.glob("*.xml")):
        for node in ET.parse(path).getroot():
            if node.tag != "string":
                continue
            name = node.attrib["name"]
            assert name not in values, f"Duplicate resource: {name}"
            values[name] = "".join(node.itertext())
            elements[name] = node
            if node.attrib.get("translatable") == "false":
                untranslatable.add(name)
    return values, elements, untranslatable


def literals(source):
    return [m.group(1)[1:-1] for m in LITERAL_OR_COMMENT.finditer(source) if m.group(1)]


def main():
    english, english_nodes, invariant = resources(MAIN / "res/values")
    chinese, chinese_nodes, _ = resources(MAIN / "res/values-zh")
    required = set(english) - invariant
    assert required <= set(chinese), f"Missing Chinese keys: {sorted(required - set(chinese))}"
    assert set(chinese) <= set(english), f"Chinese-only keys: {sorted(set(chinese) - set(english))}"
    for key in set(chinese):
        assert sorted(FORMAT.findall(english[key])) == sorted(FORMAT.findall(chinese[key])), f"Format mismatch: {key}"
    # Native names in the language picker intentionally remain recognizable in either locale.
    self_names = {"简体中文", "中文", "中文（简体）", "中/EN", "EN/中", "EN / 中", "Language / 语言"}
    unexpected_han = [key for key, value in english.items() if HAN.search(value) and not
                      (re.match(r"(?:language|locale|ui_language|app_language)_", key) and value in self_names)]
    assert not unexpected_han, f"Untranslated default English resources: {unexpected_han}"
    secret_literal = re.compile(r"(?:sk-(?:proj-)?[A-Za-z0-9_-]{20,}|Bearer\s+[A-Za-z0-9._-]{24,}|data:image/[^;]+;base64,)")
    forbidden_placeholder = re.compile(r"^(?:api[_-]?key|secret|password|base64|screenshot|image[_-]?bytes)$", re.I)
    xliff_count = 0
    for group in [english_nodes, chinese_nodes]:
        for key, node in group.items():
            assert not secret_literal.search("".join(node.itertext())), f"Sensitive literal in resource: {key}"
            for child in node.iter():
                if child.tag.endswith("}g"):
                    xliff_count += 1
                    assert not forbidden_placeholder.fullmatch(child.attrib.get("id", "")), f"Sensitive XLIFF value slot: {key}"

    used = set()
    for path in CODE.rglob("*.kt"):
        used.update(re.findall(r"(?<![\w.])R\.string\.(\w+)", path.read_text("utf-8-sig")))
    assert used <= set(english), f"Missing resource references: {sorted(used - set(english))}"

    # Normal MainActivity UI is localized; synthetic previews below @Preview are out of this scan.
    activity = (CODE / "MainActivity.kt").read_text("utf-8-sig").split("@Preview(", 1)[0]
    assert not [s for s in literals(activity) if HAN.search(s)], "Hard-coded Chinese literal in normal MainActivity UI"
    adapter = (CODE / "ui/AppStrings.kt").read_text("utf-8-sig")
    fixed = set(re.findall(r'"([^"\n]*[\u4e00-\u9fff][^"\n]*)"\s+to\s+R\.string', adapter))
    coordinator = (CODE / "core/Coordinator.kt").read_text("utf-8-sig")
    originals = {s for s in literals(coordinator) if HAN.search(s)}
    dynamic = {"恢复规划 $index/2", "等待 Planner 回复（$index/2）", "已暂停 · $detail（${failure.code}）"}
    assert originals <= fixed | dynamic, f"Unmapped runtime messages: {sorted(originals - fixed - dynamic)}"

    for name, digest in FROZEN_SOURCE.items():
        assert hashlib.sha256((CODE / name).read_bytes()).hexdigest() == digest, f"Runtime/model/storage changed: {name}"
    frozen_prompts = set()
    for manifest in ROOT.glob("prompts/v*/manifest.json"):
        def visit(value):
            if isinstance(value, dict):
                if "path" in value and "sha256" in value:
                    path = ROOT / value["path"]
                    if not path.is_file():
                        path = manifest.parent / value["path"]
                    assert hashlib.sha256(path.read_bytes()).hexdigest() == value["sha256"], f"Frozen prompt reference changed: {path.name}"
                    frozen_prompts.add(path)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(json.loads(manifest.read_text("utf-8-sig")))

    app = ET.parse(MAIN / "AndroidManifest.xml").getroot().find("application")
    locale_ref = app.attrib.get(ANDROID + "localeConfig", "")
    assert locale_ref.startswith("@xml/"), "App language localeConfig missing"
    locale_file = MAIN / "res/xml" / (locale_ref.split("/", 1)[1] + ".xml")
    tags = {node.attrib[ANDROID + "name"] for node in ET.parse(locale_file).getroot()}
    assert "en" in tags and tags & {"zh", "zh-Hans", "zh-CN"}, "Both English and Chinese must be declared"

    print(json.dumps({"status": "PASS", "english_resource_keys": len(english), "chinese_resource_keys": len(chinese),
                      "format_parity": True, "xliff_placeholders_checked": xliff_count,
                      "normal_main_activity_chinese_literals": 0, "runtime_messages_covered": len(originals),
                      "unchanged_runtime_model_storage_files": len(FROZEN_SOURCE),
                      "frozen_prompt_references": len(frozen_prompts), "locale_config": sorted(tags)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
