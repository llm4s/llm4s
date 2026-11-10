---
layout: page
title: Call OpenAI, Anthropic, Gemini or Ollama from Scala
parent: Scala AI
nav_order: 5
description: "Call OpenAI, Anthropic, Google Gemini, Ollama and other LLM providers from Scala through one LLMClient, choosing the provider in configuration, not code."
search_title: "Call OpenAI, Anthropic, Gemini or Ollama from Scala"
---

# Call OpenAI, Anthropic, Gemini or Ollama from Scala
{: .no_toc }

With LLM4S, your Scala code calls every provider through one type, `LLMClient`: OpenAI, Anthropic, Google Gemini,
Ollama and the others listed below. Which provider and model a client uses is configuration, so switching from one to
another changes a line of `application.conf` and a dependency, not your code.
{: .fs-6 .fw-300 }

1. TOC
{:toc}

## Choose the provider in configuration

Each provider you use is a named section of your `application.conf`, and `provider` names the default:

```hocon
llm4s {
  providers {
    provider = "openai-main"

    openai-main {                   # key from OPENAI_API_KEY
      provider = "openai"
      model    = "gpt-4o-mini"
    }

    claude {                        # key from ANTHROPIC_API_KEY
      provider = "anthropic"
      model    = "claude-sonnet-4-20250514"
    }

    ollama-local {                  # no key: a model on your machine
      provider = "ollama"
      model    = "llama3.2"
      baseUrl  = "http://localhost:11434"
    }
  }
}
```

Each provider module reads its vendor's usual environment variable for the API key, so a section needs only
`provider` and `model`. Then one function builds a client for any section:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.Result

def clientFor(section: String): Result[LLMClient] = for {
  providerConfig  <- Llm4sConfig.provider(section)
  registryService <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registryService
  client <- LLMConnect.getClient(providerConfig)
} yield client
```

`clientFor("claude")` and `clientFor("ollama-local")` return the same type, and the rest of your program
(`client.complete(...)`, `client.streamComplete(...)`, tools, agents, RAG) does not change. `Llm4sConfig.defaultProvider()`
loads the section `provider` names. The [configuration guide](../getting-started/configuration) lists every key, and
[providers](../guide/providers) has each provider's setup.

Each provider is its own module: add the ones you call, at the same version as `llm4s-core`.

```scala
libraryDependencies ++= Seq(
  "org.llm4s" %% "llm4s-core"      % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-openai"    % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-anthropic" % "{{ site.data.project.latest_release }}",
  "org.llm4s" %% "llm4s-ollama"    % "{{ site.data.project.latest_release }}"
)
```

## Supported providers

This list is generated from the code: `sbt generateCompatibilityMatrix` reads the provider modules of the repository,
and CI fails when the committed list is out of date, so a new provider module appears here with no edit to this page.
The [compatibility matrix](../reference/compatibility-matrix) has the full table (API keys, workload identity, the
live test suites).

| `provider` id | Aliases | Chat module | Streaming | Tool calling | Embeddings module |
|---|---|---|---|---|---|
{% for p in site.data.capabilities.providers %}| `{{ p.id }}` | {{ p.aliases | join: ", " }} | {% if p.chat %}`{{ p.chat_artifact }}`{% else %}-{% endif %} | {% if p.chat %}{% if p.streaming %}yes{% else %}no{% endif %}{% else %}-{% endif %} | {% if p.chat %}{% if p.tool_calling %}yes{% else %}no{% endif %}{% else %}-{% endif %} | {% if p.embeddings %}`{{ p.embedding_artifact }}`{% else %}-{% endif %} |
{% endfor %}

Any server that speaks OpenAI's chat-completions API, such as vLLM, LM Studio or a gateway, also works through the
`openai-compatible` id with configuration alone ([recipes](../guide/providers#openai-compatible-endpoints)). A vendor
that is missing can be added as your own module: see [writing a provider](../guide/writing-a-provider).

## Several providers at once

Because every client has the same type, using two providers is building two clients. The
[fallback recipe](../examples/cookbook/fallback) retries a provider with `ReliableClient` and asks a second provider
when the first still fails.
