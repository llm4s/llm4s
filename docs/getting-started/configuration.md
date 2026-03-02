---
layout: page
title: Configuration
parent: Getting Started
nav_order: 3
---

# Configuration Guide
{: .no_toc }

Configure LLM4S for multiple providers, environments, and use cases.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Configuration Hierarchy

LLM4S uses a hierarchical configuration system with the following precedence (highest to lowest):

1. **System properties** (`-Dkey=value` JVM flags)
2. **Environment variables** (`.env` file or shell exports)
3. **application.conf** (HOCON format in your project)
4. **reference.conf** (Library defaults)

---

## Environment Variables

The simplest way to configure LLM4S is through environment variables.

### Core Settings

```bash
# Required: Model selection (determines provider)
LLM_MODEL=openai/gpt-4o

# Required: API key for your chosen provider
OPENAI_API_KEY=sk-proj-...
```

### Complete Environment Setup

Create a `.env` file in your project root:

```bash
# ===================
# Model Selection
# ===================
# Format: <provider>/<model-name>
# Supported providers: openai, anthropic, azure, ollama, gemini, cohere
LLM_MODEL=openai/gpt-4o

# ===================
# OpenAI Configuration
# ===================
OPENAI_API_KEY=sk-proj-...
OPENAI_BASE_URL=https://api.openai.com/v1  # Optional: Override endpoint
OPENAI_ORGANIZATION=org-...                # Optional: Organization ID

# ===================
# Anthropic Configuration
# ===================
ANTHROPIC_API_KEY=sk-ant-...
ANTHROPIC_BASE_URL=https://api.anthropic.com  # Optional
ANTHROPIC_VERSION=2023-06-01                  # Optional: API version

# ===================
# Azure OpenAI Configuration
# ===================
AZURE_API_KEY=your-azure-key
AZURE_API_BASE=https://your-resource.openai.azure.com
AZURE_DEPLOYMENT_NAME=gpt-4o
AZURE_API_VERSION=2024-02-15-preview  # Optional

# ===================
# Ollama Configuration
# ===================
OLLAMA_BASE_URL=http://localhost:11434
# No API key needed for Ollama

# ===================
# Google Gemini Configuration
# ===================
GOOGLE_API_KEY=your-google-api-key
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta  # Optional

# ===================
# Cohere Configuration
# ===================
COHERE_API_KEY=your-cohere-api-key
COHERE_BASE_URL=https://api.cohere.com  # Optional

# ===================
# Tracing Configuration
# ===================
TRACING_MODE=langfuse  # Options: langfuse, console, none

# Langfuse settings (if using TRACING_MODE=langfuse)
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_URL=https://cloud.langfuse.com  # or self-hosted

# ===================
# Embeddings Configuration (RAG)
# ===================
# Unified format: provider/model (recommended)
EMBEDDING_MODEL=openai/text-embedding-3-small  # Uses default base URL
# OPENAI_API_KEY is shared with LLM config above

# Or for Voyage embeddings:
# EMBEDDING_MODEL=voyage/voyage-3
# VOYAGE_API_KEY=pa-...

# Or for local embeddings with Ollama (no API key needed):
# EMBEDDING_MODEL=ollama/nomic-embed-text

# Optional: Override default base URLs
# OPENAI_EMBEDDING_BASE_URL=https://api.openai.com/v1
# VOYAGE_EMBEDDING_BASE_URL=https://api.voyageai.com/v1
# OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434

# ===================
# Workspace Configuration (Optional)
# ===================
WORKSPACE_DIR=/tmp/llm4s-workspace
WORKSPACE_IMAGE=llm4s/workspace-runner:latest
WORKSPACE_PORT=8080
WORKSPACE_CONTAINER_NAME=llm4s-workspace
```

### Load Environment Variables

**Option 1: Source the file**

```bash
source .env
sbt run
```

**Option 2: Use sbt-dotenv plugin**

{: .warning }
**Java 16+ Compatibility Issue:** The sbt-dotenv plugin can cause a `java.lang.reflect.InaccessibleObjectException` at startup on Java 16+ due to module system restrictions. Use **Option 1** or **Option 3** as alternatives, or apply the fix below.

