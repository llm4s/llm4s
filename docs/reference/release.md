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
ci → publish → publish-kotlin → github-release → docs
             │                                 └→ sbom
             └──────────────────────────────────→ docker
```

| Job | Does |
|-----|------|
| `ci` | Full CI on the tagged commit |
| `publish` | Signs and publishes the sbt artifacts to Maven Central (`sbt ci-release`) |
| `publish-kotlin` | Builds, signs, checks and uploads `llm4s-kotlin-api`, the Gradle-built Kotlin artifact, at the same version (see [The Kotlin artifact](#the-kotlin-artifact-llm4s-kotlin-api)) |
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

### The Kotlin artifact: `llm4s-kotlin-api`

`org.llm4s:llm4s-kotlin-api` is built by Gradle (`modules/kotlin-api`), not sbt, so `sbt ci-release` does not publish it.
The `publish-kotlin` job does, after `publish`, with the same signing key and Central Portal token:

1. It checks that sbt's version (`sbt 'print ThisBuild / version'`, which sbt-dynver derives from the tag) is the tag's
   version without its `v`, and publishes the library to the runner's local Maven repository (`sbt publishM2`), so
   the Kotlin build compiles against the very `llm4s-java-api_3` the release published rather than waiting for Central.
2. `gradle publishMavenPublicationToStagingRepository -Pllm4sVersion=<version>` writes the signed publication - the
   POM, the jar, the sources jar, a javadoc jar holding the Dokka HTML of the KDoc, the Gradle module file, their
   `.asc` signatures and checksums - to `modules/kotlin-api/build/staging-deploy`. `llm4sVersion` sets both the
   artifact's version and the `llm4s-java-api_3` version it depends on; without it the build is `0.1.0-SNAPSHOT`.
3. `scripts/check-kotlin-publication.sh` (with `REQUIRE_SIGNATURES=1`) checks it: every file signed; the POM's url,
   licence, organization, scm, developers and version scheme equal to the sbt-published POMs'; a compile-scope
   dependency on `llm4s-java-api_3` at the same version and on no snapshot; and a throwaway Gradle project that imports
   `llm4s-bom` and declares `llm4s-kotlin-api` with no version compiles Kotlin code against it.
4. `scripts/publish-kotlin-api.sh` zips the version directory into a Central Portal bundle, uploads it with
   `publishingType=AUTOMATIC` (as `sbt ci-release` releases), and waits until the Portal has validated it and is
   publishing. It refuses a snapshot, an unsigned file or a missing sources or javadoc jar before uploading anything.

The artifact has no `_3` suffix, since it is Kotlin, and `llm4s-bom` manages it with every other artifact, so a Gradle
build that imports the BOM declares `implementation("org.llm4s:llm4s-kotlin-api")`. `sbt listPublishedArtifacts` lists
it as a `gradle` line (`project/PublishedArtifacts.scala`, `Gradle`), so `scripts/verify-release.sh` checks it is on
Central, and `sbt publishedArtifactsCheck` requires it to be documented like any other artifact.

Every pull request runs steps 2 and 3 unsigned in the `Kotlin API` CI job, at `0.1.0-SNAPSHOT`, so a change that breaks
the publication fails there and not on release day; `scripts/test-publish-kotlin-api.sh` runs step 4 against a fake
Portal. To try it locally (it writes nothing outside the repository but the local Maven repository you name):

```bash
sbt -Dmaven.repo.local=/tmp/m2 'set ThisBuild / version := "0.1.0-SNAPSHOT"' publishM2
(cd modules/kotlin-api && gradle -Dmaven.repo.local=/tmp/m2 publishMavenPublicationToStagingRepository)
scripts/check-kotlin-publication.sh 0.1.0-SNAPSHOT modules/kotlin-api/build/staging-deploy /tmp/m2
```

The Kotlin build needs JDK 21 to run its tests, and a Kotlin 2.0 compiler does not run on JDK 25: run Gradle on JDK 21.

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
sbt -error listPublishedArtifacts   # one `artifact <id>`, `gradle <id>`, `bom <id>` or `stub <id>` per line
```

