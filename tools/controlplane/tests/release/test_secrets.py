from __future__ import annotations

import importlib
from dataclasses import dataclass
from pathlib import Path
import traceback

import pytest
from workflow_tasks.tasks.models import CommandTaskSpec
from workflow_tasks.tasks.rendering import render_task_command

from controlplane_tool.release.secrets import validate_secret_file


@dataclass
class _Result:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""


class _Provider:
    def __init__(
        self,
        *,
        fail_transfer: bool = False,
        fail_login: bool = False,
        fail_directory_chmod: bool = False,
        fail_cleanup: bool = False,
    ) -> None:
        self.exec_calls: list[tuple[tuple[str, ...], dict[str, str] | None]] = []
        self.transfer_calls: list[tuple[Path, str]] = []
        self.fail_transfer = fail_transfer
        self.fail_login = fail_login
        self.fail_directory_chmod = fail_directory_chmod
        self.fail_cleanup = fail_cleanup

    def exec_argv(
        self,
        request: object,
        argv: tuple[str, ...],
        *,
        env: dict[str, str] | None,
        cwd: str | None,
        dry_run: bool,
    ) -> _Result:
        del request, cwd, dry_run
        self.exec_calls.append((argv, env))
        if argv[:2] == ("mktemp", "-d"):
            return _Result(stdout="/tmp/nanofaas-release-credentials.ABC123\n")
        if self.fail_directory_chmod and argv[:2] == ("chmod", "700"):
            return _Result(return_code=1, stderr="fixture-secret-must-not-leak")
        if self.fail_login and argv[:2] == ("sh", "-c"):
            return _Result(return_code=1, stderr="fixture-ghcr-token-must-not-leak")
        if self.fail_cleanup and argv[:3] == ("rm", "-rf", "--"):
            return _Result(return_code=1, stderr="fixture-secret-must-not-leak")
        return _Result()

    def transfer_to(
        self,
        request: object,
        *,
        source: Path,
        destination: str,
    ) -> _Result:
        del request
        self.transfer_calls.append((source, destination))
        if self.fail_transfer:
            return _Result(return_code=1, stderr="fixture-secret-must-not-leak")
        return _Result()


class _BodyFailure(RuntimeError):
    pass


def _rendered_commands(provider: _Provider) -> str:
    return "\n".join(
        render_task_command(
            CommandTaskSpec(
                task_id=f"release.credentials.{index}",
                summary="Release credential operation",
                argv=argv,
                target="vm",
                env=env or {},
            )
        )
        for index, (argv, env) in enumerate(provider.exec_calls)
    )


def test_validate_secret_file_accepts_private_regular_file(tmp_path: Path) -> None:
    secret = tmp_path / "token"
    secret.write_text("fixture-token", encoding="utf-8")
    secret.chmod(0o600)

    module = importlib.import_module("controlplane_tool.release.secrets")

    assert module.validate_secret_file(secret) == secret


def test_validate_secret_file_rejects_literal_cli_value() -> None:
    literal = "fixture-secret-must-not-leak"

    with pytest.raises(TypeError, match="file path") as error:
        validate_secret_file(literal)  # type: ignore[arg-type]

    assert literal not in str(error.value)


def test_validate_secret_file_rejects_symlink(tmp_path: Path) -> None:
    secret = tmp_path / "token"
    secret.write_text("fixture-token", encoding="utf-8")
    secret.chmod(0o600)
    link = tmp_path / "token-link"
    link.symlink_to(secret)

    with pytest.raises(ValueError, match="regular file"):
        validate_secret_file(link)


def test_validate_secret_file_rejects_group_or_world_permissions(tmp_path: Path) -> None:
    secret = tmp_path / "token"
    secret.write_text("fixture-token", encoding="utf-8")
    secret.chmod(0o640)

    with pytest.raises(PermissionError, match="permissions"):
        validate_secret_file(secret)


def test_validate_secret_file_requires_owner_read_permission(tmp_path: Path) -> None:
    secret = tmp_path / "token"
    secret.write_text("fixture-token", encoding="utf-8")
    secret.chmod(0o200)

    with pytest.raises(PermissionError, match="owner-readable"):
        validate_secret_file(secret)


