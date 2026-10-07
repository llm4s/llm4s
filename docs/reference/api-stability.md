---
layout: page
title: API Stability
parent: Reference
nav_order: 12
---

# API Stability

This page says how binary compatibility is checked and which modules are covered; the promise it serves, and
how an API is deprecated and removed, are in the [Compatibility and Deprecation Policy](compatibility-policy). Which packages are
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

## Tiers in the Code

[1.0 Scope](v1-scope) says which packages are frozen, but a table drifts from the code it describes. So
every top-level public type of a frozen module also carries its tier, from `org.llm4s.annotation` in
`llm4s-core`:

| Annotation | Meaning |
|---|---|
| `@Stable` | Covered by the 1.x compatibility promise: removed only after a deprecation cycle, and checked by MiMa. |
| `@Experimental` | Not covered. It can change or disappear in a minor release, with a migration note in that release's CHANGELOG. |

Both are Java annotations with runtime retention, so an IDE, a tool or a Java caller can read them; a
Scala-only annotation would be invisible to all three. A companion `object` needs no annotation of its
own: the one on its class, trait or enum covers the pair. A type that is `private` or `private[x]` needs
none. A type in a module 1.0 does not freeze (anything Beta or Experimental in the
[Package Map](v1-scope#package-map)) carries neither.

`sbt stabilityTierCheck` (a CI quick check) fails the build for a top-level public type of a frozen
module that has neither annotation or both, and for a Beta dialect that lives inside a frozen module
and is not `@Experimental`: today the Mistral and Cohere dialects of `llm4s-openai-compatible`, listed in
`build.sbt`. It reads sources, not classes, because Scala compiles `private[llm4s]` to a public
bytecode member. It covers `llm4s-core`, `llm4s-openai`, `llm4s-openai-compatible`,
`llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama`. **`llm4s-agent` is in 1.0 Scope's Frozen-at-1.0 tier but is not
covered yet**: the typed graph runtime ([#1266](https://github.com/llm4s/llm4s/issues/1266)) is
replacing its execution model and its tier is an open question in
[#1281](https://github.com/llm4s/llm4s/issues/1281). Add it to `stabilityTierModules` in `build.sbt`
when that is settled.

MiMa skips a type annotated `@Experimental` inside a frozen module: `mimaFrozen` in `build.sbt` adds
`mimaExcludeAnnotations += "org.llm4s.annotation.Experimental"`, so such a type needs no `ProblemFilters.exclude`
entry (a `@Stable` type is still checked). `scripts/mima-dry-run.sh` proves both on every run; see
[Release readiness](release#release-readiness-a-mima-dry-run).

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
no filter is needed for them. A top-level type that 1.0 Scope marks Beta or Experimental is `@Experimental`, which MiMa skips (see
[Tiers in the Code](#tiers-in-the-code)); anything else a frozen module contains that 1.0 Scope marks Beta or
Experimental needs a `ProblemFilters.exclude` entry that says why. There are none yet because no baseline is
set (see [The Baseline](#the-baseline)).

If you find yourself importing from a Beta or Experimental package, please open an issue: it likely
means the stable API is missing something.

---

## What a Frozen Module May Depend On

Freezing a module freezes what it puts on its users' classpath, so a frozen module may not resolve a
document-parsing, speech, cloud-storage, database or observability-backend dependency, or another provider's
vendor SDK, directly or transitively. `sbt frozenDependencyCheck` (a CI quick check) reads each frozen
module's resolved runtime dependencies and fails the build for a forbidden group, saying whether the module
declares it or reaches it transitively. The list, and where each group belongs instead, is in
`project/FrozenDependencies.scala`; change it there, with the reason, if a frozen module genuinely needs one.
`llm4s-openai` and `llm4s-anthropic` each keep their own vendor SDK; no other frozen module may carry either.

---

## The Baseline

`mimaBaselineVersion` in `build.sbt` names the release each frozen module is compared with. It is
`None` for now, so `sbt mimaReportBinaryIssues` checks nothing and CI passes.

The last release, 0.4.1, is a single `llm4s-core` of the pre-modularisation code, so it is not a usable
baseline for the split modules: against `llm4s-core` 0.4.1 MiMa reports thousands of problems, and
`llm4s-agent` and the provider modules do not exist at 0.4.1, so their artifacts do not resolve. The
baseline is 0.5.0, the first release with the split coordinates
([#1281](https://github.com/llm4s/llm4s/issues/1281)).

### Setting and bumping the baseline

`mimaBaselineVersion` is a plain `val`, not an sbt setting, so it is edited in the file; it cannot be
changed with `set`:

1. Publish the release and confirm every frozen artifact is on Maven Central under its `_3` name, for
   example `https://repo1.maven.org/maven2/org/llm4s/llm4s-agent_3/0.5.0/`. A frozen module whose
   baseline is not published fails `mimaPreviousClassfiles` with a "not found" error, by design.
2. Change the line to `val mimaBaselineVersion: Option[String] = Some("0.5.0")`.
3. Run `sbt mimaReportBinaryIssues`. Fix real breaks. Add a filter (below) only for an intentional one.
4. When the baseline later moves to a newer release, **delete every `mimaBinaryIssueFilters` entry**
   first: each one excuses a break against the old baseline, so against the new one it is stale and would
   silently hide a real break in the same class. Then re-run the check and re-add only what the new baseline needs.
5. Update this page and the CHANGELOG entry if the covered module list changes.

Before the first run against 0.5.0, review the Beta parts that ship inside frozen modules, because
[1.0 Scope](v1-scope) does not freeze them: `org.llm4s.assistant.*` (`llm4s-agent`) and the Mistral and
Cohere dialect classes (`llm4s-openai-compatible`). Either exclude them with a documented filter or
decide that they are frozen too. `llm4s-agent` may also wait for the graph runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)) before it gets a baseline; if so, drop
`mimaFrozen("llm4s-agent")` from `modules/agent` until then.

The build is Scala 3 only, so one `sbt mimaReportBinaryIssues` covers every artifact. If a second Scala
version returns, CI must run it cross-built (with sbt's `+` prefix) to check each version.

---

## Adding a Binary-Incompatible Change

Once a baseline is set, a change to a frozen module that breaks binary compatibility needs:

1. A `@deprecated` version of the old API pointing to the new one, where that is possible
2. A `ProblemFilters.exclude` entry in the module's `mimaBinaryIssueFilters`, with a comment saying why the break is intentional
3. An entry in `CHANGELOG.md` under `[Unreleased]`

`ProblemFilters` is not in sbt's default imports, so `build.sbt` needs
`import com.typesafe.tools.mima.core._` at the top before a filter compiles.

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
baseline set, each module logs `mimaPreviousArtifacts not set` (or `is empty`) and the task succeeds.
With a baseline set, success means the frozen API is compatible, and failure lists each problem with
the `ProblemFilters.exclude` line that would silence it. To check one module, run
`sbt agent/mimaReportBinaryIssues`. Before 0.5.0 exists, `scripts/mima-dry-run.sh` checks that this wiring
works (see [Release readiness](release#release-readiness-a-mima-dry-run)).
