"""SHIM — moved to tui_toolkit.chrome and controlplane_tool.ui_setup.

This file will be deleted in PR2. New code should import from tui_toolkit
directly, and brand strings from controlplane_tool.ui_setup.NANOFAAS_BRAND.
"""
from __future__ import annotations

from tui_toolkit import render_screen_frame

from controlplane_tool.ui_setup import NANOFAAS_BRAND, setup_ui

# Ensure the nanofaas brand is active whenever this module is imported.
# Tests that import tui_chrome but don't call main() rely on this side-effect.
setup_ui()

APP_WORDMARK = NANOFAAS_BRAND.wordmark
APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo
APP_BRAND = APP_WORDMARK
DEFAULT_BREADCRUMB = NANOFAAS_BRAND.default_breadcrumb
DEFAULT_FOOTER_HINT = NANOFAAS_BRAND.default_footer_hint

__all__ = [
    "APP_ASCII_LOGO", "APP_WORDMARK", "APP_BRAND",
    "DEFAULT_BREADCRUMB", "DEFAULT_FOOTER_HINT",
    "render_screen_frame",
]
