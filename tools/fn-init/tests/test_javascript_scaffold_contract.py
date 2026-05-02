from pathlib import Path

from fn_init.generator import build_javascript_scaffold_contract
from fn_init.main import main as scaffold_main


def test_build_javascript_scaffold_contract_inside_monorepo(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "settings.gradle").write_text("", encoding="utf-8")
    output_dir = repo / "examples" / "javascript" / "greet"
    output_dir.mkdir(parents=True)

    contract = build_javascript_scaffold_contract(repo, output_dir, "0.16.1")

    assert contract["SDK_DEPENDENCY"] == "file:../../../function-sdk-javascript"
    assert contract["BUILD_CONTEXT"] == "../../.."
    assert contract["DOCKERFILE_PATH"] == "examples/javascript/greet/Dockerfile"
    assert contract["DOCKER_APP_COPY"] == "COPY examples/javascript/greet /src/examples/javascript/greet"
    assert contract["DOCKER_APP_DIR"] == "/src/examples/javascript/greet"
    assert contract["DOCKER_SDK_COPY"] == "COPY function-sdk-javascript ./function-sdk-javascript"
    assert "WORKDIR /src/function-sdk-javascript" in contract["DOCKER_SDK_BUILD_BLOCK"]
    assert "RUN npm ci" in contract["DOCKER_SDK_BUILD_BLOCK"]
    assert contract["DOCKER_FINAL_SDK_COPY"] == "COPY --from=build /src/function-sdk-javascript /function-sdk-javascript"
    assert "npm --prefix ../../../function-sdk-javascript install" in contract["SDK_BUILD_HOOKS"]


def test_build_javascript_scaffold_contract_outside_monorepo(tmp_path: Path) -> None:
    output_dir = tmp_path / "generated" / "greet"
    output_dir.mkdir(parents=True)

    contract = build_javascript_scaffold_contract(None, output_dir, "0.16.1")

    assert contract["SDK_DEPENDENCY"] == "0.16.1"
    assert contract["SDK_BUILD_HOOKS"] == ""
    assert contract["BUILD_CONTEXT"] == "."
    assert contract["DOCKERFILE_PATH"] == "Dockerfile"
    assert contract["DOCKER_APP_COPY"] == "COPY . /src/app"
    assert contract["DOCKER_APP_DIR"] == "/src/app"
    assert contract["DOCKER_SDK_COPY"] == ""
    assert contract["DOCKER_SDK_BUILD_BLOCK"] == ""
    assert contract["DOCKER_FINAL_SDK_COPY"] == ""


def test_build_javascript_scaffold_contract_in_custom_monorepo_output_uses_dynamic_relative_paths(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "settings.gradle").write_text("", encoding="utf-8")
    output_dir = repo / "custom" / "greet"
    output_dir.mkdir(parents=True)

    contract = build_javascript_scaffold_contract(repo, output_dir, "0.16.1")

    assert contract["SDK_DEPENDENCY"] == "file:../../function-sdk-javascript"
    assert contract["BUILD_CONTEXT"] == "../.."
    assert contract["DOCKERFILE_PATH"] == "custom/greet/Dockerfile"
    assert contract["DOCKER_APP_COPY"] == "COPY custom/greet /src/custom/greet"
    assert contract["DOCKER_APP_DIR"] == "/src/custom/greet"


def test_build_javascript_scaffold_contract_vendors_sdk_for_repo_aware_external_output(
    tmp_path: Path,
) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "settings.gradle").write_text("", encoding="utf-8")
    output_dir = tmp_path / "generated" / "greet"
    output_dir.mkdir(parents=True)

    contract = build_javascript_scaffold_contract(repo, output_dir, "0.16.1")

    assert contract["SDK_DEPENDENCY"] == "file:./function-sdk-javascript"
    assert "npm --prefix ./function-sdk-javascript install" in contract["SDK_BUILD_HOOKS"]
    assert contract["BUILD_CONTEXT"] == "."
    assert contract["DOCKERFILE_PATH"] == "Dockerfile"
    assert contract["DOCKER_APP_COPY"] == "COPY . /src/app"
    assert contract["DOCKER_APP_DIR"] == "/src/app"
    assert contract["DOCKER_SDK_COPY"] == ""
    assert "WORKDIR /src/app/function-sdk-javascript" in contract["DOCKER_SDK_BUILD_BLOCK"]
    assert "RUN npm ci" in contract["DOCKER_SDK_BUILD_BLOCK"]
    assert contract["DOCKER_FINAL_SDK_COPY"] == "COPY --from=build /src/app/function-sdk-javascript /function-sdk-javascript"


