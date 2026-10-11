---
layout: page
title: Production Deployment
nav_order: 22
parent: User Guide
---

# Production Deployment Guide

This guide covers deploying LLM4S applications to production environments. It's specific to LLM4S patterns—not general application deployment advice.

---

## Overview

A production-ready LLM4S application needs to address:

1. **Configuration & Secrets** - Safe handling of API keys and provider credentials
2. **Provider Reliability** - Graceful handling of rate limits, timeouts, and failures
3. **Resource Management** - Proper lifecycle handling for clients and connections
4. **Observability** - Tracing, logging, and monitoring for production visibility
5. **Cost Control** - Token usage awareness and caching strategies

LLM4S follows a configuration boundary principle: all configuration loading happens at the application edge, and core code receives typed settings via dependency injection.

---

## Configuration & Secrets

### Never Commit Secrets

API keys belong in environment variables or a secrets manager—never in source control. A section's
key is its own `apiKey` when it sets one, and otherwise its vendor's shared
`llm4s.credentials.<provider>.apiKey`, which each provider module binds to the vendor's variable
(`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...; see [API keys](getting-started/configuration.md#api-keys)).
That default is convenient in development. **In production, give every section its own `apiKey`**:
a section meant for a second account whose key is missing would otherwise fall back to the shared
key and silently bill the default account. llm4s logs at INFO which path each key came from
(`llm4s.providers.openai-main: API key from llm4s.providers.openai-main.apiKey`), never the value.
Nothing reads `LLM_MODEL`.

```bash
# .env (add to .gitignore)
OPENAI_API_KEY=<your-openai-key>
ANTHROPIC_API_KEY=<your-anthropic-key>
```

### Configuration Hierarchy

LLM4S reads configuration through PureConfig's default source, in this order (highest to lowest
precedence):

1. **System properties** (`-Dllm4s.providers.provider=claude`)
2. **application.conf** (HOCON in `src/main/resources/`, or the file named by `-Dconfig.file` /
   `-Dconfig.resource`)
3. **reference.conf** (the defaults shipped in each llm4s module)

Environment variables are not a layer of their own: one is read only where a `${?VAR}` substitution
binds it - in your `application.conf`, or in a module's `reference.conf` (tracing, embeddings,
tools and each provider's vendor key bind theirs there; see
[the variables llm4s reads](getting-started/configuration.md#environment-variables-llm4s-reads)).

### Production application.conf

Create `src/main/resources/application.conf` with a named section per provider, each binding its
own key explicitly, so which account a section bills is written down:

```hocon
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below
    provider = ${?LLM4S_PROVIDER}     # optional override from the environment (your binding)

    openai-main {
      provider = "openai"
      model    = "gpt-4o"
      apiKey   = ${?OPENAI_API_KEY}      # explicit: this section bills the OPENAI_API_KEY account
    }

    claude {
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
      apiKey   = ${?ANTHROPIC_API_KEY}
    }
  }

  # Tracing already binds TRACING_MODE (llm4s-core), LANGFUSE_* (llm4s-observability)
  # and OTEL_SERVICE_NAME / OTEL_EXPORTER_OTLP_ENDPOINT (llm4s-observability-otel) in
  # each module's reference.conf. OTLP exporter headers come from this map, not from
  # OTEL_EXPORTER_OTLP_HEADERS:
  # tracing.opentelemetry.headers { Authorization = ${?OTEL_AUTH_HEADER} }
}
```

Each provider comes from its own module (`llm4s-openai`, `llm4s-anthropic`, ...); add the ones your
sections name. On 0.4.1 and earlier they all ship inside `llm4s-core`.

Only the section you load is validated. `Llm4sConfig.defaultProvider()` checks the default section
and `Llm4sConfig.provider("claude")` checks `claude`; a section with no key available, or
whose provider module is not on the classpath, fails only when it is the one asked for. The file
above can therefore be deployed with just `OPENAI_API_KEY` set while `openai-main` is the default.
(Up to 0.4.1 every section was validated on every load, so it needed both keys.)

### Check for inherited keys with the config policy

`llm4s-config-policy`'s `prod` preset (`ConfigPolicy.prodSafeDefaults`) includes the rule
`ownApiKey`: every named chat section whose provider needs a key must set its own `apiKey`. A
section that does not - and would therefore use the vendor's shared key - fails the check, whether
or not the shared variable is set where the check runs:

```text
 - [ownApiKey] llm4s.providers.openai-batch sets no apiKey, so it would use the shared llm4s.credentials.openai.apiKey; set llm4s.providers.openai-batch.apiKey to the key for the account it should bill
