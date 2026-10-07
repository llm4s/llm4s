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
  module, named `<artifact>-<version>.bom.json` (for example `llm4s-core_3-0.5.0.bom.json`), once the Release exists.
  It is a separate job, so a failure there turns the run red without touching Maven Central, the Release, the docs
  deploy or the container image. Attaching it re-uploads over an existing file, so re-running the job is safe.
- **On every pull request.** CI builds and validates the same files and uploads them as the `sbom` workflow artifact
  for 14 days, so a change that breaks their generation is found before a release.

## What they cover

Each BOM lists the **runtime dependency tree** of one module as resolved by the build: group, name, version, hashes
and a `pkg:maven` package URL for every library, plus the dependency graph between them. They describe the module's
compile and runtime classpath, so test frameworks such as ScalaTest are not listed (the one exception is
`llm4s-provider-testkit`, whose published API is built on ScalaTest, so ScalaTest is a real dependency of it).

A project that a module depends on only for its tests (`dependsOn(other % Test)`, for example the provider test kit)
is not listed either. The `sbt-sbom` plugin lists such a project as a required component, although the module's POM
gives it `test` scope, so `sbt publishedBoms` removes the components the described module does not reach through the
BOM's own dependency graph (`project/SbomPrune.scala`), and `scripts/check-sbom.sh` fails if one is left.

The files are CycloneDX 1.6 JSON, the default of the `sbt-sbom` plugin version the build uses. They contain no
timestamp, serial number or build-machine path, and building the same commit twice gave byte-identical files.

The generation adds nothing to what you depend on: the plugin is a build tool, and the POMs of all published
modules are byte-identical with and without it.

## Build them locally

```bash
sbt publishedBoms          # every published module, copied into target/boms
sbt core/makeBom           # one module, written under that module's target/
```

Then check them the way CI does:

```bash
sbt -error listPublishedArtifacts | grep -E '^(artifact|stub) ' > target/published-artifacts.txt
scripts/check-sbom.sh target/boms target/published-artifacts.txt
```

The check fails for a file that is not CycloneDX JSON, a BOM with no components, a build-machine path, a test
framework in a module that does not ship one, a missing library in the `llm4s-core` BOM, a published module without
a BOM, a BOM for something that is not published, and a component the described module does not reach through the
dependency graph. `scripts/test-check-sbom.sh` tests the check itself.

## Vulnerability scanning

The `Dependency scan` workflow (`.github/workflows/dependency-scan.yml`) builds the BOMs and runs
[OSV-Scanner](https://google.github.io/osv-scanner/) over them, on pull requests that change `build.sbt`,
`project/Dependencies.scala` or `project/plugins.sbt`, and every Monday.

- **Report only.** The scan step does not fail the run: findings appear in the log and the job summary. To make it
  block, delete `continue-on-error: true` from the scan step.
- **Why not GitHub's dependency review.** GitHub's dependency graph does not read sbt builds, so it never sees a
  Scala dependency change. The BOMs give OSV-Scanner the exact Maven coordinates instead.
- **File names.** OSV-Scanner recognises a CycloneDX file by the name `bom.json` or `*.cdx.json`, so the workflow
  scans copies renamed from `*.bom.json`. The release keeps the plugin's `*.bom.json` names.
- **Pinned.** The scanner binary is a pinned release whose SHA-256 is checked before it runs.

## What is not covered

- The SBOMs are not signed and there is no build provenance attestation yet.
- They list dependencies, not licences, and say nothing about vulnerabilities by themselves: a finding comes from
  a scan of the BOM at a point in time.
- The container images (`workspace-runner`) have no SBOM.
