---
layout: page
title: Troubleshooting / FAQ
parent: Reference
nav_order: 11
---

# Troubleshooting / FAQ

This guide addresses common errors and issues you might encounter when building with the LLM4S framework.

## Configuration Errors

**Q: I get "ConfigurationError: No provider configured" on startup**
A: Set `LLM_MODEL` and your API key. Example: `export LLM_MODEL=openai/gpt-4o && export OPENAI_API_KEY=sk-...`

**Q: My application configuration isn't overriding the defaults**
A: LLM4S uses PureConfig. Ensure your `application.conf` is in the `src/main/resources` directory and that you are using `Llm4sConfig.provider("openai")` (passing your provider name) instead of the legacy `ConfigReader` to load typed settings.

## Authentication Errors

**Q: I get "AuthenticationError: 401 Unauthorized"**
A: Check your API key is correct. OpenAI keys start with `sk-`, Anthropic with `sk-ant-...`. Ensure the environment variable exactly matches what the provider expects.

## Scala Version Issues

**Q: I get a binary incompatibility error when adding llm4s to my project**
A: Use the `_3` artifact for Scala 3 projects, `_2.13` for Scala 2.13 projects. SBT usually handles this if you use `%%`, e.g., `"org.llm4s" %% "core" % "0.2.0"`.

## Agent & Tool Errors

**Q: My agent runs forever and never completes**
A: Set a clear termination condition in your system prompt or set `maxSteps` on `Agent.run` to prevent infinite tool-calling loops. Example: `agent.run(prompt, maxSteps = 10)`.

**Q: Tool call returns "is not a recognized tool"**
A: Ensure you have registered the tool in your `ToolRegistry` and that the LLM is calling the exact case-sensitive tool name. Inspect `ToolParameterError` if the name matches but arguments fail validation.

**Q: Agent exceeds context window limits**
A: Use the `ContextManager` with a `HistoryCompressor` or `LLMCompressor` to automatically manage and shrink conversation history before it exceeds token limits.

**Q: Complex nested JSON parameters in tools failing to parse**
A: Ensure your JSON schema strictly matches the types provided in your Scala `ToolFunction`. Use `SafeParameterExtractor` to debug exactly where parsing fails.

**Q: Tool outputs are too large and crash the context window**
A: Wrap your tool with a `ToolOutputCompressor` to summarize or truncate large responses (like full webpage text) before adding them to the agent's memory.

## Streaming Issues

**Q: streamComplete returns immediately with an empty result**
A: Check if the provider requires `stream: true` in your `CompletionOptions` or if you are discarding the initial `TokenReceived` events. Use `StreamingResponseHandler` to safely accumulate events.

**Q: streaming chunks arrive out of order**
A: Ensure you are using `StreamingAccumulator` which correctly buffers and assembles `SSE` streams in sequence automatically.

## Provider-Specific Issues

**Q: Using Azure but getting endpoint not found errors**
A: Ensure you have set the deployment name correctly in your environment. Example: `export AZURE_DEPLOYMENT_NAME=your-deployment-name` and your base URL matches `https://<resource>.openai.azure.com/` in `AZURE_API_BASE`.

**Q: Ollama returns connection refused**
A: Ensure your local Ollama daemon is running (`ollama serve`) and the URL is correct. The default is `http://localhost:11434`. Example: `export OLLAMA_BASE_URL=http://localhost:11434`.

**Q: Getting "Unsupported modality" error for image requests**
A: Not all models support image generation or vision. Ensure you are using an image-capable model configuration (e.g., `dall-e-3` or `gpt-4o`) via `ImageGenerationClient`.

## Vector Store & RAG Issues

**Q: I get "No vector store configured" error**
A: Ensure you have added the correct vector store dependency (e.g., `"org.llm4s" %% "knowledgegraph-neo4j"`) and defined the configuration in your `application.conf` or environment variables for the specific backend.

**Q: PostgreSQL vector store crashes on initialization**
A: The Postgres store requires `pgvector`. Install the extension on your DB server and run `CREATE EXTENSION IF NOT EXISTS vector;` before initializing the vector store in LLM4S.

## Observability & Tracing

**Q: Traces are not showing up in Langfuse**
A: Verify you have set `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, and `LANGFUSE_URL`. Crucially, ensure you invoke `Tracing.shutdown()` before your application exits to avoid dropping pending trace batches.

**Q: Getting RateLimitError: 429 Too Many Requests frequently**
A: Use `LLMClientRetry` or the `ReliabilityConfig` to automatically handle 429s with exponential backoff. Example: `ReliabilityConfig.default.withRetryPolicy(RetryPolicy.exponentialBackoff(maxAttempts = 3))`.

## Memory & MCP Workspaces

**Q: SQLite memory store throws database locked exceptions**
A: SQLite may lock the database file if accessed concurrently from multiple threads. Try using `PostgresMemoryStore` for multi-threaded applications, or wrap your accesses in a synchronization block.

**Q: ContainerisedWorkspace fails with Docker socket not found**
A: The workspace requires access to the Docker daemon. If running on Linux, ensure your user is in the `docker` group, or provide the explicit socket path to the `WorkspaceSandboxConfig`.

**Q: Cannot connect to stdio MCP server**
A: Build the server config with `MCPServerConfig.stdio`, which requires the exact command that starts the server, then pass it to `new MCPClientImpl(config)`. Make sure the executable is in your PATH or provide the absolute path. Example: `val config = MCPServerConfig.stdio("sqlite", Seq("npx", "-y", "@modelcontextprotocol/server-sqlite")); val client = new MCPClientImpl(config)`.