```

Run it in CI against your production config:
`sbt "configPolicy/runMain org.llm4s.configpolicy.CheckPolicies --env=prod --config prod.conf"`.
The rule is off in the `dev` preset; enable it in a custom policy with
`withOwnApiKeyRequired(CatalogEnvironment.Prod)`. `Llm4sConfig.apiKeySources()` reports the same
information - `ApiKeySource.Section` or `ApiKeySource.Credentials` per section - for checks of your
own.

### Per-Environment Configuration

Pick the provider per environment without changing code:

- **Override the default** with a system property: `-Dllm4s.providers.provider=claude`.
- **Bind the default yourself** to a variable of your choosing:

  ```hocon
  llm4s.providers {
    provider = "openai-main"
    provider = ${?LLM4S_PROVIDER}     # your binding; any name works
  }
  ```

- **Ship one file per environment** and select it at startup, if you prefer each environment's
  file to hold only the sections it uses:

  ```bash
  java -Dconfig.resource=prod.conf -jar app.jar        # src/main/resources/prod.conf
  java -Dconfig.file=/etc/myapp/app.conf -jar app.jar  # a file outside the jar
  ```

- **Load a section by name** where one application uses several: `Llm4sConfig.provider("claude")`.

### Configuration Boundary Pattern

LLM4S enforces a strict configuration boundary. Core code never reads configuration directly—all PureConfig and environment access happens in `org.llm4s.config`, and typed settings are injected into your application.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService

// At the application edge (main, controller, etc.)
val result = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  tracingConfig  <- Llm4sConfig.tracing()
  client         <- LLMConnect.getClient(providerConfig)
} yield (client, tracingConfig)

// Pass typed config into your services—don't call Llm4sConfig inside core logic
class MyService(client: LLMClient, tracingSettings: TracingSettings) {
  // Use injected dependencies
}
```

This pattern makes testing easier and keeps configuration concerns at the edges.

### Secrets in Kubernetes

For Kubernetes deployments, use Secrets and reference them in your pod spec. The variable names are
the ones your sections bind (`apiKey = ${?OPENAI_API_KEY}`) - or, for a section without its own
`apiKey`, the vendor's variable its provider module binds; `TRACING_MODE` is bound by
llm4s-core's `reference.conf` and `LANGFUSE_*` by llm4s-observability's. Supply the key of each section the
deployment loads: with the file above and `openai-main` as the default, `OPENAI_API_KEY`, plus
`ANTHROPIC_API_KEY` if it also calls `Llm4sConfig.provider("claude")`.

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: llm4s-secrets
type: Opaque
stringData:
  OPENAI_API_KEY: <your-openai-key>
  ANTHROPIC_API_KEY: <your-anthropic-key>   # the claude section is validated too
  LANGFUSE_PUBLIC_KEY: <your-langfuse-public-key>
  LANGFUSE_SECRET_KEY: <your-langfuse-secret>
---
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: app
          envFrom:
            - secretRef:
                name: llm4s-secrets
          env:
            # Selects a section through the `provider = ${?LLM4S_PROVIDER}`
            # binding in the application.conf above
            - name: LLM4S_PROVIDER
              value: "openai-main"
            - name: TRACING_MODE
              value: "langfuse"
```

For enterprise environments, consider HashiCorp Vault or AWS Secrets Manager with init containers or sidecar injection.

---

## Provider Reliability

### Rate Limits

LLM providers enforce rate limits. In production, expect and handle `429 Too Many Requests`:

```scala
import org.llm4s.error.{RateLimitError, LLMError}
import scala.concurrent.duration._

def callWithBackoff(
  client: LLMClient,
  conversation: Conversation,
  maxRetries: Int = 3
): Result[Completion] = {
  def attempt(remaining: Int, delay: FiniteDuration): Result[Completion] = {
    client.complete(conversation) match {
      case Left(RateLimitError(_, retryAfter, _)) if remaining > 0 =>
        // retryAfter is the provider's Retry-After hint, already a FiniteDuration
        Thread.sleep(retryAfter.getOrElse(delay).toMillis)
        attempt(remaining - 1, delay * 2)
      case other => other
    }
  }
  attempt(maxRetries, 1.second)
}
```

### Timeout Configuration

Set reasonable timeouts at multiple levels:

```hocon
# application.conf
akka.http.client {
  connecting-timeout = 10s
  idle-timeout = 60s
}