def test_validate_secret_file_requires_current_user_ownership(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    secret = tmp_path / "token"
    secret.write_text("fixture-token", encoding="utf-8")
    secret.chmod(0o600)
    module = importlib.import_module("controlplane_tool.release.secrets")
    monkeypatch.setattr(module.os, "getuid", lambda: secret.stat().st_uid + 1)

    with pytest.raises(PermissionError, match="owned"):
        validate_secret_file(secret)


def test_staging_rejects_secret_replaced_by_symlink(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    secret = tmp_path / "token"
    replacement = tmp_path / "replacement"
    secret.write_text("fixture-token", encoding="utf-8")
    replacement.write_text("replacement-secret", encoding="utf-8")
    secret.chmod(0o600)
    replacement.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")
    original_validate = module.validate_secret_file

    def replace_after_validation(path: Path) -> Path:
        validated = original_validate(path)
        path.unlink()
        path.symlink_to(replacement)
        return validated

    monkeypatch.setattr(module, "validate_secret_file", replace_after_validation)

    with pytest.raises(ValueError, match="regular file"):
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=secret,
        ):
            pytest.fail("credentials must not be yielded")

    assert provider.exec_calls == []
    assert provider.transfer_calls == []


def test_validate_secret_file_rejects_empty_file(tmp_path: Path) -> None:
    secret = tmp_path / "token"
    secret.touch(mode=0o600)

    with pytest.raises(ValueError, match="empty"):
        validate_secret_file(secret)


def test_stage_ghcr_credentials_logs_in_from_file_and_cleans_metadata(tmp_path: Path) -> None:
    secret_value = "fixture-ghcr-token-must-not-leak"
    token = tmp_path / "ghcr-token"
    token.write_text(secret_value, encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")

    with module.stage_ghcr_credentials(
        provider,
        object(),
        username="release-user",
        token_file=token,
    ) as credentials:
        assert credentials.docker_config == (
            "/tmp/nanofaas-release-credentials.ABC123/docker"
        )
        staged_source = provider.transfer_calls[0][0]
        assert staged_source.exists()
        assert staged_source.stat().st_mode & 0o777 == 0o600

    assert not staged_source.exists()
    assert not staged_source.parent.exists()
    assert provider.transfer_calls[0][1].endswith("/ghcr-token")
    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )
    rendered = repr((provider.exec_calls, provider.transfer_calls, credentials))
    assert secret_value not in rendered
    login_call = next(call for call in provider.exec_calls if call[0][:2] == ("sh", "-c"))
    assert "--password-stdin" in login_call[0][2]
    assert login_call[1] == {
        "DOCKER_CONFIG": "/tmp/nanofaas-release-credentials.ABC123/docker"
    }


def test_stage_ghcr_credentials_cleans_after_transfer_failure(tmp_path: Path) -> None:
    token = tmp_path / "ghcr-token"
    token.write_text("fixture-secret-must-not-leak", encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider(fail_transfer=True)
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(RuntimeError) as error:
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            pytest.fail("credentials must not be yielded")

    staged_source = provider.transfer_calls[0][0]
    assert not staged_source.exists()
    assert not staged_source.parent.exists()
    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )
    assert "fixture-secret-must-not-leak" not in str(error.value)


def test_stage_ghcr_credentials_cleans_after_auth_failure(tmp_path: Path) -> None:
    token = tmp_path / "ghcr-token"
    token.write_text("fixture-ghcr-token-must-not-leak", encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider(fail_login=True)
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(RuntimeError) as error:
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            pytest.fail("credentials must not be yielded")

    staged_source = provider.transfer_calls[0][0]
    assert not staged_source.exists()
    assert not staged_source.parent.exists()
    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )
    assert "fixture-ghcr-token-must-not-leak" not in str(error.value)


def test_stage_ghcr_credentials_cleans_if_remote_hardening_fails(tmp_path: Path) -> None:
    token = tmp_path / "ghcr-token"
    token.write_text("fixture-secret-must-not-leak", encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider(fail_directory_chmod=True)
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(RuntimeError) as error:
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            pytest.fail("credentials must not be yielded")

    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )
    assert "fixture-secret-must-not-leak" not in str(error.value)


def test_stage_ghcr_credentials_reports_cleanup_failure_without_leaking(tmp_path: Path) -> None:
    token = tmp_path / "ghcr-token"
    token.write_text("fixture-secret-must-not-leak", encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider(fail_login=True, fail_cleanup=True)
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(RuntimeError, match="cleanup") as error:
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            pytest.fail("credentials must not be yielded")

    staged_source = provider.transfer_calls[0][0]
    assert not staged_source.exists()
    assert "fixture-secret-must-not-leak" not in str(error.value)


def test_cleanup_failure_preserves_sanitized_context_body_failure(tmp_path: Path) -> None:
    secret_value = "fixture-secret-must-not-leak"
    token = tmp_path / "ghcr-token"
    token.write_text(secret_value, encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider(fail_cleanup=True)
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(module.ReleaseCredentialCleanupError) as error:
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            staged_source = provider.transfer_calls[0][0]
            raise _BodyFailure(f"publication failed with {secret_value}")

    assert error.value.operation_type == "_BodyFailure"
    assert "cleanup failed" in str(error.value)
    assert secret_value not in str(error.value)
    assert error.value.__cause__ is None
    assert error.value.__suppress_context__ is True
    rendered_error = "".join(traceback.format_exception(error.value))
    assert "_BodyFailure" in rendered_error
    assert secret_value not in rendered_error
    assert not staged_source.exists()
    assert not staged_source.parent.exists()


def test_stage_ghcr_credentials_cleans_when_context_body_fails(tmp_path: Path) -> None:
    token = tmp_path / "ghcr-token"
    token.write_text("fixture-ghcr-token-must-not-leak", encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(_BodyFailure, match="publication failed"):
        with module.stage_ghcr_credentials(
            provider,
            object(),
            username="release-user",
            token_file=token,
        ):
            staged_source = provider.transfer_calls[0][0]
            assert staged_source.exists()
            raise _BodyFailure("publication failed")

    assert not staged_source.exists()
    assert not staged_source.parent.exists()
    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )


def test_ghcr_credential_commands_render_without_secret_values(tmp_path: Path) -> None:
    secret_value = "fixture-ghcr-token-must-not-leak"
    token = tmp_path / "ghcr-token"
    token.write_text(secret_value, encoding="utf-8")
    token.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")

    with module.stage_ghcr_credentials(
        provider,
        object(),
        username="release-user",
        token_file=token,
    ):
        rendered_plan = _rendered_commands(provider)

    assert "docker login" in rendered_plan
    assert "--password-stdin" in rendered_plan
    assert secret_value not in rendered_plan


def test_stage_cosign_credentials_exposes_only_remote_paths(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_value = "fixture-cosign-key-must-not-leak"
    password_value = "fixture-cosign-password-must-not-leak"
    key = tmp_path / "cosign.key"
    password = tmp_path / "cosign.password"
    key.write_text(key_value, encoding="utf-8")
    password.write_text(password_value, encoding="utf-8")
    key.chmod(0o600)
    password.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")
    monkeypatch.setattr(Path, "read_text", lambda *args, **kwargs: pytest.fail("read_text"))
    monkeypatch.setattr(Path, "read_bytes", lambda *args, **kwargs: pytest.fail("read_bytes"))

    with module.stage_cosign_credentials(
        provider,
        object(),
        key_file=key,
        password_file=password,
    ) as credentials:
        assert credentials.key_file == (
            "/tmp/nanofaas-release-credentials.ABC123/cosign-key"
        )
        assert credentials.password_file == (
            "/tmp/nanofaas-release-credentials.ABC123/cosign-password"
        )
        staged_sources = [source for source, _ in provider.transfer_calls]
        assert all(source.exists() for source in staged_sources)

    assert all(not source.exists() for source in staged_sources)
    assert all(not source.parent.exists() for source in staged_sources)
    rendered_plan = _rendered_commands(provider)
    assert key_value not in rendered_plan
    assert password_value not in rendered_plan


def test_stage_cosign_credentials_cleans_when_context_body_fails(tmp_path: Path) -> None:
    key = tmp_path / "cosign.key"
    password = tmp_path / "cosign.password"
    key.write_text("fixture-cosign-key-must-not-leak", encoding="utf-8")
    password.write_text("fixture-cosign-password-must-not-leak", encoding="utf-8")
    key.chmod(0o600)
    password.chmod(0o600)
    provider = _Provider()
    module = importlib.import_module("controlplane_tool.release.secrets")

    with pytest.raises(_BodyFailure, match="signing failed"):
        with module.stage_cosign_credentials(
            provider,
            object(),
            key_file=key,
            password_file=password,
        ):
            staged_sources = [source for source, _ in provider.transfer_calls]
            assert all(source.exists() for source in staged_sources)
            raise _BodyFailure("signing failed")

    assert all(not source.exists() for source in staged_sources)
    assert all(not source.parent.exists() for source in staged_sources)
    assert provider.exec_calls[-1][0] == (
        "rm",
        "-rf",
        "--",
        "/tmp/nanofaas-release-credentials.ABC123",
    )
