import os
import sys
import subprocess
import shutil
import re
import argparse
from pathlib import Path
import questionary
from rich.console import Console
from rich.panel import Panel
import semver

console = Console()

# Configuration - adjust if repository owner changes
GH_OWNER = "miciav"
GH_REPO = "nanofaas"
REGISTRY = "ghcr.io"

def get_project_root():
    """Find project root by looking for build.gradle"""
    current = Path(__file__).resolve().parent
    for parent in [current] + list(current.parents):
        if (parent / "build.gradle").exists():
            return parent
    console.print("[red]Error: Could not find project root (build.gradle not found in parents).[/red]")
    sys.exit(1)

ROOT = get_project_root()

def check_tools():
    if not shutil.which("git"):
        console.print("[red]Error: 'git' command not found in PATH.[/red]")
        sys.exit(1)
    if not shutil.which("gh"):
        console.print("[red]Error: 'gh' (GitHub CLI) not found in PATH. Install it first.[/red]")
        sys.exit(1)
    if not shutil.which("docker"):
        console.print("[yellow]Warning: 'docker' command not found. Local builds will be skipped.[/yellow]")
    
    try:
        run_command("gh auth status")
    except SystemExit:
        console.print("[red]Error: You are not authenticated with 'gh'. Run 'gh auth login'.[/red]")
        sys.exit(1)

def run_command(cmd, capture_output=True):
    result = subprocess.run(cmd, shell=True, text=True, capture_output=capture_output, cwd=ROOT)
    if result.returncode != 0:
        if capture_output:
            console.print(f"[red]Command failed: {cmd}[/red]")
            console.print(f"[dim]{result.stderr}[/dim]")
        sys.exit(1)
    return result.stdout.strip()

def get_current_version():
    gradle_file = ROOT / "build.gradle"
    content = gradle_file.read_text()
    match = re.search(r"version\s*=\s*'([^']+)'", content)
    if match:
        return match.group(1)
    console.print("[red]Error: Could not find version in build.gradle[/red]")
    sys.exit(1)

def get_git_status():
    branch = run_command("git rev-parse --abbrev-ref HEAD")
    dirty = run_command("git status --porcelain")
    return branch, dirty

def get_latest_tag():
    try:
        run_command("git rev-list --tags --max-count=1")
        return run_command("git describe --tags --abbrev=0")
    except SystemExit:
        return None

def get_commits_since(tag=None):
    if tag:
        cmd = f"git log {tag}..HEAD --oneline --no-merges"
    else:
        cmd = "git log --oneline --no-merges"
    try:
        output = run_command(cmd)
        return output.split("\n") if output else []
    except SystemExit:
        return []

def generate_release_notes(new_v, commits):
    notes = f"# Release v{new_v}\n\n"
    categories = {"feat": [], "fix": [], "chore": [], "other": []}
    
    for commit in commits:
        if not commit: continue
        match = re.match(r"^[a-f0-9]+\s+(\w+)(?:\(.*\))?:\s+(.*)$", commit)
        if match:
            ctype, msg = match.groups()
            if ctype in categories: categories[ctype].append(msg)
            else: categories["other"].append(f"{ctype}: {msg}")
        else:
            msg = re.sub(r"^[a-f0-9]+\s+", "", commit)
            categories["other"].append(msg)
            
    if categories["feat"]: notes += "## Features\n" + "\n".join(f"- {m}" for m in categories["feat"]) + "\n\n"
    if categories["fix"]: notes += "## Bug Fixes\n" + "\n".join(f"- {m}" for m in categories["fix"]) + "\n\n"
    if categories["other"] or categories["chore"]:
        notes += "## Other Changes\n"
        notes += "\n".join(f"- {m}" for m in categories["chore"]) + "\n"
        notes += "\n".join(f"- {m}" for m in categories["other"]) + "\n"
    return notes.strip()

def build_and_push_arm64(version):
    """Local ARM64 builds for Mac M-series users"""
    console.print("\n[bold]Starting local ARM64 builds...[/bold]")
    
    tag = f"v{version}"
    base_image = f"{REGISTRY}/{GH_OWNER}/{GH_REPO}"
    
    # 1. Control Plane
    cp_image = f"{base_image}/control-plane:{tag}-arm64"
    console.print(f"[blue]Building {cp_image}...[/blue]")
    run_command(f"./gradlew :control-plane:bootBuildImage -PcontrolPlaneImage={cp_image}")
    run_command(f"docker push {cp_image}")
    
    # 2. Java Runtime (referenced in job template)
    jr_image = f"{base_image}/function-runtime:{tag}-arm64"
    console.print(f"[blue]Building {jr_image}...[/blue]")
    # Assuming function-runtime has bootBuildImage too
    try:
        run_command(f"./gradlew :function-runtime:bootBuildImage -PfunctionRuntimeImage={jr_image}")
        run_command(f"docker push {jr_image}")
    except:
        console.print("[yellow]Warning: Could not build function-runtime, skipping.[/yellow]")

    # 3. Python SDK Examples
    for example in ["word-stats", "json-transform"]:
        img = f"{base_image}/python-{example}:{tag}-arm64"
        console.print(f"[blue]Building Python {example} ({img})...[/blue]")
        run_command(f"docker build --platform linux/arm64 -t {img} -f examples/python/{example}/Dockerfile .")
        run_command(f"docker push {img}")

    console.print("[green]✓ Local ARM64 images pushed to GHCR.[/green]")

