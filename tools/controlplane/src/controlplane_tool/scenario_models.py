from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from controlplane_tool.function_catalog import (
    FunctionDefinition,
    resolve_function_definition,
    resolve_function_preset,
)
from controlplane_tool.models import LoadProfile, RuntimeKind, ScenarioName


class ScenarioInvokeConfig(BaseModel):
    mode: Literal["smoke", "sync", "async", "parity"] = "smoke"
    payload_dir: str | None = None


class ScenarioLoadConfig(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    load_profile_name: LoadProfile | None = Field(default=None, alias="profile")
    targets: list[str] = Field(default_factory=list)


class ScenarioSpec(BaseModel):
    name: str
    base_scenario: ScenarioName
    runtime: RuntimeKind = "java"
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    namespace: str | None = None
    local_registry: str | None = None
    payloads: dict[str, str] = Field(default_factory=dict)
    invoke: ScenarioInvokeConfig = Field(default_factory=ScenarioInvokeConfig)
    load: ScenarioLoadConfig = Field(default_factory=ScenarioLoadConfig)

    @model_validator(mode="after")
    def validate_selection(self) -> "ScenarioSpec":
        selected_sources = int(bool(self.function_preset)) + int(bool(self.functions))
        if selected_sources != 1:
            raise ValueError("exactly one of function_preset or functions is required")

        selected_keys: list[str]
        if self.function_preset:
            preset = resolve_function_preset(self.function_preset)
            selected_keys = [function.key for function in preset.functions]
        else:
            selected_keys = []
            for key in self.functions:
                resolve_function_definition(key)
                selected_keys.append(key)

        invalid_targets = [target for target in self.load.targets if target not in selected_keys]
        if invalid_targets:
            raise ValueError("load.targets must be a subset of the selected functions")
        return self


class ResolvedFunction(BaseModel):
    key: str
    family: str
    runtime: str
    description: str
    example_dir: Path | None = None
    image: str | None = None
    payload_path: Path | None = None

    @classmethod
    def from_definition(
        cls,
        definition: FunctionDefinition,
        *,
        image: str | None,
        payload_path: Path | None,
    ) -> "ResolvedFunction":
        return cls(
            key=definition.key,
            family=definition.family,
            runtime=definition.runtime,
            description=definition.description,
            example_dir=definition.example_dir,
            image=image,
            payload_path=payload_path,
        )


class ResolvedScenario(BaseModel):
    source_path: Path | None = None
    name: str
    base_scenario: ScenarioName
    runtime: RuntimeKind = "java"
    function_preset: str | None = None
    functions: list[ResolvedFunction] = Field(default_factory=list)
    function_keys: list[str] = Field(default_factory=list)
    namespace: str | None = None
    local_registry: str = "localhost:5000"
    payloads: dict[str, Path] = Field(default_factory=dict)
    invoke: ScenarioInvokeConfig = Field(default_factory=ScenarioInvokeConfig)
    load: ScenarioLoadConfig = Field(default_factory=ScenarioLoadConfig)

    def payload_overrides(self) -> dict[str, str]:
        return {key: str(path) for key, path in self.payloads.items()}

    def selected_runtimes(self) -> set[str]:
        return {function.runtime for function in self.functions}
