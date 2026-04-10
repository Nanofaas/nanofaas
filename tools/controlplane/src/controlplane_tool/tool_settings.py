from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict


class ToolSettings(BaseSettings):
    model_config = SettingsConfigDict(env_ignore_empty=False)

    nanofaas_tool_control_plane_url: str = ""
    nanofaas_tool_control_plane_management_url: str = ""
    nanofaas_tool_prometheus_url: str = ""
    nanofaas_tool_mockk8s_url: str = ""
    nanofaas_tool_registry_url: str = ""
