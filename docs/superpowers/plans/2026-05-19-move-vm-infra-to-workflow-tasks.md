# Move VM Infrastructure to workflow_tasks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spostare `VmRequest`, `VmLifecycle`, `MultipassVmProvider`, `AzureVmProvider`, `query_prometheus_range_series` e tutte le implementazioni degli adapter in `workflow_tasks`, così che un altro progetto possa importarli direttamente senza dipendere da `controlplane_tool`.

**Architecture:** `workflow_tasks` diventa il layer riusabile con l'infrastruttura VM concreta (multipass, azure) e gli adapter. `controlplane_tool` resta il consumatore: `VmOrchestrator` estende `MultipassVmProvider`, `AzureVmOrchestrator` estende `AzureVmProvider`. I file `vm_models.py`, `vm_lifecycle_adapters.py`, `loadtest_adapters.py` diventano shim di re-export per backward compatibility.

**Tech Stack:** Python 3.11+, pydantic, pydantic-settings, shellcraft, multipass-sdk, azure-vm-sdk, httpx.

---

## File Map

**Modificati in `tools/workflow-tasks/`:**
- `pyproject.toml` — aggiunge 6 nuove dipendenze
- `src/workflow_tasks/vm/models.py` — aggiunge `VmLifecycle`, `VmRequest`, `vm_request_from_env`
- `src/workflow_tasks/vm/__init__.py` — aggiunge nuovi export
- `src/workflow_tasks/loadtest/__init__.py` — aggiunge nuovi export
- `src/workflow_tasks/__init__.py` — aggiunge nuovi export
- `tests/test_package_boundaries.py` — rimuove vincoli obsoleti
- `tests/test_public_api.py` — aggiunge assertions per nuovi tipi

**Creati in `tools/workflow-tasks/src/workflow_tasks/`:**
- `vm/multipass.py` — `MultipassVmProvider` (infrastruttura generica multipass)
- `vm/azure.py` — `AzureVmProvider` (infrastruttura generica azure)
- `vm/runners.py` — `OrchestratorVmRunner`, `VmFileFetcher`
- `vm/adapters.py` — `VmLifecycleAdapter`, `MultipassVmAdapter`, `AzureVmAdapter`
- `loadtest/prometheus.py` — `query_prometheus_range_series`
- `loadtest/adapters.py` — `HttpPrometheusClient`

**Modificati in `tools/controlplane/`:**
- `src/controlplane_tool/infra/vm/vm_models.py` — re-export shim
- `src/controlplane_tool/infra/vm/vm_adapter.py` — `VmOrchestrator(MultipassVmProvider)` solo metodi nanofaas-specifici
- `src/controlplane_tool/infra/vm/azure_vm_adapter.py` — `AzureVmOrchestrator(AzureVmProvider)` thin subclass
- `src/controlplane_tool/infra/vm_lifecycle_adapters.py` — re-export shim
- `src/controlplane_tool/loadtest/loadtest_adapters.py` — re-export shim
- `src/controlplane_tool/loadtest/metrics.py` — re-export `query_prometheus_range_series`
- `tests/test_vm_lifecycle_adapters.py` — aggiorna imports
- `tests/test_loadtest_adapters.py` — aggiorna imports

---

## Task 1: Aggiungi dipendenze a workflow_tasks

**Files:**
- Modify: `tools/workflow-tasks/pyproject.toml`

- [ ] **Step 1: Aggiorna pyproject.toml**

Leggi `tools/workflow-tasks/pyproject.toml`. Sostituisci:
```toml
dependencies = []
```
con:
```toml
dependencies = [
    "pydantic>=2.9.2",
    "pydantic-settings>=2.5",
    "httpx>=0.27",
    "multipass-sdk @ git+https://github.com/miciav/multipass-sdk.git@83b3704",
    "azure-vm-sdk @ git+https://github.com/miciav/azure-vm-sdk.git@2311b1c",
    "shellcraft @ git+https://github.com/miciav/shellcraft.git",
]
```

- [ ] **Step 2: Sincronizza l'ambiente**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv sync 2>&1 | tail -5
```
Expected: `Resolved ... packages`

- [ ] **Step 3: Verifica che i test esistenti passino ancora**

```bash
uv run pytest -q --tb=short 2>&1 | tail -5
```
Expected: tutti i test passano (≥90% coverage)

- [ ] **Step 4: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/workflow-tasks/pyproject.toml && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add vm and prometheus infrastructure dependencies

Adds shellcraft, multipass-sdk, azure-vm-sdk, pydantic, pydantic-settings,
httpx as dependencies to support moving VM infrastructure into the library.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Sposta VmLifecycle e VmRequest in workflow_tasks

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py`
- Create: `tools/workflow-tasks/tests/vm/test_vm_request.py`

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/vm/test_vm_request.py
from __future__ import annotations

import pytest

from workflow_tasks.vm.models import VmConfig, VmInfo, VmLifecycle, VmRequest


def test_vm_request_minimal() -> None:
    req = VmRequest(lifecycle="multipass", name="my-vm")
    assert req.lifecycle == "multipass"
    assert req.name == "my-vm"
    assert req.user == "ubuntu"
    assert req.cpus == 4
    assert req.memory == "12G"
    assert req.disk == "30G"


def test_vm_request_external_requires_host() -> None:
    with pytest.raises(Exception):
        VmRequest(lifecycle="external")  # host mancante


def test_vm_lifecycle_values() -> None:
    assert "multipass" in VmLifecycle.__args__  # type: ignore[attr-defined]
    assert "azure" in VmLifecycle.__args__  # type: ignore[attr-defined]
    assert "external" in VmLifecycle.__args__  # type: ignore[attr-defined]


def test_vm_config_still_works() -> None:
    cfg = VmConfig(name="test", cpus=2)
    assert cfg.name == "test"
    assert cfg.cpus == 2