A `gradle` line is an artifact the release publishes from a Gradle build rather than from the aggregate:
`llm4s-kotlin-api`, published by the `publish-kotlin` job. It is on the tagged commit when `modules/kotlin-api` is.

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
  version: the POM and the jar for a real artifact, and for `llm4s-kotlin-api` also a POM that depends on
  `llm4s-java-api_3` at that same version, a POM that carries a `<dependencyManagement>` for the
  BOM (it has no jar), and for each relocation stub (the pre-0.4.0 coordinates) a POM that carries a
  `<relocation>`. It exits non-zero and names each miss. Run against
  `0.4.0` with the stub list it reports all five stubs as missing, which is how 0.4.0 shipped
  ([#1150](https://github.com/llm4s/llm4s/issues/1150)); against `0.4.1` it passes.

  Maven Central's index can lag a successful publish, so a 404 straight after the job finishes is not
  proof of a miss: re-run before concluding one. A miss that persists cannot be fixed in that version
  ([Re-triggering a failed release](#re-triggering-a-failed-release)); it waits for the next one.

## Release readiness: a MiMa dry run

`mimaBaselineVersion` in `build.sbt` is `None`, so `sbt mimaReportBinaryIssues` compiles the build and checks
nothing ([The Baseline](api-stability#the-baseline)). 0.5.0 is the release that sets it
([#1281](https://github.com/llm4s/llm4s/issues/1281)), and MiMa cannot run before a release exists to compare
with. `scripts/mima-dry-run.sh` does those steps early, against a throwaway version, to show that they will work:

```bash
scripts/mima-dry-run.sh                 # on JDK 21, from a clean checkout of the commit you want to check
SBT=/path/to/sbt scripts/mima-dry-run.sh --no-negative-control
```

It works in a scratch clone of `HEAD` (uncommitted changes are not included; your working tree is not
touched), sets `mimaBaselineVersion` there, publishes every module to a temporary Maven repository (never
`~/.ivy2`, `~/.m2` or Central), and then, for each frozen module (read from the `mimaFrozen(...)` call sites in
`build.sbt`):

- checks that `mimaPreviousArtifacts` names the published artifact, that MiMa resolved `llm4s-<name>_3` and
  compared it (it prints the coordinates and the problem counts: a silent empty baseline would pass), and that
  `mimaReportBinaryIssues` finds nothing;
- runs a **negative control**: a baseline with two extra classes, one `@Stable` and one `@Experimental`, that
  the code then lacks. MiMa must fail on the `@Stable` one and must not report the `@Experimental` one.

It took about three minutes on a laptop with warm caches (between 156 s and 184 s over three runs; an independent run took 175 s). It proves the wiring: the coordinates, the
`_3` suffix, resolution, that the check can fail, and that `@Experimental` types are outside the freeze. It does
**not** prove that any API is compatible with anything: there is no 0.5.0 baseline yet. It is a manual script,
not a CI job, because it publishes the whole build. `scripts/test-mima-dry-run.sh` tests the script itself
without sbt.

At the cut: publish 0.5.0, confirm the frozen artifacts are on Central
([Verify Release](#5-verify-release)), then follow
[Setting and bumping the baseline](api-stability#setting-and-bumping-the-baseline).

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

- **`publish-kotlin` failed** — the sbt artifacts are live, so do **not** re-tag. If it failed before the upload
  step, re-run the job from the Actions UI once the cause is fixed outside the tag (a missing secret, a Central
  outage); `github-release` and `docs` then follow. If the upload step reported a FAILED deployment, the Portal's
  reasons are in the log and nothing reached Central, so a re-run is safe after fixing them; a change to the build
  that the fix needs waits for the next patch version. A deployment the Portal accepted cannot be uploaded again.

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
