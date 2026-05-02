from __future__ import annotations

import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from controlplane_tool.mockk8s_runtime import MockK8sRuntimeManager


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


def _request_error(
    method: str,
    url: str,
    payload: dict[str, object] | None = None,
) -> tuple[int, dict[str, object]]:
    data: bytes | None = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(url=url, method=method, data=data, headers=headers)
    try:
        with urlopen(request, timeout=3.0) as response:
            return (int(getattr(response, "status", 0)), json.loads(response.read().decode("utf-8")))
    except HTTPError as exc:
        return (exc.code, json.loads(exc.read().decode("utf-8")))


def test_api_v1_resources_include_pods(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        body = _request("GET", f"{session.url}/api/v1")
        resource_names = {resource["name"] for resource in body["resources"]}
        assert "pods" in resource_names
    finally:
        manager.cleanup(session)


def test_pods_create_get_delete_roundtrip(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        _request(
            "POST",
            f"{session.url}/api/v1/namespaces/default/pods",
            {
                "apiVersion": "v1",
                "kind": "Pod",
                "metadata": {"name": "imgval-demo"},
                "spec": {"containers": [{"name": "validate", "image": "word-stats:test"}]},
            },
        )
        pod = _request(
            "GET",
            f"{session.url}/api/v1/namespaces/default/pods/imgval-demo",
        )
        assert pod["metadata"]["name"] == "imgval-demo"

        _request(
            "DELETE",
            f"{session.url}/api/v1/namespaces/default/pods/imgval-demo",
        )
    finally:
        manager.cleanup(session)


def test_created_validation_pod_is_immediately_running(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        _request(
            "POST",
            f"{session.url}/api/v1/namespaces/default/pods",
            {
                "apiVersion": "v1",
                "kind": "Pod",
                "metadata": {"name": "imgval-running"},
                "spec": {"containers": [{"name": "validate", "image": "word-stats:test"}]},
            },
        )
        pod = _request(
            "GET",
            f"{session.url}/api/v1/namespaces/default/pods/imgval-running",
        )
        assert pod["status"]["phase"] in {"Running", "Succeeded"}
    finally:
        manager.cleanup(session)


def test_duplicate_pod_create_returns_conflict(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        payload = {
            "apiVersion": "v1",
            "kind": "Pod",
            "metadata": {"name": "imgval-dup"},
            "spec": {"containers": [{"name": "validate", "image": "word-stats:test"}]},
        }
        _request("POST", f"{session.url}/api/v1/namespaces/default/pods", payload)
        status, body = _request_error(
            "POST",
            f"{session.url}/api/v1/namespaces/default/pods",
            payload,
        )
        assert status == 409
        assert body["reason"] == "AlreadyExists"
    finally:
        manager.cleanup(session)


def test_short_api_group_path_returns_not_found(tmp_path: Path) -> None:
    manager = MockK8sRuntimeManager(repo_root=tmp_path)
    run_dir = tmp_path / "run"
    run_dir.mkdir(parents=True, exist_ok=True)
    session = manager.ensure_available(run_dir=run_dir)
    try:
        status, body = _request_error("POST", f"{session.url}/apis/apps/v1", {})
        assert status == 404
        assert body["reason"] == "NotFound"
    finally:
        manager.cleanup(session)
