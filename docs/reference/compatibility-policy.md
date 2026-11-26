---
layout: page
title: Compatibility and Deprecation Policy
description: "What you can rely on when upgrading LLM4S: the promise by stability tier, versioning, how an API is deprecated and removed, and supported platforms."
search_title: "LLM4S compatibility and deprecation policy"
parent: Reference
nav_order: 14
cites_closed_issues: [1126, 1493, 1582, 2785]
---

# Compatibility and Deprecation Policy

What you can rely on when you upgrade LLM4S, and how the project changes an API without breaking you.
Which packages and modules fall under which tier is defined in [1.0 Scope](v1-scope); how binary
compatibility is checked is in [API Stability](api-stability). This page is the promise those two serve. Which
providers support what, and which JDK, Scala, Spring Boot and other versions each release is built and tested with, is
in the generated [Compatibility Matrix](compatibility-matrix).

---

## The promise, by tier

| Tier | What you can rely on |
|---|---|
| **Frozen at 1.0** | Source and binary compatible within 1.x. An API is removed only after it has been deprecated, and only in a major release. |
| **Beta** | Usable, but the API may change in a minor release. A migration note ships in that release's CHANGELOG. |
| **Experimental** | A prototype or advanced feature: expect changes. Same migration-note rule as Beta. |

