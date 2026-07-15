import json
import os
import shutil
import subprocess
from itertools import product
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "experiments" / "k6" / "run-all.sh"
BENCHMARK = ROOT / "experiments" / "k6" / "function-benchmark.js"
FAMILIES = ("word-stats", "json-transform", "roman-numeral")
RUNTIMES = ("java", "java-lite", "python", "go", "javascript", "exec")


def _list_runs(profiles: str | None = None) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    env.pop("NANOFAAS_URL", None)
    env.pop("K6_PAYLOAD_PROFILES", None)
    if profiles is not None:
        env["K6_PAYLOAD_PROFILES"] = profiles
    return subprocess.run(
        ["bash", str(RUNNER), "--list"],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )


def _parse_runs(stdout: str) -> set[tuple[str, str, str, str]]:
    return {tuple(line.split("\t")) for line in stdout.splitlines() if line}


def test_default_matrix_covers_every_function_with_small_payloads() -> None:
    result = _list_runs()

    assert result.returncode == 0, result.stderr
    expected = {
        (family, runtime, "small", f"{family}-{runtime}")
        for family, runtime in product(FAMILIES, RUNTIMES)
    }
    assert _parse_runs(result.stdout) == expected


def test_explicit_profiles_expand_to_54_runs() -> None:
    result = _list_runs("small,medium,large")

    assert result.returncode == 0, result.stderr
    expected = {
        (family, runtime, profile, f"{family}-{runtime}")
        for family, runtime, profile in product(
            FAMILIES, RUNTIMES, ("small", "medium", "large")
        )
    }
    assert _parse_runs(result.stdout) == expected
    assert len(expected) == 54


def test_runner_rejects_unknown_or_empty_profile_lists() -> None:
    for profiles in ("", "small,huge"):
        result = _list_runs(profiles)

        assert result.returncode != 0
        assert "K6_PAYLOAD_PROFILES" in result.stderr


def test_benchmark_fails_when_semantic_responses_are_invalid() -> None:
    k6 = shutil.which("k6")
    assert k6 is not None, "k6 is required to inspect the benchmark"
    result = subprocess.run(
        [
            k6,
            "inspect",
            "-e",
            "NANOFAAS_FUNCTION=word-stats-java",
            "-e",
            "NANOFAAS_FAMILY=word-stats",
            str(BENCHMARK),
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0, result.stderr
    thresholds = json.loads(result.stdout)["thresholds"]
    assert thresholds["function_response_valid"] == ["rate==1"]