def test_vm_info_still_works() -> None:
    info = VmInfo(name="test", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    assert info.host == "10.0.0.1"
```

- [ ] **Step 2: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_request.py -v 2>&1 | tail -8
```
Expected: `ImportError: cannot import name 'VmRequest'`

- [ ] **Step 3: Aggiungi VmLifecycle e VmRequest a models.py**

Leggi `tools/workflow-tasks/src/workflow_tasks/vm/models.py` (attuale: VmConfig, VmInfo).

Aggiungi DOPO le definizioni esistenti:

```python
from typing import Literal

from pydantic import BaseModel, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

VmLifecycle = Literal["multipass", "external", "azure"]


class VmRequest(BaseModel):
    lifecycle: VmLifecycle
    name: str | None = None
    host: str | None = None
    user: str = "ubuntu"
    home: str | None = None
    cpus: int = 4
    memory: str = "12G"
    disk: str = "30G"
    azure_vm_size: str = "Standard_B2s"
    azure_resource_group: str | None = None
    azure_location: str | None = None
    azure_image_urn: str | None = None
    azure_ssh_key_path: str | None = None

    @model_validator(mode="after")
    def validate_lifecycle_requirements(self) -> "VmRequest":
        if self.lifecycle == "external" and not self.host:
            raise ValueError("host is required for external lifecycle")
        return self


class _VmEnvSettings(BaseSettings):
    model_config = SettingsConfigDict(env_ignore_empty=True)

    e2e_vm_lifecycle: VmLifecycle = "multipass"
    vm_name: str | None = None
    e2e_vm_host: str | None = None
    e2e_vm_user: str = "ubuntu"
    e2e_vm_home: str | None = None
    cpus: int = 4
    memory: str = "12G"
    disk: str = "30G"


def vm_request_from_env() -> VmRequest:
    s = _VmEnvSettings()
    return VmRequest(
        lifecycle=s.e2e_vm_lifecycle,
        name=s.vm_name,
        host=s.e2e_vm_host,
        user=s.e2e_vm_user,
        home=s.e2e_vm_home,
        cpus=s.cpus,
        memory=s.memory,
        disk=s.disk,
    )
```

Assicurati che `from __future__ import annotations` sia la prima riga del file.
Rimuovi da models.py le importazioni di `Literal` se già presenti come built-in — aggiungi `from typing import Literal` in cima.

- [ ] **Step 4: Aggiorna vm_models.py in controlplane_tool come re-export shim**

Leggi `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py`. Sostituisci interamente con:

```python
# Re-exports from workflow_tasks. Import from here for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.models import VmLifecycle, VmRequest, vm_request_from_env
from pydantic_settings import BaseSettings, SettingsConfigDict

__all__ = ["VmLifecycle", "VmRequest", "vm_request_from_env"]
```

- [ ] **Step 5: Verifica che i test passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_request.py -v 2>&1 | tail -8
```
Expected: 5 passed

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: tutti i test di controlplane passano

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/models.py \
    tools/workflow-tasks/tests/vm/test_vm_request.py \
    tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): move VmLifecycle and VmRequest to vm/models.py

controlplane_tool/infra/vm/vm_models.py becomes a re-export shim for
backward compatibility.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Sposta query_prometheus_range_series in workflow_tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/prometheus.py`
- Create: `tools/workflow-tasks/tests/loadtest/test_prometheus.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/metrics.py`

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/loadtest/test_prometheus.py
from __future__ import annotations

from unittest.mock import patch

import pytest

from workflow_tasks.loadtest.prometheus import query_prometheus_range_series


def test_query_range_series_raises_on_http_error() -> None:
    with patch("workflow_tasks.loadtest.prometheus.httpx.get") as mock_get:
        mock_get.side_effect = Exception("connection refused")
        with pytest.raises(RuntimeError, match="prometheus api request failed"):
            from datetime import datetime, timezone
            query_prometheus_range_series(
                "http://localhost:9090",
                "http_requests_total",
                datetime(2026, 1, 1, tzinfo=timezone.utc),
                datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
            )


def test_query_range_series_returns_parsed_points() -> None:
    from datetime import datetime, timezone
    from unittest.mock import MagicMock

    mock_response = MagicMock()
    mock_response.json.return_value = {
        "status": "success",
        "data": {
            "result": [
                {
                    "metric": {"__name__": "http_requests_total"},
                    "values": [
                        [1704067200.0, "42"],
                        [1704067260.0, "43"],
                    ],
                }
            ]
        },
    }

    with patch("workflow_tasks.loadtest.prometheus.httpx.get", return_value=mock_response):
        result = query_prometheus_range_series(
            "http://localhost:9090",
            "http_requests_total",
            datetime(2026, 1, 1, tzinfo=timezone.utc),
            datetime(2026, 1, 1, 1, tzinfo=timezone.utc),
        )

    assert isinstance(result, list)
    assert len(result) == 2
    assert result[0]["value"] == 42.0
```

- [ ] **Step 2: Verifica che fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_prometheus.py -v 2>&1 | tail -8
```
Expected: `ModuleNotFoundError: No module named 'workflow_tasks.loadtest.prometheus'`

- [ ] **Step 3: Crea workflow_tasks/loadtest/prometheus.py**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/prometheus.py
from __future__ import annotations

from datetime import datetime
from typing import Any

import httpx


def _prometheus_api_get(
    base_url: str, path: str, params: dict[str, str], timeout_seconds: float = 4.0
) -> Any:
    url = f"{base_url.rstrip('/')}{path}"
    try:
        response = httpx.get(url, params=params, timeout=timeout_seconds)
        data = response.json()
    except (httpx.RequestError, httpx.HTTPStatusError, ValueError) as exc:
        raise RuntimeError(f"prometheus api request failed for {path}: {exc}") from exc
    if data.get("status") != "success":
        raise RuntimeError(f"prometheus api failed for {path}: {data}")
    return data.get("data")


def query_prometheus_range_series(
    base_url: str,
    metric_name: str,
    start: datetime,
    end: datetime,
    step_seconds: int = 2,
) -> list[dict[str, float | str]]:
    data = _prometheus_api_get(
        base_url,
        "/api/v1/query_range",
        {
            "query": metric_name,
            "start": str(start.timestamp()),
            "end": str(end.timestamp()),
            "step": f"{step_seconds}s",
        },
    )
    if not isinstance(data, dict):
        raise RuntimeError("invalid prometheus query_range payload")
    result = data.get("result", [])
    if not isinstance(result, list):
        raise RuntimeError("invalid prometheus query_range payload")

    points: list[dict[str, float | str]] = []
    for series in result:
        for ts, val in series.get("values", []):
            try:
                points.append({"timestamp": float(ts), "value": float(val)})
            except (TypeError, ValueError):
                continue
    return points
```

- [ ] **Step 4: Aggiorna controlplane_tool/loadtest/metrics.py**

Leggi il file. Trova la definizione di `query_prometheus_range_series` (e la funzione privata `_prometheus_api_get`). Sostituisci SOLO quelle due funzioni con un import dal nuovo modulo, lasciando tutto il resto del file invariato:

```python
# In metrics.py: sostituisci la definizione di _prometheus_api_get e query_prometheus_range_series con:
from workflow_tasks.loadtest.prometheus import (
    _prometheus_api_get,
    query_prometheus_range_series,
)
```

Le funzioni `parse_prometheus_metric_names`, `query_prometheus_metric_names`, `build_required_metric_series`, `discover_control_plane_metric_names`, `parse_prometheus_sample_values`, `missing_required_metrics` restano in metrics.py.

- [ ] **Step 5: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_prometheus.py -v 2>&1 | tail -10
```
Expected: 2 passed

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: tutti i test passano

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/prometheus.py \
    tools/workflow-tasks/tests/loadtest/test_prometheus.py \
    tools/controlplane/src/controlplane_tool/loadtest/metrics.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): move query_prometheus_range_series to loadtest/prometheus.py

