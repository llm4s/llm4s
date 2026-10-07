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
| **Google Vertex AI** | Cloud Enterprise | Gemini models in your own GCP project | Medium |
| **Azure OpenAI** | Cloud Enterprise | Enterprise deployments, VPC isolation | Hard |
| **DeepSeek** | Cloud | Cost-effective, reasoning models | Easy |
| **OpenRouter** | Cloud gateway | Many vendors' models behind one key | Easy |
| **Z.ai** | Cloud | GLM models | Easy |
| **OpenAI-compatible** | Cloud or local | Groq, Together, Fireworks, xAI, NVIDIA NIM, OrcaRouter; vLLM, LM Studio, llama.cpp - config only ([recipes](#openai-compatible-endpoints)) | Easy |
| **Mistral** | Cloud | Mistral and Magistral models | Easy |
| **Cohere** | Cloud | Command models, RAG | Easy |
| **Ollama** | Local | Private, no API key, offline | Easy |
| **IBM watsonx.ai** | Cloud Enterprise | Granite, Llama and Mistral models under IBM governance | Medium |

Missing a vendor? See [Writing a Provider](writing-a-provider.md) to publish your own provider module.

---

## Provider Selection

### How It Works

Each provider you use is a **named section** under `llm4s.providers` in your own
`application.conf`. `llm4s.providers.provider` names the default section, which
`Llm4sConfig.defaultProvider()` loads; `Llm4sConfig.provider("<name>")` loads any section by name.
A section's API key comes from its own `apiKey` when it sets one, and otherwise from its
vendor's shared key, `llm4s.credentials.<provider>.apiKey`, which each provider module binds to
the vendor's conventional variable (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, ...). So with the
variable exported, a section needs only `provider` and `model`. The library reads no `LLM_MODEL`.
See [API keys](../getting-started/configuration.md#api-keys) for the full table and for a
second account.

```hocon
# src/main/resources/application.conf
llm4s {
  providers {
    provider = "openai-main"          # the default: the name of a section below

    openai-main {                     # key from OPENAI_API_KEY
      provider = "openai"
      model    = "gpt-4o-mini"
    }

    claude {                          # key from ANTHROPIC_API_KEY
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
    }
  }
}
```

```scala
import org.llm4s.config.Llm4sConfig

val default = Llm4sConfig.defaultProvider()   // openai-main
val claude  = Llm4sConfig.provider("claude")  // any section, by name
```

Only the section being loaded is validated, so a section with no key available - or whose
provider module is not on the classpath - fails only when it is loaded: with the file above and
just `OPENAI_API_KEY` set, `defaultProvider()` succeeds and `provider("claude")` fails with an
error naming `ANTHROPIC_API_KEY` and `claude`'s `apiKey`. See
[Named provider sections](../getting-started/configuration.md#named-provider-sections) and
[Switching providers](../getting-started/configuration.md#switching-providers) for the full story.

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
2. **Add a named section** to `application.conf` and select it as the default (below).
3. **Export the key** - `llm4s-openai` binds it to `llm4s.credentials.openai.apiKey`, which the
   section uses:

```bash
export OPENAI_API_KEY=sk-proj-...
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider     = "openai"
      model        = "gpt-4o"
      # apiKey     = ${?OPENAI_BATCH_API_KEY}  # optional; default: OPENAI_API_KEY
      organization = ${?OPENAI_ORGANIZATION}   # optional, for multi-workspace accounts
      # baseUrl    = "https://my-proxy.example.com/v1"  # optional; default https://api.openai.com/v1
    }
  }
}
```

Without an `apiKey` line the section uses `OPENAI_API_KEY`. Set `apiKey` for a second account
or a variable name of your own; it wins over the shared key. `OPENAI_ORGANIZATION` is read only
because the section binds it.

### Available Models

- **Latest:** `gpt-4o`, `gpt-4o-mini`
- **Reasoning:** the gpt-5 family (`gpt-5`, `gpt-5-mini`, `gpt-5.1`, ...) and the o-series (`o3`, `o4-mini`)
- **Turbo:** `gpt-4-turbo`
- **Legacy:** `gpt-3.5-turbo`

### Reasoning models

`CompletionOptions.reasoning` is sent as `reasoning_effort` to OpenAI's reasoning models:

```scala
client.complete(conversation, CompletionOptions().withReasoning(ReasoningEffort.High))
```

| `ReasoningEffort` | `reasoning_effort` |
|---|---|
| `Low` / `Medium` / `High` | `low` / `medium` / `high` |
| `None` | not sent: the model's own default applies |

Which models are reasoning models comes from the model registry's `supports_reasoning` flag
(the same metadata llm4s uses for context windows and costs), so the o-series and the gpt-5
family get it, and `gpt-4o`, `gpt-4.1` and older models never do: OpenAI rejects the parameter
for them, so for those models `reasoning` is ignored, as `CompletionOptions` documents. A model
newer than the bundled metadata is recognised by its name (`o<n>...`, `gpt-5` onwards,
`gpt-oss`). For Requesty, a routed id such as `openai/o4-mini` resolves the same way; a
non-OpenAI model behind Requesty is not sent `reasoning_effort`. A fine-tuned model is judged by
the model it was trained from: `ft:o4-mini-2025-04-16:my-org:my-suffix:abc123` is treated as
`o4-mini`, and a fine-tune of `gpt-4o-mini` as `gpt-4o-mini`.

Requests to a reasoning model also follow OpenAI's rules for them: `maxTokens` is sent as
`max_completion_tokens` (which counts reasoning tokens too) rather than `max_tokens`, and
`temperature`, `topP`, `presencePenalty` and `frequencyPenalty` are not sent, since those models
reject non-default values. (`gpt-oss` models keep their sampling parameters.)

The reasoning tokens a response reports (`completion_tokens_details.reasoning_tokens`) are
returned as `TokenUsage.thinkingTokens`, for streamed and non-streamed calls. They are part of
`completionTokens`, not in addition to it. OpenAI's chat-completions API does not return the
reasoning text itself, so `Completion.thinking` stays empty.

### Streaming and token usage

Streaming requests set `stream_options.include_usage`, so OpenAI reports token usage on a final
chunk with no choices, and a streamed `Completion` carries `usage` and `estimatedCost` like a
non-streamed one. The final chunk produces no `StreamedChunk`.

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
2. **Add a named section** to `application.conf` and select it as the default (below).
3. **Export the key** - `llm4s-anthropic` binds it to `llm4s.credentials.anthropic.apiKey`:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "anthropic-main"

    anthropic-main {
      provider = "anthropic"
      model    = "claude-opus-4-6"
      # apiKey = ${?ANTHROPIC_TEAM_API_KEY}   # optional; default: ANTHROPIC_API_KEY
      # baseUrl = "https://my-proxy.example.com"  # optional; default https://api.anthropic.com
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
2. **Add a named section** to `application.conf` and select it as the default (below).
3. **Export the key** - `llm4s-gemini` binds `GOOGLE_API_KEY` and `GEMINI_API_KEY` to
   `llm4s.credentials.gemini.apiKey`, preferring `GOOGLE_API_KEY` when both are set, as Google's
   SDKs do. A `provider = "google"` section uses the same key.

```bash
export GEMINI_API_KEY=your-api-key
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "gemini-main"

    gemini-main {
      provider = "gemini"            # "google" is accepted as an alias
      model    = "gemini-2.0-flash"
      # apiKey = ${?GEMINI_TEAM_API_KEY}   # optional; default: GOOGLE_API_KEY or GEMINI_API_KEY
      # baseUrl = "..."              # optional; default https://generativelanguage.googleapis.com/v1beta
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

## Google Vertex AI

Vertex AI serves the same Gemini models from a GCP project, authenticated with OAuth2 rather
than an API key. It ships in `llm4s-gemini` beside the Gemini API provider, so the dependency
above registers both.

### Setup

1. **Pick a GCP project** with the Vertex AI API enabled, and a region that serves your model.
2. **Authenticate** with Application Default Credentials (`gcloud auth application-default login`),
   or point `apiKey` at a service-account JSON file. Without `apiKey`, credentials come from
   `GOOGLE_APPLICATION_CREDENTIALS`, then `~/.config/gcloud/application_default_credentials.json`,
   then the GCE metadata server.

### Configuration

```hocon
llm4s {
  providers {
    provider = "vertex-main"

    vertex-main {
      provider = "vertexai"          # "vertex" is accepted as an alias
      model    = "gemini-2.0-flash"
      project  = ${?VERTEXAI_PROJECT}
      location = "europe-west4"      # optional
      apiKey   = ${?GOOGLE_APPLICATION_CREDENTIALS}  # optional: path to a credential file
    }
  }
}
```

| Key | Required | Meaning |
|-----|----------|---------|
| `project` | yes | The GCP project ID that owns your Vertex AI resources |
| `location` | no | The GCP region; requests go to `https://<location>-aiplatform.googleapis.com/v1`. Default `us-central1` |
| `apiKey` | no | A path to a service-account credential file - not an API key |

Because Vertex AI has no API key, it has no shared `llm4s.credentials` key: nothing is bound for
it, and `GOOGLE_API_KEY` / `GEMINI_API_KEY` apply to the Gemini API provider only.

`project` and `location` are Vertex AI's own keys ([provider-specific keys](#provider-specific-keys)).
Before [#1215](https://github.com/llm4s/llm4s/issues/1215) Vertex read the project from `endpoint`
and the region from `organization`. Those still work as deprecated aliases, with a warning each
time the config loads; rename them. Setting both a key and a different value under its old name
is an error. See the [migration note](../reference/migration.md#vertex-ai-endpoint-becomes-project-organization-becomes-location).

---

## Provider-specific keys

Every named section shares the built-in fields - `provider`, `model`, `baseUrl`, `apiKey` and
`headers` - and a provider may declare keys of its own. Its section of this guide lists them:

| Provider | Its own keys |
|---|---|
| OpenAI, Requesty, OpenRouter | `organization` |
| Azure OpenAI | `endpoint` (required), `apiVersion` |
| Vertex AI | `project` (required), `location` |
| Generic `openai-compatible` | `contextWindow`, `reserveCompletion`, `registryProvider`, `streamUsage` |

A required one that is missing fails the config with its name, the section and what it means; a
key that is neither built-in nor declared by the section's provider is ignored with a warning
naming it, so a misspelt key - or `organization` in an Anthropic section - shows up in the log
rather than silently doing nothing. `organization`, `endpoint`, `apiVersion`, `contextWindow` and
`reserveCompletion` were built-in fields that every section carried until
[#1133](https://github.com/llm4s/llm4s/issues/1133); the HOCON for the providers that use them is
unchanged.

---

## IBM watsonx.ai

> **Beta, never run against the live service.** `llm4s-watsonx` calls the watsonx.ai chat API
> (`/ml/v1/text/chat` and `/ml/v1/text/chat_stream`), which replaces the "Infer text" endpoints IBM
> [deprecated in February 2026](https://www.ibm.com/docs/en/software-hub/5.3.x?topic=new-watsonxai).
> There is no watsonx account to test with, so nothing here has been checked against the real service. The
> request and response shapes are the ones IBM's public pages report, cross-checked against IBM's own
> open-source clients (the Node.js SDK and IBM's LangChain integration; the pinned sources are listed in
> `WatsonXClient`'s Scaladoc): the token-limit field, the encoding of tool-call arguments, the finish reasons
> and the stream's usage chunk are all shown there. That proves IBM's clients send them, not that the
> service accepts them today. The module is Beta and its API is not frozen. The `@Cloud` suite
> `WatsonXAssumptionProbeSpec` (modules/it) checks each of those details against a real account and prints
> which held; running it is still open in [#1314](https://github.com/llm4s/llm4s/issues/1314). Until it has
> been run, treat tool calling and streaming as unconfirmed.

IBM's enterprise AI platform, serving Granite, Llama and Mistral models. It lives in its own module,
`llm4s-watsonx`; adding the dependency registers the `watsonx` provider.

```scala
libraryDependencies += "org.llm4s" %% "llm4s-watsonx" % llm4sVersion
```

```hocon
llm4s.providers.watsonx-main {
  provider  = "watsonx"
  model     = "ibm/granite-13b-instruct-v2"   # or meta-llama/llama-3-8b-instruct, mistralai/mistral-large
  projectId = ${?WATSONX_PROJECT_ID}          # or spaceId for a deployment space
  # baseUrl = "https://eu-de.ml.cloud.ibm.com" # default https://us-south.ml.cloud.ibm.com
}
```

`WATSONX_API_KEY` supplies the IBM Cloud API key, which the client exchanges for an IAM bearer token
and refreshes before it expires. The conversation goes as structured `messages` with roles
(`system`, `user`, `assistant`, `tool`), so user content is data and cannot forge a turn.

Behaviour to know about:

- **Tools.** `CompletionOptions.tools` are sent as `tools` with `tool_choice_option: "auto"`, a reply's
  `tool_calls` (whole, or streamed as `delta` pieces) become `ToolCall`s, and a tool result goes back as a
  `tool` message with its `tool_call_id`. Streamed calls are reported once, whole, after the last delta.
  Whether a model supports tools depends on the model; a model that does not is answered by the service,
  and that error is returned as the HTTP status maps it. A call the service sends without an `id` is
  given a generated one.
- **Ignored options.** `presencePenalty`, `frequencyPenalty`, `responseFormat`, `reasoning` and
  `budgetTokens` are dropped without error; the chat API documents some of them, and this client does
  not expose them yet. `temperature` is always sent, `maxTokens` (as `max_tokens`) and `topP` (when not
  1.0) when set.
- **Abnormal endings.** A stream that ends without a `finish_reason`, or whose `finish_reason` is
  `error`, `cancelled` or `time_limit`, returns `Left(ServiceError)` naming the reason, not the
  partial text (chunks already passed to `onChunk` were delivered). `stop`, `length`, `tool_calls`
  and unknown reasons are normal stops. `complete` applies the same rule to `choices[0].finish_reason`.
- **URLs and ids.** `baseUrl` and `iamUrl` must be `https` (plain `http` only for `localhost`,
  `127.0.0.1` and `::1`), because the IAM request carries the API key. The API key is trimmed. Set
  `projectId` or `spaceId`, not both.
- **Environment variables.** Only `WATSONX_API_KEY` is bound automatically. `WATSONX_PROJECT_ID` and
  the other variables in the example are just `${?VAR}` substitutions you write in your own config.

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
4. **Add a named section** to `application.conf` (below) and export the key, which
   `llm4s-openai` binds to `llm4s.credentials.azure.apiKey` under the openai SDK's name, and the
   endpoint the section binds:

```bash
export AZURE_OPENAI_API_KEY=your-azure-key
export AZURE_API_BASE=https://your-resource.openai.azure.com
```

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "azure-main"

    azure-main {
      provider   = "azure"
      model      = "gpt-4o"                  # your deployment name
      endpoint   = ${?AZURE_API_BASE}        # required: your resource's endpoint
      # apiKey   = ${?AZURE_TEAM_API_KEY}    # optional; default: AZURE_OPENAI_API_KEY
      apiVersion = "2024-10-21"              # optional; default V2025_01_01_PREVIEW
    }
  }
}
```

| Key | Required | Meaning |
|-----|----------|---------|
| `model` | yes | The deployment name you chose when deploying the model |
| `endpoint` | yes | The Azure OpenAI resource endpoint |
| `apiKey` | no | The resource key; default `AZURE_OPENAI_API_KEY` |
| `apiVersion` | no | Azure API version, wire form or constant name |

### Available Models

Same as OpenAI (via Azure deployment). Choose models when deploying:
- `gpt-4o`
- `gpt-4-turbo`
- `gpt-35-turbo`

### Reasoning deployments

`reasoning_effort` works as for [OpenAI](#reasoning-models), with one difference: the model
llm4s sees is the **deployment name**, which need not name the model behind it. So:

- a deployment named after a model the registry knows (`o4-mini`, `gpt-5-mini`, `gpt-4o`) is
  treated as that model, and one named after an Azure fine-tuned model
  (`o4-mini-2025-04-16.ft-...`) as its base model;
- any other deployment (`prod-reasoner`, or a fine-tune deployed under a name of your own) gets `reasoning_effort` whenever you ask for a
  reasoning effort, since asking is the only sign it is a reasoning model. The request then
  also follows the reasoning-model rules: `max_completion_tokens`, and no sampling parameters.
  Asking for reasoning on such a deployment that is not a reasoning model gets an error from
  Azure; leave `reasoning` unset (or `ReasoningEffort.None`) for it.

### Streamed token usage

Streamed completions report token usage when `apiVersion` is `2024-09-01-preview` or later
(including the default `2025-01-01-preview`, the GA `2024-10-21` and the unified v1 API): only
those versions accept `stream_options`, so it is not sent for older ones, such as
`2024-02-15-preview`, and their streams report no usage.

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
2. **Export the key** - `llm4s-openai-compatible` binds it to `llm4s.credentials.deepseek.apiKey`:

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
      # apiKey defaults to DEEPSEEK_API_KEY
      # baseUrl defaults to https://api.deepseek.com
    }
  }
}
```

### Available Models

- **Chat:** `deepseek-chat` (best for general use)
- **Reasoning:** `deepseek-reasoner` (extended thinking). Its chain of thought
  (`reasoning_content`) is returned on the message as `AssistantMessage.thinking` (and so
  `Completion.thinking`), streamed as thinking deltas, and sent back as `reasoning_content` in later
  requests, which DeepSeek's thinking mode requires once tools are involved
  (see [Thinking in conversation history](#thinking-in-conversation-history)).

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
      # apiKey defaults to OPENROUTER_API_KEY
      # baseUrl defaults to https://openrouter.ai/api/v1
    }

    zai-main {
      provider = "zai"
      model = "GLM-4.7"
      # apiKey defaults to ZAI_API_KEY
      # baseUrl defaults to https://api.z.ai/api/paas/v4
    }
  }
}
```

Both sections are shown together for brevity. Only the section you load is validated, so only
its key - `OPENROUTER_API_KEY` or `ZAI_API_KEY` - needs to be set.

OpenRouter maps `CompletionOptions.reasoning` onto the underlying model: a thinking budget for
Claude models, `reasoning_effort` for OpenAI o-series models, nothing for the rest. A GLM thinking
model's `reasoning_content` on Z.ai, and a model's `reasoning` and `reasoning_details` on OpenRouter,
are returned on the message and sent back (see [Thinking in conversation history](#thinking-in-conversation-history)).

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
| `contextWindow` | no | The model's context window; overrides the model registry. With none, the registry's window for `model` when it has one of at least 8192 (see [where the context window comes from](#where-the-context-window-comes-from)), else 8192, which is deliberately small |
| `reserveCompletion` | no | Tokens held back for the reply. Default 2048, or a quarter of a smaller window |
| `registryProvider` | no | The [model registry](../MODEL_METADATA.md) provider whose entry for `model` gives the context window when `contextWindow` is not set: `groq`, `together_ai`, `fireworks_ai`, `xai`, `perplexity`, ... Inferred from the `baseUrl` host for those five hosted APIs; naming one here switches the inference off |
| `headers` | no | Extra headers sent on every request; values are redacted when the config is printed |
| `streamUsage` | no | Whether a streaming request asks for token usage with `stream_options.include_usage`. Default `true`; set `false` for an endpoint that rejects the field (see [streamed token usage](#what-the-generic-path-does-and-does-not-do)) |

`baseUrl` is everything before `/chat/completions` in the vendor's endpoint URL. For most vendors
that is the host plus `/v1`, but some add a prefix - Groq's is `/openai/v1`, Fireworks'
`/inference/v1` - and Perplexity's Sonar API has no `/v1` at all.



### Where the context window comes from

The generic provider cannot ask an arbitrary endpoint how large the model's window is, so it takes it from
the first of these that has an answer:

1. the section's `contextWindow`;
2. the [model registry](../MODEL_METADATA.md)'s entry for `model` under the `registryProvider` the section names;
3. when the section names none, the registry's entry under the provider inferred from the `baseUrl` host, for
   exactly these hosts: `api.groq.com` (`groq`), `api.together.xyz` and `api.together.ai` (`together_ai`),
   `api.fireworks.ai` (`fireworks_ai`), `api.x.ai` (`xai`) and `api.perplexity.ai` (`perplexity`);
4. 8192.

```hocon
internal-gateway {
  provider = "openai-compatible"
  baseUrl = "https://llm-gateway.internal.example/v1"   # not a known host, so name the registry provider
  model = "llama-3.1-8b-instant"
  registryProvider = "groq"                             # the window comes from groq/llama-3.1-8b-instant
}
```

- **An explicit `registryProvider` is an instruction, not a hint.** It switches the host inference off, so a
  `registryProvider = "perplexity"` on a Groq URL looks in Perplexity's entries only.
- **The lookup is strict.** Only the named provider's own entry for exactly that model id counts. A partial
  name does not match, and neither does another provider's entry for the same name, so a model the registry
  does not list under that provider gets the default, never a neighbour's window.
- **The host is matched exactly, never by substring.** `https://api.groq.com.evil.example/v1`,
  `https://api.groq.com@evil.example/v1` and `https://evil.example/api.groq.com` all get no registry window.
  NVIDIA NIM is not in the table because the registry's three `nvidia_nim` entries carry no chat model with a
  window; `registryProvider = "nvidia_nim"` is still accepted.
- **The registry can only enlarge the window.** A registry window below 8192 is ignored and the default used:
  many entries, most of Fireworks' and Perplexity's older ones among them, carry a 4096 placeholder for input,
  output and total alike (`fireworks_ai/.../minimax-m1-80k` is one), which would cut an 80k-context model's prompt
  budget in half and reject a `reserveCompletion` that fits 8192. A model whose window really is smaller sets
  `contextWindow`. The skipped entry is logged like a miss.
- **Only the window is taken.** `reserveCompletion` keeps its own rule, a quarter of the window up to 2048,
  because a registry entry's output limit can be as large as the whole window.
- **The registry does not know every model.** It has no input limit for the Together recipe's model, and does
  not list xAI's `grok-4.7` or the NVIDIA NIM model at all, so those recipes keep their `contextWindow`. A miss
  under an explicit `registryProvider` is logged as a warning, and under an inferred one at info; a hit is
  logged at info with the entry it used.


### Selecting it, and environment variables

`Llm4sConfig` resolves providers from named sections only, and reads no `LLM_MODEL`. The generic
provider has no vendor, so - unlike `openai` or `anthropic` - it has no shared
`llm4s.credentials` key and no variable is bound for it. Every value lives in the named section,
and you bind environment variables to it with HOCON substitutions, under names you choose. The recipes use each vendor's own documented variable name, such as
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
off with `streamUsage = false` in the section (it takes `true` or `false`, and HOCON's
`yes`/`no`/`on`/`off`; anything else is a config error naming the key and section):

```hocon
strict-gateway {
  provider = "openai-compatible"
  baseUrl = "https://llm.example/v1"
  model = "my-model"
  streamUsage = false
}
```

or, building the config in code, with
`OpenAICompatibleConfig.fromValues(..., streamUsage = false)`.

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
  reserveCompletion = 8192
}
```

- The context window, 131072 for this model, comes from the model registry, because `api.groq.com` is
  a known host. Set `contextWindow` to override it, or for a model the registry does not list.
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

- Keep `contextWindow`: the registry lists this model with no input limit, so without it the provider would
  use 8192. (For a Together model the registry does give a limit for, it is taken automatically.)
- Model ids are the `<organisation>/<model>` API strings from Together's serverless model list.
- For a reasoning model on Together, the same reasoning limits apply as for Groq above.

#### Fireworks AI

```hocon
fireworks-main {
  provider = "openai-compatible"
  baseUrl = "https://api.fireworks.ai/inference/v1"
  model = "accounts/fireworks/models/gpt-oss-120b"
  apiKey = ${?FIREWORKS_API_KEY}
}
```

- The context window, 131072 for this model, comes from the model registry. Set `contextWindow` to
  override it, or for a model the registry does not list.
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

- Keep `contextWindow`: the model registry does not list `grok-4.7`, so without it the provider would use
  8192. (For an xAI model the registry does list, such as `grok-2`, it is taken automatically.)
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

- Keep `contextWindow`: the model registry has no usable chat entry for NVIDIA NIM, and
  `integrate.api.nvidia.com` is not one of the hosts the registry provider is inferred from.
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
}
```

- The context window, 200000 for `sonar-pro`, comes from the model registry.
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
Ollama: it uses Ollama's native `/api/chat` API, supports **tool calling** (tools are sent, tool
calls are read - whole or streamed - and tool results go back as `role: tool`) and also provides
Ollama embeddings. Tool calling needs a model whose Ollama page lists the **tools** capability: for any
other model Ollama answers HTTP 400 (`... does not support tools`), which the client reports as an
`Invalid tools: Ollama model '<model>' does not support tool calling ...` validation error rather than
sending the request again without the tools. Use the generic provider on Ollama's OpenAI-compatible `/v1` endpoint instead
when Ollama is behind a gateway that exposes only the OpenAI API, or when you prefer that wire format:

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
    "openai-compatible",
    "https://api\\.groq\\.com/openai/v1"
  )
  .withMaxContextWindow(CatalogEnvironment.Prod, "openai-compatible", 131072)
```

- `withAllowedProviders` and `withAllowedModelPatterns` **replace** the preset's lists; repeat
  the entries you want to keep.
- The `prod` preset also requires every section whose provider needs a key to set its own
  `apiKey` (rule `ownApiKey`, `withOwnApiKeyRequired`). The generic provider's `apiKey` is
  optional, so it is not checked; the rule is about sections that would otherwise inherit a
  vendor's shared key.
- Model patterns match `<provider>/<model>`, so a Groq model is
  `openai-compatible/openai/gpt-oss-120b`. Model and base-URL patterns are regular expressions
  that must match the **whole** value, so `^` and `$` are optional: `openai/gpt-4o` allows
  neither `openai/gpt-4o-mini` nor a dated snapshot such as `openai/gpt-4o-2024-08-06` (write
  `openai/gpt-4o(-.*)?` for those). End a base-URL pin that allows paths with `/.*`, never a
  bare `.*`: `https://api\.groq\.com.*` also accepts `https://api.groq.com.evil.example/v1`.
- `withRequiredBaseUrlPattern(env, provider, pattern)` pins one provider's endpoint and
  **replaces** the environment-wide pin for that provider. The environment-wide form,
  `withRequiredBaseUrlPattern(env, pattern)`, is checked against every provider without a pin of
  its own, not only `openai-compatible`; if you use it, name every endpoint in it (for example
  `https://(api\.openai\.com|api\.groq\.com/openai)/v1`).
- Context caps work the same way. The `prod` preset caps each of its providers at its current
  models' window (openai and azure 128000, anthropic 200000, gemini 1048576, deepseek 131072) and
  any other provider, `openai-compatible` included, at an environment-wide 1048576; `dev` caps
  everything at 1048576. So Groq, Together or Fireworks at 131072 and xAI at 500000 pass as they
  are. Set a per-provider cap with `withMaxContextWindow(env, provider, max)`, as above, to hold a
  provider tighter, or to allow a model above 1048576. A section over its cap fails with
  `contextWindow ... exceeds <max>`. A window taken from the model registry counts too: a Groq
  section that sets no `contextWindow` resolves to 131072 and is checked at that.
- Provider names in a per-provider pin or cap are canonicalised like provider ids (`google` is
  `gemini`). One that names no registered provider and is not in `allowedProviders` is an
  `[unknownProvider]` violation, so a typo cannot leave a provider unpinned.

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

**Missing `baseUrl`.** A section without one fails to load with
`baseUrl: set it in application.conf under llm4s.providers.<name>.baseUrl (e.g. http://localhost:8000/v1; to
read it from OPENAI_COMPATIBLE_BASE_URL, add baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL} to the section)`.
`Llm4sConfig` reads no variable for it by itself: set `baseUrl` in the section, from a variable
if you like - `baseUrl = ${?OPENAI_COMPATIBLE_BASE_URL}` binds the conventional one. If the
section binds a variable and still fails this way, the variable is not set in the process that
runs your app.

**Streaming fails with a 400 or 422 naming `stream_options`, while `complete` works.** The
endpoint rejects fields it does not know. Set `streamUsage = false` in the section (see
[streamed token usage](#what-the-generic-path-does-and-does-not-do)); the stream then carries
usage only if the server sends it unasked.

**Replies cut short, or context errors.** Without a `contextWindow`, the generic provider uses the model
registry's window when it knows the model (see
[where the context window comes from](#where-the-context-window-comes-from)), and otherwise 8192. Set it to the
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
2. **Export the key** - `llm4s-openai-compatible` binds it to `llm4s.credentials.mistral.apiKey`:

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
      # apiKey defaults to MISTRAL_API_KEY
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
2. **Export the key** - bound to `llm4s.credentials.cohere.apiKey`, which the Cohere reranker in
   `llm4s-rag` shares, so one variable serves both. (Cohere's Python SDK prefers `CO_API_KEY`;
   llm4s reads `COHERE_API_KEY` only, the name it has always used.)

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
      # apiKey defaults to COHERE_API_KEY
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

From the release after `0.4.1`, Ollama support - chat and embeddings - ships in its own
artifact, alongside `llm4s-core`:

```scala
libraryDependencies += "org.llm4s" %% "llm4s-ollama" % llm4sVersion
```

In `0.4.1` and earlier it is part of `llm4s-core`. See the
[installation guide](../getting-started/installation.md#for-ollama-local-models).

### Setup

1. **Install Ollama** from [ollama.ai](https://ollama.ai)
2. **Pull a model:**

```bash
ollama pull mistral        # Downloads model
ollama serve               # Runs on http://localhost:11434
```

3. **Add a named section** to `application.conf` and select it as the default. No API key needed,
   but `baseUrl` is required - Ollama sections have no default (below).

### Configuration

In `application.conf`:

```hocon
llm4s {
  providers {
    provider = "ollama-local"

    ollama-local {
      provider = "ollama"
      model    = "mistral"
      baseUrl  = "http://localhost:11434"   # required
      # baseUrl = ${?OLLAMA_BASE_URL}       # optional: let your own variable override it
    }
  }
}
```

### Structured output

`llm4s-ollama` honours `CompletionOptions.responseFormat` (and so `completeStructured`) through
the `format` field of `/api/chat`, for streaming and non-streaming calls alike:
`ResponseFormat.Json` sends `"format": "json"`, and `ResponseFormat.JsonSchema` sends the schema
object, which needs Ollama 0.5 or later. `JsonSchema.name` and `strict` have no Ollama equivalent
and are ignored. With no `responseFormat`, no `format` field is sent.

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

## Thinking in conversation history

A model's reasoning is returned on the message it produced, as `AssistantMessage.thinking` - a
sequence of `ThinkingBlock`s - and `Completion.thinking` is that message's thinking text. Because it
lives on the message, it stays in the conversation (and in an agent's thread) like the content and
tool calls do, and each client sends it back where its provider takes it:

| Provider | Read from | Sent back as |
|---|---|---|
| Anthropic | `thinking` blocks (each with its `signature`) and `redacted_thinking` | the same blocks, unchanged and first in the assistant turn |
| Bedrock (Converse) | `reasoningContent` (text with `signature`, or `redactedContent`) | the same blocks, unchanged and first in the assistant turn |
| Ollama | `message.thinking` | `thinking` on the assistant message |
| DeepSeek, Z.ai | `reasoning_content` | `reasoning_content` |
| OpenRouter | `reasoning` / `thinking`, and `reasoning_details` | `reasoning`, and `reasoning_details` unchanged (same items, order and fields) |
| Mistral (Magistral) | thinking chunks in `content` | a thinking chunk before the text chunk |
| OpenAI, Azure, Gemini, Vertex AI, Cohere, generic `openai-compatible` | - | not sent: the API has no field for it |

Anthropic and Bedrock require the signed blocks back when a thinking model's turn ends in tool
calls, and reject a thinking block without a signature, so unsigned thinking - from another
provider earlier in the conversation, or set by hand with `withThinking(text)` - is left out of
their requests. The Anthropic client sends tool calls and their results as `tool_use` and
`tool_result` blocks for the same reason: the thinking has to sit in the turn that made the calls.
Both clients send a call only when its result is in the run of tool messages straight after it, as
those APIs require; a result anywhere else (after a user message, say) goes as
`[Tool result for <id>]: ...` text, and its call is left out.

OpenRouter returns `reasoning_details` when the underlying model's reasoning is signed, summarised
or encrypted (Claude, Gemini, OpenAI reasoning models), and
[requires the whole sequence back unchanged](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#preserving-reasoning)
when a conversation continues after tool calls. Each item is kept, with every field, as a
`ThinkingBlock.Opaque("openrouter", json)` block after the reasoning text - streamed items are
joined by `index` first - and only the OpenRouter client sends them back; every other client ignores
opaque blocks that are not its own.

Signed, redacted and opaque thinking is *sealed*: it is valid only in the conversation it was produced in.
[Anthropic](https://platform.claude.com/docs/en/build-with-claude/preserved-thinking) validates a
thinking block against everything sent before it - the top-level system prompt, the tools and every
earlier message - and rejects it (400) once any of those changes; a block must also come back
unchanged, beside the content and tool calls it came with. Bedrock documents its reasoning signature
as a hash of all the messages in the conversation. llm4s enforces both halves with two rules
(`hasSealedThinking` reports which state a message is in):

- **The message itself.** `withContent` and `withToolCalls`, given a changed value, *unseal* the
  thinking: they drop redacted and opaque blocks and signatures and keep the reasoning text. An agent's
  `afterAgent` answer rewrite and a tool-call edit go through those setters, and so do the
  Anthropic and Bedrock clients when they leave out an unpaired call.
- **Everything before it.** When Anthropic, Bedrock or OpenRouter returns sealed thinking, the client records a
  fingerprint of the request on the message (`AssistantMessage.thinkingBinding`): every system
  message, the tools, the response format and every earlier message in order, system messages in
  their positions (OpenRouter sends them inline, so moving one changes what came before), as sent.
  When the message is
  sent again, the client replays its sealed thinking only if the conversation before it still has
  that fingerprint, and sends it unsealed otherwise. The check runs at the point of sending, so it
  covers every rewrite of the history - `ContextPruning` and the agent's context-window middleware,
  `TokenWindow` trimming, the context compressors (`DeterministicCompressor`, `LLMCompressor`,
  `HistoryCompressor`, `ToolOutputCompressor`, `ContextManager`), a handoff's view of the thread,
  memory or RAG context inserted before existing turns, a changed system prompt or tool set, or a
  hand edit - without any of them having to know about thinking.
- **Who produced it.** The fingerprint also covers the provider id and model that produced the
  thinking, and the client checks it against the provider and model it is about to call. A
  signature or reasoning item belongs to its producer, so a conversation produced by one client
  and continued with another - Bedrock then Anthropic, or one OpenRouter model then another - is
  sent unsealed, never with a foreign signature. Every client binds to the provider and model it is
  configured with, never the model a response reports: Anthropic and Bedrock may report an alias's
  resolved snapshot, and a router such as `openrouter/auto` (or OpenRouter's model fallbacks)
  reports the model it chose for that request. The provider that resolved the alias or chose the
  route is the one the thinking goes back to, and OpenRouter
  [requires](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens#preserving-reasoning-blocks)
  the complete `reasoning_details` sequence on a tool-call continuation, so routed turns keep it.
  Changing the configured model unseals every earlier turn. The endpoint is not part of it: a proxy or regional endpoint in
  front of the same provider changes nothing the provider checks.

Earlier turns whose history is unchanged keep their sealed thinking: Anthropic recommends passing
all thinking blocks back, keeps them in context on newer models, and accepts any unbroken run of the
original blocks. A change unseals every turn after it and none before it, so the replayed blocks never
have a gap. Unsealed thinking keeps its text, which Anthropic and Bedrock leave out and other
providers still receive. Sealed thinking with no binding - built by hand with `withThinking` - is
not replayed. To keep signed thinking, append to the conversation and leave what is already there as
it is. Thinking also counts toward token estimates (`ConversationTokenCounter`), since most providers
resend it.

```scala
for {
  first <- client.complete(conversation, options)     // a tool-call turn, with thinking
  results = runTools(first.message.toolCalls)          // your tool execution: Seq[ToolMessage]
  next    = conversation.addMessage(first.message).addMessages(results) // keeps the thinking
  answer <- client.complete(next, options)             // the provider gets it back
} yield answer
```

---

## API Key Management

### Security Best Practices

**Never commit API keys!**

1. **Keep keys in environment variables.** The vendor's own variable is enough - each provider
   module binds it to the vendor's shared key:

```hocon
openai-main { provider = "openai", model = "gpt-4o" }
```

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-...
```

   For a second account, or in production where each section should say which account it bills,
   bind the section's own key: `apiKey = ${?OPENAI_BATCH_API_KEY}`. The config-policy `prod`
   preset flags sections without one. llm4s logs at INFO which path each key came from, never
   the value.

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
// Keys come from the vendor's variable or the section's own ${?VAR} binding - never hardcoded
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

A base URL is the `baseUrl` key of the named section (`endpoint` for Azure OpenAI). No
`<PROVIDER>_BASE_URL` variable is read by itself; bind one if you want the environment to
override the URL:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {
      provider = "openai"
      model    = "gpt-4o"
      baseUrl  = "https://proxy.example.com/openai"
      baseUrl  = ${?OPENAI_BASE_URL}   # optional: overrides the line above when set
    }
  }
}
```

In HOCON the later line wins, and `${?VAR}` with the variable unset leaves the earlier value in
place. Defaults when `baseUrl` is omitted:

| Provider | Default `baseUrl` |
|----------|-------------------|
| `openai` | `https://api.openai.com/v1` |
| `anthropic` | `https://api.anthropic.com` |
| `gemini` | `https://generativelanguage.googleapis.com/v1beta` |
| `deepseek` | `https://api.deepseek.com` |
| `mistral` | `https://api.mistral.ai` |
| `cohere` | `https://api.cohere.ai/compatibility/v1` (a native root is mapped to `<root>/compatibility/v1`) |
| `openrouter` | `https://openrouter.ai/api/v1` |
| `zai` | `https://api.z.ai/api/paas/v4` |
| `requesty` | `https://router.requesty.ai/v1` |
| `ollama` | none - required |
| `openai-compatible` | none - required |

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
| **Function Calling** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ If the model supports it | ✅ If the endpoint supports it |
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

Configure one named section per provider, then load the default or any section by name:

```scala
for {
  registry <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  primaryConfig  <- Llm4sConfig.defaultProvider()      // llm4s.providers.provider
  fallbackConfig <- Llm4sConfig.provider("claude")     // llm4s.providers.claude
  primary  <- LLMConnect.getClient(primaryConfig)
  fallback <- LLMConnect.getClient(fallbackConfig)
} yield primary.complete(conversation).orElse(fallback.complete(conversation))
```

Each section is validated when it is loaded, so here both must have a key available;
a problem in either surfaces as the `Left` of this `for`. See
[Switching providers](../getting-started/configuration.md#switching-providers).

This enables:
- **Fallback logic** - Use OpenAI, fall back to Anthropic
- **A/B testing** - Compare provider outputs
- **Cost optimization** - Use cheapest available provider

---

## Troubleshooting

### "Invalid API Key"

```bash
# Verify the vendor's variable is set
echo $OPENAI_API_KEY
# ...and that the section sets no apiKey of its own, which would win over it.
# The INFO log line "llm4s.providers.<name>: API key from <path>" says which was used.

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

Check the `model` key of the section you selected, and that its `provider` is the one serving
that model (`model = "gpt-4o"` under `provider = "openai"`; no `openai/` prefix):

```bash
# Check available models
# OpenAI: https://platform.openai.com/docs/models
# Anthropic: https://docs.anthropic.com/claude/reference/models
```

### "Rate limit exceeded"

Use provider-specific strategies:
- OpenAI: Wait before retrying, use batching API
- Gemini: Upgrade from free tier
- Ollama: Increase system resources or use GPU

