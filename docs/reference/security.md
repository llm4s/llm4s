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
- `LoggingMiddleware` applies `ContentRedactor` to message content before logging (when `includeMessages = true`).
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

**Risk:** `WorkspaceRegexSafetyManager` uses pattern matching to decide which shell commands are allowed inside the containerised workspace. A carefully crafted command string might bypass the regex checks.

**Mitigation (implemented):**
- The regex-based allowlist restricts commands to a known safe set.
- The workspace module runs in a Docker container, providing an additional OS-level boundary.

**Recommended practice:** Treat the regex layer as defence-in-depth only. Do not grant the workspace access to credentials or network resources that an escaped process could exploit.

### 7. Dependency CVEs

**Risk:** Third-party dependencies may contain published CVEs.

**Mitigation (implemented):**
- Dependabot is configured (`.github/dependabot.yml`) to scan GitHub Actions workflows weekly and flag outdated dependencies.
- The `secret-scan.yml` workflow prevents committed secrets from reaching the repository.

**Recommended practice:** Periodically run `sbt dependencyUpdates` locally and review the OWASP National Vulnerability Database for Scala ecosystem libraries.

## Security Checklist for PR Authors

Before merging code that touches provider clients, tool implementations, or memory stores:

- [ ] Does the change log or surface any `String` that could contain an API key without first passing it through `Redaction.redact()`?
- [ ] Does a new tool implementation make outbound network calls? Ensure it uses `HttpConfig` with SSRF protection enabled.
- [ ] Does a new tool consume untrusted external content and feed it back into the conversation? Document the indirect injection risk.
- [ ] Does the change store data to disk? Ensure the file path is not predictable and document cleanup requirements.
- [ ] Are new environment variables or secrets introduced? Update `Llm4sConfig` and ensure they are masked in `toString`.
