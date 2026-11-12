The API reference of every published llm4s module: Large Language Models for Scala.

The guides explain how the pieces fit together: start with
[Getting started](https://llm4s.org/getting-started/), then the [user guide](https://llm4s.org/guide/),
the [examples](https://llm4s.org/examples/) and the [cookbook](https://llm4s.org/examples/cookbook).
The source is on [GitHub](https://github.com/llm4s/llm4s), and every member below links to its own.

Where to start in the API:

 - [[org.llm4s.llmconnect.LLMClient]]: one chat client for every provider, made by [[org.llm4s.llmconnect.LLMConnect]]
 - [[org.llm4s.config.Llm4sConfig]]: loads the providers, tracing and embeddings named in `application.conf`
 - [[org.llm4s.agent.Agent]]: the agent runtime, with tools, guardrails, handoffs and memory
 - [[org.llm4s.toolapi.ToolRegistry]]: the tools a model may call
 - [[org.llm4s.rag.RAG]]: retrieval-augmented generation
