# V2 Packaging and Release Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the JavaScript SDK publishable and versioned as part of the normal repo release flow, while keeping in-repo demo builds deterministic and wiring JavaScript demo images into the existing release tooling.

**Architecture:** Keep `build.gradle` as the canonical product version source. The JavaScript SDK becomes a real npm package with an explicit publish surface (`dist/` plus package metadata), deterministic local `npm pack` verification, and optional npm publication from the existing release-manager flow. In-repo examples may still use monorepo-local development ergonomics, but `fn-init` must generate externally usable JavaScript projects when invoked outside the monorepo and the image-release scripts must treat JavaScript demos like the existing Java, Go, Python, and Bash demos.

**Tech Stack:** Node.js 20 + npm + TypeScript, Python 3.11 + `uv`, shell scripts, Docker/Buildx, GitHub CLI-based release manager, GitNexus impact analysis.

---

## Scope Guardrails

- Keep the repo-wide version source in `build.gradle`; do not introduce an independently bumped JavaScript SDK version in this plan.
- Treat `scripts/build-push-images.sh` as the canonical non-interactive image release path. Update `scripts/image-builder/image_builder.py` too because it is maintained and tested, but do not let it become the source of truth for versioning.
- Do not convert the repository to npm workspaces.
- Do not publish demo applications to npm. Only `nanofaas-function-sdk` is publishable.
- Do not redesign the runtime API. This plan is about packaging, dependency shape, image automation, and release flow integration.
- Publishing from GitHub Actions is out of scope unless the existing local release-manager flow proves impossible to extend cleanly.

## Commit Safety Rule

Before **every** commit in Tasks 1 through 6:

1. Stage only the files listed in the current task.
2. Run:

```text
gitnexus_detect_changes(scope="staged")
```

3. Confirm the reported files, symbols, and execution flows match only the current task.
4. Commit only after that staged-scope check is clean and expected.

## Verified Current State (checked 2026-04-22)

- `function-sdk-javascript/package.json` is still `private: true` and uses version `0.1.0`, while the repo release version is `0.16.1` in `build.gradle`.
- `env npm_config_cache=/tmp/codex-npm-cache npm test` passes in `function-sdk-javascript/`.
- `env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run` already succeeds, but the tarball currently contains `src/`, `test/`, and `build-test/` in addition to `dist/`, which is too loose for a published SDK artifact.
- `examples/javascript/word-stats/package.json` and `examples/javascript/json-transform/package.json` still depend on `"nanofaas-function-sdk": "file:../../../function-sdk-javascript"`.
- `tools/fn-init` still resolves JavaScript scaffolds to `../../../function-sdk-javascript` outside the repo path model, so generated external projects are not publish-ready.
- `scripts/build-push-images.sh` does not build JavaScript demo images.
- `scripts/image-builder/image_builder.py` does not list JavaScript demo images.
- `scripts/release-manager/release.py:update_files` bumps `build.gradle`, `function-sdk-python`, Helm, and Rust watchdog metadata, but not the JavaScript SDK.
- GitNexus blast radius for the expected edits is low:
  - `resolve_sdk_dependency_path` is `LOW` risk with direct callers limited to `tools/fn-init/src/fn_init/main.py` and `tools/fn-init/tests/test_generator.py`.
  - `update_files` is `LOW` risk with only `scripts/release-manager/release.py:main` depending on it.
  - `build_gradle_command` and `build_docker_command` are both `LOW` risk with only `build_images` and direct tests depending on them.
- Existing release-surface tests already pass:
  - `env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project scripts/image-builder pytest scripts/image-builder/tests/test_image_builder.py -q`
  - `env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest scripts/tests/test_build_push_images_native_args.py scripts/tests/test_release_manager_native_args.py -q`

### Task 0: Preflight and boundary lock

