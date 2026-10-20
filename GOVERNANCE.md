# Governance

LLM4S is an open-source project developed in public on GitHub. This document describes the project's roles and how decisions are made.

## Roles

### Contributor
Anyone who submits a pull request, issue, or discussion to the project. No special access is required.

### Committer
A contributor who has been granted write access to the repository to merge pull requests, triage issues, and manage labels/milestones. Committers are proposed by a maintainer and approved by consensus of the existing maintainers, based on a sustained track record of quality contributions.

### Maintainer
A committer with overall responsibility for the project's direction, releases, and final decisions on contested changes. Maintainers can add/remove committers and other maintainers. The current list is in [MAINTAINERS.md](MAINTAINERS.md).

## Decision Making

- Most day-to-day decisions (code review, small features, bug fixes) are made by lazy consensus among committers and maintainers.
- Larger or contested decisions (breaking changes, new maintainers/committers, project direction) require agreement from a majority of maintainers.
- Disagreements that cannot be resolved through discussion are decided by maintainer vote.
- A change to the public API of a Frozen module, a module's tier, a new SPI or a published wire format is proposed as an [RFC](docs/rfcs/README.md) first: a pull request against `docs/rfcs/`, with a final comment period of 7 days before the maintainers decide as above.

## Code of Conduct

All participants are expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md). Reports are handled by the maintainers as described there.

## Changes to This Document

Changes to governance are proposed via pull request against this file and require approval from a majority of maintainers.
