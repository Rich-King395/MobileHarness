"""0.7 record UI/contract checks. No phone, model, user task or credential access."""
from pathlib import Path
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import jsonschema
import verify_localization060 as previous_ui

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android-app/app/src/main'
CODE = MAIN / 'java/org/mobileharness/app'


def main():
    english, _, invariant = previous_ui.resources(MAIN / 'res/values')
    chinese, _, _ = previous_ui.resources(MAIN / 'res/values-zh')
    assert set(english) - invariant == set(chinese)
    for key in chinese:
        assert sorted(previous_ui.FORMAT.findall(english[key])) == sorted(previous_ui.FORMAT.findall(chinese[key])), key
    for name in ['MainActivity.kt', 'ui/ExperimentRecordCard.kt']:
        text = (CODE / name).read_text('utf-8-sig')
        assert not [literal for literal in previous_ui.literals(text) if previous_ui.HAN.search(literal)], name
    used = set()
    for source in CODE.rglob('*.kt'):
        used.update(re.findall(r'(?<![\w.])R\.string\.(\w+)', source.read_text('utf-8-sig')))
    assert used <= set(english), used - set(english)
    # MinimalAudit intentionally changes in 0.7; the old 0.6 validator/hash remains untouched.
    unchanged = {name: digest for name, digest in previous_ui.FROZEN_SOURCE.items() if name != 'storage/MinimalAudit.kt'}
    for name, digest in unchanged.items():
        assert hashlib.sha256((CODE / name).read_bytes()).hexdigest() == digest, name
    frozen = set()
    for manifest in ROOT.glob('prompts/v*/manifest.json'):
        def visit(value):
            if isinstance(value, dict):
                if 'path' in value and 'sha256' in value:
                    path = ROOT / value['path']
                    if not path.is_file():
                        path = manifest.parent / value['path']
                    assert hashlib.sha256(path.read_bytes()).hexdigest() == value['sha256'], path.name
                    frozen.add(path)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(json.loads(manifest.read_text('utf-8-sig')))
    schema = json.loads((ROOT / 'docs/data-contracts/local-task-record.v1.schema.json').read_text('utf-8-sig'))
    validator = jsonschema.Draft202012Validator(schema)
    jsonschema.Draft202012Validator.check_schema(schema)
    payload = {'schema_version': 1, 'session_id': '00000000-0000-4000-8000-000000000701', 'task': 'Synthetic task\n合成任务 🧪'}
    validator.validate(payload)
    for extra in ['api_key', 'image', 'model_reply', 'action_parameters']:
        assert not validator.is_valid({**payload, extra: 'SYNTHETIC_PRIVATE_SENTINEL'})
    assert not validator.is_valid({**payload, 'task': '   '})
    event_schema = json.loads((ROOT / 'docs/data-contracts/minimal-event.v1.2.schema.json').read_text('utf-8-sig'))
    # No new content field is permitted in the existing content-free session event.
    row = {'schema_version': '1.2.0', 'session_id': payload['session_id'], 'seq': 1, 'elapsed_ms': 0,
           'event': 'session_started', 'data': {'task': payload['task']}}
    assert not jsonschema.Draft202012Validator(event_schema).is_valid(row)
    audit = (CODE / 'storage/MinimalAudit.kt').read_text('utf-8-sig')
    export = audit.split('fun exportMetadata(', 1)[1].split('fun deleteSession(', 1)[0]
    assert 'localTasks' not in export and 'readLocalTask' not in export
    assert audit.index('localTasks.save(sessionId, options.task)') < audit.index('try { write("session_started", metadata) }')
    print(json.dumps({'status': 'PASS', 'app_version': '0.7.0-dev', 'english_keys': len(english),
                      'chinese_keys': len(chinese), 'unchanged_core_model_profile_files': len(unchanged),
                      'frozen_references': len(frozen), 'metadata_task_field_rejected': True,
                      'local_attachment_schema_version': 1, 'old_validator_unchanged': True}))


if __name__ == '__main__':
    main()
