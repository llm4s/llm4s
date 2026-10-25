# Maintainers

This file lists the maintainers of LLM4S. See [GOVERNANCE.md](GOVERNANCE.md) for how roles are defined and how maintainers are added.

## Maintainers

| Name | GitHub | Organization |
|------|--------|--------------|
| Kannupriya Kalra | [@kannupriyakalra](https://github.com/kannupriyakalra) | Independent |
| Rory Graves | [@rorygraves](https://github.com/rorygraves) | Independent |

## Becoming a Maintainer

See the "Maintainer" role definition in [GOVERNANCE.md](GOVERNANCE.md#roles). Consistent, high-quality contributions over time are the primary criterion; new maintainers are proposed and approved by existing maintainers.

## Repository settings

Required checks and the merge queue on `main` are admin settings; the workflows already support them. See
[Protecting main from merge skew](docs/reference/merge-queue.md) for what to enable and why.

**Private vulnerability reporting** is an admin setting too (Settings → Code security → *Private vulnerability
reporting*). [SECURITY.md](SECURITY.md) names it as the preferred way to report, so it must stay enabled; check with
`gh api repos/llm4s/llm4s/private-vulnerability-reporting`, which should return `{"enabled":true}`.

**Branch protection on `main`** feeds OpenSSF Scorecard's Branch-Protection check (`scorecard.yml`, #2306): require a
pull-request review (one approval, stale approvals dismissed), the required checks above, no force pushes or
deletions, and include administrators. [Security reference](docs/reference/security.md#8-supply-chain-of-the-build-openssf-scorecard)
lists where each Scorecard check stands.

## Handling security reports

Reports reach the [Security Committee](docs/people.md#security-committee) and the maintainers, either as a private
advisory (the Security tab's *Report a vulnerability*) or through a private Discord channel opened on request (the
fallback in [SECURITY.md](SECURITY.md)). Keep every detail in that private channel until the fix is released.

1. **Acknowledge** the report in the private thread, and say who is handling it. If it came through Discord, open a
   draft advisory on the Security tab (*New draft security advisory*) and invite the reporter, so the work is
   tracked in one private place.
2. **Assess** it against the *Scope* section of SECURITY.md: is it a vulnerability, a hardening request for a limit
   the guides already document, or out of scope? Record the affected modules and versions and a severity (CVSS in
   the advisory form). If it is not a vulnerability, explain why to the reporter; a documented limit may then be
   discussed in a public issue once the reporter agrees.
3. **Fix it privately.** Use the advisory's *temporary private fork*, not a public branch or pull request. The fix
   follows the usual rules (tests, signed-off commits, review by a second maintainer or committee member), and its
   commit message must not describe the vulnerability before the release.
4. **Release.** Merge the fix and publish a release, a patch release where the
   [compatibility policy](docs/reference/compatibility-policy.md) allows. Note it in the CHANGELOG under *Security*
   for that release, with a link to the advisory once it is published.
5. **Disclose.** Publish the advisory (request a CVE from GitHub in the form if the severity warrants one), credit
   the reporter if they wish, and close the private channel. Dependabot and OSV-Scanner pick the advisory up for
   downstream users.

If the report concerns a dependency rather than LLM4S code, follow the same steps for any change LLM4S needs (an
upgrade, a workaround) and tell the reporter to report the dependency upstream as well.
