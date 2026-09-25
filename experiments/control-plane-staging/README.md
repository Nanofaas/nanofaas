# Control-plane staging

`experiments/staging_manager.py` creates and promotes candidate control-plane
versions under `versions/<slug>/`. A new version can start from `none`, from
`baseline`, or from any version present here (`--from version:<slug>`).

## Archived snapshots

Two historical snapshots were removed from the working tree and live only in
Git history:

- `control-plane-opt-recovered-decompiled-20260222-192441`
- `control-plane-rust-m3-20260222-200159`

Until they are restored, the staging manager cannot use them: `--from
version:<slug>` fails for these two slugs. Versions that are still present in
`versions/` keep working as before.

To restore both snapshots into the working tree:

```bash
archive_commit=$(git log -1 --format=%H --diff-filter=D -- experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/version.yaml)
git restore --source="${archive_commit}^" --worktree -- experiments/control-plane-staging/versions/control-plane-opt-recovered-decompiled-20260222-192441 experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159
```

Once restored, a new version can be derived from one of them. This creates a
new version directory, so run it only when you want one:

```bash
python3 experiments/staging_manager.py create-version --slug restored-copy --from version:control-plane-rust-m3-20260222-200159
```
