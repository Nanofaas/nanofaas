import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CONTRACT_DIR = Path(__file__).resolve().parent
CORPUS = CONTRACT_DIR / "saturation-wire-corpus.json"
VALIDATOR = CONTRACT_DIR / "validate_saturation_wire_corpus.py"


class SaturationWireCorpusMutationTest(unittest.TestCase):
    def test_authoritative_validator_rejects_every_embedded_mutation(self):
        self.assertTrue(
            VALIDATOR.is_file(),
            "authoritative saturation-wire validator is required",
        )
        result = subprocess.run(
            [sys.executable, str(VALIDATOR), "--run-mutations", str(CORPUS)],
            check=False,
            capture_output=True,
            text=True,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_authoritative_validator_rejects_non_finite_json_number(self):
        self.assertTrue(
            VALIDATOR.is_file(),
            "authoritative saturation-wire validator is required",
        )
        source = CORPUS.read_text(encoding="utf-8")
        mutated = source.replace('"maxPendingCallbacks": 1', '"maxPendingCallbacks": Infinity', 1)
        self.assertNotEqual(source, mutated)
        with tempfile.NamedTemporaryFile("w", suffix=".json", encoding="utf-8") as fixture:
            fixture.write(mutated)
            fixture.flush()
            result = subprocess.run(
                [sys.executable, str(VALIDATOR), fixture.name],
                check=False,
                capture_output=True,
                text=True,
                timeout=10,
            )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
