---
layout: page
title: Next Steps
parent: Getting Started
nav_order: 4
---

# Next Steps
{: .no_toc }

You've completed the getting started guide! Here's where to go next.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## 🎉 Congratulations!

You've successfully:

✅ Installed LLM4S
✅ Written your first LLM program
✅ Configured providers and API keys
✅ Understood Result-based error handling

Now let's explore what you can build with LLM4S!

---

## Learning Paths

Choose your path based on what you want to build:

### 🤖 Path 1: Build Agents

**Best for:** Interactive applications, chatbots, assistants

**What you'll learn:**
- Agent framework basics
- Multi-turn conversations
- Tool calling and integration
- Conversation state management

**Start here:**
1. [Agent Framework Guide](/examples/#agent-examples)
2. [Single-Step Agent Example](/examples/#single-step)
3. [Multi-Turn Conversations](/guide/agents/#multi-turn-conversations)
4. [Tools in the Agents Guide](/guide/agents/#agent-with-tools)

**Example project ideas:**
- Customer support chatbot
- Code review assistant
- Research assistant with web search
- Interactive game master

---

### 🛠️ Path 2: Tool Integration

**Best for:** LLMs that interact with external systems

**What you'll learn:**
- Defining custom tools
- Tool parameter schemas
- Model Context Protocol (MCP)
- Tool error handling

**Start here:**
1. [Built-in Tools Guide](/guide/builtin-tools)
2. [Weather Tool Example](/examples/#weather)
3. [MCP Tool Example](/examples/#mcp-tool)
4. [Multi-Tool Example](/examples/#multi-tool)

**Example project ideas:**
- Database query assistant
- API integration agent
- File system navigator
- Task automation system

---

### 💬 Path 3: Conversational AI

**Best for:** Chat applications, dialogue systems

**What you'll learn:**
- Context window management
- Conversation persistence
- History pruning strategies
- Streaming responses

**Start here:**
1. [Multi-Turn Conversations](/guide/agents/#multi-turn-conversations)
2. [Context Window Pruning](/guide/context-window-pruning)
3. [Streaming Events Guide](/guide/agents/streaming)
4. [Long Conversation Example](/examples/#long-conversation)

**Example project ideas:**
- Slack bot
- Discord integration
- Customer service chat
- Educational tutor

---

### 🔍 Path 4: RAG & Knowledge

**Best for:** Question answering, document search, knowledge bases

**What you'll learn:**
- Vector embeddings
- Semantic search
- Document processing
- Retrieval-augmented generation

**Start here:**
1. [Vector Store Guide](/guide/vector-store)
2. [RAG for Enterprise](/guide/patterns/rag-enterprise)
3. [Embedding Example](/examples/#embedding-example)
4. [Vector Search](/guide/vector-store#search)

**Example project ideas:**
- Documentation Q&A system
- PDF analyzer
- Knowledge base search
- Code search engine

---

### 📊 Path 5: Production Systems

**Best for:** Deploying LLM apps to production

**What you'll learn:**
- Error handling patterns
- Observability and tracing
- Performance optimization
- Security best practices

**Start here:**
1. [Production Monitoring](/guide/patterns/production-monitoring)
2. [Observability Guide](../guide/observability)
3. [Error Handling](/guide/error-handling)
4. [Security Guide](/guide/patterns/security-best-practices)

**Example project ideas:**
- Scalable API service
- Multi-tenant SaaS application
- Enterprise integration
- Monitoring dashboard

---

## Quick Reference: Key Features

### Agents
Build sophisticated multi-turn agents with automatic tool calling.

```scala
val agent = Agent.builder("assistant", client).withTools(tools).build()
val result = agent.flatMap(_.run("Your query"))
```

[Learn more →](/examples/#agent-examples)

---

### Tool Calling
Give LLMs access to external functions and APIs.

```scala
val tool = ToolFunction(
  name = "search",
  description = "Search the web",
  function = search _
)
```

[Learn more →](/guide/builtin-tools)

---

### Multi-Turn Conversations
Functional conversation management without mutation.

```scala
val result2 = agent.continueConversation(result1, "Next question")
```

[Learn more →](/guide/agents/#multi-turn-conversations)

---

### Context Management
Automatically manage token windows and prune history.

```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.OldestFirst
)
```

[Learn more →](/guide/context-window-pruning)

---

### Streaming
Get real-time token-by-token responses.

```scala
val stream = client.completeStreaming(messages, None)
stream.foreach(chunk => print(chunk.content))
```

[Learn more →](/guide/agents/streaming)

---

### Observability
Trace LLM calls with Langfuse integration.

```scala
// Automatic tracing when configured
TRACING_MODE=langfuse
```

[Learn more →](../guide/observability)

---

### Embeddings
Create and search vector embeddings.

```scala
val embeddings = embeddingsClient.embed(documents)
val results = search(query, embeddings)
```

[Learn more →](/guide/vector-store)

---

### MCP Integration
Connect to external Model Context Protocol servers.

```scala
val mcpTools = MCPClient.loadTools("mcp-server-name")
```

[Learn more →](/examples/#mcp-examples)

---

## Example Gallery

Browse the working examples organized by category:

### Basic Examples
- [Basic LLM Calling](/examples/#basic-llm-calling)
- [Streaming Responses](/examples/#streaming)
- [Multi-Provider Setup](/guide/basic-usage#multi-provider-pattern)
- [Ollama (Local Models)](/examples/#ollama)
- [Tracing Integration](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/basic/BasicLLMCallingWithTrace.scala)

[View all basic examples →](/examples/#basic-examples)

### Agent Examples
- [Single-Step Agent](/examples/#single-step)
- [Multi-Step Agent](/examples/#multi-step)
- [Multi-Turn Conversations](/examples/#multi-turn)
- [Long Conversations](/examples/#long-conversation)
- [Conversation Persistence](/examples/#persistence)
- [MCP Agent](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/agent/MCPAgentExample.scala)

[View all agent examples →](/examples/#agent-examples)

### Tool Examples
- [Weather Tool](/examples/#weather)
- [Multi-Tool Agent](/examples/#multi-tool)
- [Tool Error Messages](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/toolapi/ErrorMessageDemonstration.scala)
- [MCP Tools](/examples/#mcp-tool)

[View all tool examples →](/examples/#tool-examples)

### Context Management Examples
- [Context Pipeline](/examples/#context-pipeline)
- [Token Windows](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/context/tokens/TokenWindowExample.scala)
- [History Digest](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/context/HistoryDigestExample.scala)
- [Compression](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/context/DeterministicCompressionExample.scala)
- [Tool Externalization](https://github.com/llm4s/llm4s/blob/main/modules/samples/src/main/scala/org/llm4s/samples/context/ToolExternalizationExample.scala)

[View all context examples →](/examples/#context-management)

### More Examples
- [Embeddings](/examples/#embeddings)
- [MCP Integration](/examples/#mcp-examples)
- [Streaming](/examples/#streaming-examples)

[Browse all examples →](/examples/)

---

## Common Recipes

### Recipe 1: Simple Q&A Bot

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService

def askQuestion(question: String): String = {
  val result = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
    response <- client.complete(
      List(
        SystemMessage("You are a helpful Q&A assistant."),
        UserMessage(question)
      ),
      None
    )
  } yield response.content

  result.fold(_ => "Sorry, I couldn't process that question.", identity)
}
```

### Recipe 2: Agent with Custom Tools

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }

def getCurrentTime(): String =
  java.time.LocalDateTime.now().toString

val timeTool = ToolFunction(
  name = "get_time",
  description = "Get current date and time",
  function = getCurrentTime _
)

val result = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client <- LLMConnect.getClient(providerConfig)
  agent <- Agent.builder("time-agent", client).withTools(new ToolRegistry(Seq(timeTool))).build()
  result <- agent.run("What time is it?")
} yield result.answer
```

### Recipe 3: Streaming Chat

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage
import org.llm4s.model.ModelRegistryService

def streamChat(message: String): Unit = {
  val result = for {
    providerConfig <- Llm4sConfig.defaultProvider()
    registry <- Llm4sConfig.modelRegistryService()
    given ModelRegistryService = registry
    client <- LLMConnect.getClient(providerConfig)
    stream <- client.completeStreaming(
      List(UserMessage(message)),
      None
    )
  } yield {
    stream.foreach(chunk => print(chunk.content))
    println()
  }

  result.left.foreach(error => println(s"Error: $error"))
}
```

### Recipe 4: Multi-Turn with Pruning

```scala
import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware

val config = ContextWindowConfig(
  maxMessages = Some(20),
  preserveSystemMessage = true,
  pruningStrategy = PruningStrategy.OldestFirst
)

// Pruning is middleware on the agent: it trims what each model call is sent,
// and the thread keeps the full history
val result = for {
  agent  <- Agent.builder("assistant", client)
    .withTools(tools)
    .withMiddleware(ContextWindowMiddleware(config))
    .build()
  first  <- agent.run("First question")                              // Turn 1
  second <- agent.continueConversation(first, "Follow-up question")  // Turn 2, pruned when sent
} yield second
```

---

## Troubleshooting

### Common Issues

**Problem: API key errors**
- Check the vendor's variable is set in the shell that runs your app: `echo $OPENAI_API_KEY` - a
  section without an `apiKey` of its own uses it ([API keys](configuration#api-keys))
- If the section sets its own `apiKey = ${?SOME_VAR}`, that wins: check `SOME_VAR` instead
- Check key starts with correct prefix (`sk-` for OpenAI, `sk-ant-` for Anthropic)

**Problem: Model not found**
- Verify `model` in the provider section is the provider's own model name (e.g. `gpt-4o-mini`, no `openai/` prefix)
- Check `llm4s.providers.provider` names the section you meant
- Check provider supports that model
- Try a different model

**Problem: Slow responses**
- Use streaming for real-time feedback
- Consider using a faster model (gpt-3.5-turbo, claude-haiku)
- Check your internet connection

**Problem: Token limit errors**
- Implement context window pruning
- Use shorter system prompts
- Summarize conversation history

[Full troubleshooting guide →](/reference/troubleshooting)

---

## Community & Support

### Get Help

- **Discord**: [Join our community](https://discord.gg/4uvTPn6qww) - Active community for questions
- **GitHub Issues**: [Report bugs](https://github.com/llm4s/llm4s/issues) - Bug reports and feature requests
- **Documentation**: Browse the [user guide](../guide/basic-usage) - Comprehensive guides
- **Examples**: Check [working examples](/examples/) - runnable code samples

### Stay Updated

- **GitHub**: [Star the repo](https://github.com/llm4s/llm4s) - Get notified of updates
- **Roadmap**: [View the roadmap](/reference/roadmap) - See what's coming
- **Changelog**: [Release notes](https://github.com/llm4s/llm4s/releases) - Track changes

### Contribute

- **Starter Kit**: Use [llm4s.g8](https://github.com/llm4s/llm4s.g8) to scaffold projects
- **Share Examples**: Post your projects in Discord
- **Contribute**: See the [contributing guide](/reference/contributing)

---

## Recommended Learning Order

### Week 1: Fundamentals
1. ✅ Complete Getting Started (you are here!)
2. Read [Basic Usage Guide](../guide/basic-usage)
3. Try [Basic Examples](/examples/#basic-examples)
4. Experiment with different providers

### Week 2: Agents & Tools
1. Read [Agent Framework](/examples/#agent-examples)
2. Build a simple agent with one tool
3. Try [Tool Examples](/examples/#tool-examples)
4. Add multiple tools

### Week 3: Advanced Patterns
1. Implement [Multi-Turn Conversations](/guide/agents/#multi-turn-conversations)
2. Add [Context Window Pruning](/guide/context-window-pruning)
3. Set up [Observability](../guide/observability)
4. Try [Long Conversation Example](/examples/#long-conversation)

### Week 4: Production
1. Read [Production Monitoring](/guide/patterns/production-monitoring)
2. Implement error handling
3. Add monitoring and tracing
4. Deploy your first production agent

---

## Quick Links

### Documentation
- [User Guide](../guide/basic-usage) - Feature guides
- [API Reference](/api/) - API docs
- [Advanced Topics](/advanced/) - Production topics

### Examples
- [All Examples](/examples/) - Browse 46 examples
- [Basic](/examples/#basic-examples) - Getting started
- [Agents](/examples/#agent-examples) - Agent patterns
- [Tools](/examples/#tool-examples) - Tool integration

### Reference
- [Configuration](/getting-started/configuration) - Setup guide
- [Migration Guide](/reference/migration) - Version upgrades
- [Roadmap](/reference/roadmap) - Future plans

---

## What to Build?

Need inspiration? Here are some project ideas:

**Beginner Projects:**
- Simple Q&A bot
- Code explainer
- Translation service
- Writing assistant

**Intermediate Projects:**
- Multi-tool research agent
- Database query interface
- API integration bot
- Document summarizer

**Advanced Projects:**
- Multi-agent system
- RAG-powered knowledge base
- Production chatbot service
- Custom tool ecosystem

---

## Ready to Build?

Pick your learning path and start building:

<div class="grid">
  <div class="grid-item">
    <h3>🤖 Build Agents</h3>
    <a href="/examples/#agent-examples">Agent Framework →</a>
  </div>

  <div class="grid-item">
    <h3>🛠️ Add Tools</h3>
    <a href="/guide/builtin-tools">Tools →</a>
  </div>

  <div class="grid-item">
    <h3>💬 Chat Apps</h3>
    <a href="/guide/agents/#multi-turn-conversations">Multi-Turn →</a>
  </div>

  <div class="grid-item">
    <h3>🔍 RAG Systems</h3>
    <a href="/guide/vector-store">Vector Store →</a>
  </div>
</div>

---

**Happy building with LLM4S!** 🚀

Questions? [Join our Discord](https://discord.gg/4uvTPn6qww)
