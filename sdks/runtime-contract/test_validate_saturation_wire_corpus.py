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


def scenario(document, scenario_id):
    return next(item for item in document["scenarios"] if item["id"] == scenario_id)


class SaturationWireCorpusMutationTest(unittest.TestCase):
    def test_rejects_retry_after_success_with_failure_second(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "dispatch-retry-identity")

        mutated["backend"]["handlers"][0].update({
            "behavior": "succeed",
            "outputBytes": 128,
            "outputRelationToLimit": "below-limit",
        })
        mutated["expected"]["handlers"][0].update({
            "lifecycleRef": "succeeded",
            "started": True,
            "cancelRequested": False,
            "terminal": "succeeded",
        })
        mutated["expected"]["responses"][0].update({
            "outcomeRef": "success",
            "status": 200,
            "body": {"result": "ok"},
        })
        mutated["expected"]["callbacks"][0]["envelopeRef"] = "success"
        mutated["expected"]["callbacks"][0]["requestProjection"]["payload"] = {
            "success": True,
            "output": {"result": "ok"},
            "error": None,
        }

        mutated["backend"]["handlers"][1].update({
            "behavior": "fail",
            "outputBytes": 0,
            "outputRelationToLimit": "not-applicable",
        })
        mutated["expected"]["handlers"][1].update({
            "lifecycleRef": "failed",
            "started": True,
            "cancelRequested": False,
            "terminal": "failed",
        })
        mutated["expected"]["responses"][1].update({
            "outcomeRef": "handler-error",
            "status": 500,
            "body": {"error": {"code": "HANDLER_ERROR", "message": "Handler failed"}},
        })
        mutated["expected"]["callbacks"][1]["envelopeRef"] = "handler-error"
        mutated["expected"]["callbacks"][1]["requestProjection"]["payload"] = {
            "success": False,
            "output": None,
            "error": {"code": "HANDLER_ERROR", "message": "Handler failed"},
        }

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_redispatch_replaced_by_send(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "dispatch-retry-identity")
        redispatch = next(
            action for action in mutated["harness"]["actions"]
            if action["action"] == "control-plane-redispatch"
        )
        redispatch["action"] = "send-request"

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_stop_program_without_begin_stop(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "stop-with-full-queue")
        actions = mutated["harness"]["actions"]
        actions[:] = [action for action in actions if action["action"] != "begin-stop"]
        for sequence, action in enumerate(actions, start=1):
            action["sequence"] = sequence

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_saturation_program_without_capacity_fill(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "callback-saturated")
        actions = mutated["harness"]["actions"]
        actions[:] = [
            action for action in actions if action["action"] != "fill-callback-capacity"
        ]
        for sequence, action in enumerate(actions, start=1):
            action["sequence"] = sequence

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_swapped_success_and_restart_kinds(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        success = scenario(document, "success-drain")
        restart = scenario(document, "restart")
        success["kind"], restart["kind"] = restart["kind"], success["kind"]

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_below_limit_output_with_output_rejection_chain(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "success-drain")
        mutated["expected"]["handlers"][0].update({
            "lifecycleRef": "output-rejected",
            "started": True,
            "cancelRequested": False,
            "terminal": "output-rejected",
        })
        mutated["expected"]["responses"][0].update({
            "outcomeRef": "output-too-large",
            "status": 500,
            "body": {"error": {
                "code": "RUNTIME_OUTPUT_TOO_LARGE",
                "message": "Runtime output exceeds configured byte limit",
            }},
        })
        callback = mutated["expected"]["callbacks"][0]
        callback["envelopeRef"] = "output-too-large"
        callback["requestProjection"]["payload"] = {
            "success": False,
            "output": None,
            "error": {
                "code": "RUNTIME_OUTPUT_TOO_LARGE",
                "message": "Runtime output exceeds configured byte limit",
            },
        }

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_above_limit_output_with_success_chain(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "output-too-large")
        mutated["expected"]["handlers"][0].update({
            "lifecycleRef": "succeeded",
            "started": True,
            "cancelRequested": False,
            "terminal": "succeeded",
        })
        mutated["expected"]["responses"][0].update({
            "outcomeRef": "success",
            "status": 200,
            "body": {"result": "ok"},
        })
        callback = mutated["expected"]["callbacks"][0]
        callback["envelopeRef"] = "success"
        callback["requestProjection"]["payload"] = {
            "success": True,
            "output": {"result": "ok"},
            "error": None,
        }

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_scenario_kind_rewritten_to_handler_error(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "success-drain")
        mutated["backend"]["handlers"][0].update({
            "behavior": "fail",
            "outputBytes": 0,
            "outputRelationToLimit": "not-applicable",
        })
        mutated["expected"]["handlers"][0].update({
            "lifecycleRef": "failed",
            "started": True,
            "cancelRequested": False,
            "terminal": "failed",
        })
        mutated["expected"]["responses"][0].update({
            "outcomeRef": "handler-error",
            "status": 500,
            "body": {"error": {"code": "HANDLER_ERROR", "message": "Handler failed"}},
        })
        callback = mutated["expected"]["callbacks"][0]
        callback["envelopeRef"] = "handler-error"
        callback["requestProjection"]["payload"] = {
            "success": False,
            "output": None,
            "error": {"code": "HANDLER_ERROR", "message": "Handler failed"},
        }

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_probe_health_for_invoke_role(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "health-under-saturation")
        mutated["requests"][0]["role"] = "invoke"

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_send_request_for_health_role(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "success-drain")
        mutated["requests"][0]["role"] = "health"

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_failed_handler_with_success_wire_and_callback(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "success-drain")
        mutated["backend"]["handlers"][0]["behavior"] = "fail"
        mutated["expected"]["handlers"][0].update({
            "lifecycleRef": "failed",
            "started": True,
            "cancelRequested": False,
            "terminal": "failed",
        })

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_output_too_large_with_handler_error_callback(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "output-too-large")
        callback = mutated["expected"]["callbacks"][0]
        callback["envelopeRef"] = "handler-error"
        callback["requestProjection"]["payload"] = {
            "success": False,
            "output": None,
            "error": {
                "code": "HANDLER_ERROR",
                "message": "Handler failed",
            },
        }

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_send_request_without_request_id(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "success-drain")
        send = next(
            action for action in mutated["harness"]["actions"]
            if action["action"] == "send-request"
        )
        send["requestId"] = None

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

    def test_rejects_callback_attempts_without_dispatch_attempt_entries(self):
        validator = load_validator_module()
        document = json.loads(CORPUS.read_text(encoding="utf-8"))
        mutated = scenario(document, "callback-delivery-exhausted")
        callback = mutated["expected"]["callbacks"][0]
        self.assertEqual(3, callback["attempts"])
        callback["dispatchAttempts"] = []

        with self.assertRaises(validator.ContractError):
            validator.validate_document(document)

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