Add to `project/plugins.sbt`:

```scala
addSbtPlugin("au.com.onegeek" %% "sbt-dotenv" % "2.1.233")
```

Variables automatically load when SBT starts!

**Java 16+ Fix:** If you see `InaccessibleObjectException`, create (or add to) `.jvmopts` in your project root:

```
--add-opens=java.base/java.lang=ALL-UNNAMED
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED
```

Restart sbt after adding this file. This grants the plugin the reflective access it needs and works on all platforms.

**Option 3: IntelliJ IDEA**

1. Run → Edit Configurations
2. Environment variables → Add from file
3. Select your `.env` file

---

## Application Configuration (HOCON)

For more complex configurations, use `application.conf`:

### Create application.conf

Create `src/main/resources/application.conf`:

```hocon
llm {
  # Model configuration
  model = ${?LLM_MODEL}  # Fallback to env var
  model = "openai/gpt-4o"  # Default if not set

  # Generation parameters
  temperature = 0.7
  max-tokens = 2000
  top-p = 1.0

  # Provider configurations
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = ${?OPENAI_BASE_URL}
      base-url = "https://api.openai.com/v1"  # Default
      organization = ${?OPENAI_ORGANIZATION}
    }

    anthropic {
      api-key = ${?ANTHROPIC_API_KEY}
      base-url = ${?ANTHROPIC_BASE_URL}
      base-url = "https://api.anthropic.com"
      version = ${?ANTHROPIC_VERSION}
      version = "2023-06-01"
    }

    azure {
      api-key = ${?AZURE_API_KEY}
      api-base = ${?AZURE_API_BASE}
      deployment-name = ${?AZURE_DEPLOYMENT_NAME}
      api-version = ${?AZURE_API_VERSION}
      api-version = "2024-02-15-preview"
    }

    ollama {
      base-url = ${?OLLAMA_BASE_URL}
      base-url = "http://localhost:11434"
    }
  }
}

# Tracing configuration
tracing {
  mode = ${?TRACING_MODE}
  mode = "none"  # Default: disabled

  langfuse {
    public-key = ${?LANGFUSE_PUBLIC_KEY}
    secret-key = ${?LANGFUSE_SECRET_KEY}
    url = ${?LANGFUSE_URL}
    url = "https://cloud.langfuse.com"
  }
}

# Context window management
context {
  max-messages = 50
  preserve-system-message = true
  pruning-strategy = "oldest-first"  # oldest-first, middle-out, recent-turns
}
```

### Access Configuration in Code

```scala
import org.llm4s.config.Llm4sConfig

object ConfigurationLoader {
  def load(): Unit = {
    // 1. Load configuration at the application boundary
    val configResult = for {
      providerConfig <- Llm4sConfig.provider()
      tracingConfig <- Llm4sConfig.tracing()
      embeddingsConfig <- Llm4sConfig.embeddings()
    } yield (providerConfig, tracingConfig, embeddingsConfig)

    // 2. Pass configuration to your application components
    configResult match {
      case Right((provider, tracing, embeddings)) =>
        // Initialize your application with loaded config
        // val app = new MyApplication(provider, tracing, embeddings)
        // app.start()
        println("Configuration loaded successfully")
      
      case Left(error) =>
        Console.err.println(s"Failed to load configuration: $error")
        System.exit(1)
    }
  }
}
```

---

## Provider-Specific Configuration

### OpenAI

```bash
# Model selection
LLM_MODEL=openai/gpt-4o          # Latest GPT-4o
LLM_MODEL=openai/gpt-4-turbo     # GPT-4 Turbo
LLM_MODEL=openai/gpt-3.5-turbo   # GPT-3.5

# Required
OPENAI_API_KEY=sk-proj-...

# Optional
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_ORGANIZATION=org-...
```

**Available Models:**
- `gpt-4o` - Latest GPT-4 Optimized
- `gpt-4-turbo` - Fast GPT-4
- `gpt-4` - Standard GPT-4
- `gpt-3.5-turbo` - Fast and cheap

### Anthropic

