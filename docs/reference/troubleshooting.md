---
layout: page
title: Troubleshooting / FAQ
parent: Reference
nav_order: 11
---

# Troubleshooting / FAQ

This guide addresses common errors and issues you might encounter when building with the LLM4S framework.

## Configuration Errors

**Q: Something is wrong with my setup and I do not know which part**
A: Run the [doctor](doctor). It checks the JDK, your `application.conf`, the default provider, its module, its API key and (for a local server) whether the server is running and has your model, and prints a fix for each step that fails: `sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli --config /path/to/application.conf"`.

**Q: I get a "ConfigurationError" about the provider on startup ("not found", "missing required fields", no default selected)**
A: LLM4S does not read `LLM_MODEL`. Define a named provider section in your `application.conf` and select it as the default; its key comes from the vendor's variable, which the provider module binds:

```hocon
llm4s {
  providers {
    provider = "openai-main"
    openai-main {
      provider = "openai"
      model    = "gpt-4o-mini"
    }
  }
}
```

Then `export OPENAI_API_KEY=sk-...` and call `Llm4sConfig.defaultProvider()`. A "missing required fields ... apiKey" error means neither the vendor's variable (`OPENAI_API_KEY`) nor the section's own `apiKey` is set - or the provider module that binds the variable is missing; only the section being loaded is validated, so check the error names the section you meant. See [Configuration](../getting-started/configuration.md#named-provider-sections).

**Q: My application configuration isn't overriding the defaults**
A: LLM4S uses PureConfig. Ensure your `application.conf` is in the `src/main/resources` directory (or point at it with `-Dconfig.file=...`), and load providers with `Llm4sConfig.defaultProvider()` or `Llm4sConfig.provider("<section name>")` - the name of a section under `llm4s.providers`, not the provider type. `-D` system properties override `application.conf`.

## Authentication Errors

**Q: I get "AuthenticationError: 401 Unauthorized"**
A: Check your API key is correct. OpenAI keys start with `sk-`, Anthropic with `sk-ant-...`. Ensure the environment variable you set is the one the section's `apiKey = ${?VAR}` binding names, and that the section's `provider` matches the key's vendor.

## Scala Version Issues

**Q: I get a binary incompatibility error when adding llm4s to my project**
A: LLM4S is published for Scala 3 only, as `_3` artifacts; there is no Scala 2.13 build. SBT usually handles this if you use `%%`, e.g., `"org.llm4s" %% "llm4s-core" % "{{ site.data.project.latest_release }}"`.

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
A: Check if the provider requires `stream: true` in your `CompletionOptions` or if you are discarding the initial `TokenReceived` events. Use `StreamingAccumulator` to accumulate the chunks passed to your `onChunk` callback.

**Q: streaming chunks arrive out of order**
A: Ensure you are using `StreamingAccumulator` which correctly buffers and assembles `SSE` streams in sequence automatically.

## Provider-Specific Issues

**Q: Using Azure but getting endpoint not found errors**
A: Check the `endpoint` of your `provider = "azure"` section (the deployment endpoint in your Azure OpenAI resource, e.g. `endpoint = ${?AZURE_API_BASE}`) and its `model`. Nothing reads `AZURE_API_BASE` or a deployment-name variable unless your section binds it.

**Q: Ollama returns connection refused**
A: Ensure your local Ollama daemon is running (`ollama serve`) and the `baseUrl` of your `provider = "ollama"` section is correct - usually `http://localhost:11434`. Ollama sections require `baseUrl`; bind it with `baseUrl = ${?OLLAMA_BASE_URL}` if you want to set it from the environment.

**Q: Getting "Unsupported modality" error for image requests**
A: Not all models support image generation or vision. Ensure you are using an image-capable model configuration (e.g., `dall-e-3` or `gpt-4o`) via `ImageGenerationClient`.

## Vector Store & RAG Issues

**Q: I get "No vector store configured" error**
A: Ensure you have added the correct vector store dependency (e.g., `"org.llm4s" %% "llm4s-knowledgegraph-neo4j"`) and defined the configuration in your `application.conf` or environment variables for the specific backend.

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
