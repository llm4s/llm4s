---
layout: page
title: Compatibility and Deprecation Policy
parent: Reference
nav_order: 14
---

# Compatibility and Deprecation Policy

What you can rely on when you upgrade LLM4S, and how the project changes an API without breaking you.
Which packages and modules fall under which tier is defined in [1.0 Scope](v1-scope); how binary
compatibility is checked is in [API Stability](api-stability). This page is the promise those two serve.

---

## The promise, by tier

| Tier | What you can rely on |
|---|---|
| **Frozen at 1.0** | Source and binary compatible within 1.x. An API is removed only after it has been deprecated, and only in a major release. |
| **Beta** | Usable, but the API may change in a minor release. A migration note ships in that release's CHANGELOG. |
| **Experimental** | A prototype or advanced feature: expect changes. Same migration-note rule as Beta. |

The Frozen modules are `llm4s-core`, `llm4s-agent`, `llm4s-openai`, `llm4s-openai-compatible`,
`llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama`; everything else is Beta or Experimental, or is not
published. [1.0 Scope](v1-scope) is the source of truth for the list.

---

## Versions

The build declares `versionScheme := early-semver`, so published POMs tell resolvers how to read a
version number:

| Release | Frozen modules |
|---|---|
| **Patch** (0.x.y to 0.x.z) | No binary-breaking change. |
| **Minor** (0.x to 0.y) | A binary-breaking change is allowed, with a `@deprecated` path where one exists, and is listed in the CHANGELOG. |
| **1.0.0 and later** | Semantic versioning: a breaking change needs a new major version. |

Binary compatibility is checked by MiMa against a baseline of **0.5.0**, the first release with the
split coordinates. Until the baseline is set the check passes without checking anything; see
[The Baseline](api-stability#the-baseline).

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
  A renamed key keeps working as a deprecated alias that logs a warning naming the key to rename it to
  (`deprecatedAliases` on the provider's config spec); which release removes the alias is stated in the
  CHANGELOG when the key is renamed.

What it does **not** cover:

- Beta and Experimental modules, and anything in a Frozen module that [1.0 Scope](v1-scope) marks Beta or
  Experimental. Such a type inside a Frozen module is `@Experimental`, which MiMa skips (`mimaExcludeAnnotations`);
  anything else gets a documented MiMa filter when the baseline is set.
- Anything `private` or `private[llm4s]`. The latter is internal, may change in any release, and cannot be
  reached from your package; do not declare your code in `org.llm4s` to get around that.
- `llm4s-provider-testkit` (Beta; it requires JDK 21, see below), the samples, the workspace runner,
  `modules/it` and the benchmarks.
- Bug fixes. A fix that changes behaviour to match what the documentation or the types already promised is
  not a compatibility break; it is listed under *Fixed* in the CHANGELOG.

---

## Changing a Frozen API without breaking it

These are the rules the codebase follows so a minor release does not break anyone.

- **Add a field to a data type** (`CompletionOptions`, `Completion`, `ModelCapabilities`, and the other
  growth-prone types, which are `final case class X private (...)` with a public companion `apply`):
  add it to the constructor and, with a default, to `apply`; keep the previous `apply` as an overload
  *without* defaults that forwards to the new one; add a `withX` setter. Never re-expose `copy`.
- **Add a member to a trait that others implement** (the SPI traits, `LLMClient`, `TracingBackend`):
  give it a default implementation. An abstract one breaks every implementer.
- **Add a parameter to a method:** add an overload and deprecate the old one; do not change the
  signature in place.
- **Express a duration** as a `FiniteDuration` and a point in time as an `Instant`, never a raw number
  with its unit in the name; a wire format keeps its units and converts at the boundary.
- **Keep a helper only llm4s modules use `private[llm4s]`.** Public is a promise.

A change that cannot avoid breaking binary compatibility needs the steps in
[Adding a Binary-Incompatible Change](api-stability#adding-a-binary-incompatible-change): a
`@deprecated` version of the old API where possible, a narrow `ProblemFilters.exclude` entry that says
why, and a CHANGELOG entry. Before 1.0 that is a minor-release change; after it, a major one.

---

## Deprecating and removing

**Until the 0.5.0 baseline is set**, nothing is frozen: a Frozen-at-1.0 API that is wrong is fixed
outright, with a CHANGELOG entry and a migration note, not kept for compatibility. A Frozen module gains no speculative public types and no
`@deprecated` members: an API that should not be frozen is deleted, with a migration note, not deprecated.
The [migration guide](migration) records each such removal.

**After the baseline:**

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

1.0 targets **Scala 3 only (3.7.1)**; Scala 2.13 support is deferred to after 1.0 and, if it happens,
will target the frozen spine rather than the full tree. CI runs JDK 21. `llm4s-provider-testkit`
requires JDK 21: its interruption checks and `LocalProviderTestServer` run on virtual threads
([#1582](https://github.com/llm4s/llm4s/issues/1582)); the minimum JDK for the other artifacts is
decided in [#1493](https://github.com/llm4s/llm4s/issues/1493).

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
