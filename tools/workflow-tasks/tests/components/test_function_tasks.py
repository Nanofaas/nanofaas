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
