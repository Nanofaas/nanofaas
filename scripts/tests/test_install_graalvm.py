"""Exercise release selection without downloading or installing a JDK."""
import os
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "scripts/install-graalvm.sh"


@pytest.fixture
def download_env(tmp_path):
    curl = tmp_path / "curl"
    # Stop at the network boundary; never reach the installer's /opt writes.
    curl.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\nexit 73\n')
    curl.chmod(0o755)
    return {**os.environ, "PATH": f"{tmp_path}:{os.environ['PATH']}"}


@pytest.mark.parametrize("architecture,archive_arch", [("amd64", "x64"), ("arm64", "aarch64")])
def test_oracle_download_matches_requested_graal_release(download_env, architecture, archive_arch):
    result = subprocess.run(
        ["sh", str(SCRIPT), architecture, "25.2.4", "25.0.4", "oracle"],
        env=download_env, capture_output=True, text=True,
    )
    assert result.returncode == 73
    assert (
        "https://gds.oracle.com/download/graal/25i2/archive/"
        f"graalvm-jdk-25i2-25.0.4_linux-{archive_arch}_bin.tar.gz"
    ) in result.stdout.splitlines()


def test_oracle_rejects_an_unpinned_graal_release_before_download(download_env):
    result = subprocess.run(
        ["sh", str(SCRIPT), "amd64", "99.0.0", "25.0.4", "oracle"],
        env=download_env, capture_output=True, text=True,
    )
    assert result.returncode == 1
    assert "Unsupported GraalVM" in result.stderr
    assert not result.stdout
