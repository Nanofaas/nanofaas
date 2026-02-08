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

def get_project_root():
    """Find project root by looking for build.gradle"""
    current = Path(__file__).resolve().parent
    for parent in [current] + list(current.parents):
        if (parent / "build.gradle").exists():
            return parent
    console.print("[red]Error: Could not find project root (build.gradle not found in parents).[/red]")
    sys.exit(1)

ROOT = get_project_root()

def check_git_available():
    if not shutil.which("git"):
        console.print("[red]Error: 'git' command not found in PATH.[/red]")
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
    
    categories = {
        "feat": [],
        "fix": [],
        "chore": [],
        "other": []
    }
    
    for commit in commits:
        if not commit: continue
        match = re.match(r"^[a-f0-9]+\s+(\w+)(?:\(.*\))?:\s+(.*)$", commit)
        if match:
            ctype, msg = match.groups()
            if ctype in categories:
                categories[ctype].append(msg)
            else:
                categories["other"].append(f"{ctype}: {msg}")
        else:
            msg = re.sub(r"^[a-f0-9]+\s+", "", commit)
            categories["other"].append(msg)
            
    if categories["feat"]:
        notes += "## Features\n" + "\n".join(f"- {m}" for m in categories["feat"]) + "\n\n"
    if categories["fix"]:
        notes += "## Bug Fixes\n" + "\n".join(f"- {m}" for m in categories["fix"]) + "\n\n"
    if categories["other"] or categories["chore"]:
        notes += "## Other Changes\n"
        notes += "\n".join(f"- {m}" for m in categories["chore"]) + "\n"
        notes += "\n".join(f"- {m}" for m in categories["other"]) + "\n"
        
    return notes.strip()

def update_files(new_v, dry_run=False):
    files_to_update = [
        ("build.gradle", r"(version\s*=\s*')[^']+'", rf"\g<1>{new_v}'"),
        ("function-sdk-python/pyproject.toml", r'(version\s*=\s*")[^"]+"', rf'\g<1>{new_v}"'),
        ("watchdog/Cargo.toml", r'(^version\s*=\s*")[^"]+"', rf'\g<1>{new_v}"'),
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
        console.print("[bold yellow]Running in DRY-RUN mode. No changes will be persisted.[/bold yellow]")

    console.print(Panel.fit(
        "[bold blue]nanoFaaS Release Manager[/bold blue]\n"
        "[dim]Automated version bumping and deployment[/dim]",
        border_style="blue"
    ))
    
    check_git_available()
    
    original_branch, dirty = get_git_status()
    
    if dirty and not args.dry_run:
        console.print("[yellow]Warning: Your working tree is dirty.[/yellow]")
        if not questionary.confirm("Do you want to proceed despite uncommitted changes?").ask():
            sys.exit(0)

    try:
        # Switch to main if not already there
        if original_branch != "main" and not args.dry_run:
            if questionary.confirm(f"Currently on branch '{original_branch}'. Switch to 'main' for release?").ask():
                run_command("git checkout main")
                console.print("[green]Switched to branch 'main'[/green]")
            else:
                if not questionary.confirm(f"Proceed release on current branch '{original_branch}'?").ask():
                    return

        current_v_str = get_current_version()
        console.print(f"Current version: [bold cyan]{current_v_str}[/bold cyan]")
        
        v = semver.VersionInfo.parse(current_v_str)
        
        choices = [
            f"Patch ({v.bump_patch()})",
            f"Minor ({v.bump_minor()})",
            f"Major ({v.bump_major()})",
            "Custom"
        ]
        
        choice = questionary.select(
            "What type of release is this?",
            choices=choices
        ).ask()
        
        if not choice:
            return
            
        if "Patch" in choice:
            new_v = str(v.bump_patch())
        elif "Minor" in choice:
            new_v = str(v.bump_minor())
        elif "Major" in choice:
            new_v = str(v.bump_major())
        else:
            new_v_str = questionary.text("Enter custom version:").ask()
            new_v = str(semver.VersionInfo.parse(new_v_str))

        console.print(f"Bumping to: [bold green]{new_v}[/bold green]")
        
        if not args.dry_run and not questionary.confirm(f"Confirm bump from {current_v_str} to {new_v}?").ask():
            return
            
        latest_tag = get_latest_tag()
        commits = get_commits_since(latest_tag)
        notes = generate_release_notes(new_v, commits)
        
        console.print("\n[bold]Generated Release Notes:[/bold]")
        console.print(Panel(notes, title="Preview"))
        
        if not args.dry_run and not questionary.confirm("Do you want to use these release notes?").ask():
            notes = questionary.text("Enter your custom release notes:", multiline=True, default=notes).ask()
            if not notes:
                return

        updated_files = update_files(new_v, args.dry_run)
        
        if args.dry_run:
            console.print("[yellow]Dry-run complete. No Git commands executed.[/yellow]")
            return

        if not updated_files:
            console.print("[red]No files were updated. Aborting Git operations.[/red]")
            return

        console.print("\n[bold]Executing Git operations...[/bold]")
        
        for f in updated_files:
            run_command(f"git add {f}")
        run_command(f'git commit -m "chore: bump version to {new_v}"')
        console.print("[green]✓ Committed version bump[/green]")
        
        if questionary.confirm("Push changes?").ask():
            curr_branch, _ = get_git_status()
            run_command(f"git push origin {curr_branch}")
            console.print(f"[green]✓ Pushed to {curr_branch}[/green]")
        
        tag_name = f"v{new_v}"
        tag_file = ROOT / "RELEASE_NOTES.tmp.md"
        tag_file.write_text(notes)
        run_command(f'git tag -a {tag_name} -F RELEASE_NOTES.tmp.md')
        os.remove(tag_file)
        console.print(f"[green]✓ Created tag {tag_name}[/green]")
        
        if questionary.confirm(f"Push tag {tag_name}?").ask():
            run_command(f"git push origin {tag_name}")
            console.print("[green]✓ Pushed tag to origin[/green]")

        console.print(Panel.fit(
            f"[bold green]Release v{new_v} successful![/bold green]",
            border_style="green"
        ))

    finally:
        # Restore original branch if we switched
        current_branch, _ = get_git_status()
        if current_branch != original_branch and not args.dry_run:
            console.print(f"\n[yellow]Returning to original branch '{original_branch}'...[/yellow]")
            run_command(f"git checkout {original_branch}")

if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        console.print("\n[yellow]Release cancelled by user.[/yellow]")
        sys.exit(0)