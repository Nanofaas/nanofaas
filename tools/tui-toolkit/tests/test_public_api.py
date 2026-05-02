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

    # workflow events + helpers
    assert callable(tt.header)
    assert callable(tt.phase)
    assert callable(tt.step)
    assert callable(tt.success)
    assert callable(tt.warning)
    assert callable(tt.skip)
    assert callable(tt.fail)
    assert callable(tt.workflow_log)
    assert callable(tt.workflow_step)
    assert callable(tt.status)
    assert callable(tt.bind_workflow_sink)
    assert callable(tt.bind_workflow_context)
    assert callable(tt.get_workflow_context)
    assert callable(tt.has_workflow_sink)
    assert callable(tt.build_log_event)
    assert callable(tt.build_phase_event)
    assert callable(tt.build_task_event)
    assert tt.WorkflowEvent is not None
    assert tt.WorkflowContext is not None
    assert tt.WorkflowSink is not None
