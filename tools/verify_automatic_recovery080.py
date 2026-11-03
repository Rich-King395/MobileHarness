"""Offline policy/schema/frozen-prompt boundaries; no phone, model or credentials."""
import copy
import hashlib
import json
from pathlib import Path
import re

import jsonschema
import verify_localization060 as previous

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'android-app/app/src/main'
CODE = MAIN / 'java/org/mobileharness/app'


def main():
    schema_path = ROOT / 'docs/data-contracts/minimal-event.v1.3.schema.json'
    schema = json.loads(schema_path.read_text('utf-8-sig'))
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    checks = 0

    def check(data, expected=True):
        nonlocal checks
        assert validator.is_valid(data) == expected, data.get('event')
        checks += 1

    def event(kind, data):
        return {'schema_version': '1.3.0', 'session_id': '00000000-0000-4000-8000-000000000080',
                'seq': 1, 'elapsed_ms': 0, 'event': kind, 'data': data}

    recovery = {'recovery_id': 1, 'target': 'GUI', 'attempt': 1, 'delay_ms': 1000,
                'trigger_code': 'TRANSPORT_ERROR', 'status': 'scheduled'}
    for target in ['GUI', 'OBSERVATION', 'PLANNER_OBSERVATION']:
        for status in ['scheduled', 'started', 'cancelled']:
            for attempt, delay in [(1, 1000), (2, 3000)]:
                check(event('recovery', {**recovery, 'target': target, 'status': status,
                                         'attempt': attempt, 'delay_ms': delay}))
    for key, value in [('attempt', 3), ('delay_ms', 0), ('target', 'PRIVATE_TASK'),
                       ('recovery_id', 0), ('status', 'PRIVATE_REPLY'), ('trigger_code', 'PRIVATE_ERROR')]:
        check(event('recovery', {**recovery, key: value}), False)
    for key in recovery:
        data = copy.deepcopy(recovery)
        del data[key]
        check(event('recovery', data), False)
    for key in ['task', 'screenshot', 'frame_id', 'api_key', 'response', 'coordinates', 'package_name']:
        check(event('recovery', {**recovery, key: 'SYNTHETIC_PRIVATE_SENTINEL'}), False)
    for reason in ['DNS_FAILURE', 'TLS_HANDSHAKE_FAILED', 'TLS_PEER_UNVERIFIED', 'TIMEOUT',
                   'CONNECT_FAILED', 'REQUEST_REPLAY_BLOCKED', 'NETWORK_IO']:
        check(event('error', {'category': 'MODEL', 'code': 'TRANSPORT_ERROR', 'transport_reason': reason}))
    check(event('error', {'category': 'MODEL', 'code': 'API_ERROR', 'http_status': 429}))
    check(event('error', {'category': 'DEVICE', 'code': 'INPUT_FAILED', 'transport_reason': 'TIMEOUT'}), False)
    check(event('error', {'category': 'MODEL', 'code': 'TRANSPORT_ERROR', 'transport_reason': 'PRIVATE_TEXT'}), False)
    check(event('error', {'category': 'DEVICE', 'code': 'INPUT_FAILED', 'http_status': 503}), False)
    check(event('error', {'category': 'MODEL', 'code': 'API_ERROR', 'http_status': 600}), False)
    for source in ['none', 'gui', 'planner', 'confirmation']:
        check(event('session_ended', {'reason': 'runtime_failure', 'completion_source': source,
                                    'gui_steps_used': 3, 'planner_calls_used': 0}), source == 'none')

    frozen = set()
    for manifest in ROOT.glob('prompts/v*/manifest.json'):
        def visit(value):
            if isinstance(value, dict):
                if 'path' in value and 'sha256' in value:
                    path = ROOT / value['path']
                    if not path.is_file():
                        path = manifest.parent / value['path']
                    assert hashlib.sha256(path.read_bytes()).hexdigest() == value['sha256'], path
                    frozen.add(path)
                for child in value.values():
                    visit(child)
            elif isinstance(value, list):
                for child in value:
                    visit(child)
        visit(json.loads(manifest.read_text('utf-8-sig')))
    unchanged = {name: digest for name, digest in previous.FROZEN_SOURCE.items()
                 if name not in {'core/Coordinator.kt', 'core/Contracts.kt', 'storage/MinimalAudit.kt'}}
    for name, digest in unchanged.items():
        assert hashlib.sha256((CODE / name).read_bytes()).hexdigest() == digest, name
    # The original three data contracts stay frozen; 1.3 adds metadata only.
    old_schema_hashes = {
        'minimal-event.schema.json': 'f6c44d90974673f3a507ed083c835cacf76b7cbe1dc300b60f6014b9a89000bd',
        'minimal-event.v1.1.schema.json': '2c6a53620c310e9d7f9b42e0cddab48a86be3ef7ed38d572e7c18ae3f6af9781',
    }
    for name, digest in old_schema_hashes.items():
        assert hashlib.sha256((ROOT / 'docs/data-contracts' / name).read_bytes()).hexdigest() == digest, name
    english, _, invariant = previous.resources(MAIN / 'res/values')
    chinese, _, _ = previous.resources(MAIN / 'res/values-zh')
    assert set(english) - invariant == set(chinese)
    for key in chinese:
        assert sorted(previous.FORMAT.findall(english[key])) == sorted(previous.FORMAT.findall(chinese[key])), key
    used = set()
    for path in CODE.rglob('*.kt'):
        used.update(re.findall(r'(?<![\w.])R\.string\.(\w+)', path.read_text('utf-8-sig')))
    assert used <= set(english)
    policy = json.loads((ROOT / 'docs/runtime-policies/automatic-recovery.v1.2.json').read_text('utf-8-sig'))
    assert policy['recovery']['delays_ms'] == [1000, 3000]
    assert policy['planner']['maximum_calls'] == 2
    assert policy['planner']['maximum_readonly_check_rounds'] == 1
    print(json.dumps({'status': 'PASS', 'app_version': '0.8.0-dev',
                      'schema_boundary_checks': checks, 'frozen_prompt_references': len(frozen),
                      'unchanged_model_execution_profile_sources': len(unchanged),
                      'english_keys': len(english), 'chinese_keys': len(chinese),
                      'old_validators_and_prompts_rewritten': False}))


if __name__ == '__main__':
    main()
