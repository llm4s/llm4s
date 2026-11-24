The API reference of every published llm4s module: Large Language Models for Scala.

The guides explain how the pieces fit together: start with
[Getting started](https://llm4s.org/getting-started/), then the [user guide](https://llm4s.org/guide/),
the [examples](https://llm4s.org/examples/) and the [cookbook](https://llm4s.org/examples/cookbook).
The source is on [GitHub](https://github.com/llm4s/llm4s), and every member below links to its own.

Stability tiers: a type annotated `@Stable` (`org.llm4s.annotation`) is part of the surface 1.0 freezes and keeps
binary compatible across 1.x, and one annotated `@Experimental` may change in any release.
Only the frozen modules carry the annotations (`llm4s-core`, `llm4s-agent`, `llm4s-openai`,
`llm4s-openai-compatible`, `llm4s-anthropic`, `llm4s-gemini` and `llm4s-ollama`); a type in any other module has
its module's tier, Beta or Experimental. See [API Stability](https://llm4s.org/reference/api-stability).

Where to start in the API:

 - [[org.llm4s.llmconnect.LLMClient]]: one chat client for every provider, made by [[org.llm4s.llmconnect.LLMConnect]]
 - [[org.llm4s.config.Llm4sConfig]]: loads the providers, tracing and embeddings named in `application.conf`
 - [[org.llm4s.agent.Agent]]: the agent runtime, with tools, guardrails, handoffs and memory
 - [[org.llm4s.toolapi.ToolRegistry]]: the tools a model may call
 - [[org.llm4s.rag.RAG]]: retrieval-augmented generation
 - [[org.llm4s.agent.AgentResult]]: what a run returns, with its [[org.llm4s.agent.AgentStatus]]
 - [[org.llm4s.llmconnect.EmbeddingClient]]: embeddings through any embedding provider
 - [[org.llm4s.llmconnect.spi.ProviderDescriptor]] and [[org.llm4s.llmconnect.spi.Llm4sProviderModule]]: how a provider module plugs in
 - [[org.llm4s.trace.Tracing]]: the tracing contract; a backend implements [[org.llm4s.trace.spi.TracingBackend]]
 - [[org.llm4s.reliability.ReliableClient]]: retries, timeouts, circuit breaking and rate limits around any client
 - [[org.llm4s.mcp.MCPClient]]: the Model Context Protocol client
 - [[org.llm4s.testkit.ScriptedLLMClient]]: a scriptable fake client for your own tests
 - [[org.llm4s.javaapi.Llm4s]]: the Java facade over the client
