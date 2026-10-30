---
layout: page
title: Legal and IP FAQ
parent: Reference
nav_order: 27
description: "What licence covers LLM4S, who holds copyright, how contributions come in (DCO), the patent position of MIT, third-party licences and provider terms, with what the project has not decided."
---

# Legal and IP FAQ
{: .no_toc }

**This page is not legal advice.** It collects what the repository itself says, for the legal or open-source programme
office that reviews an adoption request, and links each answer to its source. Where the project has not decided or
published something, the answer says so instead of guessing. Have your own counsel confirm anything your organisation
relies on.

1. TOC
{:toc}

---

## Licence

**What licence covers LLM4S?** MIT, as in the repository's [LICENSE](https://github.com/llm4s/llm4s/blob/main/LICENSE).
Every module in the repository, and so every published `llm4s-*` artifact, is built from that one repository under that
licence.

**What does MIT permit and require?** It permits use, copying, modification, merging, publishing, distribution,
sublicensing and sale. Its one condition is that the copyright notice and the permission notice are included in all
copies or substantial portions of the software. The software is provided "as is", without warranty. The licence text is
authoritative; read it rather than this summary.

## Copyright

**Who holds the copyright?** The LICENSE file reads `Copyright (c) 2025 Rory Graves`. The repository contains no
`NOTICE` or `AUTHORS` file; contributors are recorded in the git history.

**Do contributors assign their copyright?** The contribution terms ([CONTRIBUTING.md](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#developer-certificate-of-origin-dco))
are the Developer Certificate of Origin, which asks for a sign-off and contains no assignment of copyright. The
project has no Contributor Licence Agreement.

**Who may relicense?** The project has not published a relicensing policy, and this page does not state one. The
licence changes only by the maintainers' decision under [GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md),
and no licence change is planned in the repository's documents.

**How does the LLM4S Foundation relate to the copyright holder?** The [Foundation](../foundation/) page describes the
Foundation and its status; this page adds nothing to it. The project has published nothing on whether the Foundation
holds any rights in the code.

## Contributions: DCO, not a CLA

**What does the sign-off certify?** Every commit carries a `Signed-off-by` trailer (`git commit -s`), by which the
committer certifies, under the [Developer Certificate of Origin](https://developercertificate.org/), that they have the
right to submit the work under the project's MIT licence. CI fails a pull request with an unsigned commit
([`dco.yml`](https://github.com/llm4s/llm4s/blob/main/.github/workflows/dco.yml)).

**Why the DCO and not a CLA?** The project uses the DCO and has no CLA; the repository does not record the reasoning.
The practical difference for a reviewer is that contributing involves no separate agreement to sign and no assignment
or additional licence grant beyond the project's MIT licence.

## Patents

**Does MIT grant a patent licence?** MIT has no express patent grant, unlike Apache-2.0. This page grants none and
claims none. Whether a patent licence is implied, and what that means for you, is a question for your counsel; the
project has not made a statement about patents.

## Third-party content and dependencies

**What are the licences of the dependencies?** Every published module has a licence report of its runtime dependency
tree (SPDX identifier per library), attached to each GitHub Release and reproducible with `sbt licenseReport`. CI runs
`sbt licenseCheck` on every pull request and fails on a copyleft (GPL, AGPL, LGPL without a linking exception), network
copyleft or unknown licence. See [Dependency licences](licences) for the allow-list, the reviewed exceptions and what
the reports do not cover. Each module's [SBOM](sbom) lists the same dependencies in CycloneDX form.

**What about the provider SDKs?** Modules such as `llm4s-openai`, `llm4s-anthropic` and `llm4s-bedrock` bring the
vendor's SDK as a dependency. Its licence is in that module's licence report and is held to the same policy; it is not
changed by llm4s.

**What about models metadata, sample prompts and documentation images?** The repository's LICENSE covers the
repository. The project has not published a separate statement on the licence of the model metadata
([MODEL_METADATA](https://github.com/llm4s/llm4s/blob/main/docs/MODEL_METADATA.md)), sample prompts or images, and this page does not make one.

## AI-assisted contributions

The project has not published a separate policy on AI-assisted contributions. The DCO sign-off is a personal
certification that applies to every commit, whatever tool produced it: the person who signs off certifies their right
to submit the work.

## Provider terms and data

**Does using a provider through llm4s change my obligations to that provider?** No. llm4s is a client library: you call
the provider with your own credentials, and its terms of service, usage policies and data terms apply to you as they
would without llm4s. llm4s grants no rights in a provider's models or services.

**Where does my data go?** Only to the destinations your configuration or code names; the library sends no telemetry.
See [Data Protection](../guide/data-protection).

## Not covered yet

- **Export control.** The project has made no statement about cryptography or export classification.
- **Name and logo use.** No trademark and name-use policy is published yet; it is tracked in
  [#2310](https://github.com/llm4s/llm4s/issues/2310).

## Related

- [Enterprise evaluation](enterprise-evaluation): the wider intake questions, with the known gaps.
- [Dependency licences](licences) and [SBOM](sbom).
- [GOVERNANCE.md](https://github.com/llm4s/llm4s/blob/main/GOVERNANCE.md) and the [Foundation](../foundation/) page.
