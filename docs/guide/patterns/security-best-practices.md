---
layout: page
title: Security Best Practices
description: "Secure LLM4S applications: API keys from files, Vault or AWS Secrets Manager, prompt injection and PII guardrails, encrypted checkpoints and the sandbox."
search_title: "LLM application security: prompt injection, keys and PII"
parent: Real-World Application Patterns
grand_parent: Operations
nav_order: 6
---

# Security Best Practices
{: .no_toc }

Most of what secures an LLM application is already in LLM4S: credentials that never appear in code or logs, guardrails
on what goes in and comes out, encryption of what an agent stores, and limits on what its tools may touch. This page
maps each concern to the component and the guide that covers it.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

---

## API keys

Keep keys out of code and out of checked-in files. In order of preference:

1. **A file the platform mounts and rotates.** A provider section's `apiKeyFile` names a file holding the key; the
   client re-reads it when it changes, so a rotated key is used from the next request with no restart. Mount it with
   mode `0400`: a file readable by its group or others is logged as a warning.
2. **Read from the secret store by llm4s.** `VaultApiKeyProvider` (`llm4s-secrets-vault`, HashiCorp Vault's KV engine)
   and `AwsSecretsManagerApiKeyProvider` (`llm4s-secrets-aws`) fetch, cache and refresh the key over HTTP, with no
   vendor SDK; set one with `withApiKeyProvider` on the provider config. For another secret store, implement
   `ApiKeyProvider` yourself.
3. **An environment variable**, bound by the provider module's `reference.conf` to `llm4s.credentials.<id>.apiKey`
   (for example `OPENAI_API_KEY`). Never read it with `sys.env` yourself.

```hocon
llm4s.providers.openai-main {
  provider   = "openai"
  model      = "gpt-4o-mini"
  apiKeyFile = "/var/run/secrets/llm4s/openai-api-key"   # instead of apiKey
}
```

Whichever `ApiKeyProvider` you use, it goes on the provider config the same way. A section is validated when it is
loaded, so `Llm4sConfig.provider` below still needs the section to resolve a key of its own (the `apiKeyFile` above, for
example); the provider you attach then supplies every key the client sends. When the secret store is the only
source, build the provider config in code instead
([Configuration in code](../../getting-started/configuration-in-code)) and attach the provider to that:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

// keys: for example, from llm4s-secrets-vault,
//   VaultApiKeyProvider(VaultSettings("https://vault.internal:8200", Secret(vaultToken), "llm4s/openai", "apiKey"))
// which returns a Result[VaultApiKeyProvider]
def clientWithKeysFrom(keys: ApiKeyProvider): Result[LLMClient] = for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  config <- Llm4sConfig.provider("openai-main").map(_.withApiKeyProvider(keys))
  client <- LLMConnect.getClient(config)
} yield client
```

Keys are wrapped in `ApiKey` and `Secret`, whose `toString` is redacted, and llm4s's errors never include them. Do not
log a key, or any part of one, yourself. The full rules, the Kubernetes, Vault Agent and CSI driver setups, and the
AWS example are in [Production Deployment](../production-deployment#secrets-and-key-rotation) and
[Rotating keys](../../getting-started/configuration#rotating-keys-apikeyfile-and-apikeyprovider). A second account's
section sets its own key; the config policy's `prod` preset flags one that does not.

## Untrusted input and output

Guardrails are middleware on the agent (`GuardrailMiddleware(input, output)`); a block ends the turn
`AgentStatus.Blocked` and stores nothing of it. The built-in ones that matter for security:

| Concern | Guardrail |
|---|---|
| Prompt injection and jailbreak attempts in user input | `PromptInjectionDetector` (input) |
| Personal data in prompts or answers | `PIIDetector` to block, `PIIMasker` to mask (input and output) |
| A key, token or other secret in a prompt or answer | `SecretLeakGuardrail` (input and output) |
| Oversized input | `LengthCheck` |
| A judgement a pattern cannot make | `LLMSafetyGuardrail` |

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ LengthCheck, PIIMasker, PromptInjectionDetector, SecretLeakGuardrail }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.types.Result

def guardedAgent(client: LLMClient): Result[Agent] =
  Agent.builder("assistant", client)
    .withMiddleware(
      new GuardrailMiddleware(
        input = Seq(new LengthCheck(1, 10000), PromptInjectionDetector(), PIIMasker()),
        output = Seq(SecretLeakGuardrail(), PIIMasker())
      )
    )
    .build()
```

Pattern-based detection is a filter, not a proof. Tool output is untrusted input too (indirect prompt injection), and
the strongest defence is to give an agent only the tools and permissions its task needs. See
[Guardrails](../agents/guardrails) and the [Security Reference](../../reference/security#known-risks-and-mitigations).

## What tools may touch

- **Tool permissions**: `PermissionMiddleware` with `PermissionRule.allow`, `deny` and `ask` decides per resource a
  tool call touches, and denies what no rule matches; `ask` suspends the turn for a person's approval. See
  [tool permissions](../agents/#tool-permissions-timeouts-and-locks) and [Human Review](../agents/human-review).
- **Commands and files**: run them in the workspace container, whose command policy, confinement and egress rules are
  in [Workspace Sandbox](../../reference/workspace-sandbox).
- **HTTP and loaders**: the HTTP tool and the URL loaders refuse private and link-local addresses by default; see
  [SSRF](../../reference/security#4-server-side-request-forgery-ssrf-via-http-tool).

## Data at rest and data retention

- **Encrypt what an agent stores.** `EncryptingCheckpointer`, `EncryptingRunQueue` and `EncryptingLongTermStore`
  (`org.llm4s.agent.encryption`, experimental) wrap any store with per-tenant keys. See
  [encrypting what the store holds](../agents/durable-checkpointers#encrypting-what-the-store-holds).
- **Delete on request.** `Agent.forget(threadId)` removes a thread; memories and vector stores delete by id or
  metadata filter. `RetentionPolicy` prunes old history. See
  [deleting a user's data](../data-protection#deleting-a-users-data).
- **Keep content out of telemetry.** The default console tracer and the durable agent events carry no message
  content; see [logging and tracing defaults](../data-protection#logging-and-tracing-defaults).

## Audit

A durable agent thread is an ordered log of its runs: model calls, tool executions, handoffs and guardrail blocks are
stored as events (without content), with the checkpoints of each step. Together with tracing to Langfuse or
OpenTelemetry, that is the audit trail; who asked is your application's to record beside the thread id. See
[Streaming](../agents/streaming) for the event types and [Production Monitoring](production-monitoring).

## See also

- [Data Protection](../data-protection): egress destinations, residency, personal data in prompts
- [Security Reference](../../reference/security)
- [Permission-based RAG](../permission-based-rag): retrieval limited to what the asking user may see
