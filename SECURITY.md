# Security Policy

## Supported versions

Security fixes are made on the `main` branch and shipped in the latest published release of LLM4S (see
[Releases](https://github.com/llm4s/llm4s/releases)). Older releases are not patched, so please upgrade to the latest
release before reporting, and say in your report which version you tested.

## Reporting a vulnerability

Please **do not** describe a vulnerability in a public issue, pull request, discussion or Discord message.
Report it privately:

1. **GitHub private vulnerability reporting (preferred).** Open the repository's
   [Security tab](https://github.com/llm4s/llm4s/security) and choose **Report a vulnerability**.
2. **If that option is not available**, ask a maintainer (listed in [MAINTAINERS.md](MAINTAINERS.md)) for a private
   channel on the [LLM4S Discord](https://discord.gg/4uvTPn6qww). Say only that you have a security report and
   keep the details out of the public channels until a private one is open.

Useful details:

- the affected module and version (for example `llm4s-openai` at the version you tested);
- what an attacker can do, and what they need first (a configuration, a prompt, a file on disk);
- the smallest code or configuration that reproduces it;
- a suggested fix, if you have one.

Please do not include real credentials or other people's data in a report.

## What to expect

Reports are handled on a best-effort basis, and we will keep you informed in the private thread. We ask for reasonable
time to prepare a fix before the issue is made public, and we will credit you in the release notes if you wish.

## Scope

In scope: the code in this repository and the `llm4s-*` artifacts published from it.

The built-in tools that read and write files, run programs and make HTTP requests are security-sensitive by design.
How to configure them safely, and the limits that remain, are described in
[the built-in tools guide](docs/guide/builtin-tools.md) (see *Safety: what each tool can do*) and in the
[security reference](docs/reference/security.md).

Those pages are open about limits that are inherent in the design. For example, the path settings are a filter and not a
sandbox, a symbolic link inside an allowed directory is not a security boundary, the shell tool is not covered by the
file settings and inherits the environment of the process, and the HTTP check can be passed by DNS rebinding. **Please
report such things privately all the same.** A demonstrated way around a guard that the guide says is enforced, or a
disclosure or escape that the guide does not warn about, is a vulnerability. A limit that the guide already documents is
a hardening request, and we may discuss and fix it in public once we have agreed that with you. Running a tool with a
configuration you chose to make permissive is not a vulnerability. If you are not sure which of these you have, report
it privately: we would rather hear about it.

Out of scope:

- Vulnerabilities in a model provider's own API or service. Report those to the provider.
- Vulnerabilities in a third-party dependency that have no effect on LLM4S users. Report those upstream. A dependency
  vulnerability that does affect LLM4S users is welcome as a report.
- Prompt injection that gets past the regular-expression detector. The
  [security reference](docs/reference/security.md#known-risks-and-mitigations) documents it as a known risk with
  residual risk, a defence-in-depth layer and not a guarantee. A report is in scope when LLM4S code fails to apply a
  mitigation that the reference says it applies.

For secure-coding expectations on contributions, see the security checklist in the
[security reference](docs/reference/security.md#security-checklist-for-pr-authors).
