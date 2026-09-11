"""Fail closed: acceptance must not survive missing work or mismatched cohorts."""
import copy
import importlib.util
import pathlib
import unittest


class ContractTest(unittest.TestCase):
    def validator(self):
        path = pathlib.Path(__file__).with_name('contract.py')
        self.assertTrue(path.exists(), 'P19 acceptance validator is required')
        spec = importlib.util.spec_from_file_location('contract', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def cohort(self):
        return [dict(id=str(i), admitted=True, success=True, execution_id=str(i), latency_ns=100+i) for i in range(10)]

    def test_unique_successes_and_nearest_rank(self):
        result = self.validator().summarize(self.cohort(), offered=10, elapsed_ns=1_000_000_000)
        self.assertEqual(result['unique_successes'], 10)
        self.assertEqual(result['success_latency_ns']['p99'], 109)
        self.assertEqual(result['useful_successes_per_s'], 10)

    def test_duplicate_execution_is_rejected(self):
        rows = self.cohort()
        rows[-1]['execution_id'] = '0'
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.validator().summarize(rows, 10, 1000)

    def test_lost_admission_is_rejected(self):
        rows = self.cohort()
        rows.pop()
        with self.assertRaisesRegex(ValueError, 'offered'):
            self.validator().summarize(rows, 10, 1000)

    def test_refusal_is_not_useful_work(self):
        rows = self.cohort()
        rows[-1].update(admitted=False, success=False, execution_id=None, status=429)
        result = self.validator().summarize(rows, 10, 1_000_000_000)
        self.assertEqual(result['unique_successes'], 9)
        self.assertEqual(result['refused'], 1)

    def test_comparison_rejects_missing_repeat_and_mismatched_work(self):
        module = self.validator()
        from test_revision_identity import pairs
        runs = pairs()
        module.validate_pairs(runs)
        with self.assertRaises(ValueError):
            module.validate_pairs(runs[:-1])
        bad = copy.deepcopy(runs)
        bad[-1]['workload'] = 'other'
        with self.assertRaises(ValueError):
            module.validate_pairs(bad)


if __name__ == '__main__':
    unittest.main()
