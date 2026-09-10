import json
from pathlib import Path


CORPUS = Path(__file__).resolve().parents[2] / "runtime-contract" / "saturation-wire-corpus.json"


def test_consumes_the_shared_runtime_saturation_wire_contract():
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))

    assert corpus["version"] > 0
    runner = corpus["runner"]
    assert corpus["releaseOn"] == runner["requiredReleaseEvents"]
    assert corpus["scope"] and corpus["admissionPoint"]
    assert all(corpus["retryIdentity"].values())

    cases = corpus["cases"]
    ids = [case["id"] for case in cases]
    assert len(ids) == len(set(ids))
    assert set(ids) == set(runner["requiredCaseIds"])
    for case in cases:
        assert case["implementationOwner"] and case["stimulus"]
        assert 0 < case["timeoutMs"] <= runner["maximumCaseTimeoutMs"]
        expected = case["expected"]
        assert expected["httpStatus"] == runner["noSecondResponseStatus"] or (
            runner["minimumHttpStatus"] <= expected["httpStatus"] <= runner["maximumHttpStatus"]
        )
        assert expected["errorCode"].replace("_", "").isalnum()
        assert expected["errorCode"] == expected["errorCode"].upper()
        assert expected["message"] and expected["release"]
        assert isinstance(expected["retryable"], bool)
        assert isinstance(expected["handlerStarted"], bool)
        assert isinstance(expected["callbackExpected"], bool)