**Files:**
- Inspect: `function-sdk-javascript/package.json`
- Inspect: `function-sdk-javascript/README.md`
- Inspect: `examples/javascript/word-stats/package.json`
- Inspect: `examples/javascript/json-transform/package.json`
- Inspect: `examples/javascript/word-stats/Dockerfile`
- Inspect: `examples/javascript/json-transform/Dockerfile`
- Inspect: `tools/fn-init/src/fn_init/generator.py`
- Inspect: `tools/fn-init/src/fn_init/templates/javascript/package.json.tmpl`
- Inspect: `scripts/build-push-images.sh`
- Inspect: `scripts/image-builder/image_builder.py`
- Inspect: `scripts/release-manager/release.py`

**Step 1: Run GitNexus impact checks before touching shared symbols**

Run:

```text
gitnexus_impact(target="resolve_sdk_dependency_path", direction="upstream")
gitnexus_impact(target="update_files", direction="upstream")
gitnexus_impact(target="build_gradle_command", direction="upstream")
gitnexus_impact(target="build_docker_command", direction="upstream")
```

Expected:
- every impact report is `LOW`
- the direct dependents stay inside `tools/fn-init`, `scripts/release-manager`, `scripts/image-builder`, and their tests

**Step 2: Write down the non-negotiable boundary**

Use this exact note in scratch work before editing:

```text
`build.gradle` stays the version source of truth.
`function-sdk-javascript/` becomes the publishable npm artifact.
Repo examples may keep local development ergonomics, but their Dockerfiles must not rely on committed SDK build output.
`fn-init` must generate semver-based JavaScript dependencies when the output is outside the monorepo.
`scripts/build-push-images.sh` remains canonical for image release; `scripts/image-builder` follows it.
```

**Step 3: No code change in this task**

Move directly to Task 1 once the boundary is explicit.

### Task 1: Tighten the JavaScript SDK pack contract

**Files:**
- Modify: `function-sdk-javascript/package.json`
- Modify: `function-sdk-javascript/package-lock.json`
- Test: `scripts/tests/test_javascript_sdk_packaging.py`

**Step 1: Write the failing packaging tests**

Create `scripts/tests/test_javascript_sdk_packaging.py`:

```python
import json
import os
import re
import subprocess
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PACKAGE_JSON = REPO_ROOT / "function-sdk-javascript" / "package.json"
BUILD_GRADLE = REPO_ROOT / "build.gradle"


def test_javascript_sdk_package_metadata_is_publishable() -> None:
    package = json.loads(PACKAGE_JSON.read_text(encoding="utf-8"))
    repo_version = re.search(
        r"version\\s*=\\s*'([^']+)'",
        BUILD_GRADLE.read_text(encoding="utf-8"),
    ).group(1)

    assert package["name"] == "nanofaas-function-sdk"
    assert package.get("private") is False
    assert package["version"] == repo_version
    assert package["files"] == ["dist", "README.md"]
    assert package["scripts"]["prepack"] == "npm run build"
    assert package["publishConfig"]["access"] == "public"
    assert package["exports"]["."]["types"] == "./dist/index.d.ts"
    assert package["exports"]["."]["default"] == "./dist/index.js"


def test_javascript_sdk_pack_dry_run_is_dist_only(tmp_path: Path) -> None:
    env = os.environ.copy()
    env["npm_config_cache"] = str(tmp_path / "npm-cache")
    result = subprocess.run(
        ["npm", "pack", "--dry-run"],
        cwd=REPO_ROOT / "function-sdk-javascript",
        env=env,
        text=True,
        capture_output=True,
        check=True,
    )
    output = result.stdout

    assert "npm notice  dist/index.js" in output
    assert "npm notice  README.md" in output
    assert "npm notice  src/runtime.ts" not in output
    assert "npm notice  test/runtime.contract.test.ts" not in output
    assert "npm notice  build-test/src/runtime.js" not in output
```

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_sdk_packaging.py -q
```

Expected:
- FAIL because `package.json` is still `private`
- FAIL because the current tarball still includes `src/`, `test/`, and `build-test/`

**Step 3: Implement the minimal package metadata**

Update `function-sdk-javascript/package.json` so the relevant block becomes:

```json
{
  "name": "nanofaas-function-sdk",
  "version": "0.16.1",
  "private": false,
  "type": "module",
  "main": "./dist/index.js",
  "types": "./dist/index.d.ts",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "default": "./dist/index.js"
    }
  },
  "files": [
    "dist",
    "README.md"
  ],
  "scripts": {
    "build": "tsc -p tsconfig.json",
    "build:test": "tsc -p tsconfig.test.json",
    "test": "npm run build:test && node --test build-test/test/**/*.test.js",
    "prepack": "npm run build"
  },
  "publishConfig": {
    "access": "public"
  }
}
```

Then refresh the lockfile:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm install --package-lock-only
```

