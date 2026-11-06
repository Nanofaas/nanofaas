import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest


class ArtifactVerificationTest(unittest.TestCase):
    def test_stale_source_missing_artifact_and_modified_binary_are_rejected(self):
        script = Path(__file__).with_name('build_guest.py')
        spec = importlib.util.spec_from_file_location('guest_build', script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            module.GUEST = root / 'guest'
            shutil.copytree(script.parent, module.GUEST, ignore=shutil.ignore_patterns('target', '__pycache__'))
            module.ARTIFACT = root / 'holt-v1.wasm'
            module.MANIFEST = root / 'manifest.json'
            module.ARTIFACT.write_bytes(b'wasm-test')
            module.MANIFEST.write_text(json.dumps(module.snapshot()))
            module.verify()
            source = module.GUEST / 'src/lib.rs'
            original = source.read_bytes()
            source.write_bytes(original + b'\n// changed')
            with self.assertRaises(ValueError): module.verify()
            source.write_bytes(original)
            module.ARTIFACT.write_bytes(b'changed')
            with self.assertRaises(ValueError): module.verify()
            module.ARTIFACT.unlink()
            with self.assertRaises(FileNotFoundError): module.verify()


if __name__ == '__main__': unittest.main()
