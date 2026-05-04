from __future__ import annotations

import json
from pathlib import Path
from urllib.request import Request, urlopen

from controlplane_tool.infra.runtimes import MockK8sRuntimeManager


def _request(method: str, url: str, payload: dict[str, object] | None = None) -> dict[str, object]:
    data: bytes | None = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(url=url, method=method, data=data, headers=headers)
    with urlopen(request, timeout=3.0) as response:
        body = response.read().decode("utf-8")
        return json.loads(body)


def test_mockk8s_runtime_bootstraps_server(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        response = _request("GET", f"{session.url}/healthz")
        assert response["status"] == "ok"
    finally:
        manager.cleanup(session)


def test_mockk8s_runtime_supports_deployment_crud_and_scale(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)

    try:
        created = _request(
            "POST",
            f"{session.url}/apis/apps/v1/namespaces/default/deployments",
            {
                "apiVersion": "apps/v1",
                "kind": "Deployment",
                "metadata": {"name": "fn-demo"},
                "spec": {"replicas": 1},
            },
        )
        assert created["metadata"]["name"] == "fn-demo"

        _request(
            "POST",
            f"{session.url}/api/v1/namespaces/default/services",
            {
                "apiVersion": "v1",
                "kind": "Service",
                "metadata": {"name": "fn-demo"},
                "spec": {},
            },
        )

        _request(
            "PATCH",
            f"{session.url}/apis/apps/v1/namespaces/default/deployments/fn-demo/scale",
            {"spec": {"replicas": 3}},
        )

        fetched = _request(
            "GET",
            f"{session.url}/apis/apps/v1/namespaces/default/deployments/fn-demo",
        )
        assert fetched["spec"]["replicas"] == 3

        _request(
            "DELETE",
            f"{session.url}/apis/apps/v1/namespaces/default/deployments/fn-demo",
        )
    finally:
        manager.cleanup(session)
