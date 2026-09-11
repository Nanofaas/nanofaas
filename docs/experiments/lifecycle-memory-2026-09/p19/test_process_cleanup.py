"""Real child lifetimes: cleanup starts with the first acquired process."""
import json
import pathlib
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch
import http_runner


class ProcessCleanupTest(unittest.TestCase):
    def test_second_spawn_failure_reaps_first_child_and_preserves_record(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        try:
            with tempfile.TemporaryDirectory() as root:
                args = types.SimpleNamespace(output=pathlib.Path(root)/'run', scenario='sync',
                                             probe=pathlib.Path(root), jar=pathlib.Path(__file__),
                                             label='B1', revision='B', profile='none', warmup=1, count=1, rate=1)
                with patch.object(http_runner.subprocess, 'Popen', side_effect=[child, OSError('second spawn refused')]):
                    with self.assertRaisesRegex(OSError, 'second spawn'):
                        http_runner.run(args)
                self.assertIsNotNone(child.poll(), 'first child leaked when second spawn failed')
                record = json.loads((args.output/'run.json').read_text())
                self.assertFalse(record['valid'])
                self.assertIn('second spawn', record['failure'])
        finally:
            if child.poll() is None:
                child.kill()
            child.wait()

    def test_timeout_kills_owned_grandchild_that_ignores_term(self):
        source = pathlib.Path(__file__).with_name('supervision.py')
        self.assertTrue(source.exists(), 'owned process-group supervisor is required')
        from supervision import supervise
        with tempfile.TemporaryDirectory() as root:
            folder = pathlib.Path(root)
            command = [sys.executable, '-c',
                       'import subprocess,sys,time,pathlib; '
                       'p=subprocess.Popen([sys.executable,"-c","import signal,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(60)"]); '
                       'pathlib.Path(sys.argv[1]).write_text(str(p.pid)); time.sleep(60)', str(folder/'pid')]
            result = supervise(command, folder/'supervision.json', timeout=.5, grace=.3)
            self.assertEqual(result['status'], 'timeout')
            self.assertTrue(result['kill_sent'])
            pid = int((folder/'pid').read_text())
            stat = pathlib.Path(f'/proc/{pid}/stat')
            self.assertTrue(not stat.exists() or stat.read_text().split(') ')[1].startswith('Z'), 'grandchild still executing')
            self.assertEqual(json.loads((folder/'supervision.json').read_text())['status'], 'timeout')
