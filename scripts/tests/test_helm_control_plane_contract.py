from __future__ import annotations

import re
import subprocess
from pathlib import Path

import pytest


CHART = Path(__file__).resolve().parents[2] / "deploy/helm/nanofaas"


def helm_template(*args):
    return subprocess.run(["helm", "template", "nanofaas", str(CHART), *args],
                          capture_output=True, text=True, timeout=10)


def test_singleton_rollout_uses_recreate():
    result = helm_template("--show-only", "templates/control-plane-deployment.yaml")
    assert result.returncode == 0, result.stderr
    assert "replicas: 1" in result.stdout
    assert re.search(r"strategy:\n\s+type: Recreate", result.stdout)


@pytest.mark.parametrize("count", ["0", "2", "-1", "1.5", "null", "one"])
def test_chart_rejects_unsupported_control_plane_replica_count(count):
    result = helm_template("--set", f"controlPlane.replicaCount={count}")
    assert result.returncode != 0
    assert "controlPlane.replicaCount must be 1" in result.stderr


@pytest.mark.parametrize("http,actuator", [(8080, 8081), (18080, 18081)])
def test_listening_ports_match_service_probes_callbacks_and_bootstrap(http, actuator):
    args = ("--set", f"controlPlane.service.ports.http={http}",
            "--set", f"controlPlane.service.ports.actuator={actuator}")
    deployment = helm_template("--show-only", "templates/control-plane-deployment.yaml", *args)
    assert deployment.returncode == 0, deployment.stderr
    env = dict(re.findall(r'name: ([A-Z0-9_]+)\n\s+value: "([^"\n]*)"', deployment.stdout))
    assert env["SERVER_PORT"] == str(http)
    assert env["MANAGEMENT_SERVER_PORT"] == str(actuator)
    assert env["NANOFAAS_K8S_CALLBACKURL"] == f"http://control-plane.nanofaas.svc.cluster.local:{http}/v1/internal/executions"
    assert f"containerPort: {http}" in deployment.stdout
    assert f"containerPort: {actuator}" in deployment.stdout
    for probe in ("readiness", "liveness"):
        assert re.search(rf'path: /actuator/health/{probe}\n\s+port: {actuator}', deployment.stdout)
    service = helm_template("--show-only", "templates/control-plane-service.yaml", *args)
    assert service.returncode == 0, service.stderr
    for name, port in (("http", http), ("actuator", actuator)):
        assert re.search(rf'name: {name}\n\s+port: {port}\n\s+targetPort: {port}', service.stdout)
    bootstrap = helm_template("--show-only", "templates/demo-register-job.yaml", *args)
    assert bootstrap.returncode == 0, bootstrap.stderr
    assert f'CP_API="http://control-plane:{http}"' in bootstrap.stdout
    assert f'CP_MGMT="http://control-plane:{actuator}"' in bootstrap.stdout


@pytest.mark.parametrize("name", ["SERVER_PORT", "MANAGEMENT_SERVER_PORT"])
def test_extra_env_cannot_override_chart_owned_listening_ports(name):
    result = helm_template("--set-string", f"controlPlane.extraEnv[0].name={name}",
                           "--set-string", "controlPlane.extraEnv[0].value=9999")
    assert result.returncode != 0
    assert "configure listening ports with controlPlane.service.ports" in result.stderr
