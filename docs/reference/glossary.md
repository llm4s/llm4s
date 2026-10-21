---
layout: page
title: Glossary
parent: Reference
nav_order: 21
---

# Glossary

Short definitions of the terms you meet in LLM4S, in alphabetical order, each with a link to the guide that explains it
or the type it names. Type names are the real ones (package `org.llm4s...`); the [Scaladoc](/scaladoc/) has every
signature.

## A

**Agent** (`org.llm4s.agent.Agent`, module `llm4s-agent`)
: An LLM-driven loop that calls tools, validates its input and output with guardrails, and may hand a conversation to
  another agent. Built once with `Agent.builder(id, client)`. See the [Agent guide](/guide/agents/).

**ApiKey** (`org.llm4s.types.ProviderModelTypes.ApiKey`)
: An opaque type over `String` that gives an API key its own type, so it cannot be passed where a model name is
  expected. `ModelName`, `BaseUrl` and `ProviderName` are its siblings. See [Configuration](/getting-started/configuration).

## C

**Chunking** (`DocumentChunker`, module `llm4s-rag`)
: Splitting a document into pieces small enough to embed and retrieve. The module has simple, sentence, Markdown and
  semantic chunkers. See the [Vector Store guide](/guide/vector-store).

**CompletionOptions** (`org.llm4s.llmconnect.model.CompletionOptions`)
: The settings of one LLM call: temperature, token limit, tools, reasoning effort and so on. It is built with `with*`
  setters, for example `CompletionOptions().withReasoning(ReasoningEffort.High)`. See [Basic usage](/guide/basic-usage).

**Context window**
: The most text, counted in tokens, that a model accepts in one request, prompt and reply together. When a
  conversation outgrows it, `ContextWindowConfig` (`llm4s-agent`) prunes the history. See
  [Context window pruning](/guide/context-window-pruning).

**Conversation** (`org.llm4s.llmconnect.model.Conversation`)
: An immutable sequence of `Message`s (system, user, assistant and tool messages) that is sent to a model. Adding a
  message returns a new conversation.