```

### Provider Fallbacks (Planned)

LLM4S doesn't yet have built-in provider fallback, but you can implement it:

```scala
def withFallback(
  primary: LLMClient,
  fallback: LLMClient,
  conversation: Conversation
): Result[Completion] = {
  primary.complete(conversation) match {
    case Left(_) => fallback.complete(conversation)
    case success => success
  }
}
```

Multi-provider resilience (circuit breakers, automatic failover) is planned for a future release.

### Validate on Startup

Call `client.validate()` during application startup to fail fast on misconfiguration:

```scala
val client = LLMConnect.getClient(config).flatMap { c =>
  c.validate().map(_ => c)
}

client match {
  case Left(error) =>
    logger.error(s"LLM client validation failed: $error")
    System.exit(1)
  case Right(c) =>
    // Proceed with healthy client
}
```

---

## Resource Management

### LLMClient Lifecycle

`LLMClient` holds HTTP connections and thread pools. Create it once at startup and close it on shutdown:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{LLMClient, LLMConnect}
import org.llm4s.model.ModelRegistryService

class Application {
  private var client: Option[LLMClient] = None

  def start(): Unit = {
    client = (for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      c <- LLMConnect.getClient(providerConfig)
    } yield c) match {
        case Right(c) => Some(c)
        case Left(_)  => None
      }
  }

  def shutdown(): Unit = {
    client.foreach(_.close())
  }
}
```

### Using AutoCloseable

For scoped usage, leverage Scala's `Using`:

```scala
import scala.util.Using

Using.resource(
  LLMConnect.getClient(config) match {
    case Right(c) => c
    case Left(e)  => throw new RuntimeException(e.toString)
  }
) { client =>
  // Client is automatically closed after this block
  client.complete(conversation)
}
```

### Framework Integration

**Akka/Pekko:**

```scala
import akka.actor.CoordinatedShutdown

CoordinatedShutdown(system).addTask(
  CoordinatedShutdown.PhaseServiceStop,
  "close-llm-client"
) { () =>
  Future {
    client.close()
    Done
  }
}
```

**Play Framework:**

```scala
import play.api.inject.ApplicationLifecycle

class LLMModule @Inject()(lifecycle: ApplicationLifecycle) {
  val client: LLMClient = // ...

  lifecycle.addStopHook { () =>
    Future.successful(client.close())
  }
}
```

**ZIO:**

```scala
import zio._

val clientLayer: ZLayer[Scope, LLMError, LLMClient] =
  ZLayer.scoped {
    ZIO.acquireRelease(
      ZIO.fromEither(LLMConnect.getClient(config))
    )(client => ZIO.succeed(client.close()))
  }
```

### Workspace Execution

For workspace-based execution (containerized command execution), the `ContainerisedWorkspace` manages its own lifecycle:

```scala
import scala.util.Using
import org.llm4s.workspace.ContainerisedWorkspace

// ContainerisedWorkspace does not extend AutoCloseable — define a Releasable
implicit val workspaceReleasable: Using.Releasable[ContainerisedWorkspace] =
  (ws: ContainerisedWorkspace) => ws.stopContainer()

Using.resource(new ContainerisedWorkspace("/app/workspace", "llm4s-runner:latest", 8090)) { workspace =>
  // Run an allowlisted program inside the isolated container: a program and its arguments, not shell text
  workspace.executeCommand("ls -la src")
}
```

---

## Observability

### Tracing Modes

LLM4S supports four tracing modes:

| Mode | Use Case | Configuration |
|------|----------|---------------|
| `langfuse` | Production monitoring | `TRACING_MODE=langfuse` |
| `opentelemetry` | OpenTelemetry tracing | `TRACING_MODE=opentelemetry` |
| `console` | Development/debugging | `TRACING_MODE=console` |
| `noop` | Disabled | `TRACING_MODE=noop` |

### Langfuse Setup

Langfuse provides production-grade LLM observability. Its backend ships in `llm4s-observability`
(`"org.llm4s" %% "llm4s-observability" % llm4sVersion`); on 0.4.1 and earlier it is part of
`llm4s-core`:

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=<your-langfuse-public-key>
LANGFUSE_SECRET_KEY=<your-langfuse-secret-key>
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted
```

Both keys are required: with either unset, `Tracing.create` logs a `ConfigurationError` naming it
(`llm4s.tracing.langfuse.publicKey (LANGFUSE_PUBLIC_KEY)`) and falls back to no tracing, so check
startup logs, or build the tracer with `Tracing.fromSettings` to fail fast.

What gets traced:

- **Traces** - Top-level request lifecycle
- **Generations** - Each LLM call with model, tokens, latency
- **Spans** - Tool executions, retrieval operations
- **Events** - User inputs, errors, custom markers

Example trace structure for a RAG query:

```
Trace: "RAG Query Processing"
├── Span: "Document Retrieval" (200ms)
│   └── Event: "Retrieved 5 documents"
├── Generation: "RAG Response" (1200ms)
│   ├── Model: gpt-4o
│   ├── Input tokens: 1,234
│   └── Output tokens: 456
└── Event: "Final Response"
```

### Structured Logging

LLM4S uses SLF4J. Configure your logging backend (Logback, Log4j2) for JSON output in production:

```xml
<!-- logback.xml -->
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
  </appender>

  <logger name="org.llm4s" level="INFO"/>
  
  <root level="WARN">
    <appender-ref ref="JSON"/>
  </root>
