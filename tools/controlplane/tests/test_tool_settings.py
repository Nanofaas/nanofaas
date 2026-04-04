"""Tests for tool_settings.py — pydantic-settings env var loading."""
from __future__ import annotations


def test_tool_settings_reads_control_plane_url(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_CONTROL_PLANE_URL", "http://cp.example.test:8080")
    from controlplane_tool.tool_settings import ToolSettings
    s = ToolSettings()
    assert s.nanofaas_tool_control_plane_url == "http://cp.example.test:8080"


def test_tool_settings_control_plane_url_defaults_empty(monkeypatch) -> None:
    monkeypatch.delenv("NANOFAAS_TOOL_CONTROL_PLANE_URL", raising=False)
    from controlplane_tool.tool_settings import ToolSettings
    s = ToolSettings()
    assert s.nanofaas_tool_control_plane_url == ""


def test_tool_settings_reads_prometheus_url(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_PROMETHEUS_URL", "http://prom.example.test:9090")
    from controlplane_tool.tool_settings import ToolSettings
    s = ToolSettings()
    assert s.nanofaas_tool_prometheus_url == "http://prom.example.test:9090"


def test_tool_settings_reads_mockk8s_url(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_MOCKK8S_URL", "http://mock.example.test:18080")
    from controlplane_tool.tool_settings import ToolSettings
    s = ToolSettings()
    assert s.nanofaas_tool_mockk8s_url == "http://mock.example.test:18080"


def test_tool_settings_reads_management_url(monkeypatch) -> None:
    monkeypatch.setenv("NANOFAAS_TOOL_CONTROL_PLANE_MANAGEMENT_URL", "http://mgmt.example.test:8081")
    from controlplane_tool.tool_settings import ToolSettings
    s = ToolSettings()
    assert s.nanofaas_tool_control_plane_management_url == "http://mgmt.example.test:8081"
