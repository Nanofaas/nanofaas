from controlplane_tool.tui_widgets import (
    _DescribedChoice,
    _back_choice,
    _with_back_described_choice,
)


def test_described_choice_is_frozen_dataclass() -> None:
    choice = _DescribedChoice(title="foo", value="foo", description="bar")
    assert choice.title == "foo"
    assert choice.description == "bar"


def test_with_back_described_choice_adds_back_entry() -> None:
    choices = [_DescribedChoice("A", "a", "desc a")]
    result = _with_back_described_choice(choices)
    assert result[-1].value == "back"


def test_with_back_described_choice_does_not_duplicate_back() -> None:
    back = _DescribedChoice("back", "back", "Return")
    choices = [back]
    result = _with_back_described_choice(choices)
    # Still only one back entry
    assert sum(1 for c in result if c.value == "back") == 1


def test_back_choice_value_is_back() -> None:
    choice = _back_choice()
    assert choice.value == "back"
