from __future__ import annotations

from unittest.mock import MagicMock, patch

from workflow_tasks.components.function_tasks import FunctionSpec, RegisterFunctions


def test_function_spec_to_body_maps_camelcase_fields() -> None:
    body = FunctionSpec(name="echo", image="reg/echo:e2e").to_body()
    assert body["name"] == "echo"
    assert body["image"] == "reg/echo:e2e"
    assert body["executionMode"] == "DEPLOYMENT"
    assert body["timeoutMs"] == 5000
    assert body["maxRetries"] == 3


def test_function_spec_to_body_includes_scaling_config_when_present() -> None:
    body = FunctionSpec(
        name="word-stats-java",
        image="localhost:5000/nanofaas/java-word-stats:e2e",
        timeout_ms=30000,
        concurrency=4,
        queue_size=100,
        scaling_config={
            "strategy": "INTERNAL",
            "minReplicas": 0,
            "maxReplicas": 5,
            "metrics": [{"type": "in_flight", "target": "2"}],
        },
    ).to_body()

    assert body["scalingConfig"] == {
        "strategy": "INTERNAL",
        "minReplicas": 0,
        "maxReplicas": 5,
        "metrics": [{"type": "in_flight", "target": "2"}],
    }


def test_function_spec_to_body_omits_scaling_config_by_default() -> None:
    body = FunctionSpec(name="echo", image="reg/echo:e2e").to_body()

    assert "scalingConfig" not in body


def test_register_functions_posts_each_spec() -> None:
    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080/",
        specs=[FunctionSpec(name="a", image="i:1"), FunctionSpec(name="b", image="i:2")],
    )
    with patch("urllib.request.urlopen") as mock_open:
        mock_open.return_value.__enter__ = MagicMock(return_value=MagicMock())
        mock_open.return_value.__exit__ = MagicMock(return_value=False)
        task.run()
    assert mock_open.call_count == 2
    posted = mock_open.call_args_list[0].args[0]
    assert posted.full_url == "http://cp:8080/v1/functions"
    assert posted.method == "POST"


def test_register_functions_raises_on_http_error() -> None:
    import urllib.error

    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080",
        specs=[FunctionSpec(name="bad", image="i:1")],
    )
    err = urllib.error.HTTPError(url="u", code=409, msg="conflict", hdrs=None, fp=None)
    with patch("urllib.request.urlopen", side_effect=err):
        try:
            task.run()
        except RuntimeError as exc:
            assert "bad" in str(exc) and "409" in str(exc)
            return
    raise AssertionError("expected RuntimeError")


def _http_error(code: int) -> "urllib.error.HTTPError":
    import urllib.error

    return urllib.error.HTTPError("http://cp:8080/v1/functions", code, "conflict", None, None)


def test_register_functions_on_conflict_skip_tolerates_409() -> None:
    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080",
        specs=[FunctionSpec(name="a", image="i:1"), FunctionSpec(name="b", image="i:2")],
        on_conflict="skip",
    )
    with patch("urllib.request.urlopen") as mock_open:
        mock_open.side_effect = [_http_error(409), MagicMock()]
        task.run()
    assert mock_open.call_count == 2


def test_register_functions_on_conflict_replace_deletes_then_reregisters() -> None:
    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080",
        specs=[FunctionSpec(name="word-stats-java", image="i:1")],
        on_conflict="replace",
    )
    with patch("urllib.request.urlopen") as mock_open:
        mock_open.side_effect = [_http_error(409), MagicMock(), MagicMock()]
        task.run()
    assert mock_open.call_count == 3
    methods = [call.args[0].method for call in mock_open.call_args_list]
    assert methods == ["POST", "DELETE", "POST"]
    delete_req = mock_open.call_args_list[1].args[0]
    assert delete_req.full_url == "http://cp:8080/v1/functions/word-stats-java"


def test_register_functions_default_still_fails_on_409() -> None:
    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080",
        specs=[FunctionSpec(name="a", image="i:1")],
    )
    with patch("urllib.request.urlopen") as mock_open:
        mock_open.side_effect = [_http_error(409)]
        try:
            task.run()
        except RuntimeError as exc:
            assert "HTTP 409" in str(exc)
            return
    raise AssertionError("expected RuntimeError")


def test_register_functions_non_409_error_always_fails_even_with_skip() -> None:
    task = RegisterFunctions(
        task_id="fn.register",
        title="Register",
        control_plane_url="http://cp:8080",
        specs=[FunctionSpec(name="a", image="i:1")],
        on_conflict="skip",
    )
    with patch("urllib.request.urlopen") as mock_open:
        mock_open.side_effect = [_http_error(500)]
        try:
            task.run()
        except RuntimeError as exc:
            assert "HTTP 500" in str(exc)
            return
    raise AssertionError("expected RuntimeError")
