---
layout: page
title: "RFC 0001: When llm4s-agent gets its MiMa baseline"
parent: RFCs
grand_parent: Reference
nav_order: 1
---

# RFC 0001: When `llm4s-agent` gets its MiMa baseline

- **Status:** Proposed
- **Author(s):** @kannupriyakalra
- **Tracking issue:** [#1281](https://github.com/llm4s/llm4s/issues/1281); depends on [#1266](https://github.com/llm4s/llm4s/issues/1266)

## Summary

[1.0 Scope](../reference/v1-scope) puts `org.llm4s.agent` (`llm4s-agent`) in the Frozen-at-1.0 tier, and
`modules/agent` already calls `mimaFrozen("llm4s-agent")`. The typed graph runtime (#1266) is still replacing the
agent's execution model, and [API Stability](../reference/api-stability#setting-and-bumping-the-baseline) leaves
open whether `llm4s-agent` takes the pre-1.0 baseline with the other frozen modules or waits for the runtime. This
RFC proposes that it **waits**: the other six frozen modules take the baseline when it is set, and `llm4s-agent` takes
its own once the runtime's public API is declared final.

## Motivation

- The baseline is meant to come "once the designs are final" (#1281). For `llm4s-core` and the five frozen provider
  modules the design is settled by the slice 6 cleanup passes. For `llm4s-agent` it is not: the runtime is being built
  in stages (the stage documents under [`docs/design/typed-agent-runtime/`](https://github.com/llm4s/llm4s/tree/main/docs/design/typed-agent-runtime)),
  and the `[Unreleased]` CHANGELOG already lists several breaking changes to it (checkpoint format, `Checkpointer`,
  `AgentStatus`, `RunEvent`).
- Baselining it early would turn each of those changes into a deprecation cycle or a MiMa filter. Either way the
  baseline would stop meaning "this is frozen" for the agent.
- Holding the other modules back for the agent would delay the compatibility promise for the parts users
  already depend on most: the client, the providers and the configuration.

## Detailed design

1. When `mimaBaselineVersion` is set, `llm4s-agent` is held back with `"llm4s-agent" -> None` in
   `mimaBaselineOverrides` ([#2355](https://github.com/llm4s/llm4s/issues/2355)), as
   [API Stability](../reference/api-stability#setting-and-bumping-the-baseline) already allows. It keeps its
   `mimaFrozen("llm4s-agent")` call, so the frozen-module checks keep covering it. The module stays in
   the Frozen-at-1.0 tier in [1.0 Scope](../reference/v1-scope) with a note that its baseline is pending.
2. The runtime's maintainers declare its public API final in #1266. A later release, still before 1.0, then becomes
   `llm4s-agent`'s baseline: its entry becomes `Some("X.Y.Z")`, a per-module baseline version, so the other
   modules' baseline does not move.
3. 1.0 still requires every frozen module, `llm4s-agent` included, to have "survived two releases after the
   baseline without a compatibility exception" (#1281). The agent's later baseline can therefore delay 1.0. That is
   the cost of this proposal, and it is stated here so that it is a decision, not a surprise.
4. The Beta parts inside `llm4s-agent` (`org.llm4s.assistant.*`) are handled when its baseline is set, as API
   Stability describes: a documented filter, or a decision that they are frozen too.

## Alternatives

- **Baseline `llm4s-agent` with the others.** One baseline and one clock for 1.0. But every remaining runtime change
  then needs a deprecation or a filter, which the runtime's pace makes likely to be frequent.
- **Hold the baseline for every module until the runtime is final.** Simple, but it delays the promise for the
  modules that are ready.
- **Move `llm4s-agent` to Beta for 1.0.** This removes the problem, but 1.0 would then make no promise about the
  agent API that most applications build on.

## Compatibility impact

- **Modules and tiers:** `llm4s-agent` only. Its tier is unchanged; only the timing of its MiMa check changes.
- **Binary / source / behaviour:** none. Nothing is frozen until a baseline is set.
- **Wire formats:** none. Checkpoint formats keep their own version checks.

## Migration

None for users. For maintainers: step 1 above, in the pull request that sets `mimaBaselineVersion`.

## Open questions

- ~~Does the build support a per-module baseline version?~~ Yes, since
  [#2355](https://github.com/llm4s/llm4s/issues/2355): `mimaBaselineOverrides` in `build.sbt` gives a module its own
  baseline (`Some`) or none (`None`), and `scripts/mima-dry-run.sh` checks both.
- Who declares the runtime's public API final, and what is the signal: a stage of #1266, or a named release?
