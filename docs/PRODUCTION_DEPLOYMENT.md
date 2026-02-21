---
layout: page
title: Production Deployment
nav_order: 11
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

API keys belong in environment variables or a secrets manager—never in source control.

```bash
# .env (add to .gitignore)
LLM_MODEL=openai/gpt-4o
OPENAI_API_KEY=<your-openai-key>
ANTHROPIC_API_KEY=<your-anthropic-key>
```

### Configuration Hierarchy

LLM4S resolves configuration in this order (highest to lowest precedence):

1. **System properties** (`-Dllm4s.llm.model=openai/gpt-4o`)
2. **Environment variables** (`LLM_MODEL`, `OPENAI_API_KEY`)
3. **application.conf** (HOCON in `src/main/resources/`)
4. **reference.conf** (library defaults)

### Production application.conf

Create `src/main/resources/application.conf` for production defaults, using environment variable substitution for secrets:

```hocon
llm4s {
  llm {
    model = ${?LLM_MODEL}
  }

  openai {
    api-key = ${?OPENAI_API_KEY}
    base-url = ${?OPENAI_BASE_URL}
    organization = ${?OPENAI_ORGANIZATION}
  }

  anthropic {
    api-key = ${?ANTHROPIC_API_KEY}
    base-url = ${?ANTHROPIC_BASE_URL}
  }

  azure {
    api-key = ${?AZURE_API_KEY}
    endpoint = ${?AZURE_API_BASE}
    api-version = ${?AZURE_API_VERSION}
  }

  tracing {
    mode = ${?TRACING_MODE}
    langfuse {
      public-key = ${?LANGFUSE_PUBLIC_KEY}
      secret-key = ${?LANGFUSE_SECRET_KEY}
      url = ${?LANGFUSE_URL}
    }
  }
}
```

### Configuration Boundary Pattern

LLM4S enforces a strict configuration boundary. Core code never reads configuration directly—all PureConfig and environment access happens in `org.llm4s.config`, and typed settings are injected into your application.

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

// At the application edge (main, controller, etc.)
val result = for {
  providerConfig <- Llm4sConfig.provider()
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

For Kubernetes deployments, use Secrets and reference them in your pod spec:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: llm4s-secrets
type: Opaque
stringData:
  OPENAI_API_KEY: <your-openai-key>
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
            - name: LLM_MODEL
              value: "openai/gpt-4o"
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

def callWithBackoff(
  client: LLMClient,
  conversation: Conversation,
  maxRetries: Int = 3
): Result[Completion] = {
  def attempt(remaining: Int, delay: Long): Result[Completion] = {
    client.complete(conversation) match {
      case Left(RateLimitError(_, retryAfter, _)) if remaining > 0 =>
        val waitMs = retryAfter.fold(delay)(_ * 1000L)
        Thread.sleep(waitMs)
        attempt(remaining - 1, delay * 2)
      case other => other
    }
  }
  attempt(maxRetries, 1000L)
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
import org.llm4s.llmconnect.{LLMClient, LLMConnect}

class Application {
  private var client: Option[LLMClient] = None

  def start(): Unit = {
    client = Llm4sConfig.provider()
      .flatMap(LLMConnect.getClient) match {
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
  // Execute a shell command inside the isolated container
  workspace.executeCommand("python main.py")
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

Langfuse provides production-grade LLM observability:

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=<your-langfuse-public-key>
LANGFUSE_SECRET_KEY=<your-langfuse-secret-key>
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted
```

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

```bash
# .env
LLM_MODEL=ollama/llama3.2
OLLAMA_BASE_URL=http://localhost:11434
TRACING_MODE=console
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
import org.llm4s.agent.AgentState
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.toolapi.ToolRegistry

val budgetTokens = client.getContextBudget(HeadroomPercent.Standard)

// Prune conversation using the AgentState pruning API
val state = AgentState(conversation, ToolRegistry.empty)

val prunedConversation =
  AgentState.pruneConversation(
    state,
    ContextWindowConfig(maxTokens = Some(budgetTokens))
  )
```

### Conversation Pruning

For long-running conversations, use built-in pruning strategies:

```scala
import org.llm4s.agent.AgentState
import org.llm4s.agent.ContextWindowConfig
import org.llm4s.agent.PruningStrategy
import org.llm4s.toolapi.ToolRegistry

// Prune when context exceeds configured limits
val state = AgentState(conversation, ToolRegistry.empty)

val prunedConversation =
  AgentState.pruneConversation(
    state,
    ContextWindowConfig(
      maxMessages = Some(50),
      pruningStrategy = PruningStrategy.OldestFirst
    )
  )
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
- **Tool registries not serializable** - Reconstruct on `AgentState` restore
- **Advanced semantic/embedding caching not included** - Add Redis/vector cache for high-volume RAG

These are tracked for improvement in the [Production Readiness Roadmap](reference/roadmap.md#production-readiness).

This guide will evolve as LLM4S approaches v1.0.

---

## Getting Help

- **Discord**: [Join the community](https://discord.gg/4uvTPn6qww)
- **GitHub Issues**: [Report problems](https://github.com/llm4s/llm4s/issues)
- **Examples**: [Production-like samples](https://github.com/llm4s/llm4s/tree/main/modules/samples)
