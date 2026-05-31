# Shim: re-exports from workflow_tasks.components.platform_commands (migrated in sub-project 2b.3).
from __future__ import annotations

from workflow_tasks.components.platform_commands import (
    platform_install_command,
    platform_status_command,
    platform_uninstall_command,
)

__all__ = [
    "platform_install_command",
    "platform_status_command",
    "platform_uninstall_command",
]