controlplane_tool/loadtest/metrics.py re-exports the function for
backward compatibility.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Crea MultipassVmProvider in workflow_tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/multipass.py`
- Create: `tools/workflow-tasks/tests/vm/test_multipass_provider.py`

`MultipassVmProvider` contiene l'infrastruttura generica multipass (connessione, esecuzione comandi, trasferimento file, lifecycle). I metodi nanofaas-specifici (sync_project, install_k3s, ecc.) restano in `VmOrchestrator` nel Task 5.

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/vm/test_multipass_provider.py
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock, patch

from workflow_tasks.vm.models import VmRequest
from workflow_tasks.vm.multipass import MultipassVmProvider


@dataclass
class _FakeShellResult:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""
    command: list[str] = None  # type: ignore


def _make_provider(workspace_root: Path | None = None) -> MultipassVmProvider:
    shell = MagicMock()
    shell.run.return_value = _FakeShellResult(command=["echo"])
    client = MagicMock()
    return MultipassVmProvider(
        workspace_root=workspace_root or Path("/repo"),
        shell=shell,
        multipass_client=client,
    )


def test_remote_home_default_ubuntu() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="ubuntu")
    assert provider.remote_home(req) == "/home/ubuntu"


def test_remote_home_custom() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="ubuntu", home="/custom")
    assert provider.remote_home(req) == "/custom"


def test_remote_home_root_user() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass", name="vm1", user="root")
    assert provider.remote_home(req) == "/root"


def test_vm_name_uses_name_field() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    assert provider.vm_name(req) == "my-vm"


def test_vm_name_default() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass")
    assert provider.vm_name(req) == "nanofaas-e2e"


def test_teardown_dry_run_returns_ok() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="multipass", name="my-vm")
    result = provider.teardown(req, dry_run=True)
    assert result.return_code == 0


def test_connection_host_external() -> None:
    provider = _make_provider()
    req = VmRequest(lifecycle="external", host="192.168.1.100")
    assert provider.connection_host(req) == "192.168.1.100"
```

- [ ] **Step 2: Verifica che fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_multipass_provider.py -v 2>&1 | tail -8
```
Expected: `ModuleNotFoundError: No module named 'workflow_tasks.vm.multipass'`

- [ ] **Step 3: Crea workflow_tasks/vm/multipass.py**

Contiene le funzioni libere e la classe `MultipassVmProvider`. Estrai da `controlplane_tool/infra/vm/vm_adapter.py` le parti generiche. Il parametro `__init__` è `workspace_root: Path` (non `repo_root`) e NON usa `ToolPaths`.

```python
# tools/workflow-tasks/src/workflow_tasks/vm/multipass.py
from __future__ import annotations

import shlex
from pathlib import Path
from typing import TYPE_CHECKING

from multipass import MultipassClient, MultipassCommandError, VmNotFoundError, find_ssh_public_key
from shellcraft.backend import ShellBackend, ShellExecutionResult, SubprocessShell

from workflow_tasks.vm.models import VmRequest

if TYPE_CHECKING:
    pass


def _vm_name_default(request: VmRequest) -> str:
    return request.name or "nanofaas-e2e"


def _ok(command: list[str], *, stdout: str = "") -> ShellExecutionResult:
    return ShellExecutionResult(command=command, return_code=0, stdout=stdout)


def _sdk_error(e: MultipassCommandError) -> ShellExecutionResult:
    return ShellExecutionResult(
        command=e.args_list,
        return_code=e.returncode,
        stdout=e.stdout,
        stderr=e.stderr,
    )


def _find_ssh_private_key_path(public_key: str | None = None) -> Path | None:
    ssh_dir = Path.home() / ".ssh"
    normalized_public_key = public_key.strip() if public_key else None
    for name in ("id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"):
        pub = ssh_dir / f"{name}.pub"
        priv = ssh_dir / name
        if pub.exists() and priv.exists():
            if normalized_public_key is not None:
                if pub.read_text(encoding="utf-8").strip() == normalized_public_key:
                    return priv
                continue
            return priv
    return None


def resolve_connection_host(
    request: VmRequest,
    client: MultipassClient,
    *,
    dry_run: bool = False,
) -> str:
    if request.lifecycle == "external":
        if not request.host:
            raise RuntimeError("external VM lifecycle requires a host")
        return request.host
    if dry_run:
        return f"<multipass-ip:{_vm_name_default(request)}>"
    try:
        info = client.get_vm(_vm_name_default(request)).info()
    except VmNotFoundError:
        raise RuntimeError(f"Unable to resolve Multipass VM '{_vm_name_default(request)}'")
    if info.ipv4:
        return info.ipv4[0]
    raise RuntimeError(f"Multipass VM '{_vm_name_default(request)}' has no IPv4 address")


