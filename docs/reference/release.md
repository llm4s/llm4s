# Release Process

## Creating a New Release

### 1. Tag Format
All release tags MUST use the `v` prefix format: `v0.3.2`, `v1.0.0`, etc.

### 2. Release Steps

```bash
# 1. Ensure you're on main branch with latest changes
git checkout main
git pull origin main

# 2. Create and push the version tag (ALWAYS use 'v' prefix)
git tag v0.3.2
git push origin v0.3.2

# 3. The GitHub Actions release workflow does the rest -- see below.
```

Pushing the tag is the whole procedure. Everything after it is automated, in this order:

```
ci → publish → github-release → docs
             └───────────────→ docker
```

| Job | Does |
|-----|------|
| `ci` | Full CI on the tagged commit |
| `publish` | Signs and publishes artifacts to Maven Central |
| `github-release` | Creates the GitHub Release for the tag |
| `docs` | Deploys llm4s.org with the new version in the install snippets |
| `docker` | Builds and pushes the container image |

### 3. What gets published is whatever the tagged commit aggregates

`sbt ci-release` publishes the root aggregate **as it exists on the tagged commit**. Work that
is on `main` but not on that commit is simply not in the release, and because Maven Central is
immutable there is no way to add it to that version afterwards -- it waits for the next one.

This has bitten us once already. The Maven relocation stubs that redirect the pre-0.4.0
coordinates ([#1146](https://github.com/llm4s/llm4s/pull/1146)) merged shortly after `v0.4.0`
was tagged, so 0.4.0 shipped without them and `org.llm4s:core % 0.4.0` still fails to resolve
rather than redirecting ([#1150](https://github.com/llm4s/llm4s/issues/1150)).

So before tagging, check that anything the release is *for* is on the commit you are about to
tag, not merely on `main`:

```bash
git merge-base --is-ancestor <commit> <tag-or-HEAD> && echo "in the release" || echo "NOT in the release"
```

A new published module needs the same check twice over: it must be on the tagged commit **and**
aggregated by the root project in `build.sbt`. A module outside the aggregate publishes nothing
and does so silently.

### 4. Do NOT create the GitHub Release by hand

The `github-release` job creates it for you, and it runs **after** `publish` succeeds. That
ordering is the point: a GitHub Release is the signal that a version is available, it is
what notifies everyone watching "Releases only", and it is what `docs` reads to decide which
version the install snippets should name.

Creating the Release manually defeats all of that. The job skips creation when a Release
already exists, so a hand-made one is accepted regardless of whether the publish went on to
succeed -- leaving a Release, a notification, and a documented coordinate for a version that
never reached Maven Central.

**Editing the generated notes afterwards is fine and encouraged.** The job only ever creates;
it never overwrites. Write whatever the release deserves once it exists.

### 5. Verify Release

- Check GitHub Actions: https://github.com/llm4s/llm4s/actions/workflows/release.yml
- Verify Maven Central: https://central.sonatype.com/namespace/org.llm4s
- Check Docker images: https://github.com/llm4s/llm4s/pkgs/container/workspace-runner

## Troubleshooting

### Release workflow didn't trigger

- Ensure tag starts with `v` (e.g., `v0.3.2` not `0.3.2`)
- Check that tag was pushed: `git push origin v0.3.2`
- Verify workflow status at GitHub Actions page

### Re-triggering a failed release

**Check what actually failed first.** Maven Central is immutable: once `publish` has
succeeded, those coordinates exist forever and cannot be replaced. Re-running a release whose
`publish` step already completed will fail on the existing version, and re-tagging will not
help.

- **Failed before or during `publish`** — nothing was published. Delete and recreate the tag:

  ```bash
  git tag -d v0.3.2
  git push origin :v0.3.2
  git tag v0.3.2
  git push origin v0.3.2
  ```

  Note that deleting a tag that already has a GitHub Release attached leaves the Release
  behind as a draft. Delete it too before retrying, or the recreated tag's `github-release`
  job will skip creation.

- **Failed after `publish`** (`github-release`, `docs` or `docker`) — the artifacts are live
  and the release is real. Do **not** re-tag. Re-run the failed job from the Actions UI, or
  cut the next patch version if the failure needs a code change.

## Version Numbering

We follow semantic versioning (MAJOR.MINOR.PATCH):
- MAJOR: Breaking API changes
- MINOR: New features, backwards compatible
- PATCH: Bug fixes, backwards compatible

Current documented version series: 0.4.x (pre-1.0 development)
