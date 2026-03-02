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
3. [Multi-Turn Conversations](/guide/multi-turn)
4. [Tool Calling Guide](/guide/tool-calling)

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
1. [Tool Calling Guide](/guide/tool-calling)
2. [Weather Tool Example](/examples/tools#weather)
3. [MCP Integration](/guide/mcp)
4. [Multi-Tool Example](/examples/tools#multi-tool)

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
1. [Multi-Turn Conversations](/guide/multi-turn)
2. [Context Management](/guide/context-management)
3. [Streaming Guide](/guide/streaming)
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
1. [Embeddings Guide](/guide/embeddings)
2. [RAG Patterns](/advanced/rag-patterns)
3. [Embedding Example](/examples/embeddings)
4. [Vector Search](/guide/embeddings#vector-search)

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
1. [Production Readiness](/advanced/production)
2. [Observability Guide](../guide/observability)
3. [Error Handling](/advanced/error-handling)
4. [Security Guide](/advanced/security)

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
val agent = new Agent(client)
val state = agent.run("Your query", tools)
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

[Learn more →](/guide/tool-calling)

---

### Multi-Turn Conversations
Functional conversation management without mutation.

```scala
val state2 = agent.continueConversation(state1, "Next question")
```

[Learn more →](/guide/multi-turn)

---

### Context Management
Automatically manage token windows and prune history.

```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.OldestFirst
)
```

[Learn more →](/guide/context-management)

---

### Streaming
Get real-time token-by-token responses.

```scala
val stream = client.completeStreaming(messages, None)
stream.foreach(chunk => print(chunk.content))
```

[Learn more →](/guide/streaming)

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

[Learn more →](/guide/embeddings)

---

### MCP Integration
Connect to external Model Context Protocol servers.

```scala
val mcpTools = MCPClient.loadTools("mcp-server-name")
```

[Learn more →](/guide/mcp)

---

## Example Gallery

Browse **69 working examples** organized by category:

### Basic Examples (9)
- [Basic LLM Calling](/examples/basic#basic-llm-calling)
- [Streaming Responses](/examples/basic#streaming)
- [Multi-Provider Setup](/examples/basic#multi-provider)
- [Ollama (Local Models)](/examples/basic#ollama)
- [Tracing Integration](/examples/basic#tracing)

[View all basic examples →](/examples/basic)

### Agent Examples (6)
- [Single-Step Agent](/examples/#single-step)
- [Multi-Step Agent](/examples/#multi-step)
- [Multi-Turn Conversations](/examples/#multi-turn)
- [Long Conversations](/examples/#long-conversation)
- [Conversation Persistence](/examples/#persistence)
- [MCP Agent](/examples/#mcp-agent)

[View all agent examples →](/examples/#agent-examples)

### Tool Examples (5)
- [Weather Tool](/examples/tools#weather)
- [LLM with Tools](/examples/tools#llm-weather)
- [Multi-Tool Agent](/examples/tools#multi-tool)
- [Error Handling](/examples/tools#error-handling)
- [MCP Tools](/examples/tools#mcp)

[View all tool examples →](/examples/tools)

### Context Management Examples (8)
- [Context Pipeline](/examples/context#pipeline)
- [Token Windows](/examples/context#token-window)
- [History Digest](/examples/context#digest)
- [Compression](/examples/context#compression)
- [Tool Externalization](/examples/context#externalization)

[View all context examples →](/examples/context)

### More Examples
- [Embeddings](/examples/embeddings) (5 examples)
- [MCP Integration](/examples/mcp) (3 examples)
- [Streaming](/examples/streaming) (2 examples)

[Browse all 46 examples →](/examples/)

---

## Common Recipes

### Recipe 1: Simple Q&A Bot

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

def askQuestion(question: String): String = {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
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
import org.llm4s.toolapi.{ ToolFunction, ToolRegistry }

def getCurrentTime(): String =
  java.time.LocalDateTime.now().toString

val timeTool = ToolFunction(
  name = "get_time",
  description = "Get current date and time",
  function = getCurrentTime _
)

val result = for {
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
  tools = new ToolRegistry(Seq(timeTool))
  agent = new Agent(client)
  state <- agent.run("What time is it?", tools)
} yield state.finalResponse
```

### Recipe 3: Streaming Chat

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.UserMessage

def streamChat(message: String): Unit = {
  val result = for {
    providerConfig <- Llm4sConfig.provider()
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

val config = ContextWindowConfig(
  maxMessages = Some(20),
  preserveSystemMessage = true,
  pruningStrategy = PruningStrategy.OldestFirst
)

// Turn 1
val state1 = agent.run("First question", tools)

// Turn 2 with pruning
val state2 = agent.continueConversation(
  state1.getOrElse(???),
  "Follow-up question",
  contextWindowConfig = Some(config)
)
```

---

## Troubleshooting

### Common Issues

**Problem: API key errors**
- Check environment variables are set: `echo $OPENAI_API_KEY`
- Verify `.env` file is sourced: `source .env`
- Check key starts with correct prefix (`sk-` for OpenAI, `sk-ant-` for Anthropic)

**Problem: Model not found**
- Verify `LLM_MODEL` format: `provider/model-name`
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
- **Examples**: Check [working examples](/examples/) - 46 code samples

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
3. Try [Basic Examples](/examples/basic)
4. Experiment with different providers

### Week 2: Agents & Tools
1. Read [Agent Framework](/examples/#agent-examples)
2. Build a simple agent with one tool
3. Try [Tool Examples](/examples/tools)
4. Add multiple tools

### Week 3: Advanced Patterns
1. Implement [Multi-Turn Conversations](/guide/multi-turn)
2. Add [Context Management](/guide/context-management)
3. Set up [Observability](../guide/observability)
4. Try [Long Conversation Example](/examples/#long-conversation)

### Week 4: Production
1. Read [Production Guide](/advanced/production)
2. Implement error handling
3. Add monitoring and tracing
4. Deploy your first production agent

---

## Quick Links

### Documentation
- [User Guide](../guide/basic-usage) - Feature guides
- [API Reference](/api/llm-client) - API docs
- [Advanced Topics](/advanced/production) - Production topics

### Examples
- [All Examples](/examples/) - Browse 46 examples
- [Basic](/examples/basic) - Getting started
- [Agents](/examples/#agent-examples) - Agent patterns
- [Tools](/examples/tools) - Tool integration

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
    <a href="/guide/tool-calling">Tool Calling →</a>
  </div>

  <div class="grid-item">
    <h3>💬 Chat Apps</h3>
    <a href="/guide/multi-turn">Multi-Turn →</a>
  </div>

  <div class="grid-item">
    <h3>🔍 RAG Systems</h3>
    <a href="/guide/embeddings">Embeddings →</a>
  </div>
</div>

---

**Happy building with LLM4S!** 🚀

Questions? [Join our Discord](https://discord.gg/4uvTPn6qww)
