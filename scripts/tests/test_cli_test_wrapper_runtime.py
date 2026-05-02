from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"


def read_script(name: str) -> str:
    return (SCRIPTS_DIR / name).read_text(encoding="utf-8")


def test_cli_test_legacy_wrappers_delegate_to_cli_test_run() -> None:
    wrappers = {
        "e2e-cli.sh": "vm",
        "e2e-cli-host-platform.sh": "host-platform",
        "e2e-cli-deploy-host.sh": "deploy-host",
    }

    for script_name, scenario_name in wrappers.items():
        script = read_script(script_name)
        assert f'controlplane.sh" cli-test run {scenario_name} "$@"' in script
        assert 'controlplane.sh" e2e run' not in script
