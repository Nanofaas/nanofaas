from __future__ import annotations

from controlplane_tool.functions.control_plane_api import ControlPlaneApi


def test_control_plane_api_builds_register_and_invoke_requests() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080")
    assert api.register_url == "http://localhost:8080/v1/functions"


def test_control_plane_api_invoke_url_includes_function_name() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080")
    assert api.invoke_url("my-fn") == "http://localhost:8080/v1/functions/my-fn:invoke"


def test_control_plane_api_function_url_without_action() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080")
    assert api.function_url("echo-test") == "http://localhost:8080/v1/functions/echo-test"


def test_control_plane_api_replicas_url() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080")
    assert api.replicas_url("word-stats") == "http://localhost:8080/v1/functions/word-stats/replicas"


def test_control_plane_api_health_url() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080", mgmt_port=8081)
    assert "actuator/health" in api.health_url


def test_control_plane_api_strips_trailing_slash() -> None:
    api = ControlPlaneApi(base_url="http://localhost:8080/")
    assert api.register_url == "http://localhost:8080/v1/functions"