class MultipassVmProvider:
    """Generic multipass VM provider: lifecycle, command execution, file transfer.

    Subclass this to add project-specific operations (e.g. VmOrchestrator in controlplane_tool).
    """

    def __init__(
        self,
        workspace_root: Path,
        shell: ShellBackend | None = None,
        multipass_client: MultipassClient | None = None,
    ) -> None:
        self.workspace_root = Path(workspace_root)
        self.shell = shell or SubprocessShell()
        self._client = multipass_client or MultipassClient()
        self._ssh_public_key: str | None = find_ssh_public_key()
        self._private_key_path: Path | None = _find_ssh_private_key_path(self._ssh_public_key)

    # --- Internal helpers ---

    def _vm_name(self, request: VmRequest) -> str:
        return _vm_name_default(request)

    def _remote_home(self, request: VmRequest) -> str:
        if request.home:
            return request.home
        if request.user == "root":
            return "/root"
        return f"/home/{request.user}"

    def _shell_run(self, command: list[str], *, dry_run: bool = False) -> ShellExecutionResult:
        return self.shell.run(command, cwd=self.workspace_root, dry_run=dry_run)

    def _ensure_multipass_authorized_key(self, request: VmRequest) -> None:
        if not self._ssh_public_key:
            return
        name = self._vm_name(request)
        remote_home = self._remote_home(request)
        authorized_keys = f"{remote_home}/.ssh/authorized_keys"
        quoted_key = shlex.quote(self._ssh_public_key)
        if request.user == "root":
            command = (
                f"install -d -m 700 {shlex.quote(remote_home)}/.ssh && "
                f"touch {shlex.quote(authorized_keys)} && "
                f"chmod 600 {shlex.quote(authorized_keys)} && "
                f"grep -qxF {quoted_key} {shlex.quote(authorized_keys)} || "
                f"printf '%s\\n' {quoted_key} >> {shlex.quote(authorized_keys)}"
            )
        else:
            command = (
                f"sudo install -d -m 700 -o {shlex.quote(request.user)} -g {shlex.quote(request.user)} {shlex.quote(remote_home)}/.ssh && "
                f"sudo touch {shlex.quote(authorized_keys)} && "
                f"sudo chown {shlex.quote(request.user)}:{shlex.quote(request.user)} {shlex.quote(authorized_keys)} && "
                f"sudo chmod 600 {shlex.quote(authorized_keys)} && "
                f"sudo -u {shlex.quote(request.user)} bash -lc "
                f"\"grep -qxF {quoted_key} {shlex.quote(authorized_keys)} || printf '%s\\\\n' {quoted_key} >> {shlex.quote(authorized_keys)}\""
            )
        self._client.get_vm(name).exec(["bash", "-lc", command])

    def _build_exec_script(
        self,
        argv: tuple[str, ...] | list[str],
        env: dict[str, str] | None = None,
        cwd: str | None = None,
    ) -> list[str]:
        parts: list[str] = []
        if cwd:
            parts.append(f"cd {shlex.quote(cwd)}")
        if env:
            for k, v in env.items():
                parts.append(f"export {k}={shlex.quote(v)}")
        parts.append(shlex.join(list(argv)))
        script = " && ".join(parts) if parts else shlex.join(list(argv))
        return ["bash", "-lc", script]

    # --- Public API ---

    def vm_name(self, request: VmRequest) -> str:
        return self._vm_name(request)

    def remote_home(self, request: VmRequest) -> str:
        return self._remote_home(request)

    def resolve_multipass_ipv4(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def connection_host(self, request: VmRequest, *, dry_run: bool = False) -> str:
        return resolve_connection_host(request, self._client, dry_run=dry_run)

    def ensure_running(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}", "true"], dry_run=dry_run
            )
        name = self._vm_name(request)
        launch_cmd = [
            "multipass", "launch", "--name", name,
            "--cpus", str(request.cpus), "--memory", request.memory, "--disk", request.disk,
        ]
        if dry_run:
            return _ok(launch_cmd)
        cloud_init_config = (
            {"ssh_authorized_keys": [self._ssh_public_key]} if self._ssh_public_key else None
        )
        self._client.ensure_running(
            name,
            cpus=request.cpus,
            memory=request.memory,
            disk=request.disk,
            cloud_init_config=cloud_init_config,
        )
        self._ensure_multipass_authorized_key(request)
        return _ok(launch_cmd)

    def teardown(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["echo", "Skipping teardown for external VM lifecycle"], dry_run=dry_run
            )
        name = self._vm_name(request)
        if dry_run:
            return _ok(["multipass", "delete", name])
        try:
            self._client.get_vm(name).delete()
        except (VmNotFoundError, MultipassCommandError) as e:
            if isinstance(e, MultipassCommandError):
                return _sdk_error(e)
        return _ok(["multipass", "delete", name])

    def inspect(self, request: VmRequest, *, dry_run: bool = False) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}", "hostname"], dry_run=dry_run
            )
        name = self._vm_name(request)
        if dry_run:
            return _ok(["multipass", "info", name])
        try:
            info = self._client.get_vm(name).info()
            stdout = (
                f"Name:  {info.name}\n"
                f"State: {info.state.value}\n"
                f"IPv4:  {', '.join(info.ipv4) or '-'}\n"
            )
        except (VmNotFoundError, MultipassCommandError) as e:
            if isinstance(e, MultipassCommandError):
                return _sdk_error(e)
            return _ok(["multipass", "info", name], stdout="(not found)")
        return _ok(["multipass", "info", name], stdout=stdout)

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        command = self._build_exec_script(argv, env=env, cwd=cwd)
        return self.remote_exec(request, command=command, dry_run=dry_run)

    def remote_exec(
        self, request: VmRequest, command: list[str], *, dry_run: bool = False
    ) -> ShellExecutionResult:
        if request.lifecycle == "external":
            return self._shell_run(
                ["ssh", f"{request.user}@{request.host}"] + command, dry_run=dry_run
            )
        name = self._vm_name(request)
        if dry_run:
            return _ok(command)
        try:
            result = self._client.get_vm(name).exec(command)
            return ShellExecutionResult(
                command=command,
                return_code=result.returncode,
                stdout=result.stdout,
                stderr=result.stderr,
            )
        except MultipassCommandError as e:
            return _sdk_error(e)

    def transfer_to(
        self,
        request: VmRequest,
        *,
        source: Path,
        destination: str,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        name = self._vm_name(request)
        cmd = ["multipass", "transfer", str(source), f"{name}:{destination}"]
        if dry_run:
            return _ok(cmd)
        try:
            self._client.get_vm(name).transfer_to_vm(str(source), destination)
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(cmd)

    def transfer_from(
        self,
        request: VmRequest,
        *,
        source: str,
        destination: Path,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        name = self._vm_name(request)
        cmd = ["multipass", "transfer", f"{name}:{source}", str(destination)]
        if dry_run:
            return _ok(cmd)
        try:
            self._client.get_vm(name).transfer_from_vm(source, str(destination))
        except MultipassCommandError as e:
            return _sdk_error(e)
        return _ok(cmd)
```

**Nota:** I metodi `transfer_to` e `transfer_from` usano l'SDK multipass se disponibile (`transfer_to_vm`, `transfer_from_vm`). Se l'SDK non ha questi metodi, usa invece `_shell_run` con `scp`. Verifica in `controlplane_tool/infra/vm/vm_adapter.py` come sono implementati lì e replica la stessa logica.

- [ ] **Step 4: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_multipass_provider.py -v 2>&1 | tail -12
```
Expected: 7 passed

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/multipass.py \
    tools/workflow-tasks/tests/vm/test_multipass_provider.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add MultipassVmProvider with generic multipass VM operations

Extracted from VmOrchestrator: lifecycle, exec_argv, transfer_from/to,
connection_host. Nanofaas-specific methods (sync_project, install_k3s, etc.)
remain in VmOrchestrator.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Refactora VmOrchestrator per estendere MultipassVmProvider

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py`

`VmOrchestrator` diventa una sottoclasse di `MultipassVmProvider`. Mantiene solo i metodi nanofaas-specifici: `sync_project`, `install_dependencies`, `install_k3s`, `setup_registry`, `ensure_registry_container`, `configure_k3s_registry`, `remote_path_for_local`, `remote_project_dir`, `kubeconfig_path`. Tutto il resto è ereditato.

- [ ] **Step 1: Leggi vm_adapter.py per capire cosa eliminare**

```bash
grep -n "def " /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py
```

Metodi da RIMUOVERE (ereditati da MultipassVmProvider):
- `_vm_name`, `_remote_home`, `_shell_run`, `_ensure_multipass_authorized_key`, `_build_exec_script`
- `vm_name`, `remote_home`, `resolve_multipass_ipv4`, `connection_host`
- `ensure_running`, `teardown`, `inspect`
- `exec_argv`, `remote_exec`, `transfer_to`, `transfer_from`

Metodi da MANTENERE (nanofaas-specifici):
- `__init__` (modificato per chiamare `super().__init__`)
- `_remote_project_dir`, `_kubeconfig_path`
- `remote_project_dir`, `kubeconfig_path`
- `remote_path_for_local`
- `sync_project`, `install_dependencies`, `install_k3s`
- `setup_registry`, `ensure_registry_container`, `configure_k3s_registry`

Funzioni libere da RIMUOVERE (spostate in multipass.py):
- `_find_ssh_private_key_path`, `_vm_name`, `_ok`, `_sdk_error`
- `resolve_connection_host`, `repo_sync_ssh_rsh`, `repo_rsync_command`

Funzioni libere da MANTENERE:
- `REPO_SYNC_EXCLUDE_PATTERNS` — usata da `sync_project`

- [ ] **Step 2: Riscrivi vm_adapter.py**

```python
# tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks.vm.multipass import (
    MultipassVmProvider,
    _ok,
    repo_rsync_command,
    repo_sync_ssh_rsh,
    REPO_SYNC_EXCLUDE_PATTERNS,
)
from workflow_tasks.vm.models import VmRequest

from controlplane_tool.workspace.paths import ToolPaths

if TYPE_CHECKING:
    from shellcraft.backend import ShellBackend
    from multipass import MultipassClient
    from controlplane_tool.infra.vm.ansible_adapter import AnsibleAdapter


class VmOrchestrator(MultipassVmProvider):
    """Extends MultipassVmProvider with nanofaas-specific operations."""

    def __init__(
        self,
        repo_root: Path,
        shell: "ShellBackend | None" = None,
        ansible: "AnsibleAdapter | None" = None,
        multipass_client: "MultipassClient | None" = None,
    ) -> None:
        self.repo_root = Path(repo_root)
        self.paths = ToolPaths.repo_root(self.repo_root)
        super().__init__(
            workspace_root=self.repo_root,
            shell=shell,
            multipass_client=multipass_client,
        )
        if ansible is None:
            from controlplane_tool.infra.vm.ansible_adapter import AnsibleAdapter

            ansible = AnsibleAdapter(
                self.repo_root,
                shell=self.shell,
                host_resolver=self.connection_host,
                private_key_path=self._private_key_path,
            )
        self.ansible = ansible

    def _remote_project_dir(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/nanofaas"

    def _kubeconfig_path(self, request: VmRequest) -> str:
        return f"{self._remote_home(request)}/.kube/config"

    def remote_project_dir(self, request: VmRequest) -> str:
        return self._remote_project_dir(request)

    def kubeconfig_path(self, request: VmRequest) -> str:
        return self._kubeconfig_path(request)

    def remote_path_for_local(
        self,
        request: VmRequest,
        local_path: Path,
        *,
        local_root: Path | None = None,
        fallback_subdir: str | None = None,
    ) -> str:
        path = Path(local_path).resolve()
        root = Path(local_root or self.paths.workspace_root).resolve()
        remote_dir = self._remote_project_dir(request)
        try:
            relative = path.relative_to(root)
            return f"{remote_dir}/{relative.as_posix()}"
        except ValueError:
            if fallback_subdir:
                return f"{remote_dir}/{fallback_subdir.strip('/')}/{path.name}"
            return f"{remote_dir}/{path.name}"

    def sync_project(
        self,
        request: VmRequest,
        *,
        source_dir: Path | None = None,
        remote_dir: str | None = None,
        dry_run: bool = False,
    ) -> object:
        source = Path(source_dir or self.paths.workspace_root)
        destination = remote_dir or self._remote_project_dir(request)
        if request.lifecycle == "external":
            return self._shell_run(
                repo_rsync_command(
                    source=source, user=request.user,
                    host=str(request.host), destination=destination,
                ),
                dry_run=dry_run,
            )
        host = self.connection_host(request, dry_run=dry_run)
        return self._shell_run(
            repo_rsync_command(
                source=source, user=request.user, host=host, destination=destination,
                ssh_rsh=repo_sync_ssh_rsh(self._private_key_path),
            ),
            dry_run=dry_run,
        )

    def install_dependencies(
        self, request: VmRequest, *, install_helm: bool = False,
        helm_version: str = "3.16.4", dry_run: bool = False,
    ) -> object:
        return self.ansible.provision_base(
            request, install_helm=install_helm, helm_version=helm_version, dry_run=dry_run,
        )

    def install_k3s(
        self, request: VmRequest, *, kubeconfig_path: str | None = None,
        k3s_version: str | None = None, dry_run: bool = False,
    ) -> object:
        return self.ansible.provision_k3s(
            request,
            kubeconfig_path=kubeconfig_path or self._kubeconfig_path(request),
            k3s_version=k3s_version, dry_run=dry_run,
        )

    def setup_registry(
        self, request: VmRequest, *, registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry", dry_run: bool = False,
    ) -> object:
        result = self.ensure_registry_container(
            request, registry=registry, container_name=container_name, dry_run=dry_run,
        )
        if result.return_code != 0:
            return result
        return self.configure_k3s_registry(request, registry=registry, dry_run=dry_run)

    def ensure_registry_container(
        self, request: VmRequest, *, registry: str = "localhost:5000",
        container_name: str = "nanofaas-e2e-registry", dry_run: bool = False,
    ) -> object:
        return self.ansible.ensure_registry_container(
            request, registry=registry, container_name=container_name, dry_run=dry_run,
        )

    def configure_k3s_registry(
        self, request: VmRequest, *, registry: str = "localhost:5000", dry_run: bool = False,
    ) -> object:
        return self.ansible.configure_k3s_registry(request, registry=registry, dry_run=dry_run)
```

**Nota:** `REPO_SYNC_EXCLUDE_PATTERNS` viene importata da workflow_tasks se necessario per `sync_project` — ma dato che non è usata direttamente nella nuova versione semplificata (è dentro `repo_rsync_command`), non serve importarla esplicitamente qui. Verifica che `repo_rsync_command` in multipass.py includa già la lista di esclusioni.

- [ ] **Step 3: Verifica che i test di controlplane passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: tutti i test passano. Se fallisce qualcosa, leggi i dettagli e correggi (probabilmente un import mancante).

- [ ] **Step 4: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py && \
git commit -m "$(cat <<'EOF'
refactor(controlplane): VmOrchestrator extends MultipassVmProvider

Generic multipass operations are now inherited. Only nanofaas-specific
operations remain: sync_project, install_k3s, setup_registry, etc.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Crea AzureVmProvider e refactora AzureVmOrchestrator

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/azure.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py`

`AzureVmOrchestrator` non ha metodi nanofaas-specifici (a differenza di VmOrchestrator), quindi diventa una thin subclass di `AzureVmProvider` o semplicemente un alias.

- [ ] **Step 1: Crea workflow_tasks/vm/azure.py**

Copia la logica da `controlplane_tool/infra/vm/azure_vm_adapter.py` rimuovendo l'import di ToolPaths (che lì è dead code — `self.paths` non viene mai usato).

```python
# tools/workflow-tasks/src/workflow_tasks/vm/azure.py
from __future__ import annotations

import subprocess
from pathlib import Path

from azure_vm import AzureClient
from azure_vm.exceptions import VmNotFoundError
from shellcraft.backend import ShellExecutionResult

from workflow_tasks.vm.models import VmRequest


def _find_ssh_private_key() -> Path | None:
    ssh_dir = Path.home() / ".ssh"
    for name in ("id_ed25519", "id_rsa", "id_ecdsa", "id_dsa"):
        priv = ssh_dir / name
        if priv.exists():
            return priv
    return None


def _ok(command: list[str]) -> ShellExecutionResult:
    return ShellExecutionResult(command=command, return_code=0, stdout="")


class AzureVmProvider:
    """Generic Azure VM provider: lifecycle, command execution, file transfer."""

    def __init__(self, repo_root: Path) -> None:
        self.repo_root = Path(repo_root)

    def _client(self, request: VmRequest) -> AzureClient:
        return AzureClient(
            resource_group=request.azure_resource_group,
            location=request.azure_location,
            ssh_key_path=request.azure_ssh_key_path,
            ssh_username=request.user,
        )

    def _vm_name(self, request: VmRequest) -> str:
        return request.name or "nanofaas-azure"

    def _ssh_key(self, request: VmRequest) -> Path | None:
        if request.azure_ssh_key_path:
            return Path(request.azure_ssh_key_path)
        return _find_ssh_private_key()

    def remote_home(self, request: VmRequest) -> str:
        if request.home:
            return request.home
        if request.user == "root":
            return "/root"
        return f"/home/{request.user}"

    def remote_project_dir(self, request: VmRequest) -> str:
        return f"{self.remote_home(request)}/nanofaas"

    def connection_host(self, request: VmRequest) -> str:
        vm = self._client(request).get_vm(self._vm_name(request))
        return vm.wait_for_ip()

    def teardown(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        try:
            self._client(request).get_vm(name).delete()
        except VmNotFoundError:
            pass
        return _ok(["azure", "delete", name])

    def ensure_running(self, request: VmRequest) -> ShellExecutionResult:
        name = self._vm_name(request)
        self._client(request).ensure_running(
            name,
            vm_size=request.azure_vm_size,
            image_urn=request.azure_image_urn,
            ssh_key_path=request.azure_ssh_key_path,
        )
        return _ok(["azure", "ensure_running", name])

    def exec_argv(
        self,
        request: VmRequest,
        argv: tuple[str, ...] | list[str],
        *,
        env: dict[str, str] | None = None,
        cwd: str | None = None,
        dry_run: bool = False,
    ) -> ShellExecutionResult:
        del dry_run  # Azure SDK has no dry-run mode; accepted for protocol compatibility
        vm = self._client(request).get_vm(self._vm_name(request))
        result = vm.exec_structured(list(argv), env=env, cwd=cwd)
        return ShellExecutionResult(
            command=list(argv),
            return_code=result.returncode,
            stdout=result.stdout,
            stderr=result.stderr,
        )

    def transfer_to(
        self, request: VmRequest, *, source: Path, destination: str,
    ) -> ShellExecutionResult:
        vm = self._client(request).get_vm(self._vm_name(request))
        vm.transfer(str(source), destination)
        return _ok(["scp", str(source), destination])

    def transfer_from(
        self, request: VmRequest, *, source: str, destination: Path,
    ) -> ShellExecutionResult:
        ip = self._client(request).get_vm(self._vm_name(request)).wait_for_ip()
        key = self._ssh_key(request)
        cmd: list[str] = ["scp"]
        if key:
            cmd.extend(["-i", str(key)])
        cmd.extend([
            "-o", "StrictHostKeyChecking=no",
            "-o", "UserKnownHostsFile=/dev/null",
            f"{request.user}@{ip}:{source}",
            str(destination),
        ])
        proc = subprocess.run(cmd, capture_output=True, text=True)
        return ShellExecutionResult(
            command=cmd,
            return_code=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )
```

- [ ] **Step 2: Refactora azure_vm_adapter.py come thin subclass**

Leggi `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py`. Sostituisci interamente con:

```python
# Re-exports AzureVmProvider and exposes AzureVmOrchestrator for backward compatibility.
from __future__ import annotations

from workflow_tasks.vm.azure import AzureVmProvider

# AzureVmOrchestrator is an alias for AzureVmProvider — no nanofaas-specific extensions.
AzureVmOrchestrator = AzureVmProvider

__all__ = ["AzureVmOrchestrator", "AzureVmProvider"]
```

- [ ] **Step 3: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: tutti i test passano

- [ ] **Step 4: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/azure.py \
    tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add AzureVmProvider; AzureVmOrchestrator becomes an alias

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Sposta OrchestratorVmRunner e VmFileFetcher in workflow_tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/runners.py`
- Create: `tools/workflow-tasks/tests/vm/test_vm_runners.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py`

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/vm/test_vm_runners.py
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher


@dataclass
class _FakeResult:
    return_code: int = 0
    stderr: str = ""
    stdout: str = ""


def test_orchestrator_vm_runner_calls_exec_argv() -> None:
    orch = MagicMock()
    orch.exec_argv.return_value = _FakeResult(return_code=0)
    request = MagicMock()

    runner = OrchestratorVmRunner(orch, request)
    runner.run_vm_command(("echo", "hi"), env={"A": "B"}, remote_dir="/home", dry_run=False)

    orch.exec_argv.assert_called_once_with(
        request, ("echo", "hi"), env={"A": "B"}, cwd="/home", dry_run=False
    )


def test_orchestrator_vm_runner_passes_empty_env_as_none() -> None:
    orch = MagicMock()
    orch.exec_argv.return_value = _FakeResult()
    runner = OrchestratorVmRunner(orch, MagicMock())
    runner.run_vm_command(("ls",), env={}, remote_dir=None, dry_run=True)
    _, kwargs = orch.exec_argv.call_args
    assert kwargs["env"] is None


def test_vm_file_fetcher_calls_transfer_from(tmp_path: Path) -> None:
    orch = MagicMock()
    orch.transfer_from.return_value = _FakeResult(return_code=0)
    request = MagicMock()

    fetcher = VmFileFetcher(vm=orch, request=request)
    fetcher.fetch_from("/remote/results", tmp_path)

    orch.transfer_from.assert_called_once_with(
        request, source="/remote/results", destination=tmp_path
    )


def test_vm_file_fetcher_raises_on_nonzero() -> None:
    orch = MagicMock()
    orch.transfer_from.return_value = _FakeResult(return_code=1, stderr="permission denied")

    fetcher = VmFileFetcher(vm=orch, request=MagicMock())
    with pytest.raises(RuntimeError, match="permission denied"):
        fetcher.fetch_from("/remote/results", Path("/local"))
```

- [ ] **Step 2: Verifica che fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_runners.py -v 2>&1 | tail -8
```
Expected: `ModuleNotFoundError`

- [ ] **Step 3: Crea workflow_tasks/vm/runners.py**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/runners.py
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    pass


class OrchestratorVmRunner:
    """Adapts any VM orchestrator's exec_argv to the VmCommandRunner protocol."""

    def __init__(self, orchestrator: object, request: object) -> None:
        self._orch = orchestrator
        self._request = request

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> object:
        return self._orch.exec_argv(  # type: ignore[attr-defined]
            self._request, argv, env=env or None, cwd=remote_dir, dry_run=dry_run
        )


class VmFileFetcher:
    """Implements RemoteFileFetcher using any orchestrator's transfer_from()."""

    def __init__(self, vm: object, request: object) -> None:
        self._vm = vm
        self._request = request

    def fetch_from(self, remote: str, local: Path) -> None:
        result = self._vm.transfer_from(self._request, source=remote, destination=local)  # type: ignore[attr-defined]
        return_code = getattr(result, "return_code", 0)
        if return_code != 0:
            stderr = getattr(result, "stderr", "") or ""
            stdout = getattr(result, "stdout", "") or ""
            raise RuntimeError(stderr or stdout or f"transfer failed (exit {return_code})")
```

- [ ] **Step 4: Aggiorna loadtest_adapters.py come re-export shim**

Leggi `tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py`. Sostituisci le classi `OrchestratorVmRunner` e `VmFileFetcher` con re-export dal nuovo modulo, mantenendo `HttpPrometheusClient` (lo sposteremo nel Task 9):

```python
# tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py
from __future__ import annotations

from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher

# HttpPrometheusClient stays here temporarily until Task 9
from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks.loadtest.models import TimeWindow
from controlplane_tool.loadtest.metrics import query_prometheus_range_series

if TYPE_CHECKING:
    pass


class HttpPrometheusClient:
    """Implements PrometheusClient using the Prometheus HTTP API."""

    def __init__(self, url: str) -> None:
        self._url = url

    def query_range(
        self, expr: str, window: TimeWindow, step_seconds: int = 5,
    ) -> list[dict[str, float | str]]:
        return query_prometheus_range_series(
            self._url, expr, window.start, window.end, step_seconds
        )


__all__ = ["OrchestratorVmRunner", "VmFileFetcher", "HttpPrometheusClient"]
```

- [ ] **Step 5: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_runners.py -v 2>&1 | tail -10
```
Expected: 4 passed

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: tutti i test passano

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/runners.py \
    tools/workflow-tasks/tests/vm/test_vm_runners.py \
    tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): move OrchestratorVmRunner and VmFileFetcher to vm/runners.py

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Sposta VmLifecycleAdapter e factory functions in workflow_tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/adapters.py`
- Create: `tools/workflow-tasks/tests/vm/test_vm_adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/vm/test_vm_adapters.py
from __future__ import annotations

from unittest.mock import MagicMock

from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter
from workflow_tasks.vm.models import VmConfig, VmInfo


def _make_orchestrator(host: str = "10.0.0.1") -> MagicMock:
    orch = MagicMock()
    orch.connection_host.return_value = host
    return orch


def test_vm_lifecycle_adapter_ensure_running_returns_vm_info() -> None:
    orch = _make_orchestrator("192.168.1.1")
    adapter = VmLifecycleAdapter(orch, lifecycle="multipass")
    config = VmConfig(name="my-vm", cpus=2, memory="4G", disk="20G")

    info = adapter.ensure_running(config)

    orch.ensure_running.assert_called_once()
    assert info.name == "my-vm"
    assert info.host == "192.168.1.1"
    assert info.user == "ubuntu"


def test_vm_lifecycle_adapter_destroy_calls_teardown() -> None:
    orch = _make_orchestrator()
    adapter = VmLifecycleAdapter(orch, lifecycle="multipass")
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    orch.teardown.assert_called_once()


def test_multipass_vm_adapter_factory() -> None:
    orch = _make_orchestrator("10.0.0.2")
    adapter = MultipassVmAdapter(orch)
    config = VmConfig(name="vm1")

    info = adapter.ensure_running(config)
    assert info.name == "vm1"


def test_azure_vm_adapter_factory() -> None:
    orch = _make_orchestrator("4.5.6.7")
    adapter = AzureVmAdapter(orch)
    config = VmConfig(name="azure-vm")

    info = adapter.ensure_running(config)
    assert info.host == "4.5.6.7"
```

- [ ] **Step 2: Verifica che fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_adapters.py -v 2>&1 | tail -8
```
Expected: `ModuleNotFoundError`

- [ ] **Step 3: Crea workflow_tasks/vm/adapters.py**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/adapters.py
from __future__ import annotations

from workflow_tasks.vm.models import VmConfig, VmInfo, VmRequest


class VmLifecycleAdapter:
    """Implements VmLifecycle Protocol for any VM orchestrator."""

    def __init__(self, orchestrator: object, *, lifecycle: str) -> None:
        self._vm = orchestrator
        self._lifecycle = lifecycle

    def ensure_running(self, config: VmConfig) -> VmInfo:
        request = VmRequest(
            lifecycle=self._lifecycle,  # type: ignore[arg-type]
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)  # type: ignore[attr-defined]
        host = self._vm.connection_host(request)  # type: ignore[attr-defined]
        return VmInfo(name=config.name, host=host, user="ubuntu", home="/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        request = VmRequest(lifecycle=self._lifecycle, name=info.name)  # type: ignore[arg-type]
        self._vm.teardown(request)  # type: ignore[attr-defined]


def MultipassVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="multipass")


def AzureVmAdapter(orchestrator: object) -> VmLifecycleAdapter:
    return VmLifecycleAdapter(orchestrator, lifecycle="azure")
```

- [ ] **Step 4: Aggiorna vm_lifecycle_adapters.py come re-export shim**

```python
# tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter

__all__ = ["VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter"]
```

- [ ] **Step 5: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/test_vm_adapters.py -v 2>&1 | tail -10
```
Expected: 4 passed

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_vm_lifecycle_adapters.py -v 2>&1 | tail -8
```
Expected: 4 passed (gli stessi test passano tramite re-export)

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/adapters.py \
    tools/workflow-tasks/tests/vm/test_vm_adapters.py \
    tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): move VmLifecycleAdapter, MultipassVmAdapter, AzureVmAdapter to vm/adapters.py

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Sposta HttpPrometheusClient in workflow_tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/adapters.py`
- Create: `tools/workflow-tasks/tests/loadtest/test_loadtest_adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py`

- [ ] **Step 1: Scrivi test failing**

```python
# tools/workflow-tasks/tests/loadtest/test_loadtest_adapters.py
from __future__ import annotations

from datetime import datetime, timezone
from unittest.mock import patch

from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.loadtest.models import TimeWindow


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


def test_http_prometheus_client_calls_query_range_series() -> None:
    fake_points = [{"timestamp": 1.0, "value": 42.0}]
    window = _make_window()

    with patch(
        "workflow_tasks.loadtest.adapters.query_prometheus_range_series",
        return_value=fake_points,
    ) as mock_fn:
        client = HttpPrometheusClient(url="http://prometheus:9090")
        result = client.query_range("http_requests_total", window)

    assert result == fake_points
    mock_fn.assert_called_once_with(
        "http://prometheus:9090",
        "http_requests_total",
        window.start,
        window.end,
        5,
    )
```

- [ ] **Step 2: Verifica che fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_adapters.py -v 2>&1 | tail -8
```
Expected: `ModuleNotFoundError`

- [ ] **Step 3: Crea workflow_tasks/loadtest/adapters.py**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/adapters.py
from __future__ import annotations

from workflow_tasks.loadtest.models import TimeWindow
from workflow_tasks.loadtest.prometheus import query_prometheus_range_series


class HttpPrometheusClient:
    """Implements PrometheusClient using the Prometheus HTTP API."""

    def __init__(self, url: str) -> None:
        self._url = url

    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]:
        return query_prometheus_range_series(
            self._url, expr, window.start, window.end, step_seconds
        )
