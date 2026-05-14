from controlplane_tool.scenario.command_resolver import CommandResolver
from controlplane_tool.infra.vm.vm_models import VmRequest


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


def test_resolve_multipass_placeholder_uses_placeholder_vm_name() -> None:
    seen: list[VmRequest] = []
    resolver = CommandResolver(
        host_resolver=lambda vm: seen.append(vm) or f"ip-for-{vm.name}"
    )
    stack_vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e", user="ubuntu")

    resolved = resolver._resolve_command(
        ["ansible-playbook", "-i", "<multipass-ip:nanofaas-e2e-loadgen>,"],
        stack_vm,
        {},
        vm=None,
    )

    assert resolved == ["ansible-playbook", "-i", "ip-for-nanofaas-e2e-loadgen,"]
    assert seen[0].name == "nanofaas-e2e-loadgen"
    assert seen[0].user == "ubuntu"
