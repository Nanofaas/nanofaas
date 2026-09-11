import gzip
import hashlib
import json
import pathlib
import tempfile
import unittest
import dossier
from contract import summarize


class DossierIntegrityTest(unittest.TestCase):
    def fixture(self, root):
        from test_revision_identity import pairs
        identities = {run['label']: run for run in pairs()}
        stage = root / 'stage'
        stage.mkdir()
        (stage/'build-identities.json').write_text(json.dumps({'A-none.jar': {'sha256': 'a'*64}, 'B-none.jar': {'sha256': 'b'*64}}))
        row = dict(id='0', admitted=True, success=True, execution_id='e0', latency_ns=100)
        for i in range(1, 4):
            for side in 'AB':
                folder = stage / 'runs' / (side + str(i))
                folder.mkdir(parents=True)
                result = summarize([row], 1, 1000)
                identity = identities[side+str(i)]
                identity['workload_document']['offered'] = 1
                identity['workload'] = hashlib.sha256(json.dumps(identity['workload_document'], sort_keys=True, separators=(',', ':')).encode()).hexdigest()
                result.update(identity)
                result.update(offered=1, admitted=1, unique_successes=1)
                result.update(label=side+str(i), workload='w', config='c', elapsed_ns=1000, valid=True,
                              schema='p19-http-run-v1')
                result.update(workload=identity['workload'], config=identity['config'])
                (folder / 'run.json').write_text(json.dumps(result))
                with gzip.open(folder / 'requests.jsonl.gz', 'wt') as stream:
                    stream.write(json.dumps(row) + '\n')
        files = {str(p.relative_to(stage)): dict(sha256=dossier.digest(p), bytes=p.stat().st_size) for p in stage.rglob('*') if p.is_file()}
        manifest = {'schema': 'nanofaas-p19-dossier-v1', 'files': files}
        (stage / 'manifest.json').write_text(json.dumps(manifest))
        sha = dossier.digest(stage / 'manifest.json')
        (stage / 'SHA256SUMS').write_text(''.join(f"{info['sha256']}  {name}\n" for name, info in files.items()) + f'{sha}  manifest.json\n')
        destination = root / sha
        stage.rename(destination)
        return destination

    def test_raw_accounting_and_hashes_are_reusable(self):
        with tempfile.TemporaryDirectory() as temp:
            dossier.verify(self.fixture(pathlib.Path(temp)))

    def test_modified_checksum_list_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = self.fixture(pathlib.Path(temp))
            (folder / 'SHA256SUMS').write_text('forged\n')
            with self.assertRaisesRegex(ValueError, 'checksum list'):
                dossier.verify(folder)

    def test_modified_payload_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = self.fixture(pathlib.Path(temp))
            (folder / 'runs/A1/run.json').write_text('{}')
            with self.assertRaisesRegex(ValueError, 'payload mismatch'):
                dossier.verify(folder)

    def test_correct_hashes_do_not_excuse_a_wrong_binary_binding(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = self.fixture(pathlib.Path(temp))
            file = folder/'build-identities.json'
            file.write_text(json.dumps({'A-none.jar': {'sha256': 'c'*64}, 'B-none.jar': {'sha256': 'b'*64}}))
            manifest = json.loads((folder/'manifest.json').read_text())
            manifest['files']['build-identities.json'] = {'sha256': dossier.digest(file), 'bytes': file.stat().st_size}
            (folder/'manifest.json').write_text(json.dumps(manifest))
            sha = dossier.digest(folder/'manifest.json')
            (folder/'SHA256SUMS').write_text(''.join(f"{v['sha256']}  {k}\n" for k,v in manifest['files'].items())+f'{sha}  manifest.json\n')
            renamed = folder.with_name(sha)
            folder.rename(renamed)
            with self.assertRaisesRegex(ValueError, 'binary artifact'):
                dossier.verify(renamed)
