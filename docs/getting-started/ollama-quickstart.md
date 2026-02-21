---
layout: page
title: Ollama Quick Start
parent: Getting Started
nav_order: 5
---

# Ollama Quick Start Guide
{: .no_toc }

Run LLM4S with Ollama for **free, local LLM inference** - no API keys required!
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Why Ollama?

**Ollama** is the easiest way to run large language models locally on your machine:

- ✅ **100% Free** - No API costs or rate limits
- ✅ **Private** - Your data never leaves your machine
- ✅ **Fast** - Low latency for local inference
- ✅ **Offline** - Works without internet connection
- ✅ **Multiple Models** - Easy model switching (llama2, mistral, phi, etc.)

Perfect for **development**, **testing**, and **production** workloads where privacy matters.

---

## Prerequisites

- **Java 11+** (JDK 21 recommended)
- **Scala 2.13.16** or **3.7.1**
- **SBT 1.10.6+**
- **4-8GB RAM** (depending on model size)

No API keys needed! 🎉

---

## Step 1: Install Ollama

### macOS / Linux

```bash
curl -fsSL https://ollama.com/install.sh | sh
```

### Windows

Download the installer from [ollama.com/download](https://ollama.com/download)

Or use PowerShell:

```powershell
# Download and install Ollama
winget install Ollama.Ollama
```

### Verify Installation

```bash
ollama --version
# Should output: ollama version is 0.x.x
```

---

## Step 2: Start Ollama Server

### Start the Server

```bash
ollama serve
```

The server will start on **http://localhost:11434** by default.

{: .note }
> On Windows/macOS, Ollama may start automatically as a background service. Check your system tray/menu bar.

### Verify Server is Running

```bash
curl http://localhost:11434
# Should output: Ollama is running
```

---

## Step 3: Pull a Model

Ollama models are pulled on-demand. Let's start with **Mistral 7B** (fast and capable):

```bash
ollama pull mistral
```

### Available Models

| Model | Size | RAM Required | Best For | Pull Command |
|-------|------|--------------|----------|--------------|
| **mistral** | 4.1GB | 8GB | General purpose, fast | `ollama pull mistral` |
| **llama2** | 3.8GB | 8GB | Good balance | `ollama pull llama2` |
| **phi** | 1.6GB | 4GB | Lightweight, fast | `ollama pull phi` |
| **neural-chat** | 4.1GB | 8GB | Conversational | `ollama pull neural-chat` |
| **codellama** | 3.8GB | 8GB | Code generation | `ollama pull codellama` |
| **llama3.2** | 2.0GB | 8GB | Latest Llama | `ollama pull llama3.2` |
| **gemma2** | 5.4GB | 8GB | Google's model | `ollama pull gemma2` |

{: .tip }
> **Recommendation**: Start with `mistral` for the best balance of speed and quality.

### List Downloaded Models

```bash
ollama list
```

---

## Step 4: Configure LLM4S

### Option A: Environment Variables (Recommended)

In your terminal (or add to `.env` file):

```bash
# Linux / macOS
export LLM_MODEL=ollama/mistral
export OLLAMA_BASE_URL=http://localhost:11434

# Windows PowerShell
$env:LLM_MODEL = "ollama/mistral"
$env:OLLAMA_BASE_URL = "http://localhost:11434"
```

### Option B: Application Config

Create `src/main/resources/application.conf`:

```hocon
llm4s {
  provider = "ollama"
  model = "mistral"
  ollama {
    baseUrl = "http://localhost:11434"
  }
}
```

---

## Step 5: Write Your First LLM4S + Ollama App

Create `HelloOllama.scala`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object HelloOllama extends App {
  // Create a conversation with system and user messages
  val conversation = Conversation(Seq(
    SystemMessage("You are a helpful AI assistant."),
    UserMessage("Explain what Scala is in one sentence.")
  ))

  // Load config and make the request
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    completion <- client.complete(conversation)
  } yield completion

  result match {
    case Right(completion) =>
      println(s"Response from ${completion.model}:")
      println(completion.message.content)
    case Left(error) =>
      Console.err.println(s"Error: ${error.formatted}")
  }
}
```

### Run It!

```bash
sbt run
```

### Example Output

```
✓ Response from mistral:
Scala is a statically-typed programming language that combines
object-oriented and functional programming paradigms, running on
the Java Virtual Machine (JVM).
```

---

## Step 6: Try Different Models

You can easily switch models:

```bash
# Try Llama 3.2 (good balance of reasoning and quality)
export LLM_MODEL=ollama/llama3.2

# Try Phi3 (lightweight,faster, smaller)
export LLM_MODEL=ollama/phi3

