---
layout: page
title: "RFC 0000: Template"
parent: RFCs
grand_parent: Reference
nav_exclude: true
---

# RFC NNNN: Title

- **Status:** Proposed <!-- Proposed, Final comment period (ends YYYY-MM-DD), Accepted, Rejected, Implemented in X.Y.Z -->
- **Author(s):** @handle
- **Tracking issue:** #NNNN
- **Pull request:** #NNNN

## Summary

One paragraph: what changes, for whom.

## Motivation

The problem, with evidence: an issue, a user report, a measurement. What happens if nothing changes?

## Detailed design

The public API after the change, as code: signatures, types, config keys, wire formats. Enough that a reviewer can
judge the change without reading the implementation.

## Alternatives

Other designs considered, including doing nothing, and why this one is preferred.

## Compatibility impact

- **Modules and tiers:** which modules change, and the tier of each ([API Stability](../reference/api-stability)).
- **Binary:** would `sbt mimaReportBinaryIssues` flag it against the baseline? Which problems, and is a filter needed?
- **Source:** does code that compiles today still compile?
- **Behaviour:** does code that compiles keep doing the same thing?
- **Wire formats:** checkpoints, persisted schemas, config keys.

## Migration

What a user changes, with a before/after snippet. This becomes the migration note in
[`docs/reference/migration.md`](https://github.com/llm4s/llm4s/blob/main/docs/reference/migration.md) and the CHANGELOG entry. For a deprecation, the version it is
deprecated in and the earliest version it can be removed in ([policy](../reference/compatibility-policy#deprecating-and-removing)).

## Open questions

What is still undecided, and who decides it.
