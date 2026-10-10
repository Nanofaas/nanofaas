import copy
import json
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator, FormatChecker, ValidationError

ROOT = Path(__file__).parent


def validator():
    return Draft202012Validator(json.loads((ROOT / 'logging-schema.json').read_text()), format_checker=FormatChecker())


def corpus():
    return json.loads((ROOT / 'logging-corpus.json').read_text())


def test_corpus_events_are_valid_and_roundtrip_as_one_line():
    cases = corpus()['cases']
    assert len(cases) >= 4
    for case in cases:
        event = case['expected']
        validator().validate(event)
        encoded = json.dumps(event, ensure_ascii=False)
        assert len(encoded.splitlines()) == 1
        assert json.loads(encoded) == event


@pytest.mark.parametrize('field,value', [('schemaVersion', 2), ('schemaVersion', True), ('attempt', 0), ('attempt', True), ('timestamp', 'yesterday'), ('level', 'info'), ('executionId', None)])
def test_schema_rejects_invalid_events(field, value):
    event = copy.deepcopy(corpus()['cases'][0]['expected'])
    event[field] = value
    with pytest.raises(ValidationError):
        validator().validate(event)


def test_context_is_optional_and_application_fields_are_nested():
    event = copy.deepcopy(corpus()['cases'][0]['expected'])
    for key in ('executionId', 'traceId', 'functionName', 'attempt'):
        event.pop(key, None)
    event['attributes'] = {'executionId': 'user-value', 'level': 'user-value'}
    validator().validate(event)
