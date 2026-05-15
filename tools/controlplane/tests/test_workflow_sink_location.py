def test_workflow_sink_importable_from_workflow_tasks() -> None:
    # WorkflowSink now lives in workflow_tasks; import must succeed without importing console
    from workflow_tasks.workflow.events import WorkflowSink
    assert hasattr(WorkflowSink, "emit")
