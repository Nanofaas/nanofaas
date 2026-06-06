"""Tests for nanofaas Python runtime"""
import os

import nanofaas_runtime.app as app_module


def test_health(client):
    """Health endpoint returns OK"""
    response = client.get('/health')
    assert response.status_code == 200
    assert response.json['status'] == 'ok'


def test_invoke_with_header_execution_id(client):
    """Invoke accepts X-Execution-Id header"""
    response = client.post('/invoke',
        json={"input": "hello"},
        headers={"X-Execution-Id": "exec-123"})

    assert response.status_code == 200
    assert response.json['echo'] == 'HELLO'


def test_invoke_with_env_execution_id(client, monkeypatch):
    """Invoke falls back to EXECUTION_ID env var (set at startup)"""
    # DEFAULT_EXECUTION_ID is read at module load time, so we patch it directly
    monkeypatch.setattr(app_module, 'DEFAULT_EXECUTION_ID', 'env-exec-456')

    response = client.post('/invoke',
        json={"input": "world"})

    assert response.status_code == 200
    assert response.json['echo'] == 'WORLD'


def test_invoke_without_execution_id_fails(client, monkeypatch):
    """Invoke fails without execution ID"""
    monkeypatch.setattr(app_module, 'DEFAULT_EXECUTION_ID', '')

    response = client.post('/invoke',
        json={"input": "test"})

    assert response.status_code == 400
    assert 'error' in response.json


def test_invoke_header_takes_precedence(client, monkeypatch):
    """Header execution ID takes precedence over ENV"""
    monkeypatch.setattr(app_module, 'DEFAULT_EXECUTION_ID', 'env-id')

    response = client.post('/invoke',
        json={"input": "test"},
        headers={"X-Execution-Id": "header-id"})

    assert response.status_code == 200
    # If we had a way to verify callback, we'd check header-id was used


def test_invoke_with_trace_id(client):
    """Invoke accepts X-Trace-Id header"""
    response = client.post('/invoke',
        json={"input": "traced"},
        headers={
            "X-Execution-Id": "exec-123",
            "X-Trace-Id": "trace-456"
        })

    assert response.status_code == 200
    assert response.json['echo'] == 'TRACED'


def test_invoke_callback_includes_dispatch_attempt_header(client, monkeypatch):
    """Callback includes X-Dispatch-Attempt when invoke request provides it"""
    captured = {}

    class Response:
        ok = True

    def fake_post(url, json, headers, timeout):
        captured['url'] = url
        captured['json'] = json
        captured['headers'] = headers
        captured['timeout'] = timeout
        return Response()

    monkeypatch.setattr(app_module.http_requests, 'post', fake_post)

    response = client.post('/invoke',
        json={"input": "callback"},
        headers={
            "X-Execution-Id": "exec-callback",
            "X-Callback-Url": "http://control/v1/internal/executions",
            "X-Dispatch-Attempt": "3"
        })

    assert response.status_code == 200
    assert captured['headers']['X-Dispatch-Attempt'] == '3'


def test_invoke_empty_payload(client):
    """Invoke handles empty/null input"""
    response = client.post('/invoke',
        json={},
        headers={"X-Execution-Id": "exec-123"})

    assert response.status_code == 200
    assert response.json['echo'] == ''
