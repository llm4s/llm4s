---
layout: page
title: RFCs
parent: Reference
nav_order: 13
has_children: true
permalink: /rfcs/
---

# RFCs: proposing a change to the public API

Once the MiMa baseline is set ([#1281](https://github.com/llm4s/llm4s/issues/1281)), a breaking change to a
Frozen module needs a deprecation cycle ([Compatibility and Deprecation Policy](../reference/compatibility-policy.md)).
An RFC is where such a change is proposed, in the open, before it is built: one Markdown file in this directory,
reviewed as a pull request. The process is deliberately small. [GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md)
already says who decides; this page says where the proposal lives and what it covers.

## When an RFC is required

- Any change to the public API of a **Frozen** module ([API Stability](../reference/api-stability.md)): a new, changed
  or removed public type or member, whether or not MiMa would flag it.
- A module's **tier**: making a module Frozen, Beta or Experimental, or moving it between tiers.
- A new **SPI**, something users or other modules implement (`ProviderDescriptor` and `TracingBackend` are the
  existing ones).
- A change to a **published wire format**: a checkpoint or session format, a persisted schema, a config key that
  existing `application.conf` files use.

## When it is not

- Bug fixes that keep the documented behaviour, and documentation.
- Internals: anything `private`, `private[llm4s]` or in a non-public package.
- **Beta** and **Experimental** APIs. Their changes need only a migration note in the CHANGELOG. Write an RFC anyway
  if the change is large or contested; nothing forbids one.
- A purely additive change that follows a pattern an accepted RFC already settled, for example a new field on a
  growth-prone data type added as [RFC 0002](#index) prescribes. Say which RFC it follows in the pull request.

When in doubt, ask on the issue; a maintainer says whether an RFC is needed.

## How it works

1. **Proposed.** Copy [0000-template.md](0000-template.md) to `NNNN-short-title.md` with the next free number, fill it
   in, add a row to the index below, and open a pull request labelled `rfc`. Link the issue it answers, if any.
2. **Final comment period.** When the discussion has settled, a maintainer announces a final comment period of
   **7 days** on the pull request (and on Discord), so that anyone who has not read it yet can still object.
3. **Accepted** or **Rejected.** After the period, the maintainers decide as [GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md)
   describes (lazy consensus, or a maintainer majority when contested) and the pull request is merged with the
   status set either way. A rejected RFC is kept, with the reason: it records what was considered.
4. **Implemented.** The implementing pull request links the RFC and sets its status, with the version it shipped in.

An accepted RFC can be amended by a later pull request; a change of direction is a new RFC that supersedes it.

## Index

| RFC | Title | Status | Tracking |
|---|---|---|---|
| [0001](0001-agent-baseline.md) | When `llm4s-agent` gets its MiMa baseline | Proposed | [#1281](https://github.com/llm4s/llm4s/issues/1281), [#1266](https://github.com/llm4s/llm4s/issues/1266) |
| 0002 | Growth-prone data types: `final case class X private`, companion `apply`, `with*` setters, no public `copy` | Accepted (recorded retroactively) | [#1133](https://github.com/llm4s/llm4s/issues/1133) pass 5; [policy](../reference/compatibility-policy.md#changing-a-frozen-api-without-breaking-it) |
| 0003 | Times in the public API: `FiniteDuration` for a duration, `Instant` for a point in time | Accepted (recorded retroactively) | [#1133](https://github.com/llm4s/llm4s/issues/1133) passes 6 and 7 |
| 0004 | A frozen module's public signatures expose no third-party type and no wildcard-importable implicit | Accepted (recorded retroactively) | [#1133](https://github.com/llm4s/llm4s/issues/1133) pass 8 |
| 0005 | Providers are `ProviderDescriptor`s discovered through `META-INF/services`; core ships none | Accepted (recorded retroactively) | [#1131](https://github.com/llm4s/llm4s/issues/1131), [#1132](https://github.com/llm4s/llm4s/issues/1132); [guide](../guide/writing-a-provider.md) |
| 0006 | Tracing backends are `TracingBackend`s selected by `TracingMode.Named`; core reads no backend's keys | Accepted (recorded retroactively) | [#1233](https://github.com/llm4s/llm4s/issues/1233), [#1133](https://github.com/llm4s/llm4s/issues/1133) |
| 0007 | Typed agent runtime: one execution model for the agent harness | Proposed (design document) | [#1266](https://github.com/llm4s/llm4s/issues/1266); [design](https://github.com/llm4s/llm4s/blob/main/docs/design/typed-agent-runtime-design.md) |

Rows 0002 to 0006 were decided before this process existed. They are listed so that the log is complete from the
start; each links to where the decision was made and has no file of its own. Write one only if the decision is
revisited.

## Design documents

The documents in [`docs/design/`](https://github.com/llm4s/llm4s/tree/main/docs/design) describe how features were
built (the agent framework phases, the typed agent runtime and its stage slices). They are not RFCs and are not
rewritten as RFCs. A design that changes a Frozen API still needs an RFC for that change, which can be short and link
the design document for the detail.