def update_files(new_v, dry_run=False):
    tag = f"v{new_v}"
    base_image = f"{REGISTRY}/{GH_OWNER}/{GH_REPO}"
    
    files_to_update = [
        ("build.gradle", r"(version\s*=\s*')[^']+'", rf"\g<1>{new_v}'"),
        ("function-sdk-python/pyproject.toml", r'(version\s*=\s*")[^"]+"', rf'\g<1>{new_v}"'),
        ("watchdog/Cargo.toml", r'(^version\s*=\s*")[^"]+"', rf'\g<1>{new_v}"'),
        # K8s Manifests
        ("k8s/control-plane-deployment.yaml", r'image:\s*.*control-plane:.*', f'image: {base_image}/control-plane:{tag}'),
        ("k8s/function-job-template.yaml", r'image:\s*.*function-runtime:.*', f'image: {base_image}/function-runtime:{tag}'),
    ]
    
    updated_files = []
    for rel_path, pattern, replacement in files_to_update:
        path = ROOT / rel_path
        if not path.exists():
            console.print(f"[yellow]Warning: {rel_path} not found, skipping.[/yellow]")
            continue
            
        content = path.read_text()
        new_content = re.sub(pattern, replacement, content, flags=re.MULTILINE)
        
        if content == new_content:
            console.print(f"[yellow]Warning: No changes made to {rel_path}[/yellow]")
            continue
            
        if dry_run:
            console.print(f"[dim](Dry-run) Would update {rel_path}[/dim]")
        else:
            path.write_text(new_content)
            console.print(f"[green]Updated {rel_path}[/green]")
            updated_files.append(rel_path)
    return updated_files

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true", help="Do not perform any changes")
    args = parser.parse_args()
    
    if args.dry_run:
        console.print("[bold yellow]Running in DRY-RUN mode.[/bold yellow]")

    console.print(Panel.fit(
        "[bold blue]nanoFaaS Advanced Release Manager[/bold blue]\n"
        "[dim]PR -> Merge -> Sync -> Bump -> K8s -> ARM64 -> Tag[/dim]",
        border_style="blue"
    ))
    
    check_tools()
    original_branch, dirty = get_git_status()
    
    if dirty and not args.dry_run:
        console.print("[yellow]Warning: Working tree is dirty.[/yellow]")
        if not questionary.confirm("Proceed anyway?").ask(): sys.exit(0)

    try:
        # 1. PR and Merge
        if original_branch != "main":
            if questionary.confirm(f"Merge '{original_branch}' into 'main' via PR?").ask():
                if args.dry_run: console.print("[dim](Dry-run) Would PR & Merge.[/dim]")
                else:
                    run_command("git push origin HEAD")
                    run_command("gh pr create --fill --base main")
                    run_command("gh pr merge --merge --delete-branch")
                    console.print("[green]✓ Branch merged via GitHub CLI.[/green]")

        # 2. Sync Main
        if not args.dry_run:
            run_command("git checkout main")
            run_command("git fetch origin main")
            run_command("git reset --hard origin/main")
            console.print("[green]✓ Local main synced.[/green]")

        # 3. Bump Logic
        current_v_str = get_current_version()
        v = semver.VersionInfo.parse(current_v_str)
        choices = [f"Patch ({v.bump_patch()})", f"Minor ({v.bump_minor()})", f"Major ({v.bump_major()})", "Custom"]
        choice = questionary.select("Release type?", choices=choices).ask()
        if not choice: return
        new_v = str(v.bump_patch() if "Patch" in choice else v.bump_minor() if "Minor" in choice else v.bump_major() if "Major" in choice else semver.VersionInfo.parse(questionary.text("Custom version:").ask()))

        # 4. Release Notes
        notes = generate_release_notes(new_v, get_commits_since(get_latest_tag()))
        console.print(Panel(notes, title="Release Notes Preview"))
        if not args.dry_run and not questionary.confirm("Use these notes?").ask():
            notes = questionary.text("Custom notes:", multiline=True, default=notes).ask()

        # 5. Apply Changes (Files + K8s)
        updated_files = update_files(new_v, args.dry_run)
        if args.dry_run: return

        # 6. Commit and Push Bump
        for f in updated_files: run_command(f"git add {f}")
        run_command(f'git commit -m "chore: release v{new_v}"')
        if questionary.confirm("Push release commit?").ask():
            run_command("git push origin main")

        # 7. Optional Local ARM64 Builds
        if questionary.confirm("Build and push ARM64 images from this machine?").ask():
            build_and_push_arm64(new_v)

        # 8. Tagging
        tag_name = f"v{new_v}"
        tag_file = ROOT / "RELEASE_NOTES.tmp.md"
        tag_file.write_text(notes)
        run_command(f'git tag -a {tag_name} -F RELEASE_NOTES.tmp.md')
        os.remove(tag_file)
        if questionary.confirm(f"Push tag {tag_name}?").ask():
            run_command(f"git push origin {tag_name}")

        console.print(Panel.fit(f"[bold green]Release {tag_name} successful![/bold green]", border_style="green"))

    finally:
        curr_branch, _ = get_git_status()
        if curr_branch != original_branch and not args.dry_run:
            if questionary.confirm(f"Return to '{original_branch}'?").ask():
                run_command(f"git checkout {original_branch}")

if __name__ == "__main__":
    try: main()
    except KeyboardInterrupt: sys.exit(0)