```

- [ ] **Step 4: Aggiorna loadtest_adapters.py in controlplane_tool come re-export completo**

Leggi `tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py` (attuale: ha ancora HttpPrometheusClient inline). Sostituisci con:

```python
# Re-exports from workflow_tasks for backward compatibility.
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher

__all__ = ["HttpPrometheusClient", "OrchestratorVmRunner", "VmFileFetcher"]
```

- [ ] **Step 5: Verifica test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_adapters.py -v 2>&1 | tail -8
```
Expected: 1 passed

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_loadtest_adapters.py -v 2>&1 | tail -8
```
Expected: 3 passed (tramite re-export)

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/adapters.py \
    tools/workflow-tasks/tests/loadtest/test_loadtest_adapters.py \
    tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): move HttpPrometheusClient to loadtest/adapters.py

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: Aggiorna public API, exports e boundary tests

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`
- Modify: `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Aggiorna vm/__init__.py**

Leggi `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py`. Aggiungi i nuovi tipi:

```python
from workflow_tasks.vm.models import VmConfig, VmInfo, VmLifecycle, VmRequest, vm_request_from_env
from workflow_tasks.vm.ports import VmLifecycle as _VmLifecycleProtocol  # existing
from workflow_tasks.vm.tasks import DestroyVm, EnsureVmRunning
from workflow_tasks.vm.multipass import MultipassVmProvider
from workflow_tasks.vm.azure import AzureVmProvider
from workflow_tasks.vm.runners import OrchestratorVmRunner, VmFileFetcher
from workflow_tasks.vm.adapters import AzureVmAdapter, MultipassVmAdapter, VmLifecycleAdapter

