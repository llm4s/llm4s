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
| **Before the baseline** (up to 0.7.0) | Still stabilising: any release, patch included, may break source or binary compatibility. Each break is listed under the CHANGELOG's *Breaking changes* with a migration note, and ships with no `@deprecated` shim. |
| **From the baseline to 1.0** (0.7.0 to 1.0.0) | A patch release makes no binary-breaking change. A minor release may still break a frozen API: each break has a narrow MiMa filter, a line under the CHANGELOG's *Breaking changes* and a migration note, and ships with no shim and no `@deprecated` member. |
| **1.0.0 and later** | Semantic versioning: a breaking change needs a new major version, after a deprecation ([Deprecating and removing](#deprecating-and-removing)). |

1.0 is the compatibility promise. The 0.7.0 baseline is not: it makes every break visible, because MiMa reports it
and it needs a reviewed filter, but between 0.7.0 and 1.0 an API that is wrong is still fixed outright. The release
sequence is 0.6.0, then 0.7.0 (the baseline for all seven frozen modules), then 1.0; 0.5.0, 0.5.1 and 0.6.0 set no
baseline, and until 0.7.0 the check passes without checking anything. See [The Baseline](api-stability#the-baseline).

Every published module has the same version, from one tag, so a provider module's version number
follows the release it ships in. The [modularisation programme](https://github.com/llm4s/llm4s/issues/1126)
aims for provider modules to version independently; that is not in place.

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
  of the promise, and neither is a log line.
- **Configuration.** The documented `application.conf` keys and the environment variables bound to them.
  Before 1.0 a key is renamed outright, with a migration note. From 1.0 a renamed key keeps its old name as an
  alias for the rest of the major version, with a warning that names the key to rename it to.

What it does **not** cover:

- Beta and Experimental modules, and anything in a Frozen module that [1.0 Scope](v1-scope) marks Beta or
  Experimental. Such a type inside a Frozen module is `@Experimental`, which MiMa skips (`mimaExcludeAnnotations`);
  anything else gets a documented MiMa filter when the baseline is set.
- Anything `private` or `private[llm4s]`. The latter is internal, may change in any release, and cannot be
  reached from your package; do not declare your code in `org.llm4s` to get around that.
- `llm4s-provider-testkit` and `llm4s-agent-testkit` (Beta; they require JDK 21, see below), `llm4s-testkit` (Beta), the
  samples, the workspace runner, `modules/it` and the benchmarks.
- Bug fixes. A fix that changes behaviour to match what the documentation or the types already promised is
  not a compatibility break; it is listed under *Fixed* in the CHANGELOG.

---

## Changing a Frozen API without breaking it

These are the rules the codebase follows so a minor release does not break anyone.

- **Add a field to a data type** (`CompletionOptions`, `Completion`, `ModelCapabilities`, and the other
  growth-prone types, which are `final case class X private (...)` with a public companion `apply`):
  add it to the constructor and, with a default, to `apply`; add a `withX` setter. From the baseline on, also keep
  the previous `apply` as an overload *without* defaults that forwards to the new one; before it, each type has one
  `apply`. Never re-expose `copy`.
- **Add a member to a trait that others implement** (the SPI traits, `LLMClient`, `TracingBackend`):
  give it a default implementation. An abstract one breaks every implementer.
- **Add a parameter to a method:** from 1.0 on, add an overload and deprecate the old one; do not change the
  signature in place. Before 1.0, change the signature, with a migration note (and, from the baseline on, a MiMa
  filter); see [Deprecating and removing](#deprecating-and-removing).
- **Express a duration** as a `FiniteDuration` and a point in time as an `Instant`, never a raw number
  with its unit in the name; a wire format keeps its units and converts at the boundary.
- **Keep a helper only llm4s modules use `private[llm4s]`.** Public is a promise.

A change that cannot avoid breaking binary compatibility follows
[Adding a Binary-Incompatible Change](api-stability#adding-a-binary-incompatible-change). Between the baseline and 1.0
it is a minor-release change: a narrow `ProblemFilters.exclude` entry that says why, a CHANGELOG *Breaking changes*
line and a migration note, with no `@deprecated` shim. From 1.0 it is a major-release change, after a deprecation.

---

## Deprecating and removing

**Before 1.0**, nothing is frozen: a Frozen-at-1.0 API that is wrong is fixed outright, with a CHANGELOG entry
and a migration note, not kept for compatibility. A Frozen module gains no speculative public types and no
`@deprecated` members: an API that should not be frozen is deleted, with a migration note, not deprecated. From the
0.7.0 baseline each such break also needs a MiMa filter, so it is visible and reviewed. The
[migration guide](migration) records each one.

**From 1.0:**

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

---

## Where each change is recorded

| What | Where |
|---|---|
| Every user-visible change, including each deprecation and removal | [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) |
| How to move from one release to the next | [Migration Guide](migration) |
| Which module is Frozen, Beta or Experimental | [1.0 Scope](v1-scope) |
| How binary compatibility is checked and filtered | [API Stability](api-stability) |
