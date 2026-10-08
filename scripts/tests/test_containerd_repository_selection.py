"""Execute build wrappers against isolated command stubs; never contact Docker/Sonar."""
import json
import os
from pathlib import Path
import shutil
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]


def sandbox(tmp_path, script):
    (tmp_path / "scripts").mkdir()
    shutil.copyfile(ROOT / "scripts" / script, tmp_path / "scripts" / script)
    commands = tmp_path / "bin"
    commands.mkdir()
    stub = '''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
with open(os.environ['STUB_LOG'], 'a') as log:
    log.write(json.dumps([name, *sys.argv[1:]]) + '\\n')
if name == 'lsof' or (name == 'docker' and sys.argv[1:2] == ['inspect']):
    sys.exit(1)
if name == 'curl':
    print(json.dumps({'status':'UP', 'token':'test-token', 'current':{'status':'SUCCESS'},
        'facets':[{'property':'impactSeverities','values':[]}], 'paging':{'total':0}}, separators=(',', ':')))
'''
    for command in ["docker", "sonar-scanner", "curl", "lsof", "git", "gradlew"]:
        destination = tmp_path / "gradlew" if command == "gradlew" else commands / command
        destination.write_text(stub)
        destination.chmod(0o755)
    env = {key: value for key, value in os.environ.items()
           if key not in ["CONTAINERD_MAVEN_REPO", "CONTROL_PLANE_MODULES"]}
    env.update(PATH=f"{commands}:{env['PATH']}", STUB_LOG=str(tmp_path / "commands.jsonl"))
    return env


@pytest.mark.parametrize("modules", ["containerd-deployment-provider", "all", "async-queue"])
@pytest.mark.parametrize("repository", ["central", "local", "missing"])
def test_native_repository_selection(tmp_path, modules, repository):
    env = sandbox(tmp_path, "native-java-image.sh")
    env["CONTROL_PLANE_MODULES"] = modules
    local = tmp_path / "maven repo with spaces"
    if repository != "central":
        env["CONTAINERD_MAVEN_REPO"] = str(local)
    if repository == "local":
        local.mkdir()
    result = subprocess.run(["bash", "scripts/native-java-image.sh", "control-plane"],
                            cwd=tmp_path, env=env, capture_output=True, text=True, timeout=5)
    uses_local = modules == "containerd-deployment-provider" and repository != "central"
    if uses_local and repository == "missing":
        assert result.returncode != 0
        assert "Missing CONTAINERD_MAVEN_REPO" in result.stderr
        return
    assert result.returncode == 0, result.stderr
    calls = [json.loads(line) for line in Path(env["STUB_LOG"]).read_text().splitlines()]
    build = next(call for call in calls if call[:2] == ["docker", "build"])
    arguments = next(arg for arg in build if arg.startswith("GRADLE_ARGS="))
    assert ("-PcontainerdMavenLocal=true" in arguments) == uses_local
    context = build[build.index("--build-context") + 1]
    assert context == "containerd_maven_repo=" + (str(local) if uses_local else "tools/native-java/empty-maven-repo")


@pytest.mark.parametrize("repository", ["central", "local", "missing"])
def test_sonar_passes_actual_gradle_repository_arguments(tmp_path, repository):
    env = sandbox(tmp_path, "sonar.sh")
    local = tmp_path / "maven repo with spaces"
    if repository != "central":
        env["CONTAINERD_MAVEN_REPO"] = str(local)
    if repository == "local":
        local.mkdir()
    result = subprocess.run(["bash", "scripts/sonar.sh", "--only", "java", "--rm"],
                            cwd=tmp_path, env=env, capture_output=True, text=True, timeout=5)
    calls = [json.loads(line) for line in Path(env["STUB_LOG"]).read_text().splitlines()]
    gradle = [call for call in calls if call[0] == "gradlew"]
    if repository == "missing":
        assert result.returncode != 0
        assert gradle == []
    else:
        assert result.returncode == 0, result.stdout + result.stderr
        assert len(gradle) == 1
        assert ("-PcontainerdMavenLocal=true" in gradle[0]) == (repository == "local")
        if repository == "local":
            assert f"-Dmaven.repo.local={local}" in gradle[0]
        else:
            assert not any(arg.startswith("-Dmaven.repo.local=") for arg in gradle[0])