__all__ = [
    "VmConfig", "VmInfo", "VmLifecycle", "VmRequest", "vm_request_from_env",
    "EnsureVmRunning", "DestroyVm",
    "MultipassVmProvider", "AzureVmProvider",
    "OrchestratorVmRunner", "VmFileFetcher",
    "VmLifecycleAdapter", "MultipassVmAdapter", "AzureVmAdapter",
]
```

**Nota:** `VmLifecycle` è ora sia un `Literal` (in models.py) sia un `Protocol` (in ports.py). Il `Literal` ha la precedenza nell'export pubblico — il `Protocol` resta importabile direttamente da `workflow_tasks.vm.ports` per chi ne ha bisogno.

- [ ] **Step 2: Aggiorna loadtest/__init__.py**

Leggi il file. Aggiungi:

```python
from workflow_tasks.loadtest.prometheus import query_prometheus_range_series
from workflow_tasks.loadtest.adapters import HttpPrometheusClient
```

e aggiorna `__all__` includendo questi nuovi nomi.

- [ ] **Step 3: Aggiorna workflow_tasks/__init__.py**

Leggi il file. Aggiungi import per:
- `VmRequest`, `vm_request_from_env`, `VmLifecycle` (Literal)
- `MultipassVmProvider`, `AzureVmProvider`
- `OrchestratorVmRunner`, `VmFileFetcher`
- `VmLifecycleAdapter`, `MultipassVmAdapter`, `AzureVmAdapter`
- `HttpPrometheusClient`
- `query_prometheus_range_series`

Aggiorna `__all__` di conseguenza.

- [ ] **Step 4: Aggiorna boundary test**

Leggi `tests/test_package_boundaries.py`. Il test `test_workflow_tasks_does_not_import_controlplane_tool` deve ancora passare. Aggiungi test per verificare che i nuovi submoduli non importino controlplane_tool:

```python
def test_vm_multipass_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.multipass")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_vm_azure_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm.azure")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 5: Aggiungi public API tests**