# Try CodeLlama (for coding tasks)
export LLM_MODEL=ollama/codellama
```

Then run your program again without code changes!

---

## Step 6a: Write Your First LLM4S + Ollama/Llama3.2 App

Llama 3.2 is Meta's latest with an impressive 128K context window. Perfect for processing large documents and long conversations.

For the code, use the same Scala example from **Step 5** - simply change the configuration:

```bash
# Linux / macOS
export LLM_MODEL=ollama/llama3.2

# Windows PowerShell
$env:LLM_MODEL = "ollama/llama3.2"
```

Then run:

```bash
sbt run
```

{: .tip }
> **Why Llama 3.2?** Latest Llama model with 128K context window. Excellent for RAG applications, long-form content generation, and processing large documents. Available in 1B, 3B, 8B, 70B, and 405B sizes.

---

## Step 6b: Write Your First LLM4S + Ollama/Phi3 App

Phi3 is Microsoft's efficient model, even smaller than Phi. Ideal for ultra-low-latency applications and edge deployment.

For the code, use the same Scala example from **Step 5** - simply change the configuration:

```bash
# Linux / macOS
export LLM_MODEL=ollama/phi3

# Windows PowerShell
$env:LLM_MODEL = "ollama/phi3"
```

Then run:

```bash
sbt run
```

{: .tip }
> **Why Phi3?** Microsoft's compact model optimized for efficiency. Smaller than Phi (1.4GB) with competitive quality. Perfect for resource-constrained environments and real-time applications requiring minimal latency.

---
## Step 6c: Write Your First LLM4S + Ollama/CodeLlama App

CodeLlama is purpose-built for code generation and understanding. Create `HelloCodeLlama.scala`:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object HelloCodeLlama extends App {
  // Create a conversation asking for code
  val conversation = Conversation(Seq(
    SystemMessage("You are an expert Scala developer. Write clean, idiomatic code."),
    UserMessage("Write a simple Scala function that reverses a list.")
  ))

  // Load config and make the request
  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    completion <- client.complete(conversation)
  } yield completion

  result match {
    case Right(completion) =>
      println(s"Code suggestion from ${completion.model}:")
      println(completion.message.content)
    case Left(error) =>
      println(s"Error: ${error.formatted}")
  }
}
```

### Configure for CodeLlama

```bash
# Linux / macOS
export LLM_MODEL=ollama/codellama

# Windows PowerShell
$env:LLM_MODEL = "ollama/codellama"
```

### Run It!

```bash
sbt run
```

### Example Output

```
✓ Code suggestion from codellama:
def reverseList[T](list: List[T]): List[T] = {
  list.reverse
}

// Or for manual reversal:
def reverseList[T](list: List[T]): List[T] = {
  def helper(acc: List[T], remaining: List[T]): List[T] = {
    if (remaining.isEmpty) acc
    else helper(remaining.head :: acc, remaining.tail)
  }
  helper(Nil, list)
}
```

{: .tip }
> **Why CodeLlama?** CodeLlama is specialized for code-related tasks. Use it for code generation, refactoring suggestions, and explaining code. 16K context window perfect for larger code files.

---
## Streaming Responses

Get real-time token streaming (like ChatGPT):

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

object StreamingOllama extends App {
  val conversation = Conversation(Seq(
    SystemMessage("You are a concise assistant."),
    UserMessage("Write a haiku about Scala programming.")
  ))

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    completion <- client.streamComplete(
      conversation,
      CompletionOptions(),
      chunk => chunk.content.foreach(print)  // Print tokens as they arrive
    )
  } yield completion

  result match {
    case Right(completion) =>
      println("\n--- Streaming complete! ---")
      println(s"Total content: ${completion.message.content}")
    case Left(error) =>
      Console.err.println(s"Error: ${error.formatted}")
  }
}
```

---

## Tool Calling with Ollama

Ollama supports tool calling (function calling) with compatible models:

```scala
import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi._
import upickle.default._

object OllamaTools extends App {
  // Define result type
  case class WeatherResult(forecast: String)
  implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW

  // Define a weather tool with proper schema
  val weatherSchema = Schema
    .`object`[Map[String, Any]]("Weather parameters")
    .withProperty(
      Schema.property("location", Schema.string("City or location name"))
    )

  val getWeather = ToolBuilder[Map[String, Any], WeatherResult](
    "get_weather",
    "Get the current weather in a location",
    weatherSchema
  ).withHandler { extractor =>
    extractor.getString("location").map { location =>
      // Mock implementation
      WeatherResult(s"Weather in $location: Sunny, 72F")
    }
  }.build()

  val tools = new ToolRegistry(Seq(getWeather))

