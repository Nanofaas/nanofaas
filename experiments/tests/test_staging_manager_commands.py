import json
import subprocess
from pathlib import Path

import yaml


REPO_ROOT = Path(__file__).resolve().parents[2]


def _run(*args: str, cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["python3", "experiments/staging_manager.py", *args],
        cwd=str(cwd),
        text=True,
        capture_output=True,
    )


def test_staging_manager_create_build_and_run_campaign_smoke(tmp_path: Path):
    staging_root = tmp_path / "control-plane-staging"
    benchmark_path = staging_root / "benchmark" / "benchmark.yaml"
    benchmark_path.parent.mkdir(parents=True, exist_ok=True)
    benchmark_path.write_text(
        yaml.safe_dump(
            {
                "function_profile": "all",
                "platform_modes": ["jvm", "native"],
                "k6": {"stages": "20s:20,20s:0"},
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )

    create = _run(
        "create-version",
        "--slug",
        "cand-a",
        "--from",
        "none",
        "--staging-root",
        str(staging_root),
        cwd=REPO_ROOT,
    )
    assert create.returncode == 0, create.stderr
    assert (staging_root / "versions" / "cand-a" / "version.yaml").exists()

    build = _run(
        "build-images",
        "--slug",
        "cand-a",
        "--force-rebuild-images",
        "--force-rebuild-mode",
        "jvm",
        cwd=REPO_ROOT,
    )
    assert build.returncode == 0, build.stderr

    run_campaign = _run(
        "run-campaign",
        "--baseline",
        "baseline-a",
        "--candidate",
        "cand-a",
        "--runs",
        "10",
        "--campaign-id",
        "cmp-smoke",
        "--staging-root",
        str(staging_root),
        "--benchmark-path",
        str(benchmark_path),
        cwd=REPO_ROOT,
    )
    assert run_campaign.returncode == 0, run_campaign.stderr

    campaign_metadata = json.loads(
        (staging_root / "campaigns" / "cmp-smoke" / "campaign.json").read_text(encoding="utf-8")
    )
    assert campaign_metadata["cells_executed"] == 40
    assert (staging_root / "campaigns" / "cmp-smoke" / "aggregate-comparison.json").exists()
    assert (staging_root / "campaigns" / "cmp-smoke" / "aggregate-comparison.md").exists()