```bash
# Model selection
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
LLM_MODEL=anthropic/claude-opus-4-5-latest

# Required
ANTHROPIC_API_KEY=sk-ant-...

# Optional
ANTHROPIC_BASE_URL=https://api.anthropic.com
ANTHROPIC_VERSION=2023-06-01
```

**Available Models:**
- `claude-sonnet-4-5-latest` - Latest Claude 4.5 Sonnet
- `claude-opus-4-5-latest` - Most capable Claude 4.5 Opus
- `claude-3-haiku-latest` - Fastest

### Azure OpenAI

```bash
# Model selection
LLM_MODEL=azure/gpt-4o

# Required
AZURE_API_KEY=your-azure-key
AZURE_API_BASE=https://your-resource.openai.azure.com
AZURE_DEPLOYMENT_NAME=gpt-4o  # Your deployment name

# Optional
AZURE_API_VERSION=2024-02-15-preview
```

### Ollama (Local Models)

```bash
# Model selection
LLM_MODEL=ollama/llama2
LLM_MODEL=ollama/mistral
LLM_MODEL=ollama/codellama

# Required
OLLAMA_BASE_URL=http://localhost:11434

# No API key needed!
```

**Install Ollama:**

```bash
# Install
curl -fsSL https://ollama.com/install.sh | sh

# Pull a model
ollama pull llama2

# Start server
ollama serve
```

### Google Gemini

```bash
# Model selection
LLM_MODEL=gemini/gemini-2.0-flash
LLM_MODEL=gemini/gemini-1.5-pro
LLM_MODEL=gemini/gemini-1.5-flash

# Required
GOOGLE_API_KEY=your-google-api-key

# Optional
GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta
```

**Available Models:**
- `gemini-2.0-flash` - Latest Gemini 2.0 (fast, multimodal)
- `gemini-2.5-flash` - Gemini 2.5 Flash (thinking model)
- `gemini-1.5-pro` - High capability (1M token context)
- `gemini-1.5-flash` - Fast and efficient (1M token context)