The Frozen modules are `llm4s-core`, `llm4s-agent`, `llm4s-openai`, `llm4s-openai-compatible`,
`llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama`; everything else is Beta or Experimental, or is not
published. [1.0 Scope](v1-scope) is the source of truth for the list. A Frozen module can hold Experimental parts:
in `llm4s-agent` only a small core is `@Stable` - building and running an agent, its results, handoffs, run ids and
settings, and attaching guardrails - and the graph runtime, checkpointers, events and streaming, middleware, tools,
human review, delegation, the harness, the run queue and worker, encryption and the other guardrails are
`@Experimental`, so they may change in a 1.x minor release ([#2785](https://github.com/llm4s/llm4s/issues/2785)). Each public type's tier is its `@Stable` or `@Experimental` annotation, shown in the
[Scaladoc](/scaladoc/).

---

## Versions

The build declares `versionScheme := early-semver`, so published POMs tell resolvers how to read a
version number:

| Release | Frozen modules |
|---|---|
| **Before the baseline** (up to 0.7.0) | Still stabilising: any release, patch included, could break source or binary compatibility. Each break is listed under the CHANGELOG's *Breaking changes* with a migration note, and shipped with no `@deprecated` shim. |
| **From the baseline to 1.0** (0.7.0 to 1.0.0) | 1.0.0 was the next release after 0.7.0. A frozen API found wrong in between was fixed in 1.0.0, with a line under the CHANGELOG's *Breaking changes* and a migration note, and no shim or `@deprecated` member; a binary break would also have needed a narrow MiMa filter, and there was none. A 0.7.x patch release makes no binary-breaking change. |
| **1.0.0 and later** | Semantic versioning: a breaking change needs a new major version, after a deprecation ([Deprecating and removing](#deprecating-and-removing)). |

1.0.0 is the compatibility promise, and it is released. The 0.7.0 baseline was not the promise: it made every break
visible, because MiMa reported it and it needed a reviewed filter, but between 0.7.0 and 1.0.0 an API that was wrong
was still fixed outright. The release sequence was 0.6.0, then 0.7.0 (the baseline for all seven frozen modules), then
1.0.0 with no release in between ([After the baseline](api-stability#after-the-baseline)); 0.5.0, 0.5.1 and 0.6.0 set
no baseline. The build compares every frozen module with its baseline artifact on each pull request: 1.0.0, with no
filter (it was 0.7.0 until 1.0.0 was on Maven Central). See
[The Baseline](api-stability#the-baseline).

Every published module has the same version, from one tag, so a provider module's version number
follows the release it ships in, and `llm4s-bom` pins that one version for every artifact.

---

## What the promise covers

For a Frozen module:

- **Public types and members.** Names, signatures, packages and the artifact that holds them. A rename,
  a removed member, a narrowed visibility, a changed parameter or return type, and a new abstract member on
  a trait that code outside this repository implements, are all breaking.
- **The provider-author SPI.** `org.llm4s.llmconnect.spi` and the helper types listed under
  [Stability](../guide/writing-a-provider#stability) in the provider guide: a provider compiled against
  1.0 keeps working across 1.x.
- **The error model.** The `LLMError` types and their fields. The *text* of an error message is not part
  of the promise, and neither is a log line. New error types are added
  ([Sealed types that may gain a case](#sealed-types-that-may-gain-a-case)).
- **Configuration.** The documented `application.conf` keys and the environment variables bound to them.
  A renamed key keeps its old name as an alias for the rest of the major version, with a warning that names the key
  to rename it to. (Before 1.0 a key was renamed outright, with a migration note.)

What it does **not** cover:

- Beta and Experimental modules, and anything in a Frozen module that [1.0 Scope](v1-scope) marks Beta or
  Experimental. Such a type inside a Frozen module is `@Experimental`, which MiMa skips (`mimaExcludeAnnotations`);
  anything else gets a documented MiMa filter (none has been needed).
- Anything `private` or `private[llm4s]`. The latter is internal, may change in any release, and cannot be
  reached from your package; do not declare your code in `org.llm4s` to get around that.
- `llm4s-provider-testkit` and `llm4s-agent-testkit` (Beta; they require JDK 21, see below), `llm4s-testkit` (Beta), the
  samples, the workspace runner, `modules/it` and the benchmarks.
- Bug fixes. A fix that changes behaviour to match what the documentation or the types already promised is
  not a compatibility break; it is listed under *Fixed* in the CHANGELOG.
- A positional pattern on a growth-safe class, one built with its companion `apply` and `with*` setters whose
  constructor is private (`OpenAIConfig`, `ModelMetadata`, `CompletionOptions`, ...). Such a class gains fields in
  minor releases, and a pattern that binds its fields by position stops compiling when it does: since 1.0.0,
  `OpenAIConfig` has gone from 11 fields to 14 and `ModelMetadata` from 8 to 9. Each gain is listed
  in the [Migration Guide](migration), and `Llm4sMigrateFrom_1_0` rewrites such a pattern
  ([Scalafix](scalafix#one-umbrella-rule-per-starting-version)). Read the fields by name to avoid it. This
  exclusion was introduced with 1.1.0; the policy as published at 1.0.0 did not state it.
- Which provider endpoint a client calls by default. A minor release may change it, as 1.1.0 moves an `openai`
  client on api.openai.com to the Responses API ([Migration Guide](migration)); such a change is announced with a
  *Breaking changes* line in the CHANGELOG and comes with a setting that restores the previous behaviour (there,
  `api = "chat-completions"`).

---

## Changing a Frozen API without breaking it

These are the rules the codebase follows so a minor release does not break anyone.

- **Add a field to a data type** (`CompletionOptions`, `Completion`, `ModelCapabilities`, and the other
  growth-prone types, which are `final case class X private (...)` with a public companion `apply`):
  add it to the constructor and, with a default, to `apply`; add a `withX` setter. Also keep the previous `apply`
  as an overload *without* defaults that forwards to the new one. Never re-expose `copy`.
- **Add a member to a trait that others implement** (the SPI traits, `LLMClient`, `TracingBackend`):
  give it a default implementation. An abstract one breaks every implementer.
- **Add a parameter to a method:** add an overload and deprecate the old one; do not change the signature in
  place. See [Deprecating and removing](#deprecating-and-removing).
- **Express a duration** as a `FiniteDuration` and a point in time as an `Instant`, never a raw number
  with its unit in the name; a wire format keeps its units and converts at the boundary.
- **Keep a helper only llm4s modules use `private[llm4s]`.** Public is a promise.
- **Add a case to a sealed type or enum** only where
  [Sealed types that may gain a case](#sealed-types-that-may-gain-a-case) lists the type. A case with fields is a
  `final case class X private` with a companion `apply`, so the case itself can grow.

A change that cannot avoid breaking binary compatibility follows
[Adding a Binary-Incompatible Change](api-stability#adding-a-binary-incompatible-change): it is a major-release
change, after a deprecation. (Between the baseline and 1.0.0 such a change would have shipped in 1.0.0, with a narrow
`ProblemFilters.exclude` entry that says why; none was needed.)

---

## Sealed types that may gain a case

A new kind of content, event, status or error is added as a new case of a sealed type or enum. That is binary
compatible, and it is **not** treated as a break: the Stable types below may gain a case in a minor release, 1.x
included. A match on one of them therefore needs a default case (`case _ =>` or `case other =>`): without it the
compiler reports the match as not exhaustive after an upgrade, which fails a build that makes warnings fatal, and a
value of the new case throws a `MatchError`. Removing or renaming a case is a break and follows
[Deprecating and removing](#deprecating-and-removing), like any other member.

| Area | Types that may gain a case |
|---|---|
| Messages and requests | `ContentPart`, `ThinkingBlock`, `Thinking`, `ReasoningEffort`, `ResponseFormat`, `Modality`, `InputPurpose`, `RequestAdjustment`, `RequestOption` |
| Errors | `LLMError`'s hierarchy (the trait is open: a new error type can appear in any minor release, and `LLMError.isRetryable` says how to treat one you do not know), `ToolCallError`, `ToolParameterError`, `RateLimitOrigin` |
| Tools | `SchemaDefinition`, `ToolParameterType` |
| Tracing and metrics | `TraceEvent`, `ErrorKind`, `ProviderExchangeOutcome` |
| Models and configuration | `ModelMode`, `ModelRegistrySource`, `ApiKeySource`, `IdentitySource`, `ProviderCollisionKind`, `OpenAIApi`, `OpenAIReasoningSummary` |
| HTTP | `HttpBody`, `MultipartPart`, `HttpMethod`: an `Llm4sHttpClient` of your own answers one it does not know with a `Left`, never a `MatchError` |
| Agents | `AgentStatus`, `RunStatus` |

`StreamEvent` and the `AgentEvents` extractors are Experimental, so the promise does not cover them at all, but the
same advice holds: an agent gains event kinds as it gains features, and a listener ignores the ones it does not
know.

Some types are already open, with a case that carries a value llm4s does not know: `FinishReason.Other(raw)` and
`FinishReason.Unknown`, `ServiceTier.Other(raw)`, `TracingMode.Named(name)`. A new *named* case can still be added
beside it, so a match on these needs its default case too; the value a provider sends keeps meaning what it meant.

A Stable sealed type or enum that is not listed here is closed for 1.x: `Message` and `MessageRole` (a conversation
has four roles), `Outcome`, `ProviderExchangeLogging`, `CircuitState` and `RetryPolicy`. Adding a case to one of
those is a breaking change.

---

## Deprecating and removing

**Before 1.0** nothing was frozen: a Frozen-at-1.0 API that was wrong was fixed outright, with a CHANGELOG entry
and a migration note, and no `@deprecated` member; from the 0.7.0 baseline each such break also needed a MiMa
filter. The [migration guide](migration) records each one. A Frozen module still gains no speculative public types.

**From 1.0**, a Frozen API changes this way:

1. **Deprecate** with `@deprecated("use <replacement>", since = "<version>")`. The message names the
   replacement and the `since` names the release that introduced the deprecation, so a compiler warning
   tells you what to change and since when.
2. **Record it** in the CHANGELOG under *Deprecated* in the same release, with the replacement.
3. **Keep it** for the rest of the major version. A Frozen API is never removed in a minor or patch
   release; it can be removed in the next major release, and only if an earlier release deprecated it.
4. **Beta and Experimental** APIs may change or be removed in a minor release without a deprecation
   period, with a migration note in the same release's CHANGELOG.

---

## Supported platforms

Which releases get fixes, and for how long, is in the [Support Policy](support-policy).

1.0 targets **Scala 3 only (3.7.1)**; Scala 2.13 support is deferred to after 1.0 and, if it happens,
will target the frozen spine rather than the full tree. Calling LLM4S from Scala 2.13 through the TASTy reader is
best effort: CI probes it, but it is not a supported configuration and there are no 2.13 artifacts
([the TASTy reader guide](../getting-started/scala-2-13)). The minimum JDK is 21 and CI runs only JDK 21; see [Scala and JDK support](v1-scope#scala-and-jdk-support).
`llm4s-provider-testkit` in particular needs it: its interruption checks and `LocalProviderTestServer` run on virtual threads
([#1582](https://github.com/llm4s/llm4s/issues/1582)), and so does `llm4s-agent-testkit`, whose
contract cases run on virtual threads. Every artifact is compiled to JDK 21 bytecode, and none has a lower floor
([#1493](https://github.com/llm4s/llm4s/issues/1493)).

Both are part of what you build against: an older Scala 3 compiler cannot read the TASTy of a library
built with a newer one, and a newer JDK's class files do not load on an older JVM. So the Scala 3 minor
series and the minimum JDK are raised only in a **minor** release, never a patch release, and each raise
is announced in the CHANGELOG under *Changed* with a migration note. See
[1.0 Scope](v1-scope#scala-and-jdk-support) and the
[programme issue](https://github.com/llm4s/llm4s/issues/1126) for the reasoning.

The artifacts are **classpath libraries**. They are not JPMS modules or OSGi bundles: no jar has a
`module-info.class`, an `Automatic-Module-Name` or OSGi headers. This follows from the layout. Code that moves to
another artifact keeps its `org.llm4s.*` package, so packages are split across artifacts by design:
`org.llm4s.llmconnect.provider`, for one, has classes in `llm4s-core` and in more than a dozen provider modules. The
module path does not allow two modules to hold the same package, so put the jars on the classpath.

---

## Where each change is recorded

| What | Where |
|---|---|
| Every user-visible change, including each deprecation and removal | [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) |
| How to move from one release to the next | [Migration Guide](migration) |
| Which module is Frozen, Beta or Experimental | [1.0 Scope](v1-scope) |
| How binary compatibility is checked and filtered | [API Stability](api-stability) |
