---
layout: page
title: Release Process
parent: Reference
nav_order: 3
---

# Release Process

## Creating a New Release

### 1. Tag Format
All release tags MUST use the `v` prefix format: `v0.3.2`, `v1.0.0`, etc. Use three numeric parts, optionally followed by a suffix such as `-RC1`
(`vMAJOR.MINOR.PATCH[-suffix]`). The release workflow runs for any `v[0-9]*` tag, but the docs deploy accepts only that form: a tag such as
`v1.0` would publish to Maven Central and then fail the `docs` job, with the artifacts already out.

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
| `docs` | Deploys llm4s.org with the new version in the install snippets: dispatches the docs workflow on `main`, built from the tag, and waits for it |
| `docker` | Builds and pushes the container image |

### The docs deploy runs from `main`, built from the tag

The `github-pages` environment only accepts deployments from the `main` branch. A job that ran on the tag
would be rejected before it started (that is what failed v0.4.1, the first release to run it: [#1152](https://github.com/llm4s/llm4s/issues/1152)),
so the `docs` job does not deploy from the tag: it **dispatches** `pages.yml` on `main` with the tag as its `ref`
input (`scripts/dispatch-docs-deploy.sh`). The dispatched run is on `main`, so the environment accepts it, and its
build checks out the tag, so the version and the install snippets are the release's. The `docs` job waits for that run and
fails if the deploy fails; it still runs only after `github-release`, so the docs never advertise a version that is not on
Maven Central.

An environment rule for `v*` tags (Settings, Environments, `github-pages`, Deployment branches and tags) would also let a
tag-triggered deploy through, and is an equally valid alternative if a maintainer prefers it; the workflow does not need it.

To deploy the docs for a tag by hand, for example to re-run a deploy that failed, or to try the path before a release,
dispatch the workflow on `main`:

```bash
gh workflow run pages.yml --ref main -f ref=v0.5.0   # the ref must be a release tag, vX.Y.Z or vX.Y.Z-RC1
```

That redeploys the site from that tag's content, so use the **latest** release's tag unless you mean to roll the site
back. The tag must exist, and `latest_release` in the install snippets always comes from the newest published GitHub
Release, whichever tag is built.

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

To see exactly what the tagged commit will publish, ask the build:

```bash
sbt -error listPublishedArtifacts   # one `artifact <id>`, `bom <id>` or `stub <id>` per line
```

`sbt publishedArtifactsCheck` (a CI quick check) fails when a published `llm4s-*` artifact has no tier in
[1.0 Scope](v1-scope) or no install line in the installation guide.

The `bom` line is `llm4s-bom`, a POM-only bill of materials that pins every real artifact to the release
version. It is generated from the same list as the `artifact` lines, and `sbt bomCheck` (a CI quick check)
fails when the generated POM disagrees with that list.

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
- Check Docker images: https://github.com/llm4s/llm4s/pkgs/container/workspace-runner
- Verify Maven Central, with the script rather than by eye:

  ```bash
  scripts/verify-release.sh 0.5.0     # a leading v is accepted
  ```

  It asks the build which artifacts it publishes (the list above) and checks that each resolves at that
  version: the POM and the jar for a real artifact, a POM that carries a `<dependencyManagement>` for the
  BOM (it has no jar), and for each relocation stub (the pre-0.4.0 coordinates) a POM that carries a
  `<relocation>`. It exits non-zero and names each miss. Run against
  `0.4.0` with the stub list it reports all five stubs as missing, which is how 0.4.0 shipped
  ([#1150](https://github.com/llm4s/llm4s/issues/1150)); against `0.4.1` it passes.

  Maven Central's index can lag a successful publish, so a 404 straight after the job finishes is not
  proof of a miss: re-run before concluding one. A miss that persists cannot be fixed in that version
  ([Re-triggering a failed release](#re-triggering-a-failed-release)); it waits for the next one.

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
  cut the next patch version if the failure needs a code change. Re-running `docs` dispatches a
  fresh docs deploy. If `docs` ended "cancelled", a newer deploy usually replaced it (the workflow
  keeps one pending run): check that llm4s.org shows the new version before re-running.

## Version Numbering

We follow semantic versioning (MAJOR.MINOR.PATCH):
- MAJOR: Breaking API changes
- MINOR: New features, backwards compatible
- PATCH: Bug fixes, backwards compatible

Current documented version series: 0.4.x (pre-1.0 development)