  val result = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
    agent = new Agent(client)
    state <- agent.run("What's the weather in San Francisco?", tools)
  } yield state

  result match {
    case Right(state) =>
      println("Final response:")
      println(state.conversation.messages.last.content)
    case Left(error) =>
      Console.err.println(s"Error: ${error.formatted}")
  }
}
```

---

## Model Comparison

Performance comparison running on Apple M1 Mac:

| Model | Speed (tokens/sec) | Quality | Memory | Best Use Case |
|-------|-------------------|---------|--------|---------------|
| **mistral** | ~40 | ⭐⭐⭐⭐ | 8GB | General purpose, great balance |
| **llama2** | ~35 | ⭐⭐⭐⭐ | 8GB | Conversational, creative |
| **phi** | ~80 | ⭐⭐⭐ | 4GB | Quick tests, development |
| **codellama** | ~35 | ⭐⭐⭐⭐ | 8GB | Code generation |
| **neural-chat** | ~40 | ⭐⭐⭐⭐ | 8GB | Dialogue, chat apps |

{: .note }
> Performance varies by hardware. These are approximate values on M1 MacBook Pro 16GB RAM.

---

## Configuration Options

### Temperature Control

```scala
CompletionOptions(
  temperature = 0.7,  // Higher = more creative (0.0-2.0)
  maxTokens = Some(1000),
  topP = Some(0.9)
)
```

### Context Length

Ollama models have different context windows:

- **mistral**: 8k tokens
- **llama2**: 4k tokens
- **llama3.2**: 128k tokens
- **codellama**: 16k tokens
- **gemma2**: 8k tokens

---

## Troubleshooting

### "Connection refused" error

**Problem**: Ollama server not running

**Solution**:
```bash
ollama serve
```

### "Model not found" error

**Problem**: Model not pulled

**Solution**:
```bash
ollama pull mistral
```

### Slow inference

**Problem**: Not enough RAM or CPU

**Solutions**:
- Use a smaller model: `ollama pull phi`
- Close other applications
- Check if running on GPU (M-series Mac, CUDA GPU)

### Model comparison

```bash
# List all models with sizes
ollama list

# Delete a model to free space
ollama rm llama2
```

---

## Running the Examples

Try the built-in LLM4S Ollama samples:

```bash
# Set environment
export LLM_MODEL=ollama/mistral
export OLLAMA_BASE_URL=http://localhost:11434

# Run basic Ollama example
sbt "samples/runMain org.llm4s.samples.basic.OllamaExample"

# Run Ollama streaming example
sbt "samples/runMain org.llm4s.samples.basic.OllamaStreamingExample"

# Run tool calling example (works with any provider)
sbt "samples/runMain org.llm4s.samples.toolapi.BuiltinToolsExample"
```

---

## Production Deployment

### Docker Compose Setup

```yaml
# docker-compose.yml
version: '3.8'

services:
  ollama:
    image: ollama/ollama:latest
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama
    mem_limit: 8g

  llm4s-app:
    build: .
    environment:
      - LLM_MODEL=ollama/mistral
      - OLLAMA_BASE_URL=http://ollama:11434
    depends_on:
      - ollama

volumes:
  ollama-data:
```

### Pre-pull Models

```bash
# Pull models into Docker volume
docker-compose exec ollama ollama pull mistral
docker-compose exec ollama ollama pull llama2
```

---

## Ollama vs Cloud Providers

| Feature | Ollama | OpenAI | Anthropic |
|---------|--------|--------|-----------|
| **Cost** | Free | $0.01-0.06/1K tokens | $0.003-0.015/1K tokens |
| **Privacy** | 100% local | Cloud-based | Cloud-based |
| **Speed** | Depends on hardware | Fast (API) | Fast (API) |
| **Offline** | ✅ Yes | ❌ No | ❌ No |
| **Model quality** | Good (7B-70B) | Excellent (GPT-4) | Excellent (Claude) |
| **Setup** | 5 minutes | Instant | Instant |

**Use Ollama for**:
- Development and testing
- Privacy-sensitive applications
- Cost-conscious deployments
- Offline environments

**Use cloud providers for**:
- Highest quality responses
- Scale without hardware limits
- Latest model capabilities

---

## Next Steps

- [Configuration Guide](configuration) - Advanced Ollama settings
- [First Example](first-example) - Build more complex agents
- [Tool Calling](../examples/) - Add custom tools
- [RAG with Ollama](../guide/vector-store) - Retrieval-augmented generation

---

## Resources

- [Ollama Official Site](https://ollama.com)
- [Ollama GitHub](https://github.com/ollama/ollama)
- [Ollama Model Library](https://ollama.com/library)
- [LLM4S Discord](https://discord.gg/4uvTPn6qww) - Get help from the community

---

{: .note-title }
> 💡 Pro Tip
>
> Use Ollama for development and testing, then switch to cloud providers for production by just changing the `LLM_MODEL` environment variable:
>
> ```bash
> # Development
> export LLM_MODEL=ollama/mistral
>
> # Production
> export LLM_MODEL=openai/gpt-4o
> export OPENAI_API_KEY=sk-...
> ```
>
> Your code stays exactly the same! 🎉

---
