"""UIContext — active theme + brand + width, bound via init_ui / bind_ui."""
from __future__ import annotations

import shutil
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from typing import Generator

from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.theme import DEFAULT_THEME, Theme


@dataclass(frozen=True, slots=True)
class UIContext:
    theme: Theme = DEFAULT_THEME
    brand: AppBrand = DEFAULT_BRAND
    max_content_cols: int = 140
    content_width: int | None = None  # populated by init_ui()


_DEFAULT_CTX = UIContext()
_ctx_var: ContextVar[UIContext] = ContextVar("tui_toolkit_ctx", default=_DEFAULT_CTX)
_ctx_shared: UIContext = _DEFAULT_CTX


def init_ui(ctx: UIContext | None = None) -> UIContext:
    """Capture terminal width and install the context as the active singleton.

    Idempotent — the last call wins. Replaces the legacy init_ui_width().
    """
    global _ctx_shared
    base = ctx or UIContext()
    width = min(shutil.get_terminal_size((80, 24)).columns, base.max_content_cols)
    resolved = replace(base, content_width=width)
    _ctx_shared = resolved
    _ctx_var.set(resolved)
    try:
        from tui_toolkit.console import _apply_width
        _apply_width(width)
    except ImportError:
        pass
    return resolved


def get_ui() -> UIContext:
    """Active UI context — returns DEFAULT before init_ui is called."""
    ctx_val = _ctx_var.get()
    if ctx_val is not _DEFAULT_CTX:
        return ctx_val
    return _ctx_shared


@contextmanager
def bind_ui(ctx: UIContext) -> Generator[UIContext, None, None]:
    """Temporary override (useful in tests). Reverts on exit."""
    token = _ctx_var.set(ctx)
    try:
        yield ctx
    finally:
        _ctx_var.reset(token)
