from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class VmConfig:
    name: str
    cpus: int = 2
    memory: str = "2G"
    disk: str = "20G"


@dataclass(frozen=True)
class VmInfo:
    name: str
    host: str
    user: str
    home: str
