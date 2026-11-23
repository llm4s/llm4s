---
layout: page
title: Troubleshooting / FAQ
description: "Fix common LLM4S problems beyond the first run: Scala version issues, agent, tool and streaming problems, RAG, tracing, memory and MCP."
search_title: "LLM4S troubleshooting and FAQ"
parent: Reference
nav_order: 11
---

# Troubleshooting / FAQ

Common problems met once a program runs, and what to do about them. A program that fails on its **first run** - a
missing API key, no default provider, a provider module that is not on the classpath, a 401, 404 or 429, a timeout, a
proxy - is covered in [First-Run Troubleshooting](../getting-started/troubleshooting), which has the symptom, cause and
fix of each.

## Configuration and authentication errors {#configuration-errors}

A configuration error, and a provider's 401, 403, 404 or 429, ends with a link to its entry in
[First-Run Troubleshooting](../getting-started/troubleshooting). When you do not know which part of the setup is
wrong, run the [doctor](doctor): it checks the JDK, your `application.conf`, the default provider, its module, its API
key and the other provider sections, and prints a fix for each step that fails.

## Scala Version Issues

**Q: I get a binary incompatibility error when adding llm4s to my project**
A: LLM4S is published for Scala 3 only, as `_3` artifacts; there is no Scala 2.13 build (calling it from 2.13 through the TASTy reader is best effort: [the TASTy reader guide](../getting-started/scala-2-13)). sbt picks the `_3` artifact when you use `%%`, e.g., `"org.llm4s" %% "llm4s-core" % "{{ site.data.project.latest_release }}"`.

## Agent & Tool Errors

**Q: My agent keeps calling tools and never answers**
A: Give it a clear stopping condition in its system prompt, and bound the turn with `Agent.builder(id, client).withMaxSteps(10)`: a turn that uses its model calls without a final answer ends `AgentStatus.StepLimitReached`. `RunBudgets` (`withTimeout`, `withMaxTokens`), passed in a `RunConfig` with `withBudgets`, bound a run's time and tokens.

**Q: Tool call returns "is not a recognized tool"**
A: Ensure you have registered the tool in your `ToolRegistry` and that the LLM is calling the exact case-sensitive tool name. Inspect `ToolParameterError` if the name matches but arguments fail validation.

**Q: Agent exceeds context window limits**
A: Add `ContextWindowMiddleware` with a `ContextWindowConfig` to the agent: it prunes what is sent to the model, never what the thread stores. Outside an agent, `ContextManager` (`org.llm4s.context`), with `HistoryCompressor` or `LLMCompressor`, shrinks a conversation before it exceeds the model's limit.

**Q: Complex nested JSON parameters in tools failing to parse**
A: Ensure your JSON schema strictly matches the types provided in your Scala `ToolFunction`. Use `SafeParameterExtractor` to see exactly where parsing fails.

**Q: Tool outputs are too large for the context window**
A: `ToolOutputCompressor` (`org.llm4s.context`) compresses large tool outputs in a conversation, such as a full web page, before they are sent back to the model.

## Streaming Issues

**Q: streamComplete returns a result but my callback sees nothing**
A: `client.streamComplete(conversation, options, onChunk)` calls `onChunk` with each `StreamedChunk` as it arrives and returns the accumulated `Completion` when the stream ends. Read the text from each chunk's `content` in the callback; the returned `Completion` already holds the whole reply. A stream that ends without the provider's end marker is a `Left`, not a short `Completion`.

## Provider-Specific Issues

**Q: Using Azure but getting endpoint not found errors**
A: Check the `endpoint` of your `provider = "azure"` section (the deployment endpoint in your Azure OpenAI resource, e.g. `endpoint = ${?AZURE_API_BASE}`) and its `model`. Nothing reads `AZURE_API_BASE` or a deployment-name variable unless your section binds it.

**Q: Ollama returns connection refused**
A: See [Ollama is not running](../getting-started/troubleshooting#ollama-not-running).

## Vector Store & RAG Issues

**Q: PostgreSQL vector store fails on initialization**
A: The Postgres store requires `pgvector`. Install the extension on your database server and run `CREATE EXTENSION IF NOT EXISTS vector;` before initializing the vector store in LLM4S.

## Observability & Tracing

**Q: Traces are not showing up in Langfuse**
A: Verify you have set `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, and `LANGFUSE_URL`, and that `llm4s-observability` is on the classpath with `TRACING_MODE=langfuse`. Call `tracing.close()` before your application exits so pending trace batches are sent.

**Q: Getting RateLimitError: 429 Too Many Requests frequently**
A: Wrap the client in `ReliableClient` (`org.llm4s.reliability`), which retries a 429 with backoff and honours the provider's `Retry-After`; set its policy with `ReliabilityConfig.default.withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3))`. See also [Rate limited (429)](../getting-started/troubleshooting#rate-limited).

## Memory & MCP Workspaces

**Q: SQLite memory store throws database locked exceptions**
A: SQLite may lock the database file if accessed concurrently from multiple threads. Try using `PostgresMemoryStore` for multi-threaded applications, or wrap your accesses in a synchronization block.

**Q: ContainerisedWorkspace fails with Docker socket not found**
A: The workspace requires access to the Docker daemon. If running on Linux, ensure your user is in the `docker` group. See [Workspace Sandbox](workspace-sandbox).

**Q: Cannot connect to stdio MCP server**
A: Build the server config with `MCPServerConfig.stdio`, which requires the exact command that starts the server, then pass it to `new MCPClientImpl(config)`. Make sure the executable is in your PATH or provide the absolute path. Example: `val config = MCPServerConfig.stdio("sqlite", Seq("npx", "-y", "@modelcontextprotocol/server-sqlite")); val client = new MCPClientImpl(config)`.

**Q: An MCP tool call fails with "timed out"**
A: Each request to an MCP server must finish within its `MCPServerConfig.timeout` (30 seconds by default). Over Streamable HTTP that covers the whole exchange, including a reply the server streams as SSE while the tool runs, so a tool that takes longer fails rather than completing late, and the connection is closed. Give that server a longer timeout, for example `MCPServerConfig.streamableHTTP("reports", url, timeout = 5.minutes)`.
