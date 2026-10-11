---
layout: page
title: Comparing LLM4S
parent: User Guide
nav_order: 24
---

# Comparing LLM4S with other JVM libraries
{: .no_toc }

This page compares LLM4S with LangChain4j, Spring AI, Koog and Embabel, so that you can decide quickly whether it fits
your project. It states facts and trade-offs, not rankings.

**Last verified: 2026-10-11.** The statements about LLM4S describe the `main` branch on that date; the latest release is
v0.4.1, and the module split described in the [v1 scope](../reference/v1-scope) ships with 0.5.0. Every statement about
another project comes from that project's own documentation, fetched on that date and listed under
[Sources](#sources). A cell that says *not verified* means it was not checked, not that the feature is absent. The
other projects change quickly: re-check anything that matters to you before relying on it, and expect this page to be
refreshed at each LLM4S release.

1. TOC
{:toc}

## At a glance, by audience

- **Scala developers.** LLM4S is written for Scala 3 only ([troubleshooting](../reference/troubleshooting)). It reports
  errors as `Result[A]` values, and its tools, agents, RAG and memory are Scala APIs. The other four projects in the
  table are Java or Kotlin libraries (see the first row); a Scala program can call them, but their documented APIs are
  written for those languages.
- **Java developers.** LLM4S has a Java facade (`llm4s-java-api`), but it is incomplete today. `JLlmClient` offers
  `complete` and `completion`, and `JAgent` runs, continues, streams the events of, resumes and recovers an agent run;
  defining tools, streaming tokens from `JLlmClient` and structured output from Java are open issues
  ([#1484](https://github.com/llm4s/llm4s/issues/1484), [#1485](https://github.com/llm4s/llm4s/issues/1485),
  [#1486](https://github.com/llm4s/llm4s/issues/1486)). A Java team that wants a complete Java API today should read
  the table below carefully.
- **Spring shops.** LLM4S has a Spring Boot starter (`llm4s-spring-boot-starter`), but it wires three providers
  (OpenAI, Anthropic and Ollama; [#1467](https://github.com/llm4s/llm4s/issues/1467) tracks the rest). Spring AI is
  the Spring project's own library, and LangChain4j publishes Spring Boot starters too.

## Capability table

| Capability | LLM4S (`main`) | LangChain4j | Spring AI | Koog | Embabel |
|---|---|---|---|---|---|
| Language and API style | Scala 3 only; `Result[A]` errors; Java facade incomplete | Java; AI Services are interfaces the library implements (annotated `@AiService` in Spring Boot) [L1][L8] | Java; fluent `ChatClient` [S6] | Kotlin DSL and fluent builder-style Java APIs [K1] | Written in Kotlin, usable from Java [E1] |
| Typed structured output | `completeStructured[A]` with a hand-written `ObjectSchema[A]` and a uPickle reader; the schema is sent as a JSON-Schema response format; Anthropic gets a best-effort prompt instruction that is not schema-enforced (its Scaladoc). No schema derivation yet ([#1472](https://github.com/llm4s/llm4s/issues/1472)) | Return a POJO or record from an AI Service method; JSON Schema for the providers it lists, prompting otherwise [L1][L7] | `BeanOutputConverter` derives a JSON Schema from a Java class; `entity()` on `ChatClient` [S4][S6] | not verified | not verified |
| Tools | `ToolBuilder` with a hand-written parameter schema; built-in calculator, date and time, UUID, JSON, file, HTTP, shell and web-search tools ([guide](builtin-tools)); no derivation from types yet ([#1473](https://github.com/llm4s/llm4s/issues/1473)) | AI Services can be configured with tools, methods annotated `@Tool`, that the model can call [L1] | `@Tool` on a method, or `MethodToolCallback` and `FunctionToolCallback`; the input schema is generated from the method's parameters [S7]; tool calls are observed [S5] | not verified | not verified |
| Agents | Agent runtime on a typed graph runtime, handoffs ([guide](agents/)) | `langchain4j-agentic`, marked experimental: sequential, loop, parallel and conditional workflows and a supervisor agent [L10]; an example of an agent with memory, tools and RAG [L2] | A guide to agentic patterns (chain, parallelization, routing, orchestrator-workers, evaluator-optimizer) built on `ChatClient` [S10]; the Advisors API intercepts and enhances model interactions [S6] | Basic, graph-based, functional and planner (beta) agents [K1] | Goal Oriented Action Planning by default; Utility AI supported [E1] |
| Guardrails | Input and output guardrails, including LLM-as-judge ones, and composition ([guide](agents/guardrails)) | Input and output guardrails on AI Services, marked experimental [L11] | not verified | not verified | not verified |
| Memory | `llm4s-memory`: in-memory, SQLite and embedding-backed stores; a Postgres store in the separate `llm4s-memory-postgres` artifact ([guide](agents/memory)) | Chat memory, shared or per user through `ChatMemoryProvider` [L1] | `ChatMemory`, with `MessageWindowChatMemory` as its built-in implementation, backed by `ChatMemoryRepository` implementations (in-memory, JDBC, Cassandra, Neo4j, MongoDB, Redis) [S6] | not verified | not verified |
| RAG and stores | Vector stores: SQLite, PostgreSQL (pgvector), Qdrant; keyword indexes: SQLite FTS5, PostgreSQL full-text; hybrid fusion and Cohere reranking ([guide](vector-store)) | `ContentRetriever` and `RetrievalAugmentor` [L1]; a long table of embedding stores [L4] | A list of vector store implementations [S2] | not verified | not verified |
| Model providers | 15 chat providers on `main`: OpenAI, Azure OpenAI, Anthropic, Gemini, Vertex AI, Ollama, Mistral, Cohere, DeepSeek, Z.ai, OpenRouter, Requesty, Amazon Bedrock, IBM watsonx.ai and a generic OpenAI-compatible one; 5 embedding providers ([providers](providers)) | A table of supported language models [L3] | A comparison table of supported chat models [S8] | not verified | not verified |
| MCP | Client (stdio, SSE and Streamable HTTP transports) and a server class in `llm4s-mcp`; no user guide yet ([#1442](https://github.com/llm4s/llm4s/issues/1442)) | Client with Streamable HTTP, stdio, WebSocket and Docker stdio [L5]; a stdio server in the `langchain4j-community-mcp-server` module [L9] | Client and server; stdio, Streamable HTTP, stateless Streamable HTTP and SSE; Spring Boot starters [S3] | MCP and A2A integrations, both marked beta [K1] | not verified |
| Observability | Langfuse, OpenTelemetry and Prometheus modules, a trace collector and cost tracking ([guide](observability/)) | Listeners and events; Micrometer listeners; OpenTelemetry semantic conventions; Arize integrations [L6] | Micrometer metrics and tracing for `ChatClient`, `ChatModel`, `EmbeddingModel`, `ImageModel` and `VectorStore` [S5] | OpenTelemetry is listed among its integrations [K1] | not verified |
| Testing and evaluation | RAGAS-style metrics and a benchmark harness for RAG ([guide](rag-evaluation)); LLM-as-judge guardrails; **no published test kit for application code** ([#1475](https://github.com/llm4s/llm4s/issues/1475)); `llm4s-provider-testkit` is for provider authors | not verified | `Evaluator`, `RelevancyEvaluator` and `FactCheckingEvaluator` [S1] | not verified | not verified |
| Frameworks and runtimes | Spring Boot starter (three providers), cats-effect ([guide](cats-effect)), ZIO ([guide](zio)), a Kotlin module, a Java facade (incomplete) | Spring Boot 3 and 4 starters and `@AiService`; example projects for Spring Boot, Quarkus, Helidon, Payara Micro, WildFly and Jakarta EE/MicroProfile [L2][L8] | Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x [S9]; Spring Boot starters, for example for its MCP client and server [S3] | Spring Boot and Ktor integrations (both beta) [K1] | Built on Spring [E1] |
| Java baseline | JDK 21: CI runs only JDK 21, and the agent runtime depends on Ox, whose jars are JDK 21 bytecode ([#1493](https://github.com/llm4s/llm4s/issues/1493)) | Its Spring Boot integration page states a minimum Java version, quoted in the note below the table [L8] | not verified | not verified | not verified |
| Maturity | Pre-1.0: latest release v0.4.1; stability tiers (Frozen, Beta, Experimental) in the [v1 scope](../reference/v1-scope) | Latest release 1.22.0, 2026-10-08 [R1]; some modules, such as agents and guardrails, are marked experimental [L10][L11] | Latest release 2.0.1, 2026-08-21 [R2] | Latest release 1.3.0, 2026-09-24 [R3]; its Spring Boot, Ktor and planner agents are marked beta [K1] | Latest release 1.5.3, 2026-10-05 [R4] |

Note on the *Java baseline* row: LangChain4j's Spring Boot integration page says "LangChain4j Spring Boot integration requires Java 17" [L8]. This is a statement about that project, quoted as fetched on 2026-10-11. <!-- doc-support: ignore -->

## What LLM4S has not got yet

Plainly, as of the date above:

- **Schema and tool derivation.** Structured-output schemas and tool parameter schemas are written by hand, and a
  mismatch with the handler shows up at run time ([#1472](https://github.com/llm4s/llm4s/issues/1472),
  [#1473](https://github.com/llm4s/llm4s/issues/1473)).
- **A complete Java API.** See the audience note above.
- **A test kit for application code** ([#1475](https://github.com/llm4s/llm4s/issues/1475)). Tests in this repository
  use scripted clients like the ones in the [migration guide's](migrating-from-langchain4j) spec.
- **A smaller integration catalogue.** Two vector-store modules that other projects offer, Elasticsearch/OpenSearch
  and Redis, are open issues ([#1469](https://github.com/llm4s/llm4s/issues/1469),
  [#1470](https://github.com/llm4s/llm4s/issues/1470)). The Spring Boot starter covers three providers
  ([#1467](https://github.com/llm4s/llm4s/issues/1467)). There is no Quarkus guide yet
  ([#1468](https://github.com/llm4s/llm4s/issues/1468)).
- **No bill of materials** to align artifact versions ([#1462](https://github.com/llm4s/llm4s/issues/1462)).
- **No Scala 2.13 artifact.** The artifacts are Scala 3 only.
- **A stable 1.0 API.** The split artifacts are unpublished until 0.5.0, and the compatibility promise starts at 1.0.

## When not to choose LLM4S

- Your application is Java and needs a complete Java API now: defining tools, streaming tokens from a plain completion
  and structured output from Java are not there on `main` yet.
- You are a Spring team that wants the widest set of ready-made integrations, or you need a vector store or model
  provider that LLM4S does not have a module for. The two projects whose pages list their stores, LangChain4j and Spring AI,
  each list far more than the three vector stores LLM4S has.
- You need to stay on a JDK older than 21.
- You need a stable, frozen API today. LLM4S is pre-1.0.

## When LLM4S fits

These are the things its code does today, not claims about quality:

- You write Scala 3 and want errors as `Result[A]` values with typed error classes
  ([error handling](error-handling)) instead of exceptions.
- You want a provider layer that is a small published interface (`ProviderDescriptor`), so that adding a provider is
  adding a dependency ([writing a provider](writing-a-provider)).
- You want guardrails (input and output validators, including LLM-as-judge ones) and agent handoffs in the same library
  as the client, usable on an agent run ([guardrails](agents/guardrails)).
- You use cats-effect or ZIO and want `AgentIO` or `AgentZ` ([cats-effect](cats-effect), [ZIO](zio)).

## Sources

All fetched on 2026-10-11, against LangChain4j 1.22.0, Spring AI 2.0.1, Koog 1.3.0 and Embabel 1.5.3. The column shows the fact taken from each page; nothing else on these pages is relied on.

| Tag | Page | Fact used |
|---|---|---|
| L1 | [LangChain4j: AI Services](https://docs.langchain4j.dev/tutorials/ai-services) | Return-type structured output; tools; chat memory (shared or per user); `ContentRetriever` for RAG |
| L2 | [langchain4j-examples](https://github.com/langchain4j/langchain4j-examples) | Example projects for Spring Boot, Quarkus, Helidon, Payara Micro, WildFly, Jakarta EE/MicroProfile; an agent example with memory, tools and RAG |
| L3 | [LangChain4j: language models](https://docs.langchain4j.dev/integrations/language-models/) | A comparison table of supported language models |
| L4 | [LangChain4j: embedding stores](https://docs.langchain4j.dev/integrations/embedding-stores/) | A comparison table of supported embedding stores |
| L5 | [LangChain4j: MCP](https://docs.langchain4j.dev/tutorials/mcp) | MCP client; Streamable HTTP, stdio, WebSocket (not standardized) and Docker stdio; stdio server in LangChain4j Community |
| L6 | [LangChain4j: observability](https://docs.langchain4j.dev/tutorials/observability) | Listeners and events; Micrometer listeners; OpenTelemetry Generative AI Semantic Conventions; Arize Phoenix and AX |
| L7 | [LangChain4j: structured outputs](https://docs.langchain4j.dev/tutorials/structured-outputs) | JSON Schema for Amazon Bedrock, Azure OpenAI, Google AI Gemini, Mistral, Ollama and OpenAI; prompting otherwise; POJOs and records |
| L8 | [LangChain4j: Spring Boot integration](https://docs.langchain4j.dev/tutorials/spring-boot-integration) | Starters for Spring Boot 3 (3.5+) and 4 (4.0+); `@AiService`; its stated Java minimum (see the note below the capability table) |
| L9 | [LangChain4j: building a Java MCP server](https://docs.langchain4j.dev/tutorials/mcp-stdio-server) | A stdio MCP server in the community module `langchain4j-community-mcp-server` |
| L10 | [LangChain4j: agents and agentic AI](https://docs.langchain4j.dev/tutorials/agents) | `langchain4j-agentic`, experimental; sequential, loop, parallel, conditional workflows; supervisor agent |
| L11 | [LangChain4j: guardrails](https://docs.langchain4j.dev/tutorials/guardrails) | Input and output guardrails, only on AI Services; experimental |
| S1 | [Spring AI: evaluation testing](https://docs.spring.io/spring-ai/reference/api/testing.html) | `Evaluator`, `RelevancyEvaluator`, `FactCheckingEvaluator` |
| S2 | [Spring AI: vector databases](https://docs.spring.io/spring-ai/reference/api/vectordbs.html) | A list of vector store implementations |
| S3 | [Spring AI: MCP overview](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html) | Client and server; stdio, Streamable HTTP, stateless Streamable HTTP, SSE; Spring Boot starters |
| S4 | [Spring AI: structured output converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html) | `BeanOutputConverter` derives a JSON Schema from a Java class |
| S5 | [Spring AI: observability](https://docs.spring.io/spring-ai/reference/observability/index.html) | Micrometer metrics and tracing for `ChatClient`, `ChatModel`, `EmbeddingModel`, `ImageModel`, `VectorStore`; `spring.ai.tool` observations |
| S6 | [Spring AI: ChatClient](https://docs.spring.io/spring-ai/reference/api/chatclient.html) | Fluent API; `entity()`; Advisors API; `ChatMemory` with `MessageWindowChatMemory` as the built-in implementation, and its `ChatMemoryRepository` implementations |
| S7 | [Spring AI: tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html) | `@Tool`, `MethodToolCallback`, `FunctionToolCallback`; input schema generated from the method's parameters |
| S8 | [Spring AI: chat models comparison](https://docs.spring.io/spring-ai/reference/api/chat/comparison.html) | A comparison table of supported chat models |
| S9 | [Spring AI: getting started](https://docs.spring.io/spring-ai/reference/getting-started.html) | Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x |
| S10 | [Spring AI: building effective agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html) | Chain, parallelization, routing, orchestrator-workers and evaluator-optimizer patterns |
| K1 | [Koog documentation](https://docs.koog.ai/) | JetBrains JVM agent framework; Kotlin DSL and fluent builder-style Java APIs; basic, graph, functional and planner (beta) agents; Spring Boot and Ktor (beta); OpenTelemetry; MCP and A2A (beta) |
| E1 | [embabel-agent](https://github.com/embabel/embabel-agent) | Agent framework for the JVM; written in Kotlin with a natural Java usage model; GOAP by default and Utility AI; built on Spring |
| R1 | [LangChain4j releases](https://github.com/langchain4j/langchain4j/releases) | Latest release 1.22.0, published 2026-10-08 |
| R2 | [Spring AI releases](https://github.com/spring-projects/spring-ai/releases) | Latest release v2.0.1, published 2026-08-21 |
| R3 | [Koog releases](https://github.com/JetBrains/koog/releases) | Latest release 1.3.0, published 2026-09-24 |
| R4 | [Embabel releases](https://github.com/embabel/embabel-agent/releases) | Latest release v1.5.3, published 2026-10-05 |

If you spot a statement here that is out of date or unfair to another project, please open an issue or a pull request.
