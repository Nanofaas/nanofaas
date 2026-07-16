from __future__ import annotations

import http.client
import json
import urllib.error
import urllib.request

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig


class PreflightError(RuntimeError):
    """Raised when a required control plane is not healthy."""


def preflight_control_plane(
    scenario: ScenarioConfig,
    environment: EnvironmentConfig,
    *,
    base_url: str,
) -> None:
    if scenario.workflow != "cli" or environment.provider != "local":
        return

    endpoint = f"{base_url.rstrip('/')}/actuator/health"
    try:
        with urllib.request.urlopen(endpoint, timeout=1.0) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise _failure(endpoint, f"HTTP {error.code}") from error
    except urllib.error.URLError as error:
        raise _failure(endpoint, str(error.reason)) from error
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise _failure(endpoint, "invalid JSON response") from error
    except http.client.HTTPException as error:
        raise _failure(endpoint, f"HTTP response error: {error}") from error
    except (OSError, ValueError) as error:
        raise _failure(endpoint, str(error)) from error

    status = payload.get("status") if isinstance(payload, dict) else None
    if status != "UP":
        raise _failure(endpoint, f"reported status {status!r}")


def _failure(endpoint: str, reason: str) -> PreflightError:
    return PreflightError(
        f"Control plane preflight failed at {endpoint}: {reason}. "
        "Start it with './gradlew :control-plane:bootRun' and retry."
    )
