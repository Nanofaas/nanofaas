from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_sonar_script_replaces_an_existing_container_instead_of_reusing_it():
    script = (REPO_ROOT / "scripts/sonar.sh").read_text()
    lifecycle = script.split("# --- Credentials", 1)[0]

    assert 'docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true' in lifecycle
    assert "Reusing running container" not in lifecycle
