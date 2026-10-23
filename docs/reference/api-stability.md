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
| **Before the baseline** (0.5.x now) | Still stabilising: any release, patch included, may break, with a CHANGELOG entry and a migration note and no `@deprecated` shim |
| **Patch, after the baseline** (0.x.y to 0.x.z) | No binary-breaking changes in frozen modules |
| **Minor, after the baseline** (0.x to 0.y) | Binary-breaking changes allowed, with a CHANGELOG entry and a migration note |
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
Scala-only annotation would be invisible to all three. A Stable companion shares the tier of its class, trait or enum. An Experimental companion
`object` must also carry `@Experimental`: Scala emits a separate companion class, and MiMa
reads each class's own annotation. A type that is `private` or `private[x]` needs
none. A type in a module 1.0 does not freeze (anything Beta or Experimental in the
[Package Map](v1-scope#package-map)) carries neither.

`sbt stabilityTierCheck` (a CI quick check) fails the build for a top-level public type of a frozen
module that has neither annotation or both, and for a Beta dialect that lives inside a frozen module
and is not `@Experimental`: today the Mistral and Cohere dialects of `llm4s-openai-compatible`, listed in
`build.sbt`. It also fails for a public member of a `@Stable` type whose signature names an
`@Experimental` type - a `def`, `val`, enum `case`, nested type or constructor parameter, unless it is `private`,
`protected` or itself `@Experimental` - because renaming or reshaping the Experimental type would then break the
Stable signature ([#1818](https://github.com/llm4s/llm4s/issues/1818)). Promote the type, or keep the member out of
the Stable surface. It runs its own fixtures first (`StabilityTiers.selfTest`), so a change that stops it finding
such a leak fails the build. It reads sources, not classes, because Scala compiles `private[llm4s]` to a public
bytecode member. It covers `llm4s-core`, `llm4s-openai`, `llm4s-openai-compatible`,
`llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama`. **`llm4s-agent` is in 1.0 Scope's Frozen-at-1.0 tier but is not
covered yet**: the typed graph runtime ([#1266](https://github.com/llm4s/llm4s/issues/1266)) is
replacing its execution model and its tier is an open question in
[#1281](https://github.com/llm4s/llm4s/issues/1281). Add it to `stabilityTierModules` in `build.sbt`
when that is settled.

The same check enforces [RFC 0004](../rfcs/#index): a public member of a `@Stable` type must not expose a
third-party type, because freezing the signature would freeze the library behind it
([#2357](https://github.com/llm4s/llm4s/issues/2357)). It fails for a signature that names a type from
fansi, `cats`, pureconfig, `com.typesafe.config`, jtokkit, slf4j, OkHttp, Jackson, or the OpenAI, Anthropic or
AWS SDKs (`com.openai`, `com.anthropic`, `software.amazon`), written fully qualified or through a name the file
imports (including a renaming import such as `import com.typesafe.config.{Config => TsConfig}`). Use an llm4s or
Scala type instead, or keep the member out of the Stable surface. A third-party type that is API on purpose
goes on the allow-list, `ThirdPartyAllowed` in `project/StabilityTiers.scala`, with its reason: today upickle
and ujson (the API's JSON values and codecs), and `OpenAIToolHelper`'s openai-java types, pending
[#2360](https://github.com/llm4s/llm4s/issues/2360). Being a source scanner, it does not resolve a name that
arrives through a wildcard import or an `export`, expand a type alias declared elsewhere, or see an inferred
result type; the limits are listed in the scanner's Scaladoc. Its fixtures include one violating signature per
deny-listed package, so dropping one from the check fails the build.

And it enforces [RFC 0002](../rfcs/#index), growth safety ([#2369](https://github.com/llm4s/llm4s/issues/2369)):
it fails for a `case class` in a `@Stable` type (top-level or nested in a `@Stable` type or its companion, and also
for an untiered public one) whose primary constructor is not `private` or `private[x]`, naming the file, line and
type, and for a public `copy` defined in one. Adding a field to a public constructor or `copy` changes its
signature, which MiMa reports as a break once the baseline exists, so a growth-prone type is written so that a
field can be added later:

```scala
@Stable
final case class Limits private (maxTokens: Int, timeout: FiniteDuration):
  def withMaxTokens(n: Int): Limits = copy(maxTokens = n) // copy is private, as the constructor is
  def withTimeout(t: FiniteDuration): Limits = copy(timeout = t)

object Limits:
  def apply(maxTokens: Int = 1024, timeout: FiniteDuration = 30.seconds): Limits = new Limits(maxTokens, timeout)
```

To add a field after the baseline, add it to the constructor and, with a default, to `apply`; keep the previous
`apply` as an overload without defaults that forwards to the new one; and add a `withX` setter. Never re-expose
`copy`. A case class with no fields is exempt. A type that is deliberately closed (a single-value wrapper, a fixed
record) goes on the allow-list, `GrowthExempted` in `project/StabilityTiers.scala`, by fully qualified name with a
one-line reason; the entries marked `TODO(#2369)` predate the check and are to be converted before the baseline.
An entry that no longer matches a failing type fails the check, so a converted type cannot leave its entry behind.
The scanner does not look at the cases of a `@Stable` `enum`.

`scripts/check-tier-drift.sh` (also a CI quick check, and it needs no sbt) keeps this page, the tiers in
[1.0 Scope](v1-scope) and the lists in `build.sbt` from drifting apart: the modules that call `mimaFrozen`
are the frozen set, and every other place that states it must agree. See the script's header for the exact
comparisons, and mark a Package Map row `tier-drift: ignore` to opt it out.

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

The baseline is per module. `mimaBaselineVersion` in `build.sbt` names the release the frozen modules
are compared with, and `mimaBaselineOverrides` gives a module a different one: `"llm4s-<name>" -> Some("X.Y.Z")`
compares it with its own release, `"llm4s-<name>" -> None` holds it back with no baseline while the others
have one. A module not in the map uses `mimaBaselineVersion`. Today `mimaBaselineVersion` is `None` and the
map is empty, so no frozen module has a baseline, `sbt mimaReportBinaryIssues` checks nothing and CI passes.

No release so far has set one. 0.5.0 and 0.5.1 are the first releases with the split coordinates, and were
published without a baseline. 0.4.1 and earlier are a single `llm4s-core` of the pre-modularisation code, so
they are not usable baselines for the split modules: the packages they hold have since moved to other
artifacts, and `llm4s-agent` and the provider modules do not exist there. The baseline is set at a pre-1.0
release, version to be decided, once the designs are final
([#1281](https://github.com/llm4s/llm4s/issues/1281)); until then nothing is frozen.
[RFC 0001](../rfcs/0001-agent-baseline) proposes that `llm4s-agent` takes a later baseline than the other
frozen modules; the per-module baseline makes either answer a one-line change.

### Setting and bumping the baseline

`mimaBaselineVersion` and `mimaBaselineOverrides` are plain `val`s, not sbt settings, so they are edited in
the file; they cannot be changed with `set`:

1. Publish the release and confirm every frozen artifact is on Maven Central under its `_3` name, for
   example `https://repo1.maven.org/maven2/org/llm4s/llm4s-agent_3/X.Y.Z/`. A frozen module whose
   baseline is not published fails `mimaPreviousClassfiles` with a "not found" error, by design.
2. Change the line to `val mimaBaselineVersion: Option[String] = Some("X.Y.Z")`. To give one module a
   different baseline, add an entry to `mimaBaselineOverrides` instead (or as well), one per line:

   ```scala
   val mimaBaselineOverrides: Map[String, Option[String]] = Map(
     "llm4s-agent" -> None // no baseline yet; later Some("X.Y.Z"), its own release
   )
   ```

   `scripts/mima-dry-run.sh --list-frozen` prints each frozen module's baseline (`shared`, `none` or its own
   version) as the dry run reads it, and refuses an entry for a module that is not frozen.
3. Run `sbt mimaReportBinaryIssues`. Fix real breaks. Add a filter (below) only for an intentional one.
4. When the baseline later moves to a newer release, **delete every `mimaBinaryIssueFilters` entry**
   first: each one excuses a break against the old baseline, so against the new one it is stale and would
   silently hide a real break in the same class. Then re-run the check and re-add only what the new baseline needs.
5. Update this page and the CHANGELOG entry if the covered module list changes.

Before the first run against the baseline, review the Beta parts that ship inside frozen modules, because
[1.0 Scope](v1-scope) does not freeze them: `org.llm4s.assistant.*` (`llm4s-agent`) and the Mistral and
Cohere dialect classes (`llm4s-openai-compatible`). Either exclude them with a documented filter or
decide that they are frozen too. `llm4s-agent` may also wait for the graph runtime
([#1266](https://github.com/llm4s/llm4s/issues/1266)) before it gets a baseline; if so, hold it back with
`"llm4s-agent" -> None` in `mimaBaselineOverrides` until then, and give it `Some("X.Y.Z")` once its API is
final. It stays a frozen module meanwhile: the stability, dependency and tier checks keep covering it.

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
`sbt agent/mimaReportBinaryIssues`. Until a baseline is set, `scripts/mima-dry-run.sh` checks that this wiring
works (see [Release readiness](release#release-readiness-a-mima-dry-run)).
