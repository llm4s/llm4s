---
layout: page
title: Enterprise Evaluation
parent: Reference
nav_order: 25
description: "Answers to the questions an open-source intake form asks about LLM4S, each linked to its evidence, with the known gaps."
cites_open_issues: [1281, 2280, 2307, 2310]
cites_closed_issues: [1465]
---

# Enterprise Evaluation
{: .no_toc }

The questions an organisation's open-source intake or due-diligence form usually asks, each answered in a sentence or
two with a link to the page that is the authority. This page restates no policy: when an answer and its source
disagree, the source is right, and the page needs fixing. Items not in place yet are in [Known gaps](#known-gaps), each
linked to its open issue.

1. TOC
{:toc}

---

## Licence and intellectual property

| Question | Answer and evidence |
|---|---|
| What is the licence? | MIT, for every module: [LICENSE](https://github.com/llm4s/llm4s/blob/main/LICENSE). |
| How do contributions come in? | Under the Developer Certificate of Origin: every commit carries a `Signed-off-by` trailer, and CI fails a pull request with an unsigned commit. See [CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#developer-certificate-of-origin-dco). |
| Is there a legal or IP FAQ (copyright, patents, third-party licences)? | Yes: [Legal and IP FAQ](legal-faq). It also says what the project has not decided or published. |
| What licences do the dependencies have? | Each release attaches a per-module licence report (CSV: dependency, SPDX licence, how it is pulled in), and CI fails on a copyleft or unknown licence: see [Dependency licences](licences). Each module's [SBOM](sbom) lists the same dependencies. |

## Governance

| Question | Answer and evidence |
|---|---|
| Who decides, and how? | Contributors, committers and maintainers; lazy consensus day to day, a maintainer majority for larger or contested decisions, and an [RFC](../rfcs/) for changes to a Frozen API, a tier, an SPI or a wire format. See [GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md). |
| Who maintains it (bus factor)? | Two maintainers, listed in [MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md); committers, committees and contributors are on the [People](../people/) page. |
| Who owns the project? | It is stewarded by the LLM4S Foundation, a community-led group: see the [Foundation](../foundation/) page. Neutrality, continuity and a trademark policy are not documented yet: see [Known gaps](#known-gaps). |

## Release and support

| Question | Answer and evidence |
|---|---|
| How are versions numbered? | Semantic versioning, with `versionScheme := early-semver` in the published POMs. Before the compatibility baseline (0.7.0) any release may break compatibility, each break listed in the CHANGELOG with a migration note. See the [Compatibility and Deprecation Policy](compatibility-policy#versions). |
| Which APIs are stable? | Each module is Frozen, Beta or Experimental: [1.0 Scope](v1-scope), with the promise per tier in the [Compatibility and Deprecation Policy](compatibility-policy). Every top-level public type of a Frozen module carries `@Stable` or `@Experimental`: [Tiers in the Code](api-stability#tiers-in-the-code). |
| Is binary compatibility checked? | MiMa runs in the build, against a baseline that is not set yet, so it checks nothing until then: [API Stability](api-stability#the-baseline) and [Known gaps](#known-gaps). |
| How are releases made, and where are the notes? | From a tag, by CI, to Maven Central: [Release Process](../contribute/release). Notes are the [GitHub Releases](https://github.com/llm4s/llm4s/releases) and the [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md); upgrades are in the [Migration Guide](migration). |
| Which versions get fixes, and for how long? | Before 1.0, fixes ship in the latest release only. From 1.0, the latest 1.x minor gets bug and security fixes and the previous minor security fixes for three months after its successor; 0.7.x gets critical security fixes for three months after 1.0. No LTS line before 2.0; minor releases at most about quarterly. See the [Support Policy](support-policy) and [SECURITY.md](https://github.com/llm4s/llm4s/blob/main/SECURITY.md#supported-versions). |

## Security

| Question | Answer and evidence |
|---|---|
| Is there a security policy? | Yes: [SECURITY.md](https://github.com/llm4s/llm4s/blob/main/SECURITY.md) covers supported versions, private reporting, what to expect and scope. Handling is best effort, with no response-time commitment. |
| Who handles reports, and how? | The [Security Committee](../people/#security-committee) and the maintainers, following the steps in [MAINTAINERS.md](https://github.com/llm4s/llm4s/blob/main/MAINTAINERS.md#handling-security-reports): acknowledge, assess, fix in a private fork, release, publish an advisory. |
| Can vulnerabilities be reported privately? | SECURITY.md names GitHub private vulnerability reporting as the preferred route and a private Discord channel as the fallback. It is enabled on the repository ([#1465](https://github.com/llm4s/llm4s/issues/1465)). |
| Is there a threat model? | Yes: the [Security Reference](security) lists data flows, trust boundaries, known risks with their mitigations and residual risk, and a checklist for pull requests. |
| What is the track record? | No GitHub security advisory has been published ([advisories](https://github.com/llm4s/llm4s/security/advisories)). |

## Supply chain

| Question | Answer and evidence |
|---|---|
| Is there an SBOM? | Yes: a CycloneDX SBOM per published module on each GitHub Release, also built and validated on every pull request. See [Software Bill of Materials](sbom). |
| Are dependencies scanned? | OSV-Scanner scans the SBOMs weekly and on pull requests that change dependencies; a pull request that adds a HIGH or CRITICAL advisory fails. See [Vulnerability scanning](sbom#vulnerability-scanning). |
| Are artifacts signed? | The Maven Central artifacts are PGP-signed by the release workflow ([`release.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/release.yml), `sbt ci-release`); the key's fingerprint and the commands to check a signature are in [Verifying Releases](../contribute/verifying-releases). From the first release after 0.6.0 the workflow also attests SLSA build provenance for the jars and POMs, the SBOMs on the GitHub Release and the container image; no release has shipped one yet: see [Known gaps](#known-gaps). |
| OpenSSF Scorecard or Best Practices badge? | Scorecard runs on every push to main ([results](https://scorecard.dev/viewer/?uri=github.com/llm4s/llm4s)); the branch-protection items it scores are still open. No Best Practices badge yet; the evidence for each passing criterion is in [OpenSSF Best Practices](best-practices), and see [Known gaps](#known-gaps). |

## Data protection

| Question | Answer and evidence |
|---|---|
| Does the library send telemetry? | No. Every network call goes to a destination your configuration or code names: [Data Protection](../guide/data-protection#in-short). |
| What leaves the process, and where to? | A table per module, and the destinations for an egress allow-list: [Data Protection](../guide/data-protection#what-leaves-the-process-and-what-is-kept). |
| What do logging and tracing record by default? | The default console tracer prints metadata only, not prompts or model output: [Logging and tracing defaults](../guide/data-protection#logging-and-tracing-defaults). |
| Data residency and retention? | The region is chosen by the provider endpoint you configure; llm4s sets no retention flag of its own: [Provider data residency and retention](../guide/data-protection#provider-data-residency-and-retention). |

## Operations

| Question | Answer and evidence |
|---|---|
| How is it deployed in production? | As a library inside your service. Configuration, secrets, timeouts, lifecycle and deployment patterns: [Production Deployment](../guide/production-deployment). A staged dev, staging and prod template with Kubernetes manifests and rollback: [deploy/](https://github.com/llm4s/llm4s/blob/main/deploy/README.md). Behind an AI gateway, with secret injection, egress policy, failover and spend controls: [Reference Architecture](../guide/reference-architecture). |
| Is there a reference architecture? | Not yet: see [Known gaps](#known-gaps). |
| Observability? | Tracing to Langfuse or OpenTelemetry, metrics to Prometheus or Micrometer: [Monitoring](../guide/observability/). |
| Health checks? | [Production Deployment](../guide/production-deployment#health-checks), and the `/health` and `/llm-check` endpoints of the deploy template. [Doctor](doctor) checks a developer's setup. |
| Secret rotation? | Keys come from configuration, which can bind environment variables: [Configuration & Secrets](../guide/production-deployment#configuration--secrets). A key mounted as a file by Vault Agent, the Secrets Store CSI driver or External Secrets Operator (`apiKeyFile`), or fetched by your own `ApiKeyProvider`, is re-read without a restart by every built-in chat provider that takes a key, the embedding providers and the Cohere reranker, also when it is the vendor's shared `llm4s.credentials` key: [Secrets and key rotation](../guide/production-deployment#secrets-and-key-rotation). |

## Platform

| Question | Answer and evidence |
|---|---|
| Which JDK and Scala versions? | Scala 3 only (3.7.1), and JDK 21 for every artifact: [Scala and JDK support](v1-scope#scala-and-jdk-support). Tested versions of the surrounding stack: [Compatibility Matrix](compatibility-matrix). |
| Can Java or Kotlin teams use it? | Yes: [Java](../guide/java) (`llm4s-java-api`), and `llm4s-kotlin-api` (Experimental, see the [User Guide](../guide/)). |
| Which frameworks? | [Spring Boot](../guide/spring-boot), [cats-effect](../guide/cats-effect), [ZIO](../guide/zio) and [Apache Pekko](../guide/pekko). A [Quarkus](../guide/quarkus) extension exists but is not published and is outside 1.0 ([#2280](https://github.com/llm4s/llm4s/issues/2280)). |
| Which model providers? | [Providers](../guide/providers), and what each supports in the [Compatibility Matrix](compatibility-matrix#provider-capabilities). |

## Adoption and help

| Question | Answer and evidence |
|---|---|
| Who uses it? | Organisations list themselves by pull request in [ADOPTERS.md](https://github.com/llm4s/llm4s/blob/main/ADOPTERS.md); each entry says only what its authors wrote. The first is [Scala Teams](https://scalateams.com) (since November 2026), which uses LLM4S as the multi-provider LLM layer of Spec Manager, an AI spec-authoring and code-generation tool, on `llm4s-core` and `llm4s-anthropic`, and whose team wrote and is implementing [RFC 0008](../rfcs/0008-generation-controls) ([Success Stories](../community/#success-stories)). Public projects are on the [Showcase](../showcase/). |
| Where is the community? | [Discord](https://discord.gg/4uvTPn6qww), [GitHub issues](https://github.com/llm4s/llm4s/issues) and the [Community](../community/) pages. |
| Where do we get help? | [SUPPORT.md](https://github.com/llm4s/llm4s/blob/main/SUPPORT.md). The maintainers are volunteers: replies are best effort and there is no service-level agreement. |
| Is commercial support available? | No paid support is offered. Questions about sponsoring or partnering with the project go to the contact on the [Foundation](../foundation/) page. |

---

## Known gaps

Reviewed 2026-11-23. Each item is an open issue; a CI check (`scripts/check-cited-issues.sh`) fails when one of them
closes, so this list is updated when the gap is.

| Gap | Issue |
|---|---|
| No binary-compatibility baseline yet: MiMa checks nothing until 0.7.0 sets one; 1.0 follows it | [#1281](https://github.com/llm4s/llm4s/issues/1281) |
| No OpenSSF Best Practices badge | [#2307](https://github.com/llm4s/llm4s/issues/2307) |
| No trademark and name-use policy; neutrality and continuity not documented in GOVERNANCE.md | [#2310](https://github.com/llm4s/llm4s/issues/2310) |

---

## Answer set to paste into a form

The same answers as plain text. Check each link before you submit: the linked page is the authority.

```text
Project: LLM4S (Large Language Models for Scala), https://github.com/llm4s/llm4s
Licence: MIT (https://github.com/llm4s/llm4s/blob/main/LICENSE). Contributions under the DCO
  (Signed-off-by on every commit, checked in CI).
Dependency licences: per-module licence report on each GitHub Release; CI fails on copyleft
  or unknown licences (https://llm4s.org/reference/licences); per-module CycloneDX SBOMs
  (https://llm4s.org/reference/sbom).
Governance: GOVERNANCE.md (lazy consensus; maintainer majority for larger decisions; RFCs for
  Frozen API changes). Two maintainers (MAINTAINERS.md). Stewarded by the LLM4S Foundation
  (https://llm4s.org/foundation/).
Versioning: semantic versioning (early-semver). Until the 0.7.0 baseline any release may break
  compatibility, with a migration note (https://llm4s.org/reference/compatibility-policy).
  No binary-compatibility baseline yet (https://github.com/llm4s/llm4s/issues/1281).
Support policy: before 1.0, fixes ship in the latest release only. From 1.0, the latest 1.x minor gets
  bug and security fixes, the previous minor security fixes for three months; no LTS before 2.0
  (https://llm4s.org/reference/support-policy). Volunteer-maintained, best effort,
  no SLA, no commercial support (SUPPORT.md).
Security: SECURITY.md; reports handled by the Security Committee and maintainers, best
  effort, no response-time commitment. Threat model: https://llm4s.org/reference/security.
  No published advisories. GitHub private vulnerability reporting
  enabled (https://github.com/llm4s/llm4s/issues/1465).
Supply chain: CycloneDX SBOM per module on each GitHub Release; OSV-Scanner weekly and on
  dependency changes. Maven Central artifacts PGP-signed (https://llm4s.org/contribute/verifying-releases).
  OpenSSF Scorecard on every push to main (https://scorecard.dev/viewer/?uri=github.com/llm4s/llm4s). SLSA build
  provenance attested for every release (https://llm4s.org/contribute/verifying-releases); no Best Practices badge
  (https://github.com/llm4s/llm4s/issues/2307).
Data protection: no telemetry; data goes only to the providers and stores you configure;
  default tracing prints metadata only (https://llm4s.org/guide/data-protection).
Platform: Scala 3 (3.7.1), JDK 21. Java and Kotlin APIs; Spring Boot, cats-effect, ZIO and
  Pekko integrations (a Quarkus extension is planned after 1.0).
Adopters: ADOPTERS.md (by pull request); first entry Scala Teams (Spec Manager, since
  2026-11).
Full answers with evidence: https://llm4s.org/reference/enterprise-evaluation
```
