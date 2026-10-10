---
layout: page
title: Security Reference
parent: Reference
nav_order: 16
---

# Security Reference

This document covers the threat model, trust boundaries, known risks, and mitigations for LLM4S.

## Data Flow Overview

```
User Input ──► Agent ──► LLM Provider (API key in header)
                │              │
                ▼              ▼
           Tool Registry   LLM Response
                │
                ▼
         Tool Outputs ──► Agent (fed back into conversation)
                │
                ▼
         Memory Stores (SQLite / Postgres / In-Memory)
```

**Sensitive data in transit:**
- API keys travel in `Authorization` headers to provider endpoints
- User prompts and LLM responses may contain PII
- Tool outputs (HTTP responses, file content, search results) are untrusted

## Trust Boundaries

| Boundary | Trust Level | Notes |
|----------|-------------|-------|
| User input | **Untrusted** | May contain prompt injection attempts |
| LLM responses | **Untrusted** | Model can be manipulated by injected content |
| Tool outputs | **Untrusted** | External HTTP responses, file reads, shell output |
| Provider API errors | **Untrusted** | Error bodies may echo back API keys |
| Memory store reads | **Semi-trusted** | Content was written by the agent but may originate from user or tools |
| Config / environment | **Trusted** | Read at startup via `Llm4sConfig` |

## Known Risks and Mitigations

### 1. API Key Leakage in Logs and Error Messages

**Risk:** A provider's error response body may contain or echo back the API key. If that body is forwarded into an `LLMError.message`, the key leaks into application logs.

**Mitigation (implemented):**
- `HttpErrorMapper.sanitize()` runs `Redaction.redact()` on the raw provider error body before constructing any `LLMError`. This strips OpenAI, Anthropic, Google, Voyage, Langfuse, AWS, and JWT patterns.
- `Redaction.scala` and `SecretPatterns.scala` maintain the canonical set of credential regexes used across the codebase.
- Provider `ProviderConfig` `toString` implementations mask API keys with `***`.

**Residual risk:** Plain-text secrets not matching any known regex pattern would not be redacted.

### 2. Prompt Injection via User Input

**Risk:** A malicious user prompt attempts to override system instructions, extract the system prompt, or manipulate the agent into performing unintended actions.

**Mitigation (implemented):**
- `PromptInjectionDetector` is an `InputGuardrail` with 6 attack categories: instruction override, role manipulation, system prompt extraction, jailbreak, code injection, and data exfiltration.
- Three sensitivity levels (High / Medium / Low) and three actions (Block / Fix / Warn).

**Residual risk:** Novel or obfuscated injection patterns that do not match the regex library may bypass detection. Regex-based detection is a defence-in-depth layer, not a guarantee.

### 3. Indirect Prompt Injection via Tool Outputs

**Risk:** A malicious web page, file, or API response returned by a built-in tool (HTTP, search, file read) contains instructions that hijack the agent when fed back into the conversation.

**Mitigation (partial):**
- No automatic output-side injection guardrail is applied to tool results by default. This is by design: the LLM provider's safety filters and the application's output guardrails are the primary defence at the response layer.
- Operators can add a custom `OutputGuardrail` that inspects tool results before they are appended to the conversation.

**Recommended practice:** For high-security deployments, apply `PromptInjectionDetector` as an output guardrail over tool result strings before passing them back to the agent.

### 4. Server-Side Request Forgery (SSRF) via HTTP Tool

**Risk:** The built-in `HTTPTool` could be directed to internal network addresses, cloud metadata endpoints (169.254.169.254), or loopback addresses.

