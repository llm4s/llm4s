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

LLM4S supports these LLM providers, plus any endpoint that speaks the OpenAI chat-completions API:

| Provider | Type | Best For | Setup |
|----------|------|----------|-------|
| **OpenAI** | Cloud | GPT-4, o1 reasoning, most popular | Medium |
| **Anthropic** | Cloud | Claude Opus, best for reasoning | Medium |
| **Google Gemini** | Cloud | Free tier, Gemini 2.0 models | Medium |
| **Azure OpenAI** | Cloud Enterprise | Enterprise deployments, VPC isolation | Hard |
| **DeepSeek** | Cloud | Cost-effective, reasoning models | Easy |
| **OpenRouter** | Cloud gateway | Many vendors' models behind one key | Easy |
| **Z.ai** | Cloud | GLM models | Easy |
| **OpenAI-compatible** | Cloud or local | Groq, Together, Fireworks, xAI, NVIDIA NIM, OrcaRouter; vLLM, LM Studio, llama.cpp - config only ([recipes](#openai-compatible-endpoints)) | Easy |
| **Mistral** | Cloud | Mistral and Magistral models | Easy |
| **Cohere** | Cloud | Command models, RAG | Easy |
| **Ollama** | Local | Private, no API key, offline | Easy |

---

## Provider Selection

### How It Works

LLM4S resolves the named provider you select as the default:

```bash
# Pick one of your configured named providers
LLM4S_PROVIDER=openai-main       # Uses your OpenAI named provider
LLM4S_PROVIDER=anthropic-main    # Uses your Anthropic named provider
LLM4S_PROVIDER=ollama-local      # Uses your Ollama named provider
```

### Available Models

See [MODEL_METADATA.md](/MODEL_METADATA.md) for the complete model list. Quick reference:

**OpenAI:** `gpt-4o`, `gpt-4-turbo`, `gpt-3.5-turbo`

**Anthropic:** `claude-opus-4-6`, `claude-sonnet-4-5-latest`, `claude-haiku-3-5`

**Google Gemini:** `gemini-2.0-flash`, `gemini-1.5-pro`, `gemini-1.5-flash`

**DeepSeek:** `deepseek-chat`, `deepseek-reasoner`

**Mistral:** `mistral-large-latest`, `mistral-small-latest`, `magistral-medium-latest`

**Cohere:** `command-a-03-2025`

**Ollama:** `mistral`, `llama2`, `neural-chat`, `nomic-embed-text` (100+ models)

---

## OpenAI

From the release after `0.4.1`, OpenAI support - chat, embeddings, and the Azure OpenAI and
Requesty providers that share its client - ships in its own artifact, alongside `llm4s-core`;
adding it registers the providers:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`. See the
[installation guide](../getting-started/installation.md#for-openai-azure-openai-and-requesty).

### Setup

1. **Get an API key** from [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
2. **Set environment variables:**

```bash
export LLM4S_PROVIDER=openai-main
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
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model = "gpt-4o"
      apiKey = ${?OPENAI_API_KEY}
      baseUrl = "https://api.openai.com/v1"
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

From the release after `0.4.1`, Anthropic support ships in its own artifact, alongside
`llm4s-core`; adding it registers the provider:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-anthropic" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`. See the
[installation guide](../getting-started/installation.md#for-anthropic).

### Setup

1. **Get an API key** from [console.anthropic.com](https://console.anthropic.com/account/keys)
2. **Set environment variables:**

```bash
export LLM4S_PROVIDER=anthropic-main
export ANTHROPIC_API_KEY=sk-ant-...
```

3. **(Optional) Custom API base URL:**

```bash
export ANTHROPIC_BASE_URL=https://api.anthropic.com
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "anthropic-main"

    anthropic-main {
      provider = "anthropic"
      model = "claude-opus-4-6"
      apiKey = ${?ANTHROPIC_API_KEY}
      baseUrl = "https://api.anthropic.com"
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

From the release after `0.4.1`, Gemini (and Vertex AI) support ships in its own artifact,
alongside `llm4s-core`; adding it registers both providers:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-gemini" % llm4sVersion
```

In `0.4.1` and earlier they are part of `llm4s-core`. See the
[installation guide](../getting-started/installation.md#for-gemini-and-vertex-ai).

### Setup

1. **Get an API key** from [aistudio.google.com/apikey](https://aistudio.google.com/apikey)
   - Free tier available (60 requests per minute)
2. **Set environment variables:**

```bash
export LLM4S_PROVIDER=gemini-main
export GOOGLE_API_KEY=your-api-key
```

3. **(Optional) Custom API base URL:**

```bash
export GEMINI_BASE_URL=https://generativelanguage.googleapis.com/v1beta
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "gemini-main"

    gemini-main {
      provider = "gemini"
      model = "gemini-2.0-flash"
      apiKey = ${?GOOGLE_API_KEY}
      baseUrl = "https://generativelanguage.googleapis.com/v1beta"
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

Azure OpenAI ships in `llm4s-openai` with OpenAI itself, from the release after `0.4.1`; see
[OpenAI](#openai) above. It runs on OpenAI's Java SDK (`com.openai:openai-java`), which covers
Azure too: requests go to `<endpoint>/openai/deployments/<deployment>/chat/completions` with an
`api-key` header and the `api-version` query parameter. `apiVersion` accepts the wire form
(`2024-10-21`) or the constant name (`V2024_10_21`); an endpoint ending in `/openai/v1` uses
Azure's unified v1 API.

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

From the release after `0.4.1`, DeepSeek ships in `llm4s-openai-compatible`, with Z.ai,
OpenRouter and the generic [OpenAI-compatible](#openai-compatible-endpoints) provider; adding it
registers them:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`. See the
[installation guide](../getting-started/installation.md#for-deepseek-zai-openrouter-mistral-cohere-and-any-openai-compatible-endpoint).

### Setup

1. **Get API key** from [platform.deepseek.com](https://platform.deepseek.com/api_keys)
2. **Set environment variables:**

```bash
export DEEPSEEK_API_KEY=sk-...
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "deepseek-main"

    deepseek-main {
      provider = "deepseek"
      model = "deepseek-chat"
      apiKey = ${?DEEPSEEK_API_KEY}
      # baseUrl defaults to https://api.deepseek.com
    }
  }
}
```

### Available Models

- **Chat:** `deepseek-chat` (best for general use)
- **Reasoning:** `deepseek-reasoner` (extended thinking). Its chain of thought
  (`reasoning_content`) is returned as `Completion.thinking`, and streamed as thinking deltas.

### Costs

Very competitive: ~$0.14-$0.28 per 1M input tokens

### Tips

- Excellent cost/performance ratio
- Reasoning model rivals GPT-4o
- Good for translations and multilingual tasks
- Supports very long contexts

---

## OpenRouter and Z.ai

Both ship in `llm4s-openai-compatible` from the release after `0.4.1`, as DeepSeek does.

```hocon
llm4s {
  providers {
    openrouter-main {
      provider = "openrouter"
      model = "anthropic/claude-3.5-sonnet"
      apiKey = ${?OPENROUTER_API_KEY}
      # baseUrl defaults to https://openrouter.ai/api/v1
    }

    zai-main {
      provider = "zai"
      model = "GLM-4.7"
      apiKey = ${?ZAI_API_KEY}
      # baseUrl defaults to https://api.z.ai/api/paas/v4
    }
  }
}
```

OpenRouter maps `CompletionOptions.reasoning` onto the underlying model: a thinking budget for
Claude models, `reasoning_effort` for OpenAI o-series models, nothing for the rest.

---

## OpenAI-compatible endpoints

Any server that speaks the OpenAI `/chat/completions` API works with
`provider = "openai-compatible"` - hosted APIs such as Groq, Together, Fireworks, xAI or NVIDIA
NIM, local servers such as vLLM, LM Studio or llama.cpp, or an internal gateway. It needs no code
and no module of its own, only `llm4s-openai-compatible`, which ships in the release after `0.4.1`
(see the [installation guide](../getting-started/installation.md#for-deepseek-zai-openrouter-mistral-cohere-and-any-openai-compatible-endpoint)):

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

Each named section is one endpoint, so several can sit side by side. The
[recipes](#hosted-apis) below are sections you can paste into your `application.conf` under
`llm4s.providers`.

### Configuration keys

| Key | Required | Meaning |
|-----|----------|---------|
| `baseUrl` | yes | Requests go to `<baseUrl>/chat/completions`, and model listing to `<baseUrl>/models`. A trailing `/` is dropped |
| `model` | yes | Sent as-is in every request |
| `apiKey` | no | Sent as `Authorization: Bearer <key>`; with none, no `Authorization` header is sent |
| `contextWindow` | no | The model's context window. Default 8192, which is deliberately small: set the real value |
| `reserveCompletion` | no | Tokens held back for the reply. Default 2048, or a quarter of a smaller window |
| `headers` | no | Extra headers sent on every request; values are redacted when the config is printed |

`baseUrl` is everything before `/chat/completions` in the vendor's endpoint URL. For most vendors
that is the host plus `/v1`, but some add a prefix - Groq's is `/openai/v1`, Fireworks'
`/inference/v1` - and Perplexity's Sonar API has no `/v1` at all. Nothing is
known about the model up front, so set `contextWindow` from the vendor's model page; the recipes
set it only where the vendor publishes one.

### Selecting it, and environment variables

`Llm4sConfig` resolves providers from named sections only - for every provider, not just this
one - and reads no provider's environment variables and no `LLM_MODEL`. Every value lives in the
named section, and you bind environment variables to it with HOCON substitutions, under names
you choose. The recipes use each vendor's own documented variable name, such as
`GROQ_API_KEY`:

```hocon
llm4s {
  providers {
    # the default named provider; LLM4S_PROVIDER overrides it when set
    provider = "groq-main"
    provider = ${?LLM4S_PROVIDER}

    groq-main {
      provider = "openai-compatible"
      baseUrl = "https://api.groq.com/openai/v1"
      model = "openai/gpt-oss-120b"
      model = ${?GROQ_MODEL}          # optional override
      apiKey = ${?GROQ_API_KEY}
      contextWindow = 131072
    }
  }
}
```

`${?VAR}` leaves the key unset when the variable is missing. For `apiKey` that is not an error
when the config loads, because the key is optional: the request goes out without an
`Authorization` header and fails with a 401 (see [Troubleshooting](#troubleshooting-openai-compatible-endpoints)).

For an endpoint chosen entirely from the environment, the conventional names are
`OPENAI_COMPATIBLE_BASE_URL` (required: the generic provider has no default endpoint) and
`OPENAI_COMPATIBLE_API_KEY` (optional), which `OpenAICompatibleConfigKeys` names:

```hocon
llm4s.providers {
  provider = "compatible-env"
  compatible-env {
    provider = "openai-compatible"
    baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}
    model = ${?OPENAI_COMPATIBLE_MODEL}
    apiKey = ${?OPENAI_COMPATIBLE_API_KEY}
  }
}
```

With `OPENAI_COMPATIBLE_BASE_URL` or the model variable unset, that section fails to load, so
keep it in the `application.conf` of deployments that set them.

The `LLM_MODEL=openai-compatible/<model>` shorthand is read, as `LLM_MODEL=deepseek/<model>` and
the other providers' are, by the tools that read `LLM_MODEL` themselves: the chat-tui sample,
which then takes the endpoint from `OPENAI_COMPATIBLE_BASE_URL` and the key from
`OPENAI_COMPATIBLE_API_KEY`, and the config-policy env check, which checks
`OPENAI_COMPATIBLE_BASE_URL` rather than `OPENAI_BASE_URL` as the endpoint. Only the first `/`
separates the provider, so `LLM_MODEL=openai-compatible/openai/gpt-oss-120b` names Groq's
`openai/gpt-oss-120b`.

Then load it by name, or as the default:

```scala
val groq: Result[ProviderConfig]  = Llm4sConfig.provider("groq-main")
val dflt: Result[ProviderConfig]  = Llm4sConfig.defaultProvider()
```

### What the generic path does, and does not do

It sends the plain chat-completions format and reads the plain reply: text, **streaming**,
**tool calling** (streamed and not), `response_format` for JSON output, and token usage. Every
request carries `temperature` and `top_p`; `max_tokens` is sent when set, and
`presence_penalty` and `frequency_penalty` only when non-zero. It never sends `stop` or
`tool_choice`. Whether a feature works also depends on the endpoint and the
model: tool calling on a local server usually needs a server flag, as the recipes note.

**Streamed token usage.** A streaming request asks for usage with
`"stream_options": {"include_usage": true}`, which is how OpenAI - and servers that follow it,
such as vLLM, Ollama's `/v1` and Perplexity's Router - are told to report it; usage is read from
whichever event carries it, including a final event with empty `choices`. An endpoint that
rejects unknown fields may refuse the request with a 400 or 422 naming `stream_options`. Turn it
off by building the config with `streamUsage = false`:

```scala
OpenAICompatibleConfig.fromValues(model = "my-model", baseUrl = "https://llm.example/v1", streamUsage = false)
```

A `streamUsage` key in the named section is not read yet: provider-specific keys wait on
[#1215](https://github.com/llm4s/llm4s/issues/1215).

A non-streaming request fails with a timeout after **two minutes** without a response, and a
streaming one after five minutes without one. Both are fixed for now; configurable timeouts are
[#712](https://github.com/llm4s/llm4s/issues/712). A slow local model answering a long prompt
can take longer than two minutes: stream it instead.

What it does **not** do:

- **No reasoning configuration.** `CompletionOptions.reasoning` is ignored; no
  `reasoning_effort` or similar field is sent, so a reasoning model runs at its vendor's default.
- **No reasoning output.** A reply's `reasoning_content` or `reasoning` field is not read, so
  `Completion.thinking` is empty and reasoning tokens are not reported separately.
- **No provider-specific fields.** Anything outside the standard reply - citations, search
  results, annotations - is dropped; `Completion` has no field for it.
- **Text only.** Messages are sent as text.
- **No cost estimate** unless the model name is one llm4s's model registry knows.

A provider that needs any of these gets its own **dialect in `llm4s-openai-compatible`**: an
`OpenAICompatibleDialect` for how it departs from the standard format (reasoning parameters,
where its thinking is, extra response decoding) and a `ProviderDescriptor` to register it, as
DeepSeek, Z.ai, OpenRouter, Mistral and Cohere have.
[Adding an OpenAI-compatible provider](https://github.com/llm4s/llm4s/blob/main/CONTRIBUTING.md#adding-an-openai-compatible-provider-a-dialect)
in the contributing guide walks through the hooks, a worked example, registration, the tests to
write and the docs to update; see also
[Adding a provider](../reference/migration.md#adding-a-provider) for the descriptor.

### Hosted APIs

The values below were checked against each vendor's API documentation in September 2026 (links
under [Provider docs](#provider-docs)). Model catalogues change often: if a model is retired,
pick a current one from the vendor's list and keep the rest of the section.

#### Groq

```hocon
groq-main {
  provider = "openai-compatible"
  baseUrl = "https://api.groq.com/openai/v1"
  model = "openai/gpt-oss-120b"
  apiKey = ${?GROQ_API_KEY}
  contextWindow = 131072
  reserveCompletion = 8192
}
```

- Model ids are Groq's own, including the `openai/` prefix here. `llama-3.3-70b-versatile`,
  which earlier versions of this guide used, was shut down for free and developer tiers on
  16 August 2026.
- `gpt-oss-120b` is a reasoning model. Groq returns its reasoning in a `reasoning` field, which
  is not read, and the effort cannot be set.
- Groq does not support `logprobs`, `logit_bias`, `messages[].name` or `n` > 1; the generic path
  sends none of them. A `temperature` of 0 is converted to 1e-8.

#### Together AI

```hocon
together-main {
  provider = "openai-compatible"
  baseUrl = "https://api.together.ai/v1"
  model = "meta-llama/Llama-3.3-70B-Instruct-Turbo"
  apiKey = ${?TOGETHER_API_KEY}
  contextWindow = 131072
}
```

- Model ids are the `<organisation>/<model>` API strings from Together's serverless model list.
- For a reasoning model on Together, the same reasoning limits apply as for Groq above.

#### Fireworks AI

```hocon
fireworks-main {
  provider = "openai-compatible"
  baseUrl = "https://api.fireworks.ai/inference/v1"
  model = "accounts/fireworks/models/gpt-oss-120b"
  apiKey = ${?FIREWORKS_API_KEY}
  contextWindow = 131072
}
```

- Model ids are full paths, `accounts/fireworks/models/<name>`. Not every model in the catalogue
  is available serverless: check its model page before using it with a plain API key.
- If the prompt plus `max_tokens` exceeds the model's window, Fireworks lowers `max_tokens`
  rather than failing (its default `context_length_exceeded_behavior` is `truncate`).
- Fireworks sends usage on the last chunk of a stream, so streamed token usage is reported.
  `reasoning_content` is not read.

#### xAI (Grok)

```hocon
xai-main {
  provider = "openai-compatible"
  baseUrl = "https://api.x.ai/v1"
  model = "grok-4.7"
  apiKey = ${?XAI_API_KEY}
  contextWindow = 500000
}
```

- xAI offers Chat Completions as a **legacy** endpoint; its new features ship on the Responses
  API, which the generic path does not speak. Chat, streaming and tools work.
- `grok-4.7` is a reasoning model (default effort `high`). Its reasoning output is not read, and
  the effort cannot be set.
- xAI rejects `presencePenalty`, `frequencyPenalty` and `stop` on reasoning models. Leave
  `CompletionOptions.presencePenalty` and `frequencyPenalty` at 0, and llm4s sends neither; it
  never sends `stop`.
- Prompts of 200k tokens or more are billed at xAI's long-context rate. A lower `contextWindow`
  keeps context compression below that line.

#### NVIDIA NIM (hosted)

```hocon
nim-cloud {
  provider = "openai-compatible"
  baseUrl = "https://integrate.api.nvidia.com/v1"
  model = "meta/llama-3.3-70b-instruct"
  apiKey = ${?NVIDIA_API_KEY}
  contextWindow = 128000
}
```

- Model ids are `<publisher>/<model>`, as in NVIDIA's API catalogue. For a NIM you run
  yourself, see [NVIDIA NIM (self-hosted)](#nvidia-nim-self-hosted).

#### Perplexity

{: .warning }
> Perplexity has replaced **Sonar Chat Completions** with its Agent API, and states that Sonar
> "will be supported until September 27, 2026". The Agent API uses the OpenAI **Responses**
> format (`/v1/responses`), which the generic provider does not speak. Check Sonar's status
> before relying on the section below.

Sonar, while it runs:

```hocon
perplexity-sonar {
  provider = "openai-compatible"
  baseUrl = "https://api.perplexity.ai"    # no /v1: requests go to /chat/completions
  model = "sonar-pro"
  apiKey = ${?PERPLEXITY_API_KEY}
  contextWindow = 200000
}
```

- **Citations and search results are not surfaced.** Sonar returns them in top-level
  `citations` and `search_results` fields, and `Completion` has no field for them, so you get
  the answer text - with its `[1]`-style markers - and not the sources they point to.
- Sonar's tool-calling support is undocumented; do not rely on it.

Perplexity's **Router API** speaks Chat Completions and serves open-weight models, without
Sonar's web search. It is in private preview (access by request to Perplexity), and publishes
no context windows, so set `contextWindow` for the model you pick:

```hocon
perplexity-router {
  provider = "openai-compatible"
  baseUrl = "https://api.perplexity.ai/router/v1"
  model = "perplexity/glm-5.3-flash"
  apiKey = ${?PERPLEXITY_API_KEY}
}
```

The Router reports token usage on a stream only when asked with `stream_options`, which the
generic provider sends, so streamed completions carry usage.

#### OrcaRouter

[OrcaRouter](https://www.orcarouter.ai) is an OpenAI-compatible gateway in front of several
vendors' models:

```hocon
orcarouter-main {
  provider = "openai-compatible"
  baseUrl = "https://api.orcarouter.ai/v1"
  model = "openai/gpt-4o-mini"
  apiKey = ${?ORCAROUTER_API_KEY}   # keys start with sk-orca-
}
```

- OrcaRouter documents no environment variable name; `ORCAROUTER_API_KEY` is only this guide's
  choice. It publishes no per-model context window either: set `contextWindow` to the
  upstream model's.
- Model ids are provider-prefixed (`openai/`, `anthropic/`, `google/`, ...). OrcaRouter
  accepts a reasoning effort as a model-name suffix (`-minimal`, `-low`, `-medium`, `-high`,
  `-max`), and since `model` is sent as-is, that is the one way to choose an effort through the
  generic path. The `reasoning_content` it passes through is not read.

### Local servers

A local server usually takes no API key: leave `apiKey` out and no `Authorization` header is
sent. Set it only if you started the server with one. Set `contextWindow` to the context the
server was started with, which may be smaller than the model's maximum.

#### vLLM

```bash
vllm serve NousResearch/Meta-Llama-3-8B-Instruct --dtype auto
```

```hocon
local-vllm {
  provider = "openai-compatible"
  baseUrl = "http://localhost:8000/v1"
  model = "NousResearch/Meta-Llama-3-8B-Instruct"   # --served-model-name, if you set one
  # apiKey = ${?VLLM_API_KEY}                        # only if started with --api-key
}
```

- The served model name defaults to the `--model` argument.
- Tool calling needs `--enable-auto-tool-choice` and a `--tool-call-parser` for the model's
  family; without them the model never calls a tool.

#### LM Studio

Start the server from LM Studio's Developer page (default port 1234), then:

```hocon
lmstudio {
  provider = "openai-compatible"
  baseUrl = "http://localhost:1234/v1"
  model = "<the model identifier LM Studio shows>"
  # apiKey = ${?LM_API_TOKEN}   # only with "Require Authentication" on (LM Studio 0.4.0+)
}
```

- Use the identifier LM Studio shows for the loaded model, or list them with
  `Llm4sConfig.listModels("lmstudio")`.

#### llama.cpp (`llama-server`)

```bash
llama-server -hf <user>/<model>:<tag> --alias local-model -c 16384
```

```hocon
llama-cpp {
  provider = "openai-compatible"
  baseUrl = "http://localhost:8080/v1"
  model = "local-model"          # the --alias
  contextWindow = 16384          # the -c / --ctx-size the server was started with
  # apiKey = ${?LLAMA_API_KEY}   # only if started with --api-key
}
```

- `llama-server` listens on `127.0.0.1:8080` by default. Without `--alias` the model id is the
  model file's path; `--alias` gives it a stable name.
- Tool calling uses the Jinja chat template (`--jinja`, on by default), and some models need a
  `--chat-template-file` override to get a tool-capable template.

#### NVIDIA NIM (self-hosted)

```hocon
nim-local {
  provider = "openai-compatible"
  baseUrl = "http://localhost:8000/v1"
  model = "meta/llama-3.1-8b-instruct"   # the id GET /v1/models reports
  # no apiKey: NVIDIA's examples for a running NIM send none
}
```

- `NGC_API_KEY` is for pulling the container and model, not for requests: do not put it in
  `apiKey`.
- The model id is the `id` from `GET /v1/models`; for a model-free NIM it is
  `NIM_SERVED_MODEL_NAME`.

#### Ollama (`/v1`)

[`llm4s-ollama`](#ollama-local-models) (`provider = "ollama"`) is the first-class route to
Ollama: it uses Ollama's native `/api/chat` API and also provides Ollama embeddings. Use the
generic provider on Ollama's OpenAI-compatible `/v1` endpoint instead when you need **tool
calling** - `llm4s-ollama`'s chat client sends no tools and drops tool messages - or when Ollama
is behind a gateway that exposes only the OpenAI API:

```hocon
ollama-openai {
  provider = "openai-compatible"
  baseUrl = "http://localhost:11434/v1"
  model = "gpt-oss:20b"
  # no apiKey: local Ollama needs none
}
```

- The OpenAI API cannot set Ollama's context size. To change it, create a model from a
  `Modelfile` with `PARAMETER num_ctx <size>`, use that model's name, and set `contextWindow` to
  the same size.
- Ollama's cloud API works the same way, with `baseUrl = "https://ollama.com/v1"` and
  `apiKey = ${?OLLAMA_API_KEY}`.
- A reasoning model's thinking is not read on this route.

### An internal gateway

A gateway that authenticates with its own header rather than a bearer token takes it in
`headers`:

```hocon
internal-gateway {
  provider = "openai-compatible"
  baseUrl = "https://llm-gateway.internal.example/v1"
  model = "gpt-4o-mini"
  headers {
    X-Team = "search"
    X-Gateway-Token = ${?GATEWAY_TOKEN}
  }
}
```

### Config policy

If you gate configs with `llm4s-config-policy`, its `dev` preset allows `openai-compatible` and
its `prod` preset does not: since the provider can point anywhere, a production config naming it
fails with `[allowedProviders] Provider 'openai-compatible' is not allowed` until you allow it.
Allow it explicitly, and pin the models and the endpoint:

```scala
import org.llm4s.configpolicy.{ CatalogEnvironment, ConfigPolicy }

val policy = ConfigPolicy.prodSafeDefaults
  .withAllowedProviders("openai", "anthropic", "azure", "gemini", "deepseek", "openai-compatible")
  .withAllowedModelPatterns(
    "^openai/gpt-4o(-mini)?$", // ...and the other preset patterns you still need
    "^openai-compatible/openai/gpt-oss-120b$"
  )
  .withRequiredBaseUrlPattern(
    CatalogEnvironment.Prod,
    "^https://(api\\.openai\\.com/v1|api\\.groq\\.com/openai/v1)$"
  )
```

- `withAllowedProviders` and `withAllowedModelPatterns` **replace** the preset's lists; repeat
  the entries you want to keep.
- Model patterns match `<provider>/<model>`, so a Groq model is
  `openai-compatible/openai/gpt-oss-120b`. Patterns are unanchored regular expressions: anchor
  them with `^` and `$`, or `openai-compatible/.*` slips through under a looser one.
- The base-URL pattern is **one per environment, checked against every provider's config** in
  that environment, not only `openai-compatible`'s. Name every endpoint you use in it, as the
  alternation above does.
- Both presets cap `contextWindow` at 128000. A recipe above that - Groq, Together or Fireworks
  at 131072, xAI at 500000 - fails the check with `contextWindow ... exceeds 128000`: lower the
  section's `contextWindow`, or raise the cap with `withMaxContextWindow`.

See `modules/config-policy/README.md` for the rest of the module.

### Troubleshooting OpenAI-compatible endpoints

**404 on every request - wrong `baseUrl`.** The client appends `/chat/completions` to `baseUrl`
and nothing else, so `baseUrl` must stop exactly where the vendor's path to
`/chat/completions` begins. Missing `/v1` (`http://localhost:8000` for vLLM) and a doubled one
(`.../v1/v1/chat/completions`, from a `baseUrl` of `.../v1/chat/completions` or with `/v1` added
twice) both return 404. Vendor paths differ: `/openai/v1` for Groq, `/inference/v1` for
Fireworks, no `/v1` at all for Perplexity Sonar. Model listing (`<baseUrl>/models`) fails the
same way, which makes it a quick check.

**401 or 403 - authentication.** Surfaced as an `AuthenticationError`. Usually the key is not
reaching the request: `apiKey = ${?GROQ_API_KEY}` with the variable unset leaves `apiKey` out,
and the request goes without an `Authorization` header. Check the variable is exported in the
process that runs your app. Otherwise the key belongs to another vendor or project, or - for
NVIDIA - is the `NGC_API_KEY` used for pulling containers. For a local server started with
`--api-key`, set the same key as `apiKey`.

**Model not found.** Vendors answer an unknown model with a 404 or a 400. Model ids must match
exactly, prefix included (`openai/gpt-oss-120b` on Groq, `accounts/fireworks/models/...` on
Fireworks), and hosted catalogues retire models regularly. List what the endpoint serves, with
the section's key and headers:

```scala
Llm4sConfig.listModels("groq-main")   // GET <baseUrl>/models
```

**No API key for a local server.** Leave `apiKey` out - do not set a placeholder. OpenAI's SDKs
require some key, which is why vendor examples show `api_key="ollama"` or `"not-used"`; llm4s
does not, and without one it sends no `Authorization` header at all. A 401 from a local server
means it was started with a key (`--api-key` for vLLM and `llama-server`, "Require
Authentication" in LM Studio), and `apiKey` must then match it.

**Missing `baseUrl`.** A section without one fails to load with an error naming `baseUrl`.
`Llm4sConfig` reads no variable for it by itself: set `baseUrl` in the section, from a variable
if you like - `baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}` binds the conventional one. If the
section binds a variable and still fails this way, the variable is not set in the process that
runs your app.

**Streaming fails with a 400 or 422 naming `stream_options`, while `complete` works.** The
endpoint rejects fields it does not know. Build the config with `streamUsage = false` (see
[streamed token usage](#what-the-generic-path-does-and-does-not-do)); the stream then carries
usage only if the server sends it unasked.

**Replies cut short, or context errors.** `contextWindow` defaults to 8192. Set it to the
model's real window, or the server's configured context for a local server.

### Provider docs

The sources for the values above:

- Groq: [OpenAI compatibility](https://console.groq.com/docs/openai),
  [models](https://console.groq.com/docs/models),
  [deprecations](https://console.groq.com/docs/deprecations),
  [reasoning](https://console.groq.com/docs/reasoning)
- Together AI: [OpenAI compatibility](https://docs.together.ai/docs/openai-api-compatibility),
  [serverless models](https://docs.together.ai/docs/serverless-models)
- Fireworks AI: [OpenAI compatibility](https://docs.fireworks.ai/tools-sdks/openai-compatibility),
  [quickstart](https://docs.fireworks.ai/getting-started/quickstart),
  [gpt-oss-120b](https://fireworks.ai/models/fireworks/gpt-oss-120b)
- xAI: [quickstart](https://docs.x.ai/developers/quickstart),
  [Chat Completions (legacy)](https://docs.x.ai/developers/model-capabilities/legacy/chat-completions),
  [models](https://docs.x.ai/developers/models),
  [reasoning](https://docs.x.ai/developers/model-capabilities/text/reasoning)
- NVIDIA NIM: [hosted LLM APIs](https://docs.api.nvidia.com/nim/reference/llm-apis),
  [Llama 3.3 70B Instruct](https://docs.api.nvidia.com/nim/reference/meta-llama-3_3-70b-instruct),
  [self-hosted quickstart](https://docs.nvidia.com/nim/large-language-models/latest/get-started/quickstart.html),
  [self-hosted API reference](https://docs.nvidia.com/nim/large-language-models/latest/reference/api-reference.html)
- Perplexity: [Sonar OpenAI compatibility](https://docs.perplexity.ai/docs/sonar/openai-compatibility),
  [Sonar Pro](https://docs.perplexity.ai/docs/sonar/models/sonar-pro),
  [migrating from Sonar](https://docs.perplexity.ai/docs/agent-api/migrate-from-sonar/overview),
  [Router API](https://docs.perplexity.ai/docs/router/quickstart),
  [Router models](https://docs.perplexity.ai/docs/router/models)
- OrcaRouter: [quickstart](https://docs.orcarouter.ai/getting-started/quickstart),
  [reasoning](https://docs.orcarouter.ai/advanced/reasoning)
- vLLM: [OpenAI-compatible server](https://docs.vllm.ai/en/latest/serving/online_serving/openai_compatible_server/),
  [`vllm serve`](https://docs.vllm.ai/en/latest/cli/serve/),
  [tool calling](https://docs.vllm.ai/en/latest/features/tool_calling/)
- LM Studio: [OpenAI compatibility](https://lmstudio.ai/docs/developer/openai-compat),
  [authentication](https://lmstudio.ai/docs/developer/core/authentication)
- llama.cpp: [`llama-server` README](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md)
- Ollama: [OpenAI compatibility](https://docs.ollama.com/api/openai-compatibility)

---

## Mistral

From the release after `0.4.1`, Mistral ships in `llm4s-openai-compatible`: Mistral's API is
the OpenAI `/v1/chat/completions` format, so it runs on the shared client with a small dialect.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`, and does not stream
([#925](https://github.com/llm4s/llm4s/issues/925)).

### Setup

1. **Get API key** from [console.mistral.ai](https://console.mistral.ai/api-keys)
2. **Set environment variables:**

```bash
export MISTRAL_API_KEY=your-key
```

### Configuration

```hocon
llm4s {
  providers {
    provider = "mistral-main"

    mistral-main {
      provider = "mistral"
      model = "mistral-large-latest"
      apiKey = ${?MISTRAL_API_KEY}
      # baseUrl defaults to https://api.mistral.ai - the API root; requests go to
      # <baseUrl>/v1/chat/completions, and a baseUrl already ending in /v1 is used as it is
    }
  }
}
```

### What it supports

Chat, **streaming** (text, tool calls and token usage), **tool calling**, structured output
(`ResponseFormat.Json` and `ResponseFormat.JsonSchema`), model listing and exchange logging.

- **Tool-call ids.** Mistral accepts only nine-letter-or-digit ids. Its own pass through; any
  other id in the conversation (from another provider, say) is mapped to a stable nine-character
  one, the same for the call and its answer.
- **Reasoning models** (Magistral) return their reasoning as `Completion.thinking`, and stream it
  as thinking deltas.
- `CompletionOptions.reasoning` is not sent: only some Mistral models accept `reasoning_effort`.

---

## Cohere

From the release after `0.4.1`, Cohere ships in `llm4s-openai-compatible` and calls Cohere's
[OpenAI-compatibility API](https://docs.cohere.com/docs/compatibility-api)
(`https://api.cohere.ai/compatibility/v1`) rather than its native v2 `/v2/chat`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-openai-compatible" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`, calls `/v2/chat`, sends text only and does
not stream ([#925](https://github.com/llm4s/llm4s/issues/925)).

### Setup

1. **Get API key** from [dashboard.cohere.com](https://dashboard.cohere.com/api-keys)
2. **Set environment variables:**

```bash
export COHERE_API_KEY=your-key
```

### Configuration

```hocon
llm4s {
  providers {
    provider = "cohere-main"

    cohere-main {
      provider = "cohere"
      model = "command-a-03-2025"
      apiKey = ${?COHERE_API_KEY}
      # baseUrl defaults to https://api.cohere.ai/compatibility/v1
    }
  }
}
```

A `baseUrl` naming Cohere's native API root, as configs written for `0.4.1` do
(`https://api.cohere.com`, with or without `/v1` or `/v2`), is mapped to
`<root>/compatibility/v1` and logged; a proxy root is treated the same way. A `baseUrl` already
ending in `/compatibility/v1` is used as it is.

### What it supports

Chat, **streaming** (text, tool calls and token usage), **tool calling**, structured output and
exchange logging. System messages are sent under the `developer` role, and a JSON schema as
`{"type": "json_object", "schema": ...}`, as Cohere's compatibility API documents.
`CompletionOptions.reasoning` is not sent. Cohere's reranker (`llm4s-rag`) is separate and
unchanged.

### Available Models

`command-a-03-2025` and the other Command models your key can use; see
[Cohere's model list](https://docs.cohere.com/docs/models).

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
val providerConfig = Llm4sConfig.defaultProvider()
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

# Cohere (its OpenAI-compatibility API; a native root is mapped to <root>/compatibility/v1)
export COHERE_BASE_URL=https://api.cohere.ai/compatibility/v1

# DeepSeek
export DEEPSEEK_BASE_URL=https://api.deepseek.com
```

### Via application.conf

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model = "gpt-4o"
      apiKey = ${?OPENAI_API_KEY}
      baseUrl = ${?OPENAI_BASE_URL}
      baseUrl = "https://proxy.example.com/openai"
    }
  }
}
```

---

## Provider Comparison Table

| Feature | OpenAI | Anthropic | Gemini | Azure | DeepSeek | Cohere | Ollama | OpenAI-compatible |
|---------|--------|-----------|--------|-------|----------|--------|--------|-------------------|
| **Setup Difficulty** | Easy | Easy | Easy | Hard | Easy | Easy | Medium | Easy |
| **API Key Required** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ | Hosted ✅, local ❌ |
| **Free Tier** | Limited | Limited | ✅ Generous | ❌ | Limited | Limited | ✅ | Endpoint-specific |
| **Local Option** | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ vLLM, LM Studio, llama.cpp |
| **Context Window** | 128K | 200K | 1M | 128K | 4K-32K | 8K | Model-specific | Set in config (default 8K) |
| **Vision Support** | ✅ | ✅ | ✅ | ✅ | ⚠️ Limited | ❌ | Model-specific | ❌ Text only |
| **Function Calling** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ Limited | ✅ If the endpoint supports it |
| **Reasoning Models** | ✅ o1 | ❌ | ❌ | ✅ (via OpenAI) | ✅ deepseek-reasoner | ❌ | ❌ | ⚠️ Run, but reasoning not configured or read |
| **Enterprise Support** | ✅ | ✅ | ✅ | ✅ | ⚠️ | ✅ | N/A | Endpoint-specific |
| **Cost (Budget)** | Medium | Medium | 🏆 Low | High | 🏆 Very Low | Low | Free | Endpoint-specific |
| **Speed** | Fast | Medium | 🏆 Very Fast | Medium | Fast | Medium | Varies | Endpoint-specific |
| **Reliability** | 🏆 Enterprise | 🏆 Enterprise | Good | 🏆 Enterprise | Good | Good | Local | Endpoint-specific |

### Which Provider Should I Use?

- **Getting started?** → Try **Gemini** (free tier) or **Ollama** (local)
- **Production API?** → **OpenAI** (most stable) or **Anthropic** (best reasoning)
- **Cost-conscious?** → **DeepSeek** or **Ollama**
- **Enterprise?** → **Azure OpenAI** or **Anthropic**
- **Private data?** → **Ollama** (runs locally)
- **Groq, Together, Fireworks, xAI, NVIDIA NIM, or a local vLLM, LM Studio or llama.cpp server?** → the generic [**OpenAI-compatible**](#openai-compatible-endpoints) provider
- **Reasoning tasks?** → **Anthropic Claude** or **DeepSeek reasoner**
- **Vision/multimodal?** → **OpenAI GPT-4o** or **Anthropic Claude**

---

## Multiple Providers in One App

Switch providers at runtime:

```scala
for {
  // Get the configured default named provider
  providerConfig <- Llm4sConfig.defaultProvider()
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

