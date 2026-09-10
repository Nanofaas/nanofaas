import copy
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


CONTRACT_DIR = Path(__file__).resolve().parent
CORPUS = CONTRACT_DIR / "saturation-wire-corpus.json"
VALIDATOR = CONTRACT_DIR / "validate_saturation_wire_corpus.py"
REVIEW_CONTRADICTION_IDS = {
    "handler-failure-with-success-outcome",
    "callback-success-with-exhausted-lifecycle",
    "handler-actor-sends-request",
    "callback-required-without-url",
    "arbitrary-success-body",
    "arbitrary-error-message",
    "noncanonical-content-type",
}


def load_validator_module():
    spec = importlib.util.spec_from_file_location("saturation_wire_validator", VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class SaturationWireCorpusMutationTest(unittest.TestCase):
    def test_rejects_all_review_round_two_contradictions(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutations = {
            mutation["id"]: mutation
            for mutation in document["mutationTests"]
            if mutation["id"] in REVIEW_CONTRADICTION_IDS
        }
        self.assertEqual(REVIEW_CONTRADICTION_IDS, set(mutations))

        for mutation_id in sorted(REVIEW_CONTRADICTION_IDS):
            with self.subTest(mutation=mutation_id):
                mutated = copy.deepcopy(document)
                validator.apply_mutation(mutated, mutations[mutation_id])
                with self.assertRaises(validator.ContractError):
                    validator.validate_document(mutated)

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
