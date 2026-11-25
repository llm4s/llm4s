---
layout: page
title: User Guide
description: "The LLM4S user guide: providers, agents, tools, structured output, RAG, vector stores, Java, effect systems and observability for the Scala JVM."
search_title: "LLM4S user guide: build LLM apps in Scala"
nav_order: 3
has_children: true
---

# User Guide

Guides for LLM4S features. They describe the current release, {{ site.data.project.latest_release }}
(Scala 3.7.x, JDK 21). New to LLM4S? Start with [Getting Started](/getting-started/). Upgrading from an earlier
release? See the [migration guides](/migrations/).

Each feature is its own module next to `llm4s-core` (the agent runtime in `llm4s-agent`, RAG in `llm4s-rag`, each
provider in its own module, and so on); each guide names the module it needs, and
[Installation](/getting-started/installation#optional-dependencies) lists them all.

The sidebar groups the guides as [Core](core), [Agents](agents/), [RAG and Data](rag),
[Java and Kotlin](java-and-kotlin), [Frameworks and Runtimes](frameworks-and-runtimes),
[Coming from Another Library](coming-from) and [Operations](operations). This page lists them all.

## Available Guides

### Fundamentals

- **[Basic Usage](basic-usage)** - Get started with LLM calls, client creation, and error handling
- **[Providers](providers)** - Overview of supported providers and how to configure them
- **[Error Handling](error-handling)** - Work with `Result[A]` and `LLMError`: pattern matching, for-comprehensions, every error type, recovery and testing
- **[Reliable LLM Calling](reliability)** - `ReliableClient`: retry policies, a circuit breaker, deadlines and local rate limiting around any client
- **[Typed Prompts](prompts)** - `prompt"..."` and `system"..."`: values rendered by a type class checked at compile time, and untrusted input fenced
- **[Structured Output](structured-output)** - `completeStructured`: typed replies from a schema, which providers enforce it, and what a bad reply looks like
- **[Derived Schemas](derived-schemas)** - Write the type once: `llm4s-schema-derivation` builds the `completeStructured` schema from a case class with `derives`
- **[Multimodal Content](multimodal)** - Send images and documents in a user message or a tool result, and check before the request whether the provider takes them
- **[Media Types](media-types)** - `llm4s-media`: the `MediaType`, `MediaCategory` and `MediaSource` vocabulary that multimodal messages, image generation and RAG extraction share
- **[Capability-Typed Clients](capabilities)** - Check once that a model can see images or call tools, and let the compiler refuse an unchecked client (experimental)
- **[Cost Controls](cost-controls)** - Thinking and reasoning effort per provider, provider-side prompt caching and service tiers
- **[Caching](caching)** - Cache embeddings (exact) and model responses (semantic): configuration, keys, TTL, eviction and what the cache reports
- **[Your HTTP Client](http-transport)** - `llm4s-sttp` sends the provider clients' requests through the sttp backend your service already has
- **[Testing with the Testkit](testing-with-the-testkit)** - Script an `LLMClient` in your tests: canned replies, tool calls, injected errors, recorded requests
- **[JSON Libraries](json-libraries)** - Use circe, play-json, zio-json or jsoniter-scala with LLM4S: converting at the boundary, structured output and tools
- **[Jupyter Notebooks](notebooks)** - Run LLM4S in Jupyter with the Almond Scala kernel
- **[Writing a Provider](writing-a-provider)** - Publish your own provider module against the `llm4s-core` SPI

### Coming from Another Library

- **[Comparing LLM4S](comparison)** - A sourced comparison with LangChain4j, Spring AI, Koog and Embabel
- **[Coming from LangChain4j or Spring AI](migrating-from-langchain4j)** - Concepts side by side, with working code for model calls, typed output, tools, memory and RAG
- **[Coming from sttp-ai or openai-scala-client](migrating-from-scala-llm-clients)** - Your Scala client code next to the same thing in LLM4S, compiled
- **[Coming from Python](for-python-developers)** - OpenAI SDK, LangChain, LlamaIndex and Pydantic code next to the same thing in Scala 3
- **[Replacing a Python Sidecar](replacing-a-python-sidecar)** - Move the LLM logic of a Python sidecar into your Scala service, in stages

### Agents and Tools

- **[Agents Overview](agents/)** - Build LLM-powered agents with tools, guardrails, and multi-turn conversations
  - **[Guardrails](agents/guardrails)** - Input/output validation for safety and quality
  - **[Memory System](agents/memory)** - Persistent context and knowledge across conversations
  - **[Handoffs](agents/handoffs)** - Agent-to-agent delegation for specialist routing
  - **[Delegation](agents/delegation)** - Hand a bounded task to a typed subagent and get back only its typed report
  - **[Human Review](agents/human-review)** - Pause for a person: approve a tool call, answer a question, review an answer, then resume
  - **[Streaming Events](agents/streaming)** - Real-time execution feedback for responsive UIs
  - **[Durable Checkpointers](agents/durable-checkpointers)** - Share a checkpoint store, recover a run whose process died, fork and prune history
  - **[Graphs](agents/graphs)** - Build your own workflow on the graph runtime: typed state, fan-out and joins, routing, interrupts, retries
  - **[Writing Middleware](agents/middleware)** - Hooks around an agent's turn, model calls and tool calls: logging, redaction, tool policy, prompt context
  - **[Running Agents on Several Machines](agents/multiple-machines)** - Several instances sharing threads through one PostgreSQL database
  - **[Store Contract Suites](agents/contract-suites)** - Test your own `Checkpointer`, `RunQueue`, `LongTermStore` or `FileBackend` with the suites in `llm4s-agent-testkit`
- **[Context Window Pruning](context-window-pruning)** - Keep a long conversation within the model's context window
- **[Built-in Tools](builtin-tools)** - Calculator, date and time, UUID, JSON, files, HTTP, shell and web search tools, the bundles that hold them, and what each one can do
- **[Build Your Own Tool](custom-tools)** - Write a tool with `ToolBuilder`: the `Schema` DSL, reading arguments, returning errors, registering it with an agent and testing it
- **[Tapir Endpoints as Tools](tapir-tools)** - `llm4s-tapir` turns the tapir endpoints a service already has into tools, with each schema derived from its endpoint
- **[Serving an Agent over HTTP](http-reference-apps)** - Reference apps with http4s, ZIO HTTP, Tapir and Play: chat and streamed endpoints, cancellation and tests

### RAG & Semantic Search

- **[Embeddings](embeddings)** - Choose a provider and model, create an `EmbeddingClient`, embed documents and queries, and keep dimensions in step with the store
- **[Vector Store](vector-store)** - Complete RAG toolkit for semantic search and retrieval
  - **Vector Backends**: SQLite (in-memory/file), PostgreSQL/pgvector, Qdrant, Elasticsearch, OpenSearch, Redis and Valkey
  - **Keyword Backends**: SQLite FTS5, PostgreSQL native full-text search
  - **Hybrid Search**: BM25 keyword + vector fusion with RRF strategy
  - **Reranking**: Cohere cross-encoder or an LLM reranker
  - **Document Chunking**: Sentence-aware + simple chunking strategies
- **[Document Loaders](document-loaders)** - Load text, files, directories, URLs, a web crawl or S3 into RAG; compose loaders; `ingest`, `sync` and `refresh`; SSRF protection and robots.txt
- **[Query Transformers](rag-query-transformers)** - Rewrite the user's query before retrieval: `LLMQueryRewriter`, `IdentityTransformer`, chaining, error behaviour and custom transformers
- **[RAG Evaluation](rag-evaluation)** - Measure and improve RAG quality
  - **RAGAS Metrics**: Faithfulness, answer relevancy, context precision/recall
  - **Benchmarking Harness**: Compare chunking, fusion, and embedding strategies
- **[Permission-Based RAG](permission-based-rag)** - Access control for RAG
  - **Hierarchical Collections**: Organize documents by tenant, team, or project
  - **Two-Level Permissions**: Collection-level `queryableBy` + document-level `readableBy`
  - **Pattern Queries**: `*`, `path/*`, `path/**` for flexible collection scoping
- **[Modular RAG Example](modular-rag-example)** - A sample with ingestion, retrieval and generation as separate modules

### Images, Speech and Decisions

- **[Image Generation](image-generation)** - Generate images with OpenAI GPT Image, Stability AI, Hugging Face or a Stable Diffusion server
- **[Image Processing and Vision](image-processing)** - Resize, crop and convert images locally, or ask an OpenAI, Anthropic or Gemini vision model about them
- **[Speech](speech)** - Speech-to-text (STT) and text-to-speech (TTS), local and cloud
- **[STT Domain Model](stt-domain-model)** - `STTOptions`, `WordTimestamp`, `Transcription` and the `STTError` cases
- **[Jev decision model](jev)** - Ask TypeSafe's Jev typed questions (Noul, Choice, Score) and get typed answers: a decision client, not a chat model

### Effect Systems

- **[Pick your stack](effect-systems)** - One program in plain `Result`, cats-effect, ZIO and Pekko side by side: the semantics every bridge shares, and the differences
- **[cats-effect](cats-effect)** - `LLMClientIO` and `AgentIO` for cats-effect `IO` and fs2 streaming
- **[ZIO](zio)** - `LLMClientZ` and `AgentZ` for ZIO 2 and ZIO Streams
- **[Apache Pekko](pekko)** - `LLMClientPekko` and `AgentPekko` for Pekko Streams
- **[Direct Style and Ox](direct-style)** - Plain `Result` code on virtual threads, and `llm4s-ox` for streamed completions as an Ox `Flow`, fan-out, races and retries

### Data Pipelines

- **[Spark and Databricks](spark)** - Enrich a DataFrame from a Spark job: a client per partition, `ReliableClient`, errors as data
- **[Apache Flink](flink)** - Enrich a Flink 2.x stream from Scala 3: `AsyncDataStream`, a client per task built in `open()`, dead letters as a side output
- **[Kafka](kafka)** - Enrich a Kafka topic with fs2-kafka: bounded concurrency, retries, a dead-letter topic, offsets committed after the produce

### Java and JVM Frameworks

- **[Java](java)** - Call a model from Java with `llm4s-java-api`: a client, a conversation and failures you can read without Scala
- **[Java AI services](java-ai-services)** - Declare an annotated interface and let `llm4s-java-api` implement it (experimental)
- **[Java RAG](java-rag)** - Ingest documents and ask questions from Java with `llm4s-java-rag`: answers with their sources
- **[Java Reactor](java-reactor)** - Stream a reply as a Reactor `Flux<String>` with `llm4s-reactor`
- **[Java Threading and Cancellation](java-threading-and-cancellation)** - Which thread a call blocks, sharing one client, virtual threads, interrupts and timeouts
- **[Moving a Java app from LangChain4j or Spring AI](java-migration)** - Each LangChain4j and Spring AI concept in `llm4s-java-api` terms, with compiled before-and-after Java
- **[Spring Boot](spring-boot)** - `llm4s-spring-boot-starter`: properties, an `LLM4STemplate` bean, asynchronous calls and a health indicator
- **[Spring AI](spring-ai)** - Any llm4s client as a Spring AI `ChatModel` or `EmbeddingModel`, and a Spring AI `ChatModel` as an llm4s client
- **[Quarkus](quarkus)** - The `quarkus-llm4s` extension: `JLlmClient` beans from `application.properties`, checked at build time
- **[Micronaut](micronaut)** - A `@Factory` bean for `JLlmClient` configured from `application.yml`, blocking and streamed endpoints and tests
- **[Helidon](helidon)** - Helidon SE 4: the client built from `application.yaml`, routes on virtual threads, a health check and tests
- **[Kotlin](kotlin)** - `llm4s-kotlin-api`: suspend functions, `Flow` streaming, agents with tools and the Kotlin DSL (experimental)

### Production

- **[Production Deployment](production-deployment)** - Configuration, secrets, reliability, observability, scaling and container images
- **[Reference Architecture](reference-architecture)** - Deploy behind an AI gateway: secret injection, egress policy, failover, quotas and spend, with tested Kubernetes manifests
- **[Data Protection](data-protection)** - What personal data LLM4S sends where, what it keeps and logs, data residency and deletion
- **[Secrets and API Keys](secrets)** - Choose a key source (config, environment, key file, custom provider, Vault, AWS Secrets Manager), with caching, rotation and failure behaviour
- **[Monitoring](observability/)** - Tracing (Langfuse, OpenTelemetry), logging, metrics, health checks and cost monitoring
  - **[Agent Tracing and Metrics](observability/agents)** - The span tree and attributes of an agent run, agent runtime and run queue metrics, usage, cost and alerts
  - **[Micrometer Metrics](observability/micrometer)** - Request, token, cost and error metrics in a Micrometer `MeterRegistry`
  - **[Provider Exchange Logging](observability/provider-exchange-logging)** - Capture raw provider requests and responses for debugging
  - **[In-Process Tracing Use Cases](observability/enhanced-tracing-use-cases)** - `TraceCollectorTracing` and `InMemoryTraceStore`: trace lookup, deterministic agent tests and latency analysis
- **[Real-World Application Patterns](patterns/)** - Multi-agent orchestration, enterprise RAG, monitoring, scaling, error recovery and security
  - **[Multi-Agent Orchestration](patterns/multi-agent-orchestration)**
  - **[RAG for Enterprise](patterns/rag-enterprise)**
  - **[Production Monitoring](patterns/production-monitoring)**
  - **[Scaling Strategies](patterns/scaling-strategies)**
  - **[Error Recovery](patterns/error-recovery)**
  - **[Security Best Practices](patterns/security-best-practices)**

## Examples

The **[Examples Gallery](/examples/)** has runnable code for each area:

| Feature | Examples Section |
|---------|------------------|
| Basic LLM Calling | [Basic Examples](/examples/#basic-examples) |
| Multi-Turn Conversations | [Context Management](/examples/#context-management) |
| Agent Framework | [Agent Examples](/examples/#agent-examples) |
| Tool Calling | [Tool Examples](/examples/#tool-examples) |
| Guardrails & Safety | [Guardrails Examples](/examples/#guardrails-examples) |
| Agent Handoffs | [Handoff Examples](/examples/#handoff-examples) |
| Memory System | [Memory Examples](/examples/#memory-examples) |
| Streaming | [Streaming Examples](/examples/#streaming-examples) |
| Embeddings & RAG | [Embeddings](/examples/#embeddings) |
| MCP Integration | [MCP Examples](/examples/#mcp-examples) |

## Getting Help

- Browse [examples](/examples/) for working code samples
- Check the [Scaladoc](/scaladoc/) for API documentation
- Join our [Discord community](https://discord.gg/4uvTPn6qww) for support
