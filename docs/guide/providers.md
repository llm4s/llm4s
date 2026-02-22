---
layout: page
title: Providers
parent: User Guide
nav_order: 2
---

# Providers Guide
{: .no_toc }

LLM4S supports multiple LLM providers out of the box. Choose your provider, configure it, and start building.
{: .fs-6 .fw-300 }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Supported Providers

LLM4S supports 7 major LLM providers plus local models:

| Provider | Type | Best For | Setup |
|----------|------|----------|-------|
| **OpenAI** | Cloud | GPT-4, o1 reasoning, most popular | Medium |
| **Anthropic** | Cloud | Claude Opus, best for reasoning | Medium |
| **Google Gemini** | Cloud | Free tier, Gemini 2.0 models | Medium |
| **Azure OpenAI** | Cloud Enterprise | Enterprise deployments, VPC isolation | Hard |
| **DeepSeek** | Cloud | Cost-effective, reasoning models | Easy |
| **Cohere** | Cloud | Production RAG, low latency | Easy |
| **Ollama** | Local | Private, no API key, offline | Easy |

---

## Provider Selection

### How It Works

LLM4S automatically selects the provider based on your `LLM_MODEL` setting:

```bash
# Format: <provider>/<model-name>
LLM_MODEL=openai/gpt-4o              # Uses OpenAI
LLM_MODEL=anthropic/claude-opus-4-6  # Uses Anthropic
LLM_MODEL=ollama/mistral              # Uses Ollama
```

### Available Models

See [MODEL_METADATA.md](/MODEL_METADATA.md) for the complete model list. Quick reference:

**OpenAI:** `gpt-4o`, `gpt-4-turbo`, `gpt-3.5-turbo`

**Anthropic:** `claude-opus-4-6`, `claude-sonnet-4-5-latest`, `claude-haiku-3-5`

**Google Gemini:** `gemini-2.0-flash`, `gemini-1.5-pro`, `gemini-1.5-flash`

**DeepSeek:** `deepseek-chat`, `deepseek-reasoner`

**Cohere:** `command-r-plus`, `command-r`

**Ollama:** `mistral`, `llama2`, `neural-chat`, `nomic-embed-text` (100+ models)

---

## OpenAI

### Setup

