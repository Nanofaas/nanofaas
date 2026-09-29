from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_sonar_script_replaces_an_existing_container_instead_of_reusing_it():
    script = (REPO_ROOT / "scripts/sonar.sh").read_text()
    lifecycle = script.split("# --- Credentials", 1)[0]

    assert 'docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true' in lifecycle
    assert "Reusing running container" not in lifecycle


def test_rust_analysis_covers_every_tracked_cargo_manifest():
    """A new crate that is not listed would silently drop out of the Rust analysis."""
    import re
    import subprocess

    script = (REPO_ROOT / "scripts/sonar.sh").read_text()
    run_rust = script.split("run_rust() {", 1)[1].split("\n}\n", 1)[0]
    listed = re.search(r"-Dsonar\.rust\.cargo\.manifestPaths=(\S+)", run_rust).group(1).split(",")
    sources = re.search(r"-Dsonar\.sources=(\S+)", run_rust).group(1).split(",")
    tracked = subprocess.run(
        ["git", "ls-files", "*Cargo.toml"], cwd=REPO_ROOT, capture_output=True, text=True, check=True
    ).stdout.split()

    assert sorted(listed) == sorted(tracked)
    assert all(any(manifest.startswith(source + "/") for source in sources) for manifest in tracked)
