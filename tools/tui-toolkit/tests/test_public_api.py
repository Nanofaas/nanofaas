"""Verify the curated public API of tui_toolkit is complete and importable."""


def test_public_api_imports():
    import tui_toolkit as tt

    # theming + setup
    assert callable(tt.init_ui)
    assert callable(tt.get_ui)
    assert tt.UIContext is not None
    assert tt.Theme is not None
    assert tt.DEFAULT_THEME is not None
    assert tt.AppBrand is not None
    assert tt.DEFAULT_BRAND is not None
    assert callable(tt.bind_ui)

    # rendering primitives
    assert tt.console is not None
    assert callable(tt.get_content_width)
    assert callable(tt.render_screen_frame)

    # pickers
    assert callable(tt.select)
    assert callable(tt.multiselect)
    assert tt.Choice is not None
    assert tt.Separator is not None

    # startup banner
    assert callable(tt.header)