**Mitigation (implemented):**
- `HttpConfig.blockInternalIPs = true` by default; `NetworkSecurity.validateHostname()` resolves DNS and refuses a resolved IP that is loopback, link-local (including `169.254.0.0/16` and `fe80::/10`), private (RFC 1918 for IPv4, unique-local `fc00::/7` and the deprecated site-local `fec0::/10` for IPv6), multicast, unspecified or in `0.0.0.0/8`, as well as the cloud metadata address, the documentation ranges (RFC 5737; `2001:db8::/32`, `3fff::/20`), carrier-grade NAT (`100.64.0.0/10`), benchmarking (`198.18.0.0/15`, `2001:2::/48`), IPv6 discard-only (`100::/64`), Teredo (`2001::/32`), local-use NAT64 (`64:ff9b:1::/48`), the deprecated IPv4-compatible form (`::/96`), reserved `240.0.0.0/4` with the broadcast address `255.255.255.255`, the IETF protocol assignments `192.0.0.0/24`, the deprecated 6to4 relay anycast `192.88.99.0/24`, every IPv6 address outside global unicast `2000::/3` (among them the SIIT form `::ffff:0:0:0/96`, the dummy prefix `100:0:0:1::/64` and SRv6 `5f00::/16`) and the non-global part of `2001::/23` (#1734). An IPv6 address that carries an IPv4 address - IPv4-mapped (`::ffff:0:0/96`), NAT64 (`64:ff9b::/96`) and 6to4 (`2002::/16`) - is refused when the IPv4 address it carries would be. The globally reachable entries of the IANA special-purpose registries (AS112, AMT, the PCP and TURN anycast addresses, ORCHIDv2, drone remote ID) are allowed.
- `HttpConfig.DefaultBlockedDomains` blocks `localhost`, `127.0.0.1`, `0.0.0.0`, `::1`, `metadata.google.internal`, `metadata.internal`, and `169.254.169.254` by hostname.
- Redirects are NOT followed by default (`followRedirects = false`). When enabled, each redirect hop is individually re-validated against the SSRF filter.
- From the first redirect hop that leaves the original request's origin - a different scheme, host or port, so a downgrade from `https` to `http` counts - and on every later hop, including one that returns to the original origin, only the caller-set headers on `HttpConfig.redirectSafeHeaders` are sent (default `Accept`, `Accept-Language`, `Accept-Encoding`, `User-Agent`, and `Content-Type` with a re-sent body). Every other header - `Authorization`, `Cookie`, `Proxy-Authorization`, `X-Api-Key`, `X-Auth-Token` and whatever else a model put in the call - is dropped, and a credential header (one of those three, a name core's redaction treats as sensitive, or one ending in `token` or `key`) is dropped even if the allowlist names it (#1734). A hop that drops the body (`301`/`302` after a `POST`, `PUT`, `PATCH` or `DELETE`), on either origin, sends no `Content-Type`, neither a caller-set one nor the tool's `content_type` parameter.
- An IPv6 address inside a global unicast prefix is not judged by an IPv4 address embedded in its interface identifier (ISATAP-style `...:5efe:a.b.c.d`); only the prefixes listed above are decoded.
- The SSRF check resolves the host with `InetAddress.getAllByName`, which cannot be interrupted: at the deadline the caller is released, but the worker thread stays in the lookup until the system resolver answers or times out.
- `HttpConfig.timeout` bounds the whole call: name resolution, connecting, every redirect hop and reading the body. A server that sends a byte at a time cannot hold the tool past it; the call fails with a `TIMEOUT:` error.
- Only `GET` and `HEAD` methods are allowed by default (read-only).

**Residual risk:** DNS rebinding attacks (where a hostname resolves to a public IP during validation but a private IP at connection time) are not explicitly mitigated at the Java `HttpURLConnection` level.

### 5. SQLite Journal Files

**Risk:** SQLite creates a journal file (`.db-journal`) alongside the database file during write transactions. If the database is stored in a predictable path, this temporary file may expose partial conversation history. If WAL mode were enabled (`PRAGMA journal_mode=WAL`), additional `.db-wal` and `.db-shm` files would also be created — but `SQLiteMemoryStore` uses SQLite's default DELETE journal mode, so only `.db-journal` applies.

**Mitigation:**
- `SQLiteMemoryStore` path is chosen by the application developer. Use a path under a directory with restricted permissions (e.g., `chmod 700`).
- For ephemeral use, use `SQLiteMemoryStore.inMemory()`, which opens the database at `":memory:"` itself — no files are created.
- Delete the `.db-journal` file alongside the database file when decommissioning a store.

### 6. Workspace Sandbox Escapes

**Risk:** the workspace runner's `executeCommand` lets an agent run programs inside the workspace. A program on the
allowlist can do more than its name suggests: `find -exec` and `git -c alias.x='!cmd' x` run other programs,
`find -delete` and `git clean` delete files, `sort -o` and `uniq in out` write them, `cp -L` and `chmod -L` follow
links out of the workspace, and any path argument can name a file outside it
([#1715](https://github.com/llm4s/llm4s/issues/1715)).

**Mitigation (implemented):**
- Every check below applies to both ways a command reaches the runner: a direct `executeCommand`, and the WebSocket
  protocol that `ContainerisedWorkspace` uses, which until [#1756](https://github.com/llm4s/llm4s/issues/1756) ran the
  raw command string through `sh -c` with the client's environment and skipped all of them. Both now share one
  function that checks the command and builds its process, and refuse with the same codes.
- The command is split into words and started directly, without a shell, and shell metacharacters (`&`, `|`, `<`, `>`,
  `^`, `;`, `` ` ``, `$`, `%`) are refused in every word. Its standard input is the null device.
- The executable must be a bare name in `WorkspaceSandboxConfig.allowedCommands`; a path to an executable is
  refused. What is enforced is decided by the runner's environment alone. `WORKSPACE_SANDBOX_PROFILE` picks the
  profile: unset, the runner uses `permissive`, whose list is `ReadWriteCommands` (`ReadOnlyCommands` plus `cp`, `mv`,
  `rm`, `mkdir`, `touch`, `chmod`, `copy` and `move`); `locked` (`shellAllowed = false`) refuses every command; an
  unknown name stops the runner. `WORKSPACE_EXTRA_COMMANDS` adds bare program names to the profile's list (set by
  `ContainerisedWorkspace`'s `extraAllowedCommands`); a shell or a launcher such as `env` or `xargs` cannot be added,
  and an added program has no per-program option rules, so add only what the agent needs. `ReadOnlyCommands` is the field default, for a `WorkspaceSandboxConfig` constructed directly. The
  workspace client has no sandbox setting of its own. On Windows, built-ins such as
  `echo`, `dir`, `type`, `copy` and `move` run through `cmd.exe /c`, after the forbidden-character check and the
  checks below.
- Each program's arguments are checked (`ARGUMENT_NOT_ALLOWED`): options that delete, write, run another program,
  read a list of file names or follow symbolic links are refused (`find -delete`/`-exec`/`-fprint`/`-L`, `sort -o`,
  `wc --files0-from`, `ls -L`, `grep -R`/`-S`, `cp -L`/`-H`/`-s`, `chmod -L`/`-H`), `uniq` takes at most one operand,
  `hostname` none, and `git` runs only read subcommands (`status`, `log`, `show`, `diff`, `ls-files`, `ls-tree`,
  `grep`, `blame`, `rev-parse`, listing `branch`) with no global option bar `--version`, `--no-pager` and a few
  harmless ones, and without `--output`, `--ext-diff`, `--textconv`, `--show-signature` or `grep -O`. Every argument
  is scanned, including those after `--`; short options are matched inside clusters and long options under any
  abbreviation.
- Every path argument, and the working directory, must really lie inside the workspace (`PATH_ESCAPE_ATTEMPT`):
  it is resolved the way the kernel resolves it, following symbolic links component by component. For `cp`, so are
  the names it will write, since `cp` writes through a link it finds there, and a recursive `cp` refuses a destination
  directory that holds a link leading outside. On POSIX, an `rm` or `unlink` operand or an `mv` source that names a
  symbolic link itself (no trailing `/`, no `rm -r`) is judged by the directory holding it, so a link out of the
  workspace can be removed or renamed without touching what it points to. An `mv` or `cp` of several sources runs one
  operation after another while every path is checked before the first, so it is refused (`ARGUMENT_NOT_ALLOWED`)
  when one of its paths goes through a name another source's operation creates, replaces or removes, or when `mv`
  moves the working directory: `mv sub/l l/secret.txt .` would otherwise move a relative link to where it points out
  and then move a file in through it ([#1776](https://github.com/llm4s/llm4s/issues/1776)). An argument longer than
  4096 characters, or a command whose paths need more than 20000 lookups, is refused rather than walked.
- `environment` may set only locale and display variables (`ENVIRONMENT_NOT_ALLOWED`), so `GIT_*`, `PAGER`,
  `LD_PRELOAD`, `PATH` and `HOME` cannot redirect a program.
- The program is started by an absolute path the runner found itself, never by its bare name
  ([#1790](https://github.com/llm4s/llm4s/issues/1790)): on Windows the system directory, the Windows directory, then
  `PATH` (`<name>.exe`, then `<name>.com`); on POSIX `PATH`. It never searches the runner's current directory, which
  `CreateProcess` searched before the system directory, nor an empty or relative `PATH` entry, which the JDK read from
  the command's working directory, nor anything inside the workspace, so a `git.exe` or `cat` an agent wrote cannot
  run in place of the real one. A program found only there is refused (`EXECUTABLE_NOT_ALLOWED`); built-ins run
  through the system directory's `cmd.exe`. On Windows the allowlist matches names ignoring case and a `.exe` /
  `.com` extension, so an entry `sort.exe` gets `sort`'s rules.
- `git` cannot be made to run a program by its repository ([#1721](https://github.com/llm4s/llm4s/issues/1721)),
  whether the agent wrote the configuration or the repository arrived with it. Every `git` the runner starts reads no
  system or global configuration or attributes (`HOME` and `XDG_CONFIG_HOME` are an empty directory the runner owns),
  may use no transport (so no lazy fetch of a partial clone, `core.sshCommand` or credential helper), and gets `-c`
  overrides that switch off `core.fsmonitor`, hooks (`core.hooksPath`), `diff.external`, `log.showSignature` and
  `gpg.*.program`, submodule recursion and implicit bare repositories, plus `--no-ext-diff`, `--no-textconv` and
  `--ignore-submodules=all` where the subcommand takes them. Filter and diff drivers are named by attributes, so the
  runner lists the repository's configuration just before the command and blanks every driver it defines; a
  configuration it cannot account for (an include, a configured hook, a driver name that is not printable ASCII)
  refuses the command (`GIT_CONFIG_NOT_ALLOWED`), and `git status -v`, whose staged diff runs textconv drivers, is
  refused (`ARGUMENT_NOT_ALLOWED`). Writes into `.git` - in any spelling a file system may take for it, and through a
  link - are refused: `writeFile` / `modifyFile` with `PATH_NOT_ALLOWED`, any program other than the read-only ones
  with `ARGUMENT_NOT_ALLOWED`.
- The runner normally runs in a Docker container, an additional OS-level boundary.

**Not covered:**
- A repository that arrives in the workspace with a `.git/config` `core.worktree`, a `.git/commondir` or a
  `.git/objects/info/alternates` still points git at files outside; git reads them but runs nothing from them. A
  program added with `WORKSPACE_EXTRA_COMMANDS` that writes files without naming them (a script, an archive tool) can
  still write into `.git`, which git then reads without running what it names. The runner lists a repository's
  configuration just before running git, so a concurrent command that swaps in another repository between the two is
  not seen.
- `diff -r` follows symbolic links it meets inside the tree it walks, and no portable option stops it.
- A link that the read-write list moves or copies to another depth (`mv a/b/rel rel`) can come to point outside.
  Path arguments through it are refused, in the same command or a later one, and a later recursive `cp` into its
  directory is refused, but the runner does not remove the link itself (the agent can, with `rm rel`).
- A program added with `WORKSPACE_EXTRA_COMMANDS` that makes links or unpacks an archive (`ln`, `tar`, `unzip`) is not
  modelled: its arguments are checked, not the link text it writes, the names in an archive, or a later argument
  that goes through a name an earlier operation of the same command made.
- The checks run before the program starts; a link made at a checked name by a concurrent command is not seen.
  Windows `copy` has the path rule but not `cp`'s destination checks.
- A path rule cannot tell a path from text, so an argument or option value that is absolute or climbs out with `..`
  is refused even when it is text: a `grep` pattern `/api` (write `[/]api`), `git log --grep=/x`. A relative value
  without `..` (`--since=2024/01/01`, `--exclude=*/target/*`) and `sort -t/` run.

**Recommended practice:** use the `locked` profile, or `ReadOnlyCommands`, unless the agent needs more. Leave `git`
out of `allowedCommands` when the agent can also write files and the repository's configuration matters. Do not
give the workspace credentials or network access that an escaped process could exploit. See
[Workspace sandbox](workspace-sandbox#command-policy).

### 7. Dependency CVEs

**Risk:** Third-party dependencies may contain published CVEs.

**Mitigation (implemented):**
- Dependabot is configured (`.github/dependabot.yml`) to scan GitHub Actions workflows weekly and flag outdated dependencies.
- Scala Steward (`.github/workflows/scala-steward.yml`, configured by `.scala-steward.conf`) opens weekly pull requests for outdated sbt dependencies, sbt plugins, sbt itself and the Scala version. Neither tool raises security alerts for sbt dependencies; they keep versions current, which is what keeps published fixes flowing in.
- The `secret-scan.yml` workflow prevents committed secrets from reaching the repository.

**Recommended practice:** Review the Scala Steward pull requests promptly, run `sbt dependencyUpdates` (from `sbt-dependency-updates`) to see what is behind, and check the National Vulnerability Database (NIST) for the libraries llm4s depends on.

## Security Checklist for PR Authors

Before merging code that touches provider clients, tool implementations, or memory stores:

- [ ] Does the change log or surface any `String` that could contain an API key without first passing it through `Redaction.redact()`?
- [ ] Does a new tool implementation make outbound network calls? Ensure it uses `HttpConfig` with SSRF protection enabled.
- [ ] Does a new tool consume untrusted external content and feed it back into the conversation? Document the indirect injection risk.
- [ ] Does the change store data to disk? Ensure the file path is not predictable and document cleanup requirements.
- [ ] Are new environment variables or secrets introduced? Update `Llm4sConfig` and ensure they are masked in `toString`.
