"""AppBrand — application identity passed through UIContext."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class AppBrand:
    name: str = "App"
    wordmark: str = ""
    ascii_logo: str = ""
    default_breadcrumb: str = "Main"
    default_footer_hint: str = "Esc back | Ctrl+C exit"


DEFAULT_BRAND = AppBrand()
