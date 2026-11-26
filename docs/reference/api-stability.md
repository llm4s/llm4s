---
layout: page
title: API Stability
description: "How LLM4S checks binary compatibility with MiMa: stability tiers in the code, which modules are covered and how the baseline is set."
search_title: "LLM4S API stability and binary compatibility (MiMa)"
parent: Reference
nav_order: 12
cites_closed_issues: [1266, 1281, 1818, 2357, 2369, 2778, 2781, 2785]
cites_issues: [2835]
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
| **Before the baseline** (up to 0.7.0) | Still stabilising: any release, patch included, could break, with a CHANGELOG entry and a migration note and no `@deprecated` shim |
| **From the baseline to 1.0** (0.7.0 to 1.0.0) | 1.0.0 was the next release after 0.7.0. A frozen API found wrong in between was fixed in 1.0.0, with a CHANGELOG *Breaking changes* line and a migration note, and no shim or `@deprecated` member (a binary break would also have needed a MiMa filter; there was none); a 0.7.x patch release makes no binary-breaking change in a frozen module ([After the baseline](#after-the-baseline)) |
| **1.0.0 and later** | Full SemVer: a MAJOR version for breaking changes, after a deprecation |

The 0.7.0 baseline made every break visible and reviewed; the compatibility promise started at 1.0.0, which is released
and is the baseline now.

One kind of change is not a break in any of those rows: a Stable sealed type or enum that the compatibility policy
lists under [Sealed types that may gain a case](compatibility-policy#sealed-types-that-may-gain-a-case) (`ContentPart`,
`TraceEvent`, `AgentStatus`, `ThinkingBlock`, `RequestAdjustment`, `ToolCallError`, `LLMError`'s hierarchy and the
others named there) may gain a case in a minor release. MiMa accepts a new case, so nothing in the build stops one;
the list is what bounds it. A match on one of those types needs a default case. Removing or renaming a case is a
break, and MiMa reports it.

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
module that has neither annotation or both, and for a type of a Beta part that lives inside a frozen module
and is not `@Experimental`: the Mistral, Cohere and xAI dialects of `llm4s-openai-compatible` and
`org.llm4s.assistant` in `llm4s-agent`, listed in `build.sbt` (`stabilityExperimentalFiles`; an entry that matches no
file fails the check too). It also fails for a public member of a `@Stable` type whose signature names an
`@Experimental` type - a `def`, `val`, enum `case`, nested type or constructor parameter, unless it is `private`,
`protected` or itself `@Experimental` - because renaming or reshaping the Experimental type would then break the
Stable signature ([#1818](https://github.com/llm4s/llm4s/issues/1818)). Promote the type, or keep the member out of
the Stable surface. It runs its own fixtures first (`StabilityTiers.selfTest`), so a change that stops it finding
such a leak fails the build. It reads sources, not classes, because Scala compiles `private[llm4s]` to a public
bytecode member. It covers every module in 1.0 Scope's Frozen-at-1.0 tier (`stabilityTierModules` in `build.sbt`):
`llm4s-core`, `llm4s-openai`, `llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini`, `llm4s-ollama` and
`llm4s-agent` ([#2778](https://github.com/llm4s/llm4s/issues/2778)). `llm4s-agent` has a small Stable core
([#2785](https://github.com/llm4s/llm4s/issues/2785)), so that the runtime underneath can keep improving in 1.x:

| Package | `@Stable` |
|---|---|
| `org.llm4s.agent` | `Agent`, `AgentBuilder`, `AgentResult`, `AgentStatus`, `AgentRun`, `Handoff`, `AgentId` |
| `org.llm4s.agent.graph` | `ThreadId`, `RunId`, `InterruptId`, `NodeId`, `TenantId`, `Principal`, `RunConfig`, `RunBudgets`, `RetentionPolicy`, `RunStatus` |
| `org.llm4s.agent.guardrails` | `Guardrail`, `InputGuardrail`, `OutputGuardrail` |
| `org.llm4s.agent.guardrails.builtin` | `LengthCheck`, `ProfanityFilter`, `JSONValidator`, `RegexValidator` |

Everything else in the module is `@Experimental`: the graph runtime, checkpoints and the `Checkpointer` contract,
events and streaming, middleware (`GuardrailMiddleware` included), the tool and tool-loop APIs, delegation, the
harness, the run queue and worker (`agent.service`), encryption, metrics, the other guardrails and
`org.llm4s.assistant`. So are the members of the Stable types that name them: on `AgentBuilder`, `withToolSet`,
`withMiddleware`, `withHarness`, `withSubagents`, `withDelegation`, `withMaxPromptSectionTokens`, `withRuntime`,
`withStreaming` and `withInterruptBefore`/`After`; on `Agent`, `loop`, `delegation`, `children`, `subscribeChild`,
`abandon`, `resume`, `startResume`, `stream`, `streamResume` and `streamRecover`; on `AgentResult`, `approve`,
`reject`, `edit`, `reply` and `proceed`; `AgentRun.subscribe`; on `RunConfig`, `withInterruptBefore`/`After` and
`withChildRetention`; `Guardrail.validateIn(value, context)`; and the `AgentStatus.Suspended` case. Streaming, human
review and durable persistence are usable at 1.0, but not promised.

The same check enforces [RFC 0004](../rfcs/#index): a public member of a `@Stable` type must not expose a
third-party type, because freezing the signature would freeze the library behind it
([#2357](https://github.com/llm4s/llm4s/issues/2357)). It fails for a signature that names a type from
fansi, `cats`, pureconfig, `com.typesafe.config`, jtokkit, slf4j, OkHttp, Jackson, or the OpenAI, Anthropic or
AWS SDKs (`com.openai`, `com.anthropic`, `software.amazon`), written fully qualified or through a name the file
imports (including a renaming import such as `import com.typesafe.config.{Config => TsConfig}`). Use an llm4s or
Scala type instead, or keep the member out of the Stable surface. A third-party type that is API on purpose
goes on the allow-list, `ThirdPartyAllowed` in `project/StabilityTiers.scala`, with its reason: today upickle
and ujson (the API's JSON values and codecs). Being a source scanner, it does not resolve a name that
arrives through a wildcard import or an `export`, expand a type alias declared elsewhere, or see an inferred
result type; the limits are listed in the scanner's Scaladoc. Its fixtures include one violating signature per
deny-listed package, so dropping one from the check fails the build.

And it enforces [RFC 0002](../rfcs/#index), growth safety ([#2369](https://github.com/llm4s/llm4s/issues/2369)):
it fails for a `case class` in a `@Stable` type (top-level or nested in a `@Stable` type or its companion, and also
for an untiered public one) whose primary constructor is not `private` or `private[x]`, naming the file, line and
type, and for a public `copy` defined in one. Adding a field to a public constructor or `copy` changes its
signature, which MiMa reports as a break, so a growth-prone type is written so that a
field can be added later:

```scala
@Stable
final case class Limits private (maxTokens: Int, timeout: FiniteDuration):
  def withMaxTokens(n: Int): Limits = copy(maxTokens = n) // copy is private, as the constructor is
  def withTimeout(t: FiniteDuration): Limits = copy(timeout = t)

object Limits:
  def apply(maxTokens: Int = 1024, timeout: FiniteDuration = 30.seconds): Limits = new Limits(maxTokens, timeout)
```

To add a field, add it to the constructor and, with a default, to `apply`, and add a `withX` setter. Also keep the
previous `apply` as an overload without defaults that forwards to the new one: the baseline holds its
signature, and MiMa reports its removal. (Up to 0.7.0 there was one `apply` per type, so the 0.7.0 baseline froze no
overload.) Never re-expose
`copy`. A case class with no fields is exempt. A type that is deliberately closed (a single-value wrapper, a fixed
record) goes on the allow-list, `GrowthExempted` in `project/StabilityTiers.scala`, by fully qualified name with a
one-line reason. Every type that predated the check has been converted or given its reason (#2377 to #2384).
An entry that no longer matches a failing type fails the check, so a converted type cannot leave its entry behind.

An `enum` case with fields is held to the same rule ([#2781](https://github.com/llm4s/llm4s/issues/2781)):
`case Done(output: String)` compiles to a case class with a public constructor, `apply` and `copy`, and is matched
positionally, so it cannot gain a field either. (A growth-safe class gains fields in a minor release; a positional
pattern on one is outside the source promise, see [What the promise covers](compatibility-policy#what-the-promise-covers),
but its constructor and `copy` stay private, which an enum case's cannot.) A case cannot have a private constructor and a companion `apply`, so
a case that may grow holds one growth-safe class, and is read by name through it:

```scala
@Stable
enum AgentStatus:
  case Completed(answer: String)                       // closed: exactly the answer, listed in GrowthExempted
  @Experimental case Suspended(suspension: Suspension) // may grow: `final case class Suspension private (...)`

status match
  case AgentStatus.Suspended(suspension) => suspension.approvals
```

Every case with fields of a `@Stable` enum is on the allow-list with its reason: a wrapper as "holding only its
growth-safe" payload, a closed case (a single value, an error or outcome case whose fields are what it is, a stored
form whose derived codec is a wire format) as what it is exactly. A case of an `@Experimental` enum, or one marked
`@Experimental` itself, is not checked and has no entry: `AgentStatus.Suspended` is one
([#2785](https://github.com/llm4s/llm4s/issues/2785)), and holds a `Suspension` all the same, so that it can be
promoted without changing shape.

`scripts/check-tier-drift.sh` (also a CI quick check, and it needs no sbt) keeps this page, the tiers in
[1.0 Scope](v1-scope) and the lists in `build.sbt` from drifting apart: the modules that call `mimaFrozen`
are the frozen set, and every other place that states it must agree. See the script's header for the exact
comparisons, and mark a Package Map row `tier-drift: ignore` to opt it out.

MiMa skips a type annotated `@Experimental` inside a frozen module: `mimaFrozen` in `build.sbt` adds
`mimaExcludeAnnotations += "org.llm4s.annotation.Experimental"`, so such a type needs no `ProblemFilters.exclude`
entry (a `@Stable` type is still checked). `scripts/mima-dry-run.sh` proves both on every run; see
[Release readiness](../contribute/release#release-readiness-a-mima-dry-run).

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
Experimental needs a `ProblemFilters.exclude` entry that says why. There are none: the 0.7.0 baseline was set
without a filter, and so was the 1.0.0 one (see [The Baseline](#the-baseline)).

An `@Experimental` *member* of a Stable type is skipped only partly, because MiMa 1.1 reads a Scala 3 member's
annotations from TASTy, for methods alone, and by name ([#2785](https://github.com/llm4s/llm4s/issues/2785)):

- an `@Experimental def` is skipped, and so is **every overload of the same name**, Stable or not, so an
  Experimental method never shares its name with a Stable one: `AgentBuilder.withToolSet` beside the Stable
  `withTools(registry)`, `Guardrail.validateIn(value, context)` beside the Stable `validate(value)`
  ([#2835](https://github.com/llm4s/llm4s/issues/2835));
- an `@Experimental val` is still checked: write it as a `def`;
- an `@Experimental` enum `case` (`AgentStatus.Suspended`) may change shape freely, but removing it is reported once,
  as the companion's field (`MissingFieldProblem`), which needs a filter.

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
have one. A module not in the map uses `mimaBaselineVersion`. Today `mimaBaselineVersion` is `Some("1.0.0")` and the
map is empty, so every frozen module is compared with its 1.0.0 artifact: `sbt mimaReportBinaryIssues`, which CI
runs on every pull request, fails on a binary-incompatible change to one. There is no filter. The baseline was 0.7.0
until 1.0.0 was on Maven Central, and 1.0.0 is binary compatible with 0.7.0 in the frozen modules
([Setting and bumping the baseline](#setting-and-bumping-the-baseline)).

No earlier release is a baseline. 0.5.0, 0.5.1 and 0.6.0, the first releases with the split coordinates, were
published without one, while the designs were still changing. 0.4.1 and earlier are a single `llm4s-core` of the pre-modularisation code, so
they are not usable baselines for the split modules: the packages they hold have since moved to other
artifacts, and `llm4s-agent` and the provider modules do not exist there. The first baseline is **0.7.0**, for every
frozen module ([#1281](https://github.com/llm4s/llm4s/issues/1281)); it was set once 0.7.0 was on Maven Central.
`llm4s-agent` takes the same baseline as the other frozen modules: [RFC 0001](../rfcs/0001-agent-baseline), which
proposed a later one, was rejected once the graph runtime ([#1266](https://github.com/llm4s/llm4s/issues/1266)) was complete.

### After the baseline

The release after 0.7.0 was 1.0.0, with no minor release and no release candidate between them; 1.0 did not wait
for the baseline to survive a number of releases. CI compared every frozen module on `main` with 0.7.0, so a break in
the baselined surface was reported on the pull request that made it. A break found necessary before 1.0.0 was still
made: the API was fixed outright, and the break recorded as a narrow MiMa filter with its reason, a line under the
CHANGELOG's *Breaking changes* and a note in the [Migration Guide](migration). None was needed: `build.sbt` has no
filter at 1.0.0, so nothing in the frozen modules changed binary-incompatibly between 0.7.0 and 1.0.0. The one
frozen API fixed in between, [an error's `provider` is a `ProviderId`](migration#an-errors-provider-is-a-providerid-2936),
breaks source only: `ProviderId` is an opaque type over `String`. A 0.7.x patch release, cut only for a critical
security fix, makes no binary-breaking change in a frozen module.

From 1.0.0 a binary break to a Stable API waits for a major release
([Adding a Binary-Incompatible Change](#adding-a-binary-incompatible-change)). The baseline moved to 1.0.0 once
that was on Maven Central, so every 1.x release is compared with 1.0.0.

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

The Beta parts that ship inside frozen modules are left out of the baseline, because [1.0 Scope](v1-scope)
does not freeze them ([#2835](https://github.com/llm4s/llm4s/issues/2835)): `org.llm4s.assistant.*` (`llm4s-agent`)
and the Mistral, Cohere and xAI dialects (`llm4s-openai-compatible`). No filter lists them. Every one of their
top-level types is `@Experimental`, which `stabilityTierCheck` enforces for the files `stabilityExperimentalFiles` in
`build.sbt` names, and MiMa skips an `@Experimental` class together with its companion and every class nested in it
(`mimaExcludeAnnotations` reads the annotation along the class's chain of enclosing classes). A Beta part added to a
frozen module later joins that list. A module that must miss the shared baseline is held back with
`"<module>" -> None` in `mimaBaselineOverrides` and given `Some("X.Y.Z")` later; it stays a frozen module
meanwhile, so the stability, dependency and tier checks keep covering it. No module is planned to: `llm4s-agent`
takes the shared baseline ([RFC 0001](../rfcs/0001-agent-baseline), rejected).

The build is Scala 3 only, so one `sbt mimaReportBinaryIssues` covers every artifact. If a second Scala
version returns, CI must run it cross-built (with sbt's `+` prefix) to check each version.

---

## Adding a Binary-Incompatible Change

From 1.0.0 a binary break to a `@Stable` API of a frozen module ships only in a major release, after the old API
has been deprecated ([Deprecating and removing](compatibility-policy#deprecating-and-removing)). In a 1.x release,
add the new API beside the old one, which stays as a `@deprecated` member pointing to it until the next major
release; that needs no filter. A change that still breaks binary compatibility with the baseline (a major release's
removal, or a break in something the promise does not cover that MiMa does not skip) needs:

1. A `ProblemFilters.exclude` entry in the module's `mimaBinaryIssueFilters`, with a comment saying why the break is intentional
2. A line under `### Breaking changes` in `CHANGELOG.md`'s `[Unreleased]`, and a note in the [Migration Guide](migration)
3. For a Stable API: an earlier release that deprecated it. (Between the 0.7.0 baseline and 1.0.0 there was no
   `@deprecated` member and no shim: the API was fixed outright.)

`ProblemFilters` is not in sbt's default imports, so `build.sbt` needs
`import com.typesafe.tools.mima.core._` at the top before a filter compiles.

```scala
// build.sbt, in the module's settings
mimaBinaryIssueFilters ++= Seq(
  // Agent.run lost its deprecated overload (2.0.0); migration note: "Agent.run takes ...".
  ProblemFilters.exclude[DirectMissingMethodProblem]("org.llm4s.agent.Agent.run")
)
```

Use the narrowest problem type and the narrowest name that matches, not `[Problem]` with a wildcard.

---

## Checking Compatibility Locally

```bash
sbt mimaReportBinaryIssues
```

This reports binary incompatibilities between the current code and the baseline release (1.0.0). A module
with no baseline (every module outside the frozen set) logs `mimaPreviousArtifacts not set` (or `is empty`)
and is skipped. For a frozen module, success means its API is compatible with the baseline, and failure lists each
problem with the `ProblemFilters.exclude` line that would silence it. To check one module, run
`sbt agent/mimaReportBinaryIssues`. `scripts/mima-dry-run.sh` checks the wiring itself, against a throwaway
baseline: that a baseline resolves, that the check can fail and that `@Experimental` types are skipped (see [Release readiness](../contribute/release#release-readiness-a-mima-dry-run)).