Run that command in `function-sdk-javascript/`.

**Step 4: Re-run the tests and pack verification**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_sdk_packaging.py -q

env npm_config_cache=/tmp/codex-npm-cache npm test
env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run
```

Expected:
- pytest PASS
- `npm test` PASS
- `npm pack --dry-run` lists `dist/*`, `README.md`, and `package.json`, but not `src/*`, `test/*`, or `build-test/*`

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the JavaScript SDK package files from this task appear in the staged-scope report

```bash
git add \
  function-sdk-javascript/package.json \
  function-sdk-javascript/package-lock.json \
  scripts/tests/test_javascript_sdk_packaging.py
git commit -m "feat(js-sdk): make npm package publishable"
```

### Task 2: Make JavaScript demo Dockerfiles deterministic from source

**Files:**
- Modify: `examples/javascript/word-stats/Dockerfile`
- Modify: `examples/javascript/json-transform/Dockerfile`
- Test: `scripts/tests/test_javascript_example_dockerfiles.py`

**Step 1: Write the failing Dockerfile tests**

Create `scripts/tests/test_javascript_example_dockerfiles.py`:

```python
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def _read(rel_path: str) -> str:
    return (REPO_ROOT / rel_path).read_text(encoding="utf-8")


def test_word_stats_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("examples/javascript/word-stats/Dockerfile")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert "RUN npm install" in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "WORKDIR /src/examples/javascript/word-stats" in dockerfile


def test_json_transform_dockerfile_builds_sdk_before_app_install() -> None:
    dockerfile = _read("examples/javascript/json-transform/Dockerfile")
    assert "WORKDIR /src/function-sdk-javascript" in dockerfile
    assert "RUN npm install" in dockerfile
    assert "RUN npm run build" in dockerfile
    assert "WORKDIR /src/examples/javascript/json-transform" in dockerfile
```

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_example_dockerfiles.py -q
```

Expected:
- FAIL because both Dockerfiles copy the SDK but do not build it before example install

**Step 3: Update both Dockerfiles**

Make `examples/javascript/word-stats/Dockerfile` look like this shape, then apply the same pattern to `json-transform`:

```dockerfile
FROM node:20-alpine AS build
WORKDIR /src

COPY function-sdk-javascript ./function-sdk-javascript
WORKDIR /src/function-sdk-javascript
RUN npm install
RUN npm run build

COPY examples/javascript/word-stats ./examples/javascript/word-stats
WORKDIR /src/examples/javascript/word-stats
RUN npm install
RUN npm run build
RUN npm prune --omit=dev
```

Do not change the runtime stage except for the source paths that still point to the example directory.

**Step 4: Re-run the tests and one real Docker build**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_example_dockerfiles.py -q

docker build -f examples/javascript/word-stats/Dockerfile .
```

Expected:
- pytest PASS
- Docker build PASS from a clean checkout without relying on previously committed SDK output

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the JavaScript example Dockerfiles and their tests from this task appear in the staged-scope report

```bash
git add \
  examples/javascript/word-stats/Dockerfile \
  examples/javascript/json-transform/Dockerfile \
  scripts/tests/test_javascript_example_dockerfiles.py
git commit -m "fix(js-examples): build sdk in docker images"
```

### Task 3: Generate publishable JavaScript scaffolds outside the monorepo

**Files:**
- Modify: `tools/fn-init/src/fn_init/generator.py`
- Modify: `tools/fn-init/src/fn_init/main.py`
- Modify: `tools/fn-init/src/fn_init/templates/javascript/package.json.tmpl`
- Test: `tools/fn-init/tests/test_generator.py`

**Step 1: Write the failing `fn-init` tests**

Replace the current JavaScript dependency assertions in `tools/fn-init/tests/test_generator.py` with these:

```python
def test_resolve_sdk_dependency_spec_inside_monorepo_uses_file_dependency(tmp_path):
    monorepo_root = tmp_path / "repo"
    output_dir = monorepo_root / "examples" / "javascript" / "greet"
    output_dir.mkdir(parents=True)
    assert resolve_sdk_dependency_spec(monorepo_root, output_dir, "0.16.1") == \
        "file:../../../function-sdk-javascript"


def test_resolve_sdk_dependency_spec_outside_monorepo_uses_published_version(tmp_path):
    monorepo_root = tmp_path / "repo"
    output_dir = tmp_path / "generated" / "greet"
    output_dir.mkdir(parents=True)
    assert resolve_sdk_dependency_spec(monorepo_root, output_dir, "0.16.1") == "0.16.1"


def test_generate_javascript_package_keeps_local_sdk_inside_monorepo(tmp_path):
    out = tmp_path / "greet"
    placeholders = dict(JAVASCRIPT_PLACEHOLDERS)
    placeholders["SDK_DEPENDENCY"] = "file:../../../function-sdk-javascript"
    placeholders["SDK_BUILD_HOOKS"] = (
        '    "prebuild": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\\n'
        '    "pretest": "npm --prefix ../../../function-sdk-javascript install && '
        'npm --prefix ../../../function-sdk-javascript run build",\\n'
    )
    generate_function("greet", "javascript", out, vscode=False, placeholders=placeholders)
    content = (out / "package.json").read_text()
    assert '"nanofaas-function-sdk": "file:../../../function-sdk-javascript"' in content
    assert '"prebuild": "npm --prefix ../../../function-sdk-javascript install && npm --prefix ../../../function-sdk-javascript run build"' in content


def test_generate_javascript_package_uses_published_sdk_outside_monorepo(tmp_path):
    out = tmp_path / "greet"
    placeholders = dict(JAVASCRIPT_PLACEHOLDERS)
    placeholders["SDK_DEPENDENCY"] = "0.16.1"
    placeholders["SDK_BUILD_HOOKS"] = ""
    generate_function("greet", "javascript", out, vscode=False, placeholders=placeholders)
    content = (out / "package.json").read_text()
    assert '"nanofaas-function-sdk": "0.16.1"' in content
    assert '"prebuild"' not in content
    assert '"pretest"' not in content
```

**Step 2: Run the targeted tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/fn-init pytest \
  tools/fn-init/tests/test_generator.py -q
```

Expected:
- FAIL because `resolve_sdk_dependency_spec` does not exist yet
- FAIL because the template still hardcodes `file:` dependency and unconditional local build hooks

**Step 3: Implement the minimal scaffold split**

In `tools/fn-init/src/fn_init/generator.py`, replace `resolve_sdk_dependency_path` with:

```python
def resolve_sdk_dependency_spec(monorepo_root: Path | None, output_dir: Path, published_version: str) -> str:
    if monorepo_root is None:
        return published_version
    try:
        output_dir.resolve().relative_to(monorepo_root.resolve())
    except ValueError:
        return published_version
    relative = os.path.relpath(monorepo_root / "function-sdk-javascript", output_dir)
    return f"file:{relative}"


def render_sdk_build_hooks(monorepo_root: Path | None, output_dir: Path) -> str:
    if monorepo_root is None:
        return ""
    try:
        output_dir.resolve().relative_to(monorepo_root.resolve())
    except ValueError:
        return ""
    relative = os.path.relpath(monorepo_root / "function-sdk-javascript", output_dir)
    return (
        f'    "prebuild": "npm --prefix {relative} install && npm --prefix {relative} run build",\\n'
        f'    "pretest": "npm --prefix {relative} install && npm --prefix {relative} run build",\\n'
    )
```

In `tools/fn-init/src/fn_init/main.py`, add placeholders derived from the SDK package metadata:

```python
sdk_package = json.loads((monorepo_root / "function-sdk-javascript" / "package.json").read_text()) \
    if monorepo_root is not None else {"version": "0.16.1"}

placeholders.update({
    "SDK_DEPENDENCY": resolve_sdk_dependency_spec(monorepo_root, output_dir, sdk_package["version"]),
    "SDK_BUILD_HOOKS": render_sdk_build_hooks(monorepo_root, output_dir),
})
```

Update `tools/fn-init/src/fn_init/templates/javascript/package.json.tmpl` to use the new placeholders:

```json
{
  "name": "{{FUNCTION_NAME}}",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "scripts": {
{{SDK_BUILD_HOOKS}}    "build": "tsc -p tsconfig.json",
    "test": "npm run build && node --test dist/test/**/*.test.js",
    "start": "node dist/src/index.js"
  },
  "dependencies": {
    "nanofaas-function-sdk": "{{SDK_DEPENDENCY}}"
  }
}
```

**Step 4: Re-run the targeted tests and one scaffold smoke command**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/fn-init pytest \
  tools/fn-init/tests/test_generator.py -q

./scripts/fn-init.sh greet-js-plan --lang javascript --out /tmp/fn-init-js-plan --yes
```

