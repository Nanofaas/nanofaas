"""Reject plausible looking copies, forged bindings and overlapping campaigns."""
import copy
import hashlib
import json
import unittest
from contract import validate_pairs


def pairs():
    runs = []
    for i, label in enumerate(['A1', 'B1', 'A2', 'B2', 'A3', 'B3']):
        side = label[0]
        config = {'java_options': ['-Xmx512m'], 'application_options': ['--server.port=18080'],
                  'function': {'name': 'p19'}, 'environment': {'boot_id': 'host', 'affinity': [5, 6, 7, 8]}}
        workload = {'offered': 10, 'warmup': 5, 'rate_per_s': 10}
        sha = lambda data: hashlib.sha256(json.dumps(data, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        runs.append(dict(label=label, revision={'A': '61d72e73528db62cf8ca465c6a037981d7ec13b0',
                                              'B': 'd93b68cdf1cca6e7af17e1c641c8e236c80d0fca'}[side],
                         jar_sha256=side.lower()*64, config=sha(config), config_document=config,
                         workload=sha(workload), workload_document=workload, offered=10, admitted=10,
                         unique_successes=10, process_start_ns=i*100+1, start_ns=i*100+20,
                         end_ns=i*100+40, process_end_ns=i*100+90,
                         processes={role: {'pid': i*10+n, 'start_ticks': i*100+n, 'boot_id': 'host'}
                                    for n, role in enumerate(['generator', 'server', 'backend'], 100)},
                         shutdown={'server_proc_absent': True, 'backend_proc_absent': True}))
    return runs


class RevisionIdentityTest(unittest.TestCase):
    def test_distinct_alternating_processes_are_valid(self):
        validate_pairs(pairs())

    def test_copied_revision_is_rejected(self):
        runs = pairs()
        runs[1]['revision'] = runs[0]['revision']
        with self.assertRaisesRegex(ValueError, 'revision'):
            validate_pairs(runs)

    def test_same_binary_on_both_sides_is_rejected(self):
        runs = pairs()
        for r in runs:
            r['jar_sha256'] = 'a'*64
        with self.assertRaisesRegex(ValueError, 'artifact|binary'):
            validate_pairs(runs)

    def test_reused_process_is_rejected(self):
        runs = pairs()
        runs[2]['processes']['server'] = copy.deepcopy(runs[0]['processes']['server'])
        with self.assertRaisesRegex(ValueError, 'fresh|process'):
            validate_pairs(runs)

    def test_process_overlap_even_outside_latency_window_is_rejected(self):
        runs = pairs()
        runs[1]['process_start_ns'] = 80
        with self.assertRaisesRegex(ValueError, 'overlap|chronolog'):
            validate_pairs(runs)

    def test_forged_workload_digest_is_rejected(self):
        runs = pairs()
        runs[1]['workload_document']['offered'] = 11
        with self.assertRaisesRegex(ValueError, 'workload'):
            validate_pairs(runs)

    def test_forged_config_digest_is_rejected(self):
        runs = pairs()
        runs[1]['config_document']['java_options'] = ['-Xmx2g']
        with self.assertRaisesRegex(ValueError, 'config'):
            validate_pairs(runs)

    def test_missing_exit_evidence_is_rejected(self):
        runs = pairs()
        runs[1]['shutdown']['backend_proc_absent'] = False
        with self.assertRaisesRegex(ValueError, 'exit|drain'):
            validate_pairs(runs)