def test_scaffold_main_renders_custom_monorepo_build_paths(tmp_path: Path, monkeypatch) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "settings.gradle").write_text("", encoding="utf-8")
    sdk_dir = repo / "function-sdk-javascript"
    sdk_dir.mkdir()
    (sdk_dir / "package.json").write_text('{"version": "0.16.1"}', encoding="utf-8")
    monkeypatch.chdir(repo)

    scaffold_main("greet", lang="javascript", out=repo / "custom", vscode=False, yes=True)

    dockerfile = (repo / "custom" / "greet" / "Dockerfile").read_text(encoding="utf-8")
    function_yaml = (repo / "custom" / "greet" / "function.yaml").read_text(encoding="utf-8")

    assert "COPY function-sdk-javascript ./function-sdk-javascript" in dockerfile
    assert "COPY custom/greet /src/custom/greet" in dockerfile
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert "WORKDIR /src/custom/greet" in dockerfile
    assert "COPY --from=build /src/function-sdk-javascript /function-sdk-javascript" in dockerfile
    assert "context: ../.." in function_yaml
    assert "dockerfile: custom/greet/Dockerfile" in function_yaml


def test_scaffold_main_vendors_sdk_for_repo_aware_external_output(tmp_path: Path, monkeypatch) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    (repo / "settings.gradle").write_text("", encoding="utf-8")
    sdk_dir = repo / "function-sdk-javascript"
    (sdk_dir / "src").mkdir(parents=True)
    (sdk_dir / "package.json").write_text('{"name":"nanofaas-function-sdk","version":"0.16.1"}', encoding="utf-8")
    (sdk_dir / "package-lock.json").write_text('{"name":"nanofaas-function-sdk","lockfileVersion":3}', encoding="utf-8")
    (sdk_dir / "tsconfig.json").write_text("{}", encoding="utf-8")
    (sdk_dir / "src" / "index.ts").write_text("export {};\n", encoding="utf-8")
    (sdk_dir / "README.md").write_text("# sdk\n", encoding="utf-8")
    monkeypatch.chdir(repo)

    scaffold_main("greet", lang="javascript", out=tmp_path / "generated", vscode=False, yes=True)

    output_dir = tmp_path / "generated" / "greet"
    dockerfile = (output_dir / "Dockerfile").read_text(encoding="utf-8")
    function_yaml = (output_dir / "function.yaml").read_text(encoding="utf-8")
    package_json = (output_dir / "package.json").read_text(encoding="utf-8")

    assert (output_dir / "function-sdk-javascript" / "package.json").exists()
    assert (output_dir / "function-sdk-javascript" / "src" / "index.ts").exists()
    assert '"nanofaas-function-sdk": "file:./function-sdk-javascript"' in package_json
    assert "COPY . /src/app" in dockerfile
    assert "COPY function-sdk-javascript ./function-sdk-javascript" not in dockerfile
    assert "WORKDIR /src/app/function-sdk-javascript" in dockerfile
    assert "COPY --from=build /src/app/function-sdk-javascript /function-sdk-javascript" in dockerfile
    assert "context: ." in function_yaml
    assert "dockerfile: Dockerfile" in function_yaml


def test_scaffold_main_renders_external_build_paths(tmp_path: Path, monkeypatch) -> None:
    project_root = tmp_path / "workspace"
    project_root.mkdir()
    monkeypatch.chdir(project_root)

    scaffold_main("greet", lang="javascript", out=project_root / "generated", vscode=False, yes=True)

    dockerfile = (project_root / "generated" / "greet" / "Dockerfile").read_text(encoding="utf-8")
    function_yaml = (project_root / "generated" / "greet" / "function.yaml").read_text(encoding="utf-8")

    assert "COPY . /src/app" in dockerfile
    assert "COPY function-sdk-javascript ./function-sdk-javascript" not in dockerfile
    assert "WORKDIR /src/app/function-sdk-javascript" not in dockerfile
    assert "WORKDIR /src/app" in dockerfile
    assert "context: ." in function_yaml
    assert "dockerfile: Dockerfile" in function_yaml