Expected:
- pytest PASS
- generated external project under `/tmp/fn-init-js-plan/greet-js-plan/package.json` uses a semver SDK dependency, not `file:../../../function-sdk-javascript`

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the `fn-init` files from this task appear in the staged-scope report

```bash
git add \
  tools/fn-init/src/fn_init/generator.py \
  tools/fn-init/src/fn_init/main.py \
  tools/fn-init/src/fn_init/templates/javascript/package.json.tmpl \
  tools/fn-init/tests/test_generator.py
git commit -m "feat(fn-init): generate publishable javascript scaffolds"
```

### Task 4: Add JavaScript demo images to the canonical image-release tools

**Files:**
- Modify: `scripts/build-push-images.sh`
- Modify: `scripts/image-builder/image_builder.py`
- Modify: `scripts/image-builder/tests/test_image_builder.py`
- Test: `scripts/tests/test_build_push_images_javascript_targets.py`

**Step 1: Write the failing script tests**

Create `scripts/tests/test_build_push_images_javascript_targets.py`:

```python
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "build-push-images.sh"


def test_build_push_images_supports_javascript_demo_target_group() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "javascript-demos" in script
    assert "examples/javascript/${example}/Dockerfile" in script
    assert '${BASE}/javascript-${example}:${TAG}${TAG_SUFFIX}' in script
```

