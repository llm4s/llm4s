---
layout: page
title: OpenSSF Best Practices
parent: Reference
nav_order: 29
description: "Each OpenSSF Best Practices passing criterion, marked Met, Unmet or N/A for LLM4S with the file, workflow or page that proves it."
search_title: "LLM4S and the OpenSSF Best Practices passing criteria"
cites_open_issues: [1465, 2307]
cites_closed_issues: [1408]
---

# OpenSSF Best Practices: passing-level evidence
{: .no_toc }

The [OpenSSF Best Practices badge](https://www.bestpractices.dev/) is a self-certified checklist. This page answers
each of its 67 [passing-level criteria](https://www.bestpractices.dev/en/criteria/0) for LLM4S, with the file,
workflow or page that proves the answer, so the questionnaire can be filled in from one place
([#2307](https://github.com/llm4s/llm4s/issues/2307)). It was checked against the repository on 2026-11-16.

LLM4S has **no bestpractices.dev entry yet**: registering it and answering the questionnaire needs a maintainer with
admin rights on the repository.

| Status | Count |
|---|---|
| Met | 61 |
| Unmet | 4 (one of them a MUST, which blocks the passing badge) |
| N/A | 2 |

The unmet criteria and the other things to fix are in [Follow-ups](#follow-ups).

1. TOC
{:toc}

---

## Basics

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `description_good` | MUST | Met | The [home page](https://llm4s.org/) and [README.md](https://github.com/llm4s/llm4s/blob/main/README.md#overview) say what LLM4S is (a Scala framework for LLM applications) and the problem it solves. |
| `interact` | MUST | Met | How to get it: [Installation](../getting-started/installation). Feedback: [issues](https://github.com/llm4s/llm4s/issues/new/choose) and [Community](../community/). Contributing: [Contributing Guide](contributing). |
| `contribution` | MUST | Met | [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#pull-request-workflow) describes the issue-first, pull-request workflow. |
| `contribution_requirements` | SHOULD | Met | Coding standards in [Code Review Guidelines](review-guidelines) and [Scalafix Rules](scalafix); the DCO sign-off in [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#developer-certificate-of-origin-dco); the [pull request template](https://github.com/llm4s/llm4s/blob/main/.github/PULL_REQUEST_TEMPLATE.md) checklist. |
| `floss_license` | MUST | Met | MIT: [LICENSE](https://github.com/llm4s/llm4s/blob/main/LICENSE). |
| `floss_license_osi` | SUGGESTED | Met | MIT is OSI-approved. |
| `license_location` | MUST | Met | `LICENSE` at the repository root; GitHub detects it as MIT. |
| `documentation_basics` | MUST | Met | [Getting Started](../getting-started/), the [user guide](../guide/) and the [examples](../examples/). |
| `documentation_interface` | MUST | Met | The [API reference](../api/) (Scaladoc for every published module) and the [configuration reference](../getting-started/configuration). |
| `sites_https` | MUST | Met | llm4s.org serves HTTPS and redirects HTTP to it (301); GitHub and Maven Central are HTTPS only. |
| `discussion` | MUST | Met | GitHub issues and pull requests are public, searchable and need only a browser; the [Discord](https://discord.gg/4uvTPn6qww) server also has a browser client. |
| `english` | SHOULD | Met | All documentation, issues and code comments are in English. |
| `maintained` | MUST | Met | Commits land on `main` most days, and 0.5.0 and 0.5.1 were released in October 2026 ([releases](https://github.com/llm4s/llm4s/releases)). |

## Change control

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `repo_public` | MUST | Met | [github.com/llm4s/llm4s](https://github.com/llm4s/llm4s), public. |
| `repo_track` | MUST | Met | Git records author, committer, date and change; each commit also carries a `Signed-off-by` ([`dco.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/dco.yml)). |
| `repo_interim` | MUST | Met | Every pull request merges to `main` between releases; nothing is held back for a release. |
| `repo_distributed` | SUGGESTED | Met | Git. |
| `version_unique` | MUST | Met | Each release has its own version (for example `0.5.1`); builds between releases get a unique snapshot version from the git commit. |
| `version_semver` | SUGGESTED | Met | Semantic versioning (`early-semver` in the POMs): [Compatibility and Deprecation Policy](compatibility-policy#versions). |
| `version_tags` | SUGGESTED | Met | Every release is a git tag (`v0.5.1`, ...), and a tag is what starts [`release.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/release.yml). |
| `release_notes` | MUST | Met | [CHANGELOG.md](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md), organised by area with a *Breaking changes* list, the [GitHub Releases](https://github.com/llm4s/llm4s/releases) and the [Migration Guide](migration). |
| `release_notes_vulns` | MUST | N/A | No release has fixed a vulnerability that had a CVE or similar identifier, and no [advisory](https://github.com/llm4s/llm4s/security/advisories) has been published. Security fixes are marked "(security)" in the CHANGELOG all the same. |

## Reporting

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `report_process` | MUST | Met | [Issue forms](https://github.com/llm4s/llm4s/issues/new/choose) for bugs, features and enhancements; [SUPPORT.md](https://github.com/llm4s/llm4s/blob/main/SUPPORT.md). |
| `report_tracker` | SHOULD | Met | GitHub issues. |
| `report_responses` | MUST | Met | Of the 31 issues labelled `bug` opened between 2025-11-16 and 2026-09-16, 30 have a comment or were closed. |
| `enhancement_responses` | SHOULD | Met | Of the 151 issues labelled `enhancement` opened in the same period, 146 have a comment or were closed. |
| `report_archive` | MUST | Met | GitHub issues and pull requests are public and searchable. |
| `vulnerability_report_process` | MUST | Met | [SECURITY.md](https://github.com/llm4s/llm4s/blob/main/SECURITY.md#reporting-a-vulnerability), linked from the site's [People](../people/#security-committee) page and the [Support Policy](support-policy). |
| `vulnerability_report_private` | MUST | Met | SECURITY.md gives a private route: ask the Security Committee for a private Discord channel. Its preferred route, GitHub private vulnerability reporting, is not enabled yet ([#1465](https://github.com/llm4s/llm4s/issues/1465)): see [Follow-ups](#follow-ups). |
| `vulnerability_report_response` | MUST | Met | The one report of the last six months, [#1408](https://github.com/llm4s/llm4s/issues/1408) (2026-10-07), was taken up the same day, and its first fix merged two days later. [MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md#handling-security-reports) lists the steps. SECURITY.md promises no response time. |

## Quality

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `build` | MUST | Met | `sbt buildAll` rebuilds every module from source; see [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md). |
| `build_common_tools` | SUGGESTED | Met | sbt, the standard Scala build tool. |
| `build_floss_tools` | SHOULD | Met | sbt, the Scala 3 compiler and an OpenJDK 21 build are all FLOSS. |
| `test` | MUST | Met | A ScalaTest suite in every module, run by `sbt test`; see the [Testing Guide](testing-guide). |
| `test_invocation` | SHOULD | Met | `sbt test`, the standard way to run tests in sbt. |
| `test_most` | SUGGESTED | Met | Each module has a statement-coverage floor that CI enforces, from 30% to 100% and 85% or more for most modules: [Test Coverage](test-coverage). |
| `test_continuous_integration` | SUGGESTED | Met | [`ci.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/ci.yml) runs the tests on every pull request, every merge-queue group and every push to `main`. |
| `test_policy` | MUST | Met | "Every PR with new code needs tests": [Code Review Guidelines](review-guidelines). |
| `tests_are_added` | MUST | Met | Recent changes add tests with their code: for example the new provider in [#2611](https://github.com/llm4s/llm4s/pull/2611) (9 source files, 7 test files) and the fixes in [#2640](https://github.com/llm4s/llm4s/pull/2640) and [#2616](https://github.com/llm4s/llm4s/pull/2616). |
| `tests_documented_added` | SUGGESTED | Met | The [pull request template](https://github.com/llm4s/llm4s/blob/main/.github/PULL_REQUEST_TEMPLATE.md) asks for "New code includes tests" and "How was this tested?". |
| `warnings` | MUST | Met | Every module compiles with `-Werror`, `-Wunused:all`, `-Wsafe-init`, `-deprecation`, `-feature` and `-unchecked` (`project/Dependencies.scala`). |
| `warnings_fixed` | MUST | Met | With `-Werror`, a warning fails the build, so none can be merged. |
| `warnings_strict` | SUGGESTED | Met | The flags above are the Scala 3 compiler's strict set, and are applied to test code as well. |

## Security

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `know_secure_design` | MUST | Met | The [Security Reference](security) holds the threat model, trust boundaries and mitigations; the [Security Committee](../people/#security-committee) handles reports. |
| `know_common_errors` | MUST | Met | The [Security Reference](security#known-risks-and-mitigations) covers key leakage, prompt injection, SSRF and sandbox escapes, and ends with a [checklist for PR authors](security#security-checklist-for-pr-authors). |
| `crypto_published` | MUST | Met | LLM4S implements no algorithm of its own; it uses TLS, SHA-256, RSA and ECDSA from the JDK: [Legal and IP FAQ](legal-faq#export-control). |
| `crypto_call` | SHOULD | Met | Every cryptographic call goes to the JDK (`java.security`, `javax.net.ssl`). |
| `crypto_floss` | MUST | Met | OpenJDK provides everything LLM4S uses. |
| `crypto_keylength` | MUST | Met | LLM4S generates only P-256 EC keys, for test JWTs in `llm4s-provider-testkit`. TLS keys and the Vertex AI RSA key come from the JDK's defaults and the service account. |
| `crypto_working` | MUST | Met | SHA-256, `SHA256withRSA`, `SHA256withECDSA` and the JDK's TLS defaults; no broken algorithm or mode. |
| `crypto_weaknesses` | SHOULD | Met | No MD5 or SHA-1 in the main sources; the JDK 21 TLS defaults disable SSLv3, TLS 1.0 and 1.1. |
| `crypto_pfs` | SHOULD | Met | TLS is negotiated by the JDK, which prefers TLS 1.3 and ECDHE suites, both forward secret. |
| `crypto_password_storage` | MUST | N/A | LLM4S stores no passwords of external users. |
| `crypto_random` | MUST | Met | Random ids use `SecureRandom` (`TraceContext`) or `UUID.randomUUID`. The two seeded `scala.util.Random` uses (test embeddings, dataset sampling) produce nothing secret. |
| `delivery_mitm` | MUST | Met | Artifacts are delivered over HTTPS from Maven Central and are PGP-signed by [`release.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/release.yml) (`sbt ci-release`); the runner image comes from ghcr.io over HTTPS. |
| `delivery_unsigned` | MUST | Met | No workflow or script fetches a hash over HTTP. Maven checks the checksums against artifacts fetched over HTTPS. |
| `vulnerabilities_fixed_60_days` | MUST | Unmet | The weekly [dependency scan](sbom#vulnerability-scanning) of 2026-11-09 lists HIGH and CRITICAL advisories in transitive dependencies of published modules (Netty, Jackson, jsoup, the PostgreSQL driver, Spring). Nobody has assessed whether LLM4S is exposed or how long each has been public, so this cannot be answered Met. |
| `vulnerabilities_critical_fixed` | SHOULD | Unmet | Same finding: one advisory is critical (CVSS 9.1, `netty-handler`) and is unpatched. |
| `no_leaked_credentials` | MUST | Met | TruffleHog ([`secret-scan.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/secret-scan.yml)) scans every pull request, every push to `main` and the whole history weekly. |

## Analysis

| Criterion | Level | Status | Evidence and justification |
|---|---|---|---|
| `static_analysis` | MUST | Met | Scalafix (`DisableSyntax` rules: no `try`, no direct environment or config reads; [Scalafix Rules](scalafix)) runs in CI's Quick Checks, which every release runs too. OSV-Scanner checks the dependencies ([`dependency-scan.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/dependency-scan.yml)). |
| `static_analysis_common_vulnerabilities` | SUGGESTED | Unmet | Neither Scalafix nor the compiler has rules for vulnerability classes, and no SAST tool such as CodeQL runs: [Security Reference, Scorecard](security#8-supply-chain-of-the-build-openssf-scorecard). |
| `static_analysis_fixed` | MUST | Met | A Scalafix finding fails Quick Checks, which `All Tests Pass` requires, so it is fixed before merge. |
| `static_analysis_often` | SUGGESTED | Met | Scalafix runs on every pull request and every push to `main`. |
| `dynamic_analysis` | SUGGESTED | Met | ScalaCheck property tests feed generated input to parsers and codecs in 15 suites (for example `UntrustedFencingSpec`, `JsonSchemaValidatorPropertySpec`, `PartialJsonSpec`), on every CI run. |
| `dynamic_analysis_unsafe` | SUGGESTED | Unmet | The published workspace-runner image contains one C program, `llm4s-confine.c`, which is compiled with `-Wall -Wextra -Werror` but never run under a sanitizer or fuzzer. The Scala and Java code is memory-safe. |
| `dynamic_analysis_enable_assertions` | SUGGESTED | Met | Scala's `assert` and `require` are always on (the build does not pass `-Xdisable-assertions`), and the property tests run with them. |
| `dynamic_analysis_fixed` | MUST | Met | A failing property test fails CI and blocks the merge; none has reported an exploitable vulnerability. |

## Follow-ups

Unmet criteria:

1. **`vulnerabilities_fixed_60_days` (MUST, blocks passing) and `vulnerabilities_critical_fixed`.** Triage the HIGH and
   CRITICAL advisories in the latest weekly dependency scan: upgrade (or override) the vulnerable Netty, Jackson,
   jsoup, PostgreSQL driver and Spring versions, or record for each why LLM4S is not exposed. Then answer both
   criteria Met.
2. **`static_analysis_common_vulnerabilities` (SUGGESTED).** Run a SAST tool with vulnerability rules, for example
   CodeQL on the compiled Java bytecode, at least before each release.
3. **`dynamic_analysis_unsafe` (SUGGESTED).** Run `llm4s-confine.c`'s tests under AddressSanitizer and
   UndefinedBehaviorSanitizer in CI.

Met, but worth fixing before a reviewer reads them:

- **Enable GitHub private vulnerability reporting** ([#1465](https://github.com/llm4s/llm4s/issues/1465)). It is the
  route SECURITY.md prefers, and it is off, so today a reporter must ask for a Discord channel.
- **State the initial-response target.** `vulnerability_report_response` asks for a first response within 14 days;
  SECURITY.md promises none. Stating "within 14 days" makes the answer something a reviewer can check.
- **The Scorecard *Vulnerabilities* check** also counts advisories in two lock files that are not published:
  `docs/Gemfile.lock` and `modules/it/mcp-reference-server/package-lock.json` ([Security Reference](security#8-supply-chain-of-the-build-openssf-scorecard)).

Maintainer actions to get the badge:

1. Register `https://github.com/llm4s/llm4s` at [bestpractices.dev](https://www.bestpractices.dev/), logged in with a
   GitHub account that has admin rights on the repository.
2. Answer each criterion as in the tables above, pasting the evidence links.
3. Once it shows "passing", add the badge where the project lists its trust signals, and the
   [Security Reference](security#8-supply-chain-of-the-build-openssf-scorecard)'s *CII-Best-Practices* row will score.

When a criterion changes, update its row here and the count at the top.
