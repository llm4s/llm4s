---
layout: page
title: Software Bill of Materials
parent: Reference
---

# Software Bill of Materials (SBOM)

Every published `llm4s-*` module has a [CycloneDX](https://cyclonedx.org/) SBOM listing the libraries it
depends on. This page says where to find them, what they cover, how to build them yourself, and how the
dependencies are scanned for known vulnerabilities.

## Where the SBOMs are

- **On each GitHub Release.** The `sbom` job of `.github/workflows/release.yml` attaches one file per published
  module, named `<module>-<version>.bom.json` (for example `llm4s-core-0.5.0.bom.json`; the file describes the
  artifact `llm4s-core_3`), once the Release exists.
  It is a separate job, so a failure there turns the run red without touching Maven Central, the Release, the docs
  deploy or the container image. Attaching it re-uploads over an existing file, so re-running the job is safe.
- **On every pull request.** CI builds and validates the same files and uploads them as the `sbom` workflow artifact
  for 14 days, so a change that breaks their generation is found before a release.

## What they cover

Each BOM lists the **runtime dependency tree** of one module as resolved by the build: group, name, version, hashes
and a `pkg:maven` package URL for every library, plus the dependency graph between them. They describe the module's
compile and runtime classpath, so test frameworks such as ScalaTest are not listed (the exceptions are
`llm4s-provider-testkit` and `llm4s-agent-testkit`, whose published APIs are built on ScalaTest, so ScalaTest is a real
dependency of them).

A project that a module depends on only for its tests (`dependsOn(other % Test)`, for example the provider test kit)
is not listed either. The `sbt-sbom` plugin lists such a project as a required component, although the module's POM
gives it `test` scope, so `sbt publishedSboms` removes the components the described module does not reach through the
BOM's own dependency graph (`project/SbomPrune.scala`), and `scripts/check-sbom.sh` fails if one is left.

A published artifact that holds no code, such as a relocation stub or a bill-of-materials POM that only aligns
versions, has no SBOM: it has no dependencies of its own to describe.

The files are CycloneDX 1.6 JSON, the default of the `sbt-sbom` plugin version the build uses. They contain no
timestamp, serial number or build-machine path, and building the same commit twice gave byte-identical files.

The generation adds nothing to what you depend on: the plugin is a build tool, and the POMs of all published
modules are byte-identical with and without it.

## Build them locally

```bash
sbt publishedSboms          # every published module, copied into target/sboms
sbt core/makeBom           # one module, written under that module's target/
```

Then check them the way CI does:

```bash
sbt -error listPublishedArtifacts | grep -E '^(artifact|stub) ' > target/published-artifacts.txt
scripts/check-sbom.sh target/sboms target/published-artifacts.txt
```

The check fails for a file that is not CycloneDX JSON, a BOM with no components, a build-machine path, a test
framework in a module that does not ship one, a missing library in the `llm4s-core` BOM, a published module without
a BOM, a BOM for something that is not published, and a component the described module does not reach through the
dependency graph. `scripts/test-check-sbom.sh` tests the check itself.

## Vulnerability scanning

The `Dependency scan` workflow (`.github/workflows/dependency-scan.yml`) builds the SBOMs and runs
[OSV-Scanner](https://google.github.io/osv-scanner/) over them, on pull requests that change `build.sbt`,
`project/Dependencies.scala` or `project/plugins.sbt`, and every Monday. No secret is used, so it runs the same on a
pull request from a fork.

- **What fails a pull request.** Only a HIGH or CRITICAL advisory (CVSS 7.0 or more, or the advisory database's
  `HIGH`/`CRITICAL` when there is no score) that the pull request's dependencies have and its base's do not. The
  workflow scans the base and the head in the same job, against the same advisory database, so a finding is new only
  because the change added or moved a dependency. Advisories `main` already carries are listed in the job summary but
  never fail an unrelated change; they are fixed in pull requests of their own. A finding is the pair (advisory,
  package), so bumping a package from one affected version to another is not new.
- **Report only** on the weekly run, and on a pull request whose base cannot build SBOMs.
- **Outages do not block.** If OSV-Scanner cannot run on a pull request (the advisory database is unreachable, say),
  the job warns and passes; the weekly run fails instead, so a broken scan is noticed. A scan that finds no package at
  all always fails: that is a broken setup, not a clean result.
- **Readable output.** The job summary has one table: blocking findings first, then other new ones, then those already
  on the base, each with its advisory link, CVSS score, package, version and the modules that pull it in.
- **Why not GitHub's dependency review.** GitHub's dependency graph does not read sbt builds, so it never sees a
  Scala dependency change. The SBOMs give OSV-Scanner the exact Maven coordinates instead.
- **Two scanner details** that otherwise make it scan nothing while reporting only "No package sources found":
  it recognises a CycloneDX file by the name `bom.json` or `*.cdx.json`, and it skips files `.gitignore` ignores
  (`target/` is). `scripts/osv-scan-sboms.sh` copies the SBOMs to `*.cdx.json` outside the repository and passes
  `--no-ignore`. The release keeps the plugin's `*.bom.json` names.
- **Pinned and open source.** The scanner binary (OSV-Scanner, Apache-2.0) is a pinned release whose SHA-256 is
  checked before it runs. The SBOMs come from the `sbt-sbom` plugin (MIT) and `cyclonedx-core-java` (Apache-2.0),
  build tools only.

Run the scan yourself, with [OSV-Scanner](https://github.com/google/osv-scanner/releases) installed:

```bash
sbt publishedSboms
OSV_SCANNER=osv-scanner scripts/osv-scan-sboms.sh target/sboms target/osv.json
scripts/osv-new-findings.sh target/osv.json        # the report; add a base report as a second argument to diff
```

`scripts/test-osv-scan.sh` tests both scripts offline.

## What is not covered

- The SBOMs are not signed and there is no build provenance attestation yet.
- They list dependencies, not licences, and say nothing about vulnerabilities by themselves: a finding comes from
  a scan of the BOM at a point in time.
- The container images (`workspace-runner`) have no SBOM.