Update `scripts/image-builder/tests/test_image_builder.py` first:

```python
expected = {
    "control-plane",
    "function-runtime",
    "watchdog",
    "java-word-stats",
    "java-json-transform",
    "java-lite-word-stats",
    "java-lite-json-transform",
    "go-word-stats",
    "go-json-transform",
    "python-word-stats",
    "python-json-transform",
    "bash-word-stats",
    "bash-json-transform",
    "javascript-word-stats",
    "javascript-json-transform",
}
assert len(ib.IMAGES) == 15
```

Also add one docker-command assertion:

```python
def test_docker_command_for_javascript_example() -> None:
    cmd = ib.build_docker_command(
        ib.IMAGES["javascript-word-stats"],
        "ghcr.io/miciav/nanofaas/javascript-word-stats:v0.10.0-arm64",
        "arm64",
    )
    assert "examples/javascript/word-stats/Dockerfile" in cmd
```

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project scripts/image-builder pytest \
  scripts/image-builder/tests/test_image_builder.py -q

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_build_push_images_native_args.py \
  scripts/tests/test_build_push_images_javascript_targets.py -q
```

Expected:
- FAIL because the image catalog and shell script still exclude JavaScript demos
- the release-manager ARM64 path is still intentionally out of scope for this task and will be extended in Task 5

**Step 3: Implement the JavaScript image targets**

In `scripts/build-push-images.sh`, extend the help text and add a new target block:

```bash
  --only TARGETS     Comma-separated list of targets to build:
                       control-plane, function-runtime, watchdog,
                       java-demos, javascript-demos, go-demos, python-demos, all (default: all)