</configuration>
```

### Health Checks

Expose health endpoints that verify LLM connectivity:

```scala
// Integrate with your framework's health check mechanism
def isLLMHealthy(): Boolean =
  client.validate().isRight
```

---

## Deployment Patterns

### Single-Node (Development/Small Scale)

Suitable for experiments, small teams, or low-traffic applications:

```
┌─────────────────────────────────────┐
│           Application               │
│  ┌─────────────┐  ┌──────────────┐  │
│  │ LLM4S Core  │  │   Tracing    │  │
│  │             │  │  (Console)   │  │
│  └─────────────┘  └──────────────┘  │
│         │                           │
│         ▼                           │
│  ┌─────────────┐                    │
│  │   Ollama    │ (or cloud provider)│
│  └─────────────┘                    │
└─────────────────────────────────────┘
```

```hocon
# application.conf
llm4s.providers {
  provider = "ollama-local"
  ollama-local {
    provider = "ollama"
    model    = "llama3.2"
    baseUrl  = "http://localhost:11434"   # required for Ollama
  }
}
```

```bash
TRACING_MODE=console   # bound by llm4s-core's reference.conf; console is also the default
```

### Kubernetes (Production)

Standard production deployment with observability:

```
┌──────────────────────────────────────────────────┐
│                  Kubernetes Cluster              │
│                                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐ │
│  │  App Pod   │  │  App Pod   │  │  App Pod   │ │
│  │  (LLM4S)   │  │  (LLM4S)   │  │  (LLM4S)   │ │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘ │
│        │               │               │        │
│        └───────────────┼───────────────┘        │
│                        ▼                        │
│  ┌─────────────────────────────────────────────┐│
│  │             Langfuse (Tracing)              ││
│  └─────────────────────────────────────────────┘│
│                        │                        │
└────────────────────────┼────────────────────────┘
                         ▼
              ┌──────────────────────┐
              │   LLM Provider API   │
              │ (OpenAI/Anthropic)   │
              └──────────────────────┘
```

Key considerations:

- Store secrets in Kubernetes Secrets or external vault
- Use horizontal pod autoscaling based on request queue depth (not CPU)
- Configure appropriate resource limits for memory-intensive operations
- Set up liveness/readiness probes that include LLM connectivity

### Enterprise VPC

For regulated industries or multi-tenant deployments:

```
┌─────────────────────────────────────────────────────────┐
│                    Private VPC                          │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │
│  │  App Tier   │───▶│  LLM Proxy  │───▶│  Audit Log  │ │
│  │  (LLM4S)    │    │  (Rate Lim) │    │  (S3/ELK)   │ │
│  └─────────────┘    └─────────────┘    └─────────────┘ │
│         │                  │                           │
│         │           ┌──────┴──────┐                    │
│         │           ▼             ▼                    │
│         │     ┌──────────┐  ┌──────────┐              │
│         │     │  Vault   │  │ Langfuse │              │
│         │     │ (Secrets)│  │ (Self-   │              │
│         │     └──────────┘  │  hosted) │              │
│         │                   └──────────┘              │
│         ▼                                              │
│  ┌─────────────────────────────────────────────────┐  │
│  │          Azure OpenAI (Private Endpoint)        │  │
│  └─────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

Key considerations:

- Use Azure OpenAI with Private Link or AWS Bedrock for data residency
- Deploy self-hosted Langfuse within your VPC
- Implement centralized audit logging for compliance
- Use HashiCorp Vault for secrets rotation

---

## Scaling & Cost Control

### Token Budgets

Use `LLMClient.getContextBudget()` to stay within limits:

```scala
import org.llm4s.agent.{Agent, ContextWindowConfig}
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

val budgetTokens = client.getContextBudget(HeadroomPercent.Standard)

// Prune what is sent to the model with ContextWindowMiddleware
val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new ContextWindowMiddleware(ContextWindowConfig(maxTokens = Some(budgetTokens)))
  )
  .build()
```

### Conversation Pruning

For long-running conversations, use built-in pruning strategies:

