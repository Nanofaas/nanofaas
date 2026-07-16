"""Shared host prerequisite diagnostics."""

import shutil


REQUIRED_EXECUTABLES = ("docker", "ssh")


def missing_executables() -> list[str]:
    return [name for name in REQUIRED_EXECUTABLES if shutil.which(name) is None]
