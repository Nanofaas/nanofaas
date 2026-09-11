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
        stage = root / 'stage'
        stage.mkdir()
        row = dict(id='0', admitted=True, success=True, execution_id='e0', latency_ns=100)
        for i in range(1, 4):
            for side in 'AB':
                folder = stage / 'runs' / (side + str(i))
                folder.mkdir(parents=True)
                result = summarize([row], 1, 1000)
                result.update(label=side+str(i), workload='w', config='c', elapsed_ns=1000, valid=True,
                              schema='p19-http-run-v1')
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