Leggi `tests/test_public_api.py`. Aggiungi:

```python
def test_public_api_exports_vm_infrastructure() -> None:
    assert hasattr(workflow_tasks, "VmRequest")
    assert hasattr(workflow_tasks, "MultipassVmProvider")
    assert hasattr(workflow_tasks, "AzureVmProvider")
    assert hasattr(workflow_tasks, "OrchestratorVmRunner")
    assert hasattr(workflow_tasks, "VmFileFetcher")
    assert hasattr(workflow_tasks, "VmLifecycleAdapter")
    assert hasattr(workflow_tasks, "MultipassVmAdapter")
    assert hasattr(workflow_tasks, "AzureVmAdapter")
    assert hasattr(workflow_tasks, "HttpPrometheusClient")
```

- [ ] **Step 6: Esegui la suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: tutti i test passano, coverage ≥ 90%

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: tutti i test passano

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/__init__.py \
    tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py \
    tools/workflow-tasks/src/workflow_tasks/__init__.py \
    tools/workflow-tasks/tests/test_package_boundaries.py \
    tools/workflow-tasks/tests/test_public_api.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): export all VM infrastructure from public API

VmRequest, MultipassVmProvider, AzureVmProvider, OrchestratorVmRunner,
VmFileFetcher, VmLifecycleAdapter, MultipassVmAdapter, AzureVmAdapter,
HttpPrometheusClient now accessible from top-level workflow_tasks namespace.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
# workflow-tasks: tutti i test, coverage ≥ 90%
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest -q 2>&1 | tail -5

