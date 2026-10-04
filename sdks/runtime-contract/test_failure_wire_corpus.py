import copy
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parent))
import pytest
from validate_saturation_wire_corpus import ContractError, validate_document


def corpus():
    return json.loads(Path(__file__).with_name('failure-wire-corpus.json').read_text())


def test_failure_corpus_is_valid():
    validate_document(corpus())


@pytest.mark.parametrize('mutation', ['status', 'callback', 'attempt', 'deadline', 'counters', 'body', 'error', 'missing-callback', 'boolean-status', 'boolean-attempt'])
def test_failure_corpus_rejects_contradictions(mutation):
    data = copy.deepcopy(corpus())
    if mutation == 'status': data['contractDefinitions']['envelope-serialization-failure']['httpStatus'] = 200
    if mutation == 'callback': data['contractDefinitions']['envelope-serialization-failure']['callbackRequired'] = False
    if mutation == 'attempt': data['config']['dispatchAttempt'] = 0
    if mutation == 'deadline': data['config']['deadlineMs'] = float('inf')
    if mutation == 'counters': data['finalCounters']['activeHandlers'] = 1
    if mutation == 'body': data['successOutput'] = {'result': 'wrong'}
    if mutation == 'error': data['contractDefinitions']['envelope-serialization-failure']['errorCode'] = None
    if mutation == 'missing-callback': del data['contractDefinitions']['callback-io-timeout']['callbackRequired']
    if mutation == 'boolean-status': data['contractDefinitions']['envelope-serialization-failure']['httpStatus'] = True
    if mutation == 'boolean-attempt': data['contractDefinitions']['envelope-serialization-failure']['callbackAttempts'] = True
    with pytest.raises(ContractError): validate_document(data)
