from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


def test_operator_docs_use_the_six_command_surface() -> None:
    paths = (
        ROOT / "README.md",
        ROOT / "docs" / "quickstart.md",
        ROOT / "docs" / "control-plane.md",
        ROOT / "docs" / "testing.md",
        ROOT / "docs" / "nanofaas-cli.md",
        ROOT / "tools" / "controlplane" / "README.md",
    )
    text = "\n".join(path.read_text(encoding="utf-8") for path in paths)

    assert "scripts/controlplane.sh plan" in text
    assert "scripts/controlplane.sh run" in text
    assert "environments/multipass.yaml" in text
    assert "environments/external.yaml.example" in text
    for legacy in ("--saved-profile", "cli-test", "e2e run", "loadtest run", "--profile core"):
        assert legacy not in text


def test_launcher_is_a_locked_thin_wrapper() -> None:
    script = (ROOT / "scripts" / "controlplane.sh").read_text(encoding="utf-8")

    assert "uv run --project tools/controlplane --locked controlplane-tool" in script