```

Add:

```bash
if should_build "javascript-demos"; then
    for example in word-stats json-transform; do
        IMG="${BASE}/javascript-${example}:${TAG}${TAG_SUFFIX}"
        info "Building javascript/${example} → $IMG"
        docker build $DOCKER_PLATFORM_FLAG \
            --label "org.opencontainers.image.source=$OCI_SOURCE" \
            -t "$IMG" -f "examples/javascript/${example}/Dockerfile" .
        ok "Built $IMG"
        push_image "$IMG"
    done
fi
```

In `scripts/image-builder/image_builder.py`, add:

```python
"javascript-word-stats": {
    "type": "docker",
    "dockerfile": "examples/javascript/word-stats/Dockerfile",
    "context": ".",
    "group": "JavaScript Functions",
},
"javascript-json-transform": {
    "type": "docker",
    "dockerfile": "examples/javascript/json-transform/Dockerfile",
    "context": ".",
    "group": "JavaScript Functions",
},
```

**Step 4: Re-run the tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project scripts/image-builder pytest \
  scripts/image-builder/tests/test_image_builder.py -q

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_build_push_images_native_args.py \
  scripts/tests/test_build_push_images_javascript_targets.py -q
```

Expected:
- all image-builder tests PASS
- build-push shell-script tests PASS

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the JavaScript image-tooling files from this task appear in the staged-scope report

```bash
git add \
  scripts/build-push-images.sh \
  scripts/image-builder/image_builder.py \
  scripts/image-builder/tests/test_image_builder.py \
  scripts/tests/test_build_push_images_javascript_targets.py
git commit -m "feat(release): add javascript demo images"
```

### Task 5: Extend the release manager for JavaScript versioning, dry-run validation, and ARM64 parity

**Files:**
- Modify: `scripts/release-manager/release.py`
- Modify: `scripts/release-manager/README.md`
- Test: `scripts/tests/test_release_manager_javascript_sdk.py`

**Step 1: Write the failing release-manager test**

Create `scripts/tests/test_release_manager_javascript_sdk.py`:

```python
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts" / "release-manager" / "release.py"


def test_release_manager_updates_and_packages_javascript_sdk() -> None:
    script = SCRIPT.read_text(encoding="utf-8")
    assert "function-sdk-javascript/package.json" in script
    assert "npm install --package-lock-only" in script
    assert "npm pack --dry-run" in script
    assert "npm publish --access public" in script
    assert "examples/javascript/{example}/Dockerfile" in script
    assert "javascript-{example}:{tag}-arm64" in script
```

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_release_manager_native_args.py \
  scripts/tests/test_release_manager_javascript_sdk.py -q
```

Expected:
- FAIL because the release manager does not yet refresh the JavaScript SDK lockfile
- FAIL because the release manager does not yet preview or run the JavaScript pack gate in a reachable `--dry-run` path
- FAIL because `build_and_push_arm64(...)` does not yet build JavaScript demo images

**Step 3: Implement the minimal release-manager support**

In `scripts/release-manager/release.py`, extend `files_to_update` with the JavaScript SDK package metadata only:

```python
("function-sdk-javascript/package.json", r'("version"\\s*:\\s*")[^"]+"', rf'\\g<1>{new_v}"'),
```

Add three helpers:

```python
def refresh_javascript_sdk_lockfile(dry_run: bool = False) -> str:
    cmd = "cd function-sdk-javascript && npm install --package-lock-only"
    if dry_run:
        console.print(f"[dim](Dry-run) Would run: {cmd}[/dim]")
        return "function-sdk-javascript/package-lock.json"
    run_command(cmd)
    return "function-sdk-javascript/package-lock.json"


