from __future__ import annotations

from email.message import Message
import http.client
from io import BytesIO
import urllib.error
import urllib.request

import pytest

from controlplane_tool.cli.preflight import PreflightError, preflight_control_plane
from controlplane_tool.config import EnvironmentConfig, ScenarioConfig


START_COMMAND = "./gradlew :control-plane:bootRun"
BASE_URL = "http://127.0.0.1:8080"
HEALTH_URL = f"{BASE_URL}/actuator/health"
CLI_SCENARIO = ScenarioConfig(workflow="cli", functions=["word-stats-java"])
VALIDATE_SCENARIO = ScenarioConfig(
    workflow="validate",
    backend="container",
    functions=["word-stats-java"],
)
LOCAL_ENVIRONMENT = EnvironmentConfig(provider="local")
MULTIPASS_ENVIRONMENT = EnvironmentConfig.model_validate(
    {"provider": "multipass", "roles": {"stack": {"name": "nanofaas-stack"}}}
)


@pytest.mark.parametrize(
    ("scenario", "environment"),
    (
        (VALIDATE_SCENARIO, LOCAL_ENVIRONMENT),
        (CLI_SCENARIO, MULTIPASS_ENVIRONMENT),
    ),
)
def test_preflight_is_a_noop_outside_local_cli(
    monkeypatch: pytest.MonkeyPatch,
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
) -> None:
    def unexpected_request(*args: object, **kwargs: object) -> None:
        raise AssertionError("preflight must not contact the control plane")

    monkeypatch.setattr(urllib.request, "urlopen", unexpected_request)

    preflight_control_plane(scenario, environment, base_url=BASE_URL)


def test_preflight_accepts_an_up_control_plane(monkeypatch: pytest.MonkeyPatch) -> None:
    request: dict[str, object] = {}

    def healthy(url: str, *, timeout: float) -> BytesIO:
        request.update(url=url, timeout=timeout)
        return BytesIO(b'{"status":"UP"}')

    monkeypatch.setattr(urllib.request, "urlopen", healthy)

    preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=f"{BASE_URL}/")

    assert request == {"url": HEALTH_URL, "timeout": 1.0}


@pytest.mark.parametrize(
    ("failure", "reason"),
    (
        (urllib.error.URLError("connection refused"), "connection refused"),
        (
            urllib.error.HTTPError(HEALTH_URL, 503, "Unavailable", Message(), None),
            "HTTP 503",
        ),
    ),
)
def test_preflight_reports_connection_and_http_errors(
    monkeypatch: pytest.MonkeyPatch,
    failure: Exception,
    reason: str,
) -> None:
    def fail(*args: object, **kwargs: object) -> None:
        raise failure

    monkeypatch.setattr(urllib.request, "urlopen", fail)

    with pytest.raises(PreflightError) as caught:
        preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=BASE_URL)

    message = str(caught.value)
    assert HEALTH_URL in message
    assert reason in message
    assert START_COMMAND in message


def test_preflight_reports_a_malformed_base_url(monkeypatch: pytest.MonkeyPatch) -> None:
    base_url = "not-a-url"

    def reject_url(*args: object, **kwargs: object) -> None:
        raise ValueError("unknown url type")

    monkeypatch.setattr(urllib.request, "urlopen", reject_url)

    with pytest.raises(PreflightError) as caught:
        preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=base_url)

    message = str(caught.value)
    assert f"{base_url}/actuator/health" in message
    assert "unknown url type" in message
    assert START_COMMAND in message


def test_preflight_reports_an_incomplete_http_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class IncompleteResponse(BytesIO):
        def read(self, size: int = -1) -> bytes:
            raise http.client.IncompleteRead(b'{"status":')

    monkeypatch.setattr(
        urllib.request,
        "urlopen",
        lambda *args, **kwargs: IncompleteResponse(),
    )

    with pytest.raises(PreflightError) as caught:
        preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=BASE_URL)

    message = str(caught.value)
    assert HEALTH_URL in message
    assert "HTTP response error" in message
    assert START_COMMAND in message


def test_preflight_reports_malformed_json(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        urllib.request,
        "urlopen",
        lambda *args, **kwargs: BytesIO(b"not-json"),
    )

    with pytest.raises(PreflightError) as caught:
        preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=BASE_URL)

    message = str(caught.value)
    assert HEALTH_URL in message
    assert "invalid JSON" in message
    assert START_COMMAND in message


def test_preflight_rejects_a_non_up_status(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        urllib.request,
        "urlopen",
        lambda *args, **kwargs: BytesIO(b'{"status":"DOWN"}'),
    )

    with pytest.raises(PreflightError) as caught:
        preflight_control_plane(CLI_SCENARIO, LOCAL_ENVIRONMENT, base_url=BASE_URL)

    message = str(caught.value)
    assert HEALTH_URL in message
    assert "DOWN" in message
    assert START_COMMAND in message
