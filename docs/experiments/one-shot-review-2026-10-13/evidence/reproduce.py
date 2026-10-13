"""Reproduce review findings without network, VM or source modifications."""
import importlib.util
import json
import tempfile
import threading
import time
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from nanolab.one_shot.supervision import CampaignSupervision, check_sampler
from nanolab.one_shot.models import CampaignManifest
from sonata_engine import Workflow

root = Path('/tmp/nanolab-one-shot-host-placement')
output = Path(__file__).parent
spec = importlib.util.spec_from_file_location('observer_review', root/'scripts/one-shot-observe.py')
observer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(observer)
results = {}

with tempfile.TemporaryDirectory() as temp:
    directory = Path(temp)
    # Execute the actual ConfigureOracle Task, mocking only its API/input adapter.
    manifest = SimpleNamespace(anchor='2026-10-13T00:00:00+00:00')
    (directory/'manifest.json').write_text('{}')
    settings = SimpleNamespace(nodes=[SimpleNamespace(id='edge', kind='edge'),
        SimpleNamespace(id='cloud', kind='cloud')],
        timing=SimpleNamespace(protocol=SimpleNamespace(preparation_seconds=10)))
    settings.model_copy = lambda **_: settings
    run = SimpleNamespace(run_dir=directory, settings=settings,
        topology=SimpleNamespace(endpoints={'edge':'edge','cloud':'cloud'}),
        trace=SimpleNamespace(nodes=['edge','cloud']),
        profile=SimpleNamespace(by_node={'edge':object()}),
        qualification=SimpleNamespace(epsilon=.2))
    client = SimpleNamespace(load_profile=lambda *a, **k:None,
        status=lambda: SimpleNamespace(revision=0,catalog_generations={}),
        load_trace=lambda *a, **k:SimpleNamespace(model_dump=lambda **_:{}))
    captured = {}
    def configuration(*args, **kwargs):
        captured.update(leadSeconds=kwargs['lead'], preparationSeconds=kwargs['protocol'].preparation_seconds)
        return {}
    with patch.object(CampaignManifest, 'model_validate_json', return_value=manifest), \
         patch.object(observer, 'NanoFaasOneShotClient', return_value=client), \
         patch.object(observer, 'configuration_for_node', side_effect=configuration), \
         patch.object(observer, 'configure_node'), patch.object(observer, 'oracle_entries', return_value=[]), \
         patch.object(observer, 'logical_schedule', return_value=('session',0)), patch.object(observer, 'event'):
        observer.ConfigureOracle(run).run(SimpleNamespace(resource=lambda key:'http://'+key))
    results['actualConfigureOracleArguments'] = captured
    assert captured == {'leadSeconds':8, 'preparationSeconds':10}

    # A status-file write failure in the monitor must wake the action; currently it does not.
    supervisor = CampaignSupervision(directory, cadence=.005)
    original_record = supervisor.record
    failures = []
    def record(kind, **values):
        if kind in ('heartbeat','operational.failure'):
            raise OSError('injected status disk failure')
        return original_record(kind, **values)
    reached = []
    def action():
        time.sleep(.05)
        reached.append('action continued after supervisor write failure')
    with patch.object(supervisor, 'record', side_effect=record), \
         patch.object(threading, 'excepthook', side_effect=lambda args: failures.append(str(args.exc_value))):
        try:
            supervisor.run(action)
        except RuntimeError as error:
            results['supervisorFinalError'] = str(error)
        except OSError as error:
            results['supervisorFinalError'] = str(error)
    results['supervisorWriteFailure'] = {'actionContinued':bool(reached), 'uncaughtThreadErrors':failures}
    assert reached and failures

    # Unrelated failed container read hides positive evidence that CP is absent.
    raw = directory/'sampler.jsonl'
    raw.write_text(json.dumps({'containers':[{'role':'collector'}],
        'errors':[{'containerId':'function-restarting','error':'PID gone'}]})+'\n')
    session = SimpleNamespace(require_healthy=lambda:None,
        sessions=[{'output':SimpleNamespace(name=str(raw)), 'node':SimpleNamespace(config=SimpleNamespace(id='edge'))}])
    check_sampler(session)
    results['missingControlPlaneWithUnrelatedReadError'] = 'not detected'

results['sonataWorkflowClass'] = str(Workflow)
archived = Path('/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-first-experiment-20261012/oracle-observation-04')
manifest = CampaignManifest.model_validate_json((archived/'manifest.json').read_bytes())
from nanolab.one_shot import report
with tempfile.TemporaryDirectory() as temp, \
     patch.object(report, 'resource_figures', return_value=[]) as figures:
    report.render_report(manifest, None, Path(temp)/'report.html')
    results['archivedObservationResourceGraphs'] = {
        'vmTelemetryField': manifest.parameters.get('vmTelemetry'),
        'resourceFigureCalls': figures.call_count,
        'rawFiles': len(list((archived/'vm-resources').glob('*.jsonl')))}
    assert figures.call_count == 0
(output/'reproductions.json').write_text(json.dumps(results, indent=2)+'\n')
print(json.dumps(results, indent=2))