def pack_javascript_sdk(dry_run: bool = False) -> None:
    cmd = "cd function-sdk-javascript && npm pack --dry-run"
    if dry_run:
        console.print(f"[dim](Dry-run) Would run: {cmd}[/dim]")
        return
    console.print("[blue]Packing JavaScript SDK...[/blue]")
    run_command(cmd)


def publish_javascript_sdk() -> None:
    ok, _, _ = try_command("cd function-sdk-javascript && npm whoami")
    if not ok:
        console.print("[yellow]npm auth unavailable; skipping JavaScript SDK publish.[/yellow]")
        return
    if questionary.confirm("Publish JavaScript SDK to npm?").ask():
        run_command("cd function-sdk-javascript && npm publish --access public")
```

Refactor the main flow so dry-run does **not** return immediately after `update_files(...)`. Instead:

```python
updated_files = update_files(new_v, args.dry_run)

if "function-sdk-javascript/package.json" in updated_files or args.dry_run:
    lockfile_path = refresh_javascript_sdk_lockfile(dry_run=args.dry_run)
    if lockfile_path not in updated_files:
        updated_files.append(lockfile_path)
    pack_javascript_sdk(dry_run=args.dry_run)

if args.dry_run:
    console.print("[dim](Dry-run) Skipping commit/tag/publish/image-build mutations.[/dim]")
    return
```

Then call `publish_javascript_sdk()` after tag push, before the optional ARM64 image-build step.

Also extend `build_and_push_arm64(...)` with a JavaScript loop that mirrors the existing Go/Python/Bash demo loops:

```python
for example in ["word-stats", "json-transform"]:
    img = f"{base_image}/javascript-{example}:{tag}-arm64"
    console.print(f"[blue]Building JavaScript {example} ({img})...[/blue]")
    run_command(
        f"docker build --platform {platform} --label org.opencontainers.image.source={oci_source} "
        f"-t {img} -f examples/javascript/{example}/Dockerfile ."
    )
    run_command(f"docker push {img}")
```

Update `scripts/release-manager/README.md` so the workflow explicitly says:

```markdown
- The release manager now bumps the JavaScript SDK version in `function-sdk-javascript/`.
- It refreshes the JavaScript SDK lockfile with `npm install --package-lock-only`.
- It previews the JavaScript SDK packaging gate during `--dry-run`, and runs `npm pack --dry-run` during the real release path.
- If npm auth is available, it can optionally publish `nanofaas-function-sdk` to npm.
- Its optional ARM64 image-release path now includes the JavaScript demo images too.
- The refreshed JavaScript lockfile is included in the same release bump commit as `function-sdk-javascript/package.json`.
```

**Step 4: Re-run the tests and one release dry-run**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_release_manager_native_args.py \
  scripts/tests/test_release_manager_javascript_sdk.py -q

gh auth status

env npm_config_cache=/tmp/codex-npm-cache uv run --project scripts/release-manager \
  scripts/release-manager/release.py --dry-run
```

Expected:
- pytest PASS
- `gh auth status` PASS, because the current release-manager still runs `check_tools()` before reaching the dry-run branch
- release-manager dry-run reaches the JavaScript validation preview instead of returning immediately after `update_files(...)`
- dry-run output shows both `npm install --package-lock-only` and `npm pack --dry-run` as explicit previewed steps without mutating git state

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the release-manager files and symbols from this task appear in the staged-scope report

```bash
git add \
  scripts/release-manager/release.py \
  scripts/release-manager/README.md \
  scripts/tests/test_release_manager_javascript_sdk.py
git commit -m "feat(release-manager): version and package javascript sdk"
```

### Task 6: Update the docs and run the full packaging verification matrix