# controlplane: nessuna regressione
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q 2>&1 | tail -5

# boundary: workflow_tasks non importa controlplane_tool
uv run pytest tests/test_package_boundaries.py -v 2>&1 | tail -10
```

---

## Note

**`VmLifecycle` ambiguità:** In `workflow_tasks/vm/models.py` `VmLifecycle` è un `Literal["multipass", "external", "azure"]`. In `workflow_tasks/vm/ports.py` `VmLifecycle` è un Protocol per il lifecycle delle VM. Sono due cose diverse con lo stesso nome. Nel Task 10, esporta il `Literal` come `VmLifecycle` dal namespace pubblico e rinomina il Protocol in `VmLifecyclePort` o `VmLifecycleProtocol` per evitare collisioni.

**`transfer_from`/`transfer_to` in MultipassVmProvider:** L'implementazione nel Task 4 usa `self._client.get_vm(name).transfer_from_vm(...)`. Verifica che il multipass-sdk abbia questi metodi — se invece usa `multipass transfer` via shell, adatta il codice copiando l'implementazione esatta da `vm_adapter.py` originale.

**Ruff e `# type: ignore`:** `vm/adapters.py` usa duck typing e ha `# type: ignore[attr-defined]` per evitare errori di tipo statici. Questo è intenzionale — le classi non usano Protocol come tipo dei parametri per evitare import circolari a runtime.
