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


def test_operator_docs_describe_the_adapted_tui_surface() -> None:
    paths = (ROOT / "tools" / "controlplane" / "README.md", ROOT / "docs" / "quickstart.md")
    for path in paths:
        text = path.read_text(encoding="utf-8")
        assert "scripts/controlplane.sh tui" in text
        for section in ("Validation", "CLI", "Load Testing", "Tools"):
            assert section in text
        assert "branded header" in text
        assert "live workflow" in text
        assert "`l`" in text
        for removed_menu in (
            "Build menu",
            "Environment menu",
            "Catalog menu",
            "saved-profile menu",
            "image-publishing menu",
        ):
            assert removed_menu not in text


def test_operator_docs_explain_tui_provider_setup_entries() -> None:
    paths = (
        ROOT / "tools" / "controlplane" / "README.md",
        ROOT / "docs" / "quickstart.md",
    )
    for path in paths:
        text = path.read_text(encoding="utf-8")
        marker = "When only the provider templates are present"
        assert marker in text
        setup_block = marker + text.split(marker, maxsplit=1)[1].split("\n\n", maxsplit=1)[0]

        for phrase in (
            "Azure (setup required)",
            "Proxmox (setup required)",
            ".yaml.example",
            "azure.yaml",
            "proxmox.yaml",
            "The TUI never loads or executes",
            "provider values and configuration",
            "external authentication",
            "az login",
            "password_env",
            "environment variable",
            "Do not store secrets in YAML",
            "writes no files",
            "starts no workflow",
        ):
            assert phrase in setup_block
