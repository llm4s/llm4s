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
- `HttpConfig.blockInternalIPs = true` by default; `NetworkSecurity.validateHostname()` resolves DNS and checks resolved IPs against private CIDR ranges (RFC 1918, RFC 5735, RFC 4193) and link-local ranges.
- `HttpConfig.DefaultBlockedDomains` blocks `localhost`, `127.0.0.1`, `0.0.0.0`, `::1`, `metadata.google.internal`, `metadata.internal`, and `169.254.169.254` by hostname.
- Redirects are NOT followed by default (`followRedirects = false`). When enabled, each redirect hop is individually re-validated against the SSRF filter.
- Sensitive headers (`Authorization`, `Cookie`, `Proxy-Authorization`) are stripped on cross-origin redirect hops.
- Only `GET` and `HEAD` methods are allowed by default (read-only).

**Residual risk:** DNS rebinding attacks (where a hostname resolves to a public IP during validation but a private IP at connection time) are not explicitly mitigated at the Java `HttpURLConnection` level.

### 5. SQLite Journal Files

**Risk:** SQLite creates a journal file (`.db-journal`) alongside the database file during write transactions. If the database is stored in a predictable path, this temporary file may expose partial conversation history. If WAL mode were enabled (`PRAGMA journal_mode=WAL`), additional `.db-wal` and `.db-shm` files would also be created — but `SQLiteMemoryStore` uses SQLite's default DELETE journal mode, so only `.db-journal` applies.

**Mitigation:**
- `SQLiteMemoryStore` path is chosen by the application developer. Use a path under a directory with restricted permissions (e.g., `chmod 700`).
- For ephemeral use, pass `":memory:"` to `SQLiteMemoryStore.inMemory()` — no files are created.
- Delete the `.db-journal` file alongside the database file when decommissioning a store.

### 6. Workspace Sandbox Escapes

**Risk:** the workspace runner's `executeCommand` lets an agent run programs inside the workspace. A program on the
allowlist can do more than its name suggests: `find -exec` and `git -c alias.x=!cmd` run other programs,
`find -delete` and `git clean` delete files, `sort -o` and `uniq in out` write them, and any path argument can name
a file outside the workspace (#1715).

**Mitigation (implemented):**
- The command is split into words and started directly, without a shell, and shell metacharacters (`&`, `|`, `<`, `>`,
  `^`, `;`, `` ` ``, `$`, `%`) are refused in every word.
- The executable must be a bare name in `WorkspaceSandboxConfig.allowedCommands`. `ReadOnlyCommands` is the
  default; `ReadWriteCommands` (the `permissive` profile) adds `cp`, `mv`, `rm`, `mkdir`, `touch`, `chmod`, `copy`
  and `move`. The `locked` profile turns the shell off.
- Each program's arguments are checked (`ARGUMENT_NOT_ALLOWED`): options that delete, write, run another program,
  read a list of file names or follow every symbolic link are refused (`find -delete`/`-exec`/`-fprint`/`-L`,
  `sort -o`, `wc --files0-from`, `ls -L`, `grep -R`), `uniq` takes at most one operand, `hostname` none, and `git`
  runs only read subcommands (`status`, `log`, `show`, `diff`, `ls-files`, `ls-tree`, `grep`, `blame`, `rev-parse`,
  listing `branch`) with no global option bar `--version`, `--no-pager` and a few harmless ones, and without
  `--output`, `--ext-diff`, `--textconv`, `--show-signature` or `grep -O`. Every argument is scanned, including
  those after `--`; short options are matched inside clusters and long options under any abbreviation.
- Every path argument, and the working directory, must really lie inside the workspace (`PATH_ESCAPE_ATTEMPT`):
  it is resolved the way the kernel resolves it, following symbolic links component by component.
- `environment` may set only locale and display variables (`ENVIRONMENT_NOT_ALLOWED`), so `GIT_*`, `PAGER`,
  `LD_PRELOAD`, `PATH` and `HOME` cannot redirect a program.
- The runner normally runs in a Docker container, an additional OS-level boundary.

**Not covered:** `git` reads the repository's own `.git/config`; where the agent can write files (the `writeFile`
operation, or the read-write allowlist) it can set `core.fsmonitor`, `diff.external` or a filter driver that a later
`git status` or `git diff` runs. `diff -r` follows symbolic links met inside the tree it walks. A path rule cannot
tell a path from text, so a `grep` pattern that starts with `/` is refused.

**Recommended practice:** use the `locked` profile, or `ReadOnlyCommands`, unless the agent needs more. Leave `git`
out of `allowedCommands` when the agent can also write files and the repository's configuration matters. Do not
give the workspace credentials or network access that an escaped process could exploit. See
[Workspace sandbox](workspace-sandbox.md#command-policy).

### 7. Dependency CVEs

**Risk:** Third-party dependencies may contain published CVEs.

**Mitigation (implemented):**
- Dependabot is configured (`.github/dependabot.yml`) to scan GitHub Actions workflows weekly and flag outdated dependencies.
- Scala Steward (`.github/workflows/scala-steward.yml`, configured by `.scala-steward.conf`) opens weekly pull requests for outdated sbt dependencies, sbt plugins, sbt itself and the Scala version. Neither tool raises security alerts for sbt dependencies; they keep versions current, which is what keeps published fixes flowing in.
- The `secret-scan.yml` workflow prevents committed secrets from reaching the repository.

**Recommended practice:** Periodically run `sbt dependencyUpdates` locally and review the OWASP National Vulnerability Database for Scala ecosystem libraries.

## Security Checklist for PR Authors

Before merging code that touches provider clients, tool implementations, or memory stores:

- [ ] Does the change log or surface any `String` that could contain an API key without first passing it through `Redaction.redact()`?
- [ ] Does a new tool implementation make outbound network calls? Ensure it uses `HttpConfig` with SSRF protection enabled.
- [ ] Does a new tool consume untrusted external content and feed it back into the conversation? Document the indirect injection risk.
- [ ] Does the change store data to disk? Ensure the file path is not predictable and document cleanup requirements.
- [ ] Are new environment variables or secrets introduced? Update `Llm4sConfig` and ensure they are masked in `toString`.
