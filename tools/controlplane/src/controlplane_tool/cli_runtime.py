"""
cli_runtime.py — re-export shim (backwards compatibility).

Implementation split into:
  - cli_vm_runner.py   (CliVmRunner)
  - cli_host_runner.py (CliHostPlatformRunner)
"""
from controlplane_tool.cli_host_runner import CliHostPlatformRunner
from controlplane_tool.cli_vm_runner import CliVmRunner

__all__ = ["CliVmRunner", "CliHostPlatformRunner"]
