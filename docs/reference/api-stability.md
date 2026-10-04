---
layout: page
title: API Stability
parent: Reference
nav_order: 12
---

# API Stability

This page says how binary compatibility is checked and which modules are covered. Which packages are
stable, beta or experimental is defined in [1.0 Scope](v1-scope), the source of truth for tiers; this
page does not repeat that list, so the two cannot drift.

Binary compatibility is enforced between releases with [MiMa](https://github.com/lightbend/mima).

---

## Stability Contract

| Release type | Guarantee |
|---|---|
| **Patch** (0.x.y to 0.x.z) | No binary-breaking changes in frozen modules |
| **Minor** (0.x to 0.y) | Binary-breaking changes allowed, with a `@deprecated` migration path where one exists |
| **1.0.0 and later** | Full SemVer: a MAJOR version for breaking changes |

---

## What MiMa Covers

MiMa runs only on the modules [1.0 Scope](v1-scope) freezes. Each calls `mimaFrozen("<artifact>")` in
`build.sbt`:

| Module | Artifact |
|---|---|
| `modules/core` | `llm4s-core` |
| `modules/agent` | `llm4s-agent` |
| `modules/openai` | `llm4s-openai` |
| `modules/openai-compatible` | `llm4s-openai-compatible` |
| `modules/anthropic` | `llm4s-anthropic` |
| `modules/gemini` | `llm4s-gemini` |
| `modules/ollama` | `llm4s-ollama` |

Every other module is Beta or Experimental, or is not published (`llm4s-samples`,
`llm4s-workspace-*`, `llm4s-it`, `llm4s-docs`, `llm4s-benchmarks`), and is not checked. That includes
`org.llm4s.speech.*` (`llm4s-speech`), `org.llm4s.runner.*` (`llm4s-workspace-runner`) and
`org.llm4s.samples.*` (`llm4s-samples`, `llm4s-workspace-samples`): none ships in a frozen module, so
no filter is needed for them. Anything a frozen module marks Beta or Experimental in 1.0 Scope is
excluded by a `ProblemFilters.exclude` entry that says why.

If you find yourself importing from a Beta or Experimental package, please open an issue: it likely
means the stable API is missing something.

---

## The Baseline

`mimaBaselineVersion` in `build.sbt` names the release each frozen module is compared with. It is
`None` for now, so `sbt mimaReportBinaryIssues` checks nothing and CI passes.

The last release, 0.4.1, is a single `llm4s-core` of the pre-modularisation code, so it is not a usable
baseline for the split modules. The baseline is 0.5.0, the first release with the split coordinates
([#1281](https://github.com/llm4s/llm4s/issues/1281)); set `mimaBaselineVersion := Some("0.5.0")` once it
is published. The `mima-check` CI job gates `all-tests-pass`.

The build is Scala 3 only, so one `sbt mimaReportBinaryIssues` covers every artifact. If a second Scala
version returns, run `sbt +mimaReportBinaryIssues` in CI.

---

## Adding a Binary-Incompatible Change

Once a baseline is set, a change to a frozen module that breaks binary compatibility needs:

1. A `@deprecated` version of the old API pointing to the new one, where that is possible
2. A `ProblemFilters.exclude` entry in the module's `mimaBinaryIssueFilters`, with a comment saying why the break is intentional
3. An entry in `CHANGELOG.md` under `[Unreleased]`

```scala
// build.sbt, in the module's settings
mimaBinaryIssueFilters ++= Seq(
  // Agent.run gained a parameter; the old overload is deprecated, not removed.
  ProblemFilters.exclude[DirectMissingMethodProblem]("org.llm4s.agent.Agent.run")
)
```

Use the narrowest problem type and the narrowest name that matches, not `[Problem]` with a wildcard.

---

## Checking Compatibility Locally

```bash
sbt mimaReportBinaryIssues
```

This reports binary incompatibilities between the current code and the baseline release. With no
baseline set it reports nothing. Zero output otherwise means the frozen API is compatible.
