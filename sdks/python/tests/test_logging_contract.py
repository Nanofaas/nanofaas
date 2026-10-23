import asyncio
import json
import logging
import subprocess
import sys
from pathlib import Path

from nanofaas.sdk import context
from nanofaas.sdk.logging import JsonFormatter
import pytest

CORPUS = Path(__file__).resolve().parents[2] / 'runtime-contract/logging-corpus.json'


def test_json_formatter_consumes_shared_corpus(monkeypatch):
    monkeypatch.setenv('NANOFAAS_LOG_SERVICE', 'function-runtime')
    for case in json.loads(CORPUS.read_text())['cases']:
        data = case['input']
        context.set_context(data.get('executionId'), data.get('traceId'))
        record = logging.LogRecord('contract', getattr(logging, data['level'].replace('WARN', 'WARNING')), '', 1, data['message'], (), None)
        record.attributes = data.get('attributes', {})
        line = JsonFormatter().format(record)
        assert len(line.splitlines()) == 1
        event = json.loads(line)
        for key, value in case['expected'].items():
            if key not in ('timestamp', 'exception'):
                assert event[key] == value
        assert event['timestamp'].endswith('Z')
        if 'executionId' not in data:
            assert 'executionId' not in event
    context.set_context(None, None)


def test_scoped_context_restores_parent_and_isolates_concurrent_work():
    async def run():
        context.set_context('parent', 'parent-trace')
        async def work(identity):
            with context.invocation_context(identity, 'trace-' + identity):
                await asyncio.sleep(0)
                fields = json.loads(JsonFormatter().format(logging.LogRecord('worker', logging.INFO, '', 1, 'working', (), None)))
                assert fields['executionId'] == identity
                try:
                    with context.invocation_context('nested', None):
                        raise ValueError('failure')
                except ValueError:
                    pass
                assert context.get_execution_id() == identity
        await asyncio.gather(work('one'), work('two'))
        assert context.get_execution_id() == 'parent'
        context.set_context(None, None)
    asyncio.run(run())


def test_importing_runtime_does_not_replace_host_logging():
    code = 'import logging; h=logging.StreamHandler(); logging.getLogger().addHandler(h); import nanofaas.runtime.app; assert h in logging.getLogger().handlers'
    subprocess.run([sys.executable, '-c', code], check=True)


@pytest.mark.parametrize('sample', [float('nan'), float('inf'), -float('inf')])
def test_non_finite_attributes_cannot_break_json(sample):
    record = logging.LogRecord('probe', logging.INFO, '', 1, 'numeric sample', (), None)
    record.attributes = {'sample': sample}
    def reject(value):
        raise ValueError('not JSON: ' + value)
    event = json.loads(JsonFormatter().format(record), parse_constant=reject)
    assert event['message'] == 'numeric sample'
