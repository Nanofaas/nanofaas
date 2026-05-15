from workflow_tasks.workflow.events import WorkflowEvent


def test_fake_sink_collects_emitted_events(fake_sink) -> None:
    event = WorkflowEvent(kind="task.running", flow_id="test.flow", title="hello")
    fake_sink.emit(event)
    assert len(fake_sink.events) == 1
    assert fake_sink.events[0].title == "hello"


def test_fake_sink_status_records_enter_exit(fake_sink) -> None:
    with fake_sink.status("loading"):
        pass
    assert fake_sink.status_events == [("start", "loading"), ("end", "loading")]


def test_fake_sink_starts_empty(fake_sink) -> None:
    assert fake_sink.events == []
    assert fake_sink.status_events == []
