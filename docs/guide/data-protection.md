---
layout: page
title: Data Protection
parent: User Guide
nav_order: 38
---

# Data Protection
{: .no_toc }

What personal data llm4s moves, to where, what it keeps, and what it logs by default: the facts a records-of-processing
entry, a DPIA or a data-residency review needs.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

> This page describes what the library does. It is not legal advice, and it does not describe what a vendor does
> with data once it arrives: follow the vendor links, and your own agreements with each vendor, for that.

## In short

- **llm4s sends no telemetry.** The library has no usage reporting, analytics or update check. Every network call it
  makes goes to a destination your configuration or code names: a provider, a store, a tracing backend, a tool's API.
  The [egress table](#network-destinations-for-an-egress-allow-list) lists them.
- **The default tracer prints metadata only.** `llm4s.tracing.mode` defaults to `console`, which prints event types,
  models, ids, tool names, durations, token counts and costs to stdout, and withholds prompts, model output, tool
  input/output, custom event data and error messages unless `llm4s.tracing.console.captureContent = true`.
- **Nothing is persisted unless you configure a store.** Agent threads, memories and vector indexes live in memory by
  default; SQLite, PostgreSQL, Qdrant, Elasticsearch/OpenSearch, Redis and Neo4j are opt-in modules you point at.
- **Prompts go to the model provider you select, in the region its endpoint serves.** llm4s does not route between
  regions; the [residency table](#provider-data-residency-and-retention) shows the setting that picks the region.

## What leaves the process, and what is kept

Each row is something a module does only when you use it. "Configured by" is where the destination comes from.

| Module | What leaves the process | Destination | Configured by | Persisted by llm4s |
|---|---|---|---|---|
| Chat providers (`llm4s-openai`, `-anthropic`, `-gemini`, `-ollama`, `-openai-compatible`, `-bedrock`, `-watsonx`, `-jev`) | The conversation: system prompt, messages, tool definitions, tool calls and results, attached images and documents | The provider's API | `llm4s.providers.<name>` (`baseUrl`, provider extras such as `endpoint`, `location`, `region`) | No |
| Embeddings (`llm4s-openai`, `-ollama`, `-voyage`, `-jina`, `-cohere`) | The text being embedded: documents at indexing, queries at search | The embedding provider's API | `llm4s.embeddings.<id>` | No |
| Reranking (`llm4s-rag`) | The query and candidate passages | Cohere's rerank API (`CohereReranker`), or your chat provider (`LLMReranker`) | `llm4s.rerank.cohere`, or the client you pass | No |
| Image generation and vision (`llm4s-image`) | Prompts and images | OpenAI, Stability AI, Hugging Face, a Stable Diffusion server you run; OpenAI, Anthropic or Gemini for vision | The client config you build | No |
| Speech (`llm4s-speech`) | Audio (speech-to-text) or text (text-to-speech) | OpenAI, ElevenLabs or Azure Speech; Vosk, Whisper and Tacotron 2 run locally | `SPEECH_TTS_MODEL`, `SPEECH_STT_MODEL` and the selected provider's key | No |
| Web search tools (`llm4s-agent-tools`) | The search query the model wrote, which can quote the user | Brave, Exa or DuckDuckGo | `llm4s.tools.*` | No |
| HTTP tool (`llm4s-agent-tools`) | Whatever request the model builds | The URLs the tool's config allows | `HttpConfig` (private and cloud-metadata addresses are refused by default) | No |
| MCP client (`llm4s-mcp`) | Tool arguments the model wrote | The MCP servers you register | Your `MCPServerConfig`s | No |
| Workspace (`llm4s-workspace`) | Commands and files the agent works on | A container you run | `llm4s.workspace.*` | In the container's file system |
| Agent runtime (`llm4s-agent`) | Nothing on its own | - | - | Thread history, events and usage in the `Checkpointer`: in memory by default, SQLite (`llm4s-agent-checkpoint-sqlite`) or PostgreSQL (`llm4s-agent-checkpoint-postgres`) when configured |
| Memory (`llm4s-memory`, `-memory-postgres`) | Memory text to the embedding provider, when the store embeds | Your embedding provider | The store you build | Memories: in memory, SQLite or PostgreSQL |
| RAG and vector stores (`llm4s-rag`, `-vectorstore-elasticsearch`, `-vectorstore-redis`) | Chunks, embeddings and metadata | pgvector, Qdrant, SQLite, Elasticsearch/OpenSearch or Redis | The store you build | Yes, in that store |
| Knowledge graph (`llm4s-knowledgegraph-neo4j`) | Entities and relations extracted from text | Neo4j | The store you build | Yes, in Neo4j |
| Response cache (`CachingLLMClient`, `CachedEmbeddingClient`) | Nothing | - | - | Completions and embeddings in process memory, until evicted or cleared |
| Provider exchange logging (`llm4s-core`) | Nothing over the network | A JSON Lines file, or your sink | `llm4s.exchangeLogging` (off by default) | Raw request and response bodies, in the file |
| Tracing backends (`llm4s-observability`, `-observability-otel`) | Trace events, including content (see below) | Langfuse (`https://cloud.langfuse.com` by default) or an OTLP collector (`http://localhost:4317` by default) | `llm4s.tracing.langfuse`, `llm4s.tracing.opentelemetry` | In the backend |
| Metrics (`llm4s-observability-prometheus`) | Counters and histograms labelled by provider, model, operation, status and error type: no content | Scraped from the `/metrics` endpoint you start | `llm4s.metrics` | No |

## Logging and tracing defaults

| What | Default | Content it records |
|---|---|---|
| `console` tracing | On (`llm4s.tracing.mode = "console"`) | None unless `llm4s.tracing.console.captureContent = true`; then the query, model output (truncated to 200 characters), tool input and output (100 each), custom event data and error messages |
| `noop` tracing | Off | None |
| `langfuse` tracing | Off | The agent's query, completion content, tool input and output, and a finished run's messages |
| `opentelemetry` tracing | Off | The query, completion content and tool input and output as span attributes, each truncated to 1000 characters, and custom event data |
| Provider exchange logging | Off | Request and response bodies. The file sink redacts credentials by pattern, not personal data; a sink you write gets the bodies untouched. See [Provider Exchange Logging](observability/provider-exchange-logging) |
| SLF4J logs from the OpenAI-compatible, Gemini and Vertex AI clients | At `DEBUG` only | Request and response bodies, truncated, with credentials redacted by pattern |

Keep `captureContent` off, and `DEBUG` off for `org.llm4s`, wherever stdout and logs are collected into a system with
other retention or access rules than your conversation store. To set it explicitly in code:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.trace.{ ConsoleTracing, NoOpTracing, Tracing }

// What the configuration selects: console, metadata only, unless TRACING_MODE says otherwise
val configured: Tracing = Llm4sConfig.tracing().map(Tracing.create).getOrElse(new NoOpTracing())

// Prompts and answers too: for a developer's machine only
val verbose: Tracing = new ConsoleTracing(captureContent = true)
```

**What is redacted.** The exchange-log file sink and the debug logging above pass bodies through llm4s's internal
redaction, which masks API keys of known formats, `Authorization`, `Cookie` and similar headers, credential-bearing URL
query parameters and fields named like secrets (`api_key`, `password`, `token`, `client_secret`). It does not look for
personal data: names, addresses, account numbers and free text pass through unchanged. `TracingSettings` and provider
configs redact credentials in their `toString`.

## Network destinations for an egress allow-list

Allow only what your configuration uses. The defaults below are what a section gets when it sets no `baseUrl`.

| Destination | Default host | When |
|---|---|---|
| OpenAI | `api.openai.com` (or a residency host such as `eu.api.openai.com`, set as `baseUrl`) | `provider = "openai"`, OpenAI embeddings, speech or images |
| Azure OpenAI | Your resource's `endpoint` | `provider = "azure"` |
| Anthropic | `api.anthropic.com` | `provider = "anthropic"`, Anthropic vision |
| Gemini API | `generativelanguage.googleapis.com` | `provider = "gemini"`, Gemini vision |
| Vertex AI | `<location>-aiplatform.googleapis.com`, plus `oauth2.googleapis.com` or the GCE metadata server for tokens | `provider = "vertexai"` |
| AWS Bedrock | `bedrock-runtime.<region>.amazonaws.com` | `provider = "bedrock"` |
| IBM watsonx.ai | `us-south.ml.cloud.ibm.com` and `iam.cloud.ibm.com` | `provider = "watsonx"` |
| DeepSeek, Z.ai, OpenRouter, Mistral, Cohere, Requesty | `api.deepseek.com`, `api.z.ai`, `openrouter.ai`, `api.mistral.ai`, `api.cohere.ai`, `router.requesty.ai` | The matching provider |
| Jev (TypeSafe) | `api.typesafe.ai` | `llm4s-jev` |
| Voyage, Jina, Cohere embeddings and rerank | `api.voyageai.com`, `api.jina.ai`, `api.cohere.com` | The matching embedding provider or `CohereReranker` |
| Ollama, generic `openai-compatible` | The `baseUrl` you set (Ollama: `localhost:11434`) | The matching provider |
| Brave, Exa, DuckDuckGo | `api.search.brave.com`, `api.exa.ai`, `api.duckduckgo.com` | The search tools |
| Stability AI, Hugging Face, ElevenLabs | `api.stability.ai`, `api-inference.huggingface.co`, `api.elevenlabs.io` | Image generation, text-to-speech |
| Langfuse, OTLP | `cloud.langfuse.com`; `localhost:4317` | The tracing mode |
| Model metadata | None: the registry is bundled | Only when you set `llm4s.modelRegistry.url` |

## Provider data residency and retention

llm4s sends a request to the endpoint a provider section names and nothing else, so residency is chosen by that
endpoint. llm4s sets no retention flag of its own on any request (OpenAI's `store`, for example, is never sent, so the
API's default applies). Zero-data-retention and modified-abuse-monitoring arrangements are made with the vendor, on
the account, and need no llm4s setting.

| Provider | How the region is chosen in llm4s | Vendor documentation |
|---|---|---|
| OpenAI | `baseUrl = "https://eu.api.openai.com/v1"` (or `us.`) for a project created with that data residency | [Your data](https://developers.openai.com/api/docs/guides/your-data) |
| Azure OpenAI | `endpoint` is your resource, whose region decides the geography; a Global or DataZone deployment may process elsewhere | [Data, privacy, and security](https://learn.microsoft.com/en-us/azure/ai-foundry/responsible-ai/openai/data-privacy) |
| Anthropic | Not selectable: llm4s does not send `inference_geo`. For regional processing of Claude models use Bedrock | [API and data retention](https://platform.claude.com/docs/en/manage-claude/api-and-data-retention), [Data residency](https://platform.claude.com/docs/en/manage-claude/data-residency) |
| Gemini API | Not selectable: one global endpoint. Paid and unpaid tiers differ in how Google uses prompts | [Gemini API terms](https://ai.google.dev/gemini-api/terms) |
| Vertex AI | `location = "europe-west4"` (default `us-central1`): a regional endpoint | [Data residency](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/learn/data-residency) |
| AWS Bedrock | `region` (required) | [Data protection](https://docs.aws.amazon.com/bedrock/latest/userguide/data-protection.html) |
| IBM watsonx.ai | `baseUrl`, such as `https://eu-de.ml.cloud.ibm.com` (default `https://us-south.ml.cloud.ibm.com`) | [Endpoint URLs](https://cloud.ibm.com/apidocs/watsonx-ai) |
| Ollama | `baseUrl` of a server you run: data stays on it | [Ollama FAQ](https://docs.ollama.com/faq) |

For example, a section that keeps processing in the EU on Vertex AI:

```hocon
llm4s.providers.gemini-eu {
  provider = "vertexai"
  model    = "gemini-2.0-flash"
  project  = ${?VERTEXAI_PROJECT}
  location = "europe-west4"
}
```

## Deleting a user's data

An erasure request reaches every store you configured. What llm4s offers per store:

| Store | Delete by | Notes |
|---|---|---|
| Agent threads (`Checkpointer`) | Thread: `Agent.forget(threadId)` or `GraphRuntime.deleteThread` | Removes the thread's history, usage and event log, and its child threads. A deletion leaves a tombstone, reclaimed by a store sweep with `tombstoneAge`. `RetentionPolicy` (`maxAge`, `maxCheckpoints`, `maxEvents`) prunes old history but never a thread's latest checkpoint, so erasure needs `forget` |
| Memories (`MemoryStore`) | `delete(id)`, `deleteMatching(filter)` | User facts carry `user_id` metadata (`MemoryFilter.ByMetadata("user_id", id)`); conversations `conversation_id` (`MemoryFilter.ByConversation`); entities `entity_id` (`MemoryFilter.ByEntity`) |
| Vector stores (`VectorStore`) | `delete`, `deleteBatch`, `deleteByPrefix`, `deleteByFilter` | Only as findable as the metadata you indexed with: tag documents with the owner's id |
| Exchange-log files, tracing backends, provider-side storage | Outside llm4s | Delete with the tool that stores them |

llm4s keeps no index from a user to their threads: record which thread ids belong to which user in your application.

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.ThreadId
import org.llm4s.agent.memory.{ MemoryFilter, MemoryStore }
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, VectorStore }

def erase(
  agent: Agent,
  memories: MemoryStore,
  vectors: VectorStore,
  userId: String,
  threads: Seq[ThreadId]
): Result[Unit] =
  for {
    _ <- threads.foldLeft[Result[Unit]](Right(()))((done, thread) => done.flatMap(_ => agent.forget(thread)))
    _ <- memories.deleteMatching(MemoryFilter.ByMetadata("user_id", userId))
    _ <- vectors.deleteByFilter(MetadataFilter.Equals("userId", userId))
  } yield ()
```

## Personal data in prompts

`llm4s-agent` has two guardrails that find personal data with regular expressions, before a query reaches the model
and before an answer is returned:

- `PIIMasker` replaces what it finds (`[REDACTED_EMAIL]`, `[REDACTED_CARD]`, ...) and lets the turn continue.
- `PIIDetector` blocks the turn by default, or masks (`GuardrailAction.Fix`) or only warns (`GuardrailAction.Warn`; the
  warning names the types found, never the text).

Both cover `SSN`, `CreditCard`, `Email` and `Phone` by default; `PIIType.all` adds `IPAddress`, `Passport` and
`DateOfBirth`, and `BankAccount` can be listed explicitly.

```scala
import org.llm4s.agent.Agent
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin.{ PIIDetector, PIIMasker }
import org.llm4s.agent.guardrails.patterns.PIIPatterns.PIIType

val guarded = Agent
  .builder("support", client)
  .withMiddleware(
    GuardrailMiddleware(
      input = Seq(PIIMasker(PIIType.all)), // mask before the model sees the query
      output = Seq(PIIDetector())          // block an answer that still contains PII
    )
  )
  .build()
```

Their limits:

- The patterns are mostly US formats (SSN, US phone numbers, US-style dates); international phone numbers are matched
  only with a leading `+`. Names, postal addresses, IBANs in most layouts and free-text health data are not detected.
- They see the query and the final answer. Tool arguments and tool results, retrieved RAG passages and memories put into
  the prompt are not checked, and neither is what a tool sends to its own API.
- A masked query is what the thread stores, so the masking also keeps it out of the checkpointer; an unmasked one is
  stored as written.

For stronger guarantees, redact or pseudonymise before text reaches llm4s, and choose a provider and region your
agreements cover.

## See also

- [Security reference](../reference/security) - threat model and data-flow overview
- [Provider Exchange Logging](observability/provider-exchange-logging) - what the exchange log records
- [Guardrails](agents/guardrails) - every built-in guardrail
- [Configuration](../getting-started/configuration#environment-variables-llm4s-reads) - the variables llm4s reads