**Get API Key:**
1. Go to [Google AI Studio](https://aistudio.google.com/)
2. Click "Get API Key"
3. Create or select a project
4. Copy the generated API key

---

## Embeddings Configuration

LLM4S supports multiple embedding providers for RAG (Retrieval-Augmented Generation) workflows.

### Unified Format (Recommended)

Use `EMBEDDING_MODEL` with the format `provider/model-name`, similar to `LLM_MODEL`:

```bash
# OpenAI embeddings
EMBEDDING_MODEL=openai/text-embedding-3-small
OPENAI_API_KEY=sk-...  # Same key as LLM

# Voyage AI embeddings
EMBEDDING_MODEL=voyage/voyage-3
VOYAGE_API_KEY=pa-...

# Ollama embeddings (local, no API key needed)
EMBEDDING_MODEL=ollama/nomic-embed-text
```

Default base URLs are used automatically:
- OpenAI: `https://api.openai.com/v1`
- Voyage: `https://api.voyageai.com/v1`
- Ollama: `http://localhost:11434`

Override base URLs if needed:
```bash
EMBEDDING_MODEL=openai/text-embedding-3-small
OPENAI_EMBEDDING_BASE_URL=https://custom.openai.com/v1
```

### Available Models

**OpenAI:**
- `text-embedding-3-small` - Fast, cost-effective (1536 dimensions)
- `text-embedding-3-large` - Higher quality (3072 dimensions)
- `text-embedding-ada-002` - Legacy model (1536 dimensions)

**Voyage AI:**
- `voyage-3` - General purpose
- `voyage-3-large` - Higher quality
- `voyage-code-2` - Code-optimized

**Ollama (local):**
- `nomic-embed-text` - General purpose (768 dimensions)
- `mxbai-embed-large` - Higher quality (1024 dimensions)
- `all-minilm` - Lightweight (384 dimensions)

**Install Ollama embedding model:**

```bash
ollama pull nomic-embed-text
```

### Legacy Format (Still Supported)

The legacy format using `EMBEDDING_PROVIDER` is still supported for backward compatibility:

```bash
# OpenAI
EMBEDDING_PROVIDER=openai
OPENAI_EMBEDDING_BASE_URL=https://api.openai.com/v1
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_API_KEY=sk-...

# Voyage AI
EMBEDDING_PROVIDER=voyage
VOYAGE_EMBEDDING_BASE_URL=https://api.voyageai.com/v1
VOYAGE_EMBEDDING_MODEL=voyage-3
VOYAGE_API_KEY=pa-...

# Ollama
EMBEDDING_PROVIDER=ollama
OLLAMA_EMBEDDING_BASE_URL=http://localhost:11434
OLLAMA_EMBEDDING_MODEL=nomic-embed-text
```

### Using Embeddings in Code

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.agent.memory.LLMEmbeddingService

// Core logic depends on injected service
class RAGService(embeddingService: LLMEmbeddingService) {
  def processDocuments(docs: Seq[String]): Unit = {
    // Use embeddingService...
  }
}

// Configuration boundary
object RAGApplication extends App {
  val startup = for {
    // 1. Load config
    (provider, cfg) <- Llm4sConfig.embeddings()
    model <- Llm4sConfig.textEmbeddingModel()
    
    // 2. Build dependencies
    client <- EmbeddingClient.from(provider, cfg)
    
    // 3. Create service
    service = LLMEmbeddingService(
      client, 
      EmbeddingModelConfig(model.modelName, model.dimensions)
    )
  } yield new RAGService(service)

  startup.fold(
    err => println(s"Startup failed: $err"),
    service => println("RAG Service started successfully")
  )
}
```

### System Properties (Alternative)

When running via sbt, use system properties for reliable configuration:

```bash
# Using unified format (recommended)
sbt -Dllm4s.embeddings.model=ollama/nomic-embed-text \
    "run"

# Or with legacy format
sbt -Dllm4s.embeddings.provider=ollama \
    -Dllm4s.embeddings.ollama.model=nomic-embed-text \
    "run"
```

---

## Tracing Configuration

### Console Tracing (Development)

```bash
TRACING_MODE=console
```

Output appears in stdout:

```
[TRACE] Completion request: UserMessage(What is Scala?)
[TRACE] Token usage: 150 tokens
[TRACE] Response time: 1.2s
```

### Langfuse (Production)

```bash
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=pk-lf-...
LANGFUSE_SECRET_KEY=sk-lf-...
LANGFUSE_URL=https://cloud.langfuse.com
```

Get keys from [Langfuse](https://langfuse.com):

1. Sign up at langfuse.com
2. Create a project
3. Navigate to Settings → API Keys
4. Copy public and secret keys

### Disable Tracing

```bash
TRACING_MODE=none
```

---

## Multi-Provider Setup

Switch between providers without code changes:

```bash
# Development: Use Ollama (free, local)
LLM_MODEL=ollama/llama2
OLLAMA_BASE_URL=http://localhost:11434

# Staging: Use OpenAI GPT-3.5 (cheap)
LLM_MODEL=openai/gpt-3.5-turbo
OPENAI_API_KEY=sk-...

# Production: Use Claude Sonnet (best quality)
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
ANTHROPIC_API_KEY=sk-ant-...
```

Your code remains the same:

```scala
val client = Llm4sConfig.provider().flatMap(LLMConnect.getClient)
```

---

## System Properties

Override any setting via JVM flags:

```bash
sbt -DLLM_MODEL=openai/gpt-4o \
    -DOPENAI_API_KEY=sk-... \
    run
```

Or in `build.sbt`:

```scala
javaOptions ++= Seq(
  s"-DLLM_MODEL=openai/gpt-4o",
  s"-DOPENAI_API_KEY=${sys.env.getOrElse("OPENAI_API_KEY", "")}"
)
```

---

## Environment-Specific Configurations

### Development

Create `.env.dev`:

```bash
LLM_MODEL=ollama/llama2
OLLAMA_BASE_URL=http://localhost:11434
TRACING_MODE=console
```

### Staging

Create `.env.staging`:

```bash
LLM_MODEL=openai/gpt-3.5-turbo
OPENAI_API_KEY=${STAGING_OPENAI_KEY}
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=${STAGING_LANGFUSE_PUBLIC}
LANGFUSE_SECRET_KEY=${STAGING_LANGFUSE_SECRET}
```

### Production

Create `.env.prod`:

```bash
LLM_MODEL=anthropic/claude-sonnet-4-5-latest
ANTHROPIC_API_KEY=${PROD_ANTHROPIC_KEY}
TRACING_MODE=langfuse
LANGFUSE_PUBLIC_KEY=${PROD_LANGFUSE_PUBLIC}
LANGFUSE_SECRET_KEY=${PROD_LANGFUSE_SECRET}
```

Load the appropriate file:

```bash
source .env.prod
sbt run
```

---

## Configuration Best Practices

### ✅ DO

1. **Use environment variables** for secrets (API keys)
2. **Use application.conf** for non-sensitive defaults
3. **Add .env to .gitignore** to prevent committing secrets
4. **Use different configs** for dev/staging/prod
5. **Validate configuration** at startup

```scala
import org.llm4s.config.Llm4sConfig

object ApplicationBoundary {
  def validateAndStart(): Unit = {
    // Fail fast at startup if config is invalid
    Llm4sConfig.provider().fold(
      error => {
        Console.err.println(s"FATAL: Configuration error: $error")
        System.exit(1)
      },
      config => {
        println(s"Configuration loaded for model: ${config.model}")
        // startApplication(config)
      }
    )
  }
}
```

### ❌ DON'T

1. **Don't hardcode API keys** in source code
2. **Don't commit .env files** to version control
3. **Don't use System.getenv() directly** (use Llm4sConfig)
4. **Don't mix production keys** in development
5. **Don't skip validation** - fail fast on bad config

---

## Validation Example

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect

object ValidateConfig extends App {
  println("Validating LLM4S configuration...")

  // Perform validation at the application edge
  val validationResult = for {
    providerConfig <- Llm4sConfig.provider()
    client <- LLMConnect.getClient(providerConfig)
  } yield (providerConfig, client)

  validationResult match {
    case Right((config, client)) =>
      println(s"✅ Model: ${config.model}")
      println(s"✅ Provider: ${config.getClass.getSimpleName}")
      println(s"✅ Client ready: ${client.getClass.getSimpleName}")
      println("✅ Configuration valid!")
      
    case Left(error) =>
      Console.err.println(s"❌ Configuration error: $error")
      System.exit(1)
  }
}
```

---

## Troubleshooting

### Problem: "API key not found"

**Symptoms:**
```
Left(ConfigurationError("Missing API key for provider: openai"))
```

**Root causes:**
1. Environment variable not set
2. Typo in variable name (case-sensitive)
3. `.env` file not loaded in your shell session
4. Using wrong shell (check with `echo $SHELL`)

**Debug steps:**
```bash
# Check if variable is set
echo $OPENAI_API_KEY

# List all LLM4S-related variables
env | grep -E '(LLM_MODEL|OPENAI|ANTHROPIC|AZURE|OLLAMA)'

# Source .env explicitly
source .env
echo $OPENAI_API_KEY  # Should show your key

# Run SBT with explicit env loading
sbt -Dconfig.override_with_env_vars=true run
```

### Problem: "Invalid model format"

**Symptoms:**
```
Left(ConfigurationError("Invalid model format: gpt-4o"))
```

**Fix:** Models must include the provider prefix:
```bash
# ✅ Correct format: provider/model-name
LLM_MODEL=openai/gpt-4o
LLM_MODEL=anthropic/claude-3-5-sonnet-20241022
LLM_MODEL=ollama/llama3.2

# ❌ Wrong - missing provider
LLM_MODEL=gpt-4o
LLM_MODEL=claude-3-5-sonnet
```

### Problem: "Configuration not loading from application.conf"

**Symptoms:** Environment variables work but application.conf is ignored.

**Debug:**
```scala
import com.typesafe.config.ConfigFactory

val config = ConfigFactory.load()
println(config.hasPath("llm4s.provider"))  // Should be true
println(config.getString("llm4s.provider.model"))  // Should show your model
```

**Common causes:**
- application.conf not in `src/main/resources/`
- HOCON syntax error (missing quotes, wrong nesting)
- Environment variable overriding config (env vars have higher precedence)

**Fix:** Validate HOCON syntax:
```bash
# Test config loading
sbt "runMain com.typesafe.config.impl.ConfigImpl"
```

### Problem: "Provider mismatch"

**Symptoms:**
```
import org.llm4s.error.AuthenticationError

Left(AuthenticationError("Invalid API key"))
```

**Root cause:** Model provider doesn't match API key provider.

```bash
# ✅ Check your configuration
echo $LLM_MODEL        # Should be openai/...
echo $OPENAI_API_KEY   # Should start with sk-proj-...

# ❌ Common mismatch:
LLM_MODEL=openai/gpt-4o
ANTHROPIC_API_KEY=sk-ant-...  # ❌ Wrong! Need OPENAI_API_KEY
OPENAI_API_KEY=              # ❌ Wrong - key is empty
```

### Problem: "Rate limiting / 429 errors"

**Symptoms:**
```
import org.llm4s.error.RateLimitError

Left(RateLimitError("Rate limit exceeded"))
```

**Solutions:**

1. **Add retry logic:**
```scala
import scala.concurrent.duration._
import org.llm4s.error.RateLimitError
import org.llm4s.types.Result

// Note: This example uses Thread.sleep for simplicity.
// In production, prefer scala.concurrent or cats-effect for non-blocking delays.
def retryWithBackoff[A](op: => Result[A], maxRetries: Int = 3): Result[A] = {
  (1 to maxRetries).foldLeft(op) { (result, attempt) =>
    result match {
      case Left(_: RateLimitError) if attempt < maxRetries =>
        Thread.sleep(Math.pow(2, attempt).toLong * 1000)  // Exponential backoff
        op
      case other => other
    }
  }
}
```

2. **Use a cheaper model for testing:**
```bash
# Development
LLM_MODEL=openai/gpt-4o-mini  # 60x cheaper

# Production
LLM_MODEL=openai/gpt-4o
```

3. **Switch to Ollama for development:**
```bash
LLM_MODEL=ollama/llama3.2  # Free, no rate limits
```

### Problem: "Slow responses or timeouts"

**Symptoms:** Requests take 30+ seconds or timeout.

**Solutions:**

1. **Increase timeout in application.conf:**
```hocon
llm4s {
  request-timeout = 60 seconds  # Default is 30s
}
```

2. **Use streaming for long responses:**
```scala
// Non-streaming: waits for full response
client.complete(Conversation(Seq(UserMessage("Your query"))))

// Streaming: get tokens as they arrive
client.streamComplete(
  Conversation(Seq(UserMessage("Your query")))
) { chunk =>
  chunk.content.foreach(print)
}
```

3. **Reduce response length:**
```scala
client.complete(
  Conversation(Seq(UserMessage("Your query"))),
  CompletionOptions(maxTokens = Some(500))  // Limit response length
)
```

### Problem: "OutOfMemoryError with embeddings"

**Symptoms:** JVM crashes when processing large document collections.

**Solutions:**

1. **Batch embeddings instead of loading all at once:**
```scala
val documents: List[String] = loadDocuments()
val batchSize = 100

val embeddings = documents.grouped(batchSize).flatMap { batch =>
  embedder.embed(batch) match {
    case Right(emb) => emb
    case Left(err) => 
      println(s"Batch failed: $err")
      List.empty
  }
}.toList
```

2. **Increase JVM heap:**
```bash
export SBT_OPTS="-Xmx4G -Xss4M"
sbt run
```

3. **Use a local embedding model (smaller memory footprint):**
```bash
LLM_EMBEDDING_MODEL=ollama/nomic-embed-text
```

---

## Next Steps

Configuration complete! Now you can:

1. **[Explore features →](next-steps)** - Dive into agents, tools, and more
2. **[Browse examples →](/examples/)** - See configuration in action
3. **[User guide →](../guide/basic-usage)** - Learn core concepts
4. **[Observability →](../guide/observability)** - Set up tracing

---

## Additional Resources

- **[Llm4sConfig API](/api/#explicit-configuration)** - Detailed API documentation
- **[Environment Variables Reference](/reference/env-vars)** - Complete list
- **[Production Guide](/advanced/production)** - Production best practices
- **[Discord Community](https://discord.gg/4uvTPn6qww)** - Get help with configuration

---

**Ready to build?** [Explore what's next →](next-steps)
