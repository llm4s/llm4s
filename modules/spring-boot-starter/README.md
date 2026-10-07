# llm4s-spring-boot-starter

Spring Boot auto-configuration for llm4s (`org.llm4s:llm4s-spring-boot-starter_3`, built on `llm4s-java-api`).

```properties
llm4s.provider=openai          # openai | anthropic | ollama
llm4s.model=gpt-4o
llm4s.api-key=${OPENAI_API_KEY}
```

Every other provider (Gemini, Azure OpenAI, Mistral, OpenRouter, a self-hosted OpenAI-compatible server, ...) is
configured under `llm4s.providers`; see [Any provider](#any-provider).

```java
@Autowired LLM4STemplate llm;
String reply = llm.complete("Summarise this quarter's results.");
CompletableFuture<String> later = llm.completeAsync("Translate to French.");
```

## Any provider

The flat `llm4s.provider`, `llm4s.model`, `llm4s.api-key` ... keys above are the starter's original, three-provider
form and keep working unchanged. For everything else, set `llm4s.providers.*` properties. They mirror the
`llm4s.providers` block of llm4s's own HOCON configuration, so any provider on the classpath works with no
Spring-specific code, and so does any extra the provider declares:

```properties
llm4s.providers.provider=gemini-main            # the default section
llm4s.providers.gemini-main.provider=gemini
llm4s.providers.gemini-main.model=gemini-2.0-flash
llm4s.providers.gemini-main.api-key=${GOOGLE_API_KEY}
```

```properties
llm4s.providers.provider=azure-main
llm4s.providers.azure-main.provider=azure
llm4s.providers.azure-main.model=my-deployment
llm4s.providers.azure-main.api-key=${AZURE_OPENAI_API_KEY}
llm4s.providers.azure-main.endpoint=https://my-resource.openai.azure.com
llm4s.providers.azure-main.api-version=2024-02-01
```

```properties
llm4s.providers.provider=local
llm4s.providers.local.provider=openai-compatible
llm4s.providers.local.model=my-model
llm4s.providers.local.base-url=http://localhost:8000/v1
llm4s.providers.local.context-window=32000        # extras of the generic provider
llm4s.providers.local.headers.X-Team-Id=team-7    # header names are kept as written
```

- **Names.** Within a section, `kebab-case` and `snake_case` names are read as the `camelCase` names of the HOCON block
  (`api-key` is `apiKey`, `base-url` is `baseUrl`, `api-version` is `apiVersion`). The section name is kept as written.
- **Providers on the classpath.** `llm4s-spring-boot-starter` brings `openai`, `azure`, `requesty`, `anthropic`, `ollama`,
  `gemini` (alias `google`), `vertexai` (alias `vertex`) and the OpenAI-compatible family: `openai-compatible`,
  `deepseek`, `zai`, `openrouter`, `mistral` and `cohere`. A provider module you add to the classpath is found the
  same way, with no change to the starter. Each provider's own keys (`endpoint` for Azure, `project` and `location` for
  Vertex AI, ...) are listed in its module's README.
- **Keys.** A section with no `api-key` uses the vendor's shared key,
  `llm4s.credentials.<provider>.api-key`, and llm4s's own conventional environment variable for the vendor
  (`GOOGLE_API_KEY`, `MISTRAL_API_KEY`, ...) before failing with an error that names both. Set `api-key` in a section
  that should use a second account.
- **Several sections.** Define as many as you like; `llm4s.providers.provider` names the one the starter uses.
  If it is not set, the flat `llm4s.provider` can name a section instead.
- **Which form wins.** As soon as any `llm4s.providers.*` property is set, the block is used and the flat
  `llm4s.model`, `llm4s.api-key`, `llm4s.base-url` ... are ignored. With none, the flat keys are read exactly as before.
- **Context size.** In the block, the context window and the reserved completion size come from llm4s's model
  registry; only the generic `openai-compatible` provider reads `context-window` and `reserve-completion` from the
  section. The flat `llm4s.context-window` keeps working for the three flat providers.
- **Values are plain text.** Spring resolves `${...}` placeholders as it does everywhere; the value is not read as HOCON
  afterwards, so quotes, `$` and braces stay as written. Environment-variable names such as `LLM4S_PROVIDERS_...` are
  not read: use properties, YAML or `@TestPropertySource`.
- **Errors.** An unknown provider id fails startup with llm4s's own message, which names the registered providers and
  the dependency to add. Only the default section is validated, so another section's mistake does not stop the
  application.
- **Health.** The Actuator indicator reports the provider and model the section resolves to, and keeps every key from
  `llm4s.providers.*` and `llm4s.credentials.*` out of its messages.

## Beans

| Bean | Replace it by defining a bean of the same type (name for the executor) |
|------|------|
| `llm4sClient` (`JLlmClient`) | closed on context shutdown |
| `llm4sTaskExecutor` (`ExecutorService`) | defined by name: a bean called `llm4sTaskExecutor` replaces it |
| `llm4sTemplate` (`LLM4STemplate`) | |
| `llmHealthIndicator` (`LlmHealthIndicator`, only with Spring Boot Actuator) | |

Set `llm4s.enabled=false` to switch all of them off.

## `completeAsync`

`completeAsync` never blocks the calling thread. The blocking provider call runs on the
`llm4sTaskExecutor` bean; the returned `CompletableFuture` completes from there and fails with
`LlmException` (the original error is kept). `future.cancel(true)` interrupts the provider call,
which is how llm4s cancels work.

The default executor is a bounded pool of daemon threads named `llm4s-async-N` (idle threads time
out) with a bounded queue; when the queue is full the returned future fails with
`RejectedExecutionException` instead of growing without limit. It is shut down with
`shutdownNow` when the context closes, interrupting calls still running. It is a plain pool rather
than virtual threads so the starter also runs on JDK 17. Your own `llm4sTaskExecutor` must be an
`ExecutorService` whose `submit(..).cancel(true)` interrupts the task (every JDK pool does).

| Property | Default | |
|----------|---------|---|
| `llm4s.async.max-threads` | `16` | worker threads of the default executor |
| `llm4s.async.queue-capacity` | `1000` | queued calls before rejection |

## Health

By default the indicator makes **no provider call**: it is `UP` with `probe=disabled`, `provider` and
`model`, which means "configured", not "reachable". The API key is never reported.

With `llm4s.health.probe=true` it sends a one-token completion (prompt `ping`, `maxTokens=1`) on the
executor, waits at most `probe-timeout`, and caches the outcome (also a failure) for `probe-ttl`, so
health polling does not hit or bill the provider each time. A failed or timed-out probe is `DOWN`
with `probe=failed|timeout` and an `error` message with the API key and token-like strings redacted.
The probe is billed: keep the TTL generous.

| Property | Default | |
|----------|---------|---|
| `llm4s.health.probe` | `false` | opt in to the real probe |
| `llm4s.health.probe-ttl` | `60s` | how long a probe result is reused |
| `llm4s.health.probe-timeout` | `10s` | a slower probe is cancelled and reports DOWN |

Other flat settings: `llm4s.base-url`, `llm4s.organization`, `llm4s.context-window`,
`llm4s.reserve-completion`. All keys are in `META-INF/additional-spring-configuration-metadata.json`.
