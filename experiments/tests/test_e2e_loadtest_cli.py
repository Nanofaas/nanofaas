import subprocess
import os
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "experiments" / "e2e-loadtest.sh"


def _write_executable(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


def test_loadtest_help_mentions_mode_and_selection_env_vars():
    proc = subprocess.run(
        ["bash", str(SCRIPT), "--help"],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
    )
    assert proc.returncode == 0
    assert "INVOCATION_MODE" in proc.stdout
    assert "LOADTEST_WORKLOADS" in proc.stdout
    assert "LOADTEST_RUNTIMES" in proc.stdout
    assert "K6_STAGE_SEQUENCE" in proc.stdout
    assert "RESULTS_DIR_OVERRIDE" in proc.stdout
    assert "K6_PAYLOAD_MODE" in proc.stdout
    assert "K6_PAYLOAD_POOL_SIZE" in proc.stdout


def test_loadtest_selection_works_on_bash3_without_unbound_array_error():
    env = os.environ.copy()
    env["LOADTEST_WORKLOADS"] = "word-stats"
    env["LOADTEST_RUNTIMES"] = "java"
    env["K6_STAGE_SEQUENCE"] = "bad-stage"

    proc = subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )
    combined = proc.stdout + proc.stderr
    assert proc.returncode == 2
    assert "Invalid stage 'bad-stage'" in combined
    assert "unbound variable" not in combined


def test_loadtest_script_does_not_use_post_increment_with_set_e():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "((idx++))" not in content
    assert "idx=$((idx + 1))" in content


def test_loadtest_script_avoids_bash4_uppercase_expansion():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "^^" not in content


def test_loadtest_preflight_uses_dynamic_required_functions_and_guidance():
    content = SCRIPT.read_text(encoding="utf-8")
    assert "Expected 8 functions" not in content
    assert "Missing required functions for selected tests" in content
    assert "No functions registered" in content
    assert "Control-plane-only deployment detected" in content


def test_loadtest_single_selected_function_reaches_final_report(tmp_path: Path):
    fake_bin = tmp_path / "bin"
    fake_bin.mkdir(parents=True, exist_ok=True)

    _write_executable(
        fake_bin / "k6",
        """#!/usr/bin/env bash
set -euo pipefail
summary=""
for arg in "$@"; do
  case "$arg" in
    --summary-export=*) summary="${arg#*=}" ;;
  esac
done
if [[ -z "${summary}" ]]; then
  echo "missing --summary-export" >&2
  exit 2
fi
mkdir -p "$(dirname "${summary}")"
cat > "${summary}" <<'JSON'
{"metrics":{"http_reqs":{"count":10},"http_req_failed":{"passes":0},"http_req_duration":{"avg":1.0,"med":1.0,"p(90)":1.0,"p(95)":1.0,"max":1.0},"iterations":{"rate":2.0}}}
JSON
echo "default ✓ [ 100% ] 00/01 VUs  2s"
""",
    )
    _write_executable(
        fake_bin / "curl",
        """#!/usr/bin/env bash
set -euo pipefail
url="${@: -1}"
if [[ "${url}" == *"/v1/functions" ]]; then
  printf '[{"name":"word-stats-java"},{"name":"word-stats-java-lite"},{"name":"word-stats-python"},{"name":"word-stats-exec"},{"name":"json-transform-java"},{"name":"json-transform-java-lite"},{"name":"json-transform-python"},{"name":"json-transform-exec"}]'
  exit 0
fi
echo '{}'
""",
    )

    results_dir = tmp_path / "results"
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}:{env.get('PATH', '')}"
    env["NANOFAAS_URL"] = "http://127.0.0.1:30080"
    env["PROM_URL"] = "http://127.0.0.1:1"
    env["SKIP_GRAFANA"] = "true"
    env["VERIFY_OUTPUT_PARITY"] = "false"
    env["LOADTEST_WORKLOADS"] = "word-stats"
    env["LOADTEST_RUNTIMES"] = "java"
    env["RESULTS_DIR_OVERRIDE"] = str(results_dir)

    proc = subprocess.run(
        ["bash", str(SCRIPT)],
        cwd=str(REPO_ROOT),
        text=True,
        capture_output=True,
        env=env,
    )

    combined = proc.stdout + proc.stderr
    assert proc.returncode == 0, combined
    assert "PERFORMANCE REPORT (SYNC)" in combined
    assert "LOAD TEST COMPLETE (SYNC)" in combined
    assert (results_dir / "word-stats-java.json").exists()
