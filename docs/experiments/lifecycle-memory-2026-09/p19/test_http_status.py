import unittest
import contract


class WireStatusTest(unittest.TestCase):
    def test_polling_uses_lowercase_wire_status_and_handles_eviction(self):
        self.assertTrue(hasattr(contract, 'terminal_status'), 'wire status classifier required')
        self.assertEqual(contract.terminal_status({'status': 'success'}), 'success')
        self.assertEqual(contract.terminal_status({'status': 'timeout'}), 'timeout')
        self.assertIsNone(contract.terminal_status({'status': 'running'}))
        self.assertEqual(contract.terminal_status(None), 'outcome_unavailable')
