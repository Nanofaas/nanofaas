"""Tests for tui_toolkit.brand."""
from __future__ import annotations

import pytest
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND


def test_default_brand_is_neutral():
    assert DEFAULT_BRAND.name == "App"
    assert DEFAULT_BRAND.wordmark == ""
    assert DEFAULT_BRAND.ascii_logo == ""
    assert DEFAULT_BRAND.default_breadcrumb == "Main"
    assert DEFAULT_BRAND.default_footer_hint == "Esc back | Ctrl+C exit"


def test_brand_is_frozen():
    with pytest.raises(AttributeError):
        DEFAULT_BRAND.name = "other"  # type: ignore[misc]


def test_app_brand_constructor_overrides():
    brand = AppBrand(name="myapp", wordmark="MYAPP", ascii_logo="...logo...")
    assert brand.name == "myapp"
    assert brand.wordmark == "MYAPP"
    assert brand.ascii_logo == "...logo..."
    # untouched fields keep defaults
    assert brand.default_breadcrumb == "Main"
