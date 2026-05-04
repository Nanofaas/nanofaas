def test_workflow_sink_importable_from_workflow_models() -> None:
    # After the move this must succeed without importing console
    from controlplane_tool.workflow.workflow_models import WorkflowSink
    assert hasattr(WorkflowSink, "emit")
