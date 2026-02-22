from pathlib import Path

import pytest
import yaml

from experiments.staging.promotion import promote_candidate_to_baseline


def _write_version(root: Path, slug: str, status: str) -> Path:
    version_dir = root / "versions" / slug
    version_dir.mkdir(parents=True, exist_ok=True)
    (version_dir / "version.yaml").write_text(
        yaml.safe_dump(
            {
                "slug": slug,
                "kind": "generic-service",
                "status": status,
                "parent": "baseline",
                "created_at": "2026-02-22T18:00:00Z",
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    return version_dir


def test_promote_candidate_to_baseline_archives_previous_baseline(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    _write_version(root, "baseline-main", "baseline")
    _write_version(root, "opt-v3", "candidate")

    promote_candidate_to_baseline(root=root, candidate_slug="opt-v3", campaign_id="cmp-010")

    new_baseline = yaml.safe_load((root / "versions" / "opt-v3" / "version.yaml").read_text(encoding="utf-8"))
    old_baseline = yaml.safe_load((root / "versions" / "baseline-main" / "version.yaml").read_text(encoding="utf-8"))

    assert new_baseline["status"] == "baseline"
    assert new_baseline["promoted_by_campaign"] == "cmp-010"
    assert old_baseline["status"] == "archived-baseline"


def test_promote_rejects_candidate_with_invalid_status(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    _write_version(root, "baseline-main", "baseline")
    _write_version(root, "opt-v3", "staging")

    with pytest.raises(ValueError, match="must be in 'candidate' status"):
        promote_candidate_to_baseline(root=root, candidate_slug="opt-v3", campaign_id="cmp-011")


def test_promote_requires_exactly_one_active_baseline(tmp_path: Path):
    root = tmp_path / "control-plane-staging"
    _write_version(root, "opt-v3", "candidate")

    with pytest.raises(ValueError, match="Expected exactly one baseline version"):
        promote_candidate_to_baseline(root=root, candidate_slug="opt-v3", campaign_id="cmp-012")
