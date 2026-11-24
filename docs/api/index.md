---
layout: page
title: API Reference
description: "API reference for LLM4S: the Scaladoc, the main entry points with their signatures, and where stability, compatibility and upgrade notes are."
search_title: "LLM4S API reference and Scaladoc for Scala"
nav_order: 6
---

# API Reference

Where to find the LLM4S API, and the handful of entry points most programs start from.
{: .fs-6 .fw-300 }

## Scaladoc

The **[Scaladoc](/scaladoc/)** documents every public type of every published module, generated from the release the
site shows (v{{ site.data.project.latest_release }}). It is the reference for signatures; this page only points into it.

## Entry points

Every call that can fail returns `org.llm4s.types.Result[A]`, which is `Either[LLMError, A]`: errors are values, not
exceptions.

| What | Entry point | Module |
|---|---|---|
| Load a provider section from `application.conf` | `Llm4sConfig.defaultProvider(): Result[ProviderConfig]`, `Llm4sConfig.provider(name): Result[ProviderConfig]` | `llm4s-core` |
| Model metadata the clients need | `Llm4sConfig.modelRegistryService(): Result[ModelRegistryService]` | `llm4s-core` |
| Build a client | `LLMConnect.getClient(config: ProviderConfig)(using ModelRegistryService, ProviderRegistry): Result[LLMClient]` | `llm4s-core`, plus the provider's module |
| One completion | `LLMClient.complete(conversation: Conversation, options: CompletionOptions = CompletionOptions()): Result[Completion]` | `llm4s-core` |
| A streamed completion | `LLMClient.streamComplete(conversation, options, onChunk: StreamedChunk => Unit): Result[Completion]` | `llm4s-core` |
| An agent with tools | `Agent.builder(id, client)` ... `.build()`, then `agent.run(query): Result[AgentResult]` | `llm4s-agent` |

`ProviderRegistry` has a default given that finds the provider modules on the classpath, so a program brings only a
`ModelRegistryService` into scope:

```scala
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService

val answer = for {
  providerConfig <- Llm4sConfig.defaultProvider()
  registry       <- Llm4sConfig.modelRegistryService()
  given ModelRegistryService = registry
  client         <- LLMConnect.getClient(providerConfig)
  completion     <- client.complete(Conversation(Seq(UserMessage("What is Scala?"))))
} yield completion.content
```

The [First Example](../getting-started/first-example) walks through this, and the [User Guide](../guide/) covers agents,
tools, RAG and the rest.

## Stability and upgrades

| Question | Page |
|---|---|
| Which packages and modules are Frozen, Beta or Experimental | [1.0 Scope](../reference/v1-scope) |
| What an upgrade may break, before and after 1.0 | [Compatibility and Deprecation Policy](../reference/compatibility-policy) |
| How binary compatibility is checked | [API Stability](../reference/api-stability) |
| What changed in each release | [CHANGELOG](https://github.com/llm4s/llm4s/blob/main/CHANGELOG.md) |
| How to move from one release to the next | [Migrating from 0.x to 1.0](../migrations/0x-to-1x) and the [Migration Guide](../reference/migration) |
| Which providers support what | [Compatibility Matrix](../reference/compatibility-matrix) |

## Design principles

- **Errors are values.** `Result[A]`, never an exception for an expected failure.
- **Configuration at the edge.** `Llm4sConfig` reads the application's `application.conf`; library code takes typed
  settings and reads no environment variable itself ([Configuration](../getting-started/configuration)).
- **Typed values.** `ModelName`, `ApiKey` (a `Secret`, which never prints its value) and the other newtypes in
  `org.llm4s.types` keep strings of different meaning apart.
- **One client interface.** `LLMClient` is the same for every provider; a provider is a module found on the
  classpath ([Writing a provider](../guide/writing-a-provider)).
- **Growth-safe data types.** Options and results such as `CompletionOptions` and `Completion` are built with
  `apply` and changed with `with*` setters, so a new field does not break your code.