**Files:**
- Modify: `function-sdk-javascript/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Test: `scripts/tests/test_javascript_packaging_docs.py`

**Step 1: Write the failing docs test**

Create `scripts/tests/test_javascript_packaging_docs.py`:

```python
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def test_javascript_sdk_readme_mentions_install_and_pack() -> None:
    readme = (REPO_ROOT / "function-sdk-javascript" / "README.md").read_text(encoding="utf-8")
    assert "npm install nanofaas-function-sdk" in readme
    assert "npm pack --dry-run" in readme


def test_root_docs_point_to_packaging_release_flow() -> None:
    root = (REPO_ROOT / "README.md").read_text(encoding="utf-8")
    testing = (REPO_ROOT / "docs" / "testing.md").read_text(encoding="utf-8")
    assert "function-sdk-javascript" in root
    assert "npm pack --dry-run" in testing
    assert "scripts/release-manager/release.py" in root or "scripts/release-manager/release.py" in testing
```

**Step 2: Run the docs test and verify it fails**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_packaging_docs.py -q
```

Expected:
- FAIL because the docs do not yet describe npm installation, pack verification, or JS release flow

**Step 3: Update the docs**

In `function-sdk-javascript/README.md`, add:

````markdown
## Install

```bash
npm install nanofaas-function-sdk
```

## Release verification

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run
```
````

In `README.md`, add one short JavaScript release note under the JavaScript section:

```markdown
The JavaScript SDK is packaged from `function-sdk-javascript/` and validated with `npm pack --dry-run` as part of the release flow.
```

In `docs/testing.md`, add a packaging subsection under JavaScript SDK tests:

````markdown
### JavaScript SDK packaging checks

```bash
cd function-sdk-javascript
env npm_config_cache=/tmp/codex-npm-cache npm test
env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run
```
````

**Step 4: Run the full verification matrix**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm --prefix function-sdk-javascript test
env npm_config_cache=/tmp/codex-npm-cache npm --prefix function-sdk-javascript pack --dry-run

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/fn-init pytest \
  tools/fn-init/tests/test_generator.py -q

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project scripts/image-builder pytest \
  scripts/image-builder/tests/test_image_builder.py -q

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_build_push_images_native_args.py \
  scripts/tests/test_build_push_images_javascript_targets.py \
  scripts/tests/test_release_manager_native_args.py \
  scripts/tests/test_release_manager_javascript_sdk.py \
  scripts/tests/test_javascript_sdk_packaging.py \
  scripts/tests/test_javascript_example_dockerfiles.py \
  scripts/tests/test_javascript_packaging_docs.py -q
```

Then run GitNexus scope check before the final commit:

```text
gitnexus_detect_changes(scope="all")
```

Expected:
- every targeted test suite PASS
- `npm pack --dry-run` stays dist-only
- `gitnexus_detect_changes` shows only the expected JavaScript packaging, `fn-init`, image tooling, release-manager, and doc files

**Step 5: Commit**

Before the commit, run:

```text
gitnexus_detect_changes(scope="staged")
```

Expected:
- only the documentation files from this task appear in the staged-scope report

```bash
git add \
  function-sdk-javascript/README.md \
  README.md \
  docs/testing.md \
  scripts/tests/test_javascript_packaging_docs.py
git commit -m "docs(js-sdk): document packaging and release flow"
```

## Exit Criteria

- `function-sdk-javascript/package.json` is no longer private and is version-aligned with `build.gradle`.
- `npm pack --dry-run` produces a tarball that contains only the intended publish surface.
- JavaScript example Dockerfiles build the SDK from source instead of relying on committed build output.
- `fn-init` generates semver-based JavaScript SDK dependencies when the output lives outside the monorepo.
- `scripts/build-push-images.sh` and `scripts/image-builder/image_builder.py` both expose JavaScript demo images.
- `scripts/release-manager/release.py` bumps the JavaScript SDK version and runs the npm packaging gate.
- Docs describe installation, pack verification, and the release flow clearly enough that a new engineer can execute it without guessing.
