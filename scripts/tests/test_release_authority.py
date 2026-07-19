"""GitHub Actions must only test: the Azure release flow is the sole publisher."""

from __future__ import annotations

from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = REPO_ROOT / ".github" / "workflows"


def _workflow_texts() -> dict[str, str]:
    return {
        path.name: path.read_text(encoding="utf-8")
        for path in sorted((*WORKFLOWS.glob("*.yml"), *WORKFLOWS.glob("*.yaml")))
    }


def test_no_workflow_can_push_images() -> None:
    for name, text in _workflow_texts().items():
        assert "docker push" not in text, f"{name} must not push images"
        assert "packages: write" not in text, f"{name} must not hold package-write permission"
        assert "docker/login-action" not in text, f"{name} must not authenticate to a registry"


def test_no_workflow_builds_release_images() -> None:
    for name, text in _workflow_texts().items():
        assert "bootBuildImage" not in text, f"{name} must not build release images"


def test_gitops_keeps_the_test_jobs() -> None:
    text = (WORKFLOWS / "gitops.yml").read_text(encoding="utf-8")
    for job in ("test-java:", "test-python:", "test-watchdog:"):
        assert job in text
    assert 'tags: ["v*"]' not in text, "version tags must not trigger CI publication"
