"""Secure file-based credentials for the Azure release workflow."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
import os
from pathlib import Path
import re
import shutil
import stat
from tempfile import TemporaryDirectory


_REMOTE_TEMPLATE = "/tmp/nanofaas-release-credentials.XXXXXX"
_REMOTE_DIRECTORY = re.compile(r"/tmp/nanofaas-release-credentials\.[A-Za-z0-9]+")


@dataclass(frozen=True)
class RemoteDockerCredentials:
    docker_config: str


@dataclass(frozen=True)
class RemoteCosignCredentials:
    key_file: str
    password_file: str | None


class ReleaseCredentialCleanupError(RuntimeError):
    """A cleanup failure with safe information about the interrupted operation."""

    def __init__(self, operation_type: str) -> None:
        self.operation_type = operation_type
        super().__init__(f"release credential cleanup failed after {operation_type}")


def validate_secret_file(path: Path) -> Path:
    if not isinstance(path, Path):
        raise TypeError("release secret must be provided as a file path")
    try:
        metadata = path.lstat()
    except OSError:
        raise ValueError("release secret must be a regular file") from None
    _validate_secret_metadata(metadata)
    return path


def _validate_secret_metadata(metadata: os.stat_result) -> None:
    if not stat.S_ISREG(metadata.st_mode):
        raise ValueError("release secret must be a regular file")
    if metadata.st_uid != os.getuid():
        raise PermissionError("release secret must be owned by the current user")
    if metadata.st_mode & 0o077:
        raise PermissionError("release secret permissions must deny group and world access")
    if not metadata.st_mode & stat.S_IRUSR:
        raise PermissionError("release secret must be owner-readable")
    if metadata.st_size == 0:
        raise ValueError("release secret file must not be empty")


def _copy_secret_file(source: Path, destination: Path) -> None:
    try:
        before = source.lstat()
    except OSError:
        raise ValueError("release secret must be a regular file") from None
    _validate_secret_metadata(before)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        source_fd = os.open(source, flags)
    except OSError:
        raise ValueError("release secret must be a regular file") from None

    with os.fdopen(source_fd, "rb") as source_stream:
        opened = os.fstat(source_stream.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError("release secret changed while being staged")
        _validate_secret_metadata(opened)
        destination_fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(destination_fd, "wb") as destination_stream:
            shutil.copyfileobj(source_stream, destination_stream)
    destination.chmod(0o600)


def _require_success(result: object, action: str) -> object:
    return_code = getattr(result, "return_code", 0)
    if return_code != 0:
        raise RuntimeError(f"{action} failed (exit {return_code})")
    return result


def _run(
    provider: object,
    request: object,
    argv: tuple[str, ...],
    *,
    env: dict[str, str] | None = None,
) -> object:
    try:
        result = provider.exec_argv(  # type: ignore[attr-defined]
            request,
            argv,
            env=env,
            cwd=None,
            dry_run=False,
        )
    except Exception:
        raise RuntimeError("remote credential command failed") from None
    return _require_success(result, "remote credential command")


def _transfer(
    provider: object,
    request: object,
    source: Path,
    destination: str,
) -> None:
    try:
        result = provider.transfer_to(  # type: ignore[attr-defined]
            request,
            source=source,
            destination=destination,
        )
    except Exception:
        raise RuntimeError("release credential transfer failed") from None
    _require_success(result, "release credential transfer")


@contextmanager
def _stage_remote_files(
    provider: object,
    request: object,
    files: Mapping[str, Path],
) -> Iterator[tuple[str, dict[str, str]]]:
    validated = {name: validate_secret_file(path) for name, path in files.items()}
    with TemporaryDirectory(prefix="nanofaas-release-credentials-") as local_name:
        local_dir = Path(local_name)
        staged: dict[str, Path] = {}
        for name, source in validated.items():
            destination = local_dir / name
            _copy_secret_file(source, destination)
            staged[name] = destination

        result = _run(provider, request, ("mktemp", "-d", _REMOTE_TEMPLATE))
        remote_dir = str(getattr(result, "stdout", "")).strip()
        if _REMOTE_DIRECTORY.fullmatch(remote_dir) is None:
            raise RuntimeError("remote credential directory creation returned an invalid path")

        operation_error: BaseException | None = None
        cleanup_failed = False
        try:
            _run(provider, request, ("chmod", "700", remote_dir))
            remote_files: dict[str, str] = {}
            for name, source in staged.items():
                destination = f"{remote_dir}/{name}"
                _transfer(provider, request, source, destination)
                _run(provider, request, ("chmod", "600", destination))
                remote_files[name] = destination
            yield remote_dir, remote_files
        except BaseException as error:
            operation_error = error
            try:
                _run(provider, request, ("rm", "-rf", "--", remote_dir))
            except RuntimeError:
                cleanup_failed = True
        else:
            _run(provider, request, ("rm", "-rf", "--", remote_dir))

        if operation_error is not None:
            operation_type = type(operation_error).__name__
            if cleanup_failed:
                operation_error = None
                raise ReleaseCredentialCleanupError(operation_type) from None
            raise operation_error


@contextmanager
def stage_ghcr_credentials(
    provider: object,
    request: object,
    *,
    username: str,
    token_file: Path,
    registry: str = "ghcr.io",
) -> Iterator[RemoteDockerCredentials]:
    """Authenticate Docker using a staged token and clean all credential state."""
    with _stage_remote_files(provider, request, {"ghcr-token": token_file}) as (
        remote_dir,
        remote_files,
    ):
        docker_config = f"{remote_dir}/docker"
        _run(provider, request, ("mkdir", "-m", "700", docker_config))
        token_path = remote_files["ghcr-token"]
        _run(
            provider,
            request,
            (
                "sh",
                "-c",
                'exec docker login "$1" --username "$2" --password-stdin < "$3"',
                "nanofaas-release-login",
                registry,
                username,
                token_path,
            ),
            env={"DOCKER_CONFIG": docker_config},
        )
        _run(provider, request, ("rm", "-f", "--", token_path))
        yield RemoteDockerCredentials(docker_config=docker_config)


@contextmanager
def stage_cosign_credentials(
    provider: object,
    request: object,
    *,
    key_file: Path,
    password_file: Path | None = None,
) -> Iterator[RemoteCosignCredentials]:
    """Stage cosign files without materializing their contents as strings."""
    files = {"cosign-key": key_file}
    if password_file is not None:
        files["cosign-password"] = password_file
    with _stage_remote_files(provider, request, files) as (_, remote_files):
        yield RemoteCosignCredentials(
            key_file=remote_files["cosign-key"],
            password_file=remote_files.get("cosign-password"),
        )