**Credentials** (`llm4s.credentials.<id>.apiKey`)
: The configuration block that holds one vendor's API key, shared by that vendor's chat sections, embeddings and
  reranker. Each provider module binds it to the vendor's usual environment variable. See
  [API keys](/getting-started/configuration#api-keys).

## D

**Doctor** (`org.llm4s.configpolicy.DoctorCli`)
: A command that checks a whole setup (JDK, configuration, provider module, API key, optionally a local model server)
  and says what to fix. See [Doctor](/reference/doctor).

## E

**Embedding**
: A vector of numbers that represents the meaning of a text, so that similar texts have close vectors. Produced by an
  `EmbeddingProvider` (`org.llm4s.llmconnect.provider.EmbeddingProvider`). See the
  [Vector Store guide](/guide/vector-store).

## G

**Guardrail** (`org.llm4s.agent.guardrails.Guardrail[A]`)
: A pure check that validates a value and returns it or an error. An `InputGuardrail` runs on the user's input before
  the agent processes it, an `OutputGuardrail` on the answer before it is returned. See [Guardrails](/guide/agents/guardrails).

## H

**Handoff** (`org.llm4s.agent.Handoff`)
: A transfer an agent's model may make to another agent. The model is offered it as a tool named `handoff_to_<id>`;
  the target then stays active until it hands off again. See [Handoffs](/guide/agents/handoffs).

**Hybrid search** (`org.llm4s.vectorstore.HybridSearcher`)
: Retrieval that combines vector similarity with keyword (BM25) matching and fuses the two rankings, for example by
  reciprocal rank fusion. See the [Vector Store guide](/guide/vector-store).

## K

**Knowledge graph** (`org.llm4s.knowledgegraph`, module `llm4s-knowledgegraph`)
: Entities (`Node`) and the relationships between them, kept in a graph store and queried alongside or instead of a
  vector store. The Neo4j store is the separate `llm4s-knowledgegraph-neo4j` module.

## L

**Llm4sConfig** (`org.llm4s.config.Llm4sConfig`)
: The entry point that reads configuration at the edge of an application and returns typed values in a `Result`, for
  example `Llm4sConfig.defaultProvider()`. Library code does not read environment variables itself. See
  [Configuration](/getting-started/configuration).

**LLMClient** (`org.llm4s.llmconnect.LLMClient`)
: The interface to one model provider: `complete` for a whole reply and `streamComplete` for a streamed one. Built
  from a provider configuration with `LLMConnect.getClient`.

**LLMError** (`org.llm4s.error.LLMError`)
: The base of every error the library returns as the left side of a `Result`: configuration, authentication, rate
  limit, timeout, network, validation and others. Each has a `formatted` message. See [Error handling](/guide/error-handling).

## M

**MCP (Model Context Protocol)**
: An open protocol through which an application lists and calls the tools a separate server offers. `llm4s-mcp` has a
  client with stdio, SSE and Streamable HTTP transports, `MCPToolRegistry`, and a server. See the
  [module README](https://github.com/llm4s/llm4s/blob/main/modules/mcp/README.md).

**Memory** (`org.llm4s.agent.memory.MemoryManager`, `MemoryStore`, module `llm4s-memory`)
: What an agent keeps beyond the current prompt: a store of recorded messages and facts, and a manager that records,
  consolidates and retrieves them. See [Memory](/guide/agents/memory).

**ModelRegistryService** (`org.llm4s.model.ModelRegistryService`)
: A read-only snapshot of model metadata (context window sizes, capabilities, prices) that `LLMConnect.getClient`
  takes as a `using` parameter. Load it with `Llm4sConfig.modelRegistryService()`.

## N

**Named provider section**
: A block under `llm4s.providers.<name>` in `application.conf` that configures one provider (`provider`, `model` and
  optional keys). `llm4s.providers.provider` names the default section. See
  [Named provider sections](/getting-started/configuration#named-provider-sections).

## P

**Provider**
: A vendor or server that supplies models (OpenAI, Anthropic, Ollama and others). Each is registered by a
  `ProviderDescriptor` in its own module. See [Providers](/guide/providers).

**Provider module**
: The sbt artifact that supplies a provider, such as `llm4s-openai` or `llm4s-ollama`. `llm4s-core` ships none, so a
  build adds the one it uses. See [Installation](/getting-started/installation).

**ProviderDescriptor** (`org.llm4s.llmconnect.spi.ProviderDescriptor`)
: Everything LLM4S needs to know about one provider: its id, the config keys it reads and how to build its client. It
  is listed in an `Llm4sProviderModule`. See [Writing a provider](/guide/writing-a-provider).

**ProviderRegistry** (`org.llm4s.llmconnect.spi.ProviderRegistry`)
: The set of providers a program can use. `ProviderRegistry.discover()` finds the modules on the classpath through
  `META-INF/services`; `ProviderRegistry.of(...)` registers descriptors explicitly.

## R

**RAG (retrieval-augmented generation)** (`org.llm4s.rag.RAG`, module `llm4s-rag`)
: Answering from your own documents: load and chunk them, embed and store the chunks, retrieve the best ones for a
  question and give them to the model with it. See the [Vector Store guide](/guide/vector-store).

**ReasoningEffort** (`org.llm4s.llmconnect.model.ReasoningEffort`)
: How much a reasoning model should think before answering (`None`, `Low`, `Medium`, `High`), set with
  `CompletionOptions.withReasoning`. A model without reasoning ignores it.

**ReliableClient** (`org.llm4s.reliability.ReliableClient`)
: A wrapper that adds retries with backoff, a circuit breaker, a deadline and local rate limiting to any `LLMClient`.

**Reranking** (`org.llm4s.reranker.Reranker`, module `llm4s-rag`)
: A second pass that scores the documents a search returned against the query and reorders them. The module has a
  Cohere reranker and one that asks an LLM.

**Result** (`org.llm4s.types.Result[A]`)
: `Either[LLMError, A]`, the return type of every operation that can fail. The library returns a `Left` instead of
  throwing. See [Error handling](/guide/error-handling).

## S

**Streaming** (`org.llm4s.llmconnect.model.StreamedChunk`)
: Receiving a reply piece by piece as the model produces it, instead of all at once. A client's `streamComplete`
  calls back with each `StreamedChunk`; agents also publish run events. See [Streaming events](/guide/agents/streaming).

## T

**Token**
: The unit a model reads and writes, roughly a word fragment. Prompt and reply sizes, limits and prices are counted in
  tokens; `TokenUsage` reports the count of a call.

**Tool** (`org.llm4s.toolapi.ToolFunction`)
: A function the model may ask the application to run, described by a name, a description and a JSON schema for its
  parameters. Build one with `ToolBuilder`; ready-made ones are in `llm4s-agent-tools`. See
  [Built-in tools](/guide/builtin-tools).

**ToolHints** (`org.llm4s.toolapi.ToolHints`)
: Advisory flags on a tool (read-only, destructive, idempotent, open-world) that a middleware can read, for example
  to ask for approval before a tool that is not read-only. They are hints, not guarantees.

**ToolRegistry** (`org.llm4s.toolapi.ToolRegistry`)
: The set of tools offered to a model; it also executes the call the model makes. `MCPToolRegistry` is a
  `ToolRegistry` that adds the tools of MCP servers.

**Tracing** (`org.llm4s.trace.Tracing`)
: Recording what a run did (LLM calls, tool calls, errors, token usage) as `TraceEvent`s. Core provides console and
  no-op tracing; other backends are modules selected by `llm4s.tracing.mode`. See [Monitoring](/guide/observability/).

**TracingBackend** (`org.llm4s.trace.spi.TracingBackend`)
: The extension point through which a module, such as `llm4s-observability` for Langfuse, supplies a tracing mode
  that `Tracing.create` can select by name.

## W

**Workspace** (module `workspace`)
: A containerised environment in which an agent runs commands and edits files, apart from the host. See
  [Workspace sandbox](/reference/workspace-sandbox).