```scala
import org.llm4s.agent.{Agent, ContextWindowConfig, PruningStrategy}
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

// Prune when context exceeds configured limits; the thread keeps its full history
val agent = Agent.builder("assistant", client)
  .withMiddleware(
    new ContextWindowMiddleware(
      ContextWindowConfig(
        maxMessages = Some(50),
        pruningStrategy = PruningStrategy.OldestFirst
      )
    )
  )
  .build()
```

### Caching Considerations

LLM4S includes a `CachingLLMClient` wrapper for basic caching, but production deployments may require external caching (Redis, semantic cache, etc.) depending on scale:

- **Embedding cache** - Store computed embeddings in Redis/Memcached
- **Response cache** - Cache identical prompts (careful with cache invalidation)
- **Semantic cache** - Use vector similarity to find cached similar queries

```scala
// Example: Simple response caching (implement based on your cache backend)
def cachedComplete(
  client: LLMClient,
  conversation: Conversation,
  cache: Cache[String, Completion]
): Result[Completion] = {
  val key = conversation.hashCode.toString
  cache.get(key) match {
    case Some(cached) => Right(cached)
    case None =>
      client.complete(conversation).map { completion =>
        cache.put(key, completion)
        completion
      }
  }
}
```

### Cost Estimation

Track token usage through tracing. With Langfuse, you get automatic cost calculation when model pricing is configured.

For manual tracking:

```scala
completion.usage match {
  case Some(usage) =>
    val inputCost = usage.promptTokens * MODEL_INPUT_PRICE_PER_1K / 1000
    val outputCost = usage.completionTokens * MODEL_OUTPUT_PRICE_PER_1K / 1000
    logger.info(s"Request cost: $$${inputCost + outputCost}")
  case None =>
    logger.warn("Usage data not available")
}
```

---

## Production Checklist

Before deploying to production, verify:

### Configuration

- [ ] API keys are in environment variables or secrets manager (not in code)
- [ ] `application.conf` uses `${?VAR}` substitution for all secrets
- [ ] Provider configuration validated on startup (`client.validate()`)
- [ ] Tracing mode set to `langfuse` (not `console`)

### Reliability

- [ ] Retry logic implemented for rate limits (429 errors)
- [ ] Timeouts configured for HTTP clients
- [ ] Graceful shutdown hooks registered for `LLMClient.close()`
- [ ] Health check endpoint includes LLM connectivity

### Observability

- [ ] Langfuse credentials configured and tested
- [ ] Structured logging enabled (JSON format)
- [ ] Log levels appropriate (INFO for `org.llm4s`, WARN for root)
- [ ] Metrics exported (Prometheus/StatsD if applicable)

### Security

- [ ] API keys rotatable without code changes
- [ ] Secrets not logged (check log output for key patterns)
- [ ] Input validation in place for user-provided prompts
- [ ] Rate limiting at application level (not just provider)

### Cost & Performance

- [ ] Token budgets configured per request type
- [ ] Conversation pruning enabled for long sessions
- [ ] Model selection appropriate for use case (don't use GPT-4 where GPT-3.5 suffices)
- [ ] Embedding caching considered for RAG workloads

### Operations

- [ ] Deployment runbook documented
- [ ] Rollback procedure tested
- [ ] Alerting configured for error rates and latency
- [ ] On-call rotation aware of LLM-specific failure modes

---

## Related Documentation

- [Configuration Guide](getting-started/configuration.md) - Complete configuration reference
- [Configuration Boundary](reference/configuration-boundary.md) - Architecture pattern explanation
- [Langfuse Workflow Patterns](langfuse-workflow-patterns.md) - Tracing event sequences
- [Roadmap](reference/roadmap.md) - Planned reliability and security features
- [Agent Framework](guide/agents/index.md) - Agent lifecycle and state management

---

## Known Limitations (v0.1.x)

Current limitations to be aware of in production:

- **No built-in circuit breaker** - Implement at application level or use Resilience4j
- **No automatic provider fallback** - Must implement manually
- **Tool registries not serializable** - Tools belong to the `Agent`, so rebuild the agent on restart and import saved messages with `history`; the in-memory runtime does not survive a restart (durable checkpointers are Stage 2 of the typed agent runtime)
- **Advanced semantic/embedding caching not included** - Add Redis/vector cache for high-volume RAG

These are tracked for improvement in the [Production Readiness Roadmap](reference/roadmap.md#production-readiness).

This guide will evolve as LLM4S approaches v1.0.

---

## Getting Help

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **GitHub Issues**: [Report problems](https://github.com/llm4s/llm4s/issues)
- **Examples**: [Production-like samples](https://github.com/llm4s/llm4s/tree/main/modules/samples)