1. **Get an API key** from [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. **Set environment variables:**

```bash
export LLM_MODEL=openai/gpt-4o
export OPENAI_API_KEY=sk-proj-...
```

3. **(Optional) Organization ID** for multi-workspace accounts:

```bash
export OPENAI_ORGANIZATION=org-...
```

4. **(Optional) Custom API base URL** for Azure or proxy:

```bash
export OPENAI_BASE_URL=https://api.openai.com/v1  # Default
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = "https://api.openai.com/v1"
      organization = ${?OPENAI_ORGANIZATION}
    }
  }
}
```

### Available Models

- **Latest:** `gpt-4o`, `gpt-4o-mini`
- **Reasoning:** `o1-preview`, `o1-mini`
- **Turbo:** `gpt-4-turbo`
- **Legacy:** `gpt-3.5-turbo`

### Costs

See [OpenAI Pricing](https://openai.com/pricing). Generally:
- `gpt-4o`: $2.50-$10 per 1M input tokens
- `gpt-3.5-turbo`: $0.50-$1.50 per 1M input tokens

### Tips

- Use `gpt-4o-mini` for cost-effective applications
- Use `o1` for complex reasoning and math
- Batching API available for high-volume use
- Vision support in `gpt-4o`

---

## Anthropic

### Setup

1. **Get an API key** from [console.anthropic.com](https://console.anthropic.com/account/keys)
2. **Set environment variables:**

```bash
export LLM_MODEL=anthropic/claude-opus-4-6
export ANTHROPIC_API_KEY=sk-ant-...
```

3. **(Optional) Custom API base URL:**

```bash
export ANTHROPIC_BASE_URL=https://api.anthropic.com
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    anthropic {
      api-key = ${?ANTHROPIC_API_KEY}
      base-url = "https://api.anthropic.com"
      version = "2023-06-01"  # API version
    }
  }
}
```

### Available Models

- **Best Quality:** `claude-opus-4-6` (200K context)
- **Balanced:** `claude-sonnet-4-5-latest` (200K context)
- **Fast:** `claude-haiku-3-5` (200K context)

### Costs

- `claude-opus-4-6`: $3-$15 per 1M input tokens
- `claude-sonnet`: $3-$15 per 1M input tokens
- `claude-haiku`: $0.80-$4 per 1M input tokens

Claude models generally score higher on reasoning benchmarks.

### Tips

- All Claude models have 200K context window
- Exceptional at writing and analysis tasks
- Excellent vision capabilities
- Supports prompt caching for repeated queries

---

## Google Gemini

### Setup

1. **Get an API key** from [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
   - Free tier available (60 requests per minute)
2. **Set environment variables:**

```bash
export LLM_MODEL=gemini/gemini-2.0-flash
export GOOGLE_API_KEY=your-api-key
```

3. **(Optional) Custom API base URL:**

```bash
export GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    gemini {
      api-key = ${?GOOGLE_API_KEY}
      base-url = "https://generativelanguage.googleapis.com/v1beta"
    }
  }
}
```

### Available Models

- **Latest:** `gemini-2.0-flash` (1M context)
- **Advanced:** `gemini-1.5-pro` (1M context)
- **Fast:** `gemini-1.5-flash` (1M context)

### Costs

- **Free tier:** 60 requests/minute, 2M free tokens/month
- **Paid:** Pay as you go (~$0.075-$1.50 per 1M input tokens)

Great for cost-conscious projects and high-volume applications.

### Tips

- Free tier perfect for development and testing
- 1M context window for processing large documents
- Very fast inference latency
- Strong code generation capabilities

---

## Azure OpenAI

### Setup

1. **Create resource** in [Azure Portal](https://portal.azure.com)
2. **Deploy model** (e.g., gpt-4o) to get deployment name
3. **Get credentials** from Azure Portal → Keys & Endpoint
4. **Set environment variables:**

```bash
export LLM_MODEL=azure/gpt-4o
export AZURE_API_KEY=your-azure-key
export AZURE_API_BASE=https://your-resource.openai.azure.com
export AZURE_DEPLOYMENT_NAME=gpt-4o
export AZURE_API_VERSION=2024-02-15-preview
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    azure {
      api-key = ${?AZURE_API_KEY}
      api-base = ${?AZURE_API_BASE}
      deployment-name = ${?AZURE_DEPLOYMENT_NAME}
      api-version = "2024-02-15-preview"
    }
  }
}
```

### Available Models

Same as OpenAI (via Azure deployment). Choose models when deploying:
- `gpt-4o`
- `gpt-4-turbo`
- `gpt-35-turbo`

### Costs

Similar to OpenAI but often bundled with enterprise agreements.

### Tips

- Use for VPC-isolated workloads
- Enterprise support available
- Same API as OpenAI (easy migration)
- Reserve capacity for predictable costs

---

## DeepSeek

### Setup

1. **Get API key** from [platform.deepseek.com](https://platform.deepseek.com/api_keys)
2. **Set environment variables:**

```bash
export LLM_MODEL=deepseek/deepseek-chat
export DEEPSEEK_API_KEY=sk-...
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    deepseek {
      api-key = ${?DEEPSEEK_API_KEY}
      base-url = "https://api.deepseek.com"
    }
  }
}
```

### Available Models

- **Chat:** `deepseek-chat` (best for general use)
- **Reasoning:** `deepseek-reasoner` (extended thinking)

### Costs

Very competitive: ~$0.14-$0.28 per 1M input tokens

### Tips

- Excellent cost/performance ratio
- Reasoning model rivals GPT-4o
- Good for translations and multilingual tasks
- Supports very long contexts

---

## Cohere

### Setup

1. **Get API key** from [dashboard.cohere.com](https://dashboard.cohere.com/api-keys)
2. **Set environment variables:**

```bash
export LLM_MODEL=cohere/command-r-plus
export COHERE_API_KEY=your-key
```

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    cohere {
      api-key = ${?COHERE_API_KEY}
      base-url = "https://api.cohere.com"
    }
  }
}
```

### Available Models

- **Best:** `command-r-plus` (advanced reasoning)
- **Standard:** `command-r` (balanced)

### Costs

Competitive for production RAG use cases.

### Tips

- Optimized for retrieval-augmented generation
- Fast token generation for streaming
- Safe and reliable for enterprise use

---

## Ollama (Local Models)

### Setup

1. **Install Ollama** from [ollama.ai](https://ollama.ai)
2. **Pull a model:**

```bash
ollama pull mistral        # Downloads model
ollama serve               # Runs on http://localhost:11434
```

3. **Set environment variables:**

```bash
export LLM_MODEL=ollama/mistral
export OLLAMA_BASE_URL=http://localhost:11434
```

No API key needed!

### Configuration

In `application.conf`:

```hocon
llm {
  providers {
    ollama {
      base-url = "http://localhost:11434"
    }
  }
}
```

### Available Models

100+ models available:

- **Small:** `phi`, `neural-chat` (~4GB)
- **Medium:** `mistral`, `llama2` (~13GB)
- **Large:** `llama2-70b` (~40GB)
- **Specialized:** `neural-chat`, `orca`, `wizard-math`

Run `ollama list` to see installed models.

### Costs

Free! Just compute (CPU or GPU needed).

### Tips

- Perfect for development and testing
- Works offline (no internet needed)
- Use GPU for faster inference
- Ideal for sensitive data (runs locally)

---

## API Key Management

### Security Best Practices

**Never commit API keys!**

1. **Use environment variables:**

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
```

2. **Use .env file** (add to `.gitignore`):

```bash
# .env (NOT committed to git)
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
```

3. **Use CI/CD secrets:**

```yaml
# GitHub Actions
- uses: actions/setup-java@v3
  env:
    OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
```

4. **Rotate keys regularly** on provider dashboards

### Using Keys Safely in Code

**Good:**

```scala
// Keys from env/config - never hardcoded
val providerConfig = Llm4sConfig.provider()
```

**Bad:**

```scala
// ❌ Never do this!
val key = "sk-proj-abc123..."  // Hardcoded
sys.env.get("OPENAI_API_KEY")  // Outside config boundary
```

---

## Base URL Customization

### When to Use Custom Base URLs

- **Reverse proxy** or load balancer
- **VPC endpoint** for security
- **Azure OpenAI** or self-hosted setup
- **Provider migration** (e.g., from OpenAI to similar API)

### Setting Custom URLs

```bash
# OpenAI
export OPENAI_BASE_URL=https://api.openai.com/v1

# Anthropic
export ANTHROPIC_BASE_URL=https://api.anthropic.com

# Azure OpenAI
export AZURE_API_BASE=https://your-resource.openai.azure.com

# Ollama
export OLLAMA_BASE_URL=http://localhost:11434

# Gemini
export GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta

# Cohere
export COHERE_BASE_URL=https://api.cohere.com

# DeepSeek
export DEEPSEEK_BASE_URL=https://api.deepseek.com
```

### Via application.conf

```hocon
llm {
  providers {
    openai {
      api-key = ${?OPENAI_API_KEY}
      base-url = ${?OPENAI_BASE_URL}
      base-url = "https://proxy.example.com/openai"
    }
  }
}
```

---

## Provider Comparison Table

| Feature | OpenAI | Anthropic | Gemini | Azure | DeepSeek | Cohere | Ollama |
|---------|--------|-----------|--------|-------|----------|--------|--------|
| **Setup Difficulty** | Easy | Easy | Easy | Hard | Easy | Easy | Medium |
| **API Key Required** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| **Free Tier** | Limited | Limited | ✅ Generous | ❌ | Limited | Limited | ✅ |
| **Local Option** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ |
| **Context Window** | 128K | 200K | 1M | 128K | 4K-32K | 8K | Model-specific |
| **Vision Support** | ✅ | ✅ | ✅ | ✅ | ⚠️ Limited | ❌ | Model-specific |
| **Function Calling** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ Limited |
| **Reasoning Models** | ✅ o1 | ❌ | ❌ | ✅ (via OpenAI) | ✅ deepseek-reasoner | ❌ | ❌ |
| **Enterprise Support** | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | N/A |
| **Cost (Budget)** | Medium | Medium | 🏆 Low | High | 🏆 Very Low | Low | Free |
| **Speed** | Fast | Medium | 🏆 Very Fast | Medium | Fast | Medium | Varies |
| **Reliability** | 🏆 Enterprise | 🏆 Enterprise | Good | 🏆 Enterprise | Good | Good | Local |

### Which Provider Should I Use?

- **Getting started?** → Try **Gemini** (free tier) or **Ollama** (local)
- **Production API?** → **OpenAI** (most stable) or **Anthropic** (best reasoning)
- **Cost-conscious?** → **DeepSeek** or **Ollama**
- **Enterprise?** → **Azure OpenAI** or **Anthropic**
- **Private data?** → **Ollama** (runs locally)
- **Reasoning tasks?** → **Anthropic Claude** or **DeepSeek reasoner**
- **Vision/multimodal?** → **OpenAI GPT-4o** or **Anthropic Claude**

---

## Multiple Providers in One App

Switch providers at runtime:

```scala
for {
  // Get configured provider from environment
  providerConfig <- Llm4sConfig.provider()
  client <- LLMConnect.getClient(providerConfig)
} yield {
  // Use the available provider
  client.complete(conversation)
}
```

This enables:
- **Fallback logic** - Use OpenAI, fall back to Anthropic
- **A/B testing** - Compare provider outputs
- **Cost optimization** - Use cheapest available provider

---

## Troubleshooting

### "Invalid API Key"

```bash
# Verify key is set
echo $OPENAI_API_KEY

# Check key format (starts with correct prefix)
# OpenAI: sk-proj-* or sk-*
# Anthropic: sk-ant-*
# Gemini: Should be long alphanumeric
```

### "Connection refused"

For local providers (Ollama):

```bash
# Check if Ollama is running
curl http://localhost:11434/api/tags

# Start Ollama
ollama serve
```

### "Model not found"

```bash
# Verify model name and provider
export LLM_MODEL=openai/gpt-4o  # Correct format

# Check available models
# OpenAI: https://platform.openai.com/docs/models
# Anthropic: https://docs.anthropic.com/claude/reference/models
```

### "Rate limit exceeded"

Use provider-specific strategies:
- OpenAI: Wait before retrying, use batching API
- Gemini: Upgrade from free tier
- Ollama: Increase system resources or use GPU


