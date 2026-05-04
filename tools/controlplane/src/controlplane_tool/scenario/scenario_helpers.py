"""
scenario_helpers.py

Shared, stateless helpers for resolving scenario metadata from a
ResolvedScenario.  Previously duplicated across k3s_runtime, cli_runtime,
and local_e2e_runner.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from controlplane_tool.scenario.scenario_models import ResolvedScenario


def resolve_scenario(scenario_file: Path | None) -> "ResolvedScenario | None":
    """Load and resolve a scenario TOML file, or return None when not given."""
    if scenario_file is None:
        return None
    from controlplane_tool.scenario.scenario_loader import load_scenario_file

    return load_scenario_file(scenario_file)


def selected_functions(
    resolved: "ResolvedScenario | None",
    *,
    default: str = "echo-test",
) -> list[str]:
    """Return the list of function keys to exercise, falling back to *default*."""
    if resolved is None or not resolved.functions:
        return [default]
    return [fn.key for fn in resolved.functions]


def function_image(
    fn_key: str,
    resolved: "ResolvedScenario | None",
    default: str,
) -> str:
    """Return the OCI image for *fn_key*, or *default* when not resolved."""
    if resolved is None:
        return default
    for fn in resolved.functions:
        if fn.key == fn_key and fn.image:
            return fn.image
    return default


def function_runtime(
    fn_key: str,
    resolved: "ResolvedScenario | None",
) -> str:
    """Return the runtime kind for *fn_key* (default: ``'java'``)."""
    if resolved is None:
        return "java"
    for fn in resolved.functions:
        if fn.key == fn_key and fn.runtime:
            return fn.runtime
    return "java"


def function_family(
    fn_key: str,
    resolved: "ResolvedScenario | None",
) -> str | None:
    """Return the function family for *fn_key*, or None when not resolved."""
    if resolved is None:
        return None
    for fn in resolved.functions:
        if fn.key == fn_key and fn.family:
            return fn.family
    return None


def function_payload(
    fn_key: str,
    resolved: "ResolvedScenario | None",
    *,
    default_message: str = "hello",
) -> str:
    """Return a JSON request payload string for *fn_key*.

    Resolution order:
    1. Per-function ``payload_path`` field on the resolved function.
    2. Scenario-level ``payloads`` dict (``resolved.payloads[fn_key]``).
    3. Inline ``{"input": {"message": default_message}}``.
    """
    default = json.dumps({"input": {"message": default_message}})
    if resolved is None:
        return default
    for fn in resolved.functions:
        if fn.key == fn_key and fn.payload_path and fn.payload_path.exists():
            content = json.loads(fn.payload_path.read_text(encoding="utf-8"))
            return json.dumps({"input": content})
    payload_path = resolved.payloads.get(fn_key)
    if payload_path and Path(payload_path).exists():
        content = json.loads(Path(payload_path).read_text(encoding="utf-8"))
        return json.dumps({"input": content})
    return default
