from controlplane_tool.scenario.command_resolver import CommandResolver


def test_replace_substitutes_single_placeholder() -> None:
    resolver = CommandResolver(host_resolver=None)
    result = resolver._replace("hello <foo>", {"foo": "world"})
    assert result == "hello world"


def test_replace_leaves_unknown_placeholder_untouched() -> None:
    resolver = CommandResolver(host_resolver=None)
    result = resolver._replace("hello <bar>", {"foo": "world"})
    assert result == "hello <bar>"


def test_resolve_placeholder_text_leaves_non_placeholder_unchanged() -> None:
    resolver = CommandResolver(host_resolver=None)
    assert resolver.resolve_placeholder_text("plain text") == "plain text"


def test_resolve_command_replaces_all_tokens() -> None:
    resolver = CommandResolver(host_resolver=None)
    cmd = ["kubectl", "apply", "-n", "<NAMESPACE>"]
    resolved = resolver.resolve_command(cmd, {"NAMESPACE": "nanofaas-e2e"})
    assert resolved == ["kubectl", "apply", "-n", "nanofaas-e2e"]
