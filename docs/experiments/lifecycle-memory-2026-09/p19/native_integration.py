"""Explicit Docker gate, separate from the portable contract suite."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


class NativeInvocationTest(unittest.TestCase):
    def test_minimal_and_managed_invocation_replay_shutdown(self):
        with tempfile.TemporaryDirectory(prefix='p19-native-contract-') as root:
            for selection in ['none', 'container']:
                with self.subTest(selection=selection):
                    result = subprocess.run([sys.executable, str(pathlib.Path(__file__).with_name('native_smoke.py')), root, selection], timeout=120)
                    self.assertEqual(result.returncode, 0)
                    record = json.loads((pathlib.Path(root)/'smoke'/('native-'+selection)/'result.json').read_text())
                    self.assertIn('invocation', record, 'health-only is not invocation evidence')
                    self.assertEqual(record['invocation']['status'], 200)
                    self.assertEqual(record['replay']['response']['executionId'], record['invocation']['response']['executionId'])
                    self.assertTrue(record['valid'])
                    self.assertEqual(record['stop_exit'], 0)
                    self.assertEqual(record['remove_exit'], 0)
                    self.assertEqual(record['owned_function_containers_after_remove'], [])
