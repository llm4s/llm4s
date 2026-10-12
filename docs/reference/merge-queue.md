---
layout: page
title: Protecting main (merge queue)
parent: Reference
nav_order: 20
---

# Protecting `main` from merge skew

Two pull requests can each be green against the `main` they were tested on and still break `main` together. A
`pull_request` run tests the merge of the pull request with `main` **as it was when the run started**; nothing checks
that merge again when the pull request is merged hours later. It has happened twice:

- 2026-10-08: [#1417](https://github.com/llm4s/llm4s/pull/1417) added a field to `Completion`, and
  [#1386](https://github.com/llm4s/llm4s/pull/1386), green against the older `main`, merged after it with Kotlin
  tests that called the old `apply`. `main` was red for over an hour
  ([#1638](https://github.com/llm4s/llm4s/issues/1638), fixed by [#1621](https://github.com/llm4s/llm4s/pull/1621)).
- [#1585](https://github.com/llm4s/llm4s/pull/1585) added an SBOM check that allows a test framework only in
  `llm4s-provider-testkit`, and [#1741](https://github.com/llm4s/llm4s/pull/1741) added `llm4s-agent-testkit`, built on
  ScalaTest. Each was green; together they failed Quick Checks on `main` until
  [#1819](https://github.com/llm4s/llm4s/pull/1819).

Re-running the old check does not help: `gh run rerun` re-runs the same merge commit, and closing and reopening a pull
request does not reliably produce a fresh one. Only a check on what will actually land can catch it.

## For contributors

Nothing changes in how you open a pull request. Once a merge queue is enabled, a maintainer adds your pull request to
the queue instead of merging it. The queue tests it on top of current `main` (and of anything queued ahead of it) and
merges it only if that passes. If it fails there, the pull request is removed from the queue with the failing run
linked: merge `main` into your branch (`git merge --signoff origin/main`), fix the clash, and push.

## For maintainers: enabling it

This is a repository setting, so it needs an admin; the workflows are ready for it. Two options, which can be used in
sequence.

### Option 1 (recommended): a merge queue

The workflows that provide the required checks run on the `merge_group` event, so the queue gets a result from each:

| Required check (job name) | Workflow | On a merge group |
|---|---|---|
| `All Tests Pass` | `.github/workflows/ci.yml` | The full PR pipeline, on `main` plus the queued pull request(s). The Ollama, Workspace, MCP-reference and Cloud tiers stay on pushes to `main` / dispatch, as on pull requests. |
| `Check DCO sign-off` | `.github/workflows/dco.yml` | Reports success without re-running `dco-check`: a pull request can be queued only after its required checks, this one included, have passed on it, and the queue adds only commits GitHub writes. `dco-check` reads the pull request from the event, which a merge group does not carry. |
| `TruffleHog Secret Scan` | `.github/workflows/secret-scan.yml` | Scans the merge group's own range (`base_sha..head_sha`), not the whole history. |

`scripts/test-merge-queue-workflows.sh` (run in CI's Quick Checks) fails if any of these loses its `merge_group`
trigger, if a required job or anything it needs would be skipped on a merge group, or if a step reads
`github.event.pull_request` without a `pull_request` gate.

To enable it:

1. Settings, Rules, Rulesets, `protect-main` (it exists, targets the default branch, and is currently
   **disabled**): set Enforcement status to **Active**. Keep its `deletion` and `non_fast_forward` rules.
2. Add **Require status checks to pass**, with the three checks above. Choose GitHub Actions as their source.
   Do not add `OSV-Scanner` (it is path-filtered, so most pull requests never report it), Codecov's statuses (they are
   informational here and are not guaranteed to report on a merge group) or the `DCO` GitHub App's check (it is not known to report on merge groups; the
   workflow's `Check DCO sign-off` covers the same rule).
3. Add **Require merge queue**. Suggested settings:
   - Merge method: **Squash**, as pull requests are merged today. The squash commit message keeps each commit's
     `Signed-off-by`.
   - Build concurrency: 5. Minimum group size 1, maximum 5, wait time 5 minutes.
   - **Only merge non-failing pull requests**: on, so a failing group is split and the culprit removed rather than
     everything behind it.
   - Status check timeout: 60 minutes (a full `ci.yml` run takes about 20).
4. Leave **Require branches to be up to date before merging** off: the queue does the same job without the author
   updating the branch after every merge.

The `merge_group` triggers are inert until the ruleset uses them; until then nothing about pull request CI changes.

To check it works after enabling: queue one pull request, then confirm with
`gh run list --event merge_group --limit 5` that CI, DCO and Secret Scan each ran on a `gh-readonly-queue/main/...`
branch and that the pull request merged when they passed.

### Option 2 (zero workflow dependency): require branches to be up to date

In the same ruleset, add **Require status checks to pass** with the checks above and tick **Require branches to be up to
date before merging** (`strict_required_status_checks_policy: true`). A pull request that is behind `main` then cannot be
merged until it is updated, which creates a new head and a fresh `pull_request` run against current `main`.

It needs nothing from the workflows and can be switched on at once. The cost is that after every merge, every other
open pull request needs "Update branch" and another full CI run (about 20 minutes) before it can merge, so it scales
poorly at dozens of merges a day. Use it as a stopgap and replace it with the queue (Option 1).

Either way, enabling `protect-main` with required status checks is the prerequisite: while it is disabled, nothing is
required of a pull request before it merges.
