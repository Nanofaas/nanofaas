from pathlib import Path
import hashlib,importlib.util,json,sys,tempfile
from unittest.mock import patch
from datetime import UTC,datetime
root=Path('/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-readiness-20261013/oracle-freeze-05')
out=Path('/home/michele/Documenti/nanofaas/.worktrees/edge-logging/docs/experiments/one-shot-review-2026-10-13/evidence')
sys.path.insert(0,str(root/'package'))
import yaml
from nanolab.config import EnvironmentConfig,ScenarioConfig
from nanolab.one_shot.supervision import CampaignSupervision
from nanolab.tasks.one_shot.artifacts import package_fingerprint
from nanolab.tasks.recipes.workflow import read_distribution
spec=importlib.util.spec_from_file_location('frozen_observer',root/'source-used.py')
observer=importlib.util.module_from_spec(spec);spec.loader.exec_module(observer)
frozen=json.loads((root/'freeze.json').read_text())
observer.verify_files(root,frozen)
observer.validate_operator_protocol(json.loads((root/'operator-protocol.json').read_text()))
assert package_fingerprint()==frozen['nanolabPackageSha256']
assert sys.version==frozen['python']
assert all(observer.metadata.version(k)==v for k,v in frozen['dependencies'].items())
scenario=yaml.safe_load((root/'scenario.yaml').read_text())
original=yaml.safe_load(Path('/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-first-experiment-20261012/load-tuning-02/load-scenario.yaml').read_text())
assert scenario['oneShot']['nodes']==original['oneShot']['nodes']
assert scenario['oneShot']['functions']==original['oneShot']['functions']
trace=json.loads((root/'trace.json').read_text())
original_trace=json.loads(Path('/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-first-experiment-20261012/load-tuning-02/trace.json').read_text())
assert trace==original_trace
protocol=json.loads((root/'operator-protocol.json').read_text())
assert protocol['protocol']['maxRounds']==8
assert protocol['protocol']['maxSolverBytes']==64*1024*1024
assert protocol['oracleMaxAgeSeconds']==4230
assert protocol['performanceAcceptanceGates'] is False
built=read_distribution(root/'distribution.json',recipe=root/'recipe.yaml',tag='readiness-20261013',published=False)
assert built.source['dirty'] is False
assert built.source['revision']==frozen['sourceRevision']
with tempfile.TemporaryDirectory(prefix='one-shot-dag-verify-') as raw, patch('nanolab.one_shot.infrastructure.external_vm_cpus', return_value=0), patch('nanolab.one_shot.infrastructure.host_capacity', return_value=(20, 65536)):
 config=ScenarioConfig.model_validate(scenario)
 workflow=observer.build_one_shot_observation_plan(config,EnvironmentConfig.model_validate({'provider':'multipass','roles':{'stack':{'name':'one-shot'}}}),freeze_dir=root,run_dir=Path(raw),supervision=CampaignSupervision(Path(raw)))
 units=workflow.compile().tasks
 graph=[{'id':getattr(u,'id',None),'kind':u.kind,'title':u.task.title if u.task else u.resource.title,'requires':[r.title for r in u.required_resources],'resource':u.resource.title if u.resource else None} for u in units]
 assert sum(u.kind=='acquire' for u in units)==sum(u.kind=='release' for u in units)
 assert any(u.task and type(u.task).__name__=='RunOneShotLoadTask' for u in units)
(out/'compiled-workflow.json').write_text(json.dumps(graph,indent=2)+'\n')
receipt={'atUtc':datetime.now(UTC).isoformat(),'freeze':str(root),'fingerprint':hashlib.sha256((root/'freeze.json').read_bytes()).hexdigest(),'frozenFilesVerified':len(frozen['files']),'runtimeSourceFilesVerified':len(frozen['sourceFiles']),'dependencyFilesVerified':len(frozen['dependencyFiles']),'compiledUnits':len(graph),'nodesFunctionsTraceUnchanged':True,'rounds':8,'vmAcquisitions':0,'campaignStarted':False,'dagHostInventory':'simulated for offline compilation; real inventory deferred to run','sourceRevision':frozen['sourceRevision']}
(out/'freeze-verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